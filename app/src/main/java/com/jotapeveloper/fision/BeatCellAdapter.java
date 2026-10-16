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
        holder.tv_chord.setText(ChordFormatter.format(cell.chord, semitonesShift, latinNotation, preferFlat));
        holder.tv_chord.setTypeface(typefaceForCurrent());

        int active = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(context);

        // Bar start -> visible vertical marker, in the player accent (the XML
        // ?attr/colorPrimary would stay on the theme accent).
        holder.v_marker.setVisibility(cell.isDownbeat ? View.VISIBLE : View.GONE);
        holder.v_marker.setBackgroundColor(active);

        // Background color depending on state:
        // - current beat: full accent;
        // - the last bar's worth of elapsed beats: trail (stronger);
        // - every earlier elapsed beat: played (subtle accent tint), so the
        //   carousel shows how far the song has gone with no hard cut;
        // - beats still to come: base / downbeat.
        int trail = androidx.core.graphics.ColorUtils.blendARGB(active, 0xFF000000, 0.55f);
        int color;
        if (position == currentBeatIndex) {
            color = active;
        } else if (currentBeatIndex >= 0
                && position < currentBeatIndex
                && currentBeatIndex - position < beatsPerBar) {
            color = trail;
        } else if (currentBeatIndex >= 0 && position < currentBeatIndex) {
            color = androidx.core.graphics.ColorUtils.blendARGB(
                    cell.isDownbeat ? COLOR_DOWNBEAT : COLOR_BASE, active, PLAYED_TINT);
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
