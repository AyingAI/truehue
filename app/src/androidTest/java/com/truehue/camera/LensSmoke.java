package com.truehue.camera;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.TextView;

import java.io.InputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Connected-device check. Takes one photo per native rear lens and one companion DNG. */
public final class LensSmoke extends Instrumentation {
    private Bundle args;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        args = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override public void onStart() {
        Context target = getTargetContext();
        SharedPreferences prefs = target.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        String[] choices = args.getString("lenses", "主摄,超广角,长焦").split(",");
        boolean includeDng = Boolean.parseBoolean(args.getString("dng", "true"));
        boolean paired = Boolean.parseBoolean(args.getString("compare", "false"));
        boolean exposureOnly = Boolean.parseBoolean(args.getString("exposureOnly", "false"));
        Bundle out = new Bundle();
        int code = Activity.RESULT_OK;
        MainActivity activity = null;
        try {
            prefs.edit().putBoolean("sameFrame", false).putBoolean("liveRaw", !exposureOnly)
                    .putBoolean("multiFrame", true).putBoolean("ultraHdr", android.os.Build.VERSION.SDK_INT >= 34)
                    .putBoolean("naturalLight", true).putBoolean("compareBaseline", paired)
                    .putBoolean("keepDng", includeDng).putString("photoFilter", PhotoFilter.ORIGINAL.id).commit();
            Intent open = new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            sendLine("正在打开本色相机");
            target.startActivity(open);
            activity = (MainActivity) waitForMonitorWithTimeout(monitor, 20000);
            if (activity == null) throw new AssertionError("本色相机未进入前台");
            MainActivity launched = activity;
            CameraScreen ui = onUi(() -> (CameraScreen) ((ViewGroup) launched.findViewById(android.R.id.content)).getChildAt(0));
            if (exposureOnly) {
                await("主摄测光就绪", () -> onUi(() -> ui.shutter.isEnabled()), ui);
                verifyExposureControl(launched, ui);
                out.putString("result", "Exposure metering passed.");
            } else if (Boolean.parseBoolean(args.getString("settingsOnly", "false"))) {
                verifyPanels(target, prefs, ui, includeDng);
                verifyFilters(target, prefs, ui);
                out.putString("result", "Settings, photo review, filter selection, RAW and viewfinder filter saves passed.");
            } else {
                for (int i = 0; i < choices.length; i++) {
                    checkLens(target, prefs, ui, choices[i], includeDng && (paired || i == 0), paired);
                    if (includeDng && !paired && i == 0 && choices.length > 1) toggleDng(ui);
                }
                out.putString("result", "All requested lenses passed.");
            }
        } catch (Throwable e) {
            code = Activity.RESULT_CANCELED;
            out.putString("error", e.toString());
        } finally {
            if (activity != null) runOnMainSync(activity::finish);
            SharedPreferences.Editor edit = prefs.edit();
            for (String k : new String[]{"sameFrame", "liveRaw", "multiFrame", "ultraHdr", "keepDng", "naturalLight", "compareBaseline"}) {
                if (before.containsKey(k)) edit.putBoolean(k, (Boolean) before.get(k));
                else edit.remove(k);
            }
            if (before.containsKey("photoFilter")) edit.putString("photoFilter", (String) before.get("photoFilter"));
            else edit.remove("photoFilter");
            edit.commit();
            finish(code, out);
        }
    }

    private void verifyFilters(Context context, SharedPreferences prefs, CameraScreen ui) throws Exception {
        await("滤镜入口", () -> onUi(ui.filterButton::isEnabled), ui);
        screenshot(context, "finder");
        onUi(() -> { ui.filterButton.performClick(); return null; });
        clickDialogText("柔光");
        await("选中柔光", () -> PhotoFilter.SOFT.id.equals(prefs.getString("photoFilter", "")), ui);
        if (!onUi(() -> ui.shutter.isEnabled() && hasText("层次")))
            throw new AssertionError("滤镜选项挡住了快门");
        screenshot(context, "filters");
        verifyFilteredPhoto(context, prefs, ui, PhotoFilter.SOFT, "TrueHue RAW");
        if (!getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
            throw new AssertionError("系统返回操作不可用");
        }
        await("返回焦距选择", () -> onUi(() -> !hasText("层次") && ui.lensBar.isShown()), ui);

        onUi(() -> { ui.mode.performClick(); return null; });
        clickDialogText("同取景 · 保存屏幕上看到的相机帧");
        clickDialogText("完成");
        await("同取景就绪", () -> prefs.getBoolean("sameFrame", false), ui);
        onUi(() -> { ui.filterButton.performClick(); return null; });
        clickDialogText("层次");
        verifyFilteredPhoto(context, prefs, ui, PhotoFilter.DETAIL, "TrueHue Preview");
    }

    private void verifyFilteredPhoto(Context context, SharedPreferences prefs, CameraScreen ui,
                                     PhotoFilter filter, String software) throws Exception {
        String old = prefs.getString("last", "");
        long start = SystemClock.elapsedRealtime();
        onUi(() -> { ui.shutter.performClick(); return null; });
        await("滤镜成片落盘", () -> !old.equals(prefs.getString("last", "")), ui);
        Uri uri = Uri.parse(prefs.getString("last", ""));
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(in);
            if (!filter.exif.equals(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
                    || !software.equals(exif.getAttribute(ExifInterface.TAG_SOFTWARE))) {
                throw new AssertionError("照片未记录当前滤镜和拍摄来源");
            }
        }
        sendLine(software + " | " + filter.id + " | " + (SystemClock.elapsedRealtime() - start) + " ms | " + uri);
    }

    private void verifyPanels(Context context, SharedPreferences prefs, CameraScreen ui, boolean dng) throws Exception {
        await("原彩拍摄设置", () -> onUi(() -> ui.shutter.isEnabled() && ui.mode.isEnabled()), ui);
        onUi(() -> { ui.mode.performClick(); return null; });
        screenshot(context, "settings");
        clickDialogText("同时保留 DNG 原始底片");
        clickDialogText("取消");
        if (!waitFor(4000, () -> onUi(() -> !hasText("完成"))) || prefs.getBoolean("keepDng", !dng) != dng)
            throw new AssertionError("取消修改了拍摄设置");
        toggleDng(ui);
        if (!waitFor(3000, () -> prefs.getBoolean("keepDng", dng) != dng))
            throw new AssertionError("设置没有保存");
        if (prefs.getString("last", "").isEmpty()) return;
        await("缩略图就绪", () -> onUi(ui.gallery::isEnabled), ui);
        onUi(() -> { ui.gallery.performClick(); return null; });
        await("照片回看", () -> onUi(() -> hasText("在相册中打开")), ui);
        screenshot(context, "review");
        boolean returned = onUi(() -> {
            java.util.ArrayList<View> matches = new java.util.ArrayList<>();
            for (View root : WindowInspector.getGlobalWindowViews()) {
                root.findViewsWithText(matches, "回到取景", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            }
            return matches.stream().filter(View::isShown).findFirst().map(View::performClick).orElse(false);
        });
        if (!returned || !waitFor(4000, () -> onUi(() -> !hasText("在相册中打开"))))
            throw new AssertionError("未返回取景");
    }

    private boolean hasText(String text) {
        java.util.ArrayList<View> matches = new java.util.ArrayList<>();
        for (View root : WindowInspector.getGlobalWindowViews()) {
            root.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
        }
        return matches.stream().anyMatch(View::isShown);
    }

    private void screenshot(Context context, String state) throws Exception {
        if (!Boolean.parseBoolean(args.getString("screenshots", "false"))) return;
        SystemClock.sleep(400);
        Bitmap image = getUiAutomation().takeScreenshot();
        if (image == null) throw new AssertionError("截图不可用");
        try (FileOutputStream out = new FileOutputStream(new File(context.getCacheDir(), "ui-" + state + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            image.recycle();
        }
    }

    // Isolate the hardware half of the loop from the room's current lighting.
    // The sampler and its hysteresis are exercised separately by RawDevelopmentCheck.
    private void verifyExposureControl(MainActivity activity, CameraScreen ui) throws Exception {
        java.lang.reflect.Field rendererField = MainActivity.class.getDeclaredField("renderer");
        java.lang.reflect.Field cameraField = MainActivity.class.getDeclaredField("camera");
        rendererField.setAccessible(true);
        cameraField.setAccessible(true);
        FrameRenderer renderer = (FrameRenderer) rendererField.get(activity);
        CameraInput camera = (CameraInput) cameraField.get(activity);
        renderer.setHighlightMetering(false);
        for (boolean guard : new boolean[]{true, false}) {
            float actual = Float.NaN;
            for (int attempt = 0; attempt < 5; attempt++) {
                camera.protectHighlights(guard);
                SystemClock.sleep(400);
                actual = captureBias(camera, false);
                if (guard ? (actual < -.2f && actual >= -1f) : actual == 0f) break;
            }
            if (!(guard ? (actual < -.2f && actual >= -1f) : actual == 0f))
                throw new AssertionError("镜头测光补偿与请求不符 " + actual);
            sendLine("物理主摄 RAW 测光补偿 " + actual + " EV");
        }

        var setEv = MainActivity.class.getDeclaredMethod("applyUserEv", float.class);
        setEv.setAccessible(true);
        onUi(() -> {
            try {
                setEv.invoke(activity, 1f);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return null;
        });
        SystemClock.sleep(600);
        camera.focusAndMeter(.5f, .5f, true);
        SystemClock.sleep(600);
        if (captureBias(camera, true) != 1f) throw new AssertionError("锁定曝光后未保留 +1 EV");
        runOnMainSync(() -> { callActivityOnPause(activity); callActivityOnResume(activity); });
        await("恢复相机会话", () -> onUi(ui.shutter::isEnabled), ui);
        SystemClock.sleep(600);
        float restored = captureBias((CameraInput) cameraField.get(activity), false);
        if (restored != 1f) throw new AssertionError("恢复会话后 +1 EV 变为 " + restored);
        sendLine("单帧曝光锁生效，恢复会话前后实拍均为 +1 EV");
    }

    private float captureBias(CameraInput camera, boolean expectLock) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Float> bias = new AtomicReference<>(Float.NaN);
        AtomicReference<Boolean> locked = new AtomicReference<>();
        camera.capture(0, new CameraInput.PhotoCallback() {
            @Override public void onImage(Image image, CameraCharacteristics info, TotalCaptureResult shot, int angle) {
                bias.set(RawRenderer.meteredBias(info, shot));
                locked.set(shot.get(CaptureResult.CONTROL_AE_LOCK));
                done.countDown();
            }
            @Override public void onFailure(String message) { done.countDown(); }
        });
        if (!done.await(4, TimeUnit.SECONDS)) throw new AssertionError("测光曝光回调超时");
        if (expectLock && !Boolean.TRUE.equals(locked.get())) throw new AssertionError("单帧快门丢失测光锁");
        return bias.get();
    }

    private void toggleDng(CameraScreen ui) {
        onUi(() -> { ui.mode.performClick(); return null; });
        clickDialogText("同时保留 DNG 原始底片");
        clickDialogText("完成");
    }

    private void clickDialogText(String text) {
        boolean found = waitFor(4000, () -> onUi(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) if (clickText(root, text)) return true;
            return false;
        }));
        if (!found) throw new AssertionError("未找到设置控件 " + text);
    }

    private boolean clickText(View root, String text) {
        if (root instanceof TextView && root.isShown() && root.isEnabled() && root.isClickable()
                && text.contentEquals(((TextView) root).getText())) {
            root.performClick();
            return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup children = (ViewGroup) root;
            for (int i = 0; i < children.getChildCount(); i++)
                if (clickText(children.getChildAt(i), text)) return true;
        }
        return false;
    }

    private void checkLens(Context context, SharedPreferences prefs, CameraScreen ui,
                           String name, boolean checkDng, boolean paired) throws Exception {
        await("镜头列表 " + name, () -> onUi(() -> ui.shutter.isEnabled() && lens(ui, name) != null), ui);
        onUi(() -> { View choice = lens(ui, name); if (!choice.isSelected()) choice.performClick(); return null; });
        await("RAW 镜头就绪 " + name, () -> onUi(() -> ui.shutter.isEnabled() && lens(ui, name) != null && lens(ui, name).isSelected()
                && ui.mode.getContentDescription().toString().contains("原彩显影")), ui);
        boolean proof = waitFor(7500, () -> onUi(() -> ui.rawPreview.getVisibility() == View.VISIBLE));
        String old = prefs.getString("last", "");
        long start = SystemClock.elapsedRealtime();
        onUi(() -> { ui.shutter.performClick(); return null; });
        await("JPEG 落盘 " + name, () -> !old.equals(prefs.getString("last", "")), ui);
        Uri uri = Uri.parse(prefs.getString("last", ""));
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, size); }
        if ((long) size.outWidth * size.outHeight != 4096L * 3072L) {
            throw new AssertionError("意外的成片尺寸 " + size.outWidth + "×" + size.outHeight);
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(in);
            if (!"TrueHue RAW".equals(exif.getAttribute(ExifInterface.TAG_SOFTWARE))
                    || !PhotoFilter.ORIGINAL.exif.equals(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
                    || exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, 0) != 1) {
                throw new AssertionError("未找到 RAW 显影 / sRGB 标记");
            }
            double focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0);
            double expected = name.equals("超广角") ? 2.57 : name.equals("长焦") ? 9.00 : 6.62;
            if (Math.abs(focal - expected) > 0.08) {
                throw new AssertionError(name + " 实际焦距 " + focal + " mm，应接近 " + expected);
            }
            if (paired) verifyBaseline(resolver, uri, exif);
            sendLine(name + " JPEG " + size.outWidth + "x" + size.outHeight + " | " +
                    focal + " mm focal | " +
                    "ISO " + exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS) + " | " +
                    (SystemClock.elapsedRealtime() - start) + " ms | RAW preview " + proof);
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            BitmapFactory.Options hdrOptions = new BitmapFactory.Options();
            hdrOptions.inSampleSize = 4;
            try (InputStream in = resolver.openInputStream(uri)) {
                Bitmap decoded = BitmapFactory.decodeStream(in, null, hdrOptions);
                if (decoded == null) throw new AssertionError("无法解码 RAW JPEG");
                try {
                    if (!decoded.hasGainmap()) throw new AssertionError("未找到 HDR 增益图");
                } finally {
                    decoded.recycle();
                }
            }
        }
        if (checkDng) verifyDng(resolver, uri);
        if (!proof) throw new AssertionError(name + " RAW 预览未出现");
    }

    private void verifyBaseline(ContentResolver resolver, Uri jpeg, ExifInterface natural) throws Exception {
        String column = MediaStore.Images.Media.DISPLAY_NAME;
        String filename;
        try (Cursor c = resolver.query(jpeg, new String[]{column}, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new AssertionError("找不到自然光影成片");
            filename = c.getString(0).replace(".jpg", "_051.jpg");
        }
        Uri images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (Cursor c = resolver.query(images, new String[]{"_id"}, column + "=? AND relative_path=?",
                new String[]{filename, "DCIM/TrueHue/"}, null)) {
            if (c == null || !c.moveToFirst()) throw new AssertionError("找不到同底片基线");
            try (InputStream in = resolver.openInputStream(ContentUris.withAppendedId(images, c.getLong(0)))) {
                ExifInterface old = new ExifInterface(in);
                if (!"Baseline 0.5.1".equals(old.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)))
                    throw new AssertionError("基线标记错误");
                for (String tag : new String[]{ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_FOCAL_LENGTH,
                        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS}) {
                    String value = natural.getAttribute(tag);
                    if (value == null || !value.equals(old.getAttribute(tag))) throw new AssertionError("曝光参数不一致 " + tag);
                }
            }
        }
    }

    private void verifyDng(ContentResolver resolver, Uri jpeg) throws Exception {
        String name;
        String displayName = MediaStore.Images.Media.DISPLAY_NAME;
        try (Cursor c = resolver.query(jpeg, new String[]{displayName}, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new AssertionError("JPEG 未发布");
            name = c.getString(0).replace(".jpg", ".dng");
        }
        Uri images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (Cursor c = resolver.query(images, new String[]{"_id", "_size"}, displayName + "=? AND relative_path=?",
                new String[]{name, "DCIM/TrueHue/"}, null)) {
            if (c == null || !c.moveToFirst() || c.getLong(1) <= 0)
                throw new AssertionError("DNG 原始底片不存在或过小");
            Uri dng = ContentUris.withAppendedId(images, c.getLong(0));
            try (InputStream in = resolver.openInputStream(dng)) {
                verifyRawDirectory(new DataInputStream(in));
            }
            sendLine("RAW DNG 4096x3072, " + c.getLong(1) + " bytes");
        }
    }

    // Read the RAW image directory. Android's ExifInterface may return a preview IFD or omit
    // raw-image tags on compressed DNG files. File length varies with the recorded scene.
    private void verifyRawDirectory(DataInputStream stream) throws Exception {
        byte[] header = new byte[8];
        stream.readFully(header);
        if (header[0] != header[1] || (header[0] != 'I' && header[0] != 'M')) throw new AssertionError("TIFF 字节序错误");
        ByteOrder order = header[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        ByteBuffer prefix = ByteBuffer.wrap(header).order(order);
        int offset = prefix.getInt(4);
        if (prefix.getShort(2) != 42 || offset < 8 || offset > 65536) throw new AssertionError("DNG/TIFF 文件头错误");
        stream.readFully(new byte[offset - 8]);
        byte[] count = new byte[2];
        stream.readFully(count);
        int entries = Short.toUnsignedInt(ByteBuffer.wrap(count).order(order).getShort());
        byte[] entry = new byte[12];
        int width = 0, height = 0, photometric = 0, version = 0;
        for (int i = 0; i < entries; i++) {
            stream.readFully(entry);
            ByteBuffer tag = ByteBuffer.wrap(entry).order(order);
            int id = Short.toUnsignedInt(tag.getShort(0));
            int value = tag.getShort(2) == 3 ? Short.toUnsignedInt(tag.getShort(8)) : tag.getInt(8);
            if (id == 256) width = value;
            if (id == 257) height = value;
            if (id == 262) photometric = value;
            if (id == 50706 && tag.getInt(4) == 4) version = entry[8];
        }
        if (width != 4096 || height != 3072 || photometric != 32803 || version != 1)
            throw new AssertionError("DNG RAW 目录缺少 Bayer 标记、版本或正确尺寸");
    }

    private View lens(CameraScreen ui, String name) {
        for (int i = 0; i < ui.lensBar.getChildCount(); i++) {
            TextView v = (TextView) ui.lensBar.getChildAt(i);
            if (v.getContentDescription().toString().startsWith(name + " ")) return v;
        }
        return null;
    }

    private void sendLine(String text) {
        Bundle status = new Bundle();
        status.putString("detail", text);
        sendStatus(0, status);
    }

    private <T> T onUi(Supplier<T> read) {
        AtomicReference<T> output = new AtomicReference<>();
        runOnMainSync(() -> output.set(read.get()));
        return output.get();
    }

    private boolean waitFor(long duration, Supplier<Boolean> check) {
        long end = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < end) {
            if (check.get()) return true;
            SystemClock.sleep(80);
        }
        return false;
    }

    private void await(String label, Supplier<Boolean> check, CameraScreen ui) {
        if (!waitFor(18000, check)) throw new AssertionError(label + " 超时，界面显示 " +
                onUi(() -> ui.status.getText() + " " + ui.prompt.getText() + " " + ui.mode.getContentDescription()));
    }
}
