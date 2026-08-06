package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
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

    private static final int VT_TEXT         = 0;
    private static final int VT_INSTRUMENTAL = 1;

    // Payload sent to notifyItemChanged so the karaoke tick only rebinds the
    // text span without swapping styles / triggering full rebind animations.
    private static final Object PAYLOAD_KARAOKE = new Object();

    public static class Row {
        public final int    type;
        public final double start;
        public final double end;
        public final Lyrics.Segment segment;
        Row(int type, double start, double end, Lyrics.Segment seg) {
            this.type = type; this.start = start; this.end = end; this.segment = seg;
        }
    }

    private List<Row> rows = Collections.emptyList();
    private int activeIndex = -1;
    private boolean karaokeMode = false;
    private double karaokePosSec = 0.0;
    private LineClickListener lineClickListener;

    public interface LineClickListener {
        void onLyricsRowClicked(Row row);
        default void onLyricsWordClicked(double startSec) { }
    }

    public void setLineClickListener(LineClickListener l) { this.lineClickListener = l; }

    public void setLyrics(Lyrics lyrics) {
        if (lyrics == null || lyrics.segments == null || lyrics.segments.isEmpty()) {
            this.rows = Collections.emptyList();
            this.activeIndex = -1;
            notifyDataSetChanged();
            return;
        }
        List<Row> out = new ArrayList<>(lyrics.segments.size() + 4);
        double prevEnd = 0.0;
        for (int i = 0; i < lyrics.segments.size(); i++) {
            Lyrics.Segment s = lyrics.segments.get(i);
            if (s.start - prevEnd >= INSTRUMENTAL_GAP_SEC) {
                out.add(new Row(VT_INSTRUMENTAL, prevEnd, s.start, null));
            }
            out.add(new Row(VT_TEXT, s.start, s.end, s));
            prevEnd = s.end;
        }
        if (lyrics.duration > 0.0 && lyrics.duration - prevEnd >= INSTRUMENTAL_GAP_SEC) {
            out.add(new Row(VT_INSTRUMENTAL, prevEnd, lyrics.duration, null));
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
        if (prev >= 0 && prev < rows.size())   notifyItemChanged(prev);
        if (index >= 0 && index < rows.size()) notifyItemChanged(index);
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
        return new TextVH(inf.inflate(R.layout.lyrics_line_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        // Karaoke tick: only refresh the text span, skip the styling reset.
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_KARAOKE)
                && holder instanceof TextVH) {
            Row r = rows.get(position);
            if (r.type == VT_TEXT && r.segment != null) {
                // Force SPANNABLE buffer so the per-word ClickableSpan stays
                // active; the default NORMAL buffer would flatten to a plain
                // String and swallow the touch handling.
                ((TextVH) holder).text.setText(buildKaraokeText(
                        holder.itemView.getContext(), r.segment, karaokePosSec,
                        lineClickListener), TextView.BufferType.SPANNABLE);
                return;
            }
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
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
            if (active) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
                tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
                tv.setAlpha(1f);
                boolean useKaraoke = karaokeMode && row.segment != null
                        && row.segment.words != null && !row.segment.words.isEmpty();
                if (useKaraoke) {
                    tv.setTextColor(dim);
                    // Movement method + highlight colour go first so the
                    // subsequent setText stores the spannable as SPANNABLE
                    // (otherwise TextView flattens it to String and the
                    // per-word ClickableSpan never receives the tap).
                    tv.setHighlightColor(android.graphics.Color.TRANSPARENT);
                    tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    tv.setText(buildKaraokeText(ctx, row.segment, karaokePosSec,
                            lineClickListener), TextView.BufferType.SPANNABLE);
                } else {
                    tv.setTextColor(accent);
                    tv.setText(raw);
                    tv.setMovementMethod(null);
                }
            } else {
                tv.setTextColor(dim);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
                tv.setTypeface(Typeface.create(tv.getTypeface(), Typeface.NORMAL));
                tv.setAlpha(0.85f);
                if (karaokeRow) {
                    // Inactive karaoke line: keep the per-word ClickableSpans
                    // so the user can seek from any line, not only the one
                    // being sung. Passing -1 as the position makes the
                    // builder skip the primary-colour blend and leave the
                    // dim base intact.
                    tv.setHighlightColor(android.graphics.Color.TRANSPARENT);
                    tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    tv.setText(buildKaraokeText(ctx, row.segment, -1.0,
                            lineClickListener), TextView.BufferType.SPANNABLE);
                } else {
                    tv.setText(raw);
                    tv.setMovementMethod(null);
                }
            }
        } else if (holder instanceof InstrumentalVH) {
            ImageView iv = ((InstrumentalVH) holder).icon;
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(active ? accent : dim));
            iv.setAlpha(active ? 1f : 0.85f);
            float scale = active ? 1.15f : 1f;
            iv.setScaleX(scale);
            iv.setScaleY(scale);
        }
    }

    /** Builds the spannable that lights up each word depending on the current
     *  playback second. The base colour of the TextView is set to the dim
     *  colour, so future words inherit it without adding a span each. Words
     *  crossing their onset fade from dim to primary over KARAOKE_FADE_SEC,
     *  and the word still in progress is bolded on top of the colour. */
    private static final double KARAOKE_FADE_SEC = 0.25;

    private static CharSequence buildKaraokeText(Context ctx, Lyrics.Segment seg,
                                                 double posSec,
                                                 LineClickListener clickListener) {
        int accent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(ctx);
        int dim    = ContextCompat.getColor(ctx, R.color.lyrics_line_dim);
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
            double t = (posSec - w.start) / KARAOKE_FADE_SEC;
            float progress = t <= 0 ? 0f : (t >= 1 ? 1f : (float) t);
            if (progress > 0f) {
                int blended = androidx.core.graphics.ColorUtils
                        .blendARGB(dim, accent, progress);
                sb.setSpan(new ForegroundColorSpan(blended), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (posSec < w.end) {
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            // Each word is clickable and seeks to its own onset. The default
            // ClickableSpan would underline; override updateDrawState so the
            // color is left untouched.
            sb.setSpan(new android.text.style.ClickableSpan() {
                @Override public void onClick(@NonNull View v) {
                    if (clickListener != null) clickListener.onLyricsWordClicked(w.start);
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
