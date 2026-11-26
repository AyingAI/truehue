package com.truehue.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** SDR viewfinder with independent, native-lens still/burst capture, AF/AE touch, and telemetry. */
public final class CameraInput {
    public interface Listener {
        void onLenses(List<Lens> lenses, String selectedId);
        void onConfigured(int width, int height, int sensorOrientation, int stillFormat, int stillWidth, int stillHeight);
        void onCameraError(String message);
        default void onTelemetry(Telemetry telemetry) {}
    }

    public interface PhotoCallback {
        /** Called on the development thread. The image stays valid until this method returns. */
        void onImage(Image image, CameraCharacteristics info, TotalCaptureResult result,
                     int clockwiseDegrees) throws Exception;
        void onFailure(String message);
    }

    public static final class Telemetry {
        public final int iso;
        public final long exposureNs;
        public final float aperture;
        public final int kelvin;
        public final int afState;
        public final float evBias;

        Telemetry(int iso, long exposureNs, float aperture, int kelvin, int afState, float evBias) {
            this.iso = iso;
            this.exposureNs = exposureNs;
            this.aperture = aperture;
            this.kelvin = kelvin;
            this.afState = afState;
            this.evBias = evBias;
        }
    }

    public static final class Lens {
        public final String id, label;
        public final int equivalentMm;
        final String deviceId, physicalId;
        final CameraCharacteristics info;
        Lens(String id, String label, int equivalentMm, String deviceId, String physicalId,
             CameraCharacteristics info) {
            this.id = id;
            this.label = label;
            this.equivalentMm = equivalentMm;
            this.deviceId = deviceId;
            this.physicalId = physicalId;
            this.info = info;
        }
    }

    private static final String TAG = "TrueHue.Camera";
    private static final Handler WORKER = createWorker();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CameraManager manager;
    private final Listener listener;
    private volatile Run current;

    public CameraInput(Context context, Listener listener) {
        this.manager = context.getApplicationContext().getSystemService(CameraManager.class);
        this.listener = listener;
    }

    public void open(SurfaceTexture input) { open(input, null); }

    public void open(SurfaceTexture input, String lensId) {
        close();
        Run run = new Run(input, lensId);
        current = run;
        WORKER.post(run::start);
    }

    public void close() {
        Run previous = current;
        current = null;
        if (previous != null) WORKER.post(previous::release);
    }

    public void capture(int deviceRotation, PhotoCallback callback) {
        Run run = current;
        WORKER.post(() -> {
            if (run == null || !run.active() || run.still == null || run.session == null || run.request == null) {
                main.post(() -> callback.onFailure("镜头尚未准备好，请重试。"));
                return;
            }
            int rotation = Math.floorMod(run.orientation() + deviceRotation, 360);
            run.still.capture(run.session, run.device, run.request.build(), rotation, callback);
        });
    }

    void captureBurst(int deviceRotation, int burstCount, boolean keepDng, StillStream.BurstCallback callback) {
        Run run = current;
        WORKER.post(() -> {
            if (run == null || !run.active() || run.still == null || run.session == null || run.request == null) {
                main.post(() -> callback.onFailure("镜头尚未准备好，请重试。"));
                return;
            }
            int rotation = Math.floorMod(run.orientation() + deviceRotation, 360);
            run.still.captureBurst(run.session, run.device, run.request.build(), rotation,
                    burstCount, keepDng, run.computeSafeExposure(), callback);
        });
    }

    public boolean supportsBurst() {
        Run run = current;
        return run != null && run.active() && run.still != null
                && run.still.format == ImageFormat.RAW_SENSOR && run.still.maxImages >= 4;
    }

    public int latestIso() {
        Run run = current;
        return run == null ? 0 : run.latestIso;
    }

    public long latestExposureNs() {
        Run run = current;
        return run == null ? 0L : run.latestExposureNs;
    }

    public boolean isHighlightProtected() {
        Run run = current;
        return run != null && run.highlightProtected;
    }

    public void protectHighlights(boolean enabled) {
        Run run = current;
        if (run != null) WORKER.post(() -> run.setHighlightProtection(enabled));
    }

    public void setUserExposureEv(float ev) {
        Run run = current;
        if (run != null) WORKER.post(() -> run.setUserExposureEv(ev));
    }

    public float[] getEvRange() {
        Run run = current;
        return run == null ? new float[]{-2f, 2f, 1f / 6f} : run.evRange();
    }

    public void focusAndMeter(float normX, float normY, boolean lockAeAf) {
        Run run = current;
        if (run != null) WORKER.post(() -> run.focusAndMeter(normX, normY, lockAeAf));
    }

    public void clearFocusLock() {
        Run run = current;
        if (run != null) WORKER.post(run::clearFocusLock);
    }

    private static Handler createWorker() {
        HandlerThread thread = new HandlerThread("TrueHue-Camera");
        thread.start();
        return new Handler(thread.getLooper());
    }

    private final class Run {
        final SurfaceTexture input;
        CameraCharacteristics characteristics;
        CameraDevice device;
        CameraCaptureSession session;
        CaptureRequest.Builder request;
        Surface surface;
        final String requestedLens;
        Lens lens;
        final List<Setup> setups = new ArrayList<>();
        int index;
        StillStream still;
        Setup selected;
        int guardSteps, userSteps, appliedSteps;
        float evStepValue = 1f / 6f, evMin = -2f, evMax = 2f;
        volatile boolean highlightProtected;
        volatile int latestIso;
        volatile long latestExposureNs;
        volatile int latestPostRawBoost = 100;
        volatile int latestAeState;
        long lastTelemetryMs;
        final Runnable resetAfTask = this::clearFocusLock;
        volatile boolean ended;

        Run(SurfaceTexture input, String requestedLens) {
            this.input = input;
            this.requestedLens = requestedLens;
        }

        int orientation() {
            Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            return sensor == null ? 90 : sensor;
        }

        boolean active() { return current == this && !ended; }

        float[] evRange() {
            return new float[]{evMin, evMax, evStepValue};
        }

        @SuppressLint("MissingPermission")
        void start() {
            if (!active()) return;
            try {
                List<Lens> lenses = LensCatalog.discover(manager);
                lens = LensCatalog.choose(lenses, requestedLens);
                if (lens == null) {
                    fail("没有找到可用的后置相机。", null);
                    return;
                }
                main.post(() -> {
                    if (active()) listener.onLenses(Collections.unmodifiableList(lenses), lens.id);
                });
                addSetups(lens.info, lens.physicalId, false);
                if (lens.physicalId != null && lens.label.equals("主摄")) {
                    addSetups(manager.getCameraCharacteristics(lens.deviceId), null, false);
                }
                addSetups(lens.info, lens.physicalId, true);
                if (lens.physicalId != null && lens.label.equals("主摄")) {
                    addSetups(manager.getCameraCharacteristics(lens.deviceId), null, true);
                }
                if (setups.isEmpty()) {
                    fail("这个镜头没有提供可用的取景输出。", null);
                    return;
                }
                manager.openCamera(lens.deviceId, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice opened) {
                        if (!active()) { opened.close(); return; }
                        device = opened;
                        configure();
                    }

                    @Override public void onDisconnected(CameraDevice opened) {
                        opened.close();
                        fail("相机连接已中断，请重试。", null);
                    }

                    @Override public void onError(CameraDevice opened, int error) {
                        opened.close();
                        String message;
                        switch (error) {
                            case ERROR_CAMERA_IN_USE:
                            case ERROR_MAX_CAMERAS_IN_USE:
                                message = "相机正被占用，请关闭其他相机应用后重试。";
                                break;
                            case ERROR_CAMERA_DISABLED:
                                message = "系统已停用相机，请检查相机访问设置。";
                                break;
                            default:
                                message = "相机发生错误，请重试。";
                        }
                        fail(message, null);
                    }
                }, WORKER);
            } catch (SecurityException e) {
                fail("请在系统设置中允许访问相机。", e);
            } catch (CameraAccessException | RuntimeException e) {
                fail("无法打开后置相机，请检查相机访问权限并重试。", e);
            }
        }

        void addSetups(CameraCharacteristics info, String physicalId, boolean previewOnly) {
            StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            List<Size> previews = previewSizes(map);
            if (previews.isEmpty()) return;
            int[] formats = previewOnly ? new int[]{ImageFormat.UNKNOWN}
                    : new int[]{ImageFormat.RAW_SENSOR, ImageFormat.JPEG};
            for (int format : formats) {
                if (format == ImageFormat.RAW_SENSOR) {
                    Integer layout = info.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                    if (layout == null || layout < 0 || layout > 3) continue;
                }
                Size full = format == ImageFormat.UNKNOWN ? null : largest(map, format);
                if (format != ImageFormat.UNKNOWN && full == null) continue;
                for (int i = 0; i < previews.size(); i++) {
                    Size preview = previews.get(i);
                    if (format == ImageFormat.RAW_SENSOR && i == 0) {
                        setups.add(new Setup(info, physicalId, preview, format, full, 6));
                    }
                    setups.add(new Setup(info, physicalId, preview, format, full, 2));
                }
            }
        }

        void configure() {
            if (!active()) return;
            Setup setup = setups.get(index);
            selected = setup;
            characteristics = setup.info;
            try {
                releaseOutputs();
                input.setDefaultBufferSize(setup.preview.getWidth(), setup.preview.getHeight());
                surface = new Surface(input);
                List<OutputConfiguration> outputs = new ArrayList<>();
                outputs.add(output(surface, setup.physicalId));
                if (setup.stillSize != null) {
                    still = new StillStream(setup.format, setup.stillSize, characteristics,
                            setup.physicalId, setup.maxImages, WORKER, main);
                    OutputConfiguration capture = output(still.surface(), setup.physicalId);
                    if (Build.VERSION.SDK_INT >= 33) capture.setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_SENSOR);
                    outputs.add(capture);
                }
                SessionConfiguration config = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, outputs, WORKER::post,
                        new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession configured) {
                        if (!active() || selected != setup) { configured.close(); return; }
                        session = configured;
                        startPreview(setup.preview);
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession failed) {
                        failed.close();
                        if (selected == setup) tryNextConfiguration(null);
                    }
                });
                try {
                    if (!device.isSessionConfigurationSupported(config)) {
                        tryNextConfiguration(null);
                        return;
                    }
                } catch (UnsupportedOperationException ignored) {
                }
                device.createCaptureSession(config);
            } catch (CameraAccessException | RuntimeException e) {
                tryNextConfiguration(e);
            }
        }

        OutputConfiguration output(Surface endpoint, String physicalId) {
            OutputConfiguration output = new OutputConfiguration(endpoint);
            if (physicalId != null) output.setPhysicalCameraId(physicalId);
            return output;
        }

        void tryNextConfiguration(Exception cause) {
            if (!active()) return;
            if (input.isReleased()) {
                fail("取景画面已关闭，请重新打开相机。", cause);
                return;
            }
            Log.i(TAG, "Unavailable device=" + lens.deviceId + " physical=" + selected.physicalId
                    + " still=" + selected.format + " maxImg=" + selected.maxImages
                    + " preview=" + selected.preview, cause);
            if (++index >= setups.size()) {
                fail("系统无法打开这个镜头的相机输出，请重试或切换镜头。", cause);
                return;
            }
            WORKER.post(this::configure);
        }

        void releaseOutputs() {
            if (surface != null) { surface.release(); surface = null; }
            if (still != null) { still.close(); still = null; }
        }

        void startPreview(Size size) {
            try {
                CameraCharacteristics deviceInfo = manager.getCameraCharacteristics(lens.deviceId);
                Range<Integer> range = deviceInfo.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                Rational step = deviceInfo.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
                if (range != null && step != null && step.floatValue() > 0 && step.floatValue() <= 1) {
                    evStepValue = step.floatValue();
                    evMin = range.getLower() * evStepValue;
                    evMax = range.getUpper() * evStepValue;
                    guardSteps = range.clamp(-Math.round((2f / 3f) / evStepValue));
                }
                request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                request.addTarget(surface);
                int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                if (contains(modes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                }
                int[] rotations = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES);
                if (contains(rotations, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)) {
                    request.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
                }
                int[] ois = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
                if (contains(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                    request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                }
                setRepeating();
                Setup configured = selected;
                Log.i(TAG, "Configured lens=" + lens.id + " device=" + lens.deviceId
                        + " physical=" + configured.physicalId + " format=" + configured.format
                        + " maxImg=" + configured.maxImages
                        + " size=" + configured.stillSize + " preview=" + size);
                main.post(() -> {
                    if (active()) listener.onConfigured(size.getWidth(), size.getHeight(), orientation(),
                            configured.format,
                            configured.stillSize == null ? 0 : configured.stillSize.getWidth(),
                            configured.stillSize == null ? 0 : configured.stillSize.getHeight());
                });
            } catch (CameraAccessException | RuntimeException e) {
                fail("无法启动相机取景，请重试。", e);
            }
        }

        private void setRepeating() throws CameraAccessException {
            session.setRepeatingRequest(request.build(), previewCallback, WORKER);
        }

        private final CameraCaptureSession.CaptureCallback previewCallback =
                new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession s,
                                                     CaptureRequest r, TotalCaptureResult result) {
                if (!active()) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastTelemetryMs < 180) return;
                lastTelemetryMs = now;
                TotalCaptureResult phys = selected != null && selected.physicalId != null
                        ? result.getPhysicalCameraTotalResults().get(selected.physicalId) : null;
                TotalCaptureResult src = phys != null ? phys : result;
                Integer isoVal = src.get(CaptureResult.SENSOR_SENSITIVITY);
                Long expVal = src.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Float apVal = src.get(CaptureResult.LENS_APERTURE);
                Integer afVal = src.get(CaptureResult.CONTROL_AF_STATE);
                Integer boostVal = src.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
                Integer aeVal = src.get(CaptureResult.CONTROL_AE_STATE);
                if (isoVal != null) latestIso = isoVal;
                if (expVal != null) latestExposureNs = expVal;
                if (boostVal != null && boostVal > 0) latestPostRawBoost = boostVal;
                if (aeVal != null) latestAeState = aeVal;
                int kelvin = Math.round(estimateResultKelvin(src));
                float bias = appliedSteps * evStepValue;
                Telemetry t = new Telemetry(
                        isoVal == null ? 0 : isoVal,
                        expVal == null ? 0L : expVal,
                        apVal == null ? 0f : apVal,
                        kelvin,
                        afVal == null ? 0 : afVal,
                        bias);
                main.post(() -> {
                    if (active()) listener.onTelemetry(t);
                });
            }
        };

        private float estimateResultKelvin(TotalCaptureResult shot) {
            float[] neutral = null;
            Rational[] pt = shot.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (pt != null && pt.length == 3) {
                neutral = new float[]{pt[0].floatValue(), pt[1].floatValue(), pt[2].floatValue()};
            } else {
                RggbChannelVector wb = shot.get(CaptureResult.COLOR_CORRECTION_GAINS);
                if (wb != null) {
                    float[] b = new float[4];
                    wb.copyTo(b, 0);
                    float g = 0.5f * (b[1] + b[2]);
                    if (b[0] > 0 && g > 0 && b[3] > 0) neutral = new float[]{g / b[0], 1f, g / b[3]};
                }
            }
            if (neutral == null || characteristics == null) return 0f;
            Number i1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
            Number i2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
            return SensorColorMatrix.estimateKelvin(neutral,
                    toArray(characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
                    toArray(characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
                    toArray(characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)),
                    toArray(characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
                    i1 == null ? 0 : i1.intValue(), i2 == null ? 0 : i2.intValue());
        }

        StillStream.SafeExposure computeSafeExposure() {
            if (characteristics == null || lens == null || latestExposureNs <= 0L || latestIso <= 0) return null;
            if (latestAeState != CaptureResult.CONTROL_AE_STATE_CONVERGED
                    && latestAeState != CaptureResult.CONTROL_AE_STATE_LOCKED
                    && latestAeState != CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                return null;
            }
            int[] caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (!contains(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) return null;
            Range<Integer> sensRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> expRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (sensRange == null || expRange == null) return null;

            int[] oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            boolean hasOis = contains(oisModes, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            long maxSafeExpNs;
            if (lens.equivalentMm > 0 && lens.equivalentMm < 20) {
                maxSafeExpNs = 40_000_000L;
            } else if (lens.equivalentMm >= 45) {
                maxSafeExpNs = hasOis ? 20_000_000L : 12_500_000L;
            } else {
                maxSafeExpNs = hasOis ? 33_333_333L : 25_000_000L;
            }
            if (latestExposureNs <= maxSafeExpNs) return null;

            double maxScale = Math.min(2.5, (double) sensRange.getUpper() / latestIso);
            double scale = Math.min(maxScale, (double) latestExposureNs / maxSafeExpNs);
            if (scale < 1.25) return null;

            long targetExp = Math.max(expRange.getLower(),
                    Math.min(expRange.getUpper(), Math.round(latestExposureNs / scale)));
            int targetIso = Math.max(sensRange.getLower(),
                    Math.min(sensRange.getUpper(), (int) Math.round(latestIso * scale)));
            float fallbackEv = (userSteps == 0 && highlightProtected) ? (guardSteps * evStepValue) : Float.NaN;
            Log.i(TAG, String.format("Anti-shake split: exp %.1fms->%.1fms, ISO %d->%d (lens=%dmm, OIS=%s)",
                    latestExposureNs * 1e-6, targetExp * 1e-6, latestIso, targetIso, lens.equivalentMm, hasOis));
            return new StillStream.SafeExposure(targetExp, targetIso, latestPostRawBoost, fallbackEv);
        }

        void setHighlightProtection(boolean enabled) {
            highlightProtected = enabled;
            applyExposureSteps();
        }

        void setUserExposureEv(float ev) {
            if (Math.abs(ev) < 0.02f) {
                userSteps = 0;
            } else if (evStepValue > 0) {
                int minS = Math.round(evMin / evStepValue);
                int maxS = Math.round(evMax / evStepValue);
                userSteps = Math.max(minS, Math.min(maxS, Math.round(ev / evStepValue)));
            }
            applyExposureSteps();
        }

        private void applyExposureSteps() {
            int steps = userSteps != 0 ? userSteps : (highlightProtected ? guardSteps : 0);
            if (!active() || session == null || request == null || steps == appliedSteps) return;
            try {
                request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps);
                setRepeating();
                appliedSteps = steps;
            } catch (CameraAccessException | RuntimeException e) {
                request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, appliedSteps);
                guardSteps = 0;
                userSteps = 0;
                Log.w(TAG, "Exposure compensation unavailable for this lens", e);
            }
        }

        void focusAndMeter(float normX, float normY, boolean lockAeAf) {
            if (!active() || session == null || request == null || characteristics == null) return;
            WORKER.removeCallbacks(resetAfTask);
            Rect activeRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (activeRect == null) return;
            float nx = Math.max(0.05f, Math.min(0.95f, normX));
            float ny = Math.max(0.05f, Math.min(0.95f, normY));
            float sxNorm, syNorm;
            switch (Math.floorMod(orientation(), 360)) {
                case 90:  sxNorm = ny; syNorm = 1f - nx; break;
                case 180: sxNorm = 1f - nx; syNorm = 1f - ny; break;
                case 270: sxNorm = 1f - ny; syNorm = nx; break;
                default:  sxNorm = nx; syNorm = ny; break;
            }
            int cx = activeRect.left + Math.round(sxNorm * activeRect.width());
            int cy = activeRect.top + Math.round(syNorm * activeRect.height());
            int halfW = Math.max(48, Math.round(activeRect.width() * 0.07f));
            int halfH = Math.max(48, Math.round(activeRect.height() * 0.07f));
            int left = Math.max(activeRect.left, Math.min(activeRect.right - 2 * halfW, cx - halfW));
            int top = Math.max(activeRect.top, Math.min(activeRect.bottom - 2 * halfH, cy - halfH));
            MeteringRectangle[] rect = {new MeteringRectangle(left, top, 2 * halfW, 2 * halfH, 1000)};

            try {
                Integer maxAf = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                Integer maxAe = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
                if (maxAf != null && maxAf > 0) request.set(CaptureRequest.CONTROL_AF_REGIONS, rect);
                if (maxAe != null && maxAe > 0) request.set(CaptureRequest.CONTROL_AE_REGIONS, rect);
                int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                    request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                }
                if (Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE))) {
                    request.set(CaptureRequest.CONTROL_AE_LOCK, lockAeAf);
                }
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                setRepeating();

                // Fire single AF trigger.
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(request.build(), null, WORKER);
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                if (!lockAeAf) {
                    WORKER.postDelayed(resetAfTask, 4500);
                }
            } catch (CameraAccessException | RuntimeException e) {
                Log.w(TAG, "Tap focus/meter unavailable", e);
            }
        }

        void clearFocusLock() {
            if (!active() || session == null || request == null || characteristics == null) return;
            WORKER.removeCallbacks(resetAfTask);
            try {
                int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                }
                request.set(CaptureRequest.CONTROL_AF_REGIONS, null);
                request.set(CaptureRequest.CONTROL_AE_REGIONS, null);
                if (Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE))) {
                    request.set(CaptureRequest.CONTROL_AE_LOCK, false);
                }
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                session.capture(request.build(), null, WORKER);
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                setRepeating();
            } catch (CameraAccessException | RuntimeException e) {
                Log.w(TAG, "Clear AF/AE lock failed", e);
            }
        }

        void fail(String message, Exception cause) {
            if (!active()) return;
            Log.w(TAG, message, cause);
            release();
            main.post(() -> {
                if (current == this) listener.onCameraError(message);
            });
        }

        void release() {
            ended = true;
            WORKER.removeCallbacks(resetAfTask);
            if (session != null) { session.close(); session = null; }
            if (device != null) { device.close(); device = null; }
            releaseOutputs();
        }
    }

    private static float[] toArray(ColorSpaceTransform transform) {
        if (transform == null) return null;
        float[] matrix = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) matrix[row * 3 + col] = transform.getElement(col, row).floatValue();
        }
        return matrix;
    }

    private static final class Setup {
        final CameraCharacteristics info;
        final String physicalId;
        final Size preview, stillSize;
        final int format, maxImages;
        Setup(CameraCharacteristics info, String physicalId, Size preview, int format, Size stillSize, int maxImages) {
            this.info = info;
            this.physicalId = physicalId;
            this.preview = preview;
            this.format = format;
            this.stillSize = stillSize;
            this.maxImages = maxImages;
        }
    }

    private static Size largest(StreamConfigurationMap map, int format) {
        Size best = null;
        Size[][] groups = {map.getOutputSizes(format), map.getHighResolutionOutputSizes(format)};
        for (Size[] group : groups) {
            if (group == null) continue;
            for (Size candidate : group) {
                if (best == null || pixels(candidate) > pixels(best)) best = candidate;
            }
        }
        return best;
    }

    static boolean contains(int[] choices, int value) {
        if (choices != null) for (int choice : choices) if (choice == value) return true;
        return false;
    }

    private static long pixels(Size size) { return (long) size.getWidth() * size.getHeight(); }

    private static List<Size> previewSizes(StreamConfigurationMap map) {
        List<Size> sizes = new ArrayList<>();
        Size[] available = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (available == null) return sizes;
        for (Size size : available) {
            if (size.getWidth() <= 1920 && size.getHeight() <= 1080) sizes.add(size);
        }
        sizes.sort(Comparator.<Size>comparingInt(s ->
                map.getOutputMinFrameDuration(SurfaceTexture.class, s) > 41_700_000L ? 1 : 0)
                .thenComparingInt(s -> pixels(s) >= 850_000 ? 0 : 1)
                .thenComparingInt(s -> Math.abs(s.getWidth() / (double) s.getHeight() - 4.0 / 3.0) < 0.025 ? 0 : 1)
                .thenComparing(Comparator.comparingLong(CameraInput::pixels).reversed()));
        if (sizes.size() <= 3) return sizes;
        List<Size> candidates = new ArrayList<>();
        candidates.add(sizes.get(0));
        for (Size size : sizes) {
            if (size.getWidth() == 1280 && size.getHeight() == 720 && !candidates.contains(size)) candidates.add(size);
        }
        for (Size size : sizes) {
            if (size.getWidth() == 640 && size.getHeight() == 480 && !candidates.contains(size)) candidates.add(size);
        }
        return candidates;
    }
}
