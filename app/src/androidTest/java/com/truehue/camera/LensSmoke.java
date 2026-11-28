package com.truehue.camera;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageFormat;
import android.graphics.Rect;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.GridView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
        boolean panelsOnly = Boolean.parseBoolean(args.getString("panelsOnly", "false"));
        boolean regressionsOnly = Boolean.parseBoolean(args.getString("regressionsOnly", "false"));
        Bundle out = new Bundle();
        int code = Activity.RESULT_OK;
        MainActivity activity = null;
        try {
            prefs.edit().putBoolean("sameFrame", false).putBoolean("liveRaw", !exposureOnly && !panelsOnly && !regressionsOnly)
                    .putBoolean("multiFrame", !regressionsOnly).putBoolean("ultraHdr", android.os.Build.VERSION.SDK_INT >= 34)
                    .putBoolean("keepDng", includeDng && !regressionsOnly).putBoolean("guideShown", !panelsOnly)
                    .putString("photoFilter", PhotoFilter.ORIGINAL.id).commit();
            Intent open = new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            sendLine("正在打开本色相机");
            target.startActivity(open);
            activity = (MainActivity) waitForMonitorWithTimeout(monitor, 20000);
            if (activity == null) throw new AssertionError("本色相机未进入前台");
            MainActivity launched = activity;
            if (paired) {
                var comparison = MainActivity.class.getDeclaredField("compareBaseline");
                comparison.setAccessible(true);
                comparison.setBoolean(launched, true);
            }
            CameraScreen ui = onUi(() -> (CameraScreen) ((ViewGroup) launched.findViewById(android.R.id.content)).getChildAt(0));
            if (regressionsOnly) {
                verifyReleaseRegressions(launched, ui);
                out.putString("result", "Capture ownership, album paging and deletion, and DNG recovery passed.");
            } else if (exposureOnly) {
                await("主摄测光就绪", () -> onUi(() -> ui.shutter.isEnabled()), ui);
                verifyExposureControl(launched, ui);
                out.putString("result", "Exposure metering passed.");
            } else if (panelsOnly || Boolean.parseBoolean(args.getString("settingsOnly", "false"))) {
                verifyPanels(target, prefs, ui, includeDng, panelsOnly);
                if (panelsOnly) {
                    out.putString("result", "Camera controls and panels passed.");
                } else {
                    verifyFilters(target, prefs, ui);
                    out.putString("result", "Settings, photo review, filter selection, RAW and viewfinder filter saves passed.");
                }
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
            for (String k : new String[]{"sameFrame", "liveRaw", "multiFrame", "ultraHdr", "keepDng", "guideShown", "showProInfo", "showHistogram"}) {
                if (before.containsKey(k)) edit.putBoolean(k, (Boolean) before.get(k));
                else edit.remove(k);
            }
            if (before.containsKey("photoFilter")) edit.putString("photoFilter", (String) before.get("photoFilter"));
            else edit.remove("photoFilter");
            if (regressionsOnly) {
                edit.putString("last", (String) before.get("last"));
                if (before.containsKey("lastTime")) edit.putLong("lastTime", (Long) before.get("lastTime"));
                else edit.remove("lastTime");
            }
            edit.commit();
            finish(code, out);
        }
    }

    private void verifyReleaseRegressions(MainActivity activity, CameraScreen ui) throws Exception {
        ContentResolver resolver = activity.getContentResolver();
        List<Uri> fixtures = new ArrayList<>();
        Bitmap tile = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888);
        PhotoViewer viewer = null;
        try {
            long date = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);
            for (int i = 0; i < 205; i++) {
                // Keep identical timestamps across page boundaries to exercise the MediaStore ID tie-breaker.
                fixtures.add(insertFixture(resolver, tile, "DCIM/TrueHue/", "TH_test_" + date + "_" + i + ".jpg", date));
            }
            Uri oldest = fixtures.get(0);
            String dngName = "TH_test_" + date + "_0.dng";
            Uri negative = insertFixture(resolver, null, "DCIM/TrueHue/", dngName, date);
            fixtures.add(negative);
            Uri backup = insertFixture(resolver, null, "DCIM/TrueHue/Archive/", dngName, date);
            fixtures.add(backup);
            sendLine("分页与相册操作");
            Uri first = PhotoStore.listPhotos(activity, 1).get(0).uri;
            viewer = onUi(() -> PhotoViewer.open(activity, ui, first, false, new PhotoViewer.Host() {
                @Override public void onShare(Uri uri) { throw new AssertionError("加载期间触发了分享"); }
                @Override public void onOpenInGallery(Uri uri) { throw new AssertionError("加载期间打开了系统相册"); }
                @Override public void onLibraryChanged(Uri uri, long takenAt) {}
                @Override public void onPhotoDeleted(Uri uri) {}
                @Override public void onDismiss() {}
            }));
            PhotoViewer album = viewer;
            await("首屏照片", () -> onUi(() -> first.equals(field(album, "displayedUri")) && albumSize(album) >= 60), ui);
            onUi(() -> {
                invoke(album, "showIndex", new Class<?>[]{int.class, int.class}, 5, 0);
                if (field(album, "displayedUri") != null
                        || ((TextView) field(album, "shareButton")).isEnabled()
                        || ((TextView) field(album, "deleteButton")).isEnabled()) {
                    throw new AssertionError("切图加载中仍可操作照片");
                }
                invoke(album, "setGridVisible", new Class<?>[]{boolean.class}, true);
                return null;
            });
            for (int i = 0; i < 5 && !onUi(() -> albumContains(album, oldest)); i++) {
                int count = onUi(() -> {
                    int size = albumSize(album);
                    ((GridView) field(album, "gridView")).setSelection(size - 1);
                    return size;
                });
                await("继续加载相册照片", () -> onUi(() -> albumSize(album) > count || albumContains(album, oldest)), ui);
            }
            if (!onUi(() -> albumSize(album) > 200 && albumContains(album, oldest))) {
                throw new AssertionError("相册无法浏览第 200 张之后的照片");
            }
            if (PhotoStore.deletePhoto(activity, oldest) != PhotoStore.DeleteResult.DELETED
                    || mediaExists(resolver, oldest) || mediaExists(resolver, negative) || !mediaExists(resolver, backup)) {
                throw new AssertionError("删除操作未正确处理同名底片");
            }
            fixtures.remove(oldest);
            fixtures.remove(negative);
            onUi(() -> { album.dismiss(); return null; });

            sendLine("快门状态和后台取消");
            await("快门就绪", () -> onUi(ui.shutter::isEnabled), ui);
            Object firstCapture = onUi(() -> {
                setField(activity, "proofInFlight", true);
                ui.shutter.performClick();
                Object capture = field(activity, "activeCapture");
                invoke(activity, "handOffCapture", new Class<?>[]{capture.getClass(), String.class}, capture, null);
                ui.shutter.performClick();
                invoke(activity, "photoSaved", new Class<?>[]{Uri.class, String.class, capture.getClass()},
                        first, "测试保存完成", capture);
                return capture;
            });
            onUi(() -> {
                invoke(activity, "actionFailed", new Class<?>[]{firstCapture.getClass(), String.class},
                        firstCapture, "重复回调");
                if (ui.shutter.isEnabled() || !Integer.valueOf(1).equals(field(activity, "pendingSaves"))) {
                    throw new AssertionError("上一张照片的回调改变了当前快门状态");
                }
                invoke(activity, "stopPreview", new Class<?>[0]);
                if (!Integer.valueOf(0).equals(field(activity, "pendingSaves"))) {
                    throw new AssertionError("等待原彩预览时取消拍摄留下了保存任务");
                }
                invoke(activity, "begin", new Class<?>[0]);
                return null;
            });
            await("恢复取景", () -> onUi(ui.shutter::isEnabled), ui);
            onUi(() -> {
                setField(activity, "sameFrame", true);
                ui.shutter.performClick();
                invoke(activity, "stopPreview", new Class<?>[0]);
                setField(activity, "sameFrame", false);
                if (!Integer.valueOf(0).equals(field(activity, "pendingSaves"))) {
                    throw new AssertionError("取景器关闭时快拍留下了保存任务");
                }
                invoke(activity, "begin", new Class<?>[0]);
                return null;
            });

            sendLine("单帧 RAW 显影失败恢复 DNG");
            await("RAW 就绪", () -> onUi(() -> ui.shutter.isEnabled()
                    && Integer.valueOf(ImageFormat.RAW_SENSOR).equals(field(activity, "stillFormat"))), ui);
            long stamp = onUi(() -> {
                Object originalFilter = field(activity, "filter");
                setField(activity, "filter", null); // Force the developer to fail after RAW extraction.
                ui.shutter.performClick();
                setField(activity, "filter", originalFilter);
                return (long) field(field(activity, "lastCapture"), "stamp");
            });
            await("DNG 恢复", () -> onUi(() -> ui.status.getText().toString().contains("已保存原始底片")), ui);
            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String dngFile = new SimpleDateFormat("'TH_'yyyyMMdd_HHmmss_SSS'.dng'", Locale.ROOT)
                    .format(new Date(stamp));
            try (Cursor cursor = resolver.query(collection, new String[]{"_id"},
                    "relative_path = ? AND _display_name = ?",
                    new String[]{"DCIM/TrueHue/", dngFile}, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new AssertionError("缺少恢复底片");
                Uri recovered = ContentUris.withAppendedId(collection, cursor.getLong(0));
                fixtures.add(recovered);
                try (DataInputStream in = new DataInputStream(resolver.openInputStream(recovered))) {
                    verifyRawDirectory(in);
                }
            }
        } finally {
            PhotoViewer album = viewer;
            if (album != null) onUi(() -> { album.dismiss(); return null; });
            tile.recycle();
            for (Uri uri : fixtures) {
                if (mediaExists(resolver, uri)) resolver.delete(uri, null, null);
            }
        }
    }

    private static Uri insertFixture(ContentResolver resolver, Bitmap jpeg, String path, String name, long date)
            throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.RELATIVE_PATH, path);
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, jpeg == null ? "image/x-adobe-dng" : "image/jpeg");
        values.put(MediaStore.Images.Media.DATE_TAKEN, date);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        try {
            try (OutputStream stream = resolver.openOutputStream(uri)) {
                if (jpeg == null) stream.write(0);
                else if (!jpeg.compress(Bitmap.CompressFormat.JPEG, 85, stream)) throw new AssertionError("测试图写入失败");
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) throw new AssertionError("测试图发布失败");
            return uri;
        } catch (Exception | AssertionError ex) {
            resolver.delete(uri, null, null);
            throw ex;
        }
    }

    private static boolean mediaExists(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{"_id"}, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        }
    }

    private static int albumSize(PhotoViewer album) {
        return ((List<?>) field(album, "photos")).size();
    }

    private static boolean albumContains(PhotoViewer album, Uri uri) {
        return (int) invoke(album, "indexOfUri", new Class<?>[]{Uri.class}, uri) >= 0;
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) {
        try {
            var method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
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
        clickDialogText("极速快拍");
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

    private void verifyPanels(Context context, SharedPreferences prefs, CameraScreen ui, boolean dng, boolean firstLaunch) throws Exception {
        await("原彩拍摄设置", () -> onUi(() -> ui.shutter.isEnabled() && ui.mode.isEnabled()), ui);
        onUi(() -> {
            verifyHeaderTargets(ui);
            Configuration config = new Configuration(ui.getResources().getConfiguration());
            config.fontScale = 2f;
            CameraScreen compact = new CameraScreen(ui.getContext().createConfigurationContext(config));
            compact.measure(View.MeasureSpec.makeMeasureSpec(compact.dp(320), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(compact.dp(600), View.MeasureSpec.EXACTLY));
            compact.layout(0, 0, compact.getMeasuredWidth(), compact.getMeasuredHeight());
            verifyHeaderTargets(compact);
            return null;
        });
        if (!firstLaunch) onUi(() -> { ui.helpButton.performClick(); return null; });
        await("新手指南", () -> onUi(() -> hasText("本色相机 · 新手指南")), ui);
        verifyGuideFooter("知道了，开始拍照");
        screenshot(context, "guide");
        clickDialogText("知道了，开始拍照");
        if (!waitFor(4000, () -> onUi(() -> !hasText("本色相机 · 新手指南"))))
            throw new AssertionError("新手指南未关闭");
        boolean proInfo = prefs.getBoolean("showProInfo", false);
        boolean histogram = prefs.getBoolean("showHistogram", false);
        onUi(() -> { ui.mode.performClick(); return null; });
        String version = "版本 " + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        await("设置显示安装版本", () -> onUi(() -> {
            TextView label = dialogText(version);
            return label != null && !label.isClickable();
        }), ui);
        screenshot(context, "settings");
        clickDialogText("专业拍摄参数");
        clickDialogText("直方图");
        clickDialogText("取消");
        await("取消保留拍照辅助偏好", () -> onUi(() -> !hasText("完成"))
                && prefs.getBoolean("showProInfo", false) == proInfo
                && prefs.getBoolean("showHistogram", false) == histogram, ui);
        onUi(() -> { ui.mode.performClick(); return null; });
        clickDialogText("专业拍摄参数");
        clickDialogText("直方图");
        clickDialogText("完成");
        await("完成保存拍照辅助偏好", () -> onUi(() -> !hasText("完成"))
                && prefs.getBoolean("showProInfo", false) != proInfo
                && prefs.getBoolean("showHistogram", false) != histogram, ui);
        toggleDng(ui);
        if (!waitFor(3000, () -> prefs.getBoolean("keepDng", dng) != dng))
            throw new AssertionError("顶部存底片开关没有保存");
        verifyQuickToggles(prefs, ui);
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

    private void verifyHeaderTargets(CameraScreen ui) {
        for (View button : new View[]{ui.helpButton, ui.gridButton, ui.mode}) {
            Rect bounds = new Rect(0, 0, button.getWidth(), button.getHeight());
            ui.offsetDescendantRectToMyCoords(button, bounds);
            if (bounds.width() < ui.dp(48) || bounds.height() < ui.dp(48)
                    || bounds.left < 0 || bounds.right > ui.getWidth()) {
                throw new AssertionError("顶部按钮点击区不足 48 dp 或超出屏幕：" + button.getContentDescription());
            }
        }
    }

    private void verifyGuideFooter(String text) {
        if (!waitFor(4000, () -> onUi(() -> {
            TextView action = dialogText(text);
            Rect visible = new Rect();
            return action != null && action.getHeight() > 0 && action.getGlobalVisibleRect(visible)
                    && visible.height() == action.getHeight();
        }))) throw new AssertionError("首次打开指南时底部按钮未完全显示：" + text);

        onUi(() -> {
            TextView action = dialogText(text);
            ViewGroup sheet = (ViewGroup) action.getParent();
            ScrollView body = (ScrollView) sheet.getChildAt(0);
            int top = action.getTop();
            int before = body.getScrollY();
            body.scrollTo(0, body.getChildAt(0).getHeight());
            if (body.getChildAt(0).getHeight() > body.getHeight() && body.getScrollY() == before) {
                throw new AssertionError("长指南正文无法滚动");
            }
            if (action.getTop() != top || !action.isShown()) {
                throw new AssertionError("底部按钮随指南正文滚动");
            }
            body.scrollTo(0, 0);
            return null;
        });
    }

    private void verifyQuickToggles(SharedPreferences prefs, CameraScreen ui) {
        boolean dng = prefs.getBoolean("keepDng", false);
        boolean hdr = prefs.getBoolean("ultraHdr", false);
        onUi(() -> { ui.mode.performClick(); return null; });
        clickDialogText("极速快拍");
        clickDialogText("完成");
        await("快拍开关均不可用", () -> onUi(() -> prefs.getBoolean("sameFrame", false) && !hasText("完成")
                && !ui.hdrToggle.isEnabled() && !ui.dngToggle.isEnabled()
                && !ui.hdrToggle.isSelected() && !ui.dngToggle.isSelected()), ui);
        onUi(() -> {
            ui.hdrToggle.performClick();
            ui.dngToggle.performClick();
            ui.mode.performClick();
            return null;
        });
        if (prefs.getBoolean("keepDng", !dng) != dng || prefs.getBoolean("ultraHdr", !hdr) != hdr)
            throw new AssertionError("快拍模式修改了原彩开关偏好");
        clickDialogText("原彩直出");
        clickDialogText("完成");
        await("恢复原彩开关", () -> onUi(() -> !prefs.getBoolean("sameFrame", true) && !hasText("完成")
                && ui.dngToggle.isEnabled() && ui.dngToggle.isSelected() == dng
                && (android.os.Build.VERSION.SDK_INT < 34 || ui.hdrToggle.isEnabled()
                && ui.hdrToggle.isSelected() == hdr)), ui);
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
        await("顶部存底片开关", () -> onUi(ui.dngToggle::isEnabled), ui);
        onUi(ui.dngToggle::performClick);
    }

    private void clickDialogText(String text) {
        boolean found = waitFor(4000, () -> onUi(() -> {
            TextView view = dialogText(text);
            if (view == null || !view.isEnabled() || !view.isClickable()) return false;
            view.performClick();
            return true;
        }));
        if (!found) throw new AssertionError("未找到设置控件 " + text);
    }

    private TextView dialogText(String text) {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            TextView found = findText(root, text);
            if (found != null) return found;
        }
        return null;
    }

    private TextView findText(View root, String text) {
        if (root instanceof TextView && root.isShown()) {
            String label = ((TextView) root).getText().toString();
            if (label.equals(text) || label.startsWith(text + "\n")) {
                return (TextView) root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup children = (ViewGroup) root;
            for (int i = 0; i < children.getChildCount(); i++) {
                TextView found = findText(children.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void checkLens(Context context, SharedPreferences prefs, CameraScreen ui,
                           String name, boolean checkDng, boolean paired) throws Exception {
        await("镜头列表 " + name, () -> onUi(() -> ui.shutter.isEnabled() && lens(ui, name) != null), ui);
        onUi(() -> { View choice = lens(ui, name); if (!choice.isSelected()) choice.performClick(); return null; });
        await("RAW 镜头就绪 " + name, () -> onUi(() -> ui.shutter.isEnabled() && lens(ui, name) != null && lens(ui, name).isSelected()
                && ui.mode.getContentDescription().toString().contains("原彩直出")), ui);
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
            int colorSpaceTag = exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, 0);
            if (!"TrueHue RAW".equals(exif.getAttribute(ExifInterface.TAG_SOFTWARE))
                    || !PhotoFilter.ORIGINAL.exif.equals(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
                    || colorSpaceTag != 65535) {
                throw new AssertionError("未找到 RAW 显影 / Display P3 标记: " + colorSpaceTag);
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
        try (InputStream in = resolver.openInputStream(uri)) {
            ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while (headBytes.size() < 65536 && (r = in.read(buf)) > 0) {
                headBytes.write(buf, 0, r);
            }
            String headAscii = new String(headBytes.toByteArray(), StandardCharsets.ISO_8859_1);
            if (!headAscii.contains("ICC_PROFILE")) {
                throw new AssertionError("JPEG 缺少 Display P3 ICC_PROFILE");
            }
        }
        BitmapFactory.Options hdrOptions = new BitmapFactory.Options();
        hdrOptions.inSampleSize = 4;
        try (InputStream in = resolver.openInputStream(uri)) {
            Bitmap decoded = BitmapFactory.decodeStream(in, null, hdrOptions);
            if (decoded == null) throw new AssertionError("无法解码 RAW JPEG");
            try {
                if (!ColorSpace.get(ColorSpace.Named.DISPLAY_P3).equals(decoded.getColorSpace())) {
                    throw new AssertionError("成片色彩空间不是 Display P3: " + decoded.getColorSpace());
                }
                if (android.os.Build.VERSION.SDK_INT >= 34 && !decoded.hasGainmap()) {
                    throw new AssertionError("未找到 HDR 增益图");
                }
            } finally {
                decoded.recycle();
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
