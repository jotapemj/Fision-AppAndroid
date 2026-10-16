package com.jotapeveloper.fision;

import java.util.Arrays;

/**
 * Where the voice is actually sounding, measured on the vocals stem. Built
 * from the loudness envelope of the track (dBFS every
 * {@link AudioEngine#ENVELOPE_HOP_MS}): a frame is voiced above a threshold
 * relative to the song's own vocal level, short silences between syllables
 * are bridged and short blips (breaths, separation leftovers) dropped.
 * Times are in seconds of the original song.
 */
public final class VocalActivity {

    private static final double HOP = AudioEngine.ENVELOPE_HOP_MS / 1000.0;
    /** Threshold below the loud vocal level (95th percentile). */
    private static final float RANGE_DB = 24f;
    private static final float FLOOR_DB = -60f;
    /** Silences shorter than this are joined (gaps between syllables). */
    private static final double MIN_SILENCE_SEC = 0.25;
    /** Finer bridge for pauses inside a line (still above consonant stops). */
    private static final double MIN_SILENCE_FINE_SEC = 0.12;
    /** Voiced runs shorter than this are dropped. */
    private static final double MIN_VOICE_SEC = 0.08;

    /** A pause that does not reach silence (reverb, decaying note): a
     *  valley this far below the level sung just before, lasting at least
     *  MIN_VALLEY_SEC. Consonant dips are shorter. */
    private static final float VALLEY_DB = 12f;
    private static final double MIN_VALLEY_SEC = 0.12;
    /** The word is lit this much before the valley ends (its consonant). */
    private static final double CONSONANT_LEAD_SEC = 0.05;

    private final boolean[] voiced;
    private final boolean[] voicedFine;
    /** Envelope smoothed over 3 frames. */
    private final float[] level;

    private VocalActivity(boolean[] voiced, boolean[] voicedFine, float[] level) {
        this.voiced = voiced;
        this.voicedFine = voicedFine;
        this.level = level;
    }

    /** Several vocal stems (lead + backing) heard together: the loudest
     *  one per frame. Null when none has audible vocals. */
    public static VocalActivity fromEnvelopes(float[]... envs) {
        float[] db = null;
        for (float[] e : envs) {
            if (e == null || e.length == 0) continue;
            if (db == null) { db = e.clone(); continue; }
            int n = Math.min(db.length, e.length);
            float[] m = new float[n];
            for (int i = 0; i < n; i++) m[i] = Math.max(db[i], e[i]);
            db = m;
        }
        return fromEnvelope(db);
    }

    /** Null when the envelope is empty or has no audible vocals. */
    public static VocalActivity fromEnvelope(float[] db) {
        if (db == null || db.length == 0) return null;
        float[] audible = new float[db.length];
        int n = 0;
        for (float v : db) if (v > -80f) audible[n++] = v;
        if (n < 50) return null;
        Arrays.sort(audible, 0, n);
        float loud = audible[Math.min(n - 1, (int) (n * 0.95))];
        float thr = Math.max(loud - RANGE_DB, FLOOR_DB);

        boolean[] v = new boolean[db.length];
        for (int i = 0; i < db.length; i++) v[i] = db[i] > thr;
        boolean[] fine = v.clone();
        bridge(v, false, (int) Math.round(MIN_SILENCE_SEC / HOP));
        bridge(v, true, (int) Math.round(MIN_VOICE_SEC / HOP));
        bridge(fine, false, (int) Math.round(MIN_SILENCE_FINE_SEC / HOP));
        bridge(fine, true, (int) Math.round(MIN_VOICE_SEC / HOP));
        float[] level = new float[db.length];
        for (int i = 0; i < db.length; i++) {
            int a = Math.max(0, i - 1), b = Math.min(db.length - 1, i + 1);
            float sum = 0f;
            for (int k = a; k <= b; k++) sum += db[k];
            level[i] = sum / (b - a + 1);
        }
        return new VocalActivity(v, fine, level);
    }

    /** Flips runs of [value] shorter than [minLen] frames (inner runs only). */
    private static void bridge(boolean[] v, boolean value, int minLen) {
        int i = 0;
        while (i < v.length) {
            if (v[i] != value) { i++; continue; }
            int j = i;
            while (j < v.length && v[j] == value) j++;
            if (i > 0 && j < v.length && j - i < minLen) {
                for (int k = i; k < j; k++) v[k] = !value;
            }
            i = j;
        }
    }

    private int frame(double sec) {
        return (int) Math.max(0, Math.min(voiced.length - 1, Math.round(sec / HOP)));
    }

    /** Last moment in [from, to] where the voice comes back after a silence;
     *  NaN when the span has no such transition. */
    public double lastOnsetIn(double from, double to) {
        return lastOnset(voiced, from, to);
    }

    /** Onset of a word inside a line, after a short pause: last return of
     *  the voice in [from, to] (fine bridge), only when at least [minRemain]
     *  seconds are left until [to] to sing the word; otherwise the pause
     *  came after the word. NaN when not found. */
    public double innerOnsetIn(double from, double to, double minRemain) {
        double valley = valleyEndIn(from, to, minRemain);
        if (!Double.isNaN(valley)) return Math.max(from, valley - CONSONANT_LEAD_SEC);
        double m = lastOnset(voicedFine, from, to);
        return !Double.isNaN(m) && to - m >= minRemain ? m : Double.NaN;
    }

    /** End of the last valley in [from, to - minRemain]: a run below the
     *  level sung around [from] minus VALLEY_DB, at least MIN_VALLEY_SEC
     *  long. NaN when there is none. */
    private double valleyEndIn(double from, double to, double minRemain) {
        float ref = -Float.MAX_VALUE;
        for (int i = frame(from - 0.4); i <= frame(from + 0.1); i++) ref = Math.max(ref, level[i]);
        float thr = ref - VALLEY_DB;
        int a = frame(from), b = frame(to - minRemain);
        int minLen = (int) Math.round(MIN_VALLEY_SEC / HOP);
        double best = Double.NaN;
        int i = a;
        while (i <= b) {
            if (level[i] >= thr) { i++; continue; }
            int j = i;
            while (j < level.length && level[j] < thr) j++;
            if (j - i >= minLen && j <= b) best = j * HOP;
            i = j;
        }
        return best;
    }

    private double lastOnset(boolean[] v, double from, double to) {
        int a = frame(from), b = frame(to);
        for (int i = b; i > a; i--) {
            if (v[i] && !v[i - 1]) return i * HOP;
        }
        return Double.NaN;
    }

    /** Moment the voice stops after a word that starts at [wordStart]:
     *  end of the first voiced run found from there (searched up to
     *  [searchTo]), capped at [limit]. NaN when no voice is found. */
    public double voiceEndAfter(double wordStart, double searchTo, double limit) {
        int i = frame(wordStart), s = frame(searchTo), lim = frame(limit);
        while (i <= s && !voiced[i]) i++;
        if (i > s) return Double.NaN;
        while (i < lim && voiced[i]) i++;
        return i * HOP;
    }
}
