package com.truehue.camera;

import android.graphics.Bitmap;
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
        boolean ultraHdr = false;
        PhotoFilter filter = PhotoFilter.ORIGINAL;
        float manualKelvin = 0f;
        float manualTint = 0f;
        float userEvBias = 0f;
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
        return bitmap(pipeline(raw, sensor, shot, 0, options), clockwiseDegrees, options, sensor, shot, 1f);
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
        return bitmap(pipeline(raw, sensor, shot, maxEdge, options), clockwiseDegrees, options, sensor, shot, 1f);
    }

    static Bitmap renderMerged(BurstRawMerger.Result merged, int[] channel, int width, int height,
                               CameraCharacteristics sensor, TotalCaptureResult shot,
                               int clockwiseDegrees, Options options) {
        ColorCalibration cal = calibrateColor(sensor, shot, options);
        ShadingInfo shade = readShading(shot);
        BayerPipeline pipe = new BayerPipeline(merged.bayer12, width, 0, 0, width, height, channel,
                merged.black12, merged.white12, cal.balance, cal.transform,
                shade.map, shade.columns, shade.rows);
        return bitmap(pipe, clockwiseDegrees, options, sensor, shot, merged.effectiveFrames);
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
                                 float effectiveFrames) {
        int rotation = Math.floorMod(degrees, 360);
        if (rotation % 90 != 0) throw new IllegalArgumentException("无效的照片方向");
        boolean swap = rotation % 180 != 0;
        Bitmap output = Bitmap.createBitmap(swap ? image.height : image.width,
                swap ? image.width : image.height, Bitmap.Config.ARGB_8888);
        try {
            float mm = LensCatalog.equivalent(sensor, shot.get(CaptureResult.LENS_FOCAL_LENGTH));
            Integer iso = shot.get(CaptureResult.SENSOR_SENSITIVITY);
            float[] noise = readNoiseProfile(shot);
            BayerPipeline.Options pipeOpt = new BayerPipeline.Options();
            pipeOpt.natural = options.natural;
            pipeOpt.equivalentMm = mm;
            pipeOpt.iso = iso == null ? 0 : iso;
            pipeOpt.noiseSlope = noise[0];
            pipeOpt.noiseOffset = noise[1];
            pipeOpt.effectiveFrames = effectiveFrames;
            pipeOpt.userEvBias = options.userEvBias;
            pipeOpt.filter = options.filter;
            pipeOpt.buildGainmap = options.ultraHdr && options.natural
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

            BayerPipeline.GainmapData gainmapData = image.render(rotation, pipeOpt,
                    (pixels, x, y, w, h) -> output.setPixels(pixels, 0, w, x, y, w, h));
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
                                          int maxEdge, Options options) {
        RawGeometry geo = inspectRaw(raw, sensor, shot);
        ColorCalibration cal = calibrateColor(sensor, shot, options);
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

    private static ColorCalibration calibrateColor(CameraCharacteristics sensor, TotalCaptureResult shot,
                                                   Options options) {
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

        float[] transform;
        if (options.natural && neutral != null
                && (SensorColorMatrix.isUsable(color1) || SensorColorMatrix.isUsable(forward1))) {
            SensorColorMatrix.Result calibrated = SensorColorMatrix.fromDngCalibrated(
                    neutral, cal1, cal2, color1, color2, forward1, forward2,
                    illum1 == null ? 0 : illum1.intValue(),
                    illum2 == null ? 0 : illum2.intValue(),
                    true, options.manualKelvin, options.manualTint);
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
        if (Math.abs(options.userEvBias) < 0.05f && ae != null
                && (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) {
            float ev = meteredBias(sensor, shot);
            if (ev < 0) exposure *= (float) Math.pow(2, 0.8 * Math.min(1f, -ev));
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
