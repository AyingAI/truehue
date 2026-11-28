package com.truehue.camera;

/** Dual-illuminant DNG calibration with CIE 1960 Planckian Duv guard and Bradford adaptation. */
final class SensorColorMatrix {
    private static final float[] IDENTITY = {1,0,0, 0,1,0, 0,0,1};
    // Inverse sRGB primaries, including Bradford adaptation from D50 PCS to D65.
    private static final float[] XYZ50_TO_SRGB = {
            3.1338561f, -1.6168667f, -0.4906146f,
            -0.9787684f, 1.9161415f, 0.0334540f,
            0.0719453f, -0.2289914f, 1.4052427f
    };
    private static final float[] XYZ65_TO_SRGB = {
            3.2404542f, -1.5371385f, -0.4985314f,
            -0.9692660f, 1.8760108f, 0.0415560f,
            0.0556434f, -0.2040259f, 1.0572252f
    };
    private static final float[] XYZ50_TO_P3 = {
            2.4038183f, -0.9897174f, -0.3975865f,
            -0.8422290f, 1.7988454f, 0.0160549f,
            0.0481867f, -0.0973766f, 1.2735110f
    };
    private static final float[] XYZ65_TO_P3 = {
            2.4931808f, -0.9312655f, -0.4026597f,
            -0.8295031f, 1.7626941f, 0.0236251f,
            0.0358536f, -0.0761890f, 0.9570926f
    };
    private static final float[] LINEAR_SRGB_TO_P3 = multiply(XYZ65_TO_P3, invert(XYZ65_TO_SRGB));
    private static final float[] BRADFORD = {
            0.8951f, 0.2664f, -0.1614f,
            -0.7502f, 1.7135f, 0.0367f,
            0.0389f, -0.0685f, 1.0296f
    };
    private static final float[] BRADFORD_INV = invert(BRADFORD);
    private static final float[] D65_XYZ = {0.95047f, 1.0f, 1.08883f};

    static final class Result {
        final float[] matrix;
        final float[] wbMatrix;
        final float[] neutral;
        final float cct;
        final float duv;

        Result(float[] matrix, float[] wbMatrix, float[] neutral, float cct, float duv) {
            this.matrix = matrix;
            this.wbMatrix = wbMatrix;
            this.neutral = neutral;
            this.cct = cct;
            this.duv = duv;
        }
    }

    private SensorColorMatrix() {}

    static float previewWarmth(float kelvin) {
        if (kelvin < 2000f) return 0f;
        float miredShift = 1_000_000f / 5500f - 1_000_000f / kelvin;
        return Math.max(-0.32f, Math.min(0.32f, miredShift * 0.0014f));
    }

    static boolean isUsable(float[] m) {
        if (m == null || m.length != 9) return false;
        for (float value : m) if (!Float.isFinite(value)) return false;
        return Math.abs(det(m)) > 0.000001f;
    }

    static float[] srgbMatrixToDisplayP3(float[] sensorToSrgb) {
        return multiply(LINEAR_SRGB_TO_P3, sensorToSrgb);
    }

    static float[] fromDng(float[] neutral, float[] cal1, float[] cal2, float[] color1, float[] color2,
                           float[] forward1, float[] forward2, int illuminant1, int illuminant2) {
        if (!isUsable(forward1)) throw new IllegalArgumentException("相机缺少可用的传感器色彩矩阵");
        for (float v : neutral) if (!(v > 0) || !Float.isFinite(v)) {
            throw new IllegalArgumentException("相机返回的中性白点无效");
        }
        float scale = neutral[1];
        neutral = new float[]{neutral[0] / scale, 1, neutral[2] / scale};
        if (cal1 == null) cal1 = IDENTITY;
        if (cal2 == null) cal2 = cal1;
        float t1 = kelvin(illuminant1), t2 = kelvin(illuminant2);
        float weight = 0;
        boolean dual = isUsable(forward2) && isUsable(color1) && isUsable(color2)
                && t1 > 0 && t2 > 0 && t1 != t2;
        if (dual) {
            weight = 0.5f;
            // Solve for the illuminant that maps the measured sensor neutral back to its XYZ
            // white. Interpolate the camera profiles in reciprocal color temperature.
            for (int n = 0; n < 10; n++) {
                float[] xyzToSensor = multiply(mix(cal1, cal2, weight), mix(color1, color2, weight));
                float[] xyz = apply(invert(xyzToSensor), neutral);
                float temperature = cctFromXyz(xyz);
                if (!Float.isFinite(temperature) || temperature <= 0) break;
                float next = (1 / temperature - 1 / t1) / (1 / t2 - 1 / t1);
                next = Math.max(0, Math.min(1, next));
                if (Math.abs(next - weight) < 0.001f) { weight = next; break; }
                weight = (weight + next) * 0.5f;
            }
        }
        float[] sensorToReference = invert(dual ? mix(cal1, cal2, weight) : cal1);
        float[] refNeutral = apply(sensorToReference, neutral);
        for (int row = 0; row < 3; row++) {
            if (!(refNeutral[row] > 0)) throw new IllegalArgumentException("无法应用传感器白点标定");
            for (int col = 0; col < 3; col++) sensorToReference[row*3 + col] /= refNeutral[row];
        }
        float[] forward = dual ? mix(forward1, forward2, weight) : forward1;
        return multiply(XYZ50_TO_SRGB, multiply(forward, sensorToReference));
    }

    static Result fromDngCalibrated(float[] rawNeutral, float[] cal1, float[] cal2,
                                    float[] color1, float[] color2,
                                    float[] forward1, float[] forward2,
                                    int illuminant1, int illuminant2,
                                    boolean guardDuv, float manualKelvin, float manualTint) {
        return fromDngCalibrated(rawNeutral, cal1, cal2, color1, color2, forward1, forward2,
                illuminant1, illuminant2, guardDuv, manualKelvin, manualTint, false, 0f);
    }

    static Result fromDngCalibrated(float[] rawNeutral, float[] cal1, float[] cal2,
                                    float[] color1, float[] color2,
                                    float[] forward1, float[] forward2,
                                    int illuminant1, int illuminant2,
                                    boolean guardDuv, float manualKelvin, float manualTint,
                                    boolean wideGamut, float ambienceRetention) {
        if (rawNeutral == null || rawNeutral.length < 3) {
            throw new IllegalArgumentException("相机返回的中性白点无效");
        }
        for (int i = 0; i < 3; i++) {
            if (!(rawNeutral[i] > 0) || !Float.isFinite(rawNeutral[i])) {
                throw new IllegalArgumentException("相机返回的中性白点无效");
            }
        }
        float scale = rawNeutral[1];
        float[] neutral = new float[]{rawNeutral[0] / scale, 1f, rawNeutral[2] / scale};
        if (!isUsable(cal1)) cal1 = IDENTITY;
        if (!isUsable(cal2)) cal2 = cal1;

        float t1 = kelvin(illuminant1), t2 = kelvin(illuminant2);
        boolean dualColor = isUsable(color1) && isUsable(color2) && t1 > 0 && t2 > 0 && t1 != t2;
        boolean hasColor = isUsable(color1);
        float weight = 0f;
        float cct = 5500f;

        if (manualKelvin >= 2000f) {
            cct = Math.max(2000f, Math.min(15000f, manualKelvin));
            if (dualColor) {
                weight = Math.max(0f, Math.min(1f, (1f / cct - 1f / t1) / (1f / t2 - 1f / t1)));
            }
        } else if (dualColor) {
            weight = 0.5f;
            for (int n = 0; n < 10; n++) {
                float[] xyzToSensor = multiply(mix(cal1, cal2, weight), mix(color1, color2, weight));
                float[] xyz = apply(invert(xyzToSensor), neutral);
                float t = cctFromXyz(xyz);
                if (!Float.isFinite(t) || t <= 0) break;
                cct = Math.max(2000f, Math.min(15000f, t));
                float next = Math.max(0f, Math.min(1f, (1f / cct - 1f / t1) / (1f / t2 - 1f / t1)));
                if (Math.abs(next - weight) < 0.001f) { weight = next; break; }
                weight = (weight + next) * 0.5f;
            }
        } else if (hasColor) {
            float[] xyz = apply(invert(multiply(cal1, color1)), neutral);
            float t = cctFromXyz(xyz);
            if (Float.isFinite(t) && t > 0) cct = Math.max(2000f, Math.min(15000f, t));
        }

        float[] cal = dualColor ? mix(cal1, cal2, weight) : cal1;
        float[] cm = dualColor ? mix(color1, color2, weight) : color1;
        float[] xyzW = null;
        float duv = 0f;

        if (hasColor) {
            float[] xyzToCam = multiply(cal, cm);
            float[] camToXyz = invert(xyzToCam);
            if (manualKelvin >= 2000f) {
                float[] uv0 = locusUv(cct);
                float u = uv0[0];
                float v = uv0[1] + manualTint * 0.008f;
                float[] targetXyz = xyzFromUv(u, v);
                float[] targetCam = apply(xyzToCam, targetXyz);
                if (targetCam[0] > 0 && targetCam[1] > 0 && targetCam[2] > 0) {
                    neutral = new float[]{targetCam[0] / targetCam[1], 1f, targetCam[2] / targetCam[1]};
                    xyzW = targetXyz;
                    duv = manualTint * 0.008f;
                }
            }
            if (xyzW == null) {
                xyzW = apply(camToXyz, neutral);
                if (xyzW[1] > 0) {
                    xyzW = new float[]{xyzW[0] / xyzW[1], 1f, xyzW[2] / xyzW[1]};
                    float[] uv = uvFromXyz(xyzW);
                    float[] uv0 = locusUv(cct);
                    duv = uv[1] - uv0[1];
                    if (guardDuv && manualKelvin < 2000f) {
                        float uFixed = uv[0], vFixed = uv[1];
                        boolean adjust = false;
                        // Clamp strong negative Duv (green cast common on mobile AWB under indoor lighting)
                        if (duv < -0.0015f) {
                            vFixed = uv0[1] - 0.0015f + 0.25f * (duv + 0.0015f);
                            uFixed = uv[0] + 0.30f * (uv0[0] - uv[0]);
                            adjust = true;
                        } else if (duv > 0.0030f) {
                            vFixed = uv0[1] + 0.0030f + 0.35f * (duv - 0.0030f);
                            uFixed = uv[0] + 0.30f * (uv0[0] - uv[0]);
                            adjust = true;
                        }
                        if (adjust) {
                            float[] fixedXyz = xyzFromUv(uFixed, vFixed);
                            float[] fixedCam = apply(xyzToCam, fixedXyz);
                            if (fixedCam[0] > 0 && fixedCam[1] > 0 && fixedCam[2] > 0) {
                                neutral = new float[]{fixedCam[0] / fixedCam[1], 1f, fixedCam[2] / fixedCam[1]};
                                xyzW = fixedXyz;
                            }
                        }
                    }
                }
            }
        }

        // Incomplete chromatic adaptation preserves subtle ambient color warmth/coolness in auto WB.
        float[] xyz65ToRgb = wideGamut ? XYZ65_TO_P3 : XYZ65_TO_SRGB;
        float[] xyz50ToRgb = wideGamut ? XYZ50_TO_P3 : XYZ50_TO_SRGB;
        float[] targetXyz = D65_XYZ;
        float[] targetRgb = {1f, 1f, 1f};
        if (manualKelvin < 2000f && ambienceRetention > 0f) {
            float deltaMired = 0f;
            if (cct < 4700f) {
                float warmWeight = smoothStep((4700f - cct) / 1900f);
                deltaMired = Math.min(24f, ambienceRetention * warmWeight
                        * (1_000_000f / Math.max(2200f, cct) - 1_000_000f / 6504f));
            } else if (cct > 7200f) {
                float coolWeight = smoothStep((cct - 7200f) / 2300f);
                deltaMired = -Math.min(10f, ambienceRetention * 0.5f * coolWeight
                        * (1_000_000f / 6504f - 1_000_000f / Math.min(12000f, cct)));
            }
            if (Math.abs(deltaMired) > 0.25f) {
                float targetCct = 1_000_000f / (1_000_000f / 6504f + deltaMired);
                float[] targetUv = locusUv(targetCct);
                float[] adaptedXyz = xyzFromUv(targetUv[0], targetUv[1]);
                float[] adaptedRgb = apply(xyz65ToRgb, adaptedXyz);
                float lum = wideGamut
                        ? (0.2290036f * adaptedRgb[0] + 0.6917267f * adaptedRgb[1] + 0.0792697f * adaptedRgb[2])
                        : (0.2126f * adaptedRgb[0] + 0.7152f * adaptedRgb[1] + 0.0722f * adaptedRgb[2]);
                if (lum > 1e-4f && adaptedRgb[0] > 0f && adaptedRgb[1] > 0f && adaptedRgb[2] > 0f) {
                    targetXyz = adaptedXyz;
                    targetRgb = new float[]{
                            adaptedRgb[0] / lum,
                            adaptedRgb[1] / lum,
                            adaptedRgb[2] / lum
                    };
                }
            }
        }

        // Prefer dual-illuminant ColorMatrix + Bradford when ForwardMatrix is missing or static across illuminants.
        boolean staticForward = !isUsable(forward1) || !isUsable(forward2) || arraysClose(forward1, forward2, 1e-4f);
        float[] camToRgb;
        if (hasColor && xyzW != null && staticForward) {
            float[] camToXyz = invert(multiply(cal, cm));
            float[] srcCone = apply(BRADFORD, xyzW);
            float[] dstCone = apply(BRADFORD, targetXyz);
            float[] diag = {
                    dstCone[0] / Math.max(1e-5f, srcCone[0]), 0, 0,
                    0, dstCone[1] / Math.max(1e-5f, srcCone[1]), 0,
                    0, 0, dstCone[2] / Math.max(1e-5f, srcCone[2])
            };
            float[] adapt = multiply(BRADFORD_INV, multiply(diag, BRADFORD));
            camToRgb = multiply(xyz65ToRgb, multiply(adapt, camToXyz));
        } else if (isUsable(forward1)) {
            float[] sensorToReference = invert(cal);
            float[] refNeutral = apply(sensorToReference, neutral);
            for (int row = 0; row < 3; row++) {
                if (!(refNeutral[row] > 0)) throw new IllegalArgumentException("无法应用传感器白点标定");
                for (int col = 0; col < 3; col++) sensorToReference[row * 3 + col] /= refNeutral[row];
            }
            boolean dualForward = isUsable(forward2) && dualColor;
            float[] forward = dualForward ? mix(forward1, forward2, weight) : forward1;
            camToRgb = multiply(xyz50ToRgb, multiply(forward, sensorToReference));
        } else {
            throw new IllegalArgumentException("相机缺少可用的传感器色彩矩阵");
        }

        // Guarantee neutral fidelity: camToRgb * neutral == targetRgb (1,1,1 when fully adapted).
        float[] rowOut = apply(camToRgb, neutral);
        for (int r = 0; r < 3; r++) {
            if (rowOut[r] > 1e-4f) {
                float scaleRow = targetRgb[r] / rowOut[r];
                for (int c = 0; c < 3; c++) camToRgb[3 * r + c] *= scaleRow;
            }
        }
        float[] wbMatrix = new float[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                wbMatrix[3 * r + c] = camToRgb[3 * r + c] * neutral[c];
            }
        }
        return new Result(camToRgb, wbMatrix, neutral, cct, duv);
    }

    static float estimateKelvin(float[] rawNeutral, float[] cal1, float[] cal2,
                                float[] color1, float[] color2, int illum1, int illum2) {
        if (rawNeutral == null || rawNeutral.length < 3 || !isUsable(color1)) return 0f;
        for (int i = 0; i < 3; i++) if (!(rawNeutral[i] > 0) || !Float.isFinite(rawNeutral[i])) return 0f;
        float[] neutral = {rawNeutral[0] / rawNeutral[1], 1f, rawNeutral[2] / rawNeutral[1]};
        if (!isUsable(cal1)) cal1 = IDENTITY;
        if (!isUsable(cal2)) cal2 = cal1;
        float t1 = kelvin(illum1), t2 = kelvin(illum2);
        boolean dual = isUsable(color2) && t1 > 0 && t2 > 0 && t1 != t2;
        float weight = dual ? 0.5f : 0f;
        float cct = 0f;
        int steps = dual ? 6 : 1;
        for (int n = 0; n < steps; n++) {
            float[] xyzToSensor = multiply(dual ? mix(cal1, cal2, weight) : cal1,
                    dual ? mix(color1, color2, weight) : color1);
            if (!isUsable(xyzToSensor)) return 0f;
            float[] xyz = apply(invert(xyzToSensor), neutral);
            float t = cctFromXyz(xyz);
            if (!Float.isFinite(t) || t <= 0) break;
            cct = Math.max(2000f, Math.min(15000f, t));
            if (!dual) break;
            float next = Math.max(0f, Math.min(1f, (1f / cct - 1f / t1) / (1f / t2 - 1f / t1)));
            if (Math.abs(next - weight) < 0.005f) break;
            weight = (weight + next) * 0.5f;
        }
        return cct;
    }

    static float[] normalizeCcmRows(float[] ccm) {
        if (!isUsable(ccm)) return ccm;
        float[] out = ccm.clone();
        for (int r = 0; r < 3; r++) {
            float sum = out[3 * r] + out[3 * r + 1] + out[3 * r + 2];
            if (sum > 0.1f) {
                out[3 * r] /= sum;
                out[3 * r + 1] /= sum;
                out[3 * r + 2] /= sum;
            }
        }
        return out;
    }

    private static float cctFromXyz(float[] xyz) {
        float sum = xyz[0] + xyz[1] + xyz[2];
        if (!(sum > 0)) return 0f;
        float x = xyz[0] / sum, y = xyz[1] / sum;
        float denom = 0.1858f - y;
        if (Math.abs(denom) < 1e-5f) return 0f;
        float q = (x - 0.3320f) / denom;
        return 5520.33f + q * (6823.3f + q * (3525f + 449f * q));
    }

    private static float[] uvFromXyz(float[] xyz) {
        float d = xyz[0] + 15f * xyz[1] + 3f * xyz[2];
        if (Math.abs(d) < 1e-6f) return new float[]{0.1978f, 0.3122f};
        return new float[]{4f * xyz[0] / d, 6f * xyz[1] / d};
    }

    private static float[] xyzFromUv(float u, float v) {
        float denom = 2f * u - 8f * v + 4f;
        if (Math.abs(denom) < 1e-5f) return D65_XYZ.clone();
        float x = 3f * u / denom;
        float y = 2f * v / denom;
        if (y <= 1e-4f) return D65_XYZ.clone();
        return new float[]{x / y, 1f, (1f - x - y) / y};
    }

    private static float[] locusUv(float T) {
        double t = Math.max(2000.0, Math.min(15000.0, T));
        double x, y;
        if (t >= 4000.0) {
            if (t <= 7000.0) {
                x = -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063;
            } else {
                x = -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040;
            }
            y = -3.000 * x * x + 2.870 * x - 0.275;
        } else {
            x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
            y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
        }
        return uvFromXyz(new float[]{(float) (x / y), 1f, (float) ((1.0 - x - y) / y)});
    }

    private static boolean arraysClose(float[] a, float[] b, float eps) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > eps) return false;
        }
        return true;
    }

    private static float[] apply(float[] m, float[] v) {
        return new float[]{m[0]*v[0]+m[1]*v[1]+m[2]*v[2], m[3]*v[0]+m[4]*v[1]+m[5]*v[2],
                m[6]*v[0]+m[7]*v[1]+m[8]*v[2]};
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] out = new float[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) out[3*r+c] = a[3*r]*b[c] + a[3*r+1]*b[3+c] + a[3*r+2]*b[6+c];
        }
        return out;
    }

    private static float[] mix(float[] a, float[] b, float weight) {
        float[] out = new float[9];
        for (int i = 0; i < out.length; i++) out[i] = a[i] + (b[i] - a[i]) * weight;
        return out;
    }

    private static float det(float[] m) {
        return m[0]*(m[4]*m[8]-m[5]*m[7]) - m[1]*(m[3]*m[8]-m[5]*m[6]) + m[2]*(m[3]*m[7]-m[4]*m[6]);
    }

    private static float[] invert(float[] m) {
        if (!isUsable(m)) throw new IllegalArgumentException("传感器校色矩阵不可逆");
        float d = det(m);
        return new float[]{(m[4]*m[8]-m[5]*m[7])/d, (m[2]*m[7]-m[1]*m[8])/d, (m[1]*m[5]-m[2]*m[4])/d,
                (m[5]*m[6]-m[3]*m[8])/d, (m[0]*m[8]-m[2]*m[6])/d, (m[2]*m[3]-m[0]*m[5])/d,
                (m[3]*m[7]-m[4]*m[6])/d, (m[1]*m[6]-m[0]*m[7])/d, (m[0]*m[4]-m[1]*m[3])/d};
    }

    private static float smoothStep(float position) {
        float t = Math.max(0f, Math.min(1f, position));
        return t * t * (3f - 2f * t);
    }

    private static float kelvin(int exif) {
        switch (exif) {
            case 3: case 17: return 2856; // Tungsten / standard A
            case 24: return 3200;
            case 2: case 14: return 4200;
            case 15: return 3500;
            case 13: case 23: return 5000;
            case 18: return 4874;
            case 1: case 4: case 9: case 20: return 5500;
            case 10: case 12: case 21: return 6500;
            case 19: return 6774;
            case 11: case 22: return 7500;
            default: return 0;
        }
    }
}
