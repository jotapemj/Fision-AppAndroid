package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

/**
 * Ambient nebula background. Renders a small set of large blurred colour blobs
 * that orbit the phone frame perimeter and pulse on demand. Meant to sit
 * behind the whole player UI as a subtle, opt-in visual layer.
 *
 * Rendering strategy: each blob is a RadialGradient (colour → transparent)
 * drawn with the ADD blend so overlapping regions accumulate towards white,
 * yielding an additive nebula look. A dark frosted tint is painted on top of
 * the blobs to soften the palette and keep the whole layer subdued behind the
 * UI stems. Frame updates run through Choreographer for vsync alignment.
 *
 * Blob 0 = the vibrant / primary colour: smaller radius, faster orbit, larger
 * pulse gain. Blobs 1..2 = larger, slower ambient halos.
 */
public class NebulaBackgroundView extends View {

    private static final int BLOB_COUNT = 3;

    /** Frosted-dark tint layered on top of the blobs. */
    private static final int DARK_TINT_COLOR = 0x66000000;

    /** Base colours: fallback to a neutral primary-blue palette. */
    private final int[] palette = new int[] {
            0xFF2D6BB0, 0xFF3AA3B3, 0xFF6C4CB0
    };

    /** Per-blob orbital phase (rad), angular frequency (Hz) and orbit radius
     *  scale so each blob traces a distinct path. */
    private final float[] phaseA  = new float[BLOB_COUNT];
    private final float[] angFreq = new float[BLOB_COUNT];
    private final float[] rxScale = new float[BLOB_COUNT];
    private final float[] ryScale = new float[BLOB_COUNT];

    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 0..1; decays each frame. Boosted by pulse(). */
    private float pulse = 0f;
    private static final float PULSE_FLOOR = 0.0f;

    private long startNanos = 0L;
    private boolean running = false;

    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public NebulaBackgroundView(Context ctx) { super(ctx); init(); }
    public NebulaBackgroundView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public NebulaBackgroundView(Context ctx, AttributeSet a, int s) { super(ctx, a, s); init(); }

    private void init() {
        // Additive blending so overlapping blobs sum toward brightness
        blobPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        java.util.Random rnd = new java.util.Random(1337);
        // Evenly-distributed base phases (2π/N spacing) plus a small jitter so
        // blobs never cluster on view attach, then per-blob orbit scales.
        for (int i = 0; i < BLOB_COUNT; i++) {
            float base = (float) (i * (2 * Math.PI / BLOB_COUNT));
            phaseA[i] = base + rnd.nextFloat() * 0.6f;
            if (i == 0) {
                // Primary blob: faster orbital motion
                angFreq[i] = 0.06f + rnd.nextFloat() * 0.05f;
            } else {
                angFreq[i] = 0.014f + rnd.nextFloat() * 0.020f;
            }
            // Distinct orbit ellipses so blobs do not all sweep the same arc.
            rxScale[i] = 0.55f + rnd.nextFloat() * 0.20f;
            ryScale[i] = 0.50f + rnd.nextFloat() * 0.20f;
        }
        setClickable(false);
        setFocusable(false);
    }

    /** Replaces the palette. Accepts 1..N colours; falls back to defaults when null. */
    public void setPalette(int[] colors) {
        if (colors == null || colors.length == 0) return;
        for (int i = 0; i < BLOB_COUNT; i++) {
            palette[i] = colors[i % colors.length];
        }
        invalidate();
    }

    /** Injects a beat pulse. Intensity 0..1 (soft on 1/3, strong on 2/4). */
    public void pulse(float intensity) {
        if (intensity < 0f) intensity = 0f;
        if (intensity > 1f) intensity = 1f;
        if (intensity > pulse) pulse = intensity;
    }

    /** Fades the whole view in / out. */
    public void setNebulaEnabled(boolean enabled, boolean animate) {
        animate().cancel();
        if (enabled) {
            if (getVisibility() != VISIBLE) {
                setAlpha(0f);
                setVisibility(VISIBLE);
            }
            if (animate) animate().alpha(1f).setDuration(500).start();
            else setAlpha(1f);
            start();
        } else {
            if (animate) {
                animate().alpha(0f).setDuration(400).withEndAction(() -> {
                    setVisibility(GONE);
                    stop();
                }).start();
            } else {
                setAlpha(0f);
                setVisibility(GONE);
                stop();
            }
        }
    }

    private void start() {
        if (running) return;
        running = true;
        startNanos = System.nanoTime();
        Choreographer.getInstance().postFrameCallback(frameCb);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCb);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) start();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float t = (System.nanoTime() - startNanos) / 1_000_000_000f;

        // Decay pulse toward floor (~200ms half-life)
        float dt = 1f / 60f;
        pulse = PULSE_FLOOR + (pulse - PULSE_FLOOR) * (float) Math.exp(-dt * 3.5f);
        if (pulse < 0.005f) pulse = 0f;

        // Draw into a transparent offscreen layer so ADD blends between blobs
        // work without the black backdrop swallowing them
        int layer = canvas.saveLayer(0, 0, w, h, null);

        float minDim = Math.min(w, h);
        // Per-blob elliptical orbits centred on the screen. Each blob owns a
        // slightly different (rx, ry) so the arcs never overlap perfectly and
        // there is always at least one bright centre near the frame.
        float ox = w * 0.5f;
        float oy = h * 0.5f;

        for (int i = 0; i < BLOB_COUNT; i++) {
            float angle = (float) (2 * Math.PI * angFreq[i] * t + phaseA[i]);
            float rx = w * rxScale[i];
            float ry = h * ryScale[i];
            float cx = ox + rx * (float) Math.cos(angle);
            float cy = oy + ry * (float) Math.sin(angle);

            float baseR;
            int alpha;
            if (i == 0) {
                // Primary vibrant blob: smaller, more reactive to the beat
                baseR = minDim * 0.30f * (1f + pulse * 0.40f);
                alpha = (int) (170 + pulse * 70);
            } else {
                // Ambient halos: larger, gentler pulse
                baseR = minDim * 0.55f * (1f + pulse * 0.10f);
                alpha = (int) (135 + pulse * 60);
            }
            if (alpha > 230) alpha = 230;

            int col  = palette[i];
            int core = (col & 0x00FFFFFF) | (alpha << 24);
            int edge =  col & 0x00FFFFFF;

            RadialGradient rg = new RadialGradient(
                    cx, cy, baseR,
                    new int[] { core, core, edge },
                    new float[] { 0f, 0.20f, 1f },
                    Shader.TileMode.CLAMP);
            blobPaint.setShader(rg);
            canvas.drawCircle(cx, cy, baseR, blobPaint);
        }

        canvas.restoreToCount(layer);

        // Frosted-dark tint over the whole nebula. Softens saturation so the
        // UI on top always stays readable; also gives that "smoked glass" look.
        canvas.drawColor(DARK_TINT_COLOR);
    }
}
