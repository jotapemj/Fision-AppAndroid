package com.jotapeveloper.fision.chords;

import androidx.annotation.Nullable;

/**
 * Chord symbol as written in chords.txt ("F#;7", "Am7b5/G", "N"), reduced to
 * root pitch class, quality (chords-db suffix naming) and optional bass.
 * The ';' superscript marks of the mus_glyhps font are ignored.
 */
public final class ChordSymbol {

    public static final String MAJOR = "major";
    public static final String MINOR = "minor";

    /** Root pitch class 0..11 (C = 0). */
    public final int root;
    /** Quality in chords-db suffix naming ("major", "minor", "m7", ...). */
    public final String quality;
    /** Bass pitch class 0..11, or -1 when the bass is the root. */
    public final int bass;

    private ChordSymbol(int root, String quality, int bass) {
        this.root = root;
        this.quality = quality;
        this.bass = bass;
    }

    /** Parses [raw] and transposes it by [semitones]. Null for "N", empty or
     *  unreadable symbols (no chord sounding). */
    @Nullable
    public static ChordSymbol parse(@Nullable String raw, int semitones) {
        if (raw == null) return null;
        String s = raw.replace(";", "").trim();
        if (s.isEmpty() || s.equals("N") || s.equals("X")) return null;
        String bassPart = null;
        int slash = s.indexOf('/');
        if (slash >= 0) {
            bassPart = s.substring(slash + 1);
            s = s.substring(0, slash);
        }
        int[] rootLen = new int[1];
        int root = pitchClass(s, rootLen);
        if (root < 0) return null;
        String quality = normalizeQuality(s.substring(rootLen[0]));
        int bass = -1;
        if (bassPart != null) {
            int b = pitchClass(bassPart, new int[1]);
            if (b >= 0 && b != root) bass = mod12(b + semitones);
        }
        return new ChordSymbol(mod12(root + semitones), quality, bass);
    }

    /** Pitch class of the note name at the start of [s] ("C", "F#", "Bb"),
     *  -1 if none. [len] receives the number of chars consumed. */
    static int pitchClass(String s, int[] len) {
        if (s == null || s.isEmpty()) return -1;
        int base;
        switch (Character.toUpperCase(s.charAt(0))) {
            case 'C': base = 0; break;
            case 'D': base = 2; break;
            case 'E': base = 4; break;
            case 'F': base = 5; break;
            case 'G': base = 7; break;
            case 'A': base = 9; break;
            case 'B': base = 11; break;
            default: return -1;
        }
        int n = 1;
        if (s.length() > 1) {
            char acc = s.charAt(1);
            if (acc == '#' || acc == '♯') { base++; n = 2; }
            else if (acc == 'b' || acc == '♭') { base--; n = 2; }
        }
        len[0] = n;
        return mod12(base);
    }

    /** Maps the app / BTC suffix to the chords-db suffix. Unknown extensions
     *  fall back to the triad they start with. */
    static String normalizeQuality(String q) {
        switch (q) {
            case "": case "maj": return MAJOR;
            case "m": case "min": return MINOR;
            case "7": case "m7": case "maj7": case "6": case "m6": case "m7b5":
            case "dim": case "dim7": case "aug": case "sus2": case "sus4":
            case "mmaj7": case "9": case "m9": case "add9":
                return q;
            case "hdim7": return "m7b5";
            case "minmaj7": case "mM7": return "mmaj7";
            case "sus": return "sus4";
            case "min7": return "m7";
            case "min6": return "m6";
            case "maj6": return "6";
            default:
                return q.startsWith("m") && !q.startsWith("maj") ? MINOR : MAJOR;
        }
    }

    static int mod12(int v) { return ((v % 12) + 12) % 12; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ChordSymbol)) return false;
        ChordSymbol c = (ChordSymbol) o;
        return c.root == root && c.bass == bass && c.quality.equals(quality);
    }

    @Override public int hashCode() { return (root * 31 + bass) * 31 + quality.hashCode(); }
}
