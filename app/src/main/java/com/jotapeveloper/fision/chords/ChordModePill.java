package com.jotapeveloper.fision.chords;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.jotapeveloper.fision.R;

/**
 * The chords button grows upwards into a vertical pill: the chords icon
 * stays at the bottom (carousel toggle) and the diagrams entry (chord grid)
 * appears on top. A tap anywhere else folds it back.
 */
public final class ChordModePill {

    public interface Listener {
        void onCarouselToggle();
        void onDiagramsOpen();
    }

    private static final long EXPAND_MS = 260;
    private static final long COLLAPSE_MS = 180;

    private final Activity activity;
    private final MaterialButton anchor;
    private final Drawable diagramsIcon;
    private final Listener listener;
    @Nullable private FrameLayout overlay;
    private boolean closing;

    public ChordModePill(Activity activity, MaterialButton anchor, Drawable diagramsIcon,
                         Listener listener) {
        this.activity = activity;
        this.anchor = anchor;
        this.diagramsIcon = diagramsIcon;
        this.listener = listener;
    }

    public boolean isShown() { return overlay != null; }

    /** Expands the pill over the anchor. [diagramsEnabled] false dims the
     *  top entry (another full view is open). */
    public void show(boolean diagramsEnabled) {
        if (overlay != null) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        float d = activity.getResources().getDisplayMetrics().density;
        int w = anchor.getWidth();
        int collapsedH = anchor.getHeight();
        int expandedH = collapsedH * 2 + (int) (6 * d);
        int[] a = new int[2], c = new int[2];
        anchor.getLocationInWindow(a);
        content.getLocationInWindow(c);
        final int left = a[0] - c[0];
        final int bottom = a[1] - c[1] + collapsedH;

        overlay = new FrameLayout(activity);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismiss());
        content.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout pill = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(w / 2f);
        bg.setStroke((int) Math.max(1, d), Color.argb(70, 255, 255, 255));
        pill.setBackground(bg);
        pill.setClickable(true);
        pill.setElevation(8 * d);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(w, collapsedH);
        plp.leftMargin = left;
        plp.topMargin = bottom - collapsedH;
        overlay.addView(pill, plp);

        int icon = anchor.getIconSize() > 0 ? anchor.getIconSize() : (int) (24 * d);
        // Bottom entry: the current chords icon, same tint.
        ImageView chords = new ImageView(activity);
        Drawable ci = anchor.getIcon();
        if (ci != null && ci.getConstantState() != null) {
            ci = ci.getConstantState().newDrawable().mutate();
            ci.setTintList(anchor.getIconTint());
        }
        chords.setImageDrawable(ci);
        chords.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        chords.setContentDescription(activity.getString(R.string.chord_mode_carousel));
        chords.setPadding((w - icon) / 2, (collapsedH - icon) / 2, (w - icon) / 2, (collapsedH - icon) / 2);
        chords.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            dismiss();
            listener.onCarouselToggle();
        });
        pill.addView(chords, new FrameLayout.LayoutParams(w, collapsedH, Gravity.BOTTOM));

        // Top entry: diagrams.
        ImageView diagrams = new ImageView(activity);
        diagrams.setImageDrawable(diagramsIcon);
        diagrams.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        diagrams.setContentDescription(activity.getString(R.string.chord_mode_diagrams));
        diagrams.setPadding((w - icon) / 2, (collapsedH - icon) / 2, (w - icon) / 2, (collapsedH - icon) / 2);
        diagrams.setAlpha(0f);
        diagrams.setEnabled(diagramsEnabled);
        diagrams.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            dismiss();
            listener.onDiagramsOpen();
        });
        pill.addView(diagrams, new FrameLayout.LayoutParams(w, collapsedH, Gravity.TOP));

        final float diagramsAlpha = diagramsEnabled ? 1f : 0.35f;
        ValueAnimator grow = ValueAnimator.ofFloat(0f, 1f);
        grow.setDuration(EXPAND_MS);
        grow.setInterpolator(new OvershootInterpolator(1.4f));
        grow.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            int h = Math.max(collapsedH, (int) (collapsedH + (expandedH - collapsedH) * f));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
            lp.height = h;
            lp.topMargin = bottom - h;
            pill.setLayoutParams(lp);
            diagrams.setAlpha(Math.max(0f, Math.min(1f, (f - 0.35f) / 0.65f)) * diagramsAlpha);
        });
        anchor.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        grow.start();
        closing = false;
        pill.setTag(new int[] {collapsedH, bottom});
    }

    /** Folds the pill back into the button and removes it. */
    public void dismiss() {
        FrameLayout ov = overlay;
        if (ov == null || closing) return;
        closing = true;
        View pill = ov.getChildAt(0);
        int[] geo = (int[]) pill.getTag();
        int collapsedH = geo[0], bottom = geo[1];
        int from = pill.getHeight();
        View diagrams = ((ViewGroup) pill).getChildAt(1);
        ValueAnimator shrink = ValueAnimator.ofFloat(0f, 1f);
        shrink.setDuration(COLLAPSE_MS);
        shrink.setInterpolator(new DecelerateInterpolator());
        float fromAlpha = diagrams.getAlpha();
        shrink.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            int h = (int) (from + (collapsedH - from) * f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
            lp.height = h;
            lp.topMargin = bottom - h;
            pill.setLayoutParams(lp);
            diagrams.setAlpha(fromAlpha * (1f - Math.min(1f, f * 1.8f)));
        });
        shrink.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                ViewGroup parent = (ViewGroup) ov.getParent();
                if (parent != null) parent.removeView(ov);
                if (overlay == ov) overlay = null;
                closing = false;
            }
        });
        shrink.start();
    }

    /** Removes the pill at once (activity going away). */
    public void dismissNow() {
        if (overlay == null) return;
        ViewGroup parent = (ViewGroup) overlay.getParent();
        if (parent != null) parent.removeView(overlay);
        overlay = null;
        closing = false;
    }
}
