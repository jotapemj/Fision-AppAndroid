package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

/**
 * ConstraintLayout that draws as background a blur of whatever sits behind it
 * (a "source" View, typically the list RecyclerView). Telegram-bar style: the
 * content behind is translucent and blurred but still lets color and motion
 * come through.
 *
 * Technique:
 *  - On each onDraw the area of sourceView sitting behind this view is captured
 *    into a downscaled Bitmap (scale 0.25). Cheap at that resolution.
 *  - The Bitmap is drawn into a RenderNode with RenderEffect.createBlurEffect
 *    (GPU shader).
 *  - The RenderNode is drawn to the canvas below the layout's normal content.
 *  - When the sourceView repaints (scroll), a predraw listener is registered
 *    and a repaint is forced.
 *
 * API 31+ only. On older versions it falls back to a solid translucent background.
 */
public class BlurBehindLayout extends ConstraintLayout {

    private static final float SCALE = 0.25f;   // downsample factor of the source bitmap
    private static final float BLUR  = 25f;     // blur radius in bitmap pixels

    @Nullable private View sourceView;
    @Nullable private Bitmap bitmap;
    @Nullable private Canvas bitmapCanvas;
    @Nullable private RenderNode renderNode;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private boolean capturing = false;   // guard against recursion source.draw -> onDraw
    /** Screen colour under the source (every host screen is black). */
    private int backdropColor = Color.BLACK;
    private final int[] myLoc  = new int[2];
    private final int[] srcLoc = new int[2];

    /** When the RV scrolls, an invalidate triggers a background recapture. Covers 99%
     *  of the cases where the content behind changes. Item changes without scroll
     *  (notifyItemChanged) show up in the blur on the next user scroll. */
    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
            if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
        }
    };

    public BlurBehindLayout(@NonNull Context context) { super(context); }
    public BlurBehindLayout(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public BlurBehindLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Assigns the View whose content is blurred as the background. Typically the RV. */
    public void setSourceView(@Nullable View v) {
        if (this.sourceView instanceof RecyclerView) {
            ((RecyclerView) this.sourceView).removeOnScrollListener(scrollListener);
        }
        this.sourceView = v;
        if (v instanceof RecyclerView) {
            ((RecyclerView) v).addOnScrollListener(scrollListener);
        }
        invalidate();
    }

    /** Forces a blur recapture. Call after notifyDataSetChanged or any source change
     *  that does not trigger a scroll. */
    public void invalidateBlur() {
        if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (sourceView instanceof RecyclerView) {
            ((RecyclerView) sourceView).removeOnScrollListener(scrollListener);
        }
        if (renderNode != null) {
            renderNode.discardDisplayList();
            renderNode = null;
        }
        bitmap = null;
        bitmapCanvas = null;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        // Paint the blur BEFORE any child / layout background.
        if (!capturing && sourceView != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && getWidth() > 0 && getHeight() > 0) {
            drawBlurBackground(canvas);
        }
        super.draw(canvas);
    }

    private void drawBlurBackground(@NonNull Canvas dst) {
        int targetW = Math.max(1, (int) (getWidth() * SCALE));
        int targetH = Math.max(1, (int) (getHeight() * SCALE));
        if (bitmap == null || bitmap.getWidth() != targetW || bitmap.getHeight() != targetH) {
            bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            bitmapCanvas = new Canvas(bitmap);
        }
        if (bitmapCanvas == null) return;
        // Opaque backdrop: rows without their own fill would otherwise blur over
        // transparency and let the sharp content behind show through.
        bitmap.eraseColor(backdropColor);

        // Compute the offset between the source and this view in window coordinates.
        getLocationInWindow(myLoc);
        sourceView.getLocationInWindow(srcLoc);
        int dx = srcLoc[0] - myLoc[0];
        int dy = srcLoc[1] - myLoc[1];

        bitmapCanvas.save();
        bitmapCanvas.scale(SCALE, SCALE);
        bitmapCanvas.translate(dx, dy);
        capturing = true;
        try { sourceView.draw(bitmapCanvas); }
        catch (Throwable ignored) {}
        finally { capturing = false; }
        bitmapCanvas.restore();

        if (renderNode == null) {
            renderNode = new RenderNode("miniPlayerBlur");
            renderNode.setRenderEffect(RenderEffect.createBlurEffect(
                    BLUR, BLUR, Shader.TileMode.CLAMP));
        }
        renderNode.setPosition(0, 0, getWidth(), getHeight());
        Canvas rn = renderNode.beginRecording();
        rn.scale(1f / SCALE, 1f / SCALE);
        rn.drawBitmap(bitmap, 0f, 0f, paint);
        renderNode.endRecording();

        dst.drawRenderNode(renderNode);
    }
}
