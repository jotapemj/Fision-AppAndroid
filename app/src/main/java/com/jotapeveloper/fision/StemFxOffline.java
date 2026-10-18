package com.jotapeveloper.fision;

/**
 * Offline FX of one stem for the mix export: same DSP as the engine (filter,
 * echo at the original tempo, reverb on the post-fader send). The result is
 * added to the mix buffer chunk by chunk, followed by the FX tail.
 */
public final class StemFxOffline implements AutoCloseable {
    static { System.loadLibrary("audioengine"); }

    private static final int CHUNK = 4096;
    /** Room for the echo and reverb tails after the stem ends. */
    static final float TAIL_SECONDS = 6f;

    private long handle;

    public StemFxOffline(int sampleRate, StemFx fx, float bpm) {
        handle = nativeCreate(sampleRate, fx.filter, fx.echo, fx.echoDelayMs(bpm), fx.reverb, fx.reverbRoom);
    }

    /**
     * Adds the processed stem to {@code out} (stereo interleaved). {@code src}
     * is interleaved with {@code srcCh} channels. Returns false when cancelled.
     */
    public boolean mixInto(float[] src, int srcCh, int srcFrames, int sampleRate,
                           float[] out, float volL, float volR,
                           java.util.function.BooleanSupplier cancelled) {
        int outFrames = out.length / 2;
        int n = Math.min(srcFrames, outFrames);
        for (int f = 0; f < n; f += CHUNK) {
            if (cancelled.getAsBoolean()) return false;
            nativeProcess(handle, src, srcCh, f, Math.min(CHUNK, n - f), out, f, volL, volR);
        }
        int tailEnd = Math.min(outFrames, n + (int) (TAIL_SECONDS * sampleRate));
        for (int f = n; f < tailEnd; f += CHUNK) {
            if (cancelled.getAsBoolean()) return false;
            nativeProcess(handle, null, srcCh, 0, Math.min(CHUNK, tailEnd - f), out, f, volL, volR);
        }
        return true;
    }

    @Override public void close() {
        if (handle != 0) { nativeDispose(handle); handle = 0; }
    }

    private static native long nativeCreate(int sr, float filter, float echo, float echoDelayMs,
                                            float send, int room);
    private static native void nativeDispose(long h);
    private static native void nativeProcess(long h, float[] src, int srcCh, int srcFrame, int frames,
                                             float[] out, int outFrame, float volL, float volR);
}
