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

    /** Static bottom anchor blob: sits below the bottom edge so only its top
     *  arc bleeds into the view, lighting the playback control row like a
     *  footlight. Shape is a horizontally-stretched ellipse with a long fade
     *  that reaches up to the transport buttons. */
    private static final float ANCHOR_Y_FRACTION = 1.02f;   // vertical centre (below edge)
    private static final float ANCHOR_WIDTH_FRAC = 1.30f;   // horizontal reach
    private static final float ANCHOR_SQUASH     = 0.36f;   // vertical squash
    private int anchorColor = 0xFF27B397;                    // colorPrimary default

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

    /** Palette handshake: while false, onDraw skips every blob so the view
     *  never paints the default colours. When the first real palette lands,
     *  the pending fade-in is committed. */
    private boolean paletteApplied = false;
    private boolean pendingFadeIn  = false;

    /** Views that repaint a region of the nebula on top of opaque content
     *  (e.g. edge fades over a SurfaceView). Invalidated with this view. */
    private final java.util.List<View> mirrors = new java.util.ArrayList<>();

    /** Redraw rate. The blobs drift slowly, so 30 fps reads as smooth while
     *  costing a quarter of a 120 Hz panel. */
    private static final long FRAME_MS = 1000L / 30L;
    /** The delayed callback lands on the first vsync after the delay; the
     *  margin keeps it from slipping one vsync past the frame period. */
    private static final long FRAME_DELAY_MS = FRAME_MS - 4L;
    /** Pulse decay rate per second (~100 ms half-life). */
    private static final float PULSE_DECAY_PER_SEC = 7f;
    private long lastFrameNanos = 0L;

    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            // Decay pulse toward floor with the real elapsed time, once per
            // frame so extra draws from mirrors do not speed it up.
            float dt = lastFrameNanos == 0L ? FRAME_MS / 1000f
                    : Math.min(0.1f, (frameTimeNanos - lastFrameNanos) / 1_000_000_000f);
            lastFrameNanos = frameTimeNanos;
            pulse = PULSE_FLOOR + (pulse - PULSE_FLOOR) * (float) Math.exp(-dt * PULSE_DECAY_PER_SEC);
            if (pulse < 0.005f) pulse = 0f;
            invalidateWithMirrors();
            Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_DELAY_MS);
        }
    };

    /** Registers a view that draws part of the nebula via {@link #drawNebula(Canvas)}. */
    public void addMirror(View v) {
        if (v != null && !mirrors.contains(v)) mirrors.add(v);
    }

    public void removeMirror(View v) {
        mirrors.remove(v);
    }

    private void invalidateWithMirrors() {
        invalidate();
        for (int i = 0; i < mirrors.size(); i++) mirrors.get(i).invalidate();
    }

    /** Opacity factor of the orbiting blobs (the static anchor is not
     *  affected). Faded to 0 behind opaque views such as the 3D scene. */
    private float orbitAlpha = 1f;
    private android.animation.ValueAnimator orbitAnim;

    /** Fades the orbiting blobs in / out; the bottom anchor glow stays. */
    public void setOrbitBlobsVisible(boolean visible, boolean animate) {
        float target = visible ? 1f : 0f;
        if (orbitAnim != null) orbitAnim.cancel();
        if (!animate || orbitAlpha == target) {
            orbitAlpha = target;
            invalidateWithMirrors();
            return;
        }
        orbitAnim = android.animation.ValueAnimator.ofFloat(orbitAlpha, target);
        orbitAnim.setDuration(500L);
        orbitAnim.addUpdateListener(an -> {
            orbitAlpha = (float) an.getAnimatedValue();
            invalidateWithMirrors();
        });
        orbitAnim.start();
    }

    /** Effective opacity of the nebula on screen (0 when hidden). */
    public float getEffectiveAlpha() {
        return getVisibility() == VISIBLE ? getAlpha() : 0f;
    }

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
        setPalette(colors, colors[0]);
    }

    /** Blob colours plus an explicit colour for the static bottom anchor
     *  (the glow behind the transport controls). */
    public void setPalette(int[] colors, int anchor) {
        if (colors == null || colors.length == 0) return;
        for (int i = 0; i < BLOB_COUNT; i++) {
            palette[i] = colors[i % colors.length];
        }
        anchorColor = anchor;
        boolean firstApply = !paletteApplied;
        paletteApplied = true;
        if (firstApply && pendingFadeIn) {
            pendingFadeIn = false;
            animate().cancel();
            animate().alpha(1f).setDuration(500).start();
        }
        invalidateWithMirrors();
    }

    /** Resets the palette handshake so the next {@link #setPalette(int[])}
     *  call is treated as a fresh first-apply. Meant to be invoked on
     *  song change so the new artwork drives a clean fade-in instead of a
     *  flash between the previous song's colours and the new ones. */
    public void resetPaletteFade() {
        paletteApplied = false;
        pendingFadeIn = true;
        animate().cancel();
        setAlpha(0f);
        invalidateWithMirrors();
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
            wanted = true;
            if (getVisibility() != VISIBLE) {
                setAlpha(0f);
                setVisibility(VISIBLE);
            }
            // If the palette is not yet known, defer the fade-in until
            // setPalette() arrives so the user never sees the default colours.
            if (paletteApplied) {
                if (animate) animate().alpha(1f).setDuration(500).start();
                else setAlpha(1f);
            } else {
                pendingFadeIn = true;
                setAlpha(0f);
            }
            start();
        } else {
            pendingFadeIn = false;
            if (animate) {
                animate().alpha(0f).setDuration(400).withEndAction(() -> {
                    wanted = false;
                    setVisibility(GONE);
                    stop();
                    invalidateWithMirrors();
                }).start();
            } else {
                wanted = false;
                setAlpha(0f);
                setVisibility(GONE);
                stop();
                invalidateWithMirrors();
            }
        }
    }

    private void start() {
        if (running || !wanted || !aggregatedVisible) return;
        running = true;
        // Keep the orbit clock across stop/start (window hidden and shown
        // again) so the blobs do not jump back to their initial positions.
        if (startNanos == 0L) startNanos = System.nanoTime();
        lastFrameNanos = 0L;
        Choreographer.getInstance().postFrameCallback(frameCb);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCb);
    }

    /** Enabled by the user (the frame loop also needs the view on screen). */
    private boolean wanted = false;
    private boolean aggregatedVisible = false;

    /** Own visibility, every ancestor and the window: the loop stops while
     *  the player is in the background or the screen is off. */
    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        aggregatedVisible = isVisible;
        if (isVisible) start(); else stop();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        if (orbitAnim != null) orbitAnim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        drawNebula(canvas);
    }

    /** Paints the nebula in this view's coordinate space. Stateless per call,
     *  so mirrors can reuse it on their own canvas (translated accordingly). */
    public void drawNebula(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        // Skip every draw until the real palette lands, so the view never
        // paints its fallback colours even for a single frame.
        if (!paletteApplied) return;

        float t = (System.nanoTime() - startNanos) / 1_000_000_000f;

        // Draw into a transparent offscreen layer so ADD blends between blobs
        // work without the black backdrop swallowing them
        int layer = canvas.saveLayer(0, 0, w, h, null);

        float minDim = Math.min(w, h);
        // Per-blob elliptical orbits centred on the screen. Each blob owns a
        // slightly different (rx, ry) so the arcs never overlap perfectly and
        // there is always at least one bright centre near the frame.
        float ox = w * 0.5f;
        float oy = h * 0.5f;

        for (int i = 0; i < BLOB_COUNT && orbitAlpha > 0f; i++) {
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
            alpha = Math.round(alpha * orbitAlpha);

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

        // Static anchor blob: horizontal ellipse near the bottom edge. Pulses
        // with the beat but never moves — reads as a stage-light glow behind
        // the playback control row.
        {
            float ax = w * 0.5f;
            float ay = h * ANCHOR_Y_FRACTION;
            float radius = w * ANCHOR_WIDTH_FRAC * (1f + pulse * 0.15f);
            int aAlpha = (int) (160 + pulse * 80);
            if (aAlpha > 240) aAlpha = 240;
            int aCore = (anchorColor & 0x00FFFFFF) | (aAlpha << 24);
            int aEdge =  anchorColor & 0x00FFFFFF;

            // Long-fade profile: no plateau, colour eases from core to edge in
            // one smooth curve so the footlight blooms with a soft halo.
            RadialGradient ag = new RadialGradient(
                    ax, ay, radius,
                    new int[] { aCore, aEdge },
                    new float[] { 0f, 1f },
                    Shader.TileMode.CLAMP);
            blobPaint.setShader(ag);
            // Squash vertically to draw a horizontal ellipse without switching
            // shader math. Scaling is applied around (ax, ay) so the light
            // stays anchored to the bottom row.
            canvas.save();
            canvas.scale(1f, ANCHOR_SQUASH, ax, ay);
            canvas.drawCircle(ax, ay, radius, blobPaint);
            canvas.restore();
        }

        canvas.restoreToCount(layer);

        // Frosted-dark tint over the whole nebula. Softens saturation so the
        // UI on top always stays readable; also gives that "smoked glass" look.
        canvas.drawColor(DARK_TINT_COLOR);
    }
}
