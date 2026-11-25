package com.truehue.camera;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pairs sensor exposures (single or burst) with their metadata, keeping the reader alive during development. */
final class StillStream {
    interface BurstCallback {
        void onProgress(int framesReceived, int totalFrames);
        void onFirstFrameForDng(Image image, CameraCharacteristics info, TotalCaptureResult result, int degrees) throws Exception;
        void onBurstReady(BurstRawMerger.Result merged, int[] channel, int width, int height,
                          CameraCharacteristics info, TotalCaptureResult referenceResult, int degrees) throws Exception;
        void onFailure(String message);
    }

    private static final ExecutorService DEVELOPMENT = Executors.newSingleThreadExecutor(
            job -> new Thread(job, "TrueHue-Develop"));
    private final Handler worker, main;
    private final ImageReader reader;
    private final CameraCharacteristics info;
    private final String physicalId;
    final int maxImages;
    private Shot pending;
    private Burst activeBurst;
    private Runnable waitingForReader;
    private boolean processing, closed;

    final int format;
    final Size size;

    StillStream(int format, Size size, CameraCharacteristics info, String physicalId,
                int maxImages, Handler worker, Handler main) {
        this.format = format;
        this.size = size;
        this.info = info;
        this.physicalId = physicalId;
        this.maxImages = Math.max(2, maxImages);
        this.worker = worker;
        this.main = main;
        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), format, this.maxImages);
        reader.setOnImageAvailableListener(this::receiveImage, worker);
    }

    Surface surface() { return reader.getSurface(); }

    void capture(CameraCaptureSession session, CameraDevice device, CaptureRequest preview,
                 int degrees, CameraInput.PhotoCallback callback) {
        if (closed) {
            main.post(() -> callback.onFailure("镜头已关闭，拍摄已取消。"));
            return;
        }
        if (pending != null || activeBurst != null || waitingForReader != null) {
            main.post(() -> callback.onFailure("上一张照片尚未处理完成，请稍后重试。"));
            return;
        }
        if (processing) {
            waitingForReader = () -> capture(session, device, preview, degrees, callback);
            return;
        }
        Shot shot = new Shot(callback, degrees);
        pending = shot;
        try {
            CaptureRequest.Builder still = buildStillRequest(device, preview, false);
            worker.postDelayed(shot.timeout, 12000);
            session.capture(still.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s,
                                                         CaptureRequest request, TotalCaptureResult result) {
                    if (pending != shot) return;
                    shot.requestTime = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    shot.result = physicalId == null ? result : result.getPhysicalCameraTotalResults().get(physicalId);
                    if (shot.result == null || (shot.requestTime == null
                            && shot.result.get(CaptureResult.SENSOR_TIMESTAMP) == null)) {
                        fail(shot, "相机缺少本次曝光的镜头参数，请重试。");
                        return;
                    }
                    deliver(shot);
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                    fail(shot, "本次曝光失败，请重新拍摄。");
                }
                @Override public void onCaptureBufferLost(CameraCaptureSession s, CaptureRequest r,
                                                           Surface target, long frameNumber) {
                    fail(shot, "相机丢失了本次曝光，请重新拍摄。");
                }
                @Override public void onCaptureSequenceAborted(CameraCaptureSession s, int sequenceId) {
                    fail(shot, "拍摄被中断，请重新拍摄。");
                }
            }, worker);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("TrueHue.Camera", "Still request rejected", e);
            fail(shot, "无法拍摄这张照片，请重试。");
        }
    }

    void captureBurst(CameraCaptureSession session, CameraDevice device, CaptureRequest preview,
                      int degrees, int burstCount, boolean keepDng, BurstCallback callback) {
        if (closed) {
            main.post(() -> callback.onFailure("镜头已关闭，拍摄已取消。"));
            return;
        }
        if (pending != null || activeBurst != null || waitingForReader != null) {
            main.post(() -> callback.onFailure("上一张照片尚未处理完成，请稍后重试。"));
            return;
        }
        if (processing) {
            waitingForReader = () -> captureBurst(session, device, preview, degrees, burstCount, keepDng, callback);
            return;
        }
        int targetCount = Math.max(2, Math.min(burstCount, Math.max(2, maxImages - 1)));
        Burst burst = new Burst(targetCount, degrees, keepDng, callback);
        activeBurst = burst;
        try {
            List<CaptureRequest> requests = new ArrayList<>(targetCount);
            for (int i = 0; i < targetCount; i++) {
                requests.add(buildStillRequest(device, preview, true).build());
            }
            worker.postDelayed(burst.overallTimeout, 12000);
            session.captureBurst(requests, new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s,
                                                         CaptureRequest r, TotalCaptureResult result) {
                    if (activeBurst != burst || burst.finalizing) return;
                    Long reqTime = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    TotalCaptureResult phys = physicalId == null ? result
                            : result.getPhysicalCameraTotalResults().get(physicalId);
                    Long physTime = phys == null ? null : phys.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (phys == null || (reqTime == null && physTime == null)) {
                        burst.completedOrFailed++;
                        burst.maybeFinalize();
                        return;
                    }
                    burst.onResult(reqTime, physTime, phys);
                }

                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                    if (activeBurst != burst || burst.finalizing) return;
                    burst.completedOrFailed++;
                    burst.maybeFinalize();
                }

                @Override public void onCaptureBufferLost(CameraCaptureSession s, CaptureRequest r,
                                                           Surface target, long frameNumber) {
                    if (activeBurst != burst || burst.finalizing) return;
                    burst.completedOrFailed++;
                    burst.maybeFinalize();
                }

                @Override public void onCaptureSequenceCompleted(CameraCaptureSession s, int sequenceId, long frameNumber) {
                    if (activeBurst != burst || burst.finalizing) return;
                    burst.sequenceDone = true;
                    burst.maybeFinalize();
                }

                @Override public void onCaptureSequenceAborted(CameraCaptureSession s, int sequenceId) {
                    if (activeBurst != burst || burst.finalizing) return;
                    burst.sequenceDone = true;
                    burst.maybeFinalize();
                }
            }, worker);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("TrueHue.Camera", "Burst request rejected", e);
            failBurst(burst, "无法启动连拍 RAW，请重试。");
        }
    }

    private CaptureRequest.Builder buildStillRequest(CameraDevice device, CaptureRequest preview,
                                                     boolean lockExposure) throws CameraAccessException {
        CaptureRequest.Builder still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        still.addTarget(surface());
        copy(preview, still, CaptureRequest.CONTROL_AF_MODE);
        copy(preview, still, CaptureRequest.CONTROL_AF_REGIONS);
        copy(preview, still, CaptureRequest.CONTROL_AE_REGIONS);
        copy(preview, still, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
        copy(preview, still, CaptureRequest.SCALER_ROTATE_AND_CROP);
        if (Boolean.TRUE.equals(info.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE))) {
            still.set(CaptureRequest.CONTROL_AE_LOCK, lockExposure
                    || Boolean.TRUE.equals(preview.get(CaptureRequest.CONTROL_AE_LOCK)));
        }
        int[] ois = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (CameraInput.contains(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            still.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        }
        int[] hotPixel = info.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES);
        if (CameraInput.contains(hotPixel, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)) {
            still.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY);
        }
        int[] aberration = info.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES);
        if (CameraInput.contains(aberration, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)) {
            still.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
        }
        if (format == ImageFormat.RAW_SENSOR) {
            int[] modes = info.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES);
            if (CameraInput.contains(modes, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
                still.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            }
        } else {
            still.set(CaptureRequest.JPEG_QUALITY, (byte) 97);
            still.set(CaptureRequest.JPEG_ORIENTATION, 0);
        }
        return still;
    }

    private static <T> void copy(CaptureRequest source, CaptureRequest.Builder target, CaptureRequest.Key<T> key) {
        T value = source.get(key);
        if (value != null) target.set(key, value);
    }

    private void receiveImage(ImageReader source) {
        if (closed) return;
        Image image;
        try {
            image = source.acquireNextImage();
        } catch (RuntimeException e) {
            if (pending != null) fail(pending, "无法读取相机像素，请重新拍摄。");
            else if (activeBurst != null && activeBurst.matched == 0) {
                failBurst(activeBurst, "无法读取相机像素，请重新拍摄。");
            }
            return;
        }
        if (image == null) return;
        if (activeBurst != null) {
            activeBurst.onImage(image);
            return;
        }
        Shot shot = pending;
        if (shot == null) { image.close(); return; }
        if ((shot.result != null && !shot.matches(image.getTimestamp()))
                || (shot.image != null && image.getTimestamp() <= shot.image.getTimestamp())) {
            image.close();
            return;
        }
        if (shot.image != null) shot.image.close();
        shot.image = image;
        deliver(shot);
    }

    private void deliver(Shot shot) {
        if (shot.image == null || shot.result == null) return;
        if (!shot.matches(shot.image.getTimestamp())) {
            shot.image.close();
            shot.image = null;
            return;
        }
        worker.removeCallbacks(shot.timeout);
        pending = null;
        processing = true;
        DEVELOPMENT.execute(() -> {
            try (Image image = shot.image) {
                shot.callback.onImage(image, info, shot.result, shot.degrees);
            } catch (Exception | OutOfMemoryError e) {
                Log.e("TrueHue.Camera", "Cannot develop the captured photo", e);
                main.post(() -> shot.callback.onFailure("生成照片失败，请重试。"));
            } finally {
                worker.post(() -> {
                    processing = false;
                    if (closed) reader.close();
                    resumeWaiting();
                });
            }
        });
    }

    private void fail(Shot shot, String message) {
        if (pending != shot) return;
        pending = null;
        worker.removeCallbacks(shot.timeout);
        if (shot.image != null) shot.image.close();
        main.post(() -> shot.callback.onFailure(message));
    }

    private void failBurst(Burst burst, String message) {
        if (activeBurst != burst) return;
        activeBurst = null;
        burst.cleanup();
        main.post(() -> burst.callback.onFailure(message));
    }

    private void resumeWaiting() {
        Runnable next = waitingForReader;
        waitingForReader = null;
        if (next != null) next.run();
    }

    void close() {
        if (closed) return;
        closed = true;
        resumeWaiting();
        if (pending != null) fail(pending, "拍摄已取消。");
        if (activeBurst != null) failBurst(activeBurst, "拍摄已取消。");
        reader.setOnImageAvailableListener(null, null);
        if (!processing) reader.close();
    }

    private final class Burst {
        final int targetCount, degrees;
        final boolean keepDng;
        final BurstCallback callback;
        final Map<Long, TotalCaptureResult> results = new LinkedHashMap<>();
        final Map<Long, Long> aliasToKey = new LinkedHashMap<>();
        final Map<Long, Image> images = new LinkedHashMap<>();
        int matched, completedOrFailed;
        boolean sequenceDone, finalizing, failedOnDev;
        BurstRawMerger merger;
        RawRenderer.RawGeometry geo;
        TotalCaptureResult refResult;
        final Runnable overallTimeout = () -> {
            if (activeBurst != this) return;
            if (matched > 0) finalizeBurst();
            else failBurst(this, "等待相机连拍数据超时，请重试。");
        };
        final Runnable graceTimeout = () -> {
            if (activeBurst == this && matched > 0) finalizeBurst();
        };

        Burst(int targetCount, int degrees, boolean keepDng, BurstCallback callback) {
            this.targetCount = targetCount;
            this.degrees = degrees;
            this.keepDng = keepDng;
            this.callback = callback;
        }

        void onResult(Long reqTime, Long physTime, TotalCaptureResult phys) {
            long key = physTime != null ? physTime : reqTime;
            Image img = images.remove(key);
            if (img == null && reqTime != null && !reqTime.equals(key)) {
                img = images.remove(reqTime);
            }
            if (img != null) {
                pair(img, phys);
            } else {
                results.put(key, phys);
                if (reqTime != null && !reqTime.equals(key)) aliasToKey.put(reqTime, key);
            }
        }

        void onImage(Image image) {
            if (finalizing) {
                image.close();
                return;
            }
            long ts = image.getTimestamp();
            TotalCaptureResult phys = results.remove(ts);
            if (phys == null) {
                Long key = aliasToKey.remove(ts);
                if (key != null) phys = results.remove(key);
            }
            if (phys != null) {
                pair(image, phys);
            } else {
                Image prev = images.put(ts, image);
                if (prev != null) prev.close();
            }
        }

        private void pair(Image image, TotalCaptureResult phys) {
            matched++;
            completedOrFailed++;
            int countNow = matched;
            main.post(() -> callback.onProgress(countNow, targetCount));
            if (countNow == 1) {
                worker.postDelayed(graceTimeout, 1800);
                processing = true;
            }
            DEVELOPMENT.execute(() -> {
                try (Image frame = image) {
                    if (failedOnDev) return;
                    if (countNow == 1) {
                        geo = RawRenderer.inspectRaw(frame, info, phys);
                        refResult = phys;
                        if (keepDng) {
                            callback.onFirstFrameForDng(frame, info, phys, degrees);
                        }
                        float[] noise = RawRenderer.readNoiseProfile(phys);
                        ShortBuffer buf = frame.getPlanes()[0].getBuffer().slice()
                                .order(ByteOrder.nativeOrder()).asShortBuffer();
                        short[] refPixels = BurstRawMerger.extractActive(buf, geo.stride,
                                geo.crop.left, geo.crop.top, geo.crop.width(), geo.crop.height());
                        merger = new BurstRawMerger(geo.crop.width(), geo.crop.height(),
                                geo.channel, geo.black, geo.white, noise[0], noise[1]);
                        merger.setReference(refPixels);
                    } else if (merger != null && geo != null) {
                        RawRenderer.RawGeometry g2 = RawRenderer.inspectRaw(frame, info, phys);
                        if (g2.crop.width() == geo.crop.width() && g2.crop.height() == geo.crop.height()) {
                            ShortBuffer buf = frame.getPlanes()[0].getBuffer().slice()
                                    .order(ByteOrder.nativeOrder()).asShortBuffer();
                            short[] alt = BurstRawMerger.extractActive(buf, g2.stride,
                                    g2.crop.left, g2.crop.top, g2.crop.width(), g2.crop.height());
                            merger.mergeFrame(alt, BayerPipeline.POOL);
                        }
                    }
                } catch (Exception | OutOfMemoryError e) {
                    Log.e("TrueHue.Camera", "Burst frame processing error", e);
                    if (countNow == 1) {
                        failedOnDev = true;
                        main.post(() -> callback.onFailure("生成照片失败，请重试。"));
                    }
                }
            });
            maybeFinalize();
        }

        void maybeFinalize() {
            if (finalizing) return;
            if (matched >= targetCount || (sequenceDone && matched > 0 && completedOrFailed >= targetCount)) {
                finalizeBurst();
            } else if (sequenceDone && matched == 0 && completedOrFailed >= targetCount) {
                failBurst(this, "本次连拍未能获取有效画面，请重试。");
            }
        }

        void finalizeBurst() {
            if (finalizing || activeBurst != this) return;
            finalizing = true;
            activeBurst = null;
            cleanup();
            DEVELOPMENT.execute(() -> {
                try {
                    if (!failedOnDev && merger != null && geo != null && refResult != null) {
                        BurstRawMerger.Result merged = merger.finish();
                        merger = null;
                        callback.onBurstReady(merged, geo.channel, geo.crop.width(), geo.crop.height(),
                                info, refResult, degrees);
                    }
                } catch (Exception | OutOfMemoryError e) {
                    Log.e("TrueHue.Camera", "Burst merge/develop failed", e);
                    main.post(() -> callback.onFailure("生成照片失败，请重试。"));
                } finally {
                    merger = null;
                    worker.post(() -> {
                        processing = false;
                        if (closed) reader.close();
                        resumeWaiting();
                    });
                }
            });
        }

        void cleanup() {
            worker.removeCallbacks(overallTimeout);
            worker.removeCallbacks(graceTimeout);
            for (Image img : images.values()) img.close();
            images.clear();
            results.clear();
            aliasToKey.clear();
        }
    }

    private final class Shot {
        final CameraInput.PhotoCallback callback;
        final int degrees;
        final Runnable timeout = () -> fail(this, "等待相机数据超时，请重试。");
        Image image;
        TotalCaptureResult result;
        Long requestTime;

        boolean matches(long bufferTime) {
            Long sensorTime = result.get(CaptureResult.SENSOR_TIMESTAMP);
            return (sensorTime != null && sensorTime == bufferTime)
                    || (requestTime != null && requestTime == bufferTime);
        }

        Shot(CameraInput.PhotoCallback callback, int degrees) {
            this.callback = callback;
            this.degrees = degrees;
        }
    }
}
