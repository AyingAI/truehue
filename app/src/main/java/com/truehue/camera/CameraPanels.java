package com.truehue.camera;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Sleek obsidian optical surfaces, grouped cards, and pinch-zoom lightbox viewer. */
final class CameraPanels {
    private CameraPanels() {}

    static Dialog sheet(CameraScreen ui, String title, View body) {
        return sheet(ui, title, body, null);
    }

    static Dialog sheet(CameraScreen ui, String title, View body, String closeLabel) {
        Context context = ui.getContext();
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(16));
        android.graphics.drawable.GradientDrawable background = ui.rounded(CameraScreen.PANEL, 0, 0);
        int r = ui.dp(26);
        background.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        background.setStroke(ui.dp(1), 0xff252529);
        content.setBackground(background);

        LinearLayout scrollContent = new LinearLayout(context);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        View handle = new View(context);
        handle.setBackground(ui.rounded(0xff3e3e44, 2, 0));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = ui.dp(12);
        scrollContent.addView(handle, handleLp);

        scrollContent.addView(heading(ui, title), new LinearLayout.LayoutParams(-1, -2));
        View line = new View(context);
        line.setBackgroundColor(CameraScreen.LINE);
        LinearLayout.LayoutParams divider = new LinearLayout.LayoutParams(-1, ui.dp(1));
        divider.topMargin = ui.dp(12);
        divider.bottomMargin = ui.dp(8);
        scrollContent.addView(line, divider);
        scrollContent.addView(body, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(scrollContent);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        if (closeLabel != null) {
            LinearLayout.LayoutParams footer = new LinearLayout.LayoutParams(-1, -2);
            footer.topMargin = ui.dp(12);
            content.addView(button(ui, closeLabel, true, v -> dialog.dismiss()), footer);
        }
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.64f);
        window.setWindowAnimations(ValueAnimator.areAnimatorsEnabled() ? R.style.CameraSheetAnimation : 0);
        return dialog;
    }

    static void fitSheet(Dialog dialog, CameraScreen ui) {
        dialog.getWindow().setLayout(Math.min(ui.getWidth(), ui.dp(560)), -2);
    }

    static TextView section(CameraScreen ui, String title) {
        TextView label = ui.label(title, 12, CameraScreen.MUTED);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(6));
        return label;
    }

    static LinearLayout card(CameraScreen ui) {
        LinearLayout card = new LinearLayout(ui.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.rounded(CameraScreen.ELEVATED, 16, CameraScreen.LINE));
        card.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
        return card;
    }

    static void divider(CameraScreen ui, LinearLayout parent) {
        View line = new View(ui.getContext());
        line.setBackgroundColor(CameraScreen.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(0.75f)));
        lp.setMarginStart(ui.dp(8));
        lp.setMarginEnd(ui.dp(8));
        parent.addView(line, lp);
    }

    static void sheetActions(CameraScreen ui, LinearLayout body, Dialog dialog, String cancel,
                             String confirm, View.OnClickListener onConfirm) {
        LinearLayout footer = new LinearLayout(ui.getContext());
        LinearLayout.LayoutParams footerSize = new LinearLayout.LayoutParams(-1, -2);
        footerSize.topMargin = ui.dp(16);
        body.addView(footer, footerSize);
        LinearLayout.LayoutParams cancelSize = new LinearLayout.LayoutParams(0, -1, 1);
        cancelSize.setMarginEnd(ui.dp(10));
        footer.addView(button(ui, cancel, false, v -> dialog.dismiss()), cancelSize);
        footer.addView(button(ui, confirm, true, onConfirm), new LinearLayout.LayoutParams(0, -2, 1));
    }

    static LinearLayout guideItem(CameraScreen ui, String title, String desc) {
        LinearLayout row = new LinearLayout(ui.getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8));
        TextView heading = ui.label(title, 14, CameraScreen.INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(heading);
        TextView body = ui.label(desc, 12, CameraScreen.MUTED);
        body.setLineSpacing(ui.dp(2), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.topMargin = ui.dp(3);
        row.addView(body, descLp);
        return row;
    }

    static Dialog photo(CameraScreen ui, View content) {
        Dialog dialog = new Dialog(ui.getContext(), R.style.CameraPhoto);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        window.setDecorFitsSystemWindows(false);
        if (!ValueAnimator.areAnimatorsEnabled()) window.setWindowAnimations(0);
        CameraScreen.useSystemInsets(content);
        return dialog;
    }

    static TextView heading(CameraScreen ui, String title) {
        TextView text = ui.label(title, 19, CameraScreen.INK);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setAccessibilityHeading(true);
        return text;
    }

    static void option(CameraScreen ui, CompoundButton button) {
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{0xff64646a, CameraScreen.INK}));
        button.setButtonTintList(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                new int[]{0xff525258, CameraScreen.ACCENT, CameraScreen.MUTED}));
        button.setTextSize(14);
        button.setMinHeight(ui.dp(48));
        button.setPaddingRelative(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10));
        button.setCompoundDrawablePadding(ui.dp(10));
        button.setBackground(ui.ripple(12));
    }

    static void option(CameraScreen ui, CompoundButton button, String title, String subtitle) {
        option(ui, button);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setLineSpacing(ui.dp(2.5f), 1f);
        SpannableStringBuilder ssb = new SpannableStringBuilder(title);
        if (subtitle != null && !subtitle.isEmpty()) {
            int start = ssb.length();
            ssb.append('\n').append(subtitle);
            ssb.setSpan(new AbsoluteSizeSpan(12, true), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new ForegroundColorSpan(CameraScreen.MUTED), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setText(ssb);
    }

    static TextView button(CameraScreen ui, String title, boolean primary, View.OnClickListener click) {
        TextView button = ui.label(title, 14, primary ? CameraScreen.BG : CameraScreen.INK);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setPaddingRelative(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        button.setMinHeight(ui.dp(48));
        button.setBackground(ui.rounded(primary ? CameraScreen.ACCENT : CameraScreen.ELEVATED, 16,
                primary ? 0 : CameraScreen.LINE));
        button.setForeground(ui.ripple(16));
        button.setFocusable(true);
        button.setOnClickListener(click);
        return button;
    }

    /** Pinch-zoomable, swipeable, and double-tap zoomable ImageView for photo review. */
    static final class ZoomableImageView extends ImageView {
        interface InteractionListener {
            void onTap();
            boolean canSwipe(int delta);
            void onSwipe(int delta);
            void onSwipeDownDismiss();
        }

        private static final int MODE_NONE = 0, MODE_PAN = 1, MODE_SWIPE_X = 2, MODE_DISMISS_Y = 3;
        private static final PathInterpolator EASE_OUT = new PathInterpolator(.16f, 1f, .3f, 1f);

        private final Matrix drawMatrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private InteractionListener listener;
        private float baseScale = 1f;
        private float currentScale = 1f;
        private float downX, downY, lastX, lastY;
        private int touchMode = MODE_NONE;
        private boolean dragging, flingHandled;

        ZoomableImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (listener != null) {
                        listener.onTap();
                        return true;
                    }
                    return false;
                }

                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (currentScale > 1.15f) {
                        fitToView();
                    } else {
                        zoomBy(2.5f / currentScale, e.getX(), e.getY());
                    }
                    return true;
                }

                @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (listener == null || currentScale > 1.02f || scaleDetector.isInProgress()) return false;
                    float flingX = dp(450);
                    float flingY = dp(550);
                    if (touchMode != MODE_DISMISS_Y
                            && Math.abs(vx) > Math.abs(vy) * 1.2f && Math.abs(vx) > flingX) {
                        int delta = vx < 0 ? 1 : -1;
                        if (listener.canSwipe(delta)) {
                            flingHandled = true;
                            resetSwipeOffset(false);
                            listener.onSwipe(delta);
                            return true;
                        }
                    }
                    if (touchMode != MODE_SWIPE_X
                            && vy > flingY && vy > Math.abs(vx) * 1.3f) {
                        flingHandled = true;
                        listener.onSwipeDownDismiss();
                        return true;
                    }
                    return false;
                }
            });
        }

        void setInteractionListener(InteractionListener listener) {
            this.listener = listener;
        }

        void resetTransform() {
            animate().cancel();
            setTranslationX(0f);
            setTranslationY(0f);
            setScaleX(1f);
            setScaleY(1f);
            setAlpha(1f);
        }

        void playSlideIn(int direction) {
            if (direction == 0 || !ValueAnimator.areAnimatorsEnabled()) {
                resetTransform();
                return;
            }
            animate().cancel();
            setTranslationY(0f);
            setScaleX(1f);
            setScaleY(1f);
            setTranslationX(direction * dp(44));
            setAlpha(0.55f);
            animate().translationX(0f).alpha(1f).setDuration(165).setInterpolator(EASE_OUT).start();
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private void resetSwipeOffset(boolean animated) {
            if (!animated || !ValueAnimator.areAnimatorsEnabled()) {
                setTranslationX(0f);
                setTranslationY(0f);
                setScaleX(1f);
                setScaleY(1f);
                return;
            }
            animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(150).setInterpolator(EASE_OUT).start();
        }

        @Override public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            fitToView();
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (changed || currentScale <= 1.001f) fitToView();
        }

        private void fitToView() {
            Drawable d = getDrawable();
            int vw = getWidth(), vh = getHeight();
            if (d == null || vw <= 0 || vh <= 0) return;
            float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;
            baseScale = Math.min(vw / dw, vh / dh);
            currentScale = 1f;
            float dx = (vw - dw * baseScale) * 0.5f;
            float dy = (vh - dh * baseScale) * 0.5f;
            drawMatrix.reset();
            drawMatrix.postScale(baseScale, baseScale);
            drawMatrix.postTranslate(dx, dy);
            setImageMatrix(drawMatrix);
        }

        private void zoomBy(float factor, float fx, float fy) {
            resetSwipeOffset(false);
            float target = Math.max(1f, Math.min(5f, currentScale * factor));
            float actual = target / currentScale;
            currentScale = target;
            drawMatrix.postScale(actual, actual, fx, fy);
            clampTranslation();
            setImageMatrix(drawMatrix);
        }

        private void clampTranslation() {
            Drawable d = getDrawable();
            if (d == null) return;
            RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            drawMatrix.mapRect(rect);
            float vw = getWidth(), vh = getHeight();
            float dx = 0, dy = 0;
            if (rect.width() <= vw) dx = (vw - rect.width()) * 0.5f - rect.left;
            else if (rect.left > 0) dx = -rect.left;
            else if (rect.right < vw) dx = vw - rect.right;
            if (rect.height() <= vh) dy = (vh - rect.height()) * 0.5f - rect.top;
            else if (rect.top > 0) dy = -rect.top;
            else if (rect.bottom < vh) dy = vh - rect.bottom;
            drawMatrix.postTranslate(dx, dy);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            scaleDetector.onTouchEvent(e);
            gestureDetector.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animate().cancel();
                    downX = lastX = e.getX();
                    downY = lastY = e.getY();
                    touchMode = MODE_NONE;
                    dragging = true;
                    flingHandled = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging || scaleDetector.isInProgress() || e.getPointerCount() != 1) {
                        lastX = e.getX();
                        lastY = e.getY();
                        break;
                    }
                    if (currentScale > 1.02f) {
                        touchMode = MODE_PAN;
                        drawMatrix.postTranslate(e.getX() - lastX, e.getY() - lastY);
                        clampTranslation();
                        setImageMatrix(drawMatrix);
                    } else {
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        float slop = dp(12);
                        if (touchMode == MODE_NONE) {
                            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                                touchMode = MODE_SWIPE_X;
                            } else if (dy > slop && dy > Math.abs(dx) * 1.35f) {
                                touchMode = MODE_DISMISS_Y;
                            }
                        }
                        if (touchMode == MODE_SWIPE_X) {
                            int delta = dx < 0 ? 1 : -1;
                            boolean canMove = listener != null && listener.canSwipe(delta);
                            setTranslationX(dx * (canMove ? 0.72f : 0.22f));
                        } else if (touchMode == MODE_DISMISS_Y) {
                            float clampedY = Math.max(0f, dy);
                            setTranslationY(clampedY * 0.60f);
                            float scale = 1f - Math.min(0.14f, clampedY / Math.max(1f, getHeight()) * 0.25f);
                            setScaleX(scale);
                            setScaleY(scale);
                        }
                    }
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging && !flingHandled && currentScale <= 1.02f) {
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && touchMode == MODE_SWIPE_X && listener != null) {
                            int delta = dx < 0 ? 1 : -1;
                            float threshold = Math.max(dp(64), getWidth() * 0.20f);
                            if (Math.abs(dx) > threshold && listener.canSwipe(delta)) {
                                resetSwipeOffset(false);
                                listener.onSwipe(delta);
                                touchMode = MODE_NONE;
                                dragging = false;
                                break;
                            }
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP && touchMode == MODE_DISMISS_Y
                                && listener != null && dy > dp(110)) {
                            listener.onSwipeDownDismiss();
                            touchMode = MODE_NONE;
                            dragging = false;
                            break;
                        }
                        resetSwipeOffset(true);
                    }
                    touchMode = MODE_NONE;
                    dragging = false;
                    break;
            }
            return true;
        }
    }
}
