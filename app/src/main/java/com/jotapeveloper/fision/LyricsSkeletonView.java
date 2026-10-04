package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.core.content.ContextCompat;

/**
 * Skeleton view rendered while the server transcribes lyrics.
 * Draws N horizontal rounded bars with a diagonal shimmer sweeping from
 * left to right on a repeating cycle. Only redraws while attached and
 * visible; the loop is vsync-aligned via Choreographer.
 */
public class LyricsSkeletonView extends View {

    private static final int   LINE_COUNT   = 6;
    private static final long  SWEEP_PERIOD = 2000L;   // ms per full sweep
    private static final float CORNER_DP    = 6f;
    private static final float LINE_HEIGHT_DP = 14f;
    private static final float LINE_GAP_DP    = 14f;
    // Extra travel outside the viewport before and after each sweep. Keeps
    // the shimmer band offscreen long enough that the loop reset is not
    // perceived as a snap.
    private static final float DWELL_BAND_MULT = 2f;

    private final Paint basePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect         = new RectF();

    private final float[] widthFactors = { 0.85f, 0.72f, 0.90f, 0.65f, 0.80f, 0.60f };

    private long startNanos;
    private boolean running = false;
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            if (startNanos == 0) startNanos = frameTimeNanos;
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public LyricsSkeletonView(Context ctx) { super(ctx); init(ctx); }
    public LyricsSkeletonView(Context ctx, AttributeSet a) { super(ctx, a); init(ctx); }
    public LyricsSkeletonView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(ctx); }

    private void init(Context ctx) {
        basePaint.setColor(ContextCompat.getColor(ctx, R.color.lyrics_skeleton_base));
        shimmerPaint.setColor(Color.WHITE);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    /** Animates only while really visible: own visibility, every ancestor
     *  and the window. A hidden lyrics overlay leaves it idle. */
    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) start(); else stop();
    }

    private void start() {
        if (running) return;
        running = true;
        startNanos = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
    }

    @Override protected void onDraw(Canvas canvas) {
        float density  = getResources().getDisplayMetrics().density;
        float corner   = CORNER_DP * density;
        float lineH    = LINE_HEIGHT_DP * density;
        float gap      = LINE_GAP_DP * density;
        float w        = getWidth();
        float startY   = 0f;

        // Base bars.
        for (int i = 0; i < LINE_COUNT; i++) {
            float lineW = w * widthFactors[i % widthFactors.length];
            float left  = (w - lineW) * 0.5f;
            float top   = startY + i * (lineH + gap);
            rect.set(left, top, left + lineW, top + lineH);
            canvas.drawRoundRect(rect, corner, corner, basePaint);
        }

        // Diagonal shimmer band.
        long now    = System.nanoTime();
        float t     = ((now - startNanos) / 1_000_000f % SWEEP_PERIOD) / (float) SWEEP_PERIOD;
        // Ease-in-out cubic on t so entry and exit accelerate smoothly rather
        // than snapping the band into and out of the viewport.
        float eased = t < 0.5f
                ? 4f * t * t * t
                : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
        float band  = w * 0.55f;
        // Add a dwell margin on both sides so the band stays fully offscreen
        // between passes, hiding the loop reset behind the pause.
        float pad    = band * DWELL_BAND_MULT;
        float travel = w + band + pad * 2f;
        float x      = -band - pad + travel * eased;

        int base = ContextCompat.getColor(getContext(), R.color.lyrics_skeleton_shimmer);
        int[] colors = new int[] { 0x00000000, base, 0x00000000 };
        float[] stops = new float[] { 0f, 0.5f, 1f };
        LinearGradient shader = new LinearGradient(
                x, 0f, x + band, getHeight() * 1f,
                colors, stops, Shader.TileMode.CLAMP);
        shimmerPaint.setShader(shader);

        // Redraw the bars with the shimmer paint clipped to their shape.
        for (int i = 0; i < LINE_COUNT; i++) {
            float lineW = w * widthFactors[i % widthFactors.length];
            float left  = (w - lineW) * 0.5f;
            float top   = startY + i * (lineH + gap);
            rect.set(left, top, left + lineW, top + lineH);
            canvas.drawRoundRect(rect, corner, corner, shimmerPaint);
        }
    }
}
