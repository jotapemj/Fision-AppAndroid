package com.jotapeveloper.fision;

/**
 * One cell of the rhythm visualizer: represents an individual beat.
 * When a new chord starts on this beat, its name is attached.
 */
public class BeatCell {
    public final float  time;          // seconds
    public final int    positionInBar; // 1..beatsPerBar
    public final boolean isDownbeat;   // positionInBar == 1
    public final String chord;         // name of the chord starting here, or null

    public BeatCell(float time, int positionInBar, int beatsPerBar, String chord) {
        this.time = time;
        this.positionInBar = positionInBar;
        this.isDownbeat = (positionInBar == 1);
        this.chord = chord;
    }
}
