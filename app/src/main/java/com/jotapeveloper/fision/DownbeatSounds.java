package com.jotapeveloper.fision;

/**
 * Catalog of sounds available for the downbeat click.
 * The WAVs live in res/raw with the `dbclick_` prefix. To add a new sound:
 * 1) Drop the WAV in res/raw (lowercase, snake_case).
 * 2) Add the entry in RES_FILES + RES_LABELS + string in strings.xml (4 locales).
 *
 * The index in each array = `downbeatSoundType` stored in Room (v5 column).
 */
public final class DownbeatSounds {

    /** WAV resources. Order aligned with RES_LABELS. */
    public static final int[] RES_FILES = {
            R.raw.dbclick_clap,
            R.raw.dbclick_glass,
            R.raw.dbclick_metronomebell,
            R.raw.dbclick_snap,
            R.raw.dbclick_stick,
            R.raw.dbclick_synthbella,
            R.raw.dbclick_synthbellb,
            R.raw.dbclick_synthsquarea,
            R.raw.dbclick_synthsquareb,
            R.raw.dbclick_synthsquarec,
            R.raw.dbclick_tongue,
            R.raw.dbclick_weird,
            R.raw.dbclick_whistle,
    };

    /** Label strings (same order as RES_FILES). */
    public static final int[] RES_LABELS = {
            R.string.downbeat_sound_clap,
            R.string.downbeat_sound_glass,
            R.string.downbeat_sound_metronomebell,
            R.string.downbeat_sound_snap,
            R.string.downbeat_sound_stick,
            R.string.downbeat_sound_synthbella,
            R.string.downbeat_sound_synthbellb,
            R.string.downbeat_sound_synthsquarea,
            R.string.downbeat_sound_synthsquareb,
            R.string.downbeat_sound_synthsquarec,
            R.string.downbeat_sound_tongue,
            R.string.downbeat_sound_weird,
            R.string.downbeat_sound_whistle,
    };

    public static int count() { return RES_FILES.length; }

    public static int fileRes(int index) {
        if (index < 0 || index >= RES_FILES.length) return RES_FILES[0];
        return RES_FILES[index];
    }

    public static int labelRes(int index) {
        if (index < 0 || index >= RES_LABELS.length) return RES_LABELS[0];
        return RES_LABELS[index];
    }

    private DownbeatSounds() {}
}
