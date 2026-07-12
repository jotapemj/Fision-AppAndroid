package com.jotapeveloper.fision;

import java.io.File;

/**
 * FLAC decoder via JNI (dr_flac). Used by ExportManager to read FLAC stems
 * offline with the same engine as playback, without extra libraries or MediaCodec.
 */
public class FlacDecoderJni {
    static { System.loadLibrary("audioengine"); }

    /** Decodes the whole FLAC file to a float[] interleaved [-1,1]. Returns
     *  null on failure. outMeta[0]=channels, outMeta[1]=sampleRate. */
    public static native float[] nativeDecodeFlac(String path, int[] outMeta);

    /** Adapter that returns a `WavIO.WavData` compatible with the rest of the
     *  export pipeline (same float32 interleaved layout). */
    public static WavIO.WavData readAsWavData(File f) {
        int[] meta = new int[2];
        float[] samples = nativeDecodeFlac(f.getAbsolutePath(), meta);
        if (samples == null) return null;
        return new WavIO.WavData(samples, meta[1], meta[0]);
    }
}
