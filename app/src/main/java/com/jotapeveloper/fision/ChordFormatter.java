package com.jotapeveloper.fision;

/**
 * Formats chords according to the selected notation (American / Latin) and applies
 * optional semitone transposition. Preserves the quality suffix (m, 7, sus4, ...).
 *
 * Rules:
 * - Canonical root comes from the server (BTC): A, A#, B, C, C#, D, D#, E, F, F#, G, G#.
 * - Flat notation is also accepted (Bb, Eb, Ab...) for robustness.
 * - Always capitalized; in Latin "Do, Re, Mi..." with the first letter uppercase.
 * - Inverted bass ("C/G") is processed as root and bass separately.
 * - Character ';' in the suffix: marks the start of a superscript for the
 *   mus_glyphs font (American). In Latin it renders with noto_sans_regular, which
 *   does not understand ';', so it is stripped from the output.
 */
public final class ChordFormatter {

    private static final String[] SHARP_NOTES =
            {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final String[] FLAT_NOTES =
            {"C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"};

    private static final String[] LATIN_SHARP =
            {"Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si"};
    private static final String[] LATIN_FLAT =
            {"Do", "Reb", "Re", "Mib", "Mi", "Fa", "Solb", "Sol", "Lab", "La", "Sib", "Si"};

    private ChordFormatter() {}

    /** Name of a single pitch class (0 = C) in the given notation. */
    public static String noteName(int pitchClass, boolean latin, boolean preferFlat) {
        int i = ((pitchClass % 12) + 12) % 12;
        if (latin) return preferFlat ? LATIN_FLAT[i] : LATIN_SHARP[i];
        return preferFlat ? FLAT_NOTES[i] : SHARP_NOTES[i];
    }

    /**
     * Applies transposition + notation + accidental.
     * @param chord      original chord
     * @param semitones  semitones to transpose (may be negative)
     * @param latin      true for Latin notation (Do/Re/Mi...), false for American
     * @param preferFlat true to render flats, false to render sharps
     */
    public static String format(String chord, int semitones, boolean latin, boolean preferFlat) {
        if (chord == null || chord.isEmpty()) return "";
        // Inverted bass C/G → root and bass are processed separately
        int slash = chord.indexOf('/');
        if (slash > 0) {
            String left  = chord.substring(0, slash);
            String right = chord.substring(slash + 1);
            return formatSingle(left, semitones, latin, preferFlat) + "/"
                 + formatSingle(right, semitones, latin, preferFlat);
        }
        return formatSingle(chord, semitones, latin, preferFlat);
    }

    private static String formatSingle(String chord, int semitones, boolean latin, boolean preferFlat) {
        // Isolate root (1 or 2 chars) — the rest is quality/suffix
        int rootLen = rootLength(chord);
        if (rootLen == 0) return chord; // not parseable
        String root  = chord.substring(0, rootLen);
        String suffix= chord.substring(rootLen);

        int idx = pitchIndex(root);
        if (idx < 0) return chord;

        int newIdx = (((idx + semitones) % 12) + 12) % 12;

        // preferFlat (global) always decides — the original notation is ignored
        String newRoot;
        if (latin) {
            newRoot = preferFlat ? LATIN_FLAT[newIdx] : LATIN_SHARP[newIdx];
            // Latin notation → noto_sans_regular does not interpret ';' as a
            // superscript marker. Strip it so it reads correctly.
            suffix = suffix.replace(";", "");
        } else {
            newRoot = preferFlat ? FLAT_NOTES[newIdx] : SHARP_NOTES[newIdx];
            // American → mus_glyphs treats ';' as the start of a superscript. Kept.
        }
        return newRoot + suffix;
    }

    /** Length of the root prefix at the start of the string ("C", "C#", "Db", "F#"...). 0 when invalid. */
    private static int rootLength(String s) {
        if (s.isEmpty()) return 0;
        char c0 = s.charAt(0);
        if (c0 < 'A' || c0 > 'G') return 0;
        if (s.length() >= 2) {
            char c1 = s.charAt(1);
            if (c1 == '#' || c1 == 'b') return 2;
        }
        return 1;
    }

    private static int pitchIndex(String root) {
        // 0=C, 1=C#, 2=D, ...
        switch (root) {
            case "C":  return 0;
            case "C#": return 1;
            case "Db": return 1;
            case "D":  return 2;
            case "D#": return 3;
            case "Eb": return 3;
            case "E":  return 4;
            case "F":  return 5;
            case "F#": return 6;
            case "Gb": return 6;
            case "G":  return 7;
            case "G#": return 8;
            case "Ab": return 8;
            case "A":  return 9;
            case "A#": return 10;
            case "Bb": return 10;
            case "B":  return 11;
            default:   return -1;
        }
    }
}
