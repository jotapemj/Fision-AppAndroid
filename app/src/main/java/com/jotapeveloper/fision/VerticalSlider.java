package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Simple vertical slider with a Material look. The official Material Slider does NOT
 * support vertical orientation and rotating it with `android:rotation` does not
 * transform the touch events (the whole range collapses into a few pixels). This
 * component handles touches in native vertical coordinates and draws track + thumb.
 *
 * Conventions: valueFrom at the bottom, valueTo at the top (top = maximum value).
 */
public class VerticalSlider extends View {

    public interface OnChangeListener {
        void onValueChanged(VerticalSlider s, float value, boolean fromUser);
    }

    private float mValueFrom = 0f;
    private float mValueTo   = 100f;
    private float mValue     = 0f;
    private float mStep      = 1f;

    private final Paint mTrackInactive = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrackActive   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumb         = new Paint(Paint.ANTI_ALIAS_FLAG);

    private @Nullable OnChangeListener mListener;
    private final int mTouchSlop;

    public VerticalSlider(Context ctx) { this(ctx, null); }
    public VerticalSlider(Context ctx, @Nullable AttributeSet a) { this(ctx, a, 0); }
    public VerticalSlider(Context ctx, @Nullable AttributeSet a, int d) {
        super(ctx, a, d);
        mTrackInactive.setColor(ContextCompat.getColor(ctx, R.color.BottomSheetColor));
        mTrackActive.setColor(com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(ctx));
        mThumb.setColor(com.jotapeveloper.fision.design.AccentColorManager.resolveAccentSecondary(ctx));
        mTouchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
    }

    public void setRange(float from, float to) {
        mValueFrom = from; mValueTo = to;
        mValue = Math.max(from, Math.min(to, mValue));
        invalidate();
    }
    public void setStep(float step) { mStep = Math.max(0.0001f, step); }
    public float getValue() { return mValue; }

    public void setValue(float v) {
        v = Math.max(mValueFrom, Math.min(mValueTo, v));
        if (mStep > 0.001f) v = Math.round(v / mStep) * mStep;
        if (Math.abs(v - mValue) < 1e-4f) return;
        mValue = v;
        invalidate();
    }

    public void setOnChangeListener(OnChangeListener l) { mListener = l; }

    /** Y coordinate (within the view) of the thumb center for the current value. */
    public float getThumbCenterY() {
        float density = getResources().getDisplayMetrics().density;
        float thumbR  = 10f * density;
        int top = (int) thumbR;
        int bot = getHeight() - (int) thumbR;
        int usable = bot - top;
        float frac = (mValue - mValueFrom) / (mValueTo - mValueFrom);
        return bot - frac * usable;
    }

    /** True while the user is currently dragging. */
    public boolean isDragging() { return mDragging; }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float trackW = 6f * density;
        float thumbR = 10f * density;
        int cx = getWidth() / 2;
        int top = (int) thumbR;
        int bot = getHeight() - (int) thumbR;
        int usable = bot - top;
        float frac = (mValue - mValueFrom) / (mValueTo - mValueFrom);
        int thumbY = bot - (int)(frac * usable);
        // Inactive track (full length)
        canvas.drawRect(cx - trackW/2, top, cx + trackW/2, bot, mTrackInactive);
        // Active track (from the thumb down to the bottom)
        canvas.drawRect(cx - trackW/2, thumbY, cx + trackW/2, bot, mTrackActive);
        // Thumb
        canvas.drawCircle(cx, thumbY, thumbR, mThumb);
    }

    private boolean mDragging = false;
    private float mDownY;

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isEnabled()) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownY = ev.getY();
                mDragging = true;
                updateFromY(ev.getY(), true);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mDragging) updateFromY(ev.getY(), true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(ev);
    }

    private void updateFromY(float y, boolean fromUser) {
        float density = getResources().getDisplayMetrics().density;
        float thumbR = 10f * density;
        int top = (int) thumbR;
        int bot = getHeight() - (int) thumbR;
        int usable = Math.max(1, bot - top);
        float clampedY = Math.max(top, Math.min(bot, y));
        float frac = 1f - (clampedY - top) / (float) usable;
        float newVal = mValueFrom + frac * (mValueTo - mValueFrom);
        if (mStep > 0.001f) newVal = Math.round(newVal / mStep) * mStep;
        if (Math.abs(newVal - mValue) < 1e-4f) return;
        mValue = newVal;
        invalidate();
        if (mListener != null) mListener.onValueChanged(this, mValue, fromUser);
    }
}
