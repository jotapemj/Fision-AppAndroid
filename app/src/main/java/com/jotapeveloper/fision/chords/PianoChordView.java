package com.jotapeveloper.fision.chords;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.jotapeveloper.fision.R;

/**
 * Two-octave piano keyboard in white outline. Keys of the chord carry a dot
 * and their note name underneath the keyboard.
 */
public class PianoChordView extends View {

    private static final int WHITES = 14;
    private static final int[] WHITE_PC = {0, 2, 4, 5, 7, 9, 11};
    /** Black key pitch class → index of the white key to its left. */
    private static final int[][] BLACKS = {{1, 0}, {3, 1}, {6, 3}, {8, 4}, {10, 5}};

    private int[] keys = new int[0];
    private String[] names = new String[0];

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blackFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public PianoChordView(Context c) { this(c, null); }

    public PianoChordView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.argb(180, 255, 255, 255));
        blackFill.setColor(Color.rgb(82, 82, 82));
        dot.setColor(Color.WHITE);
        label.setColor(Color.WHITE);
        label.setTextAlign(Paint.Align.CENTER);
        Typeface tf = null;
        try { tf = ResourcesCompat.getFont(c, R.font.noto_sans_regular); } catch (Exception ignored) {}
        label.setTypeface(Typeface.create(tf != null ? tf : Typeface.DEFAULT, Typeface.BOLD));
    }

    /** [keys] = sorted key indices 0..23; [names] = note name per key. */
    public void setKeys(int[] keys, String[] names) {
        this.keys = keys != null ? keys : new int[0];
        this.names = names != null ? names : new String[0];
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float availW = getWidth() - getPaddingLeft() - getPaddingRight();
        float availH = getHeight() - getPaddingTop() - getPaddingBottom();
        // Key height 4.2 key widths, two label rows below (white keys, then
        // black keys, so adjacent semitones never overlap).
        float kw = Math.min(availW / WHITES, availH / 5.6f);
        if (kw <= 0) return;
        float kh = kw * 4.2f;
        float x0 = getPaddingLeft() + (availW - kw * WHITES) / 2f;
        float y0 = getPaddingTop() + (availH - kw * 5.6f) / 2f;
        float lw = Math.max(1.5f, kw * 0.045f);
        float rad = kw * 0.08f;
        stroke.setStrokeWidth(lw);
        float dr = kw * 0.22f;
        label.setTextSize(kw * 0.42f);
        float labelY = y0 + kh + kw * 0.62f;

        for (int i = 0; i < WHITES; i++) {
            float x = x0 + i * kw;
            r.set(x, y0, x + kw, y0 + kh);
            canvas.drawRoundRect(r, rad, rad, stroke);
            int key = WHITE_PC[i % 7] + 12 * (i / 7);
            int at = indexOf(key);
            if (at >= 0) {
                float cx = x + kw / 2f;
                canvas.drawCircle(cx, y0 + kh - kw * 0.5f, dr, dot);
                if (at < names.length) canvas.drawText(names[at], cx, labelY, label);
            }
        }
        float bw = kw * 0.6f, bh = kh * 0.6f;
        for (int oct = 0; oct < 2; oct++) {
            for (int[] b : BLACKS) {
                float x = x0 + (b[1] + 1 + oct * 7) * kw - bw / 2f;
                r.set(x, y0, x + bw, y0 + bh);
                canvas.drawRoundRect(r, rad, rad, blackFill);
                canvas.drawRoundRect(r, rad, rad, stroke);
                int at = indexOf(b[0] + 12 * oct);
                if (at >= 0) {
                    float cx = x + bw / 2f;
                    canvas.drawCircle(cx, y0 + bh - kw * 0.42f, dr, dot);
                    if (at < names.length) canvas.drawText(names[at], cx, labelY + kw * 0.5f, label);
                }
            }
        }
    }

    private int indexOf(int key) {
        for (int i = 0; i < keys.length; i++) if (keys[i] == key) return i;
        return -1;
    }
}
