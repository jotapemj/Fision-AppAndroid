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
 *  drawn in the primary accent colour; the rest fade to the dim colour
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
        notifyDataSetChanged();
    }

    public List<Row> getRows() { return rows; }

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
        this.karaokePosSec = posSec;
        if (!karaokeMode) return;
        if (activeIndex < 0 || activeIndex >= rows.size()) return;
        Row r = rows.get(activeIndex);
        if (r.type != VT_TEXT || r.segment == null
                || r.segment.words == null || r.segment.words.isEmpty()) return;
        notifyItemChanged(activeIndex, PAYLOAD_KARAOKE);
    }

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
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_KARAOKE)
                && holder instanceof TextVH && position == activeIndex) {
            Row r = rows.get(position);
            if (r.type == VT_TEXT && r.segment != null) {
                // Force SPANNABLE buffer so the per-word ClickableSpan stays
                // active; the default NORMAL buffer would flatten to a plain
                // String and swallow the touch handling.
                ((TextVH) holder).text.setText(buildKaraokeText(
                        holder.itemView.getContext(), r.segment, r.wordOnsets, karaokePosSec,
                        lineClickListener), TextView.BufferType.SPANNABLE);
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
            TextView tv = ((TextVH) holder).text;
            String raw = row.segment != null && row.segment.text != null
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
                    tv.setTextColor(accent);
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
            if (animate) animateTextStyle(tv, fromScale, fromColor, fromAlpha);
        } else if (holder instanceof InstrumentalVH) {
            ImageView iv = ((InstrumentalVH) holder).icon;
            int toColor = active ? accent : dim;
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
     *  crossing their onset fade from dim to primary over KARAOKE_FADE_SEC,
     *  and the word still in progress is bolded on top of the colour. */
    static final double KARAOKE_FADE_SEC = 0.25;

    private static CharSequence buildKaraokeText(Context ctx, Lyrics.Segment seg,
                                                 double[] onsets, double posSec,
                                                 LineClickListener clickListener) {
        int accent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(ctx);
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
                        .blendARGB(base, accent, progress);
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
        final TextView text;
        TextVH(@NonNull View v) { super(v); text = v.findViewById(R.id.tv_lyricsLine); }
    }

    static class InstrumentalVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        InstrumentalVH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.iv_lyricsInstrumental);
        }
    }
}
