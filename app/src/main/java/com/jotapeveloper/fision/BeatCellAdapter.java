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
 * The beat currently playing lights up; past beats of the current bar
 * keep a faint trail; the rest stay at the base color.
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
    // yugothic_ui_regular is readable for the Latin syllables (Do, Re, ...).
    private android.graphics.Typeface tfMusGlyphs;
    private android.graphics.Typeface tfYuGothic;

    // Colors
    private static final int COLOR_BASE      = Color.parseColor("#262626");
    private static final int COLOR_DOWNBEAT  = Color.parseColor("#3A3A3A");
    private static final int COLOR_TRAIL     = Color.parseColor("#27725F"); // faint
    private static final int COLOR_ACTIVE    = Color.parseColor("#27B397"); // primary

    public BeatCellAdapter(Context context, List<BeatCell> cells, int beatsPerBar) {
        this.context     = context;
        this.cells       = cells;
        this.beatsPerBar = beatsPerBar;
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
        // Repaint the affected range: old bar + new bar (covers the transition
        // between bars so the trail does not switch off abruptly).
        int min = old >= 0 ? Math.min(old, beatIndex) : beatIndex;
        int max = old >= 0 ? Math.max(old, beatIndex) : beatIndex;
        if (max < 0) return;
        int barStart = (min / beatsPerBar) * beatsPerBar;
        int barEnd   = Math.min(cells.size() - 1, ((max / beatsPerBar) + 1) * beatsPerBar - 1);
        for (int i = barStart; i <= barEnd; i++) notifyItemChanged(i);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.beat_cell_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BeatCell cell = cells.get(position);
        holder.tv_chord.setText(ChordFormatter.format(cell.chord, semitonesShift, latinNotation, preferFlat));
        holder.tv_chord.setTypeface(typefaceForCurrent());

        // Bar start -> visible vertical marker
        holder.v_marker.setVisibility(cell.isDownbeat ? View.VISIBLE : View.GONE);

        // Background color depending on state.
        // Beats of the current bar already elapsed -> trail.
        // The last beat of the previous bar stays in trail color until the next bar
        // begins (avoids switching it off too early).
        int color;
        if (position == currentBeatIndex) {
            color = COLOR_ACTIVE;
        } else if (currentBeatIndex >= 0
                && position < currentBeatIndex
                && currentBeatIndex - position < beatsPerBar) {
            color = COLOR_TRAIL;
        } else if (cell.isDownbeat) {
            color = COLOR_DOWNBEAT;
        } else {
            color = COLOR_BASE;
        }
        holder.v_bg.getBackground().mutate().setTint(color);
    }

    @Override
    public int getItemCount() { return cells.size(); }

    /** Returns the typeface for the active notation (lazy + cached). */
    private android.graphics.Typeface typefaceForCurrent() {
        if (latinNotation) {
            if (tfYuGothic == null) {
                tfYuGothic = androidx.core.content.res.ResourcesCompat.getFont(
                        context, R.font.yugothic_ui_regular);
            }
            return tfYuGothic;
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
