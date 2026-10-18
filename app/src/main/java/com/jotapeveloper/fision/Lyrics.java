package com.jotapeveloper.fision;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Parsed lyrics.json produced by the server (Whisper transcription).
 * Segments carry phrase-level [start, end] timestamps. When the server was
 * run with word_timestamps=True, each segment also embeds a words[] array
 * with per-word onsets used to drive karaoke-style highlighting; the field
 * is optional and stays null on legacy jsons.
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
        @SerializedName("words") public List<Word> words;
    }

    public static class Word {
        @SerializedName("start") public double start;
        @SerializedName("end")   public double end;
        @SerializedName("text")  public String text;
    }

    /** Drops what the UI cannot draw: null segments or words and entries
     *  with non-finite times. Called right after parsing the server JSON. */
    public static Lyrics sanitize(Lyrics lyrics) {
        if (lyrics == null || lyrics.segments == null) return lyrics;
        java.util.List<Segment> segs = new java.util.ArrayList<>();
        for (Segment s : lyrics.segments) {
            if (s == null || !finite(s.start, s.end)) continue;
            if (s.words != null) {
                java.util.List<Word> words = new java.util.ArrayList<>();
                for (Word w : s.words) {
                    if (w != null && finite(w.start, w.end)) words.add(w);
                }
                s.words = words;
            }
            segs.add(s);
        }
        lyrics.segments = segs;
        return lyrics;
    }

    private static boolean finite(double a, double b) {
        return !Double.isNaN(a) && !Double.isInfinite(a) && !Double.isNaN(b) && !Double.isInfinite(b);
    }

    /** True when at least one segment carries per-word timestamps. Callers
     *  use this to decide whether the karaoke mode is available for the
     *  currently loaded lyrics. */
    public static boolean hasWordTimestamps(Lyrics lyrics) {
        if (lyrics == null || lyrics.segments == null) return false;
        for (Segment s : lyrics.segments) {
            if (s.words != null && !s.words.isEmpty()) return true;
        }
        return false;
    }
}
