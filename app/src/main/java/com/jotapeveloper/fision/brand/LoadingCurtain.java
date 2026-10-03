package com.jotapeveloper.fision.brand;

import android.app.Activity;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * Black curtain over an activity while it loads, with the Fision loading
 * animation. The curtain and the mark are drawn by {@link FisionLoadingSurface}
 * on its own render thread, so they keep moving while the main thread is busy.
 * {@link #reveal(Runnable)} closes the ring, plays the fission of the mark and
 * fades the curtain out, running the given action as the fade starts so
 * whatever animates on reveal is seen. A very fast load just fades out.
 */
public final class LoadingCurtain {

    private static final int NUCLEUS_DP = 64;
    private static final float FADE_IN_MS = 220f;

    @Nullable private FrameLayout root;
    @Nullable private FisionLoadingSurface surface;
    private boolean revealing;

    private LoadingCurtain(Activity activity, boolean fadeIn, @Nullable Runnable onCovered) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        root = new FrameLayout(activity);
        root.setClickable(true);              // blocks touches on the UI below
        root.setFocusable(true);
        // Opening: black at once, even before the surface draws its first
        // frame. Song change: the surface fades the curtain in by itself.
        if (!fadeIn) root.setBackgroundColor(Color.BLACK);
        float nucleus = NUCLEUS_DP * activity.getResources().getDisplayMetrics().density;
        surface = new FisionLoadingSurface(activity, nucleus, fadeIn ? FADE_IN_MS : 0f, onCovered);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Covers [activity] at once (call right after setContentView). */
    public static LoadingCurtain show(Activity activity) {
        return new LoadingCurtain(activity, false, null);
    }

    /** Fades the curtain in over the visible UI and runs [onCovered] once it
     *  is fully opaque, so the work behind it is never seen. */
    public static LoadingCurtain showFading(Activity activity, Runnable onCovered) {
        return new LoadingCurtain(activity, true, onCovered);
    }

    public boolean isShowing() { return root != null; }

    /** Closes the ring, plays the fission and fades out; [onRevealStart] runs
     *  as the fade starts. */
    public void reveal(@Nullable Runnable onRevealStart) {
        reveal(onRevealStart, true);
    }

    /** With [resolve] false the spinner does not close: quick fade out only
     *  (nothing new was loaded, e.g. back into the player from the mini-player). */
    public void reveal(@Nullable Runnable onRevealStart, boolean resolve) {
        if (root == null || surface == null || revealing) {
            if (onRevealStart != null) onRevealStart.run();
            return;
        }
        revealing = true;
        surface.finish(() -> {
            // The surface is fully opaque at this point: drop the view-side
            // black so only the surface fade is seen, and let touches through.
            if (root != null) {
                root.setBackground(null);
                root.setClickable(false);
            }
            if (onRevealStart != null) onRevealStart.run();
        }, this::dismissNow, resolve);
    }

    /** Removes the curtain at once (activity going away). */
    public void dismissNow() {
        if (root == null) return;
        ViewGroup parent = (ViewGroup) root.getParent();
        if (parent != null) parent.removeView(root);
        root = null;
        surface = null;
    }
}
