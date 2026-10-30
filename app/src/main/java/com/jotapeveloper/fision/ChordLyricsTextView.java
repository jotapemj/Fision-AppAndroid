package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.util.AttributeSet;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import java.util.Collections;
import java.util.List;

/**
 * Lyric line that can draw chord names above its words. Each chord is
 * anchored to a character offset (the start of a word). The room for the
 * names is a band above every line: the top padding for the first one and
 * extra line spacing for the rest, so the line breaks never change. The
 * band grows with {@link #setChordReveal} (0 = no chords, 1 = full) for the
 * on / off transition. Chords already reached by the playback position use
 * one colour and the rest another.
 */
public class ChordLyricsTextView extends AppCompatTextView {

    /** A chord name anchored at [offset]: the start of the word it falls on,
     *  or, [after], the end of the word it follows (a change in a silence). */
    public static final class Anchor {
        final int offset;
        final String label;
        final double time;
        final boolean after;
        public Anchor(int offset, String label, double time, boolean after) {
            this.offset = offset; this.label = label; this.time = time; this.after = after;
        }
    }

    private static final float CHORD_SP = 17f;
    private static final float GAP_DP = 3f;      // between the chord and the words
    private static final float SPACING_DP = 8f;  // between two chords on the same line

    private final Paint chordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float baseSpacingExtra;
    private final int basePaddingTop;
    private float reveal = 0f;
    private List<Anchor> anchors = Collections.emptyList();
    private int reachedColor, pendingColor;
    private double posSec = Double.NEGATIVE_INFINITY;

    public ChordLyricsTextView(@NonNull Context c) { this(c, null); }
    public ChordLyricsTextView(@NonNull Context c, @Nullable AttributeSet a) {
        super(c, a);
        chordPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CHORD_SP,
                getResources().getDisplayMetrics()));
        baseSpacingExtra = getLineSpacingExtra();
        basePaddingTop = getPaddingTop();
    }

    /** Height of the band reserved above each line: the chord height, less
     *  the empty room the font already leaves above the tallest letters. */
    private float bandPx() {
        Paint.FontMetrics fm = chordPaint.getFontMetrics();
        float chord = fm.descent - fm.ascent + dp(GAP_DP);
        float room = -getPaint().getFontMetrics().ascent + letterTop();
        return Math.max(chord * 0.6f, chord - room);
    }

    private final android.graphics.Rect bounds = new android.graphics.Rect();

    /** Top of the tallest plain letters, relative to the baseline (negative). */
    private float letterTop() {
        getPaint().getTextBounds("Hdlfkt", 0, 6, bounds);
        return bounds.top;
    }

    /** Room and opacity of the chords: 0 hidden, 1 shown. */
    public void setChordReveal(float f) {
        f = Math.max(0f, Math.min(1f, f));
        if (f == reveal && (f > 0f || getPaddingTop() == basePaddingTop)) return;
        reveal = f;
        float band = bandPx() * f;
        setLineSpacing(baseSpacingExtra + band, 1f);
        setPaddingRelative(getPaddingStart(), basePaddingTop + Math.round(band),
                getPaddingEnd(), getPaddingBottom());
        invalidate();
    }

    public void setChords(@Nullable List<Anchor> anchors, @Nullable Typeface tf,
                          int reachedColor, int pendingColor, double posSec) {
        this.anchors = anchors != null ? anchors : Collections.emptyList();
        if (tf != null) chordPaint.setTypeface(tf);
        this.reachedColor = reachedColor;
        this.pendingColor = pendingColor;
        this.posSec = posSec;
        invalidate();
    }

    /** Moves the playback position; repaints only when a chord changes colour. */
    public void setChordPos(double posSec) {
        double old = this.posSec;
        this.posSec = posSec;
        for (Anchor a : anchors) {
            if ((a.time <= old) != (a.time <= posSec)) { invalidate(); return; }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Layout layout = getLayout();
        if (anchors.isEmpty() || layout == null || reveal <= 0f) return;
        Paint.FontMetrics fm = chordPaint.getFontMetrics();
        float textTop = letterTop();
        float left = getTotalPaddingLeft(), top = getTotalPaddingTop();
        float maxX = getWidth() - getTotalPaddingRight();
        int alpha = Math.round(255 * reveal);
        int lastLine = -1;
        float lineRight = 0f;
        for (Anchor a : anchors) {
            int off = Math.max(0, Math.min(a.offset, layout.getText().length()));
            // The end of a word belongs to its line even when it ends it.
            int line = layout.getLineForOffset(a.after ? Math.max(0, off - 1) : off);
            float w = chordPaint.measureText(a.label);
            float x = left + (a.after
                    ? layout.getPrimaryHorizontal(Math.max(0, off - 1))
                            + getPaint().measureText(layout.getText(), Math.max(0, off - 1), off)
                            + dp(SPACING_DP)
                    : layout.getPrimaryHorizontal(off));
            // Two chords on neighbouring short words would overlap: the later
            // one moves right of the earlier.
            if (line == lastLine) x = Math.max(x, lineRight + dp(SPACING_DP));
            x = Math.min(x, Math.max(left, maxX - w));
            // Right above the letters of its line, whatever the line height.
            float baseline = top + layout.getLineBaseline(line) + textTop - dp(GAP_DP) - fm.descent * 0.5f;
            chordPaint.setColor(a.time <= posSec ? reachedColor : pendingColor);
            chordPaint.setAlpha(Math.round(alphaOf(chordPaint.getColor()) * alpha / 255f));
            canvas.drawText(a.label, x, baseline, chordPaint);
            lastLine = line;
            lineRight = x + w;
        }
    }

    private static int alphaOf(int argb) { return (argb >>> 24) & 0xFF; }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
