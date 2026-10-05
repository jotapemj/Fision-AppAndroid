package com.jotapeveloper.fision.brand;

import static com.jotapeveloper.fision.brand.FisionMotion.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.Nullable;

/**
 * Loading curtain drawn on its own render thread: a SurfaceView composited by
 * the system, so the animation keeps running while the main thread is busy
 * inflating or rebuilding the player. It draws the black curtain and the mark:
 * the nucleus fades in and spins until {@link #finish}, then the ring closes
 * at its starting point, the fission completes the mark and the curtain fades
 * out. All callbacks run on the main thread.
 */
public class FisionLoadingSurface extends SurfaceView implements SurfaceHolder.Callback {

    private static final float FADE_OUT_MS = 280f;
    private static final float QUICK_FADE_MS = 180f;  // fade out without closing (standard spinner)
    private static final float HOLD_MS = 250f;        // complete mark before the fade
    private static final float QUICK_MS = 250f;       // finish before this: fade out only
    private static final float END_SPEED = 2f;        // closing + fission speed after loading
    private static final float SPEED_RAMP_MS = 150f;  // 1x -> END_SPEED transition

    private final Handler main = new Handler(Looper.getMainLooper());
    private final FisionRenderer renderer = new FisionRenderer();
    private final FisionRenderer.State state = new FisionRenderer.State();
    private final Paint curtain = new Paint();
    private final float[] arc = new float[2];
    private final float nucleusPx;
    /** Logo-unit bounds of the settled mark (with margin), for the fade bitmap. */
    private static final float MARK_MIN = -20f, MARK_MAX = 532f;
    private final Paint markPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    @Nullable private Bitmap markBitmap;      // render thread only
    @Nullable private Canvas markCanvas;

    // Shared with the render thread.
    private final Object lock = new Object();
    private long startMs = -1;
    private float fadeInMs;
    @Nullable private Runnable onCovered;
    private float readyAt = -1f;
    @Nullable private ClosePlan plan;
    private boolean quick;
    @Nullable private Runnable onRevealStart, onGone;
    private long fadeOutStart = -1;
    private boolean coveredFired, revealFired, goneFired;

    @Nullable private Thread thread;
    private volatile boolean running;

    /**
     * @param fadeInMs 0 to start opaque, otherwise the curtain fades in and
     *                 [covered] runs once it is fully opaque.
     */
    public FisionLoadingSurface(Context c, float nucleusPx, float fadeInMs,
                                @Nullable Runnable covered) {
        this(c, nucleusPx, fadeInMs, covered, false);
    }

    /**
     * @param embedded false: composited over the whole window (curtain).
     *                 true: composited under the window, above other
     *                 SurfaceViews, so the views drawn after it (controls,
     *                 fades) stay on top: a loading panel behind the UI.
     */
    public FisionLoadingSurface(Context c, float nucleusPx, float fadeInMs,
                                @Nullable Runnable covered, boolean embedded) {
        super(c);
        this.nucleusPx = nucleusPx;
        this.fadeInMs = fadeInMs;
        this.onCovered = covered;
        coveredFired = covered == null;
        if (embedded) setZOrderMediaOverlay(true);
        else setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        curtain.setColor(Color.BLACK);
    }

    /** Ms since the animation started (0 before the first frame). */
    public float elapsed() {
        synchronized (lock) {
            return startMs < 0 ? 0f : SystemClock.uptimeMillis() - startMs;
        }
    }

    /**
     * Ends the loading: closes the ring (after MIN_SPIN), plays the fission,
     * holds the complete mark and fades out. A very fast load skips the mark
     * and only fades out. [revealStart] runs as the fade starts, [gone] when
     * the curtain is fully transparent.
     */
    public void finish(@Nullable Runnable revealStart, @Nullable Runnable gone) {
        finish(revealStart, gone, true);
    }

    /** Same as {@link #finish(Runnable, Runnable)}; with [resolve] false the
     *  spinner behaves as a standard one: no closing or fission, just a quick
     *  fade out (used when nothing new was loaded). */
    public void finish(@Nullable Runnable revealStart, @Nullable Runnable gone, boolean resolve) {
        synchronized (lock) {
            if (readyAt >= 0f || quick) return;
            onRevealStart = revealStart;
            onGone = gone;
            float t = startMs < 0 ? 0f : SystemClock.uptimeMillis() - startMs;
            if (!resolve || t < QUICK_MS) {
                quick = true;
            } else {
                readyAt = Math.max(t, MIN_SPIN);
                plan = FisionMotion.closePlan(readyAt);
            }
        }
    }

    // ---- Surface lifecycle ----

    @Override public void surfaceCreated(SurfaceHolder holder) {
        running = true;
        thread = new Thread(this::renderLoop, "fision-loading");
        thread.start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- Render thread ----

    private void renderLoop() {
        SurfaceHolder holder = getHolder();
        while (running) {
            Canvas c;
            try {
                c = holder.lockHardwareCanvas();
            } catch (Exception e) {
                c = null;
            }
            if (c == null) {
                SystemClock.sleep(8);
                continue;
            }
            boolean finished;
            try {
                finished = drawFrame(c);
            } finally {
                try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) {}
            }
            if (finished) running = false;
        }
    }

    /** Draws one frame (render thread). Returns true once fully faded out. */
    private boolean drawFrame(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float curtainA, markA;
        boolean gone = false;
        synchronized (lock) {
            if (startMs < 0) startMs = now;
            float t = now - startMs;

            // Curtain fade-in (song change) and fade-out (reveal).
            curtainA = fadeInMs > 0f ? c01(t / fadeInMs) : 1f;
            if (!coveredFired && curtainA >= 1f) {
                coveredFired = true;
                postMain(onCovered);
                onCovered = null;
            }

            state.alpha = c01(t / APPEAR);
            state.tf = NO_TF;
            if (plan == null || t < readyAt) {
                FisionMotion.steady(t, arc);
            } else {
                // Closing and fission run at END_SPEED, reached smoothly.
                float local = warp(t - readyAt);
                float closeEnd = plan.dur;
                state.tf = local - closeEnd;  // fission timeline (negative while closing)
                if (local < closeEnd) {
                    FisionMotion.closeArc(plan, local, arc);
                } else {
                    arc[1] = plan.t1;
                    arc[0] = plan.t1 + 1f;
                }
            }
            state.head = arc[0];
            state.tail = arc[1];

            boolean markDone = quick || (plan != null && state.tf >= SETTLED + HOLD_MS);
            if (markDone && fadeOutStart < 0) {
                fadeOutStart = now;
                if (!revealFired) {
                    revealFired = true;
                    postMain(onRevealStart);
                }
            }
            if (fadeOutStart >= 0) {
                float out = c01((now - fadeOutStart) / (quick ? QUICK_FADE_MS : FADE_OUT_MS));
                curtainA *= 1f - out;
                if (out >= 1f && !goneFired) {
                    goneFired = true;
                    gone = true;
                    postMain(onGone);
                }
            }
            markA = curtainA;
        }

        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        if (curtainA <= 0f) return gone;
        curtain.setAlpha(Math.round(255 * curtainA));
        c.drawRect(0, 0, c.getWidth(), c.getHeight(), curtain);

        float s = nucleusPx / (2f * (ATOM_RM + ATOM_SW / 2f));
        float cx = c.getWidth() / 2f, cy = c.getHeight() / 2f;
        if (markA >= 1f) {
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            c.translate(-ATOM_X, -ATOM_Y);
            renderer.draw(c, state, false, Color.WHITE);
            c.restore();
        } else {
            // While fading, the mark is rendered opaque into an offscreen bitmap
            // that is drawn with the fade alpha: per-shape alpha would show where
            // bars and rings overlap (a saveLayer on this hardware canvas is not
            // honored reliably).
            int size = (int) Math.ceil((MARK_MAX - MARK_MIN) * s);
            if (markBitmap == null || markBitmap.getWidth() != size) {
                markBitmap = Bitmap.createBitmap(Math.max(1, size), Math.max(1, size),
                        Bitmap.Config.ARGB_8888);
                markCanvas = new Canvas(markBitmap);
            }
            Canvas mc = markCanvas;
            mc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            mc.save();
            mc.scale(s, s);
            mc.translate(-MARK_MIN, -MARK_MIN);
            renderer.draw(mc, state, false, Color.WHITE);
            mc.restore();
            markPaint.setAlpha(Math.round(255 * markA));
            c.drawBitmap(markBitmap, cx - (ATOM_X - MARK_MIN) * s,
                    cy - (ATOM_Y - MARK_MIN) * s, markPaint);
        }
        return gone;
    }

    /** Animation time elapsed after [dt] real ms from the end of loading: the
     *  speed ramps linearly from 1x to END_SPEED over SPEED_RAMP_MS, so the
     *  closing ring does not jump in speed. */
    private static float warp(float dt) {
        float extra = END_SPEED - 1f;
        if (dt < SPEED_RAMP_MS) return dt + extra * dt * dt / (2f * SPEED_RAMP_MS);
        return dt + extra * (dt - SPEED_RAMP_MS / 2f);
    }

    private void postMain(@Nullable Runnable r) {
        if (r != null) main.post(r);
    }
}
