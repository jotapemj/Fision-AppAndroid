package com.jotapeveloper.fision;

public class ChordsFile {
    private String chord;
    private float time;

    public ChordsFile(String chord, float time){
        this.chord = chord;
        this.time = time;

    }

    public String getChord() {
        return chord;
    }

    public float getTime() {
        return time;
    }
}
