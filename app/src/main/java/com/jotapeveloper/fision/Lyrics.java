package com.jotapeveloper.fision;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Parsed lyrics.json produced by the server (Whisper transcription).
 * Timestamps live at phrase granularity, not word level: the entire text
 * of a segment is either fully highlighted or fully unhighlighted based
 * on whether the current playback position falls inside [start, end].
 */
public class Lyrics {

    @SerializedName("language")             public String language;
    @SerializedName("language_probability") public double languageProbability;
    @SerializedName("duration")             public double duration;
    @SerializedName("model")                public String model;
    @SerializedName("segments")             public List<Segment> segments;

    public static class Segment {
        @SerializedName("id")    public int id;
        @SerializedName("start") public double start;
        @SerializedName("end")   public double end;
        @SerializedName("text")  public String text;
    }
}
