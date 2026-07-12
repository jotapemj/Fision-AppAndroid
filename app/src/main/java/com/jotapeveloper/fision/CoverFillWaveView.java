package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Custom view for the cover during download. Draws the darkened cover as
 * background and gradually "fills up" with the cover at real brightness from
 * bottom to top, clipped by a smoothly oscillating sine wave (2D water effect).
 *
 * Usage from the adapter:
 *   view.setCover(bitmap);         // same bitmap used by iv_cover
 *   view.setProgress(pct/100f);    // 0..1
 *   view.setVisibility(VISIBLE);   // starts the phase animation
 *   view.setVisibility(GONE);      // stops it (saves CPU when recycled)
 */
public class CoverFillWaveView extends View {

    private @Nullable Bitmap coverBitmap;
    private float progress = 0f;   // 0..1 base level of the wave
    private float phase    = 0f;   // radians, 0..2pi loop

    private final Paint darkPaint   = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint brightPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path  wavePath    = new Path();
    private final Rect  srcRect     = new Rect();
    private final RectF dstRect     = new RectF();

    // Config
    private static final float WAVE_AMP_DP     = 6f;    // wave peak amplitude
    private static final float WAVE_CYCLES     = 0.8f;  // wide wave (single visible peak)
    private static final float PHASE_SPEED     = 3.2f;  // rad/s
    private static final float DARK_BRIGHTNESS = 0.28f; // brightness multiplier for the dark background

    private boolean animating = false;
    private long    lastFrameNanos = 0L;
    private final Runnable frameTick = this::tick;

    public CoverFillWaveView(Context ctx) { this(ctx, null); }
    public CoverFillWaveView(Context ctx, @Nullable AttributeSet attrs) { this(ctx, attrs, 0); }
    public CoverFillWaveView(Context ctx, @Nullable AttributeSet attrs, int def) {
        super(ctx, attrs, def);
        // ColorMatrix that darkens the cover by multiplying RGB by DARK_BRIGHTNESS
        // and slightly lowering saturation so it feels "dimmed".
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.6f);
        ColorMatrix scale = new ColorMatrix(new float[]{
                DARK_BRIGHTNESS, 0, 0, 0, 0,
                0, DARK_BRIGHTNESS, 0, 0, 0,
                0, 0, DARK_BRIGHTNESS, 0, 0,
                0, 0, 0, 1, 0
        });
        cm.postConcat(scale);
        darkPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        // Hardware layer forced to keep clipPath smooth
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setCover(@Nullable Bitmap bm) {
        this.coverBitmap = bm;
        invalidate();
    }

    public void setProgress(float p) {
        if (p < 0f) p = 0f; else if (p > 1f) p = 1f;
        if (this.progress == p) return;
        this.progress = p;
        invalidate();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) startAnim(); else stopAnim();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnim();
    }

    private void startAnim() {
        if (animating) return;
        animating = true;
        lastFrameNanos = System.nanoTime();
        postOnAnimation(frameTick);
    }

    private void stopAnim() {
        animating = false;
        removeCallbacks(frameTick);
    }

    private void tick() {
        if (!animating) return;
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        phase += PHASE_SPEED * dt;
        float twoPi = (float) (Math.PI * 2);
        while (phase > twoPi) phase -= twoPi;
        invalidate();
        postOnAnimation(frameTick);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (coverBitmap == null) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        srcRect.set(0, 0, coverBitmap.getWidth(), coverBitmap.getHeight());
        dstRect.set(0f, 0f, w, h);

        // 1) Darkened cover (base).
        canvas.drawBitmap(coverBitmap, srcRect, dstRect, darkPaint);

        // 2) Wave path: grows from the bottom upwards with progress.
        //    y baseline = h * (1 - progress). Sinusoid around that baseline.
        wavePath.reset();
        float density = getResources().getDisplayMetrics().density;
        float amp = WAVE_AMP_DP * density;
        // When progress=0 the level sits at h+amp (off-screen) so no brightness
        // shows through. When progress=1 it sits at -amp to fully fill even
        // taking the crest of the wave into account.
        float baseline = (1f - progress) * (h + 2f * amp) - amp;
        wavePath.moveTo(0f, h);
        wavePath.lineTo(0f, baseline);
        final int steps = 40;
        float twoPi = (float) (Math.PI * 2);
        for (int i = 0; i <= steps; i++) {
            float x = (float) i / steps * w;
            float t = ((float) i / steps) * WAVE_CYCLES * twoPi;
            float y = baseline + amp * (float) Math.sin(t + phase);
            wavePath.lineTo(x, y);
        }
        wavePath.lineTo(w, h);
        wavePath.close();

        // 3) Clip and draw the cover at real brightness.
        canvas.save();
        canvas.clipPath(wavePath);
        canvas.drawBitmap(coverBitmap, srcRect, dstRect, brightPaint);
        canvas.restore();
    }
}
