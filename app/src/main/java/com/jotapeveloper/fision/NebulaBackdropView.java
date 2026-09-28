package com.jotapeveloper.fision;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Repaints the player backdrop (black base plus the matching region of the
 * nebula background) on top of content that cannot be masked from the view
 * hierarchy, such as a SurfaceView. Instead of fading that content to
 * transparent, the backdrop is drawn over it, so the result blends with
 * whatever the nebula shows around the view.
 *
 * {@code fadeEdge}: top / bottom = opaque at that edge, transparent at the
 * opposite one (edge fade); none = uniformly opaque (full cover, meant to be
 * faded via alpha during transitions).
 */
public class NebulaBackdropView extends View {

    private static final int EDGE_NONE   = 0;
    private static final int EDGE_TOP    = 1;
    private static final int EDGE_BOTTOM = 2;

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] srcLoc  = new int[2];
    private final int[] selfLoc = new int[2];
    private NebulaBackgroundView source;
    private int fadeEdge = EDGE_NONE;

    public NebulaBackdropView(Context ctx) { super(ctx); init(null); }
    public NebulaBackdropView(Context ctx, AttributeSet a) { super(ctx, a); init(a); }
    public NebulaBackdropView(Context ctx, AttributeSet a, int s) { super(ctx, a, s); init(a); }

    private void init(AttributeSet attrs) {
        if (attrs != null) {
            TypedArray ta = getContext().obtainStyledAttributes(attrs, R.styleable.NebulaBackdropView);
            fadeEdge = ta.getInt(R.styleable.NebulaBackdropView_fadeEdge, EDGE_NONE);
            ta.recycle();
        }
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setClickable(false);
        setFocusable(false);
    }

    /** Nebula to mirror. Null → plain black backdrop. */
    public void setSource(NebulaBackgroundView nebula) {
        if (source != null) source.removeMirror(this);
        source = nebula;
        if (source != null && isAttachedToWindow()) source.addMirror(this);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (fadeEdge == EDGE_NONE) {
            maskPaint.setShader(null);
            return;
        }
        // Opaque at the chosen edge, eased mid-stop, transparent at the other.
        float y0 = fadeEdge == EDGE_BOTTOM ? h : 0;
        float y1 = fadeEdge == EDGE_BOTTOM ? 0 : h;
        maskPaint.setShader(new LinearGradient(0, y0, 0, y1,
                new int[] { 0xFF000000, 0x8C000000, 0x00000000 },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (source != null) source.addMirror(this);
    }

    @Override protected void onDetachedFromWindow() {
        if (source != null) source.removeMirror(this);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        boolean masked = fadeEdge != EDGE_NONE;
        int layer = masked ? canvas.saveLayer(0, 0, w, h, null) : canvas.save();
        canvas.drawColor(Color.BLACK);
        float a = source != null ? source.getEffectiveAlpha() : 0f;
        if (a > 0f) {
            source.getLocationInWindow(srcLoc);
            getLocationInWindow(selfLoc);
            float dx = srcLoc[0] - selfLoc[0];
            float dy = srcLoc[1] - selfLoc[1];
            canvas.save();
            canvas.translate(dx, dy);
            // Nebula view alpha is applied by its parent, not by draw(): replicate it.
            int alphaLayer = canvas.saveLayerAlpha(-dx, -dy, -dx + w, -dy + h,
                    Math.round(a * 255f));
            source.drawNebula(canvas);
            canvas.restoreToCount(alphaLayer);
            canvas.restore();
        }
        if (masked) canvas.drawRect(0, 0, w, h, maskPaint);
        canvas.restoreToCount(layer);
    }
}
