package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Custom view for the cover during download. Draws the darkened cover as
 * background and gradually "fills up" with the cover at real brightness from
 * bottom to top, bounded by a smoothly oscillating sine wave (2D water effect).
 * The level eases towards the reported progress, so coarse percentage steps
 * rise continuously, and the wave edge is antialiased (shader fill, no clip).
 *
 * Usage from the adapter:
 *   view.setCover(bitmap);         // same bitmap used by iv_cover
 *   view.setProgress(pct/100f);    // 0..1
 *   view.setVisibility(VISIBLE);   // starts the phase animation
 *   view.setVisibility(GONE);      // stops it (saves CPU when recycled)
 */
public class CoverFillWaveView extends View {

    private @Nullable Bitmap coverBitmap;
    private float progress = 0f;   // 0..1 reported level
    private float shown    = 0f;   // 0..1 drawn level, eased towards progress
    private float phase    = 0f;   // radians, 0..2pi loop

    private final Paint darkPaint   = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint brightPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Path  wavePath    = new Path();
    private final Rect  srcRect     = new Rect();
    private final RectF dstRect     = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private @Nullable BitmapShader shader;
    private int shaderW = -1, shaderH = -1;

    // Config
    private static final float WAVE_AMP_DP     = 6f;    // wave peak amplitude
    private static final float WAVE_CYCLES     = 0.8f;  // wide wave (single visible peak)
    private static final float PHASE_SPEED     = 3.2f;  // rad/s
    private static final float DARK_BRIGHTNESS = 0.28f; // brightness multiplier for the dark background
    private static final float LEVEL_EASE      = 4f;    // 1/s, level catch-up rate

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
    }

    public void setCover(@Nullable Bitmap bm) {
        if (bm != coverBitmap) {
            shader = null;
            brightPaint.setShader(null);
        }
        this.coverBitmap = bm;
        invalidate();
    }

    public void setProgress(float p) {
        if (p < 0f) p = 0f; else if (p > 1f) p = 1f;
        if (this.progress == p) return;
        // A drop means a new download (recycled row): no easing down.
        if (p < shown || !animating) shown = p;
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
        shown += (progress - shown) * (1f - (float) Math.exp(-LEVEL_EASE * dt));
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

        // 2) Wave path: grows from the bottom upwards with the level.
        //    y baseline = h * (1 - level). Sinusoid around that baseline.
        wavePath.reset();
        float density = getResources().getDisplayMetrics().density;
        float amp = WAVE_AMP_DP * density;
        // At level 0 the baseline sits at h+amp (off-screen) so no brightness
        // shows through. At level 1 it sits at -amp to fully fill even
        // taking the crest of the wave into account.
        float baseline = (1f - shown) * (h + 2f * amp) - amp;
        wavePath.moveTo(0f, h);
        wavePath.lineTo(0f, baseline);
        final int steps = 48;
        float twoPi = (float) (Math.PI * 2);
        for (int i = 0; i <= steps; i++) {
            float x = (float) i / steps * w;
            float t = ((float) i / steps) * WAVE_CYCLES * twoPi;
            float y = baseline + amp * (float) Math.sin(t + phase);
            wavePath.lineTo(x, y);
        }
        wavePath.lineTo(w, h);
        wavePath.close();

        // 3) Cover at real brightness filling the wave (antialiased edge).
        if (shader == null) {
            shader = new BitmapShader(coverBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            brightPaint.setShader(shader);
            shaderW = -1;
        }
        if (shaderW != w || shaderH != h) {
            shaderMatrix.setRectToRect(new RectF(srcRect), dstRect, Matrix.ScaleToFit.FILL);
            shader.setLocalMatrix(shaderMatrix);
            shaderW = w;
            shaderH = h;
        }
        canvas.drawPath(wavePath, brightPaint);
    }
}
