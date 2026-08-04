package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView with real transparent fading edges. The native fadingEdge draws
 * against the View's solid color, which under hardware acceleration collapses
 * to the window background (opaque black) and produces a visible black band on
 * an otherwise transparent overlay. Here the fade is drawn with a
 * DST_OUT xfermode over a hardware layer, wiping the content's alpha at the
 * top and bottom so the real background behind the recycler shows through.
 */
public class FadingEdgeRecyclerView extends RecyclerView {

    private static final int   DEFAULT_TOP_DP    = 120;
    private static final int   DEFAULT_BOTTOM_DP = 110;

    private final Paint         mFadePaint    = new Paint();
    private LinearGradient      mTopShader;
    private LinearGradient      mBottomShader;
    private int                 mFadeTopPx;
    private int                 mFadeBottomPx;
    // Optional fully-transparent slab at the top of the recycler (measured in
    // px from y=0). Between 0 and this value the wipe stays at 100% (content
    // fully cleared); between this value and mFadeTopPx it eases from
    // transparent to opaque. Useful when an overlay covers the top band and
    // the recycler content must never bleed through the gap between tiles.
    private int                 mFadeTopSolidPx;

    public FadingEdgeRecyclerView(Context ctx) {
        super(ctx);
        init(ctx);
    }

    public FadingEdgeRecyclerView(Context ctx, @Nullable AttributeSet a) {
        super(ctx, a);
        init(ctx);
    }

    public FadingEdgeRecyclerView(Context ctx, @Nullable AttributeSet a, int d) {
        super(ctx, a, d);
        init(ctx);
    }

    private void init(Context ctx) {
        mFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        // Offscreen layer required so DST_OUT operates on the content pixels
        // rather than on the framebuffer.
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setVerticalFadingEdgeEnabled(false);
        setHorizontalFadingEdgeEnabled(false);
        float d = ctx.getResources().getDisplayMetrics().density;
        mFadeTopPx    = (int) (DEFAULT_TOP_DP    * d);
        mFadeBottomPx = (int) (DEFAULT_BOTTOM_DP * d);
    }

    /** Change the fade lengths at runtime. Rebuilds the shaders. */
    public void setFadeLengthsPx(int topPx, int bottomPx) {
        if (topPx == mFadeTopPx && bottomPx == mFadeBottomPx) return;
        mFadeTopPx    = topPx;
        mFadeBottomPx = bottomPx;
        buildShaders();
        invalidate();
    }

    /** Reserves a fully-transparent slab at the top. Values in [0, solidPx]
     *  stay 100% wiped; from solidPx to the current fade top length the
     *  wipe eases from transparent to opaque. Pass 0 to disable. */
    public void setTopFadeSolidPx(int solidPx) {
        if (solidPx == mFadeTopSolidPx) return;
        mFadeTopSolidPx = solidPx;
        buildShaders();
        invalidate();
    }

    public int getFadeTopPx()      { return mFadeTopPx; }
    public int getFadeBottomPx()   { return mFadeBottomPx; }
    public int getTopFadeSolidPx() { return mFadeTopSolidPx; }

    private void buildShaders() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            mTopShader = mBottomShader = null;
            return;
        }
        int topLen = Math.min(mFadeTopPx, h / 2);
        int botLen = Math.min(mFadeBottomPx, h / 2);
        // Alpha 1 near the edge, alpha 0 towards the visible area. DST_OUT
        // subtracts that alpha from the destination. With mFadeTopSolidPx > 0
        // an extra stop keeps the wipe fully opaque up to that y, so the
        // covered band never sees content through the tile gaps.
        if (topLen > 0) {
            int solid = Math.max(0, Math.min(mFadeTopSolidPx, topLen));
            if (solid == 0) {
                mTopShader = new LinearGradient(0, 0, 0, topLen,
                        0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
            } else {
                float p = solid / (float) topLen;
                mTopShader = new LinearGradient(0, 0, 0, topLen,
                        new int[] { 0xFF000000, 0xFF000000, 0x00000000 },
                        new float[] { 0f, p, 1f },
                        Shader.TileMode.CLAMP);
            }
        } else {
            mTopShader = null;
        }
        mBottomShader = botLen > 0
                ? new LinearGradient(0, h - botLen, 0, h,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP)
                : null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        buildShaders();
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (mTopShader != null) {
            int topLen = Math.min(mFadeTopPx, h / 2);
            mFadePaint.setShader(mTopShader);
            canvas.drawRect(0, 0, w, topLen, mFadePaint);
        }
        if (mBottomShader != null) {
            int botLen = Math.min(mFadeBottomPx, h / 2);
            mFadePaint.setShader(mBottomShader);
            canvas.drawRect(0, h - botLen, w, h, mFadePaint);
        }
    }
}
