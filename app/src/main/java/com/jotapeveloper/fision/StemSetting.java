package com.jotapeveloper.fision;

/** Persistent settings for a stem within a song. */
public class StemSetting {
    public int   volume = 75;   // 0..100
    public float pan    = 0f;   // -100..100
    /** Normalized 3D position [-1..1] relative to the head center. NaN = no saved
     *  position; PlayerActivity uses the default layout (frontal semicircle). */
    public float pos3DX = Float.NaN;
    public float pos3DY = Float.NaN;
    /** 3D elevation in degrees (-40..+90). Default 0 = horizontal plane. */
    public float elevationDeg = 0f;
    /** 3D distance from the head in scene units (~metres). NaN = use the
     *  scene default (1.5). Only meaningful in the real 3D scene mode. */
    public float distance3D = Float.NaN;
    /** Omnipresent mode: stem playing at the center with no specific direction. */
    public boolean omnipresent = false;
    /** 3D mute flag, independent from the volume slider. Toggled by long-press
     *  on the stem icon inside the 3D view. */
    public boolean mute3D = false;

    public StemSetting() {}
    public StemSetting(int volume, float pan) {
        this.volume = volume;
        this.pan    = pan;
    }
}
