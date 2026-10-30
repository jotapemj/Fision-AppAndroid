package com.jotapeveloper.fision.chords;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.jotapeveloper.fision.R;

/**
 * Segmented selector: an outlined pill with one white chip that slides to
 * the tapped option. Selected label in black bold, the rest in white.
 */
public class SlidingChipSelector extends View {

    public interface OnSelectedListener { void onSelected(int index); }

    /** A label ending in this is a dotted value; the dot is drawn, not typed. */
    public static final String DOT = "·";

    private String[] options = new String[0];
    private int selected = 0;
    /** Chip position in option units (animated). */
    private float chipPos = 0f;
    @Nullable private OnSelectedListener listener;
    @Nullable private ValueAnimator anim;

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Typeface regular;
    private final Typeface bold;

    public SlidingChipSelector(Context c) { this(c, null); }

    public SlidingChipSelector(Context c, @Nullable AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1f * d);
        outline.setColor(Color.argb(90, 255, 255, 255));
        chip.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        Typeface tf = null;
        try { tf = ResourcesCompat.getFont(c, R.font.noto_sans_regular); } catch (Exception ignored) {}
        regular = tf != null ? tf : Typeface.DEFAULT;
        bold = Typeface.create(regular, Typeface.BOLD);
    }

    public void setOptions(String... options) {
        this.options = options;
        selected = Math.min(selected, Math.max(0, options.length - 1));
        chipPos = selected;
        invalidate();
    }

    public void setOnSelectedListener(@Nullable OnSelectedListener l) { listener = l; }

    public int getSelected() { return selected; }

    /** Selects [index]; animated slide when [animate]. No callback. */
    public void setSelected(int index, boolean animate) {
        if (index < 0 || index >= options.length) return;
        selected = index;
        if (anim != null) anim.cancel();
        if (!animate) { chipPos = index; invalidate(); return; }
        anim = ValueAnimator.ofFloat(chipPos, index);
        anim.setDuration(260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(v -> { chipPos = (float) v.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float d = getResources().getDisplayMetrics().density;
        int h = (int) (30 * d);
        setMeasuredDimension(MeasureSpec.getSize(wSpec), resolveSize(h, hSpec));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (options.length == 0 || !isEnabled()) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float seg = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) options.length;
            int idx = (int) ((e.getX() - getPaddingLeft()) / seg);
            idx = Math.max(0, Math.min(options.length - 1, idx));
            if (idx != selected) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                setSelected(idx, true);
                if (listener != null) listener.onSelected(idx);
            }
            performClick();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    @Override
    protected void onDraw(Canvas c) {
        int n = options.length;
        if (n == 0) return;
        float left = getPaddingLeft(), top = getPaddingTop();
        float w = getWidth() - left - getPaddingRight();
        float h = getHeight() - top - getPaddingBottom();
        float inset = outline.getStrokeWidth() / 2f;
        float rad = h / 2f;
        r.set(left + inset, top + inset, left + w - inset, top + h - inset);
        c.drawRoundRect(r, rad, rad, outline);
        float seg = w / n;
        r.set(left + chipPos * seg, top, left + (chipPos + 1) * seg, top + h);
        c.drawRoundRect(r, rad, rad, chip);
        float ty = top + h / 2f - (text.descent() + text.ascent()) / 2f;
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < n; i++) {
            // Label turns black while the chip covers it (crossfade by overlap).
            float cover = Math.max(0f, 1f - Math.abs(chipPos - i));
            text.setTypeface(cover > 0.5f ? bold : regular);
            int v = (int) (255 * (1f - cover));
            text.setColor(Color.argb(cover > 0.5f ? 255 : 210, v, v, v));
            float cx = left + (i + 0.5f) * seg;
            String label = options[i];
            if (!label.endsWith(DOT)) {
                c.drawText(label, cx, ty, text);
                continue;
            }
            // Dotted value: the font's middle dot is too small to read, so the
            // dot is drawn as a round mark at mid-height of the digits, like
            // the dot after a note. Label and dot are centred as one group.
            String base = label.substring(0, label.length() - DOT.length());
            float tw = text.measureText(base);
            float dotR = (cover > 0.5f ? 1.9f : 1.7f) * d;
            float gap = 2.2f * d;
            float total = tw + gap + 2f * dotR;
            float x0 = cx - total / 2f;
            c.drawText(base, x0 + tw / 2f, ty, text);
            c.drawCircle(x0 + tw + gap + dotR, ty - 0.36f * text.getTextSize(), dotR, text);
        }
    }
}
