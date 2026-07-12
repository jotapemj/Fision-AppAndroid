package com.jotapeveloper.fision;

import java.io.File;
import java.io.IOException;

/**
 * Format-agnostic audio reader. Picks WAV (WavIO) or FLAC (FlacDecoderJni)
 * based on the file extension. Always returns a {@link WavIO.WavData} with
 * float32 interleaved data compatible with the whole export pipeline.
 *
 * <p>For new song folders (processed by FisionServer as FLAC), stems come as
 * .flac; older folders still contain .wav. The helpers {@link #findStemFile}
 * and {@link #hasStem} return the real file present in the folder.
 */
public final class AudioIO {
    private AudioIO() {}

    public static WavIO.WavData read(File f) throws IOException {
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".flac")) {
            WavIO.WavData d = FlacDecoderJni.readAsWavData(f);
            if (d == null) throw new IOException("FLAC decode failed: " + f);
            return d;
        }
        return WavIO.read(f);
    }

    /** Bit-for-bit copy of the source file (WAV or FLAC) to the destination. Used for
     *  the "1:1 copy" export when the output format matches the source. */
    public static void copy(File src, File dst) throws IOException {
        WavIO.copyFile(src, dst);
    }

    /** Looks up a stem file in the folder, trying .flac first (new) and
     *  falling back to .wav. Returns null when neither exists. */
    public static File findStemFile(File songFolder, String stem) {
        File flac = new File(songFolder, stem + ".flac");
        if (flac.exists()) return flac;
        File wav = new File(songFolder, stem + ".wav");
        return wav.exists() ? wav : null;
    }

    /** True when the folder contains a stem (WAV or FLAC). */
    public static boolean hasStem(File songFolder, String stem) {
        return findStemFile(songFolder, stem) != null;
    }
}
