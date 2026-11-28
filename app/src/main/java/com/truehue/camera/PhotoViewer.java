package com.truehue.camera;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native obsidian in-app photo viewer with swipe browsing, filmstrip, grid album, Ultra HDR display, and delete. */
final class PhotoViewer {
    interface Host {
        void onShare(Uri uri);
        void onOpenInGallery(Uri uri);
        void onLibraryChanged(Uri newestUri, long newestTakenAt);
        void onPhotoDeleted(Uri uri);
        void onDismiss();
    }

    private static final int PAGE_SIZE = 60;
    private static final Comparator<PhotoStore.PhotoEntry> NEWEST_FIRST = Comparator
            .comparing((PhotoStore.PhotoEntry photo) -> photo.dateTaken,
                    Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Comparator.comparingLong((PhotoStore.PhotoEntry photo) -> photo.id).reversed());
    private static final int MAX_FILMSTRIP_ITEMS = 60;
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "TrueHue-Viewer");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService THUMB_LOADER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "TrueHue-Thumbnail");
        t.setDaemon(true);
        return t;
    });

    private final Activity activity;
    private final CameraScreen ui;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<PhotoStore.PhotoEntry> photos = new ArrayList<>();
    private final Set<Uri> excluded = new HashSet<>();
    private final LruCache<Uri, Bitmap> fullCache = new LruCache<>(3);
    private final LruCache<Uri, Bitmap> thumbCache = new LruCache<Uri, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(Uri key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount() / 1024);
        }
    };

    private Dialog dialog;
    private Runnable unregisterBack;
    private LinearLayout singleLayer, gridLayer, header, bottomDock, filmstripRow;
    private TextView titleView, timeView, modePill, hdrPill, exifView, loadingText, gridCountView;
    private CameraPanels.ZoomableImageView photoView;
    private LinearLayout loadingOverlay;
    private HorizontalScrollView filmstripScroll;
    private GridView gridView;
    private GridAdapter gridAdapter;

    private int currentIndex = -1;
    private int loadGen;
    private int filmstripStart;
    private Uri displayedUri, loadingUri;
    private TextView shareButton, deleteButton, openGalleryButton;
    private PhotoStore.PhotoEntry pageAnchor;
    private boolean loadingPage, hasMore = true;
    private boolean pendingSave;
    private boolean showingGrid;
    private boolean chromeVisible = true;
    private boolean userNavigated;
    private volatile boolean dismissed;

    private PhotoViewer(Activity activity, CameraScreen ui, boolean pendingSave, Host host) {
        this.activity = activity;
        this.ui = ui;
        this.pendingSave = pendingSave;
        this.host = host;
    }

    static PhotoViewer open(Activity activity, CameraScreen ui, Uri initialUri, boolean pendingSave, Host host) {
        PhotoViewer viewer = new PhotoViewer(activity, ui, pendingSave, host);
        viewer.show(initialUri);
        return viewer;
    }

    private void show(Uri initialUri) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(CameraScreen.BG);
        buildSingleLayer(root);
        buildGridLayer(root);
        gridLayer.setVisibility(View.GONE);

        dialog = new Dialog(activity, R.style.CameraPhoto) {
            @Override public void onBackPressed() {
                if (showingGrid) setGridVisible(false);
                else super.onBackPressed();
            }
        };
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setDecorFitsSystemWindows(false);
            window.setColorMode(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ? ActivityInfo.COLOR_MODE_HDR
                    : ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT);
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
                window.setWindowAnimations(0);
            }
        }
        CameraScreen.useSystemInsets(root);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OnBackInvokedDispatcher dispatcher = dialog.getOnBackInvokedDispatcher();
            OnBackInvokedCallback callback = () -> {
                if (showingGrid) setGridVisible(false);
                else dialog.dismiss();
            };
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            unregisterBack = () -> dispatcher.unregisterOnBackInvokedCallback(callback);
        }

        dialog.setOnDismissListener(d -> cleanup());
        dialog.show();

        if (initialUri != null) {
            photos.add(new PhotoStore.PhotoEntry(initialUri, null, 0L));
            currentIndex = 0;
            showIndex(0, 0);
        } else {
            updateHeaderEmpty();
        }
        loadNextPage();
    }

    private void buildSingleLayer(FrameLayout root) {
        singleLayer = new LinearLayout(activity);
        singleLayer.setOrientation(LinearLayout.VERTICAL);
        singleLayer.setBackgroundColor(CameraScreen.BG);
        root.addView(singleLayer, new FrameLayout.LayoutParams(-1, -1));

        // Top bar
        header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(ui.dp(6), ui.dp(2), ui.dp(8), ui.dp(2));
        singleLayer.addView(header, new LinearLayout.LayoutParams(-1, ui.dp(56)));

        CameraScreen.Mark backBtn = new CameraScreen.Mark(activity, CameraScreen.Mark.BACK);
        backBtn.setContentDescription("回到取景");
        backBtn.setForeground(ui.ripple(24));
        backBtn.setFocusable(true);
        backBtn.setOnClickListener(v -> dialog.dismiss());
        header.addView(backBtn, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setGravity(Gravity.CENTER_VERTICAL);
        titleCol.setPaddingRelative(ui.dp(6), 0, ui.dp(8), 0);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));

        titleView = ui.label("本色相册", 16, CameraScreen.INK);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setFontFeatureSettings("tnum");
        titleView.setMaxLines(1);
        titleView.setAccessibilityHeading(true);
        titleCol.addView(titleView);

        timeView = ui.label("", 11, CameraScreen.MUTED);
        timeView.setFontFeatureSettings("tnum");
        timeView.setMaxLines(1);
        timeView.setVisibility(View.GONE);
        titleCol.addView(timeView);

        modePill = ui.label("", 11, CameraScreen.INK);
        modePill.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
        modePill.setBackground(ui.rounded(CameraScreen.ELEVATED, 10, CameraScreen.LINE));
        modePill.setVisibility(View.GONE);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.setMarginEnd(ui.dp(6));
        header.addView(modePill, mp);

        hdrPill = ui.label("亮彩", 11, CameraScreen.ACCENT);
        hdrPill.setContentDescription("高光亮彩照片");
        hdrPill.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
        hdrPill.setBackground(ui.rounded(0x26e8a838, 10, CameraScreen.ACCENT));
        hdrPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.setMarginEnd(ui.dp(4));
        header.addView(hdrPill, hp);

        CameraScreen.Mark gridBtn = new CameraScreen.Mark(activity, CameraScreen.Mark.ALBUM_GRID);
        gridBtn.setContentDescription("全部照片");
        gridBtn.setTooltipText("全部照片");
        gridBtn.setForeground(ui.ripple(22));
        gridBtn.setFocusable(true);
        gridBtn.setOnClickListener(v -> setGridVisible(true));
        header.addView(gridBtn, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));

        // Center Zoomable Stage
        FrameLayout stage = new FrameLayout(activity);
        stage.setBackgroundColor(CameraScreen.BG);
        singleLayer.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        photoView = new CameraPanels.ZoomableImageView(activity);
        photoView.setContentDescription("照片查看，支持左右滑动切换、双指缩放与双击放大");
        photoView.setInteractionListener(new CameraPanels.ZoomableImageView.InteractionListener() {
            @Override public void onTap() { toggleChrome(); }
            @Override public boolean canSwipe(int delta) {
                int target = currentIndex + delta;
                return target >= 0 && target < photos.size();
            }
            @Override public void onSwipe(int delta) {
                int target = currentIndex + delta;
                if (target >= 0 && target < photos.size()) {
                    userNavigated = true;
                    showIndex(target, delta);
                }
            }
            @Override public void onSwipeDownDismiss() { dialog.dismiss(); }
        });
        stage.addView(photoView, new FrameLayout.LayoutParams(-1, -1));

        loadingOverlay = new LinearLayout(activity);
        loadingOverlay.setOrientation(LinearLayout.VERTICAL);
        loadingOverlay.setGravity(Gravity.CENTER);
        loadingOverlay.setVisibility(View.GONE);
        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(CameraScreen.ACCENT));
        loadingOverlay.addView(spinner, new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
        loadingText = ui.label("正在加载…", 13, CameraScreen.MUTED);
        loadingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(-2, -2);
        ltLp.topMargin = ui.dp(10);
        loadingOverlay.addView(loadingText, ltLp);
        stage.addView(loadingOverlay, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // Bottom Dock
        bottomDock = new LinearLayout(activity);
        bottomDock.setOrientation(LinearLayout.VERTICAL);
        bottomDock.setBackgroundColor(CameraScreen.PANEL);
        singleLayer.addView(bottomDock, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(activity);
        divider.setBackgroundColor(CameraScreen.LINE);
        bottomDock.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(0.75f))));

        exifView = ui.label("", 12, CameraScreen.MUTED);
        exifView.setFontFeatureSettings("tnum");
        exifView.setGravity(Gravity.CENTER);
        exifView.setMaxLines(1);
        exifView.setEllipsize(TextUtils.TruncateAt.END);
        exifView.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(4));
        exifView.setVisibility(View.GONE);
        bottomDock.addView(exifView, new LinearLayout.LayoutParams(-1, -2));

        filmstripScroll = new HorizontalScrollView(activity);
        filmstripScroll.setHorizontalScrollBarEnabled(false);
        filmstripScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(-1, -2);
        fsLp.topMargin = ui.dp(6);
        bottomDock.addView(filmstripScroll, fsLp);

        filmstripRow = new LinearLayout(activity);
        filmstripRow.setOrientation(LinearLayout.HORIZONTAL);
        filmstripRow.setGravity(Gravity.CENTER_VERTICAL);
        filmstripRow.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4));
        filmstripScroll.addView(filmstripRow, new FrameLayout.LayoutParams(-2, -2));

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        actionRow.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(14));
        bottomDock.addView(actionRow, new LinearLayout.LayoutParams(-1, -2));

        shareButton = iconActionButton(CameraScreen.Mark.SHARE, "分享", v -> {
            PhotoStore.PhotoEntry entry = displayedEntry();
            if (entry != null) host.onShare(entry.uri);
        });
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(-2, -2);
        shareLp.setMarginEnd(ui.dp(8));
        actionRow.addView(shareButton, shareLp);

        deleteButton = iconActionButton(CameraScreen.Mark.DELETE, "删除", v -> {
            PhotoStore.PhotoEntry entry = displayedEntry();
            if (entry != null) confirmDelete(entry);
        });
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(-2, -2);
        delLp.setMarginEnd(ui.dp(8));
        actionRow.addView(deleteButton, delLp);

        openGalleryButton = CameraPanels.button(ui, "在相册中打开", false, v -> {
            PhotoStore.PhotoEntry entry = displayedEntry();
            if (entry == null) return;
            Uri target = entry.uri;
            dialog.dismiss();
            host.onOpenInGallery(target);
        });
        actionRow.addView(openGalleryButton, new LinearLayout.LayoutParams(-2, -2));
        setDisplayedUri(null);
    }

    private TextView iconActionButton(int markKind, String title, View.OnClickListener click) {
        // Keep accessible TextView button styling consistent with CameraPanels.button
        TextView btn = ui.label(title, 13, CameraScreen.INK);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setGravity(Gravity.CENTER);
        btn.setPaddingRelative(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        btn.setMinHeight(ui.dp(48));
        btn.setBackground(ui.rounded(CameraScreen.ELEVATED, 16, CameraScreen.LINE));
        btn.setForeground(ui.ripple(16));
        btn.setFocusable(true);
        btn.setContentDescription(markKind == CameraScreen.Mark.DELETE ? "删除当前照片" : "分享当前照片");
        btn.setOnClickListener(click);
        return btn;
    }

    private void buildGridLayer(FrameLayout root) {
        gridLayer = new LinearLayout(activity);
        gridLayer.setOrientation(LinearLayout.VERTICAL);
        gridLayer.setBackgroundColor(CameraScreen.BG);
        root.addView(gridLayer, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout gridHeader = new LinearLayout(activity);
        gridHeader.setGravity(Gravity.CENTER_VERTICAL);
        gridHeader.setPadding(ui.dp(6), ui.dp(2), ui.dp(14), ui.dp(2));
        gridLayer.addView(gridHeader, new LinearLayout.LayoutParams(-1, ui.dp(56)));

        CameraScreen.Mark backToSingle = new CameraScreen.Mark(activity, CameraScreen.Mark.BACK);
        backToSingle.setContentDescription("返回单张查看");
        backToSingle.setForeground(ui.ripple(24));
        backToSingle.setFocusable(true);
        backToSingle.setOnClickListener(v -> setGridVisible(false));
        gridHeader.addView(backToSingle, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));

        TextView gridTitle = CameraPanels.heading(ui, "本色相册");
        gridTitle.setPaddingRelative(ui.dp(6), 0, 0, 0);
        gridHeader.addView(gridTitle, new LinearLayout.LayoutParams(0, -2, 1));

        gridCountView = ui.label("", 12, CameraScreen.MUTED);
        gridCountView.setFontFeatureSettings("tnum");
        gridHeader.addView(gridCountView, new LinearLayout.LayoutParams(-2, -2));

        gridView = new GridView(activity);
        gridView.setNumColumns(3);
        int gap = ui.dp(3);
        gridView.setHorizontalSpacing(gap);
        gridView.setVerticalSpacing(gap);
        gridView.setPadding(gap, gap, gap, gap);
        gridView.setClipToPadding(false);
        gridView.setBackgroundColor(CameraScreen.BG);
        gridAdapter = new GridAdapter();
        gridView.setAdapter(gridAdapter);
        gridView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {}

            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (showingGrid && visible > 0 && first + visible >= total - 9) loadNextPage();
            }
        });
        gridView.setOnItemClickListener((parent, view, position, id) -> {
            int index = indexOfUri((Uri) view.getTag());
            if (index >= 0) {
                userNavigated = true;
                setGridVisible(false);
                showIndex(index, 0);
            }
        });
        gridView.setOnItemLongClickListener((parent, view, position, id) -> {
            int index = indexOfUri((Uri) view.getTag());
            if (index >= 0) {
                showGridItemSheet(index);
                return true;
            }
            return false;
        });
        gridLayer.addView(gridView, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void setGridVisible(boolean visible) {
        if (dismissed || showingGrid == visible) return;
        showingGrid = visible;
        if (visible) {
            setChromeVisible(true);
            updateGrid();
            gridLayer.setVisibility(View.VISIBLE);
            singleLayer.setVisibility(View.GONE);
            if (currentIndex >= 0 && currentIndex < photos.size()) {
                gridView.setSelection(currentIndex);
            }
        } else {
            gridLayer.setVisibility(View.GONE);
            singleLayer.setVisibility(View.VISIBLE);
        }
    }

    private void toggleChrome() {
        setChromeVisible(!chromeVisible);
    }

    private void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        header.setVisibility(visible ? View.VISIBLE : View.GONE);
        bottomDock.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private PhotoStore.PhotoEntry currentEntry() {
        if (currentIndex < 0 || currentIndex >= photos.size()) return null;
        return photos.get(currentIndex);
    }

    private PhotoStore.PhotoEntry displayedEntry() {
        PhotoStore.PhotoEntry entry = currentEntry();
        return entry != null && entry.uri.equals(displayedUri) ? entry : null;
    }

    private void setDisplayedUri(Uri uri) {
        displayedUri = uri;
        for (TextView button : new TextView[]{shareButton, deleteButton, openGalleryButton}) {
            button.setEnabled(uri != null);
            button.setAlpha(uri != null ? 1f : 0.45f);
        }
    }

    void onPhotoSaved(Uri uri, long takenAt, boolean morePending) {
        if (dismissed) return;
        pendingSave = morePending;
        PhotoStore.PhotoEntry selected = currentEntry();
        int existing = indexOfUri(uri);
        if (existing < 0) {
            photos.add(new PhotoStore.PhotoEntry(uri, takenAt, takenAt));
            photos.sort(NEWEST_FIRST);
        }
        boolean followNewest = !userNavigated && photos.get(0).uri.equals(uri);
        updateLibrarySelection(followNewest ? uri : selected == null ? null : selected.uri, 0,
                followNewest && selected != null ? -1 : 0);
    }

    void onPendingSaveFinished() {
        if (dismissed) return;
        pendingSave = false;
        updateCountersAndTime();
        closeIfEmpty();
    }

    private void loadNextPage() {
        if (dismissed || !hasMore || loadingPage) return;
        loadingPage = true;
        PhotoStore.PhotoEntry anchor = pageAnchor;
        Context appCtx = activity.getApplicationContext();
        LOADER.execute(() -> {
            List<PhotoStore.PhotoEntry> page = PhotoStore.listPhotos(appCtx, PAGE_SIZE + 1, anchor);
            main.post(() -> {
                if (dismissed) return;
                PhotoStore.PhotoEntry selected = currentEntry();
                loadingPage = false;
                hasMore = page.size() > PAGE_SIZE;
                int count = Math.min(PAGE_SIZE, page.size());
                if (count > 0) pageAnchor = page.get(count - 1);
                Set<Uri> incoming = new HashSet<>();
                for (int i = 0; i < count; i++) incoming.add(page.get(i).uri);
                photos.removeIf(entry -> incoming.contains(entry.uri));
                for (int i = 0; i < count; i++) {
                    PhotoStore.PhotoEntry entry = page.get(i);
                    if (!excluded.contains(entry.uri)) photos.add(entry);
                }
                photos.sort(NEWEST_FIRST);
                updateLibrarySelection(selected == null ? null : selected.uri, 0, 0);
                if (anchor == null || selected == null) reportNewest();
                closeIfEmpty();
                if (photos.isEmpty() && hasMore) loadNextPage();
            });
        });
    }

    private void closeIfEmpty() {
        if (photos.isEmpty() && !loadingPage && !hasMore && !pendingSave) {
            Toast.makeText(activity, "本色相册暂无照片", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }
    }

    private void updateLibrarySelection(Uri selectedUri, int fallbackIndex, int slideDir) {
        int target = indexOfUri(selectedUri);
        if (target < 0 && !photos.isEmpty()) target = Math.min(fallbackIndex, photos.size() - 1);
        currentIndex = target;
        rebuildFilmstrip();
        updateGrid();
        if (target < 0) {
            loadGen++;
            loadingUri = null;
            setDisplayedUri(null);
            photoView.setImageDrawable(null);
            updateHeaderEmpty();
        } else if (!photos.get(target).uri.equals(displayedUri) && !photos.get(target).uri.equals(loadingUri)) {
            showIndex(target, slideDir);
        } else {
            updateCountersAndTime();
        }
    }

    private void updateGrid() {
        gridCountView.setText(photos.size() + (hasMore ? "+ 张" : " 张"));
        gridAdapter.notifyDataSetChanged();
    }

    private void reportNewest() {
        PhotoStore.PhotoEntry newest = photos.isEmpty() ? null : photos.get(0);
        host.onLibraryChanged(newest == null ? null : newest.uri, newest == null ? 0L : newest.takenAt);
    }

    private int indexOfUri(Uri uri) {
        if (uri == null) return -1;
        for (int i = 0; i < photos.size(); i++) {
            if (uri.equals(photos.get(i).uri)) return i;
        }
        return -1;
    }

    private void updateHeaderEmpty() {
        titleView.setText("本色相册");
        timeView.setText(pendingSave ? "新照片冲洗中…" : "");
        timeView.setTextColor(pendingSave ? CameraScreen.ACCENT : CameraScreen.MUTED);
        timeView.setVisibility(pendingSave ? View.VISIBLE : View.GONE);
        modePill.setVisibility(View.GONE);
        hdrPill.setVisibility(View.GONE);
        exifView.setVisibility(View.GONE);
        loadingText.setText(pendingSave ? "正在冲洗刚拍的照片…" : "暂无照片");
        loadingOverlay.setVisibility(pendingSave ? View.VISIBLE : View.GONE);
    }

    private void updateCountersAndTime() {
        PhotoStore.PhotoEntry entry = currentEntry();
        if (entry == null) {
            updateHeaderEmpty();
            return;
        }
        titleView.setText(photos.size() > 1
                ? (currentIndex + 1) + " / " + photos.size() + (hasMore ? "+" : "") : "照片");
        if (pendingSave) {
            timeView.setText("新照片冲洗中…");
            timeView.setTextColor(CameraScreen.ACCENT);
            timeView.setVisibility(View.VISIBLE);
        } else {
            String time = PhotoStore.formatTakenTime(entry.takenAt);
            timeView.setText(time);
            timeView.setTextColor(CameraScreen.MUTED);
            timeView.setVisibility(time.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void showIndex(int index, int slideDir) {
        if (dismissed || index < 0 || index >= photos.size()) return;
        currentIndex = index;
        PhotoStore.PhotoEntry entry = photos.get(index);
        final int gen = ++loadGen;
        if (index >= photos.size() - 4) loadNextPage();
        updateCountersAndTime();
        if (filmstripStart != firstFilmstripIndex()) rebuildFilmstrip();
        else updateFilmstripSelection();
        if (showingGrid) gridAdapter.notifyDataSetChanged();

        Bitmap cached = fullCache.get(entry.uri);
        loadingUri = cached == null ? entry.uri : null;
        if (cached != null) {
            loadingOverlay.setVisibility(View.GONE);
            photoView.setImageBitmap(cached);
            photoView.playSlideIn(slideDir);
            setDisplayedUri(entry.uri);
        } else {
            setDisplayedUri(null);
            photoView.setImageDrawable(null);
            photoView.resetTransform();
            loadingText.setText("正在加载…");
            loadingOverlay.setVisibility(View.VISIBLE);
        }

        modePill.setVisibility(View.GONE);
        hdrPill.setVisibility(View.GONE);
        exifView.setVisibility(View.GONE);

        final int maxEdge = Math.max(1280, Math.max(ui.getWidth(), ui.getHeight()));
        final Context appCtx = activity.getApplicationContext();
        LOADER.execute(() -> {
            Bitmap loaded = cached;
            IOException error = null;
            if (loaded == null) {
                try {
                    loaded = PhotoStore.read(appCtx, entry.uri, maxEdge);
                } catch (IOException | RuntimeException ex) {
                    error = ex instanceof IOException ? (IOException) ex : new IOException(ex);
                }
            }
            PhotoStore.PhotoMeta meta = PhotoStore.readMeta(appCtx, entry.uri);
            Bitmap finalLoaded = loaded;
            IOException finalError = error;
            main.post(() -> {
                if (dismissed || gen != loadGen) return;
                PhotoStore.PhotoEntry selected = currentEntry();
                if (selected == null || !entry.uri.equals(selected.uri)) return;
                loadingUri = null;
                if (finalLoaded == null || finalError != null) {
                    loadingOverlay.setVisibility(View.GONE);
                    handleUnreadableEntry(entry);
                    return;
                }
                if (cached == null) {
                    fullCache.put(entry.uri, finalLoaded);
                    loadingOverlay.setVisibility(View.GONE);
                    photoView.setImageBitmap(finalLoaded);
                    photoView.playSlideIn(slideDir);
                    setDisplayedUri(entry.uri);
                }
                applyMeta(meta);
                preloadNeighbor(currentIndex - 1, maxEdge, appCtx);
                preloadNeighbor(currentIndex + 1, maxEdge, appCtx);
            });
        });
    }

    private void preloadNeighbor(int index, int maxEdge, Context appCtx) {
        if (index < 0 || index >= photos.size()) return;
        Uri uri = photos.get(index).uri;
        if (fullCache.get(uri) != null) return;
        LOADER.execute(() -> {
            if (dismissed || fullCache.get(uri) != null) return;
            try {
                Bitmap bmp = PhotoStore.read(appCtx, uri, maxEdge);
                main.post(() -> {
                    if (!dismissed) fullCache.put(uri, bmp);
                });
            } catch (IOException | RuntimeException ignored) {
                // Neighbor preload failure is harmless.
            }
        });
    }

    private void applyMeta(PhotoStore.PhotoMeta meta) {
        if (meta == null) return;
        modePill.setText(meta.modeLabel);
        modePill.setVisibility(View.VISIBLE);
        hdrPill.setVisibility(meta.ultraHdr ? View.VISIBLE : View.GONE);
        StringBuilder bar = new StringBuilder(meta.look);
        if (!meta.summary.isEmpty()) bar.append(" · ").append(meta.summary);
        exifView.setText(bar.toString());
        exifView.setVisibility(View.VISIBLE);
    }

    private void removeEntry(Uri uri) {
        PhotoStore.PhotoEntry selected = currentEntry();
        int removed = indexOfUri(uri);
        excluded.add(uri);
        if (removed >= 0) photos.remove(removed);
        fullCache.remove(uri);
        thumbCache.remove(uri);
        updateLibrarySelection(selected == null ? null : selected.uri, Math.max(0, removed), 0);
        if (removed == 0) reportNewest();
        if (photos.isEmpty() && hasMore) loadNextPage();
        closeIfEmpty();
    }

    private void handleUnreadableEntry(PhotoStore.PhotoEntry entry) {
        Toast.makeText(activity, "照片已被移除或无法打开", Toast.LENGTH_SHORT).show();
        removeEntry(entry.uri);
    }

    private int firstFilmstripIndex() {
        int maxStart = Math.max(0, photos.size() - MAX_FILMSTRIP_ITEMS);
        int step = MAX_FILMSTRIP_ITEMS / 2;
        return Math.min(maxStart, Math.max(0, ((currentIndex + 5) / step - 1) * step));
    }

    private void rebuildFilmstrip() {
        filmstripRow.removeAllViews();
        filmstripStart = firstFilmstripIndex();
        if (photos.size() <= 1) {
            filmstripScroll.setVisibility(View.GONE);
            return;
        }
        filmstripScroll.setVisibility(View.VISIBLE);
        int count = Math.min(photos.size() - filmstripStart, MAX_FILMSTRIP_ITEMS);
        int tileSize = ui.dp(44);
        int margin = ui.dp(3);
        int thumbPx = ui.dp(64);
        Context appCtx = activity.getApplicationContext();

        for (int i = 0; i < count; i++) {
            final int index = filmstripStart + i;
            final PhotoStore.PhotoEntry entry = photos.get(index);
            FrameLayout cell = new FrameLayout(activity);
            cell.setClipToOutline(true);
            cell.setForeground(ui.ripple(8));
            cell.setContentDescription("查看第 " + (index + 1) + " 张照片");
            cell.setOnClickListener(v -> {
                if (index != currentIndex) {
                    userNavigated = true;
                    showIndex(index, index > currentIndex ? 1 : -1);
                }
            });

            ImageView thumb = new ImageView(activity);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setTag(entry.uri);
            cell.addView(thumb, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileSize, tileSize);
            lp.setMargins(margin, 0, margin, 0);
            filmstripRow.addView(cell, lp);

            Bitmap cached = thumbCache.get(entry.uri);
            if (cached != null) {
                thumb.setImageBitmap(cached);
            } else {
                THUMB_LOADER.execute(() -> loadThumbInto(appCtx, entry.uri, thumbPx, thumb));
            }
        }
        updateFilmstripSelection();
    }

    private void updateFilmstripSelection() {
        int count = filmstripRow.getChildCount();
        for (int i = 0; i < count; i++) {
            View cell = filmstripRow.getChildAt(i);
            boolean active = filmstripStart + i == currentIndex;
            cell.setBackground(ui.rounded(CameraScreen.PANEL, 8, active ? CameraScreen.ACCENT : CameraScreen.LINE));
            int pad = active ? ui.dp(2) : ui.dp(1);
            cell.setPadding(pad, pad, pad, pad);
            cell.setAlpha(active ? 1f : 0.65f);
            if (active) {
                cell.post(() -> {
                    int targetX = cell.getLeft() - (filmstripScroll.getWidth() - cell.getWidth()) / 2;
                    filmstripScroll.smoothScrollTo(Math.max(0, targetX), 0);
                });
            }
        }
    }

    private void loadThumbInto(Context appCtx, Uri uri, int sizePx, ImageView target) {
        if (dismissed) return;
        try {
            Bitmap bmp = PhotoStore.readThumbnail(appCtx, uri, sizePx);
            main.post(() -> {
                if (dismissed) return;
                thumbCache.put(uri, bmp);
                if (uri.equals(target.getTag())) target.setImageBitmap(bmp);
            });
        } catch (IOException | RuntimeException ignored) {
            // Leave tile blank if thumbnail unreadable.
        }
    }

    private void confirmDelete(PhotoStore.PhotoEntry entry) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView msg = ui.label("这张照片将从手机相册永久移除。同一文件夹里的同名底片也会删除。",
                13, CameraScreen.MUTED);
        msg.setLineSpacing(ui.dp(2), 1f);
        msg.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(4));
        body.addView(msg);

        Dialog confirm = CameraPanels.sheet(ui, "删除这张照片？", body);
        CameraPanels.sheetActions(ui, body, confirm, "取消", "删除", v -> {
            confirm.dismiss();
            performDelete(entry.uri);
        });
        confirm.show();
        CameraPanels.fitSheet(confirm, ui);
    }

    private void performDelete(Uri uri) {
        Context appCtx = activity.getApplicationContext();
        LOADER.execute(() -> {
            PhotoStore.DeleteResult result = PhotoStore.deletePhoto(appCtx, uri);
            main.post(() -> {
                if (dismissed) {
                    if (result != PhotoStore.DeleteResult.FAILED) host.onPhotoDeleted(uri);
                    return;
                }
                if (result == PhotoStore.DeleteResult.FAILED) {
                    Toast.makeText(activity, "无法删除这张照片，请尝试在系统相册删除", Toast.LENGTH_SHORT).show();
                    return;
                }
                String message = result == PhotoStore.DeleteResult.DNG_REMAINS ? "照片已删除，底片仍保留" : "已删除";
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                removeEntry(uri);
            });
        });
    }

    private void showGridItemSheet(int position) {
        PhotoStore.PhotoEntry entry = photos.get(position);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        Dialog sheet = CameraPanels.sheet(ui, "照片操作", body);
        LinearLayout card = CameraPanels.card(ui);
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));

        addSheetRow(card, "查看大图", v -> {
            sheet.dismiss();
            int index = indexOfUri(entry.uri);
            if (index >= 0) {
                userNavigated = true;
                setGridVisible(false);
                showIndex(index, 0);
            }
        });
        CameraPanels.divider(ui, card);
        addSheetRow(card, "分享", v -> {
            sheet.dismiss();
            host.onShare(entry.uri);
        });
        CameraPanels.divider(ui, card);
        addSheetRow(card, "在相册中打开", v -> {
            sheet.dismiss();
            dialog.dismiss();
            host.onOpenInGallery(entry.uri);
        });
        CameraPanels.divider(ui, card);
        addSheetRow(card, "删除", v -> {
            sheet.dismiss();
            confirmDelete(entry);
        });

        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-1, -2);
        cancelLp.topMargin = ui.dp(12);
        body.addView(CameraPanels.button(ui, "取消", false, v -> sheet.dismiss()), cancelLp);

        sheet.show();
        CameraPanels.fitSheet(sheet, ui);
    }

    private void addSheetRow(LinearLayout card, String label, View.OnClickListener click) {
        TextView row = ui.label(label, 15, CameraScreen.INK);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(ui.dp(48));
        row.setPaddingRelative(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        row.setBackground(ui.ripple(12));
        row.setFocusable(true);
        row.setOnClickListener(click);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void cleanup() {
        dismissed = true;
        if (unregisterBack != null) {
            unregisterBack.run();
            unregisterBack = null;
        }
        if (photoView != null) photoView.setImageDrawable(null);
        filmstripRow.removeAllViews();
        fullCache.evictAll();
        thumbCache.evictAll();
        host.onDismiss();
    }

    private final class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return photos.size(); }
        @Override public Object getItem(int position) { return photos.get(position); }
        @Override public long getItemId(int position) { return photos.get(position).id; }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            SquareCell cell;
            ImageView image;
            if (convertView instanceof SquareCell) {
                cell = (SquareCell) convertView;
                image = (ImageView) cell.getChildAt(0);
            } else {
                cell = new SquareCell(activity);
                image = new ImageView(activity);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(image, new FrameLayout.LayoutParams(-1, -1));
            }
            PhotoStore.PhotoEntry entry = photos.get(position);
            cell.setTag(entry.uri);
            boolean active = position == currentIndex;
            cell.setBackground(ui.rounded(CameraScreen.PANEL, 4, active ? CameraScreen.ACCENT : 0));
            int pad = active ? ui.dp(2) : 0;
            cell.setPadding(pad, pad, pad, pad);
            image.setTag(entry.uri);
            Bitmap cached = thumbCache.get(entry.uri);
            if (cached != null) {
                image.setImageBitmap(cached);
            } else {
                image.setImageDrawable(null);
                THUMB_LOADER.execute(() -> loadThumbInto(activity.getApplicationContext(), entry.uri, ui.dp(180), image));
            }
            return cell;
        }
    }

    private static final class SquareCell extends FrameLayout {
        SquareCell(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, widthSpec);
        }
    }
}
