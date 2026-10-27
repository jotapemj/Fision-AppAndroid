package com.jotapeveloper.fision.brand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jotapeveloper.fision.design.AccentColorManager;

/**
 * Backdrop of the empty library: two faint accent blobs dancing. The big one
 * drifts on a small circle; the small one orbits it on a tilted ellipse,
 * passing in front (larger, brighter, drawn last) and behind (smaller, dimmer,
 * drawn first). 30 fps only while visible; nothing is scheduled otherwise.
 * Shown and hidden with {@link #setShown} (fades).
 */
public final class EmptyBlobsView extends View {

    private static final long FRAME_MS = 33;
    /** One turn of the small blob. */
    private static final float TURN_MS = 14_000f;
    private static final long FADE_IN_MS = 1200;
    private static final long FADE_OUT_MS = 250;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final float bigR, smallR;
    private RadialGradient big, small;
    private boolean visible;

    public EmptyBlobsView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        bigR = 95f * d;
        smallR = 58f * d;
        setAccent(AccentColorManager.resolveAccent(c));
    }

    public void setAccent(int color) {
        big = gradient(color, 0x30);
        small = gradient(color, 0x3A);
        invalidate();
    }

    private boolean shown;

    /** Fades in from alpha 0 when shown, out (then GONE) when hidden. Calls
     *  that keep the current state change nothing, so the dance never restarts. */
    public void setShown(boolean show) {
        if (show == shown) return;
        shown = show;
        animate().cancel();
        if (show) {
            setAlpha(0f);
            setVisibility(VISIBLE);
            animate().alpha(1f).setDuration(FADE_IN_MS).start();
        } else {
            animate().alpha(0f).setDuration(FADE_OUT_MS)
                    .withEndAction(() -> setVisibility(GONE)).start();
        }
    }

    /** Unit radial gradient: soft core fading to transparent at radius 1. */
    private static RadialGradient gradient(int color, int alpha) {
        int rgb = color & 0x00FFFFFF;
        return new RadialGradient(0f, 0f, 1f,
                new int[]{(alpha << 24) | rgb, ((alpha / 2) << 24) | rgb, rgb},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        visible = isVisible;
        if (isVisible) invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // Two turns of the small blob = one of the big one, so the loop is seamless.
        double t = (SystemClock.uptimeMillis() % (long) (TURN_MS * 2)) / TURN_MS * 2 * Math.PI;
        float cx = w / 2f, cy = h / 2f;
        float unit = Math.min(w, h);

        float bx = cx + (float) Math.cos(-t / 2) * unit * 0.06f;
        float by = cy + (float) Math.sin(-t / 2) * unit * 0.05f;

        float sx = bx + (float) Math.cos(t) * unit * 0.24f;
        float sy = by + (float) Math.sin(t) * unit * 0.08f;
        float z = (float) Math.sin(t);                 // > 0: in front
        float sr = smallR * (1f + 0.18f * z);
        int sa = (int) (255 * (0.6f + 0.2f * (z + 1f)));

        if (z < 0) {
            blob(c, small, sx, sy, sr, sa);
            blob(c, big, bx, by, bigR, 255);
        } else {
            blob(c, big, bx, by, bigR, 255);
            blob(c, small, sx, sy, sr, sa);
        }
        if (visible) postInvalidateDelayed(FRAME_MS);
    }

    private void blob(Canvas c, RadialGradient g, float x, float y, float r, int alpha) {
        m.setScale(r, r);
        m.postTranslate(x, y);
        g.setLocalMatrix(m);
        paint.setShader(g);
        paint.setAlpha(alpha);
        c.drawCircle(x, y, r, paint);
    }
}
