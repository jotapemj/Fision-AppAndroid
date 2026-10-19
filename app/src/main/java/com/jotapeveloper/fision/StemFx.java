package com.jotapeveloper.fision;

/**
 * FX of one stem: bipolar filter, tempo echo and reverb send. Lives only in
 * memory for the loaded song (never persisted).
 */
public class StemFx {

    /** Echo divisions in beats: 1/2, 1/4, dotted 1/8, 1/8, eighth triplet ("1/3"), 1/16. */
    public static final float[] ECHO_BEATS = {2f, 1f, 0.75f, 0.5f, 1f / 3f, 0.25f};
    public static final String[] ECHO_LABELS = {"1/2", "1/4", "1/8·", "1/8", "1/3", "1/16"};
    public static final int DEFAULT_ECHO_DIVISION = 2;
    /** Tempo used for the echo when the song has no detected BPM. */
    public static final float FALLBACK_BPM = 120f;

    /** -1 (low-pass closed) .. 0 (off) .. 1 (high-pass closed). */
    public float filter = 0f;
    /** 0..1. */
    public float echo = 0f;
    public int echoDivision = DEFAULT_ECHO_DIVISION;
    /** 0..1. */
    public float reverb = 0f;
    /** Reverb room: 0 small, 1 medium, 2 large. */
    public int reverbRoom = DEFAULT_REVERB_ROOM;
    public static final int DEFAULT_REVERB_ROOM = 1;

    public boolean isActive() {
        return filter != 0f || echo > 0f || reverb > 0f;
    }

    /** Echo or reverb keep sounding after the stem ends. */
    public boolean hasTail() {
        return echo > 0f || reverb > 0f;
    }

    public StemFx copy() {
        StemFx c = new StemFx();
        c.filter = filter; c.echo = echo; c.echoDivision = echoDivision; c.reverb = reverb;
        c.reverbRoom = reverbRoom;
        return c;
    }

    /** Echo delay in source milliseconds (original tempo). */
    public float echoDelayMs(float bpm) {
        float b = bpm > 0f ? bpm : FALLBACK_BPM;
        int d = Math.max(0, Math.min(ECHO_BEATS.length - 1, echoDivision));
        return 60000f / b * ECHO_BEATS[d];
    }

    /** Labels for the division chips: note values with a BPM, milliseconds without. */
    public static String[] echoLabels(float bpm) {
        if (bpm > 0f) return ECHO_LABELS.clone();
        String[] l = new String[ECHO_BEATS.length];
        for (int i = 0; i < l.length; i++) {
            l[i] = Math.round(60000f / FALLBACK_BPM * ECHO_BEATS[i]) + " ms";
        }
        return l;
    }

    /** Filter cutoff in Hz for the current amount (same mapping as the engine). */
    public static float filterCutoffHz(float amount) {
        float a = Math.abs(amount);
        return amount > 0f ? (float) (20.0 * Math.pow(750.0, a))
                           : (float) (20000.0 * Math.pow(0.01, a));
    }
}
