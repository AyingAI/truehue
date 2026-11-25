package com.truehue.camera;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Develops a 16-bit Bayer plane into sRGB (and optional Ultra HDR gainmap).
 * Supports both the legacy 0.5.1 streaming path and the multi-threaded TrueHue 2.0 optical engine.
 */
final class BayerPipeline {
    private static final int STRIP_HEIGHT = 32;
    private static final int TONE_RESOLUTION = 2048;
    private static final int FILMIC_MAX = 8;
    private static final float[] TONE_GAIN = buildToneGain(false);
    private static final float[] SOFT_GAIN = buildToneGain(true);
    private static final float[] OPTICAL_FILMIC = buildOpticalFilmic();

    static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.min(6, Math.max(2, Runtime.getRuntime().availableProcessors() - 1)),
            job -> {
                Thread t = new Thread(job, "TrueHue-Worker");
                t.setDaemon(true);
                return t;
            });

    interface StripWriter {
        void write(int[] pixels, int x, int y, int width, int height);
    }

    static final class Options {
        boolean natural = true;
        float equivalentMm = 0f;
        int iso = 0;
        float noiseSlope = 0f;
        float noiseOffset = 0f;
        float effectiveFrames = 1f;
        float userEvBias = 0f;
        PhotoFilter filter = PhotoFilter.ORIGINAL;
        boolean buildGainmap = false;
    }

    static final class GainmapData {
        final byte[] pixels;
        final int width, height;
        final float maxRatio;

        GainmapData(byte[] pixels, int width, int height, float maxRatio) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.maxRatio = maxRatio;
        }
    }

    /** A preview-sized Bayer mosaic, averaging four samples from the matching CFA site. */
    static ShortBuffer reduce(ShortBuffer source, int stride, int left, int top, int width,
                              int height, int smallWidth, int smallHeight) {
        short[] reduced = new short[smallWidth * smallHeight];
        int[] x0 = new int[smallWidth], x1 = new int[smallWidth];
        for (int x = 0; x < smallWidth; x++) {
            x0[x] = left + sampleCoordinate(x, 0.25f, width, smallWidth);
            x1[x] = left + sampleCoordinate(x, 0.75f, width, smallWidth);
        }
        for (int y = 0; y < smallHeight; y++) {
            int a = (top + sampleCoordinate(y, 0.25f, height, smallHeight)) * stride;
            int b = (top + sampleCoordinate(y, 0.75f, height, smallHeight)) * stride;
            for (int x = 0; x < smallWidth; x++) {
                int sum = (source.get(a + x0[x]) & 0xffff) + (source.get(a + x1[x]) & 0xffff)
                        + (source.get(b + x0[x]) & 0xffff) + (source.get(b + x1[x]) & 0xffff);
                reduced[y * smallWidth + x] = (short) ((sum + 2) >> 2);
            }
        }
        return ShortBuffer.wrap(reduced);
    }

    private static int sampleCoordinate(int pixel, float fraction, int source, int dest) {
        int parity = pixel & 1;
        float center = (pixel + fraction) * source / dest - 0.5f;
        int site = Math.round((center - parity) * 0.5f);
        return Math.max(0, Math.min((source - 1 - parity) / 2, site)) * 2 + parity;
    }

    final int width, height;
    private final ShortBuffer raw;
    private final int stride, left, top;
    // Coordinates start at the active image. All 2x2 arrays use row-major parity in this space.
    private final int[] channel;
    private final float[] black, gain, matrix, clipStart, clipScale;
    private final short[] sensorRow;
    private final float[] shading, blended;
    private final int gridWidth, gridHeight;
    private final int[] gridOffset;
    private final float[] gridFraction;

    BayerPipeline(ShortBuffer raw, int stride, int left, int top, int width, int height,
                  int[] channel, float[] black, float white, float[] balance, float[] matrix,
                  float[] shading, int gridWidth, int gridHeight) {
        this.raw = raw.duplicate();
        this.stride = stride;
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.channel = channel;
        this.black = black;
        this.matrix = matrix;
        this.shading = shading;
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
        this.sensorRow = new short[width];
        if (width < 4 || height < 4 || left < 0 || top < 0 || (long) left + width > stride
                || ((long) top + height - 1) * stride + left + width > raw.limit()) {
            throw new IllegalArgumentException("RAW 像素缓冲区与画幅不符");
        }
        gain = new float[4];
        clipStart = new float[4];
        clipScale = new float[4];
        for (int p = 0; p < 4; p++) {
            if (!(white > black[p]) || !(balance[channel[p]] > 0)) {
                throw new IllegalArgumentException("RAW 黑白电平或白平衡无效");
            }
            gain[p] = balance[channel[p]] / (white - black[p]);
            clipStart[p] = black[p] + 0.92f * (white - black[p]);
            clipScale[p] = 255f / (white - clipStart[p]);
        }
        gridOffset = new int[width];
        gridFraction = new float[width];
        blended = new float[4 * (gridWidth + 1)];
        if (shading != null) {
            for (int x = 0; x < width; x++) {
                float gridX = x * ((gridWidth - 1f) / (width - 1));
                int ix = Math.min((int) gridX, gridWidth - 1);
                gridOffset[x] = ix * 4;
                gridFraction[x] = gridX - ix;
            }
        }
    }

    private void load(int y, float[] line, byte[] clipping) {
        loadFrom(raw, sensorRow, blended, y, line, clipping);
    }

    private void loadFrom(ShortBuffer src, short[] rowBuf, float[] blendBuf, int y, float[] line, byte[] clipping) {
        src.position((top + y) * stride + left);
        src.get(rowBuf, 0, width);
        int parity = (y & 1) << 1;
        if (shading != null) {
            float gridY = y * ((gridHeight - 1f) / (height - 1));
            int iy = Math.min((int) gridY, gridHeight - 1);
            float mix = gridY - iy;
            int a = iy * gridWidth * 4;
            int b = Math.min(iy + 1, gridHeight - 1) * gridWidth * 4;
            for (int i = 0; i < gridWidth * 4; i++) {
                blendBuf[i] = shading[a + i] + mix * (shading[b + i] - shading[a + i]);
            }
            System.arraycopy(blendBuf, gridWidth * 4 - 4, blendBuf, gridWidth * 4, 4);
        }
        for (int x = 0; x < width; x++) {
            int p = parity | (x & 1);
            int code = rowBuf[x] & 0xffff;
            float value = Math.max(0, code - black[p]) * gain[p];
            clipping[x + 2] = code < clipStart[p] ? 0 : (byte) Math.min(255,
                    Math.round((code - clipStart[p]) * clipScale[p]));
            if (shading != null) {
                int i = gridOffset[x] + channel[p];
                value *= blendBuf[i] + gridFraction[x] * (blendBuf[i + 4] - blendBuf[i]);
            }
            line[x + 2] = value;
        }
        // Even reflection preserves the CFA phase in the 2-pixel convolution halo.
        line[0] = line[4];
        line[1] = line[3];
        line[width + 2] = line[width];
        line[width + 3] = line[width - 1];
        clipping[1] = clipping[3];
        clipping[width + 2] = clipping[width];
    }

    void render(int rotation, StripWriter writer) {
        render(rotation, false, 0, 0, writer);
    }

    void render(int rotation, boolean natural, float equivalentMm, int iso, StripWriter writer) {
        render(rotation, natural, equivalentMm, iso, PhotoFilter.ORIGINAL, writer);
    }

    void render(int rotation, boolean natural, float equivalentMm, int iso, PhotoFilter filter, StripWriter writer) {
        Options opt = new Options();
        opt.natural = natural;
        opt.equivalentMm = equivalentMm;
        opt.iso = iso;
        opt.filter = filter;
        render(rotation, opt, writer);
    }

    GainmapData render(int rotation, Options options, StripWriter writer) {
        if (!options.natural) {
            renderLegacy(rotation, options.filter, writer);
            return null;
        }
        return renderOptical(rotation, options, writer);
    }

    /** Legacy 0.5.1 baseline streaming renderer (kept verbatim for baseline comparison). */
    private void renderLegacy(int rotation, PhotoFilter filter, StripWriter writer) {
        Scene scene = measureScene(false, 0, 0);
        float[][] lines = new float[5][width + 4];
        byte[][] clipping = new byte[5][width + 4];
        for (int i = 0; i < 4; i++) load(Math.abs(i - 2), lines[i], clipping[i]);
        int[] pixels = new int[width * Math.min(height, STRIP_HEIGHT)];
        for (int y0 = 0; y0 < height; y0 += STRIP_HEIGHT) {
            int count = Math.min(STRIP_HEIGHT, height - y0);
            for (int dy = 0; dy < count; dy++) {
                int y = y0 + dy;
                int next = y + 2;
                load(next < height ? next : 2 * height - 2 - next, lines[4], clipping[4]);
                int index, step;
                switch (rotation) {
                    case 0:   index = dy * width; step = 1; break;
                    case 90:  index = count - 1 - dy; step = count; break;
                    case 180: index = (count - dy) * width - 1; step = -1; break;
                    case 270: index = (width - 1) * count + dy; step = -count; break;
                    default: throw new IllegalArgumentException("无效的照片方向");
                }
                developLegacyRow(lines, clipping, y, pixels, index, step, scene, filter);
                float[] reuse = lines[0];
                System.arraycopy(lines, 1, lines, 0, 4);
                lines[4] = reuse;
                byte[] oldClip = clipping[0];
                System.arraycopy(clipping, 1, clipping, 0, 4);
                clipping[4] = oldClip;
            }
            writeRotatedStrip(writer, rotation, y0, count, pixels);
        }
    }

    private void developLegacyRow(float[][] lines, byte[][] clipping, int y, int[] pixels,
                                  int index, int step, Scene scene, PhotoFilter filter) {
        float[] up2 = lines[0], up = lines[1], row = lines[2], down = lines[3], down2 = lines[4];
        int phase = (y & 1) << 1;
        boolean redOnRow = channel[phase] == 0 || channel[phase + 1] == 0;
        for (int x = 0; x < width; x++, index += step) {
            int i = x + 2;
            float c = row[i];
            float h1 = row[i - 1] + row[i + 1], v1 = up[i] + down[i];
            float h2 = row[i - 2] + row[i + 2], v2 = up2[i] + down2[i];
            float diagonal = up[i - 1] + up[i + 1] + down[i - 1] + down[i + 1];
            float r, g, b;
            int component = channel[phase | (x & 1)];
            if (component == 0 || component == 3) {
                g = (4 * c + 2 * (h1 + v1) - (h2 + v2)) * 0.125f;
                float opposite = (6 * c + 2 * diagonal - 1.5f * (h2 + v2)) * 0.125f;
                r = component == 0 ? c : opposite;
                b = component == 3 ? c : opposite;
            } else {
                float base = 5 * c - diagonal;
                float horizontal = (base + 4 * h1 - h2 + 0.5f * v2) * 0.125f;
                float vertical = (base + 4 * v1 - v2 + 0.5f * h2) * 0.125f;
                g = c;
                r = redOnRow ? horizontal : vertical;
                b = redOnRow ? vertical : horizontal;
            }
            float red = matrix[0]*r + matrix[1]*g + matrix[2]*b;
            float green = matrix[3]*r + matrix[4]*g + matrix[5]*b;
            float blue = matrix[6]*r + matrix[7]*g + matrix[8]*b;
            if (clipping[2][i] != 0) {
                int minClip = 255;
                for (int k = 1; k <= 3; k++) {
                    byte[] cfaRow = clipping[k];
                    minClip = Math.min(minClip, Math.min(cfaRow[i-1] & 255,
                            Math.min(cfaRow[i] & 255, cfaRow[i+1] & 255)));
                }
                float white = Math.max(red, Math.max(green, blue));
                float amount = minClip / 255f;
                red += amount * (white - red);
                green += amount * (white - green);
                blue += amount * (white - blue);
            }
            pixels[index] = encodeLegacy(red, green, blue, scene, filter);
        }
    }

    private static int encodeLegacy(float r, float g, float b, Scene scene, PhotoFilter filter) {
        float min = Math.min(r, Math.min(g, b));
        float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        if (min < 0) {
            if (luminance <= 0) return 0xff000000;
            float saturation = luminance / (luminance - min);
            r = luminance + (r - luminance) * saturation;
            g = luminance + (g - luminance) * saturation;
            b = luminance + (b - luminance) * saturation;
            min = 0;
        }
        float peak = Math.max(r, Math.max(g, b));
        if (scene.whiteGain > 1f) {
            float lift = 1f + (scene.whiteGain - 1f) * smoothStep((luminance - .012f) / .10f)
                    * (1f - smoothStep((peak - .65f) / 1.25f));
            r *= lift; g *= lift; b *= lift;
            min *= lift; peak *= lift; luminance *= lift;
        }
        if (peak > 0.04f && peak < 0.9f) {
            float chroma = (peak - min) / peak;
            float boost = 0.065f * smoothStep((chroma - 0.06f) / 0.20f)
                    * (1f - smoothStep((chroma - 0.52f) / 0.32f))
                    * smoothStep((luminance - 0.025f) / 0.10f)
                    * (1f - smoothStep((peak - 0.52f) / 0.38f));
            r += (r - luminance) * boost;
            g += (g - luminance) * boost;
            b += (b - luminance) * boost;
            peak = Math.max(r, Math.max(g, b));
        }
        float scale = toneGain(peak, TONE_GAIN);
        return filter.encode(r * scale, g * scale, b * scale);
    }

    // ==================== TrueHue 2.0 Optical Engine ====================

    private GainmapData renderOptical(int rotation, Options opt, StripWriter writer) {
        int rot = Math.floorMod(rotation, 360);
        if (rot % 90 != 0) throw new IllegalArgumentException("无效的照片方向");
        int n = width * height;
        float[] yPlane = new float[n];
        float[] crPlane = new float[n];
        float[] cbPlane = new float[n];

        // Stage A: Parallel Bayer normalization, shading, hot-pixel guard, directional demosaic, CCM.
        demosaicParallel(yPlane, crPlane, cbPlane);

        // Stage B: Noise-profile-guided fast chroma denoising + gentle flat-area luma cleanup.
        float effSlope = (opt.noiseSlope > 0 ? opt.noiseSlope : opt.iso * 6.5e-7f)
                / Math.max(1f, opt.effectiveFrames);
        float effOffset = (opt.noiseOffset > 0 ? opt.noiseOffset : 1e-6f)
                / Math.max(1f, opt.effectiveFrames);
        if (width >= 32 && height >= 32 && (opt.noiseSlope > 0 || opt.iso >= 150)) {
            denoiseChromaGuided(yPlane, crPlane, cbPlane, effSlope);
            if (effSlope > 9e-5f) {
                denoiseAndClarityLuma(yPlane, effSlope, effOffset);
            }
        }

        // Stage C: Physically-grounded scene exposure placement (preserving manual EV & low-key night).
        float exposure = computeSceneExposure(yPlane, opt.userEvBias);

        // Stage D: 1/4-res guided log-luminance light & shadow sculpting (zero halo).
        float[] baseGrid = null;
        int baseW = 0, baseH = 0;
        float baseDr = 0f;
        float shadowBoost = 0f;
        if (width >= 64 && height >= 64) {
            baseW = width / 4;
            baseH = height / 4;
            baseGrid = buildIlluminationBase(yPlane, exposure, baseW, baseH);
            float[] bPct = computeBasePercentiles(baseGrid);
            baseDr = bPct[1] - bPct[0];
            boolean ultra = opt.equivalentMm > 0 && opt.equivalentMm < 20;
            boolean tele = opt.equivalentMm >= 45;
            float noisy = opt.iso <= 0 ? 0.35f : smoothStep((opt.iso - 220f) / (ultra ? 600f : 1300f));
            float drGate = smoothStep((baseDr - 4.4f) / 2.0f);
            shadowBoost = (ultra ? 0.14f : tele ? 0.34f : 0.26f) * drGate * (1f - (ultra ? 0.9f : 0.65f) * noisy);
        }

        // Stage E: Optical filmic S-curve + path-to-white + vibrance + filter + optional Ultra HDR gainmap.
        return encodeFinalParallel(rot, opt, writer, yPlane, crPlane, cbPlane,
                exposure, baseGrid, baseW, baseH, shadowBoost);
    }

    private void demosaicParallel(float[] yOut, float[] crOut, float[] cbOut) {
        int workers = width * height >= 262_144 ? 6 : 1;
        runStrips(height, workers, (yStart, yEnd) -> {
            ShortBuffer src = raw.duplicate();
            short[] sRow = new short[width];
            float[] blend = shading == null ? null : new float[4 * (gridWidth + 1)];
            float[][] lines = new float[5][width + 4];
            byte[][] clip = new byte[5][width + 4];
            for (int k = 0; k < 4; k++) {
                int sy = yStart - 2 + k;
                if (sy < 0) sy = -sy;
                if (sy >= height) sy = 2 * height - 2 - sy;
                loadFrom(src, sRow, blend, sy, lines[k], clip[k]);
            }
            for (int y = yStart; y < yEnd; y++) {
                int next = y + 2;
                int sy = next < height ? next : 2 * height - 2 - next;
                loadFrom(src, sRow, blend, sy, lines[4], clip[4]);

                demosaicRow(y, lines, clip, yOut, crOut, cbOut);

                float[] reuse = lines[0];
                System.arraycopy(lines, 1, lines, 0, 4);
                lines[4] = reuse;
                byte[] oldClip = clip[0];
                System.arraycopy(clip, 1, clip, 0, 4);
                clip[4] = oldClip;
            }
        });
    }

    private void demosaicRow(int y, float[][] lines, byte[][] clip,
                             float[] yOut, float[] crOut, float[] cbOut) {
        float[] up2 = lines[0], up = lines[1], row = lines[2], down = lines[3], down2 = lines[4];
        int phase = (y & 1) << 1;
        boolean redOnRow = channel[phase] == 0 || channel[phase + 1] == 0;
        int rowOff = y * width;

        for (int x = 0; x < width; x++) {
            int i = x + 2;
            float c = row[i];
            // Hot-pixel guard on isolated same-color impulse spikes.
            float nMin = Math.min(Math.min(row[i - 2], row[i + 2]), Math.min(up2[i], down2[i]));
            float nMax = Math.max(Math.max(row[i - 2], row[i + 2]), Math.max(up2[i], down2[i]));
            float nSpan = nMax - nMin;
            if (nSpan < 0.06f && c - nMax > Math.max(0.08f, 3.5f * nSpan)) {
                c = nMax;
            }

            float h1 = row[i - 1] + row[i + 1], v1 = up[i] + down[i];
            float h2 = row[i - 2] + row[i + 2], v2 = up2[i] + down2[i];
            int component = channel[phase | (x & 1)];

            boolean guardEdge = Math.abs(c - 0.25f * (h2 + v2)) > 0.055f;
            int sideX = 0, sideY = 0;
            if (guardEdge) {
                float dl = Math.abs(c - row[i - 2]), dr = Math.abs(c - row[i + 2]);
                float du = Math.abs(c - up2[i]), dd = Math.abs(c - down2[i]);
                if (Math.max(du, dd) < 0.18f * Math.max(dl, dr)
                        && Math.min(dl, dr) < 0.18f * Math.max(dl, dr)) sideX = dl < dr ? -1 : 1;
                else if (Math.max(dl, dr) < 0.18f * Math.max(du, dd)
                        && Math.min(du, dd) < 0.18f * Math.max(du, dd)) sideY = du < dd ? -1 : 1;
            }

            float r, g, b;
            if (component == 0 || component == 3) {
                if (sideX != 0) {
                    g = 0.5f * v1;
                    float opposite = 0.5f * (up[i + sideX] + down[i + sideX]);
                    r = component == 0 ? c : opposite;
                    b = component == 3 ? c : opposite;
                } else if (sideY != 0) {
                    g = 0.5f * h1;
                    float[] sameSide = lines[2 + sideY];
                    float opposite = 0.5f * (sameSide[i - 1] + sameSide[i + 1]);
                    r = component == 0 ? c : opposite;
                    b = component == 3 ? c : opposite;
                } else {
                    // Gradient-adaptive Hamilton-Adams green interpolation.
                    float dh = Math.abs(row[i - 1] - row[i + 1]) + Math.abs(2f * c - h2);
                    float dv = Math.abs(up[i] - down[i]) + Math.abs(2f * c - v2);
                    float gH = 0.5f * h1 + 0.25f * (2f * c - h2);
                    float gV = 0.5f * v1 + 0.25f * (2f * c - v2);
                    float wh = (dv * dv + 1e-7f) / (dh * dh + dv * dv + 2e-7f);
                    g = wh * gH + (1f - wh) * gV;
                    if (guardEdge) g = boundEstimate(g, row[i - 1], row[i + 1], up[i], down[i]);

                    float opposite = oppositeChannel(i, g, row, up, down);
                    if (guardEdge) opposite = boundEstimate(opposite, up[i - 1], up[i + 1], down[i - 1], down[i + 1]);
                    r = component == 0 ? c : opposite;
                    b = component == 3 ? c : opposite;
                }
            } else {
                float horizontal, vertical;
                if (sideX != 0) {
                    horizontal = row[i + sideX];
                    vertical = 0.5f * v1;
                } else if (sideY != 0) {
                    horizontal = 0.5f * h1;
                    vertical = lines[2 + sideY][i];
                } else {
                    float gL = 0.5f * (c + row[i - 2]);
                    float gR = 0.5f * (c + row[i + 2]);
                    float gU = 0.5f * (c + up2[i]);
                    float gD = 0.5f * (c + down2[i]);
                    horizontal = c + 0.5f * ((row[i - 1] - gL) + (row[i + 1] - gR));
                    vertical = c + 0.5f * ((up[i] - gU) + (down[i] - gD));
                    if (guardEdge) {
                        horizontal = boundRange(horizontal, Math.min(row[i - 1], row[i + 1]),
                                Math.max(row[i - 1], row[i + 1]));
                        vertical = boundRange(vertical, Math.min(up[i], down[i]),
                                Math.max(up[i], down[i]));
                    }
                }
                g = c;
                r = redOnRow ? horizontal : vertical;
                b = redOnRow ? vertical : horizontal;
            }

            writeLinearPixel(rowOff + x, i, r, g, b, clip, yOut, crOut, cbOut);
        }
    }

    private static float oppositeChannel(int i, float g, float[] row, float[] up, float[] down) {
        float gNW = 0.5f * (row[i - 1] + up[i]);
        float gNE = 0.5f * (row[i + 1] + up[i]);
        float gSW = 0.5f * (row[i - 1] + down[i]);
        float gSE = 0.5f * (row[i + 1] + down[i]);
        float dNE = Math.abs(up[i + 1] - down[i - 1])
                + Math.abs(up[i] - row[i - 1]) + Math.abs(row[i + 1] - down[i]);
        float dNW = Math.abs(up[i - 1] - down[i + 1])
                + Math.abs(up[i] - row[i + 1]) + Math.abs(row[i - 1] - down[i]);
        float wNE = (dNW * dNW + 1e-7f) / (dNE * dNE + dNW * dNW + 2e-7f);
        float diffNE = 0.5f * ((up[i + 1] - gNE) + (down[i - 1] - gSW));
        float diffNW = 0.5f * ((up[i - 1] - gNW) + (down[i + 1] - gSE));
        return g + wNE * diffNE + (1f - wNE) * diffNW;
    }

    private void writeLinearPixel(int idx, int i, float r, float g, float b, byte[][] clip,
                                  float[] yOut, float[] crOut, float[] cbOut) {
        float red = matrix[0] * r + matrix[1] * g + matrix[2] * b;
        float green = matrix[3] * r + matrix[4] * g + matrix[5] * b;
        float blue = matrix[6] * r + matrix[7] * g + matrix[8] * b;

        if (clip[2][i] != 0) {
            int minClip = 255;
            for (int k = 1; k <= 3; k++) {
                byte[] cfaRow = clip[k];
                minClip = Math.min(minClip, Math.min(cfaRow[i - 1] & 255,
                        Math.min(cfaRow[i] & 255, cfaRow[i + 1] & 255)));
            }
            float wMax = Math.max(red, Math.max(green, blue));
            float amount = minClip / 255f;
            red += amount * (wMax - red);
            green += amount * (wMax - green);
            blue += amount * (wMax - blue);
        }

        float lum = 0.2126f * red + 0.7152f * green + 0.0722f * blue;
        float mn = Math.min(red, Math.min(green, blue));
        if (mn < 0f) {
            if (lum <= 0f) {
                red = green = blue = lum = 0f;
            } else {
                float sat = lum / (lum - mn);
                red = lum + (red - lum) * sat;
                green = lum + (green - lum) * sat;
                blue = lum + (blue - lum) * sat;
            }
        }
        yOut[idx] = Math.max(0f, lum);
        crOut[idx] = red - lum;
        cbOut[idx] = blue - lum;
    }

    private void denoiseChromaGuided(float[] yPlane, float[] crPlane, float[] cbPlane, float noiseSlope) {
        int hw = width / 2, hh = height / 2;
        if (hw < 8 || hh < 8) return;

        // Compute log-Y range cleanly on subsampled grid.
        float minL = Float.MAX_VALUE, maxL = -Float.MAX_VALUE;
        for (int hy = 0; hy < hh; hy += 4) {
            int r0 = (hy * 2) * width;
            for (int hx = 0; hx < hw; hx += 4) {
                float v = (float) Math.log(Math.max(yPlane[r0 + hx * 2], 1e-4f) + 0.01f);
                if (v < minL) minL = v;
                if (v > maxL) maxL = v;
            }
        }
        float invSpan = 1f / Math.max(1e-3f, maxL - minL);
        final float logMin = minL;

        int targetRadius = noiseSlope > 4.5e-4f ? 4 : (noiseSlope > 1.5e-4f ? 3 : 2);
        int radius = Math.min(targetRadius, Math.min(hw, hh) / 4);
        float eps = Math.max(0.0015f, Math.min(0.020f, 0.0015f + 2.6f * noiseSlope));
        // Two box filters and bilinear upsampling need this many original half-resolution rows.
        int halo = 2 * radius + 1;
        int bandRows = 64;
        int capacity = Math.min(hh, bandRows + 2 * halo) * hw;
        float[] guide = new float[capacity], red = new float[capacity], blue = new float[capacity];
        float[] mean = new float[capacity], variance = new float[capacity], tmp = new float[capacity];
        float[] aRed = new float[capacity], bRed = new float[capacity];
        float[] aBlue = new float[capacity], bBlue = new float[capacity];
        int previousTop = 0, prepared = 0;
        for (int first = 0; first < hh; first += bandRows) {
            int end = Math.min(hh, first + bandRows);
            int top = Math.max(0, first - halo), bottom = Math.min(hh, end + halo);
            int rows = bottom - top;
            // Retain original samples across the boundary before modifying the full-resolution planes.
            int keep = (prepared - top) * hw, offset = (top - previousTop) * hw;
            System.arraycopy(guide, offset, guide, 0, keep);
            System.arraycopy(red, offset, red, 0, keep);
            System.arraycopy(blue, offset, blue, 0, keep);
            for (int hy = prepared; hy < bottom; hy++) {
                int upper = 2 * hy * width, lower = upper + width;
                for (int hx = 0; hx < hw; hx++) {
                    int x = hx * 2, i = (hy - top) * hw + hx;
                    float avg = quadMean(yPlane, upper + x, lower + x);
                    guide[i] = logGuide(avg, logMin, invSpan);
                    red[i] = quadMean(crPlane, upper + x, lower + x);
                    blue[i] = quadMean(cbPlane, upper + x, lower + x);
                }
            }
            previousTop = top;
            prepared = bottom;

            int cells = rows * hw;
            boxFilter(guide, hw, rows, radius, tmp, mean);
            for (int i = 0; i < cells; i++) variance[i] = guide[i] * guide[i];
            boxFilter(variance, hw, rows, radius, tmp, variance);
            for (int i = 0; i < cells; i++) variance[i] = Math.max(0f, variance[i] - mean[i] * mean[i]);
            solveGuidedCoeffs(guide, mean, variance, red, hw, rows, radius, eps, tmp, aRed, bRed);
            solveGuidedCoeffs(guide, mean, variance, blue, hw, rows, radius, eps, tmp, aBlue, bBlue);

            final int startY = 2 * first;
            int outputRows = (end == hh ? height : 2 * end) - startY;
            runStrips(outputRows, width * outputRows >= 262144 ? 6 : 1, (from, to) -> {
                for (int y = startY + from; y < startY + to; y++) {
                    float fy = Math.max(0f, Math.min(hh - 1f, y * 0.5f - 0.25f));
                    int iy = (int) fy;
                    int r0 = (iy - top) * hw, r1 = (Math.min(hh - 1, iy + 1) - top) * hw;
                    float ty = fy - iy;
                    for (int x = 0; x < width; x++) {
                        float fx = Math.max(0f, Math.min(hw - 1f, x * 0.5f - 0.25f));
                        int x0 = (int) fx, x1 = Math.min(hw - 1, x0 + 1);
                        float tx = fx - x0;
                        int i = y * width + x;
                        float g = logGuide(yPlane[i], logMin, invSpan);
                        crPlane[i] = bilerp(aRed, r0, r1, x0, x1, tx, ty) * g
                                + bilerp(bRed, r0, r1, x0, x1, tx, ty);
                        cbPlane[i] = bilerp(aBlue, r0, r1, x0, x1, tx, ty) * g
                                + bilerp(bBlue, r0, r1, x0, x1, tx, ty);
                    }
                }
            });
        }
    }

    private static float logGuide(float value, float min, float invSpan) {
        float normalized = ((float) Math.log(Math.max(value, 1e-4f) + 0.01f) - min) * invSpan;
        return Math.max(0f, Math.min(1f, normalized));
    }

    private static float quadMean(float[] plane, int upper, int lower) {
        return 0.25f * (plane[upper] + plane[upper + 1] + plane[lower] + plane[lower + 1]);
    }

    private static void solveGuidedCoeffs(float[] guide, float[] mean, float[] variance, float[] chroma,
                                          int w, int h, int radius, float eps, float[] tmp, float[] a, float[] b) {
        int n = w * h;
        boxFilter(chroma, w, h, radius, tmp, b);
        for (int i = 0; i < n; i++) a[i] = guide[i] * chroma[i];
        boxFilter(a, w, h, radius, tmp, a);
        for (int i = 0; i < n; i++) {
            a[i] = (a[i] - mean[i] * b[i]) / (variance[i] + eps);
            b[i] -= a[i] * mean[i];
        }
        boxFilter(a, w, h, radius, tmp, a);
        boxFilter(b, w, h, radius, tmp, b);
    }

    private void denoiseAndClarityLuma(float[] yPlane, float noiseSlope, float noiseOffset) {
        int workers = width * height >= 262_144 ? Math.min(6, Math.max(1, height / 16)) : 1;
        int chunk = (height + workers - 1) / workers;
        // Snapshot exact halo rows before parallel in-place updates so threads never race at borders.
        float[][] topHalos = new float[workers][width];
        float[][] botHalos = new float[workers][width];
        for (int w = 0; w < workers; w++) {
            int y0 = w * chunk, y1 = Math.min(height, y0 + chunk);
            if (y0 >= y1) break;
            int topIdx = Math.max(0, y0 - 1);
            int botIdx = Math.min(height - 1, y1);
            System.arraycopy(yPlane, topIdx * width, topHalos[w], 0, width);
            System.arraycopy(yPlane, botIdx * width, botHalos[w], 0, width);
        }

        List<Callable<Void>> jobs = new ArrayList<>(workers);
        for (int w = 0; w < workers; w++) {
            final int id = w;
            final int y0 = w * chunk, y1 = Math.min(height, y0 + chunk);
            if (y0 >= y1) break;
            jobs.add(() -> {
                float[] prev = new float[width];
                float[] curr = new float[width];
                float[] next = new float[width];
                System.arraycopy(topHalos[id], 0, prev, 0, width);
                System.arraycopy(yPlane, y0 * width, curr, 0, width);
                for (int y = y0; y < y1; y++) {
                    if (y + 1 < y1) {
                        System.arraycopy(yPlane, (y + 1) * width, next, 0, width);
                    } else {
                        System.arraycopy(botHalos[id], 0, next, 0, width);
                    }
                    int rowOff = y * width;
                    for (int x = 0; x < width; x++) {
                        int xm = Math.max(0, x - 1), xp = Math.min(width - 1, x + 1);
                        float c = curr[x];
                        float s = prev[xm] + prev[x] + prev[xp]
                                + curr[xm] + c + curr[xp]
                                + next[xm] + next[x] + next[xp];
                        float mean = s * (1f / 9f);
                        float ss = prev[xm] * prev[xm] + prev[x] * prev[x] + prev[xp] * prev[xp]
                                + curr[xm] * curr[xm] + c * c + curr[xp] * curr[xp]
                                + next[xm] * next[xm] + next[x] * next[x] + next[xp] * next[xp];
                        float localVar = Math.max(0f, ss * (1f / 9f) - mean * mean);
                        float nVar = noiseSlope * Math.max(0.005f, mean) + noiseOffset;

                        float out = c;
                        if (localVar < 2.2f * nVar) {
                            float alpha = Math.max(0f, Math.min(0.55f,
                                    (2.2f * nVar - localVar) / Math.max(1e-7f, 2.2f * nVar)));
                            out = c + alpha * (mean - c);
                        } else if (localVar > 4.2f * nVar && c > 0.015f) {
                            float relDiff = Math.abs(c - mean) / Math.max(0.05f, mean);
                            float edgeBrake = 1f - smoothStep((relDiff - 0.25f) / 0.45f);
                            float diff = c - mean;
                            float maxStep = 0.045f * c;
                            float clarity = Math.max(-maxStep, Math.min(maxStep, 0.12f * diff * edgeBrake));
                            out = Math.max(0f, c + clarity);
                        }
                        yPlane[rowOff + x] = out;
                    }
                    float[] swap = prev;
                    prev = curr;
                    curr = next;
                    next = swap;
                }
                return null;
            });
        }
        invokeJobs(jobs);
    }

    private float computeSceneExposure(float[] yPlane, float userEvBias) {
        if (Math.abs(userEvBias) >= 0.05f) return 1f;
        int step = Math.max(1, Math.min(width, height) / 96);
        int[] hist = new int[513];
        int count = 0;
        for (int y = 0; y < height; y += step) {
            int row = y * width;
            for (int x = 0; x < width; x += step) {
                float lum = yPlane[row + x];
                int bin = Math.max(0, Math.min(512, Math.round(lum * 512f)));
                hist[bin]++;
                count++;
            }
        }
        if (count == 0) return 1f;
        float p10 = histPercentile(hist, count, 0.10f, 512f);
        float p50 = histPercentile(hist, count, 0.50f, 512f);
        float p92 = histPercentile(hist, count, 0.92f, 512f);
        float p995 = histPercentile(hist, count, 0.995f, 512f);

        if (p92 - p10 <= 0.035f || p92 <= 0.06f) return 1f;
        float gainKey = 0.145f / Math.max(p50, 0.020f);
        float gainWhite = 0.78f / Math.max(p92, 0.075f);
        float gainHi = 1.95f / Math.max(p995, 0.22f);
        float nightGate = smoothStep((p92 - 0.06f) / 0.10f);
        float blended = 0.45f * Math.max(gainKey, 1f) + 0.55f * Math.max(gainWhite, 1f);
        float rawGain = Math.max(1f, Math.min(Math.min(3.5f, Math.max(1.65f, gainHi)),
                Math.min(gainWhite, blended)));
        return 1f + nightGate * (rawGain - 1f);
    }

    private float[] buildIlluminationBase(float[] yPlane, float exposure, int bw, int bh) {
        int bn = bw * bh;
        float[] logY = new float[bn];
        for (int by = 0; by < bh; by++) {
            int y = Math.min(height - 1, by * 4 + 1);
            int row = y * width;
            for (int bx = 0; bx < bw; bx++) {
                int x = Math.min(width - 1, bx * 4 + 1);
                float y00 = yPlane[row + x];
                float y01 = yPlane[row + Math.min(width - 1, x + 2)];
                float y10 = yPlane[Math.min(height - 1, y + 2) * width + x];
                float y11 = yPlane[Math.min(height - 1, y + 2) * width + Math.min(width - 1, x + 2)];
                float avg = 0.25f * (y00 + y01 + y10 + y11) * exposure;
                logY[by * bw + bx] = log2f(Math.max(0f, avg) + 0.003f);
            }
        }
        int radius = Math.max(3, Math.min(36, bw / 20));
        float[] tmp = new float[bn];
        float[] mean = new float[bn];
        float[] slope = new float[bn];
        boxFilter(logY, bw, bh, radius, tmp, mean);
        for (int i = 0; i < bn; i++) slope[i] = logY[i] * logY[i];
        boxFilter(slope, bw, bh, radius, tmp, slope);
        for (int i = 0; i < bn; i++) {
            float variance = Math.max(0f, slope[i] - mean[i] * mean[i]);
            slope[i] = variance / (variance + 4.0f);
            mean[i] *= 1f - slope[i];
        }
        boxFilter(slope, bw, bh, radius, tmp, slope);
        boxFilter(mean, bw, bh, radius, tmp, mean);
        for (int i = 0; i < bn; i++) logY[i] = slope[i] * logY[i] + mean[i];
        return logY;
    }

    private static float[] computeBasePercentiles(float[] base) {
        int[] hist = new int[257];
        for (float v : base) {
            int bin = Math.max(0, Math.min(256, Math.round((v + 9f) * (256f / 12f))));
            hist[bin]++;
        }
        float lo = histPercentile(hist, base.length, 0.05f, 256f / 12f) - 9f;
        float hi = histPercentile(hist, base.length, 0.98f, 256f / 12f) - 9f;
        return new float[]{lo, hi};
    }

    private GainmapData encodeFinalParallel(int rotation, Options opt, StripWriter writer,
                                            float[] yPlane, float[] crPlane, float[] cbPlane,
                                            float exposure, float[] baseGrid, int baseW, int baseH,
                                            float shadowBoost) {
        boolean swap = rotation % 180 != 0;
        int outW = swap ? height : width;
        int outH = swap ? width : height;
        int gw = opt.buildGainmap ? Math.max(1, outW / 4) : 0;
        int gh = opt.buildGainmap ? Math.max(1, outH / 4) : 0;
        final float maxGainRatio = 3.2f;
        final float invLogMaxRatio = 1f / log2f(maxGainRatio);
        byte[] gainPixels = opt.buildGainmap ? new byte[gw * gh] : null;
        PhotoFilter filter = opt.filter;

        int stripRows = Math.min(height, STRIP_HEIGHT);
        int stripCount = (height + stripRows - 1) / stripRows;
        int workers = width * height >= 262_144 ? Math.min(6, stripCount) : 1;

        runStrips(stripCount, workers, (s0, s1) -> {
            int[] pixels = new int[width * stripRows];
            for (int s = s0; s < s1; s++) {
                int y0 = s * stripRows;
                int count = Math.min(stripRows, height - y0);
                for (int dy = 0; dy < count; dy++) {
                    int y = y0 + dy;
                    int index, step;
                    switch (rotation) {
                        case 0:   index = dy * width; step = 1; break;
                        case 90:  index = count - 1 - dy; step = count; break;
                        case 180: index = (count - dy) * width - 1; step = -1; break;
                        case 270: index = (width - 1) * count + dy; step = -count; break;
                        default: throw new IllegalArgumentException("无效的照片方向");
                    }
                    int rowOff = y * width;
                    float fy = baseGrid == null ? 0f : Math.max(0f, Math.min(baseH - 1f, y * 0.25f - 0.375f));
                    int iy0 = (int) fy, iy1 = baseGrid == null ? 0 : Math.min(baseH - 1, iy0 + 1);
                    float ty = fy - iy0;
                    int bRow0 = iy0 * baseW, bRow1 = iy1 * baseW;

                    for (int x = 0; x < width; x++, index += step) {
                        int idx = rowOff + x;
                        float lum = yPlane[idx] * exposure;
                        float cr = crPlane[idx] * exposure;
                        float cb = cbPlane[idx] * exposure;

                        if (baseGrid != null && lum > 1e-5f) {
                            float fx = Math.max(0f, Math.min(baseW - 1f, x * 0.25f - 0.375f));
                            int ix0 = (int) fx, ix1 = Math.min(baseW - 1, ix0 + 1);
                            float base = bilerp(baseGrid, bRow0, bRow1, ix0, ix1, fx - ix0, ty);
                            float logLum = log2f(lum + 0.003f);
                            float detail = logLum - base;

                            float newBase = base;
                            if (base > 0.05f) {
                                newBase -= 0.22f * (base - 0.05f);
                            }
                            if (shadowBoost > 0f && base > -5.6f && base < -2.2f) {
                                float midBell = smoothStep((base + 5.6f) / 1.4f)
                                        * (1f - smoothStep((base + 3.2f) / 1.0f));
                                newBase += shadowBoost * 0.65f * midBell;
                            }
                            float noiseGate = smoothStep((lum - 0.015f) / 0.045f);
                            float detailBrake = 1f - smoothStep((Math.abs(detail) - 0.25f) / 0.40f);
                            float newLog = newBase + detail * (1f + 0.08f * noiseGate * detailBrake);
                            float targetY = Math.max(0f, exp2f(newLog) - 0.003f);
                            float ratio = Math.max(0.50f, Math.min(1.65f, targetY / lum));
                            lum *= ratio;
                            cr *= ratio;
                            cb *= ratio;
                        }

                        // Reconstruct linear RGB with gamut protection.
                        float r = lum + cr;
                        float b = lum + cb;
                        float g = (lum - 0.2126f * r - 0.0722f * b) / 0.7152f;
                        float mn = Math.min(r, Math.min(g, b));
                        if (mn < 0f) {
                            if (lum <= 0f) {
                                r = g = b = 0f;
                            } else {
                                float sat = lum / (lum - mn);
                                r = lum + (r - lum) * sat;
                                g = lum + (g - lum) * sat;
                                b = lum + (b - lum) * sat;
                            }
                        }

                        float peakHdr = Math.max(r, Math.max(g, b));
                        float lumMapped = opticalFilmic(lum);
                        float tmScale = lum > 1e-6f ? lumMapped / lum : 0f;
                        r *= tmScale;
                        g *= tmScale;
                        b *= tmScale;

                        // Hue-preserving highlight path-to-white.
                        float pAfter = Math.max(r, Math.max(g, b));
                        if (pAfter > 0.78f) {
                            float desat = 0.90f * smoothStep((pAfter - 0.78f) / 0.85f);
                            float targetW = Math.min(0.996f, opticalFilmic(peakHdr));
                            r += desat * (targetW - r);
                            g += desat * (targetW - g);
                            b += desat * (targetW - b);
                        }
                        float pSafe = Math.max(1f, Math.max(r, Math.max(g, b)));
                        if (pSafe > 1f) {
                            r /= pSafe; g /= pSafe; b /= pSafe;
                        }

                        // Restrained natural chromatic purity recovery (tapered on skin/neutral).
                        float yf = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                        float pk = Math.max(r, Math.max(g, b));
                        float minC = Math.min(r, Math.min(g, b));
                        if (pk > 0.025f && yf < 0.75f) {
                            float chroma = (pk - minC) / pk;
                            float vib = 0.11f * smoothStep((chroma - 0.05f) / 0.18f)
                                    * (1f - smoothStep((chroma - 0.58f) / 0.32f))
                                    * smoothStep((yf - 0.02f) / 0.07f)
                                    * (1f - smoothStep((yf - 0.50f) / 0.25f));
                            if (r > g && g > b) {
                                float warm = smoothStep((r - g) / (0.20f * pk))
                                        * smoothStep((g - b) / (0.14f * pk));
                                vib *= (1f - 0.45f * warm);
                            }
                            if (vib > 0f) {
                                r = Math.max(0f, Math.min(1f, yf + (r - yf) * (1f + vib)));
                                g = Math.max(0f, Math.min(1f, yf + (g - yf) * (1f + vib)));
                                b = Math.max(0f, Math.min(1f, yf + (b - yf) * (1f + vib)));
                            }
                        }

                        if (gainPixels != null && (x & 3) == 1 && (y & 3) == 1) {
                            float peakSdr = Math.max(0.01f, Math.max(r, Math.max(g, b)));
                            float hdrRamp = smoothStep((peakSdr - 0.32f) / 0.28f);
                            float ratio = peakHdr > peakSdr
                                    ? 1f + hdrRamp * Math.min(maxGainRatio - 1f, peakHdr / peakSdr - 1f)
                                    : 1f;
                            int code = Math.max(0, Math.min(255, Math.round(255f * log2f(ratio) * invLogMaxRatio)));
                            int ox, oy;
                            switch (rotation) {
                                case 90:  ox = (height - 1 - y) / 4; oy = x / 4; break;
                                case 180: ox = (width - 1 - x) / 4; oy = (height - 1 - y) / 4; break;
                                case 270: ox = y / 4; oy = (width - 1 - x) / 4; break;
                                default:  ox = x / 4; oy = y / 4; break;
                            }
                            if (ox >= 0 && ox < gw && oy >= 0 && oy < gh) {
                                gainPixels[oy * gw + ox] = (byte) code;
                            }
                        }

                        pixels[index] = filter.encode(r, g, b);
                    }
                }
                writeRotatedStrip(writer, rotation, y0, count, pixels);
            }
        });

        return gainPixels == null ? null : new GainmapData(gainPixels, gw, gh, maxGainRatio);
    }

    private void writeRotatedStrip(StripWriter writer, int rotation, int y0, int count, int[] pixels) {
        synchronized (writer) {
            switch (rotation) {
                case 0:   writer.write(pixels, 0, y0, width, count); break;
                case 90:  writer.write(pixels, height - y0 - count, 0, count, width); break;
                case 180: writer.write(pixels, 0, height - y0 - count, width, count); break;
                case 270: writer.write(pixels, y0, 0, count, width); break;
            }
        }
    }

    private static void boxFilter(float[] src, int w, int h, int r, float[] tmp, float[] dst) {
        float inv = 1f / (2 * r + 1);
        runStrips(h, w * h >= 65_536 ? 6 : 1, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                int row = y * w;
                float sum = src[row] * (r + 1);
                for (int dx = 1; dx <= r; dx++) sum += src[row + Math.min(w - 1, dx)];
                for (int x = 0; x < w; x++) {
                    tmp[row + x] = sum * inv;
                    int addX = Math.min(w - 1, x + r + 1);
                    int subX = Math.max(0, x - r);
                    sum += src[row + addX] - src[row + subX];
                }
            }
        });
        runStrips(w, w * h >= 65_536 ? 6 : 1, (x0, x1) -> {
            for (int x = x0; x < x1; x++) {
                float sum = tmp[x] * (r + 1);
                for (int dy = 1; dy <= r; dy++) sum += tmp[Math.min(h - 1, dy) * w + x];
                for (int y = 0; y < h; y++) {
                    dst[y * w + x] = sum * inv;
                    int addY = Math.min(h - 1, y + r + 1);
                    int subY = Math.max(0, y - r);
                    sum += tmp[addY * w + x] - tmp[subY * w + x];
                }
            }
        });
    }

    private static float bilerp(float[] grid, int r0, int r1, int ix0, int ix1, float tx, float ty) {
        float topVal = grid[r0 + ix0] + tx * (grid[r0 + ix1] - grid[r0 + ix0]);
        float botVal = grid[r1 + ix0] + tx * (grid[r1 + ix1] - grid[r1 + ix0]);
        return topVal + ty * (botVal - topVal);
    }

    private static float histPercentile(int[] hist, int count, float fraction, float scale) {
        int target = Math.round(count * fraction);
        int i = 0, sum = hist[0];
        while (sum < target && i < hist.length - 1) sum += hist[++i];
        return i / scale;
    }

    private static float log2f(float v) {
        return (float) (Math.log(v) * 1.4426950408889634);
    }

    private static float exp2f(float v) {
        return (float) Math.pow(2.0, v);
    }

    interface StripTask { void run(int start, int end); }

    private static void runStrips(int count, int workers, StripTask task) {
        if (workers <= 1 || count <= 1) {
            task.run(0, count);
            return;
        }
        int actual = Math.min(workers, count);
        int chunk = (count + actual - 1) / actual;
        List<Callable<Void>> jobs = new ArrayList<>(actual);
        for (int w = 0; w < actual; w++) {
            int s = w * chunk, e = Math.min(count, s + chunk);
            if (s >= e) break;
            jobs.add(() -> {
                task.run(s, e);
                return null;
            });
        }
        invokeJobs(jobs);
    }

    private static void invokeJobs(List<Callable<Void>> jobs) {
        try {
            for (Future<Void> f : POOL.invokeAll(jobs)) f.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("显影任务被中断", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }

    private static float boundEstimate(float value, float a, float b, float c, float d) {
        float lo = Math.min(Math.min(a, b), Math.min(c, d));
        float hi = Math.max(Math.max(a, b), Math.max(c, d));
        return boundRange(value, lo, hi);
    }

    private static float boundRange(float value, float lo, float hi) {
        float margin = .012f + .12f * (hi - lo);
        return Math.max(lo - margin, Math.min(hi + margin, value));
    }

    // Legacy scene statistics preserved for 0.5.1 baseline and RawDevelopmentCheck.
    static final class Scene {
        final boolean natural;
        final float whiteGain, shoulder;
        final float[][] shadowRows;

        Scene(boolean natural, float whiteGain, float shoulder, float[][] shadowRows) {
            this.natural = natural;
            this.whiteGain = whiteGain;
            this.shoulder = shoulder;
            this.shadowRows = shadowRows;
        }
    }

    Scene measureScene(boolean natural, float equivalentMm, int iso) {
        int cols = Math.min(48, width / 2), rows = Math.min(36, height / 2);
        int[] luminance = new int[129], peaks = new int[129], neutralLuma = new int[129];
        float[] a = new float[width + 4], b = new float[width + 4], quad = new float[4];
        byte[] ca = new byte[width + 4], cb = new byte[width + 4];
        for (int j = 0; j < rows; j++) {
            int y = 2 * ((2 * j + 1) * (height / 2) / (2 * rows));
            load(y, a, ca);
            load(y + 1, b, cb);
            for (int i = 0; i < cols; i++) {
                int x = 2 + 2 * ((2 * i + 1) * (width / 2) / (2 * cols));
                quad[channel[0]] = a[x]; quad[channel[1]] = a[x + 1];
                quad[channel[2]] = b[x]; quad[channel[3]] = b[x + 1];
                float g = .5f * (quad[1] + quad[2]);
                float r = matrix[0]*quad[0] + matrix[1]*g + matrix[2]*quad[3];
                float green = matrix[3]*quad[0] + matrix[4]*g + matrix[5]*quad[3];
                float blue = matrix[6]*quad[0] + matrix[7]*g + matrix[8]*quad[3];
                float yLinear = .2126f*r + .7152f*green + .0722f*blue;
                float peak = Math.max(r, Math.max(green, blue));
                int bin = Math.max(0, Math.min(128, Math.round(yLinear * 128)));
                luminance[bin]++;
                peaks[Math.max(0, Math.min(128, Math.round(peak * 128)))]++;
                if (Math.min(r, Math.min(green, blue)) > .63f * peak) neutralLuma[bin]++;
            }
        }
        int count = cols * rows;
        float white = percentile(luminance, count, .95f);
        float gain = diffuseWhiteGain(luminance, peaks, neutralLuma, count, white);
        return new Scene(natural, gain, 0, null);
    }

    private static float diffuseWhiteGain(int[] luminance, int[] peaks, int[] neutralLuma, int count, float white) {
        if (white <= .14f || white >= .64f) return 1f;
        int neutral = 0;
        for (int i = Math.round(.70f * white * 128); i < neutralLuma.length; i++) neutral += neutralLuma[i];
        float spread = white - percentile(luminance, count, .25f);
        float subject = percentile(luminance, count, .50f);
        float weight = smoothStep((neutral / (float) count - .03f) / .065f)
                * smoothStep((white - .14f) / .09f) * smoothStep((spread - .06f) / .12f)
                * smoothStep((subject - .025f) / .035f)
                * (1f - smoothStep((percentile(peaks, count, .99f) - .72f) / .26f));
        return 1f + weight * Math.min(.68f, .64f / white - 1f);
    }

    private static float percentile(int[] histogram, int count, float fraction) {
        int target = Math.round(count * fraction);
        int i = 0, sum = histogram[0];
        while (sum < target && i < histogram.length - 1) sum += histogram[++i];
        return i / 128f;
    }

    private static float[] buildToneGain(boolean soft) {
        float[] x = {0, .005f, .02f, .04f, .09f, .18f, .32f, .5f, .75f, 1, 1.5f, 2, 4};
        float[] y = soft
                ? new float[]{0, .0035f, .014f, .031f, .089f, .218f, .40f, .559f, .685f, .763f, .868f, .922f, .974f}
                : new float[]{0, .0035f, .014f, .031f, .089f, .218f, .40f, .57f, .714f, .797f, .883f, .928f, .974f};
        float[] slope = new float[x.length];
        float previous = slope[0] = y[1] / x[1];
        for (int i = 1; i < x.length - 1; i++) {
            float next = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
            slope[i] = 2f * previous * next / (previous + next);
            previous = next;
        }
        slope[x.length - 1] = .013f;
        float[] gain = new float[4 * TONE_RESOLUTION + 1];
        gain[0] = slope[0];
        int k = 0;
        for (int i = 1; i < gain.length; i++) {
            float v = i / (float) TONE_RESOLUTION;
            while (v > x[k + 1]) k++;
            float h = x[k + 1] - x[k], t = (v - x[k]) / h, u = 1f - t;
            float mapped = u*u*(1f + 2f*t)*y[k] + t*t*(3f - 2f*t)*y[k+1]
                    + h*t*u*(u*slope[k] - t*slope[k+1]);
            gain[i] = mapped / v;
        }
        return gain;
    }

    private static float[] buildOpticalFilmic() {
        float[] x = {0f, .005f, .015f, .04f, .09f, .18f, .35f, .55f, .85f, 1f, 1.5f, 2.5f, 4f, 8f};
        float[] y = {0f, .0018f, .0080f, .029f, .084f, .205f, .430f, .635f, .832f, .885f, .946f, .979f, .992f, .998f};
        float[] slope = new float[x.length];
        float previous = slope[0] = y[1] / x[1];
        for (int i = 1; i < x.length - 1; i++) {
            float next = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
            slope[i] = 2f * previous * next / (previous + next);
            previous = next;
        }
        slope[x.length - 1] = 0.0005f;
        float[] lut = new float[FILMIC_MAX * TONE_RESOLUTION + 1];
        int k = 0;
        for (int i = 1; i < lut.length; i++) {
            float v = i / (float) TONE_RESOLUTION;
            while (k < x.length - 2 && v > x[k + 1]) k++;
            float h = x[k + 1] - x[k], t = (v - x[k]) / h, u = 1f - t;
            lut[i] = u * u * (1f + 2f * t) * y[k] + t * t * (3f - 2f * t) * y[k + 1]
                    + h * t * u * (u * slope[k] - t * slope[k + 1]);
        }
        return lut;
    }

    private static float opticalFilmic(float x) {
        if (x <= 0f) return 0f;
        if (x >= FILMIC_MAX) {
            return 1f - 0.016f / (1f + 0.875f * (x - FILMIC_MAX));
        }
        float idx = x * TONE_RESOLUTION;
        int i = (int) idx;
        return OPTICAL_FILMIC[i] + (idx - i) * (OPTICAL_FILMIC[i + 1] - OPTICAL_FILMIC[i]);
    }

    private static float toneGain(float peak, float[] gain) {
        if (peak >= 4f) return (1f - .026f / (1f + .5f * (peak - 4f))) / peak;
        float index = peak * TONE_RESOLUTION;
        int i = (int) index;
        return gain[i] + (index - i) * (gain[i + 1] - gain[i]);
    }

    private static float smoothStep(float position) {
        float t = Math.max(0f, Math.min(1f, position));
        return t * t * (3f - 2f * t);
    }
}
