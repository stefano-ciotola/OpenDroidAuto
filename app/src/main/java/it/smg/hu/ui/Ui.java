package it.smg.hu.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;

import it.smg.hu.R;
import it.smg.hu.config.Settings;

/**
 * Small UI kit shared by the home and settings screens: the palette of the
 * current theme, the shapes built from it, press feedback and the easing
 * curve. Everything is built in code because Android 4.0 cannot tint shape
 * drawables or use vector icons.
 *
 * Motion follows a few strict rules: only presses and state changes animate,
 * nothing animates while the projection runs, and every animation goes
 * through ViewPropertyAnimator so the system animation scale still applies.
 */
public final class Ui {

    /** Strong ease-out, cubic-bezier(0.23, 1, 0.32, 1): starts fast, settles softly. */
    public static final Interpolator EASE_OUT = new CubicBezier(0.23f, 1f, 0.32f, 1f);

    public static final long PRESS_MS = 120;
    public static final long RELEASE_MS = 160;
    public static final long FADE_MS = 150;
    public static final float PRESSED_SCALE = 0.97f;

    private Ui() {}

    /** Colours of the current theme. */
    public static final class Palette {
        public final boolean dark;
        public final int bg, rail, surface, selected, trackOff;
        public final int text, body, muted, accent, onAccent, hairline, dashed;
        public final int ready, waiting, error;

        Palette(Resources r, boolean dark) {
            this.dark = dark;
            bg = r.getColor(dark ? R.color.ui_dark_bg : R.color.ui_light_bg);
            rail = r.getColor(dark ? R.color.ui_dark_rail : R.color.ui_light_rail);
            surface = r.getColor(dark ? R.color.ui_dark_surface : R.color.ui_light_surface);
            selected = r.getColor(dark ? R.color.ui_dark_selected : R.color.ui_light_selected);
            trackOff = r.getColor(dark ? R.color.ui_dark_track_off : R.color.ui_light_track_off);
            text = r.getColor(dark ? R.color.ui_dark_text : R.color.ui_light_text);
            body = r.getColor(dark ? R.color.ui_dark_body : R.color.ui_light_body);
            muted = r.getColor(dark ? R.color.ui_dark_muted : R.color.ui_light_muted);
            accent = r.getColor(dark ? R.color.ui_dark_accent : R.color.ui_light_accent);
            onAccent = r.getColor(dark ? R.color.ui_dark_on_accent : R.color.ui_light_on_accent);
            hairline = r.getColor(dark ? R.color.ui_dark_hairline : R.color.ui_light_hairline);
            dashed = r.getColor(dark ? R.color.ui_dark_dashed : R.color.ui_light_dashed);
            ready = r.getColor(dark ? R.color.ui_dark_ready : R.color.ui_light_ready);
            waiting = r.getColor(dark ? R.color.ui_dark_waiting : R.color.ui_light_waiting);
            error = r.getColor(dark ? R.color.ui_dark_error : R.color.ui_light_error);
        }
    }

    public static boolean isDark() {
        Settings settings = Settings.instance();
        return settings == null || settings.appearance.darkTheme();
    }

    public static Palette palette(Context context) {
        return new Palette(context.getResources(), isDark());
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Rounded rectangle; strokeColor 0 means no outline. */
    public static GradientDrawable rounded(Context context, int fill, float radiusDp, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != 0) {
            shape.setStroke(dp(context, 1), strokeColor);
        }
        return shape;
    }

    /** Same shape, darker or lighter while pressed. */
    public static StateListDrawable pressable(Drawable normal, Drawable pressed) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    /** Quiet button: surface fill and hairline, the selected colour while pressed. */
    public static StateListDrawable quietButton(Context context, Palette p, float radiusDp) {
        return pressable(rounded(context, p.surface, radiusDp, p.hairline),
                rounded(context, p.selected, radiusDp, p.hairline));
    }

    /** Row inside a group: transparent, highlighted while pressed. */
    public static StateListDrawable rowBackground(Context context, Palette p) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x00000000);
        return pressable(normal, rounded(context, p.selected, 14, 0));
    }

    /** Dashed placeholder outline. */
    public static GradientDrawable dashedOutline(Context context, Palette p, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0x00000000);
        shape.setCornerRadius(dp(context, radiusDp));
        shape.setStroke(dp(context, 1.5f), p.dashed, dp(context, 8), dp(context, 6));
        return shape;
    }

    /**
     * 52x30 dp switch shown on the right of a checkbox row: an accent track
     * with the knob on the right when checked, a grey track with the knob on
     * the left otherwise.
     */
    public static Drawable switchDrawable(Context context, Palette p) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_checked}, switchState(context, p, true));
        states.addState(new int[]{}, switchState(context, p, false));
        return states;
    }

    private static Drawable switchState(Context context, Palette p, boolean on) {
        GradientDrawable track = new GradientDrawable();
        track.setColor(on ? p.accent : p.trackOff);
        track.setCornerRadius(dp(context, 15));
        track.setSize(dp(context, 52), dp(context, 30));
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        knob.setColor(on ? (p.dark ? p.text : 0xFFFFFFFF) : (p.dark ? p.muted : 0xFFFFFFFF));
        knob.setSize(dp(context, 22), dp(context, 22));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, knob});
        int inset = dp(context, 4);
        int travel = dp(context, 22);
        layers.setLayerInset(1, on ? inset + travel : inset, inset, on ? inset : inset + travel, inset);
        return layers;
    }

    /**
     * Input field: a rounded box a step darker than the group it sits in,
     * with a hairline and, for spinners, a chevron on the right.
     */
    public static Drawable field(Context context, Palette p, boolean chevron) {
        return new FieldDrawable(p.dark ? p.bg : p.rail, p.hairline, chevron ? p.muted : 0,
                context.getResources().getDisplayMetrics().density);
    }

    static final class FieldDrawable extends Drawable {
        private final Paint fill_ = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke_ = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chevron_ = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect_ = new RectF();
        private final Path path_ = new Path();
        private final float density_;
        private final boolean hasChevron_;

        FieldDrawable(int fill, int stroke, int chevron, float density) {
            density_ = density;
            fill_.setColor(fill);
            stroke_.setStyle(Paint.Style.STROKE);
            stroke_.setStrokeWidth(density);
            stroke_.setColor(stroke);
            hasChevron_ = chevron != 0;
            chevron_.setStyle(Paint.Style.STROKE);
            chevron_.setStrokeWidth(2f * density);
            chevron_.setStrokeCap(Paint.Cap.ROUND);
            chevron_.setStrokeJoin(Paint.Join.ROUND);
            chevron_.setColor(chevron);
        }

        @Override
        public void draw(Canvas canvas) {
            float half = density_ / 2f;
            rect_.set(getBounds());
            rect_.inset(half, half);
            float radius = 10f * density_;
            canvas.drawRoundRect(rect_, radius, radius, fill_);
            canvas.drawRoundRect(rect_, radius, radius, stroke_);
            if (hasChevron_) {
                float cx = rect_.right - 18f * density_;
                float cy = rect_.centerY();
                float w = 5f * density_;
                float h = 2.5f * density_;
                path_.reset();
                path_.moveTo(cx - w, cy - h);
                path_.lineTo(cx, cy + h);
                path_.lineTo(cx + w, cy - h);
                canvas.drawPath(path_, chevron_);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            fill_.setAlpha(alpha);
            stroke_.setAlpha(alpha);
            chevron_.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            fill_.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Scales the view to 0.97 while it is pressed: 120 ms in, 160 ms back,
     * both with the strong ease-out. Clicks are still delivered normally.
     */
    public static void pressFeedback(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.isEnabled() && v.isClickable()) {
                        v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                                .setDuration(PRESS_MS).setInterpolator(EASE_OUT).start();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(RELEASE_MS).setInterpolator(EASE_OUT).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Fades a view in from 0 without moving it. */
    public static void fadeIn(View view) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(EASE_OUT).start();
    }

    /** CSS-style cubic-bezier easing (PathInterpolator needs API 21). */
    static final class CubicBezier implements Interpolator {
        private static final int SAMPLES = 64;
        private final float x1, y1, x2, y2;
        private final float[] xs = new float[SAMPLES + 1];

        CubicBezier(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            for (int i = 0; i <= SAMPLES; i++) {
                xs[i] = bezier(i / (float) SAMPLES, x1, x2);
            }
        }

        private static float bezier(float t, float p1, float p2) {
            float u = 1f - t;
            return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t;
        }

        @Override
        public float getInterpolation(float x) {
            if (x <= 0f) return 0f;
            if (x >= 1f) return 1f;
            // Nearest sample, then a few bisection steps on the x curve.
            int i = 0;
            while (i < SAMPLES && xs[i + 1] < x) i++;
            float lo = i / (float) SAMPLES;
            float hi = (i + 1) / (float) SAMPLES;
            float t = lo;
            for (int step = 0; step < 8; step++) {
                t = (lo + hi) * 0.5f;
                if (bezier(t, x1, x2) < x) lo = t; else hi = t;
            }
            return bezier(t, y1, y2);
        }
    }
}
