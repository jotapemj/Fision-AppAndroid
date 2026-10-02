package com.jotapeveloper.fision.chords;

import androidx.annotation.Nullable;

/**
 * Piano notes of a chord on a two-octave keyboard (keys 0..23, C = 0). The
 * bass (root, or the inversion bass) sits lowest; the other chord tones
 * stack right above it.
 */
public final class PianoVoicing {

    public static final int KEYS = 24;

    private PianoVoicing() {}

    /** Semitone intervals above the root for a chords-db quality. */
    static int[] intervals(String quality) {
        switch (quality) {
            case ChordSymbol.MINOR: return new int[] {0, 3, 7};
            case "dim":   return new int[] {0, 3, 6};
            case "dim7":  return new int[] {0, 3, 6, 9};
            case "aug":   return new int[] {0, 4, 8};
            case "6":     return new int[] {0, 4, 7, 9};
            case "m6":    return new int[] {0, 3, 7, 9};
            case "7":     return new int[] {0, 4, 7, 10};
            case "m7":    return new int[] {0, 3, 7, 10};
            case "maj7":  return new int[] {0, 4, 7, 11};
            case "m7b5":  return new int[] {0, 3, 6, 10};
            case "mmaj7": return new int[] {0, 3, 7, 11};
            case "sus2":  return new int[] {0, 2, 7};
            case "sus4":  return new int[] {0, 5, 7};
            case "9":     return new int[] {0, 4, 7, 10, 14};
            case "m9":    return new int[] {0, 3, 7, 10, 14};
            case "add9":  return new int[] {0, 4, 7, 14};
            default:      return new int[] {0, 4, 7};
        }
    }

    /** Sorted key indices (0..23) of [c], or an empty array for no chord. */
    public static int[] keys(@Nullable ChordSymbol c) {
        if (c == null) return new int[0];
        int[] iv = intervals(c.quality);
        int bass = c.bass >= 0 ? c.bass : c.root;
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        out.add(bass);
        for (int i : iv) {
            int pc = (c.root + i) % 12;
            if (pc == bass && i < 12) continue;
            // Lowest key above the bass with this pitch class; ninths an
            // octave higher when there is room.
            int k = bass + ((pc - bass) % 12 + 12) % 12;
            if (k == bass) k += 12;
            if (i >= 12 && k + 12 < KEYS) k += 12;
            if (k < KEYS) out.add(k);
        }
        int[] r = new int[out.size()];
        int n = 0;
        for (int k : out) r[n++] = k;
        return r;
    }
}
