package com.jotapeveloper.fision.brand;

import static com.jotapeveloper.fision.brand.FisionMotion.*;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Draws one frame of the Fision mark in logo units (512 grid). The caller
 * sets the canvas transform and the color; the splash composites the result
 * with the logo gradient.
 */
final class FisionRenderer {

    /** Frame state. */
    static final class State {
        /** Arc head and tail in turns; head - tail >= 1 is the closed ring. */
        float head = 1f, tail = 0f;
        /** Nucleus opacity and appear scale. */
        float alpha = 1f, grow = 1f;
        /** Ms since the ring closed, or NO_TF while spinning. */
        float tf = NO_TF;
    }

    private final Paint round = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint butt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path notePath = new Path();
    private final float[][] fragDir = new float[FRAGS.length][3];

    FisionRenderer() {
        round.setStyle(Paint.Style.STROKE);
        round.setStrokeCap(Paint.Cap.ROUND);
        round.setStrokeJoin(Paint.Join.ROUND);
        butt.setStyle(Paint.Style.STROKE);
        butt.setStrokeCap(Paint.Cap.BUTT);
        butt.setStrokeWidth(BAR_W);
        fill.setStyle(Paint.Style.FILL);
        note.setStyle(Paint.Style.STROKE);
        note.setStrokeWidth(7f);
        note.setStrokeCap(Paint.Cap.BUTT);
        note.setStrokeJoin(Paint.Join.ROUND);

        // Beamed eighth note inside the nucleus (own design, 7 / 512 stroke).
        notePath.moveTo(207.5f, 287.5f);
        notePath.lineTo(207.5f, 227.4f);
        notePath.quadTo(207.5f, 215.4f, 219.29f, 213.17f);
        notePath.lineTo(260.21f, 205.43f);
        notePath.quadTo(272f, 203.2f, 272f, 215.2f);
        notePath.lineTo(272f, 275.8f);
        notePath.moveTo(207.5f, 242.8f);
        notePath.lineTo(272f, 231.5f);
        notePath.addCircle(189.5f, 287.5f, 18f, Path.Direction.CW);
        notePath.addCircle(254f, 275.8f, 18f, Path.Direction.CW);

        for (int i = 0; i < FRAGS.length; i++) {
            float dx = FRAGS[i][0] - ATOM_X, dy = FRAGS[i][1] - ATOM_Y;
            float d = (float) Math.hypot(dx, dy);
            fragDir[i][0] = dx / d; fragDir[i][1] = dy / d; fragDir[i][2] = d;
        }
    }

    private static void color(Paint p, int color, float a) {
        p.setColor(color);
        p.setAlpha(Math.round(Color.alpha(color) * c01(a)));
    }

    void draw(Canvas c, State s, boolean withNote, int color) {
        float tf = s.tf;

        // Nucleus bounce: damped spring pushed to the right by the impact.
        float bx = 0f, bs = 1f;
        if (tf >= IMPACT) {
            float u = tf - IMPACT;
            float env = (float) Math.exp(-u / BOUNCE_TAU);
            float osc = (float) Math.sin(2 * Math.PI * u / BOUNCE_T);
            bx = BOUNCE_A * env * osc;
            bs = 1f + 0.035f * env * Math.abs(osc);
        }
        float ax = ATOM_X + bx;
        float scale = bs * s.grow;

        // Bonds and spokes go under the rings.
        if (tf != NO_TF) drawBonds(c, tf, ax, color);

        // Neutron: enters already moving with a fade-in, hits, recoils, settles.
        if (tf >= N_START) {
            float nx, na;
            round.setStrokeWidth(NEU_SW);
            if (tf < IMPACT) {
                float p = (tf - N_START) / N_FLY;
                nx = N_START_X + (NEU_CONTACT - N_START_X) * p;
                na = smoother(c01(p / N_FADE));
                float v = (NEU_CONTACT - N_START_X) / N_FLY;
                for (int i = 0; i < N_GHOSTS; i++) {
                    color(round, color, na * 0.22f / (i + 1));
                    c.drawCircle(nx - v * 16f * (i + 1), NEU_Y, NEU_RM, round);
                }
            } else {
                nx = herm(NEU_CONTACT, -N_RECOIL_V, NEU_X, 0f, N_RECOIL, tf - IMPACT);
                na = 1f;
                // Bond born at the impact, stretched by the recoil.
                color(butt, color, 1f);
                c.drawLine(nx + NEU_RM, NEU_Y, ax - ATOM_RM, ATOM_Y, butt);
            }
            color(round, color, na);
            c.drawCircle(nx, NEU_Y, NEU_RM, round);
        }

        // Nucleus: spinner arc or closed ring.
        float f = s.head - s.tail;
        float rot = ((s.tail % 1f) + 1f) % 1f * 360f + START_DEG;
        c.save();
        c.translate(ax, ATOM_Y);
        c.scale(scale, scale);
        c.translate(-ATOM_X, -ATOM_Y);
        round.setStrokeWidth(ATOM_SW);
        color(round, color, s.alpha);
        if (f >= 0.999f) {
            c.drawCircle(ATOM_X, ATOM_Y, ATOM_RM, round);
        } else {
            rect.set(ATOM_X - ATOM_RM, ATOM_Y - ATOM_RM, ATOM_X + ATOM_RM, ATOM_Y + ATOM_RM);
            c.drawArc(rect, rot, Math.max(0.5f, f * 360f), false, round);
        }
        if (withNote) {
            color(note, color, s.alpha);
            c.drawPath(notePath, note);
        }
        c.restore();

        if (tf != NO_TF) drawFragments(c, tf, color);
    }

    /** Fragment position along its spoke, or -1 when not born yet. */
    private float fragDistance(int i, float tf) {
        float p = tf < 0 ? 0f : c01((tf - FRAG_START) / FRAG_DUR);
        if (p <= 0f) return -1f;
        float d0 = ATOM_RM + ATOM_SW / 2f;
        return d0 + (fragDir[i][2] - d0) * backOut(p, 1.5f);
    }

    private void drawBonds(Canvas c, float tf, float ax, int color) {
        // Spokes from the nucleus ring to each fragment ring.
        for (int i = 0; i < FRAGS.length; i++) {
            float d = fragDistance(i, tf);
            if (d < 0f) continue;
            float p = c01((tf - FRAG_START) / FRAG_DUR);
            float dx = fragDir[i][0], dy = fragDir[i][1];
            float sc = 0.3f + 0.7f * easeOut(p);
            float x = ATOM_X + dx * d, y = ATOM_Y + dy * d;
            float sx = ax + dx * ATOM_RM, sy = ATOM_Y + dy * ATOM_RM;
            float ex = x - dx * FRAG_RM * sc, ey = y - dy * FRAG_RM * sc;
            if ((ex - sx) * dx + (ey - sy) * dy > 0) {
                color(butt, color, c01(p / 0.2f));
                c.drawLine(sx, sy, ex, ey, butt);
            }
        }
        // Right bar towards the released neutrons.
        float pb = tf < 0 ? 0f : c01((tf - SQ_START) / SQ_DUR);
        if (pb > 0f) {
            color(butt, color, c01(pb / 0.15f));
            c.drawLine(ax + ATOM_RM, ATOM_Y,
                    ax + ATOM_RM + (RBAR_END - ATOM_X - ATOM_RM) * easeOut(pb), ATOM_Y, butt);
        }
    }

    private void drawFragments(Canvas c, float tf, int color) {
        // Fragments bud from the nucleus edge, grow and settle with overshoot.
        for (int i = 0; i < FRAGS.length; i++) {
            float d = fragDistance(i, tf);
            if (d < 0f) continue;
            float p = c01((tf - FRAG_START) / FRAG_DUR);
            float sc = 0.3f + 0.7f * easeOut(p);
            round.setStrokeWidth(FRAG_SW * sc);
            color(round, color, c01(p / 0.2f));
            c.drawCircle(ATOM_X + fragDir[i][0] * d, ATOM_Y + fragDir[i][1] * d, FRAG_RM * sc, round);
        }
        // Released neutrons (squares).
        for (int i = 0; i < SQ.length; i++) {
            float p = tf < 0 ? 0f : c01((tf - SQ_START - i * SQ_STAG) / SQ_DUR);
            if (p <= 0f) continue;
            float x0 = ATOM_X + ATOM_RM + ATOM_SW / 2f, e = backOut(p, 1.3f);
            float x = x0 + (SQ[i][0] - x0) * e, y = ATOM_Y + (SQ[i][1] - ATOM_Y) * e;
            float z = SQ_S * (0.3f + 0.7f * easeOut(p)) / 2f;
            color(fill, color, c01(p / 0.2f));
            c.drawRect(x - z, y - z, x + z, y + z, fill);
        }
    }
}
