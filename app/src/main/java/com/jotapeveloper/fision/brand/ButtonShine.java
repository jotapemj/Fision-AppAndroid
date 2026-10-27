package com.jotapeveloper.fision.brand;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Glint over a button, like tilting a shiny trading card: a soft diagonal band
 * in [color] crosses its content from left to right. Only the icon's pixels
 * light up, plus the label's when it is shown (the band is masked by their
 * alpha). Drawn in the button's overlay; between sweeps nothing redraws. The
 * next sweep is asked to a {@link Schedule} each time.
 */
public final class ButtonShine {

    /** Delay before the next sweep in ms ([first] = first of this run), or a negative value to stop. */
    public interface Schedule { long nextDelayMs(boolean first); }

    private static final long SWEEP_MS = 1200;

    private final View host;
    private final ImageView icon;
    @Nullable private final View label;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Band band;
    private Schedule schedule;
    private ValueAnimator anim;
    private boolean attached;

    /** [host] = the button, [icon] = its icon (inside it or the host itself),
     *  [label] = its text, lit too while visible. */
    public ButtonShine(@NonNull View host, @NonNull ImageView icon, @Nullable View label, int color) {
        this.host = host;
        this.icon = icon;
        this.label = label;
        this.band = new Band(color);
    }

    /** Starts the cycle (again from its first delay). */
    public void start(@NonNull Schedule s) {
        stop();
        schedule = s;
        post(true);
    }

    public void stop() {
        handler.removeCallbacksAndMessages(null);
        if (anim != null) { anim.cancel(); anim = null; }
        detach();
        schedule = null;
    }

    private void post(boolean first) {
        if (schedule == null) return;
        long d = schedule.nextDelayMs(first);
        if (d >= 0) handler.postDelayed(this::fire, d);
    }

    private void fire() {
        // Hidden (search open, other tab, covered): skip this one and wait for the next.
        if (host.isShown() && host.getWidth() > 0 && host.isEnabled()
                && icon.getDrawable() != null) sweep();
        post(false);
    }

    private void sweep() {
        attach();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SWEEP_MS);
        anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        anim.addUpdateListener(a -> band.setProgress((float) a.getAnimatedValue()));
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { detach(); }
        });
        anim.start();
    }

    private void attach() {
        if (attached) return;
        band.icon = icon.getDrawable();
        band.imageMatrix.set(icon.getImageMatrix());
        float[] io = offsetInHost(icon);
        band.iconX = io[0] + icon.getPaddingLeft();
        band.iconY = io[1] + icon.getPaddingTop();
        // Sweep span: the icon, or icon + label.
        band.spanL = io[0];
        band.spanR = io[0] + icon.getWidth();
        band.label = label != null && label.getVisibility() == View.VISIBLE ? label : null;
        if (band.label != null) {
            float[] lo = offsetInHost(label);
            band.labelX = lo[0];
            band.labelY = lo[1];
            band.spanL = Math.min(band.spanL, lo[0]);
            band.spanR = Math.max(band.spanR, lo[0] + label.getWidth());
        }
        band.setBounds(0, 0, host.getWidth(), host.getHeight());
        host.getOverlay().add(band);
        attached = true;
    }

    private void detach() {
        if (!attached) return;
        host.getOverlay().remove(band);
        band.icon = null;
        band.label = null;
        attached = false;
    }

    /** Position of [v] inside the host. */
    private float[] offsetInHost(View v) {
        float x = 0f, y = 0f;
        for (View p = v; p != host && p != null; p = (View) p.getParent()) {
            x += p.getLeft();
            y += p.getTop();
        }
        return new float[]{x, y};
    }

    /** The light band masked by the icon (and label); position 0..1 from the
     *  left of the content to its right. */
    private static final class Band extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        final Matrix imageMatrix = new Matrix();
        Drawable icon;
        View label;
        float iconX, iconY, labelX, labelY, spanL, spanR;
        private float progress;
        private int alpha = 0xFF;

        Band(int color) {
            int rgb = color & 0x00FFFFFF;
            // Unit band along x: soft edges, solid accent core.
            paint.setShader(new LinearGradient(0f, 0f, 1f, 0f,
                    new int[]{rgb, 0x66000000 | rgb, 0xFF000000 | rgb, 0x66000000 | rgb, rgb},
                    new float[]{0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        }

        void setProgress(float p) { progress = p; invalidateSelf(); }

        @Override public void draw(@NonNull Canvas c) {
            Drawable d = icon;
            float w = getBounds().width(), h = getBounds().height();
            if (d == null || w <= 0 || h <= 0) return;
            int layer = c.saveLayer(0, 0, w, h, null);
            // Mask: the icon exactly where its ImageView draws it, and the label.
            c.save();
            c.translate(iconX, iconY);
            c.concat(imageMatrix);
            d.draw(c);
            c.restore();
            if (label != null) {
                c.save();
                c.translate(labelX, labelY);
                label.draw(c);
                c.restore();
            }
            float span = spanR - spanL;
            float bandW = span * 0.55f;
            float x = spanL - bandW - h * 0.4f + progress * (span + 2 * bandW + h * 0.8f);
            m.setScale(bandW, 1f);
            m.postSkew(-0.4f, 0f);   // tilted like a glare
            m.postTranslate(x, 0f);
            paint.getShader().setLocalMatrix(m);
            paint.setAlpha(alpha);
            c.drawRect(0, 0, w, h, paint);
            c.restoreToCount(layer);
        }

        @Override public void setAlpha(int a) { alpha = a; }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
