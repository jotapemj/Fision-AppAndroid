package com.jotapeveloper.fision.brand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Background of the welcome screens: the splash focus (dark brand green to
 * black) breathing slowly, its centre drifting with the page scroll. Redraws
 * at 30 fps and only while visible.
 */
public class WelcomeBackdropView extends View {

    // Same stops as the splash and the launcher background.
    private static final int[] COLORS = {0xFF17604F, 0xFF0C3A30, 0xFF03130F, 0xFF000000};
    private static final float[] POS  = {0f, 0.28f, 0.58f, 0.82f};
    private static final long  FRAME_MS = 33;
    private static final float BREATH_S = 6f;      // one breath
    private static final float BREATH_AMP = 0.10f; // radius +/- 10 %
    private static final float FADE_IN_S = 1.6f;   // from black at the start

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private long startNs = -1;
    private boolean running;
    /** Page position (index + offset) for the drift. */
    private float page;

    public WelcomeBackdropView(Context c) { this(c, null); }

    public WelcomeBackdropView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        // Unit gradient placed every frame with the local matrix (no new shader per frame).
        paint.setShader(new RadialGradient(0f, 0f, 1f, COLORS, POS, Shader.TileMode.CLAMP));
    }

    public void setPage(float p) { page = p; invalidate(); }

    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            invalidate();
            Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_MS - 4);
        }
    };

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible && !running) {
            running = true;
            Choreographer.getInstance().postFrameCallback(tick);
        } else if (!visible && running) {
            running = false;
            Choreographer.getInstance().removeFrameCallback(tick);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = System.nanoTime();
        if (startNs < 0) startNs = now;
        float t = (now - startNs) / 1e9f;
        // Starts dark: fades in from black and the breath begins at its smallest.
        float breath = (float) -Math.cos(2 * Math.PI * t / BREATH_S);
        float in = Math.min(1f, t / FADE_IN_S);
        paint.setAlpha(Math.round(255 * in * in * (3f - 2f * in)));
        // Focus around the page icon, drifting a little to the side on each page.
        float cx = w * (0.5f + 0.06f * (float) Math.sin(page * 1.3f));
        float cy = h * 0.36f;
        float r = (float) Math.hypot(Math.max(cx, w - cx), Math.max(cy, h - cy)) * 0.9f
                * (1f + BREATH_AMP * breath);
        m.setScale(r, r);
        m.postTranslate(cx, cy);
        paint.getShader().setLocalMatrix(m);
        c.drawRect(0, 0, w, h, paint);
    }
}
