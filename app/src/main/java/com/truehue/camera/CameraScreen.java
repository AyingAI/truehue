package com.truehue.camera;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

/** Pure neutral obsidian optical UI with live telemetry, horizon, histogram, and tactile dials. */
final class CameraScreen extends LinearLayout {
    static final int BG = 0xff09090a, PANEL = 0xff141416, ELEVATED = 0xff1e1e22;
    static final int INK = 0xfff5f5f2, MUTED = 0xff8e8e93, ACCENT = 0xffe8a838, LINE = 0xff2a2a2e;
    private static final int FINDER = 0xff050506;
    private static final int SELECTED = 0xff27272c;
    private static final int ACCENT_PILL = 0x26e8a838;
    private static final PathInterpolator EASE_OUT = new PathInterpolator(.16f, 1f, .3f, 1f);

    static final int TRAY_LENS = 0, TRAY_FILTER = 1, TRAY_EV = 2, TRAY_WB = 3;

    final FrameLayout viewport, surfaceHost;
    final FinderOverlay overlay;
    final TextView prompt, action, status, dimensions, telemetry, evBadge;
    final TextView modeBadge, hdrToggle, dngToggle;
    final FrameLayout gallery;
    final Mark helpButton, mode, shutter, filterButton, gridButton;
    final LensRail lensBar, filterBar;
    final TextView evChip, wbChip, evReadout, wbReadout, filterDesc, filterCaption;
    final TextView evResetBtn, evCloseBtn, wbCloseBtn;
    final LinearLayout wbPresetRow;
    final DialWheel evWheel, wbWheel;
    final ImageView thumbnail, rawPreview;

    private final LinearLayout lensRow, filterTray, evTray, wbTray;
    private final Mark galleryMark;
    private final GalleryRing galleryRing;
    private final View curtain, exposureBlink;
    private boolean hasLenses, proInfoVisible, galleryHasPhoto, galleryProcessing;
    private int activeTray = TRAY_LENS;
    private float aspect = 0.75f;

    CameraScreen(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        useSystemInsets(this);

        // 1. Top status and controls. Use a second row when the labels need more space.
        LinearLayout quickChips = new LinearLayout(context);
        quickChips.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headerTools = new LinearLayout(context);
        LinearLayout header = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int unconstrained = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                quickChips.measure(unconstrained, unconstrained);
                headerTools.measure(unconstrained, unconstrained);
                int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                boolean stack = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
                        && quickChips.getMeasuredWidth() + headerTools.getMeasuredWidth() > available;
                setOrientation(stack ? VERTICAL : HORIZONTAL);
                LayoutParams chipsSize = (LayoutParams) quickChips.getLayoutParams();
                chipsSize.width = stack ? LayoutParams.MATCH_PARENT : 0;
                chipsSize.weight = stack ? 0 : 1;
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(2), dp(10), dp(2));
        addView(header, row(-2));
        header.addView(quickChips, new LayoutParams(0, -2, 1));
        LayoutParams toolsSize = new LayoutParams(-2, -2);
        toolsSize.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        header.addView(headerTools, toolsSize);

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.ic_camera);
        logo.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoLp = square(20);
        logoLp.setMarginEnd(dp(8));
        quickChips.addView(logo, logoLp);

        modeBadge = pill("原彩", 11, INK, ELEVATED, LINE);
        modeBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        quickChips.addView(modeBadge, pillLp(5));

        hdrToggle = pill("亮彩", 11, MUTED, PANEL, LINE);
        hdrToggle.setContentDescription("高光亮彩开关，原 ProXDR");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) hdrToggle.setVisibility(GONE);
        quickChips.addView(hdrToggle, pillLp(5));

        dngToggle = pill("存底片", 11, MUTED, PANEL, LINE);
        dngToggle.setContentDescription("同时保存专业底片（DNG）开关");
        quickChips.addView(dngToggle, pillLp(5));

        helpButton = headerButton(headerTools, Mark.HELP, 5, "新手指南与名词说明", "使用指南");
        gridButton = headerButton(headerTools, Mark.GRID, 5, "构图辅助线与水平仪", "辅助线与水平仪");
        mode = headerButton(headerTools, Mark.SETTINGS, 4, "拍摄设置", "拍摄设置");

        // 2. Full-Width 4:3 Edge-to-Edge Viewfinder Stage
        FrameLayout stage = new FrameLayout(context) {
            @Override protected void onMeasure(int w, int h) {
                int width = Math.max(0, View.MeasureSpec.getSize(w));
                int height = Math.max(0, View.MeasureSpec.getSize(h) - dp(4));
                int fittedWidth = Math.min(width, Math.round(height * aspect));
                FrameLayout.LayoutParams box = (FrameLayout.LayoutParams) viewport.getLayoutParams();
                box.width = fittedWidth;
                box.height = Math.min(height, Math.round(fittedWidth / aspect));
                super.onMeasure(w, h);
            }
        };
        addView(stage, new LayoutParams(-1, 0, 1));
        viewport = new FrameLayout(context);
        viewport.setBackgroundColor(FINDER);
        stage.addView(viewport, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        surfaceHost = new FrameLayout(context);
        viewport.addView(surfaceHost, new FrameLayout.LayoutParams(-1, -1));
        rawPreview = new ImageView(context);
        rawPreview.setBackgroundColor(FINDER);
        rawPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        rawPreview.setVisibility(GONE);
        viewport.addView(rawPreview, new FrameLayout.LayoutParams(-1, -1));
        overlay = new FinderOverlay(context);
        viewport.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        curtain = new View(context);
        curtain.setBackgroundColor(FINDER);
        viewport.addView(curtain, new FrameLayout.LayoutParams(-1, -1));
        exposureBlink = new View(context);
        exposureBlink.setBackgroundColor(FINDER);
        exposureBlink.setAlpha(0f);
        viewport.addView(exposureBlink, new FrameLayout.LayoutParams(-1, -1));
        viewport.addView(new FinderFrame(context), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout message = column();
        viewport.addView(message, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        prompt = label("", 15, INK);
        prompt.setGravity(Gravity.CENTER);
        prompt.setPadding(dp(20), dp(10), dp(20), dp(10));
        message.addView(prompt);
        action = label("", 14, BG);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(20), 0, dp(20), 0);
        action.setMinHeight(dp(48));
        action.setBackground(rounded(ACCENT, 24, 0));
        action.setForeground(ripple(24));
        message.addView(action, new LayoutParams(-2, -2));
        action.setVisibility(GONE);

        // 3. Optical Telemetry & Status Strip (28dp)
        LinearLayout meta = new LinearLayout(context);
        meta.setPadding(dp(16), dp(4), dp(16), dp(2));
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setMinimumHeight(dp(28));
        addView(meta, row(-2));

        telemetry = label("", 11, MUTED);
        telemetry.setFontFeatureSettings("tnum");
        telemetry.setMaxLines(1);
        meta.addView(telemetry, new LayoutParams(-2, -2));

        evBadge = pill("", 10, ACCENT, ACCENT_PILL, ACCENT);
        evBadge.setVisibility(GONE);
        LayoutParams evBadgeLp = new LayoutParams(-2, -2);
        evBadgeLp.setMarginEnd(dp(8));
        meta.addView(evBadge, evBadgeLp);

        status = label("启动中", 11, MUTED);
        status.setGravity(Gravity.START);
        status.setMaxLines(1);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        meta.addView(status, stretch());

        dimensions = label("", 11, MUTED);
        dimensions.setFontFeatureSettings("tnum");
        dimensions.setPaddingRelative(dp(8), 0, 0, 0);
        meta.addView(dimensions, new LayoutParams(-2, -2));

        // 4. Contextual Expandable Control Tray
        FrameLayout trayHost = new FrameLayout(context);
        LayoutParams trayLp = row(-2);
        trayLp.setMargins(dp(12), dp(4), dp(12), dp(6));
        addView(trayHost, trayLp);

        // 4a. Default Lens + EV/WB Pro Chips Row
        lensRow = new LinearLayout(context);
        lensRow.setOrientation(HORIZONTAL);
        lensRow.setGravity(Gravity.CENTER_VERTICAL);
        trayHost.addView(lensRow, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        evChip = chipButton("亮度");
        evChip.setContentDescription("调节画面亮度");
        lensRow.addView(evChip, new LayoutParams(dp(56), dp(38)));

        FrameLayout lensCenter = new FrameLayout(context);
        lensRow.addView(lensCenter, new LayoutParams(0, -2, 1));
        lensBar = new LensRail(context);
        lensBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        lensBar.setBackground(rounded(PANEL, 22, LINE));
        lensBar.setVisibility(GONE);
        lensCenter.addView(lensBar, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        wbChip = chipButton("色调");
        wbChip.setContentDescription("调节画面冷暖色调");
        lensRow.addView(wbChip, new LayoutParams(dp(56), dp(38)));

        // 4b. Look / Filter Drawer
        filterTray = new LinearLayout(context);
        filterTray.setOrientation(VERTICAL);
        filterTray.setGravity(Gravity.CENTER_HORIZONTAL);
        filterTray.setVisibility(GONE);
        trayHost.addView(filterTray, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        filterDesc = label("", 11, MUTED);
        filterDesc.setGravity(Gravity.CENTER);
        filterDesc.setPadding(dp(8), 0, dp(8), dp(6));
        filterTray.addView(filterDesc, row(-2));

        filterBar = new LensRail(context);
        filterBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        filterBar.setBackground(rounded(PANEL, 22, LINE));
        filterTray.addView(filterBar, new LayoutParams(-2, -2));

        // 4c. EV Dial Drawer
        evTray = new LinearLayout(context);
        evTray.setOrientation(VERTICAL);
        evTray.setBackground(rounded(PANEL, 18, LINE));
        evTray.setPadding(dp(12), dp(8), dp(12), dp(8));
        evTray.setVisibility(GONE);
        trayHost.addView(evTray, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout evHead = new LinearLayout(context);
        evHead.setGravity(Gravity.CENTER_VERTICAL);
        evTray.addView(evHead, row(-2));
        evHead.addView(label("画面亮度", 12, MUTED), new LayoutParams(-2, -2));
        evReadout = label("标准", 12, ACCENT);
        evReadout.setFontFeatureSettings("tnum");
        evReadout.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        evReadout.setPaddingRelative(dp(8), 0, 0, 0);
        evHead.addView(evReadout, stretch());
        evResetBtn = pill("归零", 11, INK, ELEVATED, LINE);
        evHead.addView(evResetBtn, pillLp(6));
        evCloseBtn = pill("收起", 11, BG, ACCENT, 0);
        evHead.addView(evCloseBtn, new LayoutParams(-2, -2));

        evWheel = new DialWheel(context, -2.0f, 2.0f, 0.1f, 0f, false);
        LayoutParams wheelLp = new LayoutParams(-1, dp(40));
        wheelLp.topMargin = dp(4);
        evTray.addView(evWheel, wheelLp);

        LinearLayout evHints = new LinearLayout(context);
        evHints.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams evHintsLp = row(-2);
        evHintsLp.topMargin = dp(2);
        evTray.addView(evHints, evHintsLp);
        evHints.addView(label("← 更暗", 10, MUTED), new LayoutParams(-2, -2));
        TextView evHintMid = label("左右滑动调明暗，也可在画面上下滑", 10, MUTED);
        evHintMid.setGravity(Gravity.CENTER);
        evHints.addView(evHintMid, stretch());
        evHints.addView(label("更亮 →", 10, MUTED), new LayoutParams(-2, -2));

        // 4d. WB Dial Drawer
        wbTray = new LinearLayout(context);
        wbTray.setOrientation(VERTICAL);
        wbTray.setBackground(rounded(PANEL, 18, LINE));
        wbTray.setPadding(dp(12), dp(8), dp(12), dp(8));
        wbTray.setVisibility(GONE);
        trayHost.addView(wbTray, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout wbHead = new LinearLayout(context);
        wbHead.setGravity(Gravity.CENTER_VERTICAL);
        wbTray.addView(wbHead, row(-2));
        wbReadout = label("自动色调", 12, ACCENT);
        wbReadout.setFontFeatureSettings("tnum");
        wbReadout.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wbHead.addView(wbReadout, new LayoutParams(-2, -2));

        wbPresetRow = new LinearLayout(context);
        wbPresetRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        wbHead.addView(wbPresetRow, stretch());
        wbCloseBtn = pill("收起", 11, BG, ACCENT, 0);
        wbHead.addView(wbCloseBtn, new LayoutParams(-2, -2));

        wbWheel = new DialWheel(context, 2800f, 8000f, 100f, 5500f, true);
        LayoutParams wbWheelLp = new LayoutParams(-1, dp(40));
        wbWheelLp.topMargin = dp(4);
        wbTray.addView(wbWheel, wbWheelLp);

        LinearLayout wbHints = new LinearLayout(context);
        wbHints.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams wbHintsLp = row(-2);
        wbHintsLp.topMargin = dp(2);
        wbTray.addView(wbHints, wbHintsLp);
        wbHints.addView(label("← 偏冷去黄", 10, MUTED), new LayoutParams(-2, -2));
        TextView wbHintMid = label("滑动刻度调整冷暖", 10, MUTED);
        wbHintMid.setGravity(Gravity.CENTER);
        wbHints.addView(wbHintMid, stretch());
        wbHints.addView(label("偏暖柔和 →", 10, MUTED), new LayoutParams(-2, -2));

        // 5. Primary Shutter Deck
        FrameLayout controls = new FrameLayout(context);
        controls.setPadding(dp(28), dp(4), dp(28), dp(12));
        addView(controls, row(-2));

        LinearLayout galleryWrap = column();
        controls.addView(galleryWrap, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        gallery = new FrameLayout(context);
        gallery.setBackground(rounded(PANEL, 13, LINE));
        gallery.setForeground(ripple(13));
        gallery.setClipToOutline(true);
        galleryWrap.addView(gallery, square(46));
        galleryMark = new Mark(context, Mark.GALLERY);
        gallery.addView(galleryMark, new FrameLayout.LayoutParams(-1, -1));
        thumbnail = new ImageView(context);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setVisibility(GONE);
        gallery.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
        galleryRing = new GalleryRing(context);
        galleryRing.setVisibility(GONE);
        gallery.addView(galleryRing, new FrameLayout.LayoutParams(-1, -1));
        gallery.setContentDescription("查看本色相册");
        gallery.setTooltipText("本色相册");
        gallery.setFocusable(true);
        TextView galleryCaption = label("相册", 10, MUTED);
        galleryCaption.setOnClickListener(v -> gallery.performClick());
        LayoutParams capLp = new LayoutParams(-2, -2);
        capLp.topMargin = dp(3);
        galleryWrap.addView(galleryCaption, capLp);

        shutter = new Mark(context, Mark.SHUTTER);
        controls.addView(shutter, squareAt(76, Gravity.CENTER));
        shutter.setFocusable(true);
        shutter.setForeground(ripple(40));
        shutter.setContentDescription("拍照");

        LinearLayout filterWrap = column();
        controls.addView(filterWrap, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        filterButton = new Mark(context, Mark.FILTER);
        StateListDrawable filterBackground = new StateListDrawable();
        filterBackground.addState(new int[]{android.R.attr.state_selected}, rounded(ACCENT_PILL, 13, ACCENT));
        filterBackground.addState(new int[0], rounded(PANEL, 13, LINE));
        filterButton.setBackground(filterBackground);
        filterButton.setForeground(ripple(13));
        filterWrap.addView(filterButton, square(46));
        filterButton.setFocusable(true);
        filterCaption = label("风格", 10, MUTED);
        filterCaption.setOnClickListener(v -> filterButton.performClick());
        LayoutParams fCapLp = new LayoutParams(-2, -2);
        fCapLp.topMargin = dp(3);
        filterWrap.addView(filterCaption, fCapLp);

        setProInfoVisible(false);
    }

    private Mark headerButton(LinearLayout tools, int kind, int insetDp, String description, String tooltip) {
        Mark button = new Mark(getContext(), kind);
        int inset = dp(insetDp);
        button.setPadding(inset, inset, inset, inset);
        button.setContentDescription(description);
        button.setTooltipText(tooltip);
        button.setFocusable(true);
        button.setForeground(ripple(16));
        tools.addView(button, square(48));
        return button;
    }

    private TextView pill(String text, int sp, int color, int bg, int stroke) {
        TextView view = label(text, sp, color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
        view.setMinHeight(dp(26));
        view.setBackground(rounded(bg, 13, stroke));
        view.setForeground(ripple(13));
        view.setFocusable(true);
        return view;
    }

    private LayoutParams pillLp(int marginEndDp) {
        LayoutParams lp = new LayoutParams(-2, -2);
        lp.setMarginEnd(dp(marginEndDp));
        return lp;
    }

    private TextView chipButton(String text) {
        TextView view = label(text, 12, INK);
        view.setFontFeatureSettings("tnum");
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(PANEL, 19, LINE));
        view.setForeground(ripple(19));
        view.setFocusable(true);
        return view;
    }

    void setShowGrid(boolean show) {
        overlay.setShowGrid(show);
        gridButton.setSelected(show);
        gridButton.invalidate();
    }

    void updateQuickToggle(TextView pill, boolean available, boolean checked, boolean controlsEnabled) {
        boolean active = available && checked;
        boolean enabled = available && controlsEnabled;
        if (pill.isSelected() != active) {
            pill.setTextColor(active ? ACCENT : MUTED);
            pill.setBackground(rounded(active ? ACCENT_PILL : PANEL, 13, active ? ACCENT : LINE));
        }
        pill.setSelected(active);
        pill.setEnabled(enabled);
        pill.setAlpha(enabled ? 1f : .55f);
        pill.setStateDescription(!available ? "当前拍摄模式下不可用" : active ? "已开启" : "已关闭");
    }

    void setProInfoVisible(boolean visible) {
        proInfoVisible = visible;
        telemetry.setVisibility(visible ? VISIBLE : GONE);
        dimensions.setVisibility(visible && activeTray == TRAY_LENS ? VISIBLE : GONE);
        LayoutParams lp = (LayoutParams) evBadge.getLayoutParams();
        lp.setMarginStart(visible ? dp(6) : 0);
        lp.setMarginEnd(visible ? 0 : dp(8));
        evBadge.setLayoutParams(lp);
        status.setGravity(visible ? Gravity.END : Gravity.START);
        status.setPaddingRelative(visible ? dp(8) : 0, 0, 0, 0);
    }

    void updateProChips(float userEv, float manualKelvin) {
        boolean evActive = Math.abs(userEv) >= 0.05f || activeTray == TRAY_EV;
        evChip.setText(Math.abs(userEv) < 0.05f ? "亮度" : String.format(Locale.ROOT, "%+.1f", userEv));
        evChip.setTextColor(evActive ? ACCENT : INK);
        evChip.setBackground(rounded(evActive ? ACCENT_PILL : PANEL, 19, evActive ? ACCENT : LINE));

        boolean wbActive = manualKelvin >= 2000f || activeTray == TRAY_WB;
        String wbText = "色调";
        if (manualKelvin >= 2000f) {
            wbText = manualKelvin < 4800f ? "偏冷" : (manualKelvin <= 5900f ? "自然" : "偏暖");
        }
        wbChip.setText(wbText);
        wbChip.setTextColor(wbActive ? ACCENT : INK);
        wbChip.setBackground(rounded(wbActive ? ACCENT_PILL : PANEL, 19, wbActive ? ACCENT : LINE));
    }

    void updateTelemetry(CameraInput.Telemetry t, boolean highlightProtect, float userEv) {
        if (t == null) {
            telemetry.setText("");
        } else {
            StringBuilder sb = new StringBuilder();
            if (t.aperture > 0) sb.append(String.format(Locale.ROOT, "f/%.2f", t.aperture));
            if (t.exposureNs > 0) {
                if (sb.length() > 0) sb.append(" · ");
                double sec = t.exposureNs * 1e-9;
                if (sec >= 0.5) sb.append(String.format(Locale.ROOT, "%.1fs", sec));
                else sb.append("1/").append(Math.max(1, Math.round(1.0 / sec))).append("s");
            }
            if (t.iso > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("ISO ").append(t.iso);
            }
            if (t.kelvin > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(t.kelvin).append("K");
            }
            telemetry.setText(sb.toString());
        }

        if (Math.abs(userEv) >= 0.05f) {
            evBadge.setText(String.format(Locale.ROOT, "亮度 %+.1f", userEv));
            evBadge.setVisibility(VISIBLE);
        } else if (highlightProtect && t != null) {
            evBadge.setText("自动防过曝");
            evBadge.setVisibility(VISIBLE);
        } else {
            evBadge.setVisibility(GONE);
        }
    }

    void setFilterOptions(PhotoFilter selected, Consumer<PhotoFilter> onSelect) {
        filterBar.removeAllViews();
        for (PhotoFilter option : PhotoFilter.values()) {
            TextView choice = label(option.label, 13, INK);
            choice.setGravity(Gravity.CENTER);
            choice.setMinHeight(dp(40));
            choice.setPadding(dp(4), dp(4), dp(4), dp(4));
            choice.setFocusable(true);
            choice.setContentDescription(option.label + "，" + option.description);
            choice.setBackground(ripple(18));
            filterBar.addView(choice, stretch());
            choice.setOnClickListener(v -> onSelect.accept(option));
        }
        updateFilter(selected);
    }

    void updateFilter(PhotoFilter selected) {
        String name = getResources().getString(R.string.current_filter, selected.label);
        filterButton.setContentDescription(name);
        filterButton.setTooltipText(name);
        filterButton.setActivated(selected != PhotoFilter.ORIGINAL);
        filterButton.invalidate();
        filterCaption.setText(selected == PhotoFilter.ORIGINAL ? "风格" : selected.label);
        filterCaption.setTextColor(selected == PhotoFilter.ORIGINAL ? MUTED : ACCENT);
        filterDesc.setText(selected.description);
        for (int i = 0; i < filterBar.getChildCount(); i++) {
            TextView choice = (TextView) filterBar.getChildAt(i);
            boolean active = i == selected.ordinal();
            choice.setSelected(active);
            choice.setTextColor(active ? ACCENT : INK);
            if (active) filterBar.select(i);
        }
    }

    void showTray(int tray) {
        activeTray = tray;
        lensRow.setVisibility(tray == TRAY_LENS ? VISIBLE : GONE);
        lensBar.setVisibility(tray == TRAY_LENS && hasLenses ? VISIBLE : GONE);
        filterTray.setVisibility(tray == TRAY_FILTER ? VISIBLE : GONE);
        evTray.setVisibility(tray == TRAY_EV ? VISIBLE : GONE);
        wbTray.setVisibility(tray == TRAY_WB ? VISIBLE : GONE);
        dimensions.setVisibility(proInfoVisible && tray == TRAY_LENS ? VISIBLE : GONE);
        filterButton.setSelected(tray == TRAY_FILTER);
        filterButton.invalidate();
    }

    void showFilters(boolean open) {
        showTray(open ? TRAY_FILTER : TRAY_LENS);
    }

    int activeTray() {
        return activeTray;
    }

    static void useSystemInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    void setMode(String badge, String name, String format, int width, int height) {
        modeBadge.setText(badge);
        mode.setContentDescription("拍摄设置，当前" + name);
        dimensions.setText(width > 0 && height > 0 ? format + " · " + width + "×" + height : "");
    }

    void setAspect(int width, int height) {
        aspect = (float) width / height;
        ((View) viewport.getParent()).requestLayout();
    }

    void coverFinder() {
        curtain.animate().cancel();
        curtain.setAlpha(1f);
        exposureBlink.animate().cancel();
        exposureBlink.setAlpha(0f);
    }

    void revealFinder() {
        curtain.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            curtain.setAlpha(0f);
        } else {
            curtain.animate().alpha(0f).setDuration(170).setInterpolator(EASE_OUT).start();
        }
    }

    void shutterFeedback() {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        shutter.animate().cancel();
        shutter.setScaleX(.9f);
        shutter.setScaleY(.9f);
        shutter.animate().scaleX(1).scaleY(1).setDuration(180).setInterpolator(EASE_OUT).start();
        exposureBlink.animate().cancel();
        exposureBlink.setAlpha(.26f);
        exposureBlink.animate().alpha(0).setDuration(170).setInterpolator(EASE_OUT).start();
    }

    interface LensSelection { void select(CameraInput.Lens lens); }

    void setLenses(java.util.List<CameraInput.Lens> lenses, String selected, LensSelection selection) {
        hasLenses = lenses.size() > 1;
        lensBar.setVisibility(hasLenses && activeTray == TRAY_LENS ? VISIBLE : GONE);
        boolean reuse = lensBar.getChildCount() == lenses.size();
        for (int i = 0; reuse && i < lenses.size(); i++) {
            reuse = lenses.get(i).id.equals(lensBar.getChildAt(i).getTag());
        }
        if (!reuse) lensBar.removeAllViews();
        for (int i = 0; i < lenses.size(); i++) {
            CameraInput.Lens lens = lenses.get(i);
            TextView button;
            if (reuse) {
                button = (TextView) lensBar.getChildAt(i);
            } else {
                button = label("", 12, INK);
                button.setFontFeatureSettings("tnum");
                button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                button.setGravity(Gravity.CENTER);
                button.setMinHeight(dp(42));
                button.setLineSpacing(dp(1), 1f);
                button.setPadding(dp(2), dp(4), dp(2), dp(4));
                button.setFocusable(true);
                button.setTag(lens.id);
                button.setBackground(ripple(18));
                lensBar.addView(button, stretch());
            }
            String focal = lens.equivalentMm > 0 ? lens.equivalentMm + " mm" : "";
            button.setContentDescription(lens.label + " " + (focal.isEmpty() ? "" : "约 " + focal));
            int index = i;
            button.setOnClickListener(v -> {
                lensBar.select(index);
                selection.select(lens);
            });
            boolean active = lens.id.equals(selected);
            SpannableStringBuilder lensText = new SpannableStringBuilder(lens.label);
            if (lens.equivalentMm > 0) {
                int subStart = lensText.length();
                lensText.append('\n').append(Integer.toString(lens.equivalentMm)).append("mm");
                lensText.setSpan(new AbsoluteSizeSpan(9, true), subStart, lensText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                lensText.setSpan(new ForegroundColorSpan(active ? ACCENT : MUTED), subStart, lensText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            button.setText(lensText);
            button.setSelected(active);
            button.setTextColor(active ? ACCENT : INK);
            if (active) lensBar.select(i);
        }
    }

    void showMessage(String message, String button, OnClickListener handler) {
        prompt.setText(message);
        prompt.setVisibility(message.isEmpty() ? GONE : VISIBLE);
        action.setText(button);
        action.setVisibility(button.isEmpty() ? GONE : VISIBLE);
        action.setOnClickListener(handler);
    }

    void controls(boolean ready, boolean busy) {
        shutter.setEnabled(ready && !busy);
        shutter.setCaptureActive(busy);
        shutter.setAlpha(ready ? 1f : .36f);
        filterButton.setEnabled(ready && !busy);
        filterButton.setAlpha(ready && !busy ? 1f : .45f);
        helpButton.setEnabled(!busy);
        helpButton.setAlpha(!busy ? 1f : .55f);
        mode.setEnabled(ready && !busy);
        mode.setAlpha(ready && !busy ? 1f : .55f);
        modeBadge.setEnabled(ready && !busy);
        evChip.setEnabled(ready && !busy);
        wbChip.setEnabled(ready && !busy);
        for (LensRail bar : new LensRail[]{lensBar, filterBar}) {
            bar.setAlpha(busy ? .55f : 1f);
            for (int i = 0; i < bar.getChildCount(); i++) bar.getChildAt(i).setEnabled(!busy);
        }
    }

    void showThumbnail(Bitmap image, boolean animate) {
        galleryHasPhoto = image != null;
        thumbnail.animate().cancel();
        thumbnail.setImageBitmap(image);
        thumbnail.setVisibility(galleryHasPhoto ? VISIBLE : GONE);
        galleryMark.setVisibility(galleryHasPhoto ? GONE : VISIBLE);
        updateGalleryInteractiveState();
        thumbnail.setAlpha(1f);
        thumbnail.setScaleX(1f);
        thumbnail.setScaleY(1f);
        if (galleryHasPhoto && animate && ValueAnimator.areAnimatorsEnabled()) {
            thumbnail.setAlpha(.54f);
            thumbnail.setScaleX(.90f);
            thumbnail.setScaleY(.90f);
            thumbnail.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(210).setInterpolator(EASE_OUT).start();
        }
    }

    void setGalleryProcessing(boolean processing) {
        if (galleryProcessing == processing) return;
        galleryProcessing = processing;
        galleryRing.setActive(processing);
        updateGalleryInteractiveState();
    }

    void setGalleryHasPhoto(boolean hasPhoto) {
        galleryHasPhoto = hasPhoto;
        updateGalleryInteractiveState();
    }

    private void updateGalleryInteractiveState() {
        boolean interactive = galleryHasPhoto || galleryProcessing;
        gallery.setEnabled(interactive);
        gallery.setAlpha(interactive ? 1f : 0.5f);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(VERTICAL);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    TextView label(String text, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(Math.max(1, dp(1)), stroke);
        return drawable;
    }

    RippleDrawable ripple(int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x36e8a838), null, rounded(-1, radius, 0));
    }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LayoutParams row(int height) { return new LayoutParams(-1, height < 0 ? height : dp(height)); }
    private LayoutParams square(int size) { return new LayoutParams(dp(size), dp(size)); }
    private LayoutParams stretch() { return new LayoutParams(0, -2, 1); }
    private FrameLayout.LayoutParams squareAt(int size, int gravity) {
        return new FrameLayout.LayoutParams(dp(size), dp(size), gravity);
    }

    final class LensRail extends LinearLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF selection = new RectF();
        private ValueAnimator animation;
        private float position = -1;
        private int selected = -1;

        LensRail(Context context) { super(context); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int perItem = getChildCount() > 3 ? 62 : 70;
            int width = Math.min(MeasureSpec.getSize(widthSpec), dp(8 + perItem * getChildCount()));
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
        }

        void select(int index) {
            if (selected == index) return;
            if (animation != null) animation.cancel();
            selected = index;
            if (position < 0 || !isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
                position = index;
                invalidate();
                return;
            }
            animation = ValueAnimator.ofFloat(position, index);
            animation.setDuration(205);
            animation.setInterpolator(EASE_OUT);
            animation.addUpdateListener(a -> {
                position = (float) a.getAnimatedValue();
                invalidate();
            });
            animation.start();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            if (position >= 0 && getChildCount() > 0) {
                int from = Math.min((int) position, getChildCount() - 1);
                View a = getChildAt(from), b = getChildAt(Math.min(from + 1, getChildCount() - 1));
                float t = Math.min(1, position - from);
                float left = a.getLeft() + (b.getLeft() - a.getLeft()) * t;
                float right = a.getRight() + (b.getRight() - a.getRight()) * t;
                selection.set(left + dp(1), getPaddingTop() + dp(1),
                        right - dp(1), getHeight() - getPaddingBottom() - dp(1));
                paint.setColor(SELECTED);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(selection, dp(18), dp(18), paint);
            }
            super.dispatchDraw(canvas);
        }
    }

    /** Precision tactile horizontal ruler dial for EV and Kelvin control. */
    final class DialWheel extends View {
        private final float minVal, maxVal, step;
        private final boolean wbGradient;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value, downX, startVal;
        private int lastTickIndex = Integer.MIN_VALUE;
        private Consumer<Float> listener;

        DialWheel(Context context, float minVal, float maxVal, float step, float initial, boolean wbGradient) {
            super(context);
            this.minVal = minVal;
            this.maxVal = maxVal;
            this.step = step;
            this.value = initial;
            this.wbGradient = wbGradient;
        }

        void setOnValueChangeListener(Consumer<Float> listener) {
            this.listener = listener;
        }

        void setValue(float v) {
            this.value = Math.max(minVal, Math.min(maxVal, v));
            this.lastTickIndex = Math.round((this.value - minVal) / step);
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    startVal = value;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float span = Math.max(1f, getWidth() - dp(32));
                    float deltaRatio = (e.getX() - downX) / (span * 0.8f);
                    float raw = Math.max(minVal, Math.min(maxVal, startVal - deltaRatio * (maxVal - minVal)));
                    int tick = Math.round((raw - minVal) / step);
                    float snapped = Math.max(minVal, Math.min(maxVal, minVal + tick * step));
                    if (tick != lastTickIndex) {
                        lastTickIndex = tick;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        value = snapped;
                        invalidate();
                        if (listener != null) listener.accept(snapped);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w * 0.5f;
            float tickSpacing = dp(10);
            int totalSteps = Math.round((maxVal - minVal) / step);
            float currentStep = (value - minVal) / step;

            if (wbGradient) {
                paint.setShader(new LinearGradient(dp(16), 0, w - dp(16), 0,
                        new int[]{0x558ab4f8, 0x33ffffff, 0x55ffb74d}, null, Shader.TileMode.CLAMP));
                paint.setStrokeWidth(dp(2));
                canvas.drawLine(dp(16), h - dp(4), w - dp(16), h - dp(4), paint);
                paint.setShader(null);
            }

            for (int i = 0; i <= totalSteps; i++) {
                float x = cx + (i - currentStep) * tickSpacing;
                if (x < dp(8) || x > w - dp(8)) continue;
                float dist = Math.abs(x - cx) / (w * 0.5f);
                int alpha = Math.round(220 * Math.max(0.1f, 1f - dist * dist));
                boolean major = (i % 5 == 0);
                paint.setColor((alpha << 24) | (major ? 0xf5f5f2 : 0x8e8e93));
                paint.setStrokeWidth(major ? dp(1.5f) : dp(1));
                float top = major ? dp(10) : dp(16);
                float bot = h - dp(10);
                canvas.drawLine(x, top, x, bot, paint);
            }

            // Center stationary precision amber needle.
            paint.setColor(ACCENT);
            paint.setStrokeWidth(dp(2.2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(cx, dp(4), cx, h - dp(5), paint);
        }
    }

    /** Optical viewfinder overlay: rule-of-thirds, spirit level, mini histogram, focus reticle. */
    final class FinderOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path histPath = new Path();
        private final RectF histBox = new RectF();
        private final RectF lockPill = new RectF();
        private boolean showGrid = true, showHistogram = true;
        private final float[] histBins = new float[HighlightMeter.BINS];
        private float clipFraction;
        private float rollDeg, pitchDeg;
        private float focusX = -1, focusY = -1, focusScale = 1f;
        private boolean focusVisible, focusLocked, draggingEv;
        private float evDisplay;
        private ValueAnimator reticleAnim;
        private final Runnable fadeReticle = () -> {
            if (!focusLocked && !draggingEv) {
                focusVisible = false;
                invalidate();
            }
        };

        FinderOverlay(Context context) { super(context); }

        void setShowGrid(boolean show) { this.showGrid = show; invalidate(); }
        void setShowHistogram(boolean show) { this.showHistogram = show; invalidate(); }

        void setHistogram(float[] bins, float clip) {
            if (bins != null && bins.length == histBins.length) {
                System.arraycopy(bins, 0, histBins, 0, histBins.length);
            }
            this.clipFraction = clip;
            if (showHistogram) invalidate();
        }

        void setHorizon(float roll, float pitch) {
            this.rollDeg = roll;
            this.pitchDeg = pitch;
            if (showGrid) invalidate();
        }

        void showFocus(float nx, float ny, boolean locked) {
            removeCallbacks(fadeReticle);
            focusX = nx;
            focusY = ny;
            focusLocked = locked;
            focusVisible = true;
            if (reticleAnim != null) reticleAnim.cancel();
            if (ValueAnimator.areAnimatorsEnabled()) {
                reticleAnim = ValueAnimator.ofFloat(1.24f, 1f);
                reticleAnim.setDuration(190);
                reticleAnim.setInterpolator(EASE_OUT);
                reticleAnim.addUpdateListener(a -> {
                    focusScale = (float) a.getAnimatedValue();
                    invalidate();
                });
                reticleAnim.start();
            } else {
                focusScale = 1f;
                invalidate();
            }
            if (!locked) postDelayed(fadeReticle, 3600);
        }

        void setDraggingEv(boolean dragging, float ev) {
            this.draggingEv = dragging;
            this.evDisplay = ev;
            if (dragging) removeCallbacks(fadeReticle);
            else if (!focusLocked && focusVisible) postDelayed(fadeReticle, 2200);
            invalidate();
        }

        void clearReticle() {
            removeCallbacks(fadeReticle);
            focusVisible = false;
            focusLocked = false;
            draggingEv = false;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (showGrid) {
                // Rule of thirds hairline
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dp(0.75f)));
                paint.setColor(0x30ffffff);
                canvas.drawLine(w / 3f, 0, w / 3f, h, paint);
                canvas.drawLine(2f * w / 3f, 0, 2f * w / 3f, h, paint);
                canvas.drawLine(0, h / 3f, w, h / 3f, paint);
                canvas.drawLine(0, 2f * h / 3f, w, 2f * h / 3f, paint);

                // Horizon spirit level (fades gracefully when camera points straight down/up)
                float pitchFade = 1f - Math.max(0f, Math.min(1f, (Math.abs(pitchDeg) - 54f) / 14f));
                if (pitchFade > 0.02f) {
                    float cx = w * 0.5f, cy = h * 0.5f;
                    float nearest90 = Math.round(rollDeg / 90f) * 90f;
                    float err = rollDeg - nearest90;
                    boolean level = Math.abs(err) < 1.3f;
                    int a = Math.round(pitchFade * (level ? 240 : 175));
                    paint.setColor((a << 24) | (level ? 0xe8a838 : 0xffffff));
                    paint.setStrokeWidth(dp(1.25f));
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    // Outer fixed reference ticks
                    canvas.drawLine(cx - dp(38), cy, cx - dp(26), cy, paint);
                    canvas.drawLine(cx + dp(26), cy, cx + dp(38), cy, paint);
                    // Rotating center level bar
                    canvas.save();
                    canvas.rotate(-err, cx, cy);
                    canvas.drawLine(cx - dp(21), cy, cx - dp(6), cy, paint);
                    canvas.drawLine(cx + dp(6), cy, cx + dp(21), cy, paint);
                    canvas.restore();
                }
            }

            if (showHistogram) {
                float hw = dp(74), hh = dp(26);
                float right = w - dp(10), top = dp(10);
                histBox.set(right - hw, top, right, top + hh);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x9a09090a);
                canvas.drawRoundRect(histBox, dp(6), dp(6), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dp(0.75f)));
                paint.setColor(0x36ffffff);
                canvas.drawRoundRect(histBox, dp(6), dp(6), paint);

                float padX = dp(4), padY = dp(4);
                float plotW = hw - 2 * padX, plotH = hh - 2 * padY;
                float baseY = histBox.bottom - padY;
                histPath.reset();
                histPath.moveTo(histBox.left + padX, baseY);
                for (int i = 0; i < histBins.length; i++) {
                    float px = histBox.left + padX + (i / (float) (histBins.length - 1)) * plotW;
                    float py = baseY - histBins[i] * plotH;
                    histPath.lineTo(px, py);
                }
                histPath.lineTo(histBox.left + padX + plotW, baseY);
                histPath.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xc8eeeeeb);
                canvas.drawPath(histPath, paint);

                if (clipFraction > 0.015f) {
                    paint.setColor(ACCENT);
                    canvas.drawCircle(histBox.right - dp(4), histBox.top + dp(4), dp(2.2f), paint);
                }
            }

            if (focusLocked) {
                paint.setTextSize(dp(11));
                paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                String text = "已锁定对焦与亮度";
                float tw = paint.measureText(text);
                float pw = tw + dp(20), ph = dp(24);
                lockPill.set(w * 0.5f - pw * 0.5f, dp(10), w * 0.5f + pw * 0.5f, dp(10) + ph);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xb809090a);
                canvas.drawRoundRect(lockPill, dp(12), dp(12), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(ACCENT);
                canvas.drawRoundRect(lockPill, dp(12), dp(12), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ACCENT);
                Paint.FontMetrics fm = paint.getFontMetrics();
                canvas.drawText(text, lockPill.centerX() - tw * 0.5f,
                        lockPill.centerY() - (fm.ascent + fm.descent) * 0.5f, paint);
            }

            if (focusVisible && focusX >= 0 && focusY >= 0) {
                float cx = focusX * w, cy = focusY * h;
                float half = dp(29) * focusScale;
                float arm = dp(10);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f));
                paint.setStrokeCap(Paint.Cap.SQUARE);
                paint.setColor(focusLocked || draggingEv ? ACCENT : INK);
                // Top-left
                canvas.drawLine(cx - half, cy - half + arm, cx - half, cy - half, paint);
                canvas.drawLine(cx - half, cy - half, cx - half + arm, cy - half, paint);
                // Top-right
                canvas.drawLine(cx + half - arm, cy - half, cx + half, cy - half, paint);
                canvas.drawLine(cx + half, cy - half, cx + half, cy - half + arm, paint);
                // Bottom-left
                canvas.drawLine(cx - half, cy + half - arm, cx - half, cy + half, paint);
                canvas.drawLine(cx - half, cy + half, cx - half + arm, cy + half, paint);
                // Bottom-right
                canvas.drawLine(cx + half - arm, cy + half, cx + half, cy + half, paint);
                canvas.drawLine(cx + half, cy + half, cx + half, cy + half - arm, paint);

                // Inline vertical EV bar next to reticle
                if (draggingEv) {
                    float barX = cx + half + dp(12) > w - dp(16) ? cx - half - dp(12) : cx + half + dp(12);
                    float barHalf = dp(24);
                    paint.setColor(0x66ffffff);
                    paint.setStrokeWidth(dp(1.2f));
                    canvas.drawLine(barX, cy - barHalf, barX, cy + barHalf, paint);
                    float t = Math.max(-1f, Math.min(1f, evDisplay / 2f));
                    float dotY = cy - t * barHalf;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(ACCENT);
                    canvas.drawCircle(barX, dotY, dp(3.5f), paint);
                }
            }
        }
    }

    private final class FinderFrame extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mask = new Path();
        private final RectF bounds = new RectF();

        FinderFrame(Context context) { super(context); }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            bounds.set(0f, 0f, w, h);
            mask.reset();
            mask.setFillType(Path.FillType.EVEN_ODD);
            mask.addRect(0, 0, w, h, Path.Direction.CW);
            mask.addRoundRect(bounds, dp(6), dp(6), Path.Direction.CW);
        }

        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BG);
            canvas.drawPath(mask, paint);
        }
    }

    static final class Mark extends View {
        static final int SHUTTER = 0, FILTER = 1, GALLERY = 2, SETTINGS = 3, BACK = 4, GRID = 5, SHARE = 6, HELP = 7, DELETE = 8, ALBUM_GRID = 9;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcBounds = new RectF();
        private final int kind;
        private boolean captureActive;
        private float sweepPhase;
        private ValueAnimator sweepAnim;

        Mark(Context context, int kind) { super(context); this.kind = kind; }

        @Override public CharSequence getAccessibilityClassName() {
            return isClickable() ? android.widget.Button.class.getName() : super.getAccessibilityClassName();
        }

        void setCaptureActive(boolean active) {
            if (kind != SHUTTER || captureActive == active) return;
            captureActive = active;
            if (sweepAnim != null) {
                sweepAnim.cancel();
                sweepAnim = null;
            }
            if (active && ValueAnimator.areAnimatorsEnabled()) {
                sweepAnim = ValueAnimator.ofFloat(0f, 360f);
                sweepAnim.setDuration(900);
                sweepAnim.setRepeatCount(ValueAnimator.INFINITE);
                sweepAnim.setInterpolator(new LinearInterpolator());
                sweepAnim.addUpdateListener(a -> {
                    sweepPhase = (float) a.getAnimatedValue();
                    invalidate();
                });
                sweepAnim.start();
            } else {
                invalidate();
            }
        }

        @Override protected void onDetachedFromWindow() {
            if (sweepAnim != null) {
                sweepAnim.cancel();
                sweepAnim = null;
            }
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.translate(getPaddingLeft(), getPaddingTop());
            canvas.scale((getWidth() - getPaddingLeft() - getPaddingRight()) / 48f,
                    (getHeight() - getPaddingTop() - getPaddingBottom()) / 48f);
            paint.setColor(isSelected() ? ACCENT : INK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.65f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            switch (kind) {
                case SHUTTER:
                    paint.setStrokeWidth(1.4f);
                    paint.setColor(captureActive ? 0x44f5f5f2 : INK);
                    canvas.drawCircle(24, 24, 22.0f, paint);
                    paint.setColor(captureActive ? 0xffd0cfc8 : 0xfff5f5f0);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(24, 24, captureActive ? 16.8f : 18.4f, paint);
                    if (captureActive) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(1.9f);
                        paint.setColor(ACCENT);
                        arcBounds.set(2f, 2f, 46f, 46f);
                        canvas.drawArc(arcBounds, sweepPhase, 115f, false, paint);
                    }
                    break;
                case FILTER:
                    canvas.drawCircle(24, 18.5f, 6.8f, paint);
                    canvas.drawCircle(19.5f, 26.2f, 6.8f, paint);
                    canvas.drawCircle(28.5f, 26.2f, 6.8f, paint);
                    if (isActivated()) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(ACCENT);
                        canvas.drawCircle(36.5f, 11.5f, 2.5f, paint);
                    }
                    break;
                case GALLERY:
                    canvas.drawRoundRect(12, 13, 36, 35, 4, 4, paint);
                    canvas.drawCircle(19, 19.5f, 1.75f, paint);
                    canvas.drawLine(13, 31, 21, 25, paint);
                    canvas.drawLine(21, 25, 26, 29, paint);
                    canvas.drawLine(26, 29, 34, 22, paint);
                    break;
                case SETTINGS:
                    canvas.drawLine(13, 19, 35, 19, paint);
                    canvas.drawLine(13, 29, 35, 29, paint);
                    canvas.drawLine(20, 15.5f, 20, 22.5f, paint);
                    canvas.drawLine(28, 25.5f, 28, 32.5f, paint);
                    break;
                case BACK:
                    paint.setStrokeWidth(2.1f);
                    canvas.drawLine(15, 24, 34, 24, paint);
                    canvas.drawLine(15, 24, 23, 16, paint);
                    canvas.drawLine(15, 24, 23, 32, paint);
                    break;
                case GRID:
                    paint.setStrokeWidth(1.45f);
                    canvas.drawRoundRect(12, 12, 36, 36, 3, 3, paint);
                    canvas.drawLine(20, 12.5f, 20, 35.5f, paint);
                    canvas.drawLine(28, 12.5f, 28, 35.5f, paint);
                    canvas.drawLine(12.5f, 20, 35.5f, 20, paint);
                    canvas.drawLine(12.5f, 28, 35.5f, 28, paint);
                    break;
                case SHARE:
                    paint.setStrokeWidth(1.85f);
                    canvas.drawLine(24, 13, 24, 28, paint);
                    canvas.drawLine(24, 13, 18.5f, 18.5f, paint);
                    canvas.drawLine(24, 13, 29.5f, 18.5f, paint);
                    canvas.drawLine(15, 25, 15, 34, paint);
                    canvas.drawLine(15, 34, 33, 34, paint);
                    canvas.drawLine(33, 34, 33, 25, paint);
                    break;
                case HELP:
                    paint.setStrokeWidth(1.45f);
                    canvas.drawCircle(24, 24, 12.2f, paint);
                    paint.setStrokeWidth(1.65f);
                    arcBounds.set(20.6f, 16.8f, 27.4f, 23.4f);
                    canvas.drawArc(arcBounds, 200f, 225f, false, paint);
                    canvas.drawLine(24, 23.4f, 24, 26.8f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(24, 30.6f, 1.15f, paint);
                    break;
                case DELETE:
                    paint.setStrokeWidth(1.65f);
                    canvas.drawLine(14, 17, 34, 17, paint);
                    canvas.drawLine(20.5f, 14, 27.5f, 14, paint);
                    canvas.drawLine(20.5f, 14, 20.5f, 17, paint);
                    canvas.drawLine(27.5f, 14, 27.5f, 17, paint);
                    canvas.drawLine(17, 17, 18.5f, 34, paint);
                    canvas.drawLine(18.5f, 34, 29.5f, 34, paint);
                    canvas.drawLine(29.5f, 34, 31, 17, paint);
                    canvas.drawLine(22, 21.5f, 22.3f, 29.5f, paint);
                    canvas.drawLine(26, 21.5f, 25.7f, 29.5f, paint);
                    break;
                case ALBUM_GRID:
                    paint.setStrokeWidth(1.65f);
                    canvas.drawRoundRect(13, 13, 22, 22, 2.5f, 2.5f, paint);
                    canvas.drawRoundRect(26, 13, 35, 22, 2.5f, 2.5f, paint);
                    canvas.drawRoundRect(13, 26, 22, 35, 2.5f, 2.5f, paint);
                    canvas.drawRoundRect(26, 26, 35, 35, 2.5f, 2.5f, paint);
                    break;
                default: break;
            }
            canvas.restore();
        }
    }

    private final class GalleryRing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private ValueAnimator anim;
        private float angle;
        private boolean active;

        GalleryRing(Context context) { super(context); }

        void setActive(boolean active) {
            if (this.active == active) return;
            this.active = active;
            setVisibility(active ? VISIBLE : GONE);
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            if (active && ValueAnimator.areAnimatorsEnabled()) {
                anim = ValueAnimator.ofFloat(0f, 360f);
                anim.setDuration(880);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new LinearInterpolator());
                anim.addUpdateListener(a -> {
                    angle = (float) a.getAnimatedValue();
                    invalidate();
                });
                anim.start();
            } else {
                invalidate();
            }
        }

        @Override protected void onDetachedFromWindow() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (!active) return;
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x6609090a);
            canvas.drawRect(0, 0, w, h, paint);

            float cx = w * 0.5f, cy = h * 0.5f, r = dp(10.5f);
            arc.set(cx - r, cy - r, cx + r, cy + r);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0x44ffffff);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(ACCENT);
            canvas.drawArc(arc, angle, 110f, false, paint);
        }
    }
}
