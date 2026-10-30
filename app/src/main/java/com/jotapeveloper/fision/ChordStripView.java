package com.jotapeveloper.fision;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * One row of chord names for an instrumental part of the lyrics. The chord
 * sounding now is lit; when the row is wider than the view it glides so the
 * current chord stays centred, and the edges fade out (only where more
 * chords are hidden).
 */
public class ChordStripView extends View {

    private static final float CHORD_SP = 20f;
    private static final float SPACING_DP = 22f;
    private static final float FADE_DP = 36f;
    private static final long GLIDE_MS = 380;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint();
    private String[] labels = new String[0];
    private double[] times = new double[0];
    private float[] widths = new float[0];
    private float contentWidth;
    private int current = -1;
    private float offset;              // x of the content start
    private ValueAnimator glide;
    private int litColor, pastColor, futureColor;

    public ChordStripView(Context c) { this(c, null); }
    public ChordStripView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CHORD_SP,
                getResources().getDisplayMetrics()));
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private float reveal = 1f;

    public boolean isEmpty() { return labels.length == 0; }

    /** Height and opacity for the on / off transition: 0 hidden, 1 shown. */
    public void setReveal(float f) {
        f = Math.max(0f, Math.min(1f, f));
        if (f == reveal) return;
        reveal = f;
        requestLayout();
        invalidate();
    }

    public void setChords(String[] labels, double[] times, @Nullable Typeface tf) {
        this.labels = labels;
        this.times = times;
        if (tf != null) paint.setTypeface(tf);
        widths = new float[labels.length];
        contentWidth = 0f;
        for (int i = 0; i < labels.length; i++) {
            widths[i] = paint.measureText(labels[i]);
            contentWidth += widths[i] + (i > 0 ? dp(SPACING_DP) : 0f);
        }
        current = -1;
        if (glide != null) glide.cancel();
        offset = targetOffset(-1);
        requestLayout();
        invalidate();
    }

    public void setColors(int lit, int past, int future) {
        litColor = lit; pastColor = past; futureColor = future;
        invalidate();
    }

    /** Lights the chord sounding at [posSec] (negative: none). */
    public void setPos(double posSec, boolean animate) {
        int idx = -1;
        for (int i = 0; i < times.length; i++) if (times[i] <= posSec) idx = i;
        if (idx == current) return;
        current = idx;
        float to = targetOffset(idx);
        if (glide != null) glide.cancel();
        if (!animate || getWidth() == 0) {
            offset = to;
            invalidate();
            return;
        }
        glide = ValueAnimator.ofFloat(offset, to);
        glide.setDuration(GLIDE_MS);
        glide.setInterpolator(new DecelerateInterpolator());
        glide.addUpdateListener(v -> { offset = (float) v.getAnimatedValue(); invalidate(); });
        glide.start();
    }

    /** Content start so that chord [idx] is centred, clamped to the ends. */
    private float targetOffset(int idx) {
        int w = getWidth();
        if (contentWidth <= w || w == 0) return (w - contentWidth) / 2f;
        float center = 0f;
        if (idx >= 0) {
            float x = 0f;
            for (int i = 0; i < idx; i++) x += widths[i] + dp(SPACING_DP);
            center = x + widths[idx] / 2f;
        }
        float o = w / 2f - center;
        return Math.max(w - contentWidth, Math.min(0f, o));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (glide != null) glide.cancel();
        offset = targetOffset(current);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        int h = labels.length == 0 ? 0
                : Math.round((fm.descent - fm.ascent + getPaddingTop() + getPaddingBottom()) * reveal);
        setMeasuredDimension(MeasureSpec.getSize(wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (labels.length == 0 || reveal <= 0f) return;
        Paint.FontMetrics fm = paint.getFontMetrics();
        // Anchored to the bottom, so the names rise in as the row opens.
        float full = fm.descent - fm.ascent + getPaddingTop() + getPaddingBottom();
        float baseline = getHeight() - full + getPaddingTop() - fm.ascent;
        float x = offset;
        for (int i = 0; i < labels.length; i++) {
            int c = i == current ? litColor : (i < current ? pastColor : futureColor);
            paint.setColor(c);
            paint.setAlpha(Math.round(((c >>> 24) & 0xFF) * reveal));
            canvas.drawText(labels[i], x, baseline, paint);
            x += widths[i] + dp(SPACING_DP);
        }
        // Edge fades only where chords are hidden past that edge.
        int w = getWidth(), h = getHeight();
        float fade = dp(FADE_DP);
        if (offset < -0.5f) {
            fadePaint.setShader(new LinearGradient(0, 0, fade, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, fade, h, fadePaint);
        }
        if (offset + contentWidth > w + 0.5f) {
            fadePaint.setShader(new LinearGradient(w - fade, 0, w, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            canvas.drawRect(w - fade, 0, w, h, fadePaint);
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
