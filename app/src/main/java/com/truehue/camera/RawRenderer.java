package com.truehue.camera;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.RggbChannelVector;
import android.media.Image;
import android.os.Build;
import android.util.Pair;
import android.util.Rational;
import android.util.Size;

import java.nio.ByteBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Single-frame and burst RAW_SENSOR developer. Call off the UI thread; the caller owns the Image. */
final class RawRenderer {
    // Values are indices into RggbChannelVector, ordered by Bayer row/column parity.
    private static final int[][] CHANNELS = {
            {0, 1, 2, 3}, {1, 0, 3, 2}, {1, 3, 0, 2}, {3, 1, 2, 0}
    };

    static final class Options {
        boolean natural = true;
        boolean wideGamut = true;
        boolean ultraHdr = false;
        PhotoFilter filter = PhotoFilter.ORIGINAL;
        float manualKelvin = 0f;
        float manualTint = 0f;
        float userEvBias = 0f;
        float fallbackMeteredBiasEv = Float.NaN;
    }

    static final class RawGeometry {
        final int[] channel;
        final float[] black;
        final float white;
        final Rect crop;
        final int stride;

        RawGeometry(int[] channel, float[] black, float white, Rect crop, int stride) {
            this.channel = channel;
            this.black = black;
            this.white = white;
            this.crop = crop;
            this.stride = stride;
        }
    }

    /** Extracted active Bayer pixel buffer decoupled from the Camera2 Image lifecycle. */
    static final class RawSnapshot {
        final byte[] pixels;
        final Size size;
        final RawGeometry geometry;
        final CameraCharacteristics sensor;
        final TotalCaptureResult shot;
        final int clockwiseDegrees;

        RawSnapshot(byte[] pixels, Size size, RawGeometry geometry,
                    CameraCharacteristics sensor, TotalCaptureResult shot, int clockwiseDegrees) {
            this.pixels = pixels;
            this.size = size;
            this.geometry = geometry;
            this.sensor = sensor;
            this.shot = shot;
            this.clockwiseDegrees = clockwiseDegrees;
        }
    }

    private RawRenderer() {}

    static Bitmap render(Image raw, CameraCharacteristics sensor, TotalCaptureResult shot,
                         int clockwiseDegrees, boolean natural, PhotoFilter filter) {
        Options opt = new Options();
        opt.natural = natural;
        opt.filter = filter;
        return render(raw, sensor, shot, clockwiseDegrees, opt);
    }

    static Bitmap render(Image raw, CameraCharacteristics sensor, TotalCaptureResult shot,
                         int clockwiseDegrees, Options options) {
        float sceneEv = computeSceneEv(shot);
        return bitmap(pipeline(raw, sensor, shot, 0, options, sceneEv), clockwiseDegrees, options, sensor, shot, 1f, sceneEv);
    }

    static Bitmap renderPreview(Image raw, CameraCharacteristics sensor, TotalCaptureResult shot,
                                int clockwiseDegrees, int maxEdge, boolean natural, PhotoFilter filter) {
        Options opt = new Options();
        opt.natural = natural;
        opt.filter = filter;
        return renderPreview(raw, sensor, shot, clockwiseDegrees, maxEdge, opt);
    }

    static Bitmap renderPreview(Image raw, CameraCharacteristics sensor, TotalCaptureResult shot,
                                int clockwiseDegrees, int maxEdge, Options options) {
        if (maxEdge < 4) throw new IllegalArgumentException("RAW 预览尺寸太小");
        float sceneEv = computeSceneEv(shot);
        return bitmap(pipeline(raw, sensor, shot, maxEdge, options, sceneEv), clockwiseDegrees, options, sensor, shot, 1f, sceneEv);
    }

    static Bitmap renderMerged(BurstRawMerger.Result merged, int[] channel, int width, int height,
                               CameraCharacteristics sensor, TotalCaptureResult shot,
                               int clockwiseDegrees, Options options) {
        float sceneEv = computeSceneEv(shot);
        ColorCalibration cal = calibrateColor(sensor, shot, options, sceneEv);
        ShadingInfo shade = readShading(shot);
        BayerPipeline pipe = new BayerPipeline(merged.bayer12, width, 0, 0, width, height, channel,
                merged.black12, merged.white12, cal.balance, cal.transform,
                shade.map, shade.columns, shade.rows);
        return bitmap(pipe, clockwiseDegrees, options, sensor, shot, merged.effectiveFrames, sceneEv);
    }

    static RawSnapshot extractSnapshot(Image raw, CameraCharacteristics sensor, TotalCaptureResult shot,
                                       int clockwiseDegrees) {
        RawGeometry geo = inspectRaw(raw, sensor, shot);
        int width = raw.getWidth(), height = raw.getHeight();
        int rowBytes = Math.multiplyExact(width, Short.BYTES);
        int strideBytes = geo.stride * Short.BYTES;
        ByteBuffer data = raw.getPlanes()[0].getBuffer().slice();
        byte[] pixels = new byte[Math.multiplyExact(rowBytes, height)];
        // DngCreator needs the full sensor frame with padding removed.
        if (strideBytes == rowBytes) {
            data.get(pixels);
        } else {
            for (int y = 0; y < height; y++) {
                data.position(y * strideBytes);
                data.get(pixels, y * rowBytes, rowBytes);
            }
        }
        return new RawSnapshot(pixels, new Size(width, height), geo, sensor, shot, clockwiseDegrees);
    }

    static Bitmap renderSnapshot(RawSnapshot snap, Options options) {
        float sceneEv = computeSceneEv(snap.shot);
        ColorCalibration cal = calibrateColor(snap.sensor, snap.shot, options, sceneEv);
        ShadingInfo shade = readShading(snap.shot);
        RawGeometry geo = snap.geometry;
        ShortBuffer bayer = ByteBuffer.wrap(snap.pixels).order(ByteOrder.nativeOrder()).asShortBuffer();
        BayerPipeline pipe = new BayerPipeline(bayer, snap.size.getWidth(), geo.crop.left, geo.crop.top,
                geo.crop.width(), geo.crop.height(), geo.channel, geo.black, geo.white,
                cal.balance, cal.transform, shade.map, shade.columns, shade.rows);
        return bitmap(pipe, snap.clockwiseDegrees, options, snap.sensor, snap.shot, 1f, sceneEv);
    }

    static RawGeometry inspectRaw(Image raw, CameraCharacteristics sensor, TotalCaptureResult shot) {
        if (raw.getFormat() != ImageFormat.RAW_SENSOR || raw.getPlanes().length != 1) {
            throw new IllegalArgumentException("当前照片没有标准 RAW_SENSOR 像素");
        }
        Image.Plane plane = raw.getPlanes()[0];
        if (plane.getPixelStride() != 2 || plane.getRowStride() % 2 != 0) {
            throw new IllegalArgumentException("不支持该 RAW 像素布局");
        }
        Integer cfa = sensor.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (cfa == null || cfa < 0 || cfa >= CHANNELS.length) {
            throw new IllegalArgumentException("当前主摄没有标准 Bayer 色彩排列");
        }

        int pixelWidth = raw.getWidth(), pixelHeight = raw.getHeight();
        Size pixelArray = sensor.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        Rect active = sensor.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        if (active == null) active = sensor.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Rect crop = new Rect(0, 0, pixelWidth, pixelHeight);
        int originX = 0, originY = 0;
        if (active != null && pixelArray != null && pixelArray.getWidth() == pixelWidth
                && pixelArray.getHeight() == pixelHeight) {
            if (!crop.intersect(active)) throw new IllegalArgumentException("RAW 有效区域为空");
        } else if (active != null && active.width() == pixelWidth && active.height() == pixelHeight) {
            originX = active.left;
            originY = active.top;
        } else if (active != null) {
            throw new IllegalArgumentException("RAW 尺寸与传感器有效区域不匹配");
        }
        if (!crop.intersect(raw.getCropRect())) throw new IllegalArgumentException("RAW 裁切区域为空");

        float[] levels = shot.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        BlackLevelPattern fixed = sensor.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        Integer white = shot.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        if (white == null) white = sensor.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if ((levels == null && fixed == null) || (levels != null && levels.length != 4) || white == null) {
            throw new IllegalArgumentException("相机没有提供 RAW 黑白电平");
        }
        int[] channel = new int[4];
        float[] black = new float[4];
        for (int p = 0; p < 4; p++) {
            int x = (originX + crop.left + (p & 1)) & 1;
            int y = (originY + crop.top + (p >> 1)) & 1;
            int physical = y * 2 + x;
            channel[p] = CHANNELS[cfa][physical];
            black[p] = levels != null ? levels[physical] : fixed.getOffsetForIndex(x, y);
        }
        return new RawGeometry(channel, black, white, crop, plane.getRowStride() / 2);
    }

    static float[] readNoiseProfile(TotalCaptureResult shot) {
        @SuppressWarnings("unchecked")
        Pair<Double, Double>[] profile = shot.get(CaptureResult.SENSOR_NOISE_PROFILE);
        if (profile == null || profile.length == 0) return new float[]{0f, 0f};
        double sumS = 0, sumO = 0;
        int n = 0;
        for (Pair<Double, Double> p : profile) {
            if (p != null && p.first != null && p.second != null
                    && Double.isFinite(p.first) && Double.isFinite(p.second)) {
                sumS += p.first;
                sumO += p.second;
                n++;
            }
        }
        return n > 0 ? new float[]{(float) (sumS / n), (float) (sumO / n)} : new float[]{0f, 0f};
    }

    private static Bitmap bitmap(BayerPipeline image, int degrees, Options options,
                                 CameraCharacteristics sensor, TotalCaptureResult shot,
                                 float effectiveFrames, float sceneEv) {
        int rotation = Math.floorMod(degrees, 360);
        if (rotation % 90 != 0) throw new IllegalArgumentException("无效的照片方向");
        boolean swap = rotation % 180 != 0;
        Bitmap output = Bitmap.createBitmap(swap ? image.height : image.width,
                swap ? image.width : image.height, Bitmap.Config.ARGB_8888);
        try {
            float mm = LensCatalog.equivalent(sensor, shot.get(CaptureResult.LENS_FOCAL_LENGTH));
            Integer iso = shot.get(CaptureResult.SENSOR_SENSITIVITY);
            float[] noise = readNoiseProfile(shot);
            boolean useWideGamut = options.natural && options.wideGamut;
            BayerPipeline.Options pipeOpt = new BayerPipeline.Options();
            pipeOpt.natural = options.natural;
            pipeOpt.wideGamut = useWideGamut;
            pipeOpt.equivalentMm = mm;
            pipeOpt.iso = iso == null ? 0 : iso;
            pipeOpt.sceneEv = sceneEv;
            pipeOpt.noiseSlope = noise[0];
            pipeOpt.noiseOffset = noise[1];
            pipeOpt.effectiveFrames = effectiveFrames;
            pipeOpt.userEvBias = options.userEvBias;
            pipeOpt.filter = options.filter;
            pipeOpt.buildGainmap = options.ultraHdr && options.natural
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

            BayerPipeline.GainmapData gainmapData = image.render(rotation, pipeOpt,
                    (pixels, x, y, w, h) -> output.setPixels(pixels, 0, w, x, y, w, h));
            if (useWideGamut) {
                output.setColorSpace(ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
            }
            if (gainmapData != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                attachGainmap(output, gainmapData);
            }
            return output;
        } catch (RuntimeException | Error failure) {
            output.recycle();
            throw failure;
        }
    }

    private static void attachGainmap(Bitmap output, BayerPipeline.GainmapData data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
        Bitmap gmBmp = Bitmap.createBitmap(data.width, data.height, Bitmap.Config.ALPHA_8);
        gmBmp.copyPixelsFromBuffer(ByteBuffer.wrap(data.pixels));
        Gainmap gainmap = new Gainmap(gmBmp);
        gainmap.setRatioMin(1f, 1f, 1f);
        gainmap.setRatioMax(data.maxRatio, data.maxRatio, data.maxRatio);
        gainmap.setGamma(1f, 1f, 1f);
        gainmap.setEpsilonSdr(0.01f, 0.01f, 0.01f);
        gainmap.setEpsilonHdr(0.01f, 0.01f, 0.01f);
        gainmap.setDisplayRatioForFullHdr(data.maxRatio);
        gainmap.setMinDisplayRatioForHdrTransition(1f);
        output.setGainmap(gainmap);
    }

    private static BayerPipeline pipeline(Image raw, CameraCharacteristics sensor, TotalCaptureResult shot,
                                          int maxEdge, Options options, float sceneEv) {
        RawGeometry geo = inspectRaw(raw, sensor, shot);
        ColorCalibration cal = calibrateColor(sensor, shot, options, sceneEv);
        ShadingInfo shade = readShading(shot);
        ShortBuffer data = raw.getPlanes()[0].getBuffer().slice().order(ByteOrder.nativeOrder()).asShortBuffer();
        int width = geo.crop.width(), height = geo.crop.height();
        if (maxEdge > 0 && Math.max(width, height) > maxEdge) {
            float scale = (float) maxEdge / Math.max(width, height);
            int smallW = Math.max(4, Math.round(width * scale) & ~1);
            int smallH = Math.max(4, Math.round(height * scale) & ~1);
            data = BayerPipeline.reduce(data, geo.stride, geo.crop.left, geo.crop.top,
                    width, height, smallW, smallH);
            return new BayerPipeline(data, smallW, 0, 0, smallW, smallH, geo.channel, geo.black, geo.white,
                    cal.balance, cal.transform, shade.map, shade.columns, shade.rows);
        }
        return new BayerPipeline(data, geo.stride, geo.crop.left, geo.crop.top, width, height, geo.channel,
                geo.black, geo.white, cal.balance, cal.transform, shade.map, shade.columns, shade.rows);
    }

    private static final class ColorCalibration {
        final float[] balance;
        final float[] transform;
        ColorCalibration(float[] balance, float[] transform) {
            this.balance = balance;
            this.transform = transform;
        }
    }

    private static final class ShadingInfo {
        final float[] map;
        final int columns, rows;
        ShadingInfo(float[] map, int columns, int rows) {
            this.map = map;
            this.columns = columns;
            this.rows = rows;
        }
    }

    private static ShadingInfo readShading(TotalCaptureResult shot) {
        LensShadingMap lens = shot.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if (lens == null) return new ShadingInfo(null, 0, 0);
        float[] shading = new float[lens.getGainFactorCount()];
        lens.copyGainFactors(shading, 0);
        return new ShadingInfo(shading, lens.getColumnCount(), lens.getRowCount());
    }

    static float computeSceneEv(TotalCaptureResult shot) {
        if (shot == null) return Float.NaN;
        Float aperture = shot.get(CaptureResult.LENS_APERTURE);
        Long expNs = shot.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = shot.get(CaptureResult.SENSOR_SENSITIVITY);
        if (aperture == null || !(aperture > 0f) || expNs == null || expNs <= 0L || iso == null || iso <= 0) {
            return Float.NaN;
        }
        Integer boost = shot.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        double effIso = iso * ((boost != null && boost > 0 ? boost : 100) * 0.01);
        double ev100 = Math.log((aperture * aperture) / (expNs * 1e-9)) / Math.log(2.0)
                - Math.log(effIso / 100.0) / Math.log(2.0);
        return Double.isFinite(ev100) ? (float) ev100 : Float.NaN;
    }

    private static ColorCalibration calibrateColor(CameraCharacteristics sensor, TotalCaptureResult shot,
                                                   Options options, float sceneEv) {
        float[] balance = {1, 1, 1, 1};
        RggbChannelVector wb = shot.get(CaptureResult.COLOR_CORRECTION_GAINS);
        if (wb != null) wb.copyTo(balance, 0);
        float[] halTransform = array(shot.get(CaptureResult.COLOR_CORRECTION_TRANSFORM));

        Rational[] point = shot.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        float[] neutral = null;
        if (point != null && point.length == 3) {
            neutral = new float[]{point[0].floatValue(), point[1].floatValue(), point[2].floatValue()};
        } else if (wb != null) {
            float g = (balance[1] + balance[2]) * 0.5f;
            if (balance[0] > 0 && g > 0 && balance[3] > 0) {
                neutral = new float[]{g / balance[0], 1f, g / balance[3]};
            }
        }

        float[] cal1 = array(sensor.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1));
        float[] cal2 = array(sensor.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2));
        float[] color1 = array(sensor.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
        float[] color2 = array(sensor.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
        float[] forward1 = array(sensor.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1));
        float[] forward2 = array(sensor.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2));
        Number illum1 = sensor.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        Number illum2 = sensor.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);

        boolean useWideGamut = options.natural && options.wideGamut;
        float ambienceRetention = 0f;
        if (options.natural && options.manualKelvin < 2000f) {
            if (Float.isFinite(sceneEv)) {
                float t = Math.max(0f, Math.min(1f, (9.0f - sceneEv) / 3.5f));
                float dimFactor = t * t * (3f - 2f * t);
                ambienceRetention = 0.12f + 0.12f * dimFactor;
            } else {
                ambienceRetention = 0.18f;
            }
        }

        float[] transform;
        if (options.natural && neutral != null
                && (SensorColorMatrix.isUsable(color1) || SensorColorMatrix.isUsable(forward1))) {
            SensorColorMatrix.Result calibrated = SensorColorMatrix.fromDngCalibrated(
                    neutral, cal1, cal2, color1, color2, forward1, forward2,
                    illum1 == null ? 0 : illum1.intValue(),
                    illum2 == null ? 0 : illum2.intValue(),
                    true, options.manualKelvin, options.manualTint,
                    useWideGamut, ambienceRetention);
            float greenMean = (balance[1] + balance[2]) * 0.5f;
            if (!(greenMean > 0)) greenMean = 1f;
            balance = new float[]{
                    1f / calibrated.neutral[0],
                    balance[1] / greenMean,
                    balance[2] / greenMean,
                    1f / calibrated.neutral[2]
            };
            transform = calibrated.wbMatrix;
        } else if (wb != null && SensorColorMatrix.isUsable(halTransform)) {
            transform = options.natural ? SensorColorMatrix.normalizeCcmRows(halTransform) : halTransform;
            if (useWideGamut) transform = SensorColorMatrix.srgbMatrixToDisplayP3(transform);
        } else {
            if (neutral == null) throw new IllegalArgumentException("相机没有提供本次拍摄的白平衡");
            transform = SensorColorMatrix.fromDng(neutral, cal1, cal2, color1, color2, forward1, forward2,
                    illum1 == null ? 0 : illum1.intValue(),
                    illum2 == null ? 0 : illum2.intValue());
            float greenMean = (balance[1] + balance[2]) * 0.5f;
            balance = new float[]{1, balance[1] / greenMean, balance[2] / greenMean, 1};
        }

        Integer postRaw = shot.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        float exposure = postRaw != null && postRaw > 0 ? postRaw * 0.01f : 1f;
        Integer ae = shot.get(CaptureResult.CONTROL_AE_STATE);
        if (Math.abs(options.userEvBias) < 0.05f) {
            float ev = Float.NaN;
            if (ae != null && (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                    || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                    || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) {
                ev = meteredBias(sensor, shot);
            } else if (Float.isFinite(options.fallbackMeteredBiasEv)) {
                ev = options.fallbackMeteredBiasEv;
            }
            if (ev < 0) exposure *= (float) Math.pow(2, 0.95 * Math.min(1f, -ev));
        }
        for (int i = 0; i < transform.length; i++) transform[i] *= exposure;
        return new ColorCalibration(balance, transform);
    }

    static float meteredBias(CameraCharacteristics sensor, TotalCaptureResult shot) {
        Integer steps = shot.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
        Rational step = sensor.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        return steps != null && step != null && step.isFinite() && step.floatValue() > 0
                ? steps * step.floatValue() : Float.NaN;
    }

    private static float[] array(ColorSpaceTransform transform) {
        if (transform == null) return null;
        float[] matrix = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) matrix[row*3 + col] = transform.getElement(col, row).floatValue();
        }
        return matrix;
    }
}
