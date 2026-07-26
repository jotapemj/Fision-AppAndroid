package com.jotapeveloper.fision;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

/**
 * Horizontal ruler-style number picker. The user drags left/right and the value
 * snaps to the closest tick on release. Minor ticks are drawn every step, major
 * ticks every ten steps, and a small triangle marker at the top points at the
 * currently selected value.
 *
 * The internal state is a single continuous float ({@link #mScrollValue}) so
 * dragging is smooth end-to-end and always reaches both bounds. Fling is
 * disabled on purpose: the ruler is used for precise musical parameters where
 * inertia would overshoot several steps.
 */
public class RulerPickerView extends View {

    public interface OnValueChangeListener {
        void onChanged(int value, boolean fromUser);
    }

    public interface LabelFormatter {
        CharSequence format(int value);
    }

    // Configuration
    private int mMin  = 0;
    private int mMax  = 100;
    private int mStep = 1;
    /** Value that triggers the stronger CONFIRM haptic. Integer.MIN_VALUE = disabled. */
    private int mNeutralValue = Integer.MIN_VALUE;

    private OnValueChangeListener mListener;
    private LabelFormatter        mFormatter;

    // Release-time snap animator ("magnet").
    private ValueAnimator mSnapAnimator;
    private static final long SNAP_DURATION_MS = 180L;

    // Drawing paints
    private final Paint mTickMinorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTickMajorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTickLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMarkerPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Layout in pixels — cached from dp on init
    private float mSpacingPx;      // horizontal distance between consecutive ticks (one per step)
    private float mTickTopY;
    private float mTickMinorH;
    private float mTickMajorH;
    private float mTickLabelSize;
    private float mLabelBaselineOffset;
    private float mMarkerHeight;
    private float mMarkerHalfWidth;
    private float mMarkerGap;      // gap between marker apex and the tick row
    private final Path mMarkerPath = new Path();

    // Vibrator (independent of performHapticFeedback which some OEM skins
    // ignore for CLOCK_TICK / CONFIRM even with FLAG_IGNORE_VIEW_SETTING).
    @Nullable private final Vibrator mVibrator;

    // Touch / motion
    private final int   mTouchSlop;
    private float       mLastTouchX;
    private boolean     mDragging;
    // Continuous position along the ruler in step units (float). Integer value
    // is what we display / commit.
    private float       mScrollValue;
    private int         mLastHapticValue;

    public RulerPickerView(Context ctx) { this(ctx, null); }
    public RulerPickerView(Context ctx, @Nullable AttributeSet a) { this(ctx, a, 0); }
    public RulerPickerView(Context ctx, @Nullable AttributeSet a, int def) {
        super(ctx, a, def);
        mTouchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        setHapticFeedbackEnabled(true);
        mVibrator = resolveVibrator(ctx);
        initPaints(ctx);
    }

    private static Vibrator resolveVibrator(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    private void initPaints(Context ctx) {
        float density = getResources().getDisplayMetrics().density;
        mSpacingPx     = 14f * density;
        mTickTopY      = 14f * density;
        mTickMinorH    = 10f * density;
        mTickMajorH    = 18f * density;
        mTickLabelSize = 12f * density;
        mLabelBaselineOffset = 6f * density;
        mMarkerHeight    = 10f * density;
        mMarkerHalfWidth = 6f  * density;
        mMarkerGap       = 3f  * density;

        int white55 = 0x8CFFFFFF;
        int white85 = 0xD9FFFFFF;
        int primary = ContextCompat.getColor(ctx, R.color.colorPrimary);

        mTickMinorPaint.setColor(white55);
        mTickMinorPaint.setStrokeWidth(1f * density);
        mTickMinorPaint.setStrokeCap(Paint.Cap.ROUND);

        mTickMajorPaint.setColor(white85);
        mTickMajorPaint.setStrokeWidth(1.5f * density);
        mTickMajorPaint.setStrokeCap(Paint.Cap.ROUND);

        mTickLabelPaint.setColor(white85);
        mTickLabelPaint.setTextSize(mTickLabelSize);
        mTickLabelPaint.setTextAlign(Paint.Align.CENTER);
        Typeface tf = ResourcesCompat.getFont(ctx, R.font.yugothic_ui_regular);
        if (tf != null) mTickLabelPaint.setTypeface(tf);

        mMarkerPaint.setColor(primary);
        mMarkerPaint.setStyle(Paint.Style.FILL);
    }

    /** Sets the numeric range covered by the ruler. Must be called before setValue(). */
    public void setRange(int min, int max) {
        if (max < min) { int tmp = min; min = max; max = tmp; }
        mMin = min;
        mMax = max;
        mScrollValue = clampContinuous(mScrollValue);
        invalidate();
    }

    /** Step between snap points. Values are always mMin + k*mStep. */
    public void setStep(int step) {
        mStep = Math.max(1, step);
        mScrollValue = snapToStep(mScrollValue);
        invalidate();
    }

    /** Horizontal distance between consecutive ticks. Larger values force a
     *  longer drag to change one step — useful for parameters (like pitch)
     *  where fewer, more deliberate changes are preferred. */
    public void setTickSpacingDp(float dp) {
        float density = getResources().getDisplayMetrics().density;
        mSpacingPx = Math.max(1f, dp * density);
        invalidate();
    }

    /** Enables a stronger CONFIRM haptic when the ruler crosses this value.
     *  Typically the "neutral" position (globalBpm for tempo, 0 for pitch). */
    public void setNeutralValue(int neutral) {
        mNeutralValue = neutral;
    }

    public void setValue(int v) {
        int clamped = snapToStep(v);
        if (clamped == getValue()) return;
        cancelSnapAnimation();
        mScrollValue = clamped;
        invalidate();
        if (mListener != null) mListener.onChanged(clamped, /*fromUser=*/false);
    }

    private void cancelSnapAnimation() {
        if (mSnapAnimator != null && mSnapAnimator.isStarted()) {
            mSnapAnimator.cancel();
        }
        mSnapAnimator = null;
    }

    /** Animates mScrollValue to the closest integer step ("magnet" feel on
     *  release). Emits an intermediate haptic if the animation crosses the
     *  neutral value, and a final onChanged when it settles. */
    private void animateSnap() {
        cancelSnapAnimation();
        final int target = snapToStep(mScrollValue);
        final float from = mScrollValue;
        if (Math.abs(from - target) < 0.01f) {
            mScrollValue = target;
            invalidate();
            emitFinalSnap(target);
            return;
        }
        mSnapAnimator = ValueAnimator.ofFloat(from, target);
        mSnapAnimator.setDuration(SNAP_DURATION_MS);
        mSnapAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        mSnapAnimator.addUpdateListener(a -> {
            mScrollValue = (float) a.getAnimatedValue();
            int nowValue = snapToStep(mScrollValue);
            if (nowValue != mLastHapticValue) {
                emitHapticForValue(nowValue);
                mLastHapticValue = nowValue;
            }
            invalidate();
        });
        mSnapAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator anim) {
                if (mSnapAnimator != anim) return;
                mScrollValue = target;
                invalidate();
                emitFinalSnap(target);
            }
        });
        mSnapAnimator.start();
    }

    private void emitFinalSnap(int value) {
        if (mListener != null) mListener.onChanged(value, /*fromUser=*/true);
    }

    private void emitHapticForValue(int value) {
        boolean neutral = (value == mNeutralValue);
        // Prefer the Vibrator with a predefined effect: Samsung One UI and some
        // other skins swallow performHapticFeedback for tick-level constants.
        if (mVibrator != null && mVibrator.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    int effect = neutral
                            ? VibrationEffect.EFFECT_HEAVY_CLICK
                            : VibrationEffect.EFFECT_TICK;
                    mVibrator.vibrate(VibrationEffect.createPredefined(effect));
                } else {
                    // Legacy: short one-shot.
                    mVibrator.vibrate(neutral ? 30L : 12L);
                }
                return;
            } catch (Throwable ignored) { /* fall through to performHapticFeedback */ }
        }
        int flag = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING;
        int fbConst = neutral
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.CLOCK_TICK;
        performHapticFeedback(fbConst, flag);
    }

    public int getValue() { return snapToStep(mScrollValue); }
    public int getMin()   { return mMin; }
    public int getMax()   { return mMax; }

    public void setOnValueChangeListener(OnValueChangeListener l) { mListener = l; }
    public void setLabelFormatter(LabelFormatter f) {
        mFormatter = f;
        invalidate();
    }

    private float clampContinuous(float v) {
        return Math.max(mMin, Math.min(mMax, v));
    }

    private int snapToStep(float v) {
        int clamped = (int) Math.round(clampContinuous(v));
        int off = clamped - mMin;
        off = Math.round((float) off / mStep) * mStep;
        return Math.max(mMin, Math.min(mMax, mMin + off));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        float density = getResources().getDisplayMetrics().density;
        // Ticks column + label baseline + a bit of breathing room underneath.
        int h = (int) (mTickTopY + mTickMajorH + mLabelBaselineOffset + mTickLabelSize + 8f * density);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        if (hMode == MeasureSpec.EXACTLY) h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float centerX = getWidth() * 0.5f;
        float valueAtCursor = mScrollValue;

        // How many step-units cover half the width of the view.
        float halfWidthValueUnits = (centerX / mSpacingPx) * mStep;

        // Iterate on the step index k so we always land on a valid grid tick.
        int totalSteps = (mMax - mMin) / mStep;
        int kMin = (int) Math.floor((valueAtCursor - halfWidthValueUnits - mMin) / mStep);
        int kMax = (int) Math.ceil ((valueAtCursor + halfWidthValueUnits - mMin) / mStep);
        if (kMin < 0)          kMin = 0;
        if (kMax > totalSteps) kMax = totalSteps;

        float tickBottom = mTickTopY + mTickMajorH;

        for (int k = kMin; k <= kMax; k++) {
            int v = mMin + k * mStep;
            float x = centerX + (v - valueAtCursor) * mSpacingPx;
            // Major tick every ten values (independent of mStep), so labels
            // land on the natural round numbers (0, 10, 20, ...).
            boolean major = (v % 10) == 0;
            Paint p = major ? mTickMajorPaint : mTickMinorPaint;
            float h = major ? mTickMajorH : mTickMinorH;
            float top = tickBottom - h;
            canvas.drawLine(x, top, x, tickBottom, p);
            if (major) {
                canvas.drawText(String.valueOf(v), x,
                        tickBottom + mLabelBaselineOffset + mTickLabelSize, mTickLabelPaint);
            }
        }

        // Downward-pointing triangle marker sitting above the tick row, apex
        // touching the top of the ticks. Never overlaps the labels.
        float apexY = mTickTopY - mMarkerGap;
        float baseY = apexY - mMarkerHeight;
        mMarkerPath.rewind();
        mMarkerPath.moveTo(centerX, apexY);
        mMarkerPath.lineTo(centerX - mMarkerHalfWidth, baseY);
        mMarkerPath.lineTo(centerX + mMarkerHalfWidth, baseY);
        mMarkerPath.close();
        canvas.drawPath(mMarkerPath, mMarkerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelSnapAnimation();
                mLastTouchX = ev.getX();
                mDragging = false;
                mLastHapticValue = getValue();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - mLastTouchX;
                if (!mDragging && Math.abs(dx) > mTouchSlop) {
                    mDragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (mDragging) {
                    // Dragging right = pull smaller values to the center → mScrollValue decreases.
                    mScrollValue = clampContinuous(mScrollValue - dx / mSpacingPx * mStep);
                    mLastTouchX = ev.getX();

                    int nowValue = getValue();
                    if (nowValue != mLastHapticValue) {
                        emitHapticForValue(nowValue);
                        mLastHapticValue = nowValue;
                        if (mListener != null) mListener.onChanged(nowValue, /*fromUser=*/true);
                    }
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mDragging) {
                    animateSnap();   // magnetic release
                }
                mDragging = false;
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }
}
