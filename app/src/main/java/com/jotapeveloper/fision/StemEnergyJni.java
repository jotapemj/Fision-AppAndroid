package com.jotapeveloper.fision;

/** Offline measurement of a stem's energy through the engine's own decoders. */
final class StemEnergyJni {
    static { System.loadLibrary("audioengine"); }

    private StemEnergyJni() {}

    /** Adds the mean power of each beat window of a WAV/FLAC file to [power].
     *  Window i spans beats[i]..beats[i + 1]; the last one runs to the end of
     *  the file. Returns false when the file cannot be decoded. */
    static native boolean nativeAddBeatPower(String path, float[] beats, double[] power);
}
