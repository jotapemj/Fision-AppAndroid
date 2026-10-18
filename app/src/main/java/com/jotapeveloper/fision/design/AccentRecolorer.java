package com.jotapeveloper.fision.design;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.jotapeveloper.fision.VerticalSlider;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves every accent-painted widget of a view tree from one accent pair
 * (primary + secondary) to another.
 *
 * Detection is by current color: a property is recolored only when it
 * currently shows the old accent, so state-dependent tints (a toggle button
 * that is white while off) are left alone. Switches and the seek bar are
 * always themed (their stock tints do not follow the accent).
 *
 * Buttons, sliders, switches and seek bars fade over the given duration;
 * text, image tints and progress spinners switch instantly.
 */
public final class AccentRecolorer {

    private interface Target { void apply(int primary, int secondary); }

    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    private AccentRecolorer() {}

    /** Recolors [roots] and returns the running animator (null when the
     *  change was applied instantly). */
    @Nullable
    public static ValueAnimator recolor(List<View> roots,
                                        @ColorInt int fromP, @ColorInt int fromS,
                                        @ColorInt int toP, @ColorInt int toS,
                                        long durationMs) {
        List<Target> animated = new ArrayList<>();
        List<Target> instant  = new ArrayList<>();
        for (View r : roots) if (r != null) collect(r, fromP, fromS, animated, instant);
        for (Target t : instant) t.apply(toP, toS);
        if (durationMs <= 0 || animated.isEmpty()) {
            for (Target t : animated) t.apply(toP, toS);
            return null;
        }
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(durationMs);
        va.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            int p = (int) ARGB.evaluate(f, fromP, toP);
            int s = (int) ARGB.evaluate(f, fromS, toS);
            for (Target t : animated) t.apply(p, s);
        });
        va.start();
        return va;
    }

    private static final int[] STATE_ENABLED = new int[] { android.R.attr.state_enabled };
    private static final int[] STATE_NONE = new int[0];

    /** Color shown in the enabled state. Style tints are often selectors
     *  whose default (fallback) entry is the disabled color, so the default
     *  color alone would miss accent-painted widgets. */
    private static int en(@Nullable ColorStateList csl) {
        return csl != null ? csl.getColorForState(STATE_ENABLED, csl.getDefaultColor()) : 0;
    }

    /** [orig] with its enabled color replaced by [color]; the disabled color
     *  of a selector is kept. [orig] is captured before any recolor. */
    private static ColorStateList retint(@Nullable ColorStateList orig, int color) {
        if (orig == null) return ColorStateList.valueOf(color);
        int disabled = orig.getColorForState(STATE_NONE, orig.getDefaultColor());
        if (disabled == en(orig)) return ColorStateList.valueOf(color);
        return new ColorStateList(
                new int[][] { new int[] { -android.R.attr.state_enabled }, STATE_NONE },
                new int[] { disabled, color });
    }

    /** True when [csl]'s enabled color is [color] at any alpha (ripples and
     *  halos carry the accent with their own transparency). */
    private static boolean sameRgb(@Nullable ColorStateList csl, int color) {
        return csl != null && (en(csl) & 0x00FFFFFF) == (color & 0x00FFFFFF);
    }

    /** [csl]'s enabled color with its hue replaced by [color], alpha kept. */
    private static ColorStateList withRgb(@Nullable ColorStateList csl, int color) {
        int a = csl != null ? en(csl) & 0xFF000000 : 0xFF000000;
        return ColorStateList.valueOf(a | (color & 0x00FFFFFF));
    }

    private static void collect(View v, int fromP, int fromS,
                                List<Target> animated, List<Target> instant) {
        if (v instanceof MaterialButton) {
            MaterialButton b = (MaterialButton) v;
            ColorStateList icon = b.getIconTint();
            if (en(icon) == fromP) {
                animated.add((p, s) -> b.setIconTint(retint(icon, p)));
            }
            // Background: MDC reports the style's selector (enabled →
            // colorPrimary) for buttons tinted via android:backgroundTint,
            // even when they draw black, so only a plain single-color tint
            // (app:backgroundTint) is trusted. Accent-filled buttons in the
            // player layouts use app:backgroundTint for this reason.
            ColorStateList bg = b.getBackgroundTintList();
            if (bg != null && !bg.isStateful() && bg.getDefaultColor() == fromP) {
                animated.add((p, s) -> b.setBackgroundTintList(ColorStateList.valueOf(p)));
            }
            ColorStateList stroke = b.getStrokeColor();
            if (en(stroke) == fromP) {
                animated.add((p, s) -> b.setStrokeColor(retint(stroke, p)));
            }
            // Press ripple (app:rippleColor="?attr/colorPrimary"): same hue, own alpha.
            ColorStateList ripple = b.getRippleColor();
            if (sameRgb(ripple, fromP)) {
                animated.add((p, s) -> b.setRippleColor(withRgb(ripple, p)));
            }
        } else if (v instanceof Slider) {
            Slider sl = (Slider) v;
            ColorStateList track = sl.getTrackActiveTintList();
            if (en(track) == fromP) {
                animated.add((p, s) -> sl.setTrackActiveTintList(retint(track, p)));
            }
            ColorStateList thumbCsl = sl.getThumbTintList();
            int thumb = en(thumbCsl);
            if (thumb == fromS) {
                animated.add((p, s) -> sl.setThumbTintList(retint(thumbCsl, s)));
            } else if (thumb == fromP) {
                animated.add((p, s) -> sl.setThumbTintList(retint(thumbCsl, p)));
            }
            // Halo shown while dragging: the accent at low alpha.
            ColorStateList halo = sl.getHaloTintList();
            if (sameRgb(halo, fromP)) {
                animated.add((p, s) -> sl.setHaloTintList(withRgb(halo, p)));
            }
        } else if (v instanceof VerticalSlider) {
            VerticalSlider vs = (VerticalSlider) v;
            if (vs.getTrackActiveColor() == fromP) {
                animated.add(vs::setColors);
            }
        } else if (v instanceof SwitchMaterial) {
            SwitchMaterial sw = (SwitchMaterial) v;
            animated.add((p, s) -> tintSwitch(sw, p));
        } else if (v instanceof android.widget.CompoundButton) {
            android.widget.CompoundButton cb = (android.widget.CompoundButton) v;
            ColorStateList btn = cb.getButtonTintList();
            // Checkbox tints key on the checked state.
            int checked = btn != null ? btn.getColorForState(new int[] {
                    android.R.attr.state_enabled, android.R.attr.state_checked }, btn.getDefaultColor()) : 0;
            if (checked == fromP) {
                instant.add((p, s) -> cb.setButtonTintList(retintChecked(btn, p)));
            }
        } else if (v instanceof SeekBar) {
            SeekBar sb = (SeekBar) v;
            animated.add((p, s) -> {
                sb.setProgressTintList(ColorStateList.valueOf(p));
                sb.setThumbTintList(ColorStateList.valueOf(p));
            });
        } else if (v instanceof ProgressBar) {
            ProgressBar pb = (ProgressBar) v;
            ColorStateList ind = pb.getIndeterminateTintList();
            if (en(ind) == fromP) {
                instant.add((p, s) -> pb.setIndeterminateTintList(retint(ind, p)));
            }
            ColorStateList prog = pb.getProgressTintList();
            if (en(prog) == fromP) {
                instant.add((p, s) -> pb.setProgressTintList(retint(prog, p)));
            }
        } else if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (tv.getCurrentTextColor() == fromP) {
                instant.add((p, s) -> tv.setTextColor(p));
            }
        } else if (v instanceof ImageView) {
            ImageView iv = (ImageView) v;
            ColorStateList tint = iv.getImageTintList();
            if (en(tint) == fromP) {
                instant.add((p, s) -> iv.setImageTintList(retint(tint, p)));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), fromP, fromS, animated, instant);
            }
        }
    }

    /** Accent-checked switch: solid accent thumb, 54% accent track; the
     *  unchecked colors are read from the switch's current tints, so the
     *  stock Material look is kept. */
    public static void tintSwitch(SwitchMaterial sw, @ColorInt int accent) {
        int[] unchecked = new int[] { android.R.attr.state_enabled };
        int thumbOff = colorFor(sw.getThumbTintList(), unchecked, 0xFFECECEC);
        int trackOff = colorFor(sw.getTrackTintList(), unchecked, 0x61FFFFFF);
        int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] {}
        };
        sw.setThumbTintList(new ColorStateList(states, new int[] { accent, thumbOff }));
        sw.setTrackTintList(new ColorStateList(states, new int[] {
                (accent & 0x00FFFFFF) | 0x8A000000, trackOff }));
    }

    /** Checkbox tint: checked → [color]; unchecked keeps the original. */
    private static ColorStateList retintChecked(@Nullable ColorStateList orig, int color) {
        if (orig == null) return ColorStateList.valueOf(color);
        int unchecked = orig.getColorForState(STATE_ENABLED, orig.getDefaultColor());
        return new ColorStateList(
                new int[][] { new int[] { android.R.attr.state_checked }, STATE_NONE },
                new int[] { color, unchecked });
    }

    private static int colorFor(@Nullable ColorStateList csl, int[] state, int fallback) {
        return csl != null ? csl.getColorForState(state, fallback) : fallback;
    }
}
