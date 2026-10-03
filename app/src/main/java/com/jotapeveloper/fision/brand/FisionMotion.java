package com.jotapeveloper.fision.brand;

/**
 * Geometry and kinematics of the animated Fision mark: the nucleus as a
 * spinner that closes into a ring, and the fission sequence (neutron impact,
 * fragments and released neutrons). Units: logo units (512 grid of the app
 * logo), milliseconds and turns.
 */
final class FisionMotion {

    private FisionMotion() {}

    // ---- Geometry (512 grid) ----
    static final float ATOM_X = 225f, ATOM_Y = 255.5f, ATOM_RM = 90f, ATOM_SW = 29f;
    static final float NEU_X = 44.5f, NEU_Y = 255.5f, NEU_RM = 29.6f, NEU_SW = 30f;
    static final float[][] FRAGS = {{404.5f, 75f}, {404.5f, 436f}};
    static final float FRAG_RM = 59f, FRAG_SW = 30f;
    static final float[][] SQ = {{435.5f, 225.5f}, {495.5f, 255.5f}, {435.5f, 285.5f}};
    static final float SQ_S = 30f, BAR_W = 30f, RBAR_END = 389f;
    /** Neutron center when it touches the nucleus. */
    static final float NEU_CONTACT = ATOM_X - (ATOM_RM + ATOM_SW / 2f) - (NEU_RM + NEU_SW / 2f);

    // ---- Spinner ----
    static final float P = 1100f;              // extension / retraction cycle
    static final float W = 1f / 1400f;         // base rotation, turns per ms
    static final float L_MIN = 0.08f, L_EXT = 0.62f;
    static final float APPEAR = 220f;          // initial fade-in
    static final float MIN_SPIN = 600f;        // minimum spin before closing
    static final float D_CLOSE_MIN = 380f, D_CLOSE_MAX = 900f;
    static final float RATIO_MAX = 2.6f;       // v*D/delta: monotonic Hermite, no overshoot
    static final float HEAD_AVG_MAX = 1f / 450f;
    static final float START_DEG = -90f;       // the arc starts and closes at 12 o'clock

    // ---- Fission sequence (ms from the moment the ring closes) ----
    static final float N_START = -140f, N_FLY = 240f, IMPACT = N_START + N_FLY;
    static final float N_RECOIL = 320f, N_RECOIL_V = 0.24f, N_START_X = -110f, N_FADE = 0.8f;
    static final int N_GHOSTS = 3;
    static final float BOUNCE_A = 11f, BOUNCE_TAU = 130f, BOUNCE_T = 280f;
    static final float FRAG_START = IMPACT + 70f, FRAG_DUR = 620f;
    static final float SQ_START = IMPACT + 150f, SQ_STAG = 60f, SQ_DUR = 560f;
    static final float SETTLED = SQ_START + 2 * SQ_STAG + SQ_DUR;
    /** Marker for "no fission phase" (spinning). */
    static final float NO_TF = -1e9f;

    // ---- Easing ----
    static float c01(float x) { return Math.max(0f, Math.min(1f, x)); }
    static float easeOut(float x) { float i = 1f - x; return 1f - i * i * i; }
    static float smoother(float x) { return x * x * x * (x * (6f * x - 15f) + 10f); }
    static float backOut(float x, float k) {
        float d = x - 1f;
        return 1f + (k + 1f) * d * d * d + k * d * d;
    }
    static float herm(float p0, float v0, float p1, float v1, float dur, float t) {
        float s = c01(t / dur), s2 = s * s, s3 = s2 * s;
        return (2 * s3 - 3 * s2 + 1) * p0 + (s3 - 2 * s2 + s) * dur * v0
                + (-2 * s3 + 3 * s2) * p1 + (s3 - s2) * dur * v1;
    }

    /** Steady spinner at time [u]: out[0] = head, out[1] = tail (turns). The
     *  head extends during the first half of each cycle, the tail catches up
     *  during the second. */
    static void steady(float u, float[] out) {
        int k = (int) Math.floor(u / P);
        float s = u / P - k;
        float base = W * u + k * L_EXT;
        out[0] = base + L_MIN + L_EXT * smoother(c01(2f * s));
        out[1] = base + L_EXT * smoother(c01(2f * s - 1f));
    }

    /** Closing plan from any instant of the spin. */
    static final class ClosePlan {
        float h0, t0, vH, vT, dur, t1;
        int extraTurns;
    }

    /** The tail brakes into the starting point (whole turn) and the head
     *  catches it; both stop together. One more turn when the starting point is
     *  too close to brake smoothly. */
    static ClosePlan closePlan(float u0) {
        float[] s0 = new float[2], a = new float[2], b = new float[2];
        steady(u0, s0);
        steady(u0 - 0.5f, a);
        steady(u0 + 0.5f, b);
        float vH = b[0] - a[0], vT = b[1] - a[1], f0 = s0[0] - s0[1];
        float dT = (float) Math.ceil(s0[1]) - s0[1];
        for (int k = 0; k < 4; k++, dT += 1f) {
            float dH = dT + 1f - f0;
            float d = Math.max(D_CLOSE_MIN, Math.min(D_CLOSE_MAX,
                    Math.max(2f * dT / vT, dH / HEAD_AVG_MAX)));
            if (vT * d / dT <= RATIO_MAX && vH * d / dH <= RATIO_MAX) {
                ClosePlan p = new ClosePlan();
                p.h0 = s0[0]; p.t0 = s0[1]; p.vH = vH; p.vT = vT;
                p.dur = Math.round(d); p.t1 = s0[1] + dT; p.extraTurns = k;
                return p;
            }
        }
        // Not reachable with the current constants; fall back to a plain stop.
        ClosePlan p = new ClosePlan();
        p.h0 = s0[0]; p.t0 = s0[1]; p.vH = vH; p.vT = vT; p.dur = D_CLOSE_MAX;
        p.t1 = s0[1] + dT;
        return p;
    }

    /** Arc while closing: out[0] = head, out[1] = tail. */
    static void closeArc(ClosePlan p, float t, float[] out) {
        float tail = herm(p.t0, p.vT, p.t1, 0f, p.dur, t);
        float head = herm(p.h0, p.vH, p.t1 + 1f, 0f, p.dur, t);
        out[0] = Math.min(tail + 1f, head);   // the head never passes the joint
        out[1] = tail;
    }
}
