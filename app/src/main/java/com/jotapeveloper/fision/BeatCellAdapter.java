package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Adapter of the rhythm visualizer. Each cell = one beat.
 * The beat currently playing lights up; the last bar's worth of elapsed beats
 * keeps a trail; every earlier elapsed beat a subtle played tint; beats still
 * to come stay at the base color.
 */
public class BeatCellAdapter extends RecyclerView.Adapter<BeatCellAdapter.ViewHolder> {

    private final Context context;
    private final List<BeatCell> cells;

    // Highlight state driven from the outside (PlayerActivity timer)
    private int currentBeatIndex = -1;
    private int beatsPerBar = 4;

    // Presentation format: notation + accidental + transposition (semitones)
    private boolean latinNotation   = false;
    private boolean preferFlat      = false;
    private int     semitonesShift  = 0;

    // Cached typefaces (lazy). mus_glyphs interprets ';' as superscript (American);
    // noto_sans_regular is readable for the Latin syllables (Do, Re, ...).
    private android.graphics.Typeface tfMusGlyphs;
    private android.graphics.Typeface tfLatin;

    // Colors
    private static final int COLOR_BASE      = Color.parseColor("#262626");
    private static final int COLOR_DOWNBEAT  = Color.parseColor("#3A3A3A");
    /** Accent share of the played-history tint (subtle, below the trail). */
    private static final float PLAYED_TINT   = 0.22f;

    // Ghost of a held chord at bar starts.
    private static final float CHORD_SP = 18f, GHOST_SP = 14f;
    private static final float GHOST_ALPHA = 0.32f, GHOST_ALPHA_ACTIVE = 0.6f;

    /** Tap on a beat cell (jump the song to that beat). */
    public interface OnCellClickListener { void onCellClick(int position); }
    @androidx.annotation.Nullable private OnCellClickListener cellClickListener;

    public BeatCellAdapter(Context context, List<BeatCell> cells, int beatsPerBar) {
        this.context     = context;
        this.cells       = cells;
        this.beatsPerBar = beatsPerBar;
    }

    public void setOnCellClickListener(@androidx.annotation.Nullable OnCellClickListener l) {
        cellClickListener = l;
    }

    /** Switches notation (American/Latin) and refreshes the whole recycler. */
    public void setLatinNotation(boolean latin) {
        if (latinNotation == latin) return;
        latinNotation = latin;
        notifyDataSetChanged();
    }

    /** Switches accidental preference (flat vs sharp). */
    public void setPreferFlat(boolean flat) {
        if (preferFlat == flat) return;
        preferFlat = flat;
        notifyDataSetChanged();
    }

    /** Sets the transposition applied to the display and refreshes the whole recycler. */
    public void setSemitonesShift(int semitones) {
        if (semitonesShift == semitones) return;
        semitonesShift = semitones;
        notifyDataSetChanged();
    }

    public void setHighlight(int beatIndex) {
        int old = currentBeatIndex;
        currentBeatIndex = beatIndex;
        // Every cell between the old and the new position changes state
        // (played history), plus one bar ahead of the highest for the trail.
        // A reset (-1) or a first highlight repaints from the start.
        int min = (old >= 0 && beatIndex >= 0) ? Math.min(old, beatIndex) : 0;
        int max = Math.max(old, beatIndex);
        if (max < 0 || cells.isEmpty()) return;
        int from = Math.max(0, min - beatsPerBar);
        int to   = Math.min(cells.size() - 1, max + beatsPerBar);
        if (to >= from) notifyItemRangeChanged(from, to - from + 1);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.beat_cell_item, parent, false);
        ViewHolder h = new ViewHolder(v);
        v.setOnClickListener(view -> {
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && cellClickListener != null) {
                cellClickListener.onCellClick(pos);
            }
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BeatCell cell = cells.get(position);
        int active = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(context);
        bindChord(holder.tv_chord, cells, position, currentBeatIndex,
                ChordFormatter.format(chordShown(cells, position), semitonesShift, latinNotation, preferFlat),
                typefaceForCurrent());

        // Bar start -> visible vertical marker, in the player accent (the XML
        // ?attr/colorPrimary would stay on the theme accent).
        holder.v_marker.setVisibility(cell.isDownbeat ? View.VISIBLE : View.GONE);
        holder.v_marker.setBackgroundColor(active);

        holder.v_bg.getBackground().mutate().setTint(
                stateColor(position, currentBeatIndex, beatsPerBar, cell.isDownbeat, active));
    }

    /**
     * Tile colour of beat [position] with [current] playing:
     * - current beat: full accent;
     * - the last bar's worth of elapsed beats: trail (stronger);
     * - every earlier elapsed beat: played (subtle accent tint), so the
     *   view shows how far the song has gone with no hard cut;
     * - beats still to come: base / downbeat.
     */
    public static int stateColor(int position, int current, int beatsPerBar,
                                 boolean downbeat, int accent) {
        return stateColor(position, current, beatsPerBar, downbeat, accent, true);
    }

    /** As above; without [trail] the elapsed beats of the bar get the played tint. */
    public static int stateColor(int position, int current, int beatsPerBar,
                                 boolean downbeat, int accent, boolean trail) {
        if (position == current) return accent;
        if (trail && current >= 0 && position < current && current - position < beatsPerBar) {
            return androidx.core.graphics.ColorUtils.blendARGB(accent, 0xFF000000, 0.55f);
        }
        if (current >= 0 && position < current) {
            return androidx.core.graphics.ColorUtils.blendARGB(
                    downbeat ? COLOR_DOWNBEAT : COLOR_BASE, accent, PLAYED_TINT);
        }
        return downbeat ? COLOR_DOWNBEAT : COLOR_BASE;
    }

    /**
     * Chord written on beat [position]: its own, or at a bar start without
     * one the chord still held (drawn as a ghost by {@link #bindChord}).
     */
    @androidx.annotation.Nullable
    public static String chordShown(List<BeatCell> cells, int position) {
        BeatCell cell = cells.get(position);
        if (cell.chord != null || !cell.isDownbeat) return cell.chord;
        return sustainedChord(cells, position);
    }

    /**
     * Chord label of a tile: a chord held over several bars is recalled at
     * each bar start as a faint, smaller ghost until the next chord.
     */
    public static void bindChord(TextView tv, List<BeatCell> cells, int position, int current,
                                 String label, android.graphics.Typeface tf) {
        boolean ghost = cells.get(position).chord == null && label != null && !label.isEmpty();
        tv.setText(label);
        tv.setTypeface(tf);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, ghost ? GHOST_SP : CHORD_SP);
        tv.setAlpha(!ghost ? 1f : position == current ? GHOST_ALPHA_ACTIVE : GHOST_ALPHA);
    }

    @Override
    public int getItemCount() { return cells.size(); }

    /** Chord still sounding at [position]: the last one that started before. */
    @androidx.annotation.Nullable
    private static String sustainedChord(List<BeatCell> cells, int position) {
        for (int i = position - 1; i >= 0; i--) {
            String c = cells.get(i).chord;
            if (c != null) return c;
        }
        return null;
    }

    /** Returns the typeface for the active notation (lazy + cached). */
    private android.graphics.Typeface typefaceForCurrent() {
        if (latinNotation) {
            if (tfLatin == null) {
                tfLatin = androidx.core.content.res.ResourcesCompat.getFont(
                        context, R.font.noto_sans_regular);
            }
            return tfLatin;
        }
        if (tfMusGlyphs == null) {
            tfMusGlyphs = androidx.core.content.res.ResourcesCompat.getFont(
                    context, R.font.mus_glyhps);
        }
        return tfMusGlyphs;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View v_bg;
        View v_marker;
        TextView tv_chord;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            v_bg     = itemView.findViewById(R.id.v_beat_bg);
            v_marker = itemView.findViewById(R.id.v_bar_marker);
            tv_chord = itemView.findViewById(R.id.tv_beat_chord);
        }
    }
}
