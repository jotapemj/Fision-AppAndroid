package com.jotapeveloper.fision;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Renders lyric rows: either a text line (transcribed segment) or an
 *  instrumental placeholder (music-note icon) inserted between segments when
 *  the silent gap between them exceeds INSTRUMENTAL_GAP_SEC. The active row
 *  (whose [start, next.start] contains the current playback position) is
 *  drawn in white whatever the accent; the rest fade to the dim colour
 *  defined in colors.xml. When karaoke mode is enabled and the active TEXT
 *  row carries per-word timestamps, each word inside the line is coloured
 *  independently based on the current playback second. */
public class LyricsLinesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final double INSTRUMENTAL_GAP_SEC = 8.0;
    // With the measured vocal activity the gap is known to be real silence,
    // so a shorter one already shows the instrumental note.
    private static final double INSTRUMENTAL_GAP_VOICED_SEC = 4.0;

    private static final int VT_TEXT         = 0;
    private static final int VT_INSTRUMENTAL = 1;

    // Payload sent to notifyItemChanged so the karaoke tick only rebinds the
    // text span without swapping styles / triggering full rebind animations.
    private static final Object PAYLOAD_KARAOKE = new Object();
    // Payload for an active-line change: the row eases between the active and
    // the inactive style instead of jumping.
    private static final Object PAYLOAD_ACTIVE = new Object();
    // Shared by the row style change and the recycler glide (PlayerActivity).
    static final long LINE_ANIM_MS = 520;
    static final android.view.animation.Interpolator LINE_INTERPOLATOR =
            new androidx.interpolator.view.animation.FastOutSlowInInterpolator();
    // Every row is laid out at the same size and weight, so the line breaks
    // never change; the active row is drawn scaled up (from its start edge)
    // and the rows wrap narrower to leave room for that scale.
    private static final float ACTIVE_SCALE = 30f / 26f;

    private RecyclerView recycler;

    @Override public void onAttachedToRecyclerView(@NonNull RecyclerView rv) { recycler = rv; }
    @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) { recycler = null; }

    /** End padding so a row scaled by ACTIVE_SCALE still fits the width. */
    private int scaledEndPadding() {
        if (recycler == null) return 0;
        int content = recycler.getWidth() - recycler.getPaddingLeft() - recycler.getPaddingRight();
        return content > 0 ? Math.round(content * (1f - 1f / ACTIVE_SCALE)) : 0;
    }

    public static class Row {
        public final int    type;
        public final double start;
        public final double end;
        public final Lyrics.Segment segment;
        /** Moment the first word is actually sung (see firstWordOnset). */
        public final double onset;
        /** Moment the voice of this line stops (see voicedEnd). */
        public final double voicedEnd;
        /** Moment each word is actually sung (karaoke), or null. */
        public final double[] wordOnsets;
        Row(int type, double start, double end, Lyrics.Segment seg,
            double onset, double voicedEnd, double[] wordOnsets) {
            this.type = type; this.start = start; this.end = end; this.segment = seg;
            this.onset = onset; this.voicedEnd = voicedEnd; this.wordOnsets = wordOnsets;
        }
    }

    /**
     * Word timestamps come without gaps: the silence before a line is
     * absorbed by its first word, whose start then sits far before the
     * voice ("Y" lasting almost 2 s). With the measured vocal activity the
     * onset is the last time the voice comes back inside that word. Without
     * it (or when the voice runs on from the previous line) and when the
     * word is longer than a generous estimate for its length, the onset is
     * end - estimate. Only the first word is corrected: inside a line long
     * words are usually held notes, sung from their real start.
     */
    static double firstWordOnset(Lyrics.Segment seg, VocalActivity va) {
        if (seg.words == null || seg.words.isEmpty()) return seg.start;
        Lyrics.Word w = seg.words.get(0);
        if (va != null) {
            // The word end is unreliable too: search up to the second word.
            double to = seg.words.size() > 1 ? seg.words.get(1).start : w.end + 0.4;
            double measured = va.lastOnsetIn(w.start, Math.max(w.start, to - 0.05));
            if (!Double.isNaN(measured)) return measured;
            // No silence (the previous line's note decays into this word):
            // the same valley test used inside lines.
            measured = va.innerOnsetIn(w.start, Math.max(w.start, to - 0.05),
                    0.08 + 0.03 * letterCount(w.text));
            if (!Double.isNaN(measured)) return measured;
        }
        double maxDur = 0.25 + 0.07 * Math.max(1, letterCount(w.text));
        return Math.max(w.start, w.end - maxDur);
    }

    /**
     * Moment each word is sung. A pause inside a line is absorbed by the
     * next word too (its start sits in the silence), so with the vocal
     * activity a word whose span holds a silence-to-voice transition starts
     * there, provided enough time is left after it to sing the word (a
     * pause right before the next word belongs after this one). No
     * transition (continuous voice, held notes) keeps the timestamp. The
     * first word uses the line onset.
     */
    private static int letterCount(String s) {
        int n = 0;
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                if (Character.isLetterOrDigit(s.charAt(i))) n++;
            }
        }
        return n;
    }

    static double[] wordOnsets(Lyrics.Segment seg, VocalActivity va, double firstOnset) {
        if (seg.words == null || seg.words.isEmpty()) return null;
        int n = seg.words.size();
        double[] out = new double[n];
        out[0] = firstOnset;
        for (int i = 1; i < n; i++) {
            Lyrics.Word w = seg.words.get(i);
            double at = w.start;
            if (va != null) {
                double to = i + 1 < n ? seg.words.get(i + 1).start : w.end;
                double m = va.innerOnsetIn(w.start, Math.max(w.start, to - 0.05),
                        0.08 + 0.03 * letterCount(w.text));
                if (!Double.isNaN(m)) at = m;
            }
            out[i] = Math.max(at, out[i - 1]);
        }
        return out;
    }

    /**
     * Moment the voice of a line stops. Word ends from the transcription are
     * unreliable on held notes ("nombreeee" ends well after its timestamp)
     * and on words stretched into the following silence. With the vocal
     * activity it is the end of the voiced run that contains the last word,
     * bounded by [limit] (the next line's onset); otherwise the segment end.
     */
    static double voicedEnd(Lyrics.Segment seg, VocalActivity va, double limit) {
        if (va == null || seg.words == null || seg.words.isEmpty()) return seg.end;
        Lyrics.Word last = seg.words.get(seg.words.size() - 1);
        double end = va.voiceEndAfter(last.start, Math.max(last.start, last.end), limit);
        if (Double.isNaN(end)) return seg.end;
        return Math.max(last.start + KARAOKE_FADE_SEC, end);
    }

    private List<Row> rows = Collections.emptyList();
    private int activeIndex = -1;
    private static final float INACTIVE_ALPHA = 0.85f;
    private boolean karaokeMode = false;
    private double karaokePosSec = 0.0;
    private LineClickListener lineClickListener;

    // ---- Chords on lyrics ----

    /** A chord change of the song: [time] in song seconds, [raw] as in chords.txt. */
    public static final class ChordMark {
        public final double time;
        public final String raw;
        public ChordMark(double time, String raw) { this.time = time; this.raw = raw; }
    }

    /**
     * Chord of a row; [word] is the index of the word it belongs to (-1
     * instrumental). [after]: it changes in the silence after that word, so
     * it is drawn in the gap behind it, not over it. [offset]: character of
     * the display text it is drawn over (behind, when [after]).
     */
    private static final class RowChord {
        final double time;
        final String raw;
        final int word;
        final boolean after;
        final int offset;
        RowChord(double time, String raw, int word, boolean after, int offset) {
            this.time = time; this.raw = raw; this.word = word; this.after = after;
            this.offset = offset;
        }
    }

    /** Text of a row as displayed with chords: where each word starts and
     *  ends in the text, and when it is sung. */
    private static final class WordLayout {
        final String text;
        final int[] offsets;
        final int[] ends;
        final double[] times;
        final double[] endTimes;
        WordLayout(String text, int[] offsets, int[] ends, double[] times, double[] endTimes) {
            this.text = text; this.offsets = offsets; this.ends = ends;
            this.times = times; this.endTimes = endTimes;
        }
    }

    // A chord played just before a word belongs to it.
    private static final double CHORD_WORD_LEAD_SEC = 0.15;
    // A chord this long after a word ends falls in a silence, not on the word.
    private static final double CHORD_WORD_TAIL_SEC = 0.12;

    private boolean chordsOn = false;
    private List<ChordMark> chordMarks = Collections.emptyList();
    private boolean chordLatin, chordFlat;
    private int chordShift;
    private android.graphics.Typeface chordTypeface;
    private List<List<RowChord>> rowChords = Collections.emptyList();
    private WordLayout[] wordLayouts = new WordLayout[0];

    public interface LineClickListener {
        void onLyricsRowClicked(Row row);
        default void onLyricsWordClicked(double startSec) { }
    }

    public void setLineClickListener(LineClickListener l) { this.lineClickListener = l; }

    // Common sentence starters (lower case). A capitalised one in the middle
    // of a segment marks a new sentence the transcription merged.
    private static final java.util.Set<String> STARTERS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    // es / ca / pt / it / fr
                    "y", "e", "o", "que", "porque", "pero", "mas", "sobre", "si", "no",
                    "ya", "yo", "tu", "te", "me", "mi", "se", "es", "el", "la", "los",
                    "las", "un", "una", "en", "de", "con", "por", "para", "cuando",
                    "como", "donde", "aunque", "todavia", "hoy", "ahora", "hay",
                    "eu", "voce", "nao", "mas", "ma", "che", "io", "non", "et", "je",
                    "tu", "il", "elle", "nous", "vous", "et", "per", "quan",
                    // en / de
                    "and", "but", "so", "the", "you", "we", "they", "he", "she", "it",
                    "if", "when", "oh", "now", "und", "ich", "du", "wir", "aber"));

    /**
     * The transcription sometimes merges two sentences into one segment
     * ("...estar en pie Porque ya hasta..."): a capitalised word right after
     * a word without punctuation. Such a segment is split there, unless the
     * word looks like a proper noun: it must be a common starter or appear
     * in lower case elsewhere in the lyrics.
     */
    private static List<Lyrics.Segment> splitMergedSentences(List<Lyrics.Segment> in) {
        java.util.Set<String> lower = new java.util.HashSet<>();
        for (Lyrics.Segment s : in) {
            if (s.words == null) continue;
            for (Lyrics.Word w : s.words) {
                String t = bare(w.text);
                if (!t.isEmpty() && Character.isLowerCase(t.charAt(0))) lower.add(t);
            }
        }
        List<Lyrics.Segment> out = new ArrayList<>(in.size() + 8);
        for (Lyrics.Segment s : in) {
            if (s.words == null || s.words.size() < 2) { out.add(s); continue; }
            int from = 0;
            for (int i = 1; i < s.words.size(); i++) {
                String prev = s.words.get(i - 1).text == null ? "" : s.words.get(i - 1).text.trim();
                String t = bare(s.words.get(i).text);
                boolean capital = !t.isEmpty() && Character.isUpperCase(t.charAt(0));
                boolean prevOpen = !prev.isEmpty() && ".!?,;:\u2026".indexOf(prev.charAt(prev.length() - 1)) < 0;
                String key = stripAccents(t.toLowerCase(java.util.Locale.ROOT));
                if (capital && prevOpen && t.length() > 1
                        && (STARTERS.contains(key) || lower.contains(t.toLowerCase(java.util.Locale.ROOT)))) {
                    out.add(subSegment(s, from, i));
                    from = i;
                }
            }
            out.add(from == 0 ? s : subSegment(s, from, s.words.size()));
        }
        return out;
    }

    private static Lyrics.Segment subSegment(Lyrics.Segment s, int from, int to) {
        Lyrics.Segment p = new Lyrics.Segment();
        p.id = s.id;
        p.words = new ArrayList<>(s.words.subList(from, to));
        p.start = from == 0 ? s.start : p.words.get(0).start;
        p.end = to == s.words.size() ? s.end : p.words.get(p.words.size() - 1).end;
        StringBuilder sb = new StringBuilder();
        for (Lyrics.Word w : p.words) {
            String t = w.text == null ? "" : w.text.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        p.text = sb.toString();
        return p;
    }

    /** Word without surrounding punctuation. */
    private static String bare(String s) {
        if (s == null) return "";
        int a = 0, b = s.length();
        while (a < b && !Character.isLetterOrDigit(s.charAt(a))) a++;
        while (b > a && !Character.isLetterOrDigit(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static String stripAccents(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    /** [va] is the measured vocal activity of the song, or null. */
    public void setLyrics(Lyrics lyrics, VocalActivity va) {
        if (lyrics == null || lyrics.segments == null || lyrics.segments.isEmpty()) {
            this.rows = Collections.emptyList();
            this.activeIndex = -1;
            rebuildRowChords();
            notifyDataSetChanged();
            return;
        }
        List<Lyrics.Segment> segs = splitMergedSentences(lyrics.segments);
        int n = segs.size();
        double[] onset = new double[n];
        double[] vEnd = new double[n];
        for (int i = 0; i < n; i++) onset[i] = firstWordOnset(segs.get(i), va);
        for (int i = 0; i < n; i++) {
            Lyrics.Segment s = segs.get(i);
            double limit = i + 1 < n ? onset[i + 1]
                    : (lyrics.duration > 0.0 ? lyrics.duration : s.end + 10.0);
            vEnd[i] = voicedEnd(s, va, Math.max(limit, s.end));
        }
        double minGap = va != null ? INSTRUMENTAL_GAP_VOICED_SEC : INSTRUMENTAL_GAP_SEC;
        List<Row> out = new ArrayList<>(n + 4);
        double prevEnd = 0.0;
        for (int i = 0; i < n; i++) {
            Lyrics.Segment s = segs.get(i);
            if (onset[i] - prevEnd >= minGap) {
                out.add(new Row(VT_INSTRUMENTAL, prevEnd, onset[i], null, prevEnd, onset[i], null));
            }
            out.add(new Row(VT_TEXT, s.start, s.end, s, onset[i], vEnd[i],
                    wordOnsets(s, va, onset[i])));
            prevEnd = vEnd[i];
        }
        if (lyrics.duration > 0.0 && lyrics.duration - prevEnd >= minGap) {
            out.add(new Row(VT_INSTRUMENTAL, prevEnd, lyrics.duration, null,
                    prevEnd, lyrics.duration, null));
        }
        this.rows = out;
        this.activeIndex = -1;
        rebuildRowChords();
        notifyDataSetChanged();
    }

    public List<Row> getRows() { return rows; }

    /** Shows or hides the chord names above the lyrics, at once. */
    public void setChordsOnLyrics(boolean on) {
        if (chordsOn == on && chordReveal == (on ? 1f : 0f)) return;
        chordsOn = on;
        chordReveal = on ? 1f : 0f;
        notifyDataSetChanged();
    }

    /** Start of the "on" transition: chords bound but with no room yet. */
    public void beginChordsReveal() {
        chordsOn = true;
        chordReveal = 0f;
        notifyDataSetChanged();
    }

    /** Transition step (0 hidden, 1 shown) applied to the rows on screen. */
    public void setChordReveal(float f) {
        chordReveal = f;
        if (recycler == null) return;
        for (int i = 0; i < recycler.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = recycler.getChildViewHolder(recycler.getChildAt(i));
            if (vh instanceof TextVH) ((TextVH) vh).text.setChordReveal(f);
            else if (vh instanceof InstrumentalVH) ((InstrumentalVH) vh).chords.setReveal(f);
        }
    }

    private float chordReveal = 0f;

    public boolean isChordsOnLyrics() { return chordsOn; }

    /** Chord changes of the song, in time order. */
    public void setChordMarks(List<ChordMark> marks) {
        chordMarks = marks != null ? marks : Collections.emptyList();
        rebuildRowChords();
        if (chordsOn) notifyDataSetChanged();
    }

    /** Notation, accidentals, transposition and font of the chord names. */
    public void setChordFormat(boolean latin, boolean flat, int shift, android.graphics.Typeface tf) {
        if (latin == chordLatin && flat == chordFlat && shift == chordShift && tf == chordTypeface) return;
        chordLatin = latin;
        chordFlat = flat;
        chordShift = shift;
        chordTypeface = tf;
        if (chordsOn) notifyDataSetChanged();
    }

    /**
     * Places every chord change in the row that is lit when it sounds, so it
     * lights up on time: in the instrumental part it falls in, or in the
     * line being sung (a line stays lit through the silence until the next
     * one takes over). Within the line it sits on a word only when it changes
     * while that word is sung (or just before it starts); a change in a
     * silence goes to the gap behind the last word sung, never onto a word
     * it does not fall with.
     */
    private void rebuildRowChords() {
        int n = rows.size();
        wordLayouts = new WordLayout[n];
        List<List<RowChord>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new ArrayList<>(2));
        if (n > 0) {
            for (ChordMark m : chordMarks) {
                int ri = 0;
                for (int i = 0; i < n; i++) {
                    if (chordRowStart(rows.get(i)) <= m.time + CHORD_WORD_LEAD_SEC) ri = i;
                    else break;
                }
                Row r = rows.get(ri);
                if (r.type != VT_TEXT) {
                    out.get(ri).add(new RowChord(m.time, m.raw, -1, false, 0));
                    continue;
                }
                WordLayout wl = wordLayout(ri);
                int w = 0;
                for (int i = 0; i < wl.times.length; i++) {
                    if (wl.times[i] <= m.time + CHORD_WORD_LEAD_SEC) w = i;
                }
                boolean after = m.time > wl.endTimes[w] + CHORD_WORD_TAIL_SEC
                        && m.time + CHORD_WORD_LEAD_SEC >= wl.times[w];
                int offset = after ? wl.ends[w] : offsetInWord(wl, w, m.time);
                out.get(ri).add(new RowChord(m.time, m.raw, w, after, offset));
            }
        }
        rowChords = out;
    }

    private static double chordRowStart(Row r) { return r.type == VT_TEXT ? r.onset : r.start; }

    /** Character of word [w] sung at [t]: the word's letters spread evenly
     *  over its sung time, so a change late in a long word sits over its
     *  later letters instead of its first one. */
    private static int offsetInWord(WordLayout wl, int w, double t) {
        int start = wl.offsets[w], end = wl.ends[w];
        double dur = wl.endTimes[w] - wl.times[w];
        if (end - start <= 1 || dur <= 0.0 || t <= wl.times[w]) return start;
        double frac = Math.min(1.0, (t - wl.times[w]) / dur);
        return Math.min(end - 1, start + (int) Math.floor(frac * (end - start)));
    }

    /** Display text of a text row with its word starts (cached per row). */
    private WordLayout wordLayout(int rowIdx) {
        WordLayout wl = wordLayouts[rowIdx];
        if (wl != null) return wl;
        Row r = rows.get(rowIdx);
        if (hasWords(r)) {
            List<Lyrics.Word> words = r.segment.words;
            int[] starts = new int[words.size()];
            int[] ends = new int[words.size()];
            String text = joinWords(words, starts, ends);
            int n = words.size();
            double[] times = new double[n];
            for (int i = 0; i < n; i++) {
                times[i] = r.wordOnsets != null && i < r.wordOnsets.length
                        ? r.wordOnsets[i] : words.get(i).start;
            }
            // A word ends at its timestamp, but never after the next one is
            // sung; the last one ends with the measured voice of the line.
            double[] endTimes = new double[n];
            for (int i = 0; i < n; i++) {
                double e = i == n - 1 ? Math.max(words.get(i).end, r.voicedEnd) : words.get(i).end;
                if (i + 1 < n) e = Math.min(e, times[i + 1]);
                endTimes[i] = Math.max(times[i], e);
            }
            wl = new WordLayout(text, starts, ends, times, endTimes);
        } else {
            // No word timestamps: words spread evenly over the line.
            String text = r.segment != null && r.segment.text != null ? r.segment.text.trim() : "";
            List<Integer> starts = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))
                        && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) starts.add(i);
            }
            if (starts.isEmpty()) starts.add(0);
            int n = starts.size();
            int[] offs = new int[n];
            int[] ends = new int[n];
            double[] times = new double[n];
            double[] endTimes = new double[n];
            for (int i = 0; i < n; i++) {
                offs[i] = starts.get(i);
                int e = offs[i];
                while (e < text.length() && !Character.isWhitespace(text.charAt(e))) e++;
                ends[i] = e;
                times[i] = r.start + (r.end - r.start) * i / n;
                endTimes[i] = r.start + (r.end - r.start) * (i + 1) / n;
            }
            wl = new WordLayout(text, offs, ends, times, endTimes);
        }
        wordLayouts[rowIdx] = wl;
        return wl;
    }

    private static boolean hasWords(Row r) {
        return r.type == VT_TEXT && r.segment != null
                && r.segment.words != null && !r.segment.words.isEmpty();
    }

    /** Words joined as the karaoke shows them; fills where each word starts and ends. */
    private static String joinWords(List<Lyrics.Word> words, int[] starts, int[] ends) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String piece = words.get(i).text == null ? "" : words.get(i).text;
            if (i > 0 && !piece.startsWith(" ") && !piece.isEmpty()
                    && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            int start = sb.length();
            sb.append(piece);
            int lead = 0;
            while (lead < piece.length() && Character.isWhitespace(piece.charAt(lead))) lead++;
            starts[i] = start + Math.min(lead, Math.max(0, piece.length() - 1));
            int end = sb.length();
            while (end > starts[i] + 1 && Character.isWhitespace(sb.charAt(end - 1))) end--;
            ends[i] = end;
        }
        return sb.toString();
    }

    private String chordLabel(String raw) {
        return ChordFormatter.format(raw, chordShift, chordLatin, chordFlat);
    }

    /** Whether a chord of row [idx] changes state between two positions. */
    private boolean chordCrossed(int idx, double from, double to) {
        if (idx < 0 || idx >= rowChords.size()) return false;
        for (RowChord c : rowChords.get(idx)) {
            if ((c.time <= from) != (c.time <= to)) return true;
        }
        return false;
    }

    public int getActiveIndex() { return activeIndex; }

    public void setActiveIndex(int index) {
        if (index == activeIndex) return;
        int prev = activeIndex;
        activeIndex = index;
        if (prev >= 0 && prev < rows.size())   notifyItemChanged(prev, PAYLOAD_ACTIVE);
        if (index >= 0 && index < rows.size()) notifyItemChanged(index, PAYLOAD_ACTIVE);
    }

    private float rowAlpha(int position) {
        return position == activeIndex ? 1f : INACTIVE_ALPHA;
    }

    /** Toggles karaoke rendering. When true, the active TEXT row shows a
     *  per-word coloured span based on the position published by
     *  {@link #setKaraokePosSec(double)}. */
    public void setKaraokeMode(boolean enabled) {
        if (karaokeMode == enabled) return;
        karaokeMode = enabled;
        if (activeIndex >= 0 && activeIndex < rows.size()) {
            notifyItemChanged(activeIndex);
        }
    }

    public boolean isKaraokeMode() { return karaokeMode; }

    /** Publishes the current perceptual playback position (seconds). Only
     *  triggers a lightweight rebind of the active row when karaoke is on
     *  and the row is a text row with per-word timestamps. */
    public void setKaraokePosSec(double posSec) {
        double old = this.karaokePosSec;
        this.karaokePosSec = posSec;
        if (activeIndex < 0 || activeIndex >= rows.size()) return;
        boolean words = karaokeMode && hasWords(rows.get(activeIndex));
        // Chords on lyrics: repaint only when a chord of the line is reached.
        boolean chords = chordsOn && chordCrossed(activeIndex, old - chordLagSec, posSec - chordLagSec);
        if (words || chords) notifyItemChanged(activeIndex, PAYLOAD_KARAOKE);
    }

    // The lyrics clock runs ahead of the beat clock the carousel lights its
    // chords with; chords on lyrics follow the beat clock.
    private double chordLagSec = 0.0;

    /** How far the beat clock trails the lyrics clock, in seconds. */
    public void setChordLagSec(double lag) { chordLagSec = lag; }

    private double chordPos() { return karaokePosSec - chordLagSec; }

    @Override public int getItemViewType(int position) { return rows.get(position).type; }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == VT_INSTRUMENTAL) {
            return new InstrumentalVH(
                    inf.inflate(R.layout.lyrics_instrumental_row, parent, false));
        }
        TextVH vh = new TextVH(inf.inflate(R.layout.lyrics_line_row, parent, false));
        // Scale from the start edge and the vertical centre.
        vh.text.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            v.setPivotX(rtl ? (r - l) : 0f);
            v.setPivotY((b - t) / 2f);
        });
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        // Karaoke tick: only refresh the text span, skip the styling reset.
        if (payloads.contains(PAYLOAD_ACTIVE)) {
            bind(holder, position, true);
            return;
        }
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_KARAOKE) && position == activeIndex) {
            Row r = rows.get(position);
            if (holder instanceof TextVH && r.type == VT_TEXT && r.segment != null) {
                ChordLyricsTextView tv = ((TextVH) holder).text;
                if (karaokeMode && hasWords(r)) {
                    // Force SPANNABLE buffer so the per-word ClickableSpan stays
                    // active; the default NORMAL buffer would flatten to a plain
                    // String and swallow the touch handling.
                    tv.setText(buildKaraokeText(
                            holder.itemView.getContext(), r.segment, r.wordOnsets, karaokePosSec,
                            lineClickListener), TextView.BufferType.SPANNABLE);
                }
                tv.setChordPos(chordPos());
                return;
            }
            if (holder instanceof InstrumentalVH) {
                ((InstrumentalVH) holder).chords.setPos(chordPos(), true);
                return;
            }
        }
        bind(holder, position, false);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        bind(holder, position, false);
    }

    private void bind(@NonNull RecyclerView.ViewHolder holder, int position, boolean animate) {
        // A full rebind lands any running style animation first.
        Object running = holder.itemView.getTag(R.id.tag_lyrics_anim);
        if (running instanceof android.animation.Animator) {
            ((android.animation.Animator) running).cancel();
        }
        holder.itemView.setTag(R.id.tag_lyrics_anim, null);
        Row row = rows.get(position);
        boolean active = position == activeIndex;
        Context ctx = holder.itemView.getContext();
        int accent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(ctx);
        int dim    = ContextCompat.getColor(ctx, R.color.lyrics_line_dim);
        // The TextView is the itemView, so setting a whole-row OnClickListener
        // fires *in addition* to any ClickableSpan the karaoke text carries —
        // and the row callback runs later, overwriting the per-word seek.
        // Only wire the row listener on non-karaoke rows.
        boolean karaokeRow = karaokeMode && row.type == VT_TEXT
                && row.segment != null && row.segment.words != null
                && !row.segment.words.isEmpty();
        if (karaokeRow) {
            holder.itemView.setOnClickListener(null);
            holder.itemView.setClickable(false);
        } else {
            holder.itemView.setOnClickListener(v -> {
                if (lineClickListener != null) lineClickListener.onLyricsRowClicked(row);
            });
        }

        if (holder instanceof TextVH) {
            ChordLyricsTextView tv = ((TextVH) holder).text;
            // With chords the words are joined as the karaoke does, so the
            // chord anchors fall on the right characters.
            String raw = chordsOn ? wordLayout(position).text
                    : row.segment != null && row.segment.text != null
                    ? row.segment.text.trim() : "";
            final float fromScale = tv.getScaleX();
            final int fromColor = tv.getCurrentTextColor();
            final float fromAlpha = tv.getAlpha();
            int endPad = scaledEndPadding();
            if (tv.getPaddingEnd() != endPad) {
                tv.setPaddingRelative(tv.getPaddingStart(), tv.getPaddingTop(), endPad, tv.getPaddingBottom());
            }
            if (active) {
                tv.setScaleX(ACTIVE_SCALE);
                tv.setScaleY(ACTIVE_SCALE);
                tv.setAlpha(1f);
                boolean useKaraoke = karaokeMode && row.segment != null
                        && row.segment.words != null && !row.segment.words.isEmpty();
                if (useKaraoke) {
                    // Words not reached yet: a brighter grey than the other lines.
                    tv.setTextColor(ContextCompat.getColor(ctx, R.color.lyrics_line_current));
                    // Movement method + highlight colour go first so the
                    // subsequent setText stores the spannable as SPANNABLE
                    // (otherwise TextView flattens it to String and the
                    // per-word ClickableSpan never receives the tap).
                    tv.setHighlightColor(android.graphics.Color.TRANSPARENT);
                    tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    tv.setText(buildKaraokeText(ctx, row.segment, row.wordOnsets, karaokePosSec,
                            lineClickListener), TextView.BufferType.SPANNABLE);
                } else {
                    tv.setTextColor(ContextCompat.getColor(ctx, R.color.AppWhite));
                    tv.setText(raw);
                    tv.setMovementMethod(null);
                }
            } else {
                tv.setTextColor(dim);
                tv.setScaleX(1f);
                tv.setScaleY(1f);
                tv.setAlpha(rowAlpha(position));
                if (karaokeRow) {
                    // Inactive karaoke line: keep the per-word ClickableSpans
                    // so the user can seek from any line, not only the one
                    // being sung. Passing -1 as the position makes the
                    // builder skip the primary-colour blend and leave the
                    // dim base intact.
                    tv.setHighlightColor(android.graphics.Color.TRANSPARENT);
                    tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    tv.setText(buildKaraokeText(ctx, row.segment, row.wordOnsets, -1.0,
                            lineClickListener), TextView.BufferType.SPANNABLE);
                } else {
                    tv.setText(raw);
                    tv.setMovementMethod(null);
                }
            }
            bindLineChords(tv, position, active, accent, dim);
            if (animate) animateTextStyle(tv, fromScale, fromColor, fromAlpha);
        } else if (holder instanceof InstrumentalVH) {
            bindStripChords(((InstrumentalVH) holder).chords, position, active, accent, dim,
                    ContextCompat.getColor(ctx, R.color.lyrics_line_current));
            ImageView iv = ((InstrumentalVH) holder).icon;
            // Active note in white, like the active line; chords keep the accent.
            int toColor = active ? ContextCompat.getColor(ctx, R.color.AppWhite) : dim;
            float toAlpha = rowAlpha(position);
            float toScale = active ? 1.15f : 1f;
            if (!animate) {
                iv.setImageTintList(android.content.res.ColorStateList.valueOf(toColor));
                iv.setAlpha(toAlpha);
                iv.setScaleX(toScale);
                iv.setScaleY(toScale);
            } else {
                int fromColor = iv.getImageTintList() != null
                        ? iv.getImageTintList().getDefaultColor() : dim;
                float fromAlpha = iv.getAlpha(), fromScale = iv.getScaleX();
                android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
                a.setDuration(LINE_ANIM_MS);
                a.setInterpolator(LINE_INTERPOLATOR);
                a.addUpdateListener(v -> {
                    float f = (float) v.getAnimatedValue();
                    iv.setImageTintList(android.content.res.ColorStateList.valueOf(
                            androidx.core.graphics.ColorUtils.blendARGB(fromColor, toColor, f)));
                    iv.setAlpha(fromAlpha + (toAlpha - fromAlpha) * f);
                    float sc = fromScale + (toScale - fromScale) * f;
                    iv.setScaleX(sc);
                    iv.setScaleY(sc);
                });
                holder.itemView.setTag(R.id.tag_lyrics_anim, a);
                a.start();
            }
        }
    }

    /** Chord names above a text line: lit once reached on the active line, dim elsewhere. */
    private void bindLineChords(ChordLyricsTextView tv, int position, boolean active, int accent, int dim) {
        // Every line keeps the band (even without a chord) so the spacing is even.
        tv.setChordReveal(chordsOn ? chordReveal : 0f);
        if (!chordsOn || position >= rowChords.size() || rowChords.get(position).isEmpty()) {
            tv.setChords(null, null, 0, 0, 0);
            return;
        }
        List<ChordLyricsTextView.Anchor> anchors = new ArrayList<>();
        for (RowChord c : rowChords.get(position)) {
            anchors.add(new ChordLyricsTextView.Anchor(c.offset, chordLabel(c.raw), c.time, c.after));
        }
        int pending = active
                ? ContextCompat.getColor(tv.getContext(), R.color.lyrics_line_current) : dim;
        tv.setChords(anchors, chordTypeface, active ? accent : dim, pending,
                active ? chordPos() : Double.NEGATIVE_INFINITY);
    }

    /** Chords of an instrumental part, one row above the note. */
    private void bindStripChords(ChordStripView strip, int position, boolean active,
                                 int accent, int dim, int current) {
        List<RowChord> list = chordsOn && position < rowChords.size()
                ? rowChords.get(position) : Collections.emptyList();
        if (list.isEmpty()) {
            strip.setVisibility(View.GONE);
            return;
        }
        String[] labels = new String[list.size()];
        double[] times = new double[list.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = chordLabel(list.get(i).raw);
            times[i] = list.get(i).time;
        }
        strip.setVisibility(View.VISIBLE);
        strip.setReveal(chordReveal);
        strip.setChords(labels, times, chordTypeface);
        strip.setColors(active ? accent : dim, dim, active ? current : dim);
        strip.setPos(active ? chordPos() : -1.0, false);
    }

    /** Eases size, colour and alpha from the previous style to the one just
     *  bound. Karaoke spans override the text colour on the words they cover. */
    private static void animateTextStyle(TextView tv, float fromScale, int fromColor, float fromAlpha) {
        final float toScale = tv.getScaleX();
        final int toColor = tv.getCurrentTextColor();
        final float toAlpha = tv.getAlpha();
        if (fromScale == toScale && fromColor == toColor && fromAlpha == toAlpha) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(LINE_ANIM_MS);
        a.setInterpolator(LINE_INTERPOLATOR);
        a.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            float sc = fromScale + (toScale - fromScale) * f;
            tv.setScaleX(sc);
            tv.setScaleY(sc);
            tv.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(fromColor, toColor, f));
            tv.setAlpha(fromAlpha + (toAlpha - fromAlpha) * f);
        });
        tv.setTag(R.id.tag_lyrics_anim, a);
        a.start();
    }

    /** Builds the spannable that lights up each word depending on the current
     *  playback second. The base colour of the TextView is set to the dim
     *  colour, so future words inherit it without adding a span each. Words
     *  crossing their onset fade from dim to white over KARAOKE_FADE_SEC,
     *  and the word still in progress is bolded on top of the colour. */
    static final double KARAOKE_FADE_SEC = 0.25;

    private static CharSequence buildKaraokeText(Context ctx, Lyrics.Segment seg,
                                                 double[] onsets, double posSec,
                                                 LineClickListener clickListener) {
        // Sung words light up in white, whatever the accent.
        int lit = ContextCompat.getColor(ctx, R.color.AppWhite);
        // posSec < 0 = line not active: dim base; active line: brighter base.
        int base   = ContextCompat.getColor(ctx, posSec >= 0
                ? R.color.lyrics_line_current : R.color.lyrics_line_dim);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        List<Lyrics.Word> words = seg.words;
        for (int i = 0; i < words.size(); i++) {
            final Lyrics.Word w = words.get(i);
            String piece = w.text == null ? "" : w.text;
            if (i > 0 && !piece.startsWith(" ") && !piece.isEmpty()
                    && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            int start = sb.length();
            sb.append(piece);
            int end = sb.length();
            double onset = onsets != null && i < onsets.length ? onsets[i] : w.start;
            double t = (posSec - onset) / KARAOKE_FADE_SEC;
            float progress = t <= 0 ? 0f : (t >= 1 ? 1f : (float) t);
            if (progress > 0f) {
                int blended = androidx.core.graphics.ColorUtils
                        .blendARGB(base, lit, progress);
                sb.setSpan(new ForegroundColorSpan(blended), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // Each word is clickable and seeks to its own onset. The default
            // ClickableSpan would underline; override updateDrawState so the
            // color is left untouched.
            sb.setSpan(new android.text.style.ClickableSpan() {
                @Override public void onClick(@NonNull View v) {
                    // Seek to the word as sung (measured onset), not to its raw start.
                    if (clickListener != null) clickListener.onLyricsWordClicked(onset);
                }
                @Override public void updateDrawState(@NonNull android.text.TextPaint ds) {
                    ds.setUnderlineText(false);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    @Override public int getItemCount() { return rows.size(); }

    static class TextVH extends RecyclerView.ViewHolder {
        final ChordLyricsTextView text;
        TextVH(@NonNull View v) { super(v); text = v.findViewById(R.id.tv_lyricsLine); }
    }

    static class InstrumentalVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final ChordStripView chords;
        InstrumentalVH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.iv_lyricsInstrumental);
            chords = v.findViewById(R.id.cs_lyricsInstrumentalChords);
        }
    }
}
