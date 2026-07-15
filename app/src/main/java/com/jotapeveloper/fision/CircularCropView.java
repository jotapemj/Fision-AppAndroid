package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Interactive crop viewport: displays a bitmap that the user can pan and pinch
 * to reframe, and paints a dark overlay with a transparent circular hole that
 * defines the crop region.
 *
 *   - The circle is always centered on the view and sized to fit tightly
 *     inside the shorter dimension (with a small margin).
 *   - The bitmap uses a live Matrix (translate + uniform scale) so the crop
 *     region is derived by inverse-mapping the circle bounds into bitmap space.
 *   - Minimum scale is chosen so the circle is always fully covered by the
 *     bitmap (no visible background inside the hole).
 */
public class CircularCropView extends View {

    private static final float MAX_SCALE_FACTOR = 6f;
    private static final float CIRCLE_MARGIN_DP = 24f;

    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];

    private ScaleGestureDetector scaleDetector;
    private float minScale = 1f;
    private float maxScale = MAX_SCALE_FACTOR;

    private float lastTouchX, lastTouchY;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();

    private final RectF circle = new RectF();
    private final float marginPx;

    public CircularCropView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        overlayPaint.setColor(0xCC000000);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        ringPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2f * ctx.getResources().getDisplayMetrics().density);
        marginPx = CIRCLE_MARGIN_DP * ctx.getResources().getDisplayMetrics().density;

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleListener());
    }

    public void setBitmap(Bitmap bm) {
        this.bitmap = bm;
        if (getWidth() > 0 && getHeight() > 0) resetMatrix();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCircle();
        if (bitmap != null) resetMatrix();
    }

    private void updateCircle() {
        int w = getWidth();
        int h = getHeight();
        float r = Math.min(w, h) * 0.5f - marginPx;
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        circle.set(cx - r, cy - r, cx + r, cy + r);
    }

    /** Sets minScale so the bitmap always covers the circle, and centers it. */
    private void resetMatrix() {
        if (bitmap == null) return;
        updateCircle();
        float circleDiameter = circle.width();
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        // "Cover" the circle: the smaller bitmap side maps to the circle diameter.
        float shorterSide = Math.min(bw, bh);
        minScale = circleDiameter / shorterSide;
        maxScale = minScale * MAX_SCALE_FACTOR;
        matrix.reset();
        matrix.postScale(minScale, minScale);
        // Center the (scaled) bitmap on the view
        float dx = (getWidth()  - bw * minScale) * 0.5f;
        float dy = (getHeight() - bh * minScale) * 0.5f;
        matrix.postTranslate(dx, dy);
        clampMatrix();
        invalidate();
    }

    /** Ensures the bitmap keeps covering the circle after any pan/zoom. */
    private void clampMatrix() {
        if (bitmap == null) return;
        matrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        float tx = matrixValues[Matrix.MTRANS_X];
        float ty = matrixValues[Matrix.MTRANS_Y];
        float bw = bitmap.getWidth() * scale;
        float bh = bitmap.getHeight() * scale;
        float minTx = circle.right  - bw;
        float maxTx = circle.left;
        float minTy = circle.bottom - bh;
        float maxTy = circle.top;
        if (tx > maxTx) tx = maxTx;
        if (tx < minTx) tx = minTx;
        if (ty > maxTy) ty = maxTy;
        if (ty < minTy) ty = minTy;
        matrixValues[Matrix.MTRANS_X] = tx;
        matrixValues[Matrix.MTRANS_Y] = ty;
        matrix.setValues(matrixValues);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) canvas.drawBitmap(bitmap, matrix, null);

        // Dark overlay everywhere, then punch a circular hole out of it
        int save = canvas.saveLayer(null, null);
        canvas.drawRect(0, 0, getWidth(), getHeight(), overlayPaint);
        canvas.drawOval(circle, clearPaint);
        canvas.restoreToCount(save);

        // Thin ring for aesthetics
        canvas.drawOval(circle, ringPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastTouchX = ev.getX();
                lastTouchY = ev.getY();
                activePointerId = ev.getPointerId(0);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (scaleDetector.isInProgress()) break;
                int idx = ev.findPointerIndex(activePointerId);
                if (idx < 0) break;
                float x = ev.getX(idx);
                float y = ev.getY(idx);
                matrix.postTranslate(x - lastTouchX, y - lastTouchY);
                clampMatrix();
                lastTouchX = x;
                lastTouchY = y;
                invalidate();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int upIdx = ev.getActionIndex();
                if (ev.getPointerId(upIdx) == activePointerId) {
                    int newIdx = upIdx == 0 ? 1 : 0;
                    lastTouchX = ev.getX(newIdx);
                    lastTouchY = ev.getY(newIdx);
                    activePointerId = ev.getPointerId(newIdx);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                break;
        }
        return true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScale(ScaleGestureDetector d) {
            if (bitmap == null) return false;
            matrix.getValues(matrixValues);
            float current = matrixValues[Matrix.MSCALE_X];
            float factor  = d.getScaleFactor();
            float target  = current * factor;
            if (target < minScale) factor = minScale / current;
            if (target > maxScale) factor = maxScale / current;
            matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
            clampMatrix();
            invalidate();
            return true;
        }
    }

    /** Returns the crop as a square bitmap of the requested pixel size, taken
     *  from the region of the source bitmap currently framed by the circle
     *  (its axis-aligned bounding box, since the circle is inscribed). */
    public Bitmap produceCrop(int outSizePx) {
        if (bitmap == null) return null;
        matrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        float tx = matrixValues[Matrix.MTRANS_X];
        float ty = matrixValues[Matrix.MTRANS_Y];
        // Map circle bounds from view space back to bitmap space
        float srcX = (circle.left - tx) / scale;
        float srcY = (circle.top  - ty) / scale;
        float srcSize = circle.width() / scale;
        int x = Math.max(0, Math.round(srcX));
        int y = Math.max(0, Math.round(srcY));
        int s = Math.max(1, Math.round(srcSize));
        if (x + s > bitmap.getWidth())  s = bitmap.getWidth()  - x;
        if (y + s > bitmap.getHeight()) s = bitmap.getHeight() - y;
        Bitmap cropped = Bitmap.createBitmap(bitmap, x, y, s, s);
        if (cropped.getWidth() == outSizePx) return cropped;
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, outSizePx, outSizePx, true);
        if (scaled != cropped) cropped.recycle();
        return scaled;
    }
}
