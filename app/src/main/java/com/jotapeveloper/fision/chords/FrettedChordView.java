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
 * Guitar / ukulele chord diagram in white on a transparent background:
 * vertical strings, five frets, nut (or "Nfr" base fret label), open and
 * muted markers, finger dots with their number and barres. Without a
 * position only the empty grid is drawn, dimmed.
 */
public class FrettedChordView extends View {

    private static final int FRETS = 5;

    private int strings = 6;
    @Nullable private ChordShapes.Position position;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fingerText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public FrettedChordView(Context c) { this(c, null); }

    public FrettedChordView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(Color.WHITE);
        Typeface bold = null;
        try { bold = ResourcesCompat.getFont(c, R.font.yugothic_ui_regular); } catch (Exception ignored) {}
        fingerText.setColor(Color.BLACK);
        fingerText.setTextAlign(Paint.Align.CENTER);
        fingerText.setTypeface(Typeface.create(bold != null ? bold : Typeface.DEFAULT, Typeface.BOLD));
        label.setColor(Color.WHITE);
        label.setTextAlign(Paint.Align.RIGHT);
        if (bold != null) label.setTypeface(bold);
    }

    public void setStrings(int strings) {
        if (this.strings == strings) return;
        this.strings = strings;
        invalidate();
    }

    public void setPosition(@Nullable ChordShapes.Position p) {
        position = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = strings;
        // Layout in string-spacing units: label margin left, marker row on top.
        float wUnits = (n - 1) + 1.5f + 0.6f;
        float hUnits = 0.95f + FRETS * 1.32f + 0.2f;
        float sp = Math.min((getWidth() - getPaddingLeft() - getPaddingRight()) / wUnits,
                (getHeight() - getPaddingTop() - getPaddingBottom()) / hUnits);
        if (sp <= 0) return;
        float fh = sp * 1.32f;
        float gridW = sp * (n - 1);
        float gridH = fh * FRETS;
        float x0 = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - gridW) / 2f + sp * 0.45f;
        float y0 = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - sp * hUnits) / 2f + sp * 0.95f;
        float lw = Math.max(1.5f, sp * 0.06f);
        ChordShapes.Position p = position;
        int gridAlpha = p == null ? 70 : 255;

        // Nut or base fret label.
        if (p == null || p.baseFret <= 1) {
            fill.setAlpha(gridAlpha);
            rect.set(x0 - lw / 2f, y0 - sp * 0.16f, x0 + gridW + lw / 2f, y0);
            canvas.drawRect(rect, fill);
        } else {
            label.setTextSize(sp * 0.55f);
            canvas.drawText(p.baseFret + "fr", x0 - sp * 0.45f, y0 + fh * 0.5f + sp * 0.2f, label);
        }
        // Frets and strings.
        line.setStrokeWidth(lw);
        line.setColor(Color.argb((int) (gridAlpha * 0.55f), 255, 255, 255));
        for (int i = 0; i <= FRETS; i++) {
            float y = y0 + i * fh;
            canvas.drawLine(x0, y, x0 + gridW, y, line);
        }
        line.setColor(Color.argb((int) (gridAlpha * 0.85f), 255, 255, 255));
        for (int s = 0; s < n; s++) {
            float x = x0 + s * sp;
            canvas.drawLine(x, y0, x, y0 + gridH, line);
        }
        fill.setAlpha(255);
        if (p == null) return;

        // Open / muted markers.
        float mr = sp * 0.24f;
        float my = y0 - sp * 0.55f;
        line.setColor(Color.WHITE);
        for (int s = 0; s < n && s < p.frets.length; s++) {
            float x = x0 + s * sp;
            if (p.frets[s] < 0) {
                float k = mr * 0.9f;
                canvas.drawLine(x - k, my - k, x + k, my + k, line);
                canvas.drawLine(x - k, my + k, x + k, my - k, line);
            } else if (p.frets[s] == 0) {
                canvas.drawCircle(x, my, mr, line);
            }
        }

        float dr = sp * 0.42f;
        fingerText.setTextSize(dr * 1.15f);
        float textDy = -(fingerText.descent() + fingerText.ascent()) / 2f;
        // Barres: from the first to the last string holding the barre fret.
        for (int b : p.barres) {
            int first = -1, last = -1;
            for (int s = 0; s < n && s < p.frets.length; s++) {
                if (p.frets[s] == b) { if (first < 0) first = s; last = s; }
            }
            if (first < 0 || last == first) continue;
            float y = y0 + (b - 0.5f) * fh;
            rect.set(x0 + first * sp - dr * 0.8f, y - dr, x0 + last * sp + dr * 0.8f, y + dr);
            canvas.drawRoundRect(rect, dr, dr, fill);
            int f = fingerAt(p, first);
            if (f > 0) canvas.drawText(String.valueOf(f), x0 + first * sp, y + textDy, fingerText);
        }
        // Finger dots not covered by a barre.
        for (int s = 0; s < n && s < p.frets.length; s++) {
            int fr = p.frets[s];
            if (fr <= 0 || isOnBarre(p, s, fr, n)) continue;
            float x = x0 + s * sp;
            float y = y0 + (fr - 0.5f) * fh;
            canvas.drawCircle(x, y, dr, fill);
            int f = fingerAt(p, s);
            if (f > 0) canvas.drawText(String.valueOf(f), x, y + textDy, fingerText);
        }
    }

    private static int fingerAt(ChordShapes.Position p, int s) {
        return p.fingers != null && s < p.fingers.length ? p.fingers[s] : 0;
    }

    private static boolean isOnBarre(ChordShapes.Position p, int s, int fr, int n) {
        for (int b : p.barres) {
            if (b != fr) continue;
            int first = -1, last = -1;
            for (int i = 0; i < n && i < p.frets.length; i++) {
                if (p.frets[i] == b) { if (first < 0) first = i; last = i; }
            }
            if (first >= 0 && last > first && s >= first && s <= last) return true;
        }
        return false;
    }
}
