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
    private static final long  SWEEP_PERIOD = 1400L;   // ms per full sweep
    private static final float CORNER_DP    = 6f;
    private static final float LINE_HEIGHT_DP = 14f;
    private static final float LINE_GAP_DP    = 14f;

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

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) start(); else stop();
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
        float band  = w * 0.55f;
        float travel = w + band;
        float x     = -band + travel * t;

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
