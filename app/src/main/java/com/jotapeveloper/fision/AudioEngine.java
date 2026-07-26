package com.jotapeveloper.fision;

public class AudioEngine {
    static { System.loadLibrary("audioengine"); }

    private long handle = nativeCreate();

    // Tracks
    public int  addTrack(String wavPath, boolean loop) { return nativeAddTrack(handle, wavPath, loop); }

    // Global control
    public boolean start() { return nativeStart(handle); }
    public void pause()    { nativePause(handle); }
    public void resume()   { nativeResume(handle); }
    public void stop()     { nativeStop(handle); }

    // Per-track control
    public void setTempo(int id, float t)                  { nativeSetTempo(handle, id, t); }
    public void setPitch(int id, float semitones)          { nativeSetPitch(handle, id, semitones); }
    public void setVolume(int id, float left, float right) { nativeSetVolume(handle, id, left, right); }
    /** Global attenuation applied to the mix (1.0 normal, ~0.3 for navigation ducking). */
    public void setDuckFactor(float factor)                { nativeSetDuckFactor(handle, factor); }
    public void seekTo(int id, long ms)                    { nativeSeekTo(handle, id, ms); }
    public long getCurrentPosition(int id)                 { return nativeGetCurrentPosition(handle, id); }

    // === Global helpers ===
    public void seekTo(long ms)       { nativeSeekToAll(handle, ms); }
    public long getCurrentPositionMs(){ return nativeGetCurrentPositionAll(handle); }
    public long getDurationMs()       { return nativeGetDuration(handle); }

    /** Real output latency in ms, measured via Oboe getTimestamp.
     *  Returns 0 when the device does not report timestamps (rare on modern Android). */
    public long getOutputLatencyMs()  { return nativeGetOutputLatencyMs(handle); }

    // === Downbeat click ===
    /** Loads the click WAV. Path must point to a file (res/raw needs to be extracted to cache first). */
    public boolean setClickWav(String path)   { return nativeSetClickWav(handle, path); }
    /** Schedules a click fire when the song crosses sourceMs. */
    public void scheduleClickAt(long sourceMs){ nativeScheduleClickAt(handle, sourceMs); }
    /** Clears the queue of pending triggers. */
    public void clearClickSchedule()          { nativeClearClickSchedule(handle); }
    /** Global enable/disable of the click. */
    public void setClickEnabled(boolean e)    { nativeSetClickEnabled(handle, e); }
    /** Track that gets ducked by the engine during the click (typically the active metronome). */
    public void setClickDuckTrack(int trackId){ nativeSetClickDuckTrack(handle, trackId); }

    // === Per-track 3D mode (ITD + ILD + distance) ===
    /** Enables/disables the 3D pipeline for a stem. While active the user's volume
     *  and pan are ignored; the spatial 3D position drives them instead. */
    public void setTrack3DEnabled(int trackId, boolean enabled) {
        nativeSetTrack3DEnabled(handle, trackId, enabled);
    }
    /** 3D position of the stem around the listener's head.
     *  @param azimuthRad 0=front, +pi/2=right, -pi/2=left, +/-pi=behind.
     *  @param distance approximate meters; 1.0 = base distance. Clamped to [0.1,+inf).
     *  @param elevationDeg degrees above the horizontal plane, -40..+90; 0 = same height. */
    public void setTrack3DPosition(int trackId, float azimuthRad, float distance, float elevationDeg) {
        nativeSetTrack3DPosition(handle, trackId, azimuthRad, distance, elevationDeg);
    }
    /** Omnipresent mode: sound "at the center" with no direction; still routed through frontal HRTF. */
    public void setTrack3DOmnipresent(int trackId, boolean omni) {
        nativeSetTrack3DOmnipresent(handle, trackId, omni);
    }
    /** Per-stem mute inside 3D mode. Independent from the user slider so muting
     *  in 3D does not touch the slider's stored value outside 3D. */
    public void setTrack3DMuted(int trackId, boolean muted) {
        nativeSetTrack3DMuted(handle, trackId, muted);
    }

    /** Loads the HRIR bank from `assets/<assetPath>`. Must be called before
     *  addTrack so the first song already uses HRTF. Returns true when loaded. */
    public boolean initHrtf(android.content.res.AssetManager mgr, String assetPath) {
        return nativeInitHrtf(handle, mgr, assetPath);
    }

    public void dispose(){ nativeDispose(handle); handle = 0; }

    // JNI
    private static native long   nativeCreate();
    private static native void   nativeDispose(long h);
    private static native int    nativeAddTrack(long h, String path, boolean loop);
    private static native void   nativeSetTempo(long h, int id, float tempo);
    private static native void   nativeSetPitch(long h, int id, float semi);
    private static native void   nativeSetVolume(long h, int id, float L, float R);
    private static native void   nativeSetDuckFactor(long h, float factor);
    private static native boolean nativeStart(long h);
    private static native void    nativePause(long h);
    private static native void    nativeResume(long h);
    private static native void    nativeStop(long h);
    private static native void    nativeSeekTo(long h, int id, long ms);
    private static native long    nativeGetCurrentPosition(long h, int id);

    // === Global natives ===
    private static native void    nativeSeekToAll(long h, long ms);
    private static native long    nativeGetCurrentPositionAll(long h);
    private static native long    nativeGetDuration(long h);
    private static native long    nativeGetOutputLatencyMs(long h);
    private static native boolean nativeSetClickWav(long h, String path);
    private static native void    nativeScheduleClickAt(long h, long sourceMs);
    private static native void    nativeClearClickSchedule(long h);
    private static native void    nativeSetClickEnabled(long h, boolean enabled);
    private static native void    nativeSetClickDuckTrack(long h, int trackId);

    // === 3D natives ===
    private static native void    nativeSetTrack3DEnabled(long h, int id, boolean enabled);
    private static native void    nativeSetTrack3DPosition(long h, int id, float azimuthRad, float distance, float elevationDeg);
    private static native void    nativeSetTrack3DOmnipresent(long h, int id, boolean omni);
    private static native void    nativeSetTrack3DMuted(long h, int id, boolean muted);
    private static native boolean nativeInitHrtf(long h, android.content.res.AssetManager mgr, String assetPath);
}

