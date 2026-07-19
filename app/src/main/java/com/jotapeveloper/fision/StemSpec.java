package com.jotapeveloper.fision;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Catalog of supported stems. Centralizes display order,
 * label to show and whether the stem receives pitch shifting.
 */
public class StemSpec {

    // Display order in the player
    public static final String[] ORDER = {
            "vocals", "vocals2", "bass", "drums",
            "other", "instrumental", "piano", "guitar", "winds",
            "perc", "kick", "snare", "toms", "hh", "ride", "crash"
    };

    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList(ORDER));

    /** Drum sub-stems (split drums separation). Grouped under a collapsible
     *  header in the RecyclerView to avoid inflating the UI. "perc" is the
     *  residual (drums − kit parts) but sits OUTSIDE the group, right above
     *  the drumset header, so it can be muted/soloed without expanding. */
    public static final String[] DRUM_PARTS_ORDER = { "kick", "snare", "toms", "hh", "ride", "crash" };
    private static final Set<String> DRUM_PARTS = new HashSet<>(Arrays.asList(DRUM_PARTS_ORDER));

    public static boolean isDrumPart(String stem) { return DRUM_PARTS.contains(stem); }

    // Stems that receive pitch shifting (melodic instruments)
    private static final Set<String> PITCHED = new HashSet<>(Arrays.asList(
            "vocals", "vocals2", "bass", "other", "instrumental", "piano", "guitar", "winds"
    ));

    public static boolean isKnown(String stem)   { return KNOWN.contains(stem); }
    public static boolean isPitched(String stem) { return PITCHED.contains(stem); }

    public static int orderIndex(String stem) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(stem)) return i;
        return ORDER.length;
    }

    /** Resource ID for the long title in the stem options bottom sheet.
     *  Vocals case: title_main_vocals if the song also carries vocals2, or
     *  title_vocals if it goes alone. Returns 0 when no string is associated (fallback). */
    public static int titleResource(String stem, boolean hasVocals2Sibling) {
        switch (stem) {
            case "vocals":       return hasVocals2Sibling ? R.string.title_main_vocals : R.string.title_vocals;
            case "vocals2":      return R.string.title_chorus;
            case "drums":        return R.string.title_drums;
            case "bass":         return R.string.title_bass;
            case "other":        return R.string.title_other;
            case "instrumental": return R.string.title_accompaniment;
            case "guitar":       return R.string.title_guitar;
            case "piano":        return R.string.title_piano;
            case "winds":        return R.string.title_winds;
            case "kick":         return R.string.title_kick;
            case "snare":        return R.string.title_snare;
            case "hh":           return R.string.title_hh;
            case "crash":        return R.string.title_crash;
            case "ride":         return R.string.title_ride;
            case "toms":         return R.string.title_toms;
            case "perc":         return R.string.title_perc;
            default:             return 0;
        }
    }

    /** Short label shown on the stem button (used while there is no icon). */
    public static String displayLabel(String stem) {
        switch (stem) {
            case "vocals":       return "VOX";
            case "vocals2":      return "V2";
            case "bass":         return "BASS";
            case "drums":        return "DRUM";
            case "other":        return "OTR";
            case "instrumental": return "INST";
            case "piano":        return "PNO";
            case "guitar":       return "GTR";
            case "winds":        return "WND";
            case "kick":         return "KCK";
            case "snare":        return "SNR";
            case "toms":         return "TMS";
            case "hh":           return "HH";
            case "ride":         return "RDE";
            case "crash":        return "CRS";
            case "perc":         return "PRC";
            default:             return stem.toUpperCase();
        }
    }

    /**
     * PNG resource (in res/raw) for the stem icon.
     * Special case: "vocals" uses ic_vocals1 if the song also carries vocals2 (both
     * siblings get paired icons V1/V2). If the song only has one vocals, the generic
     * ic_vocals is used.
     * @param hasVocals2Sibling true when the loaded stems list also contains "vocals2"
     */
    public static int iconResource(String stem, boolean hasVocals2Sibling) {
        switch (stem) {
            case "vocals":       return hasVocals2Sibling ? R.raw.ic_vocals1 : R.raw.ic_vocals;
            case "vocals2":      return R.raw.ic_vocals2;
            case "bass":         return R.raw.ic_bass;
            case "drums":        return R.raw.ic_drums;
            case "other":        return R.raw.ic_other;
            case "instrumental": return R.raw.ic_other; // fallback, no dedicated icon
            case "piano":        return R.raw.ic_piano;
            case "guitar":       return R.raw.ic_guitar;
            case "winds":        return R.raw.ic_winds;
            case "kick":         return R.raw.ic_kick;
            case "snare":        return R.raw.ic_snare;
            case "toms":         return R.raw.ic_toms;
            case "hh":           return R.raw.ic_hh;
            case "ride":         return R.raw.ic_ride;
            case "crash":        return R.raw.ic_crash;
            case "perc":         return R.raw.ic_drums; // generic drums icon for now
            default:             return R.raw.ic_other;
        }
    }
}
