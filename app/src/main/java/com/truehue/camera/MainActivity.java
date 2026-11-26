package com.truehue.camera;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements SensorEventListener {
    private static final int CAMERA_PERMISSION = 1;
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private CameraScreen ui;
    private CameraInput camera;
    private FrameRenderer renderer;
    private GLSurfaceView surface;
    private OrientationEventListener orientation;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private final float[] gravity = new float[3];
    private boolean hasGravity;

    private Uri lastPhoto;
    private int run, deviceRotation;
    private int stillFormat, stillWidth, stillHeight, previewWidth, previewHeight;
    private int currentLensMm;
    private String selectedLens;

    private boolean sameFrame, keepDng, liveRaw, multiFrame, ultraHdr, showGrid, showHistogram;
    private boolean showProInfo, guideShown;
    private boolean proofUnavailable, proofInFlight, waitingForProof, dialogOpen;
    private PhotoFilter filter;
    private Runnable unregisterTrayBack;
    private boolean compareBaseline; // Set only by the paired instrumented capture test.
    private float userEvBias, manualKelvin, manualTint;
    private boolean highlightProtecting, showingTransientStatus;
    private CameraInput.Telemetry latestTelemetry;
    private boolean foreground, ready, busy;
    private long shutterTime;

    private final Runnable nextProof = this::requestRawPreview;
    private final Runnable clearStatus = () -> {
        showingTransientStatus = false;
        if (ready && !busy) setStatus();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        sameFrame = prefs.getBoolean("sameFrame", false);
        keepDng = prefs.getBoolean("keepDng", false);
        liveRaw = prefs.getBoolean("liveRaw", false);
        multiFrame = prefs.getBoolean("multiFrame", true);
        ultraHdr = prefs.getBoolean("ultraHdr", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
        showGrid = prefs.getBoolean("showGrid", true);
        showHistogram = prefs.getBoolean("showHistogram", false);
        showProInfo = prefs.getBoolean("showProInfo", false);
        guideShown = prefs.getBoolean("guideShown", false);
        filter = PhotoFilter.fromId(prefs.getString("photoFilter", null));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setColorMode(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT);
        ui = new CameraScreen(this);
        setContentView(ui);
        ui.showThumbnail(null, false);
        ui.setShowGrid(showGrid);
        ui.overlay.setShowHistogram(showHistogram);
        ui.setProInfoVisible(showProInfo);
        updateQuickToggles();
        ui.updateProChips(userEvBias, manualKelvin);

        ui.shutter.setOnClickListener(v -> pressShutter());
        ui.gallery.setOnClickListener(v -> review());
        ui.helpButton.setOnClickListener(v -> showGuide());
        ui.filterButton.setOnClickListener(v -> {
            if (ready && !busy && !dialogOpen) {
                setTrayOpen(ui.activeTray() == CameraScreen.TRAY_FILTER
                        ? CameraScreen.TRAY_LENS : CameraScreen.TRAY_FILTER);
            }
        });
        ui.evChip.setOnClickListener(v -> {
            if (ready && !busy && !dialogOpen) {
                setTrayOpen(ui.activeTray() == CameraScreen.TRAY_EV
                        ? CameraScreen.TRAY_LENS : CameraScreen.TRAY_EV);
            }
        });
        ui.wbChip.setOnClickListener(v -> {
            if (ready && !busy && !dialogOpen) {
                setTrayOpen(ui.activeTray() == CameraScreen.TRAY_WB
                        ? CameraScreen.TRAY_LENS : CameraScreen.TRAY_WB);
            }
        });
        ui.evCloseBtn.setOnClickListener(v -> setTrayOpen(CameraScreen.TRAY_LENS));
        ui.wbCloseBtn.setOnClickListener(v -> setTrayOpen(CameraScreen.TRAY_LENS));
        ui.evResetBtn.setOnClickListener(v -> applyUserEv(0f));

        ui.evWheel.setOnValueChangeListener(this::applyUserEv);
        ui.wbWheel.setOnValueChangeListener(this::applyManualKelvin);
        setupWbPresets();

        ui.hdrToggle.setOnClickListener(v -> {
            if (!ready || busy || dialogOpen || !canUseUltraHdr(captureFormat() == ImageFormat.RAW_SENSOR)) {
                return;
            }
            ultraHdr = !ultraHdr;
            getPreferences(MODE_PRIVATE).edit().putBoolean("ultraHdr", ultraHdr).apply();
            updateQuickToggles();
            showTransientStatus(ultraHdr ? "亮彩已开 · 阳光和灯光更通透" : "亮彩已关 · 标准亮度出图", 3000);
        });
        ui.dngToggle.setOnClickListener(v -> {
            if (!ready || busy || dialogOpen || captureFormat() != ImageFormat.RAW_SENSOR) return;
            keepDng = !keepDng;
            getPreferences(MODE_PRIVATE).edit().putBoolean("keepDng", keepDng).apply();
            updateQuickToggles();
            showTransientStatus(keepDng ? "存底片已开 · 额外存专业修图文件" : "存底片已关 · 只存普通照片更省空间", 3000);
        });
        ui.gridButton.setOnClickListener(v -> {
            showGrid = !showGrid;
            getPreferences(MODE_PRIVATE).edit().putBoolean("showGrid", showGrid).apply();
            ui.setShowGrid(showGrid);
            updateHorizonSensor();
            showTransientStatus(showGrid ? "九宫格与水平仪已开 · 帮你把画面拍端正" : "九宫格与水平仪已关", 2800);
        });

        ui.setFilterOptions(filter, this::selectFilter);
        ui.mode.setOnClickListener(v -> chooseMode());
        ui.modeBadge.setOnClickListener(v -> chooseMode());
        setupFinderGestures();

        sensorManager = getSystemService(SensorManager.class);
        if (sensorManager != null) accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        orientation = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int angle) {
                if (angle == ORIENTATION_UNKNOWN) return;
                int distance = Math.abs((angle - deviceRotation + 540) % 360 - 180);
                if (distance > 55) deviceRotation = ((angle + 45) / 90 * 90) % 360;
            }
        };
    }

    private void setupWbPresets() {
        String[] labels = {"自动", "偏冷", "自然", "偏暖"};
        float[] kelvins = {0f, 3600f, 5500f, 7200f};
        for (int i = 0; i < labels.length; i++) {
            final float k = kelvins[i];
            TextView btn = ui.label(labels[i], 11, CameraScreen.INK);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
            btn.setBackground(ui.rounded(CameraScreen.ELEVATED, 11, CameraScreen.LINE));
            btn.setForeground(ui.ripple(11));
            btn.setFocusable(true);
            btn.setOnClickListener(v -> {
                applyManualKelvin(k);
                if (k >= 2000f) ui.wbWheel.setValue(k);
                else ui.wbWheel.setValue(5500f);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(ui.dp(5));
            ui.wbPresetRow.addView(btn, lp);
        }
    }

    private void setupFinderGestures() {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (!ready || busy || dialogOpen || camera == null) return false;
                float vw = ui.viewport.getWidth(), vh = ui.viewport.getHeight();
                if (vw <= 0 || vh <= 0) return false;
                float nx = Math.max(0.05f, Math.min(0.95f, e.getX() / vw));
                float ny = Math.max(0.05f, Math.min(0.95f, e.getY() / vh));
                ui.viewport.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                ui.overlay.showFocus(nx, ny, false);
                camera.focusAndMeter(nx, ny, false);
                showTransientStatus("已对焦 · 上下滑动可调画面亮度", 2400);
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (!ready || busy || dialogOpen || camera == null) return;
                float vw = ui.viewport.getWidth(), vh = ui.viewport.getHeight();
                if (vw <= 0 || vh <= 0) return;
                float nx = Math.max(0.05f, Math.min(0.95f, e.getX() / vw));
                float ny = Math.max(0.05f, Math.min(0.95f, e.getY() / vh));
                ui.viewport.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                ui.overlay.showFocus(nx, ny, true);
                camera.focusAndMeter(nx, ny, true);
                showTransientStatus("已锁定对焦与亮度 · 轻点画面即可解锁", 3200);
            }
        });

        ui.viewport.setOnTouchListener(new View.OnTouchListener() {
            float downY, startEv;
            boolean draggingEv;
            @Override public boolean onTouch(View v, MotionEvent event) {
                detector.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = event.getY();
                        startEv = userEvBias;
                        draggingEv = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!ready || busy || dialogOpen) return false;
                        float dy = event.getY() - downY;
                        if (!draggingEv && Math.abs(dy) > ui.dp(22) && event.getPointerCount() == 1) {
                            draggingEv = true;
                        }
                        if (draggingEv) {
                            float vh = Math.max(1f, ui.viewport.getHeight());
                            float raw = startEv - (dy / (vh * 0.45f)) * 2.0f;
                            float snapped = Math.round(Math.max(-2.0f, Math.min(2.0f, raw)) * 10f) / 10f;
                            if (Math.abs(snapped - userEvBias) >= 0.05f) {
                                applyUserEv(snapped);
                                ui.overlay.setDraggingEv(true, snapped);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (draggingEv) ui.overlay.setDraggingEv(false, userEvBias);
                        draggingEv = false;
                        return true;
                }
                return false;
            }
        });
    }

    private void applyUserEv(float ev) {
        userEvBias = Math.round(Math.max(-2.0f, Math.min(2.0f, ev)) * 10f) / 10f;
        ui.evWheel.setValue(userEvBias);
        String readout;
        if (Math.abs(userEvBias) < 0.05f) readout = "标准";
        else if (userEvBias > 0) readout = String.format(Locale.ROOT, "变亮 %+.1f", userEvBias);
        else readout = String.format(Locale.ROOT, "变暗 %+.1f", userEvBias);
        ui.evReadout.setText(readout);
        ui.updateProChips(userEvBias, manualKelvin);
        ui.updateTelemetry(latestTelemetry, highlightProtecting, userEvBias);
        if (camera != null) camera.setUserExposureEv(userEvBias);
    }

    private void applyManualKelvin(float kelvin) {
        manualKelvin = kelvin >= 2000f ? Math.round(kelvin / 100f) * 100f : 0f;
        if (manualKelvin >= 2000f) {
            String tone = manualKelvin < 4800f ? "偏冷" : (manualKelvin <= 5900f ? "自然" : "偏暖");
            ui.wbReadout.setText(tone + " · " + Math.round(manualKelvin) + "K");
        } else {
            ui.wbReadout.setText("自动色调");
        }
        ui.updateProChips(userEvBias, manualKelvin);
        if (renderer != null) renderer.setWhiteBalancePreview(manualKelvin, manualTint);
        proofUnavailable = false;
        scheduleProof();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (orientation.canDetectOrientation()) orientation.enable();
        updateHorizonSensor();
        loadLastPhoto();
        begin();
    }

    @Override protected void onPause() {
        foreground = false;
        orientation.disable();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        stopPreview();
        super.onPause();
    }

    private void updateHorizonSensor() {
        if (sensorManager == null || accelerometer == null) return;
        sensorManager.unregisterListener(this);
        if (foreground && showGrid) {
            hasGravity = false;
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        float alpha = hasGravity ? 0.18f : 1f;
        gravity[0] += alpha * (event.values[0] - gravity[0]);
        gravity[1] += alpha * (event.values[1] - gravity[1]);
        gravity[2] += alpha * (event.values[2] - gravity[2]);
        hasGravity = true;
        float roll = (float) Math.toDegrees(Math.atan2(gravity[0], gravity[1]));
        float pitch = (float) Math.toDegrees(Math.atan2(gravity[2], Math.hypot(gravity[0], gravity[1])));
        ui.overlay.setHorizon(roll, pitch);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void begin() { begin(null); }

    private void begin(String switchingTo) {
        stopPreview();
        if (!foreground) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            boolean tried = getPreferences(MODE_PRIVATE).getBoolean("asked", false);
            boolean useSettings = tried && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
            ui.status.setText("等待相机权限");
            ui.showMessage("允许使用相机后即可取景", useSettings ? "打开系统设置" : "允许使用相机", v -> {
                if (useSettings) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } else {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("asked", true).apply();
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
                }
            });
            return;
        }

        int session = run;
        highlightProtecting = false;
        latestTelemetry = null;
        ui.overlay.clearReticle();
        ui.status.setText(switchingTo == null ? "正在打开相机" : "切换至" + switchingTo);
        ui.showMessage(switchingTo == null ? "正在打开后置相机…" : "", "", null);

        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(2);
        surface.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        camera = new CameraInput(this, new CameraInput.Listener() {
            @Override public void onLenses(List<CameraInput.Lens> lenses, String selected) {
                if (session != run) return;
                selectedLens = selected;
                for (CameraInput.Lens l : lenses) {
                    if (l.id.equals(selected)) currentLensMm = l.equivalentMm;
                }
                ui.setLenses(lenses, selected, lens -> {
                    if (busy || !foreground || (lens.id.equals(selectedLens) && ready)) return;
                    selectedLens = lens.id;
                    currentLensMm = lens.equivalentMm;
                    applyUserEv(0f);
                    begin(lens.label);
                });
            }

            @Override public void onConfigured(int width, int height, int angle,
                                                int format, int photoW, int photoH) {
                if (session != run) return;
                camera.setUserExposureEv(userEvBias);
                renderer.setGeometry(width, height, angle);
                boolean swap = angle % 180 != 0;
                ui.setAspect(swap ? height : width, swap ? width : height);
                stillFormat = format;
                stillWidth = photoW;
                stillHeight = photoH;
                previewWidth = width;
                previewHeight = height;
                displayMode();
                refreshControls();
            }

            @Override public void onTelemetry(CameraInput.Telemetry t) {
                if (session != run) return;
                latestTelemetry = t;
                ui.updateTelemetry(t, highlightProtecting, userEvBias);
            }

            @Override public void onCameraError(String message) {
                if (session == run) showError(message);
            }
        });

        renderer = new FrameRenderer(surface, new FrameRenderer.Listener() {
            @Override public void onInputReady(SurfaceTexture input) {
                if (session != run) return;
                ready = busy = false;
                renderer.setFilter(filter);
                renderer.setWhiteBalancePreview(manualKelvin, manualTint);
                renderer.setHistogramEnabled(showHistogram);
                refreshControls();
                camera.open(input, selectedLens);
            }

            @Override public void onFrameReady() {
                if (session != run) return;
                ready = true;
                ui.showMessage("", "", null);
                ui.revealFinder();
                setStatus();
                refreshControls();
                scheduleProof();
                if (!guideShown) showGuide();
            }

            @Override public void onPhoto(Bitmap bitmap) {
                if (session != run) {
                    bitmap.recycle();
                    return;
                }
                savePreviewFrame(bitmap, shutterTime, session, filter);
            }

            @Override public void onHighlightMeter(boolean protect) {
                if (session == run && ready && !busy && captureFormat() == ImageFormat.RAW_SENSOR) {
                    highlightProtecting = protect;
                    camera.protectHighlights(protect);
                    ui.updateTelemetry(latestTelemetry, highlightProtecting, userEvBias);
                }
            }

            @Override public void onHistogram(float[] bins, float clipFraction) {
                if (session == run && showHistogram) {
                    ui.overlay.setHistogram(bins, clipFraction);
                }
            }

            @Override public void onRenderError(String message) {
                if (session == run) showError(message);
            }
        });

        surface.setRenderer(renderer);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        ui.surfaceHost.addView(surface);
        surface.onResume();
        main.postDelayed(() -> {
            if (run == session && foreground && !ready) showError("相机没有送来画面，请重新连接。");
        }, 12000);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == CAMERA_PERMISSION && foreground) begin();
    }

    private void stopPreview() {
        ++run;
        ui.coverFinder();
        main.removeCallbacks(nextProof);
        main.removeCallbacks(clearStatus);
        showingTransientStatus = false;
        hideProof();
        proofUnavailable = proofInFlight = waitingForProof = false;
        setTrayOpen(CameraScreen.TRAY_LENS);
        if (camera != null) camera.close();
        if (surface != null) {
            renderer.release();
            surface.onPause();
            ui.surfaceHost.removeView(surface);
        }
        camera = null;
        renderer = null;
        surface = null;
        ready = busy = false;
        stillFormat = ImageFormat.UNKNOWN;
        ui.dimensions.setText("");
        ui.telemetry.setText("");
        ui.evBadge.setVisibility(View.GONE);
        refreshControls();
    }

    private void showError(String message) {
        stopPreview();
        ui.status.setText("取景已暂停");
        ui.showMessage(message, "重新打开相机", v -> begin());
    }

    private void refreshControls() {
        ui.controls(ready, busy);
        updateQuickToggles();
    }

    private void updateQuickToggles() {
        boolean raw = captureFormat() == ImageFormat.RAW_SENSOR;
        boolean controlsEnabled = ready && !busy;
        ui.updateQuickToggle(ui.hdrToggle, canUseUltraHdr(raw), ultraHdr, controlsEnabled);
        ui.updateQuickToggle(ui.dngToggle, raw, keepDng, controlsEnabled);
    }

    private static boolean canUseUltraHdr(boolean raw) {
        return raw && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    private void showTransientStatus(String text, long durationMs) {
        main.removeCallbacks(clearStatus);
        showingTransientStatus = true;
        ui.status.setText(text);
        main.postDelayed(clearStatus, durationMs);
    }

    private void setStatus() {
        if (showingTransientStatus) return;
        if (ui.activeTray() == CameraScreen.TRAY_FILTER) {
            ui.status.setText("");
        } else if (ui.rawPreview.getVisibility() == View.VISIBLE) {
            ui.status.setText("原彩预览中");
        } else if (!showProInfo) {
            ui.status.setText("轻点对焦 · 长按锁定 · 上下滑调亮度");
        } else {
            ui.status.setText("");
        }
    }

    private void setTrayOpen(int tray) {
        if (ui.activeTray() == tray) return;
        if (tray != CameraScreen.TRAY_LENS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && unregisterTrayBack == null) {
            registerTrayBack();
        } else if (tray == CameraScreen.TRAY_LENS && unregisterTrayBack != null) {
            unregisterTrayBack.run();
            unregisterTrayBack = null;
        }
        ui.showTray(tray);
        ui.updateProChips(userEvBias, manualKelvin);
        if (ready && !busy) setStatus();
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private void registerTrayBack() {
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        OnBackInvokedCallback callback = () -> setTrayOpen(CameraScreen.TRAY_LENS);
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        unregisterTrayBack = () -> dispatcher.unregisterOnBackInvokedCallback(callback);
    }

    private void selectFilter(PhotoFilter selected) {
        if (!ready || busy || selected == filter) return;
        filter = selected;
        getPreferences(MODE_PRIVATE).edit().putString("photoFilter", filter.id).apply();
        ui.updateFilter(filter);
        renderer.setFilter(filter);
        proofUnavailable = false;
        hideProof();
        setStatus();
        scheduleProof();
    }

    @Override public void onBackPressed() {
        if (ui.activeTray() != CameraScreen.TRAY_LENS) setTrayOpen(CameraScreen.TRAY_LENS);
        else super.onBackPressed();
    }

    private int captureFormat() {
        return sameFrame ? ImageFormat.UNKNOWN : stillFormat;
    }

    private void displayMode() {
        int format = captureFormat();
        renderer.setHighlightMetering(format == ImageFormat.RAW_SENSOR);
        if (format != ImageFormat.RAW_SENSOR) {
            highlightProtecting = false;
            camera.protectHighlights(false);
            ui.updateTelemetry(latestTelemetry, false, userEvBias);
        }
        String label = format == ImageFormat.RAW_SENSOR ? "原彩直出" : format == ImageFormat.JPEG ? "高清直出" : "极速快拍";
        String badge = format == ImageFormat.RAW_SENSOR ? "原彩" : format == ImageFormat.JPEG ? "高清" : "快拍";
        boolean still = format != ImageFormat.UNKNOWN;
        ui.setMode(badge, label, badge, still ? stillWidth : previewWidth, still ? stillHeight : previewHeight);
    }

    private void showGuide() {
        if (!foreground || isFinishing() || busy || dialogOpen) return;
        setTrayOpen(CameraScreen.TRAY_LENS);
        dialogOpen = true;
        main.removeCallbacks(nextProof);
        Dialog dialog = createGuide();
        dialog.setOnDismissListener(d -> {
            dialogOpen = false;
            scheduleProof();
        });
        dialog.show();
        CameraPanels.fitSheet(dialog, ui);
        if (!guideShown) {
            guideShown = true;
            getPreferences(MODE_PRIVATE).edit().putBoolean("guideShown", true).apply();
        }
    }

    private Dialog createGuide() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView intro = ui.label("不用懂专业参数，默认设置直接按快门就能拍出自然真实的好照片。", 13, CameraScreen.MUTED);
        intro.setLineSpacing(ui.dp(2), 1f);
        intro.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(6));
        body.addView(intro);

        body.addView(CameraPanels.section(ui, "3 步快速上手"));
        LinearLayout quickCard = CameraPanels.card(ui);
        quickCard.addView(CameraPanels.guideItem(ui, "1. 切镜头：超广角 / 主摄 / 长焦",
                "拍大风景选「超广角」，日常随手拍选「主摄」，想把远处拉近或拍特写选「长焦」。"));
        CameraPanels.divider(ui, quickCard);
        quickCard.addView(CameraPanels.guideItem(ui, "2. 轻点对焦 · 上下滑调亮度 · 长按锁定",
                "点画面想拍清楚的地方；手指在画面上下滑可调亮暗；长按可锁定对焦与亮度。"));
        CameraPanels.divider(ui, quickCard);
        quickCard.addView(CameraPanels.guideItem(ui, "3. 右下角选风格",
                "提供原彩、光影、柔光、层次、黑白 5 种风格，取景时实时看到效果。"));
        body.addView(quickCard);

        body.addView(CameraPanels.section(ui, "顶部和底部按钮是什么意思？"));
        LinearLayout buttonsCard = CameraPanels.card(ui);
        buttonsCard.addView(CameraPanels.guideItem(ui, "亮彩（原 ProXDR）",
                "让阳光、灯光和天空在手机屏幕上更有通透立体感，日常推荐开启。"));
        CameraPanels.divider(ui, buttonsCard);
        buttonsCard.addView(CameraPanels.guideItem(ui, "存底片（原 DNG）",
                "额外保存约 20MB 的专业修图原文件（供电脑或专业修图软件后期）。日常发朋友圈不需要，建议关闭更省空间。"));
        CameraPanels.divider(ui, buttonsCard);
        buttonsCard.addView(CameraPanels.guideItem(ui, "亮度 / 色调",
                "觉得太暗太亮就点「亮度」；室内发黄或偏蓝就点「色调」调冷暖。"));
        CameraPanels.divider(ui, buttonsCard);
        buttonsCard.addView(CameraPanels.guideItem(ui, "九宫格与水平仪",
                "显示构图参考线，画面中央水平线变黄说明手机拿端平了。"));
        body.addView(buttonsCard);

        body.addView(CameraPanels.section(ui, "常见问题与名词解释"));
        LinearLayout faqCard = CameraPanels.card(ui);
        faqCard.addView(CameraPanels.guideItem(ui, "原彩直出是什么？",
                "不做过度磨皮和假锐化，尽量还原眼睛亲眼看到的真实色彩和质感。"));
        CameraPanels.divider(ui, faqCard);
        faqCard.addView(CameraPanels.guideItem(ui, "自动防过曝是什么？",
                "遇到强光或天空时，相机会自动压低一点亮度，避免亮部惨白没有细节。"));
        CameraPanels.divider(ui, faqCard);
        faqCard.addView(CameraPanels.guideItem(ui, "想看光圈、快门、ISO 等专业参数？",
                "点右上角设置图标，在「拍照辅助」中开启「专业拍摄参数」。"));
        body.addView(faqCard);

        return CameraPanels.sheet(ui, "本色相机 · 新手指南", body, "知道了，开始拍照");
    }

    private void chooseMode() {
        if (!ready || busy || dialogOpen) return;
        setTrayOpen(CameraScreen.TRAY_LENS);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        boolean rawAvailable = stillFormat == ImageFormat.RAW_SENSOR;
        body.addView(CameraPanels.section(ui, "拍摄"));
        LinearLayout modeCard = CameraPanels.card(ui);
        RadioGroup modes = new RadioGroup(this);
        RadioButton sensor = new RadioButton(this);
        sensor.setId(View.generateViewId());
        sensor.setEnabled(stillFormat != ImageFormat.UNKNOWN);
        if (rawAvailable) {
            CameraPanels.option(ui, sensor, "原彩直出", "从 RAW 显影，保留更多色彩和细节");
        } else if (stillFormat == ImageFormat.JPEG) {
            CameraPanels.option(ui, sensor, "高清直出", "保存相机的高分辨率照片");
        } else {
            CameraPanels.option(ui, sensor, "当前镜头仅支持快拍", null);
        }
        modes.addView(sensor);
        RadioButton preview = new RadioButton(this);
        preview.setId(View.generateViewId());
        CameraPanels.option(ui, preview, "极速快拍", "直接保存取景画面");
        modes.addView(preview);
        modeCard.addView(modes);
        body.addView(modeCard);
        modes.check(captureFormat() == ImageFormat.UNKNOWN ? preview.getId() : sensor.getId());

        CheckBox mfBox = new CheckBox(this);
        CameraPanels.option(ui, mfBox, "多帧降噪", "合成多张照片，减少暗部噪点");
        mfBox.setChecked(multiFrame);
        if (rawAvailable) {
            CameraPanels.divider(ui, modeCard);
            modeCard.addView(mfBox);
        }

        body.addView(CameraPanels.section(ui, "拍照辅助"));
        LinearLayout finderCard = CameraPanels.card(ui);
        CheckBox proof = new CheckBox(this);
        CameraPanels.option(ui, proof, "原彩预览", "预览成片色彩，取景刷新会变慢");
        proof.setChecked(liveRaw);
        if (rawAvailable) {
            finderCard.addView(proof);
            CameraPanels.divider(ui, finderCard);
        }

        CheckBox proInfoBox = new CheckBox(this);
        CameraPanels.option(ui, proInfoBox, "专业拍摄参数", "显示光圈、快门、ISO 和照片尺寸");
        proInfoBox.setChecked(showProInfo);
        finderCard.addView(proInfoBox);

        CheckBox histBox = new CheckBox(this);
        CameraPanels.option(ui, histBox, "直方图", "显示取景画面的亮度分布");
        histBox.setChecked(showHistogram);
        CameraPanels.divider(ui, finderCard);
        finderCard.addView(histBox);
        body.addView(finderCard);

        Runnable options = () -> {
            boolean canDevelop = sensor.isChecked() && rawAvailable;
            mfBox.setEnabled(canDevelop);
            proof.setEnabled(canDevelop);
        };
        options.run();
        modes.setOnCheckedChangeListener((group, id) -> options.run());

        TextView version = ui.label(installedVersionLabel(), 12, CameraScreen.MUTED);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(-1, -2);
        versionLp.topMargin = ui.dp(14);
        body.addView(version, versionLp);

        dialogOpen = true;
        main.removeCallbacks(nextProof);
        Dialog dialog = CameraPanels.sheet(ui, "拍摄设置", body);
        CameraPanels.sheetActions(ui, body, dialog, "取消", "完成", v -> {
            if (ready) {
                sameFrame = preview.isChecked() && stillFormat != ImageFormat.UNKNOWN;
                multiFrame = mfBox.isChecked();
                liveRaw = proof.isChecked();
                showProInfo = proInfoBox.isChecked();
                showHistogram = histBox.isChecked();
                getPreferences(MODE_PRIVATE).edit()
                        .putBoolean("sameFrame", sameFrame)
                        .putBoolean("multiFrame", multiFrame)
                        .putBoolean("liveRaw", liveRaw)
                        .putBoolean("showProInfo", showProInfo)
                        .putBoolean("showHistogram", showHistogram)
                        .apply();
                ui.overlay.setShowHistogram(showHistogram);
                ui.setProInfoVisible(showProInfo);
                if (renderer != null) renderer.setHistogramEnabled(showHistogram);
                proofUnavailable = false;
                hideProof();
                displayMode();
                setStatus();
                refreshControls();
            }
            dialog.dismiss();
        });
        dialog.setOnDismissListener(d -> {
            dialogOpen = false;
            scheduleProof();
        });
        dialog.show();
        CameraPanels.fitSheet(dialog, ui);
    }

    private String installedVersionLabel() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (name != null && !name.isEmpty()) return getString(R.string.camera_version, name);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w("TrueHue", "Cannot read the installed version", e);
        }
        return getString(R.string.camera_version_unknown);
    }

    private void hideProof() {
        ui.rawPreview.setVisibility(View.GONE);
        ui.rawPreview.setImageDrawable(null);
    }

    private boolean wantsProof() {
        return ready && foreground && !dialogOpen && !busy && !proofUnavailable
                && liveRaw && captureFormat() == ImageFormat.RAW_SENSOR;
    }

    private void scheduleProof() {
        main.removeCallbacks(nextProof);
        if (wantsProof() && !proofInFlight) main.postDelayed(nextProof, 240);
    }

    private void requestRawPreview() {
        if (!wantsProof() || proofInFlight) return;
        proofInFlight = true;
        int session = run;
        PhotoFilter selected = filter;
        RawRenderer.Options opt = buildRenderOptions(selected, false);
        camera.capture(0, new CameraInput.PhotoCallback() {
            @Override public void onImage(Image image, CameraCharacteristics sensor, TotalCaptureResult shot, int angle) {
                Bitmap result = null;
                try {
                    result = RawRenderer.renderPreview(image, sensor, shot, angle, 720, opt);
                } catch (RuntimeException | OutOfMemoryError ex) {
                    Log.w("TrueHue", "Cannot develop a RAW preview", ex);
                }
                Bitmap shown = result;
                main.post(() -> finishProof(session, selected, shown));
            }
            @Override public void onFailure(String message) { finishProof(session, selected, null); }
        });
    }

    private void finishProof(int session, PhotoFilter selected, Bitmap result) {
        if (session != run) {
            if (result != null) result.recycle();
            return;
        }
        proofInFlight = false;
        if (selected != filter || !liveRaw || captureFormat() != ImageFormat.RAW_SENSOR) {
            if (result != null) result.recycle();
        } else if (result == null) {
            proofUnavailable = true;
            hideProof();
            if (!busy && !showingTransientStatus) ui.status.setText("实景预览已暂停，可正常拍照");
        } else if (wantsProof()) {
            boolean first = ui.rawPreview.getVisibility() != View.VISIBLE;
            ui.rawPreview.setImageBitmap(result);
            ui.rawPreview.setVisibility(View.VISIBLE);
            if (first) setStatus();
        } else {
            result.recycle();
        }
        if (waitingForProof) {
            waitingForProof = false;
            submitShutter();
        } else {
            scheduleProof();
        }
    }

    private RawRenderer.Options buildRenderOptions(PhotoFilter selected, boolean allowHdr) {
        RawRenderer.Options opt = new RawRenderer.Options();
        opt.ultraHdr = allowHdr && ultraHdr && canUseUltraHdr(captureFormat() == ImageFormat.RAW_SENSOR);
        opt.filter = selected;
        opt.manualKelvin = manualKelvin;
        opt.manualTint = manualTint;
        opt.userEvBias = userEvBias;
        return opt;
    }

    private void pressShutter() {
        if (!ready || busy || dialogOpen) return;
        ui.shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        ui.shutterFeedback();
        busy = true;
        main.removeCallbacks(nextProof);
        main.removeCallbacks(clearStatus);
        showingTransientStatus = false;
        refreshControls();
        shutterTime = System.currentTimeMillis();
        ui.status.setText("正在拍摄…");
        if (proofInFlight) waitingForProof = true;
        else submitShutter();
    }

    private void submitShutter() {
        int session = run;
        int format = captureFormat();
        if (format == ImageFormat.UNKNOWN) {
            renderer.shoot(deviceRotation, filter);
            return;
        }
        long stamp = shutterTime;
        boolean saveNegative = format == ImageFormat.RAW_SENSOR && keepDng;
        PhotoFilter selected = filter;
        boolean compare = compareBaseline;
        int lensMm = currentLensMm;
        RawRenderer.Options opt = buildRenderOptions(selected, true);

        if (format == ImageFormat.RAW_SENSOR && multiFrame && !compare
                && camera != null && camera.supportsBurst()) {
            int iso = camera.latestIso();
            long expNs = camera.latestExposureNs();
            boolean guard = camera.isHighlightProtected();
            boolean skipBurstForBrightDaylight = iso > 0 && iso <= 125
                    && expNs > 0L && expNs <= 10_000_000L && !guard && lensMm < 45;
            if (!skipBurstForBrightDaylight) {
                int burstCount = iso >= 800 ? 5 : (iso >= 350 || expNs > 20_000_000L) ? 4 : 3;
                final boolean[] dngSaved = {false};
                final boolean[] dngFailed = {false};
                camera.captureBurst(deviceRotation, burstCount, saveNegative, new StillStream.BurstCallback() {
                    @Override public void onProgress(int framesReceived, int totalFrames) {
                        if (session == run) ui.status.setText("连拍合成中 " + framesReceived + "/" + totalFrames);
                    }

                    @Override public void onFirstFrameForDng(Image image, CameraCharacteristics info,
                                                             TotalCaptureResult result, int degrees) {
                        try {
                            PhotoStore.saveDng(getApplicationContext(), image, info, result, stamp, degrees);
                            dngSaved[0] = true;
                        } catch (IOException | RuntimeException e) {
                            Log.e("TrueHue", "Optional DNG could not be saved", e);
                            dngFailed[0] = true;
                        }
                    }

                    @Override public void onBurstReady(BurstRawMerger.Result merged, int[] channel,
                                                       int width, int height, CameraCharacteristics info,
                                                       TotalCaptureResult refResult, int degrees,
                                                       float fallbackGuardEv) {
                        opt.fallbackMeteredBiasEv = fallbackGuardEv;
                        main.post(() -> {
                            if (session == run) ui.status.setText("正在优化画质…");
                        });
                        try {
                            Bitmap developed = RawRenderer.renderMerged(merged, channel, width, height,
                                    info, refResult, degrees, opt);
                            Uri saved;
                            try {
                                saved = PhotoStore.save(getApplicationContext(), developed, stamp, refResult,
                                        RawRenderer.meteredBias(info, refResult), true, false, selected, lensMm);
                            } finally {
                                developed.recycle();
                            }
                            String msg = "照片已保存";
                            if (dngSaved[0]) msg += " · 含底片";
                            else if (dngFailed[0]) msg += " · 底片保存失败";
                            if (opt.ultraHdr) {
                                msg += " · 亮彩";
                            }
                            photoSaved(saved, msg, stamp, session);
                        } catch (IOException | RuntimeException | OutOfMemoryError e) {
                            Log.e("TrueHue", "Cannot save burst capture", e);
                            main.post(() -> actionFailed(session, "照片保存失败，请检查可用空间后重试"));
                        }
                    }

                    @Override public void onFailure(String message) {
                        actionFailed(session, message);
                    }
                });
                return;
            }
        }

        camera.capture(deviceRotation, new CameraInput.PhotoCallback() {
            @Override public void onImage(Image image, CameraCharacteristics sensor, TotalCaptureResult shot, int angle) {
                main.post(() -> {
                    if (session == run) ui.status.setText(format == ImageFormat.RAW_SENSOR ? "正在生成原彩照片…" : "正在保存照片…");
                });
                try {
                    if (image.getFormat() == ImageFormat.JPEG) {
                        Uri saved = PhotoStore.saveDeviceJpeg(getApplicationContext(), image, shot, stamp,
                                angle, RawRenderer.meteredBias(sensor, shot), selected);
                        photoSaved(saved, "高清照片已保存", stamp, session);
                    } else {
                        developPhoto(image, sensor, shot, angle, opt, compare, saveNegative, lensMm, stamp, session);
                    }
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    Log.e("TrueHue", "Cannot save still capture", e);
                    main.post(() -> actionFailed(session, "照片保存失败，请检查可用空间后重试"));
                }
            }
            @Override public void onFailure(String message) { actionFailed(session, message); }
        });
    }

    private void developPhoto(Image image, CameraCharacteristics sensor, TotalCaptureResult shot,
                              int angle, RawRenderer.Options opt, boolean compare,
                              boolean saveNegative, int lensMm, long stamp, int session) throws IOException {
        Bitmap developed;
        try {
            developed = RawRenderer.render(image, sensor, shot, angle, opt);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.e("TrueHue", "Saving DNG after JPEG development failed", e);
            PhotoStore.saveDng(getApplicationContext(), image, sensor, shot, stamp, angle);
            main.post(() -> actionFailed(session, "原彩合成失败，已保存原始底片"));
            return;
        }
        Uri saved;
        try {
            saved = PhotoStore.save(getApplicationContext(), developed, stamp, shot,
                    RawRenderer.meteredBias(sensor, shot), opt.natural, false, opt.filter, lensMm);
        } finally {
            developed.recycle();
        }
        String message = "原彩照片已保存";
        if (saveNegative) {
            try {
                PhotoStore.saveDng(getApplicationContext(), image, sensor, shot, stamp, angle);
                message = "照片与底片已保存";
            } catch (IOException | RuntimeException e) {
                Log.e("TrueHue", "Optional DNG could not be saved", e);
                message = "照片已保存，底片写入失败";
            }
        }
        if (compare) {
            try {
                Bitmap baseline = RawRenderer.render(image, sensor, shot, angle, false, PhotoFilter.ORIGINAL);
                try {
                    PhotoStore.save(getApplicationContext(), baseline, stamp, shot,
                            RawRenderer.meteredBias(sensor, shot), false, true, PhotoFilter.ORIGINAL, lensMm);
                } finally {
                    baseline.recycle();
                }
                message += " · 已存对照图";
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w("TrueHue", "Cannot save baseline companion", e);
                message += " · 对照图保存失败";
            }
        }
        photoSaved(saved, message, stamp, session);
    }

    private void savePreviewFrame(Bitmap image, long timestamp, int session, PhotoFilter selected) {
        FILES.execute(() -> {
            try {
                Uri saved = PhotoStore.save(getApplicationContext(), image, timestamp, null,
                        Float.NaN, false, false, selected, currentLensMm);
                photoSaved(saved, "快拍照片已保存", timestamp, session);
            } catch (IOException | RuntimeException e) {
                Log.e("TrueHue", "Cannot save preview capture", e);
                main.post(() -> actionFailed(session, "照片保存失败，请检查可用空间后重试"));
            } finally {
                image.recycle();
            }
        });
    }

    private void photoSaved(Uri saved, String message, long stamp, int session) {
        main.post(() -> {
            if (stamp >= getPreferences(MODE_PRIVATE).getLong("lastTime", 0)) {
                getPreferences(MODE_PRIVATE).edit().putString("last", saved.toString()).putLong("lastTime", stamp).apply();
                if (!isDestroyed()) {
                    lastPhoto = saved;
                    updateThumbnail(saved, foreground && session == run);
                }
            }
            if (session != run) return;
            busy = false;
            main.removeCallbacks(clearStatus);
            showingTransientStatus = false;
            ui.status.setText(message);
            refreshControls();
            scheduleProof();
            main.postDelayed(() -> {
                if (run == session && ready && !busy && shutterTime == stamp) setStatus();
            }, 2600);
        });
    }

    private void actionFailed(int session, String message) {
        if (session != run) return;
        busy = false;
        ui.status.setText(message);
        refreshControls();
        scheduleProof();
    }

    private void loadLastPhoto() {
        String saved = getPreferences(MODE_PRIVATE).getString("last", null);
        if (saved == null) return;
        lastPhoto = Uri.parse(saved);
        updateThumbnail(lastPhoto, false);
    }

    private void updateThumbnail(Uri uri, boolean animate) {
        FILES.execute(() -> {
            try {
                Bitmap thumb = PhotoStore.read(getApplicationContext(), uri, 200);
                main.post(() -> {
                    if (!isDestroyed() && uri.equals(lastPhoto)) ui.showThumbnail(thumb, animate && foreground);
                });
            } catch (IOException | RuntimeException ex) {
                main.post(() -> {
                    if (!isDestroyed() && uri.equals(lastPhoto)) ui.showThumbnail(null, false);
                });
            }
        });
    }

    private void review() {
        final Uri uri = lastPhoto;
        if (uri == null || dialogOpen) return;
        dialogOpen = true;
        main.removeCallbacks(nextProof);
        int edge = Math.max(ui.getWidth(), ui.getHeight());
        FILES.execute(() -> {
            try {
                Bitmap image = PhotoStore.read(getApplicationContext(), uri, Math.max(1080, edge));
                PhotoStore.PhotoMeta meta = PhotoStore.readMeta(getApplicationContext(), uri);
                main.post(() -> {
                    if (foreground && !isFinishing() && !isDestroyed()) showPhoto(image, uri, meta);
                    else {
                        image.recycle();
                        dialogOpen = false;
                    }
                });
            } catch (IOException | RuntimeException ex) {
                main.post(() -> {
                    dialogOpen = false;
                    scheduleProof();
                    if (foreground) Toast.makeText(this, "这张照片已无法打开", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showPhoto(Bitmap image, Uri uri, PhotoStore.PhotoMeta meta) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(CameraScreen.BG);
        Dialog dialog = CameraPanels.photo(ui, content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setColorMode(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT);
        }

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(ui.dp(8), 0, ui.dp(12), 0);
        content.addView(header, new LinearLayout.LayoutParams(-1, ui.dp(56)));

        CameraScreen.Mark back = new CameraScreen.Mark(this, CameraScreen.Mark.BACK);
        back.setContentDescription("回到取景");
        back.setForeground(ui.ripple(24));
        back.setFocusable(true);
        back.setOnClickListener(v -> dialog.dismiss());
        header.addView(back, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        header.addView(CameraPanels.heading(ui, "上张照片"), new LinearLayout.LayoutParams(0, -2, 1));

        if (meta != null) {
            TextView modePill = ui.label(meta.modeLabel, 11, CameraScreen.INK);
            modePill.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
            modePill.setBackground(ui.rounded(CameraScreen.ELEVATED, 10, CameraScreen.LINE));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
            mp.setMarginEnd(ui.dp(6));
            header.addView(modePill, mp);
            if (meta.ultraHdr) {
                TextView hdrPill = ui.label("亮彩", 11, CameraScreen.ACCENT);
                hdrPill.setContentDescription("高光亮彩照片");
                hdrPill.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
                hdrPill.setBackground(ui.rounded(0x26e8a838, 10, CameraScreen.ACCENT));
                LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
                hp.setMarginEnd(ui.dp(6));
                header.addView(hdrPill, hp);
            }
        }

        CameraScreen.Mark share = new CameraScreen.Mark(this, CameraScreen.Mark.SHARE);
        share.setContentDescription("分享照片");
        share.setForeground(ui.ripple(24));
        share.setFocusable(true);
        share.setOnClickListener(v -> sharePhoto(uri));
        header.addView(share, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));

        CameraPanels.ZoomableImageView photo = new CameraPanels.ZoomableImageView(this);
        photo.setImageBitmap(image);
        photo.setContentDescription("最近拍摄的照片，支持双指缩放与双击放大");
        content.addView(photo, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(18));
        content.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        if (meta != null) {
            String barText = showProInfo && !meta.summary.isEmpty()
                    ? meta.summary + " · " + meta.look
                    : "风格：" + meta.look + " · 双指缩放可看细节";
            TextView exifBar = ui.label(barText, 12, CameraScreen.MUTED);
            exifBar.setFontFeatureSettings("tnum");
            exifBar.setGravity(Gravity.CENTER);
            exifBar.setPadding(0, 0, 0, ui.dp(10));
            footer.addView(exifBar);
        }

        TextView open = CameraPanels.button(ui, "在相册中打开", false, v -> {
            dialog.dismiss();
            openInGallery(uri);
        });
        footer.addView(open, new LinearLayout.LayoutParams(-2, -2));

        dialog.setOnDismissListener(d -> {
            dialogOpen = false;
            photo.setImageDrawable(null);
            image.recycle();
            scheduleProof();
        });
        dialog.show();
    }

    private void sharePhoto(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType("image/jpeg")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "分享照片"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有找到可用于分享的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void openInGallery(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有找到能打开照片的相册", Toast.LENGTH_SHORT).show();
        }
    }
}
