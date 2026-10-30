package com.jotapeveloper.fision.chords;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.jotapeveloper.fision.BeatCell;
import com.jotapeveloper.fision.BeatCellAdapter;
import com.jotapeveloper.fision.ChordFormatter;
import com.jotapeveloper.fision.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chord grid: the beats of the song as tiles, read top to bottom and left
 * to right, two bars per row (one when a bar has more than four beats).
 * Every bar starts on a bar column: a pickup before the first bar and odd
 * bars leave empty slots. Tiles look and light up like the carousel.
 */
public class ChordGridAdapter extends RecyclerView.Adapter<ChordGridAdapter.VH> {

    public interface OnCellClickListener { void onCellClick(int cellIdx); }

    private final Context ctx;
    private List<BeatCell> cells = Collections.emptyList();
    /** Grid slot -> beat cell index, -1 for an empty slot. */
    private int[] slotCell = new int[0];
    /** Beat cell index -> grid slot. */
    private int[] cellSlot = new int[0];
    private int beatsPerBar = 4;
    private int columns = 8;
    private int current = -1;
    private boolean latin, preferFlat;
    private int shift;
    @Nullable private Typeface typeface;
    @Nullable private OnCellClickListener clickListener;

    public ChordGridAdapter(Context ctx) { this.ctx = ctx; }

    public void setOnCellClickListener(@Nullable OnCellClickListener l) { clickListener = l; }

    public int getColumns() { return columns; }

    /** Rebuilds the grid; returns true when the column count changed. */
    public boolean setCells(List<BeatCell> cells) {
        this.cells = cells;
        int bpb = 1;
        for (BeatCell c : cells) bpb = Math.max(bpb, c.positionInBar);
        beatsPerBar = Math.max(2, bpb);
        int oldColumns = columns;
        columns = beatsPerBar <= 4 ? beatsPerBar * 2 : beatsPerBar;
        List<Integer> slots = new ArrayList<>(cells.size() + 16);
        cellSlot = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            BeatCell c = cells.get(i);
            if (i == 0 && !c.isDownbeat) {
                // Pickup: the first beat sits at its place within the bar.
                for (int k = 1; k < c.positionInBar; k++) slots.add(-1);
            } else if (c.isDownbeat) {
                while (slots.size() % beatsPerBar != 0) slots.add(-1);
            }
            cellSlot[i] = slots.size();
            slots.add(i);
        }
        slotCell = new int[slots.size()];
        for (int i = 0; i < slotCell.length; i++) slotCell[i] = slots.get(i);
        current = Math.min(current, cells.size() - 1);
        notifyDataSetChanged();
        return oldColumns != columns;
    }

    public void setFormat(boolean latin, boolean preferFlat, int shift, @Nullable Typeface tf) {
        if (this.latin == latin && this.preferFlat == preferFlat && this.shift == shift && typeface == tf) return;
        this.latin = latin;
        this.preferFlat = preferFlat;
        this.shift = shift;
        this.typeface = tf;
        notifyDataSetChanged();
    }

    /** Grid slot of beat cell [cellIdx] (0 when out of range). */
    public int slotOf(int cellIdx) {
        if (cellIdx < 0 || cellIdx >= cellSlot.length) return 0;
        return cellSlot[cellIdx];
    }

    /** Beat cell at grid [slot], or -1 for an empty slot. */
    public int cellAt(int slot) {
        return slot >= 0 && slot < slotCell.length ? slotCell[slot] : -1;
    }

    public void setHighlight(int cellIdx) {
        int old = current;
        current = cellIdx;
        // Every beat between the two positions changes state (played history).
        int min = (old >= 0 && cellIdx >= 0) ? Math.min(old, cellIdx) : 0;
        int max = Math.max(old, cellIdx);
        if (max < 0 || cells.isEmpty()) return;
        int from = slotOf(Math.max(0, min));
        int to = slotOf(Math.min(cells.size() - 1, max));
        if (to >= from) notifyItemRangeChanged(from, to - from + 1);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.chord_grid_cell, parent, false);
        VH h = new VH(v);
        v.setOnClickListener(view -> {
            int cell = cellAt(h.getBindingAdapterPosition());
            if (cell >= 0 && clickListener != null) clickListener.onCellClick(cell);
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int slot) {
        int cellIdx = slotCell[slot];
        if (cellIdx < 0) {
            h.itemView.setVisibility(View.INVISIBLE);
            h.itemView.setClickable(false);
            return;
        }
        h.itemView.setVisibility(View.VISIBLE);
        h.itemView.setClickable(true);
        BeatCell cell = cells.get(cellIdx);
        int accent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(ctx);
        BeatCellAdapter.bindChord(h.chord, cells, cellIdx, current,
                ChordFormatter.format(BeatCellAdapter.chordShown(cells, cellIdx), shift, latin, preferFlat),
                typeface);
        h.bg.getBackground().mutate().setTint(
                BeatCellAdapter.stateColor(cellIdx, current, beatsPerBar, cell.isDownbeat, accent, false));
    }

    @Override public int getItemCount() { return slotCell.length; }

    static final class VH extends RecyclerView.ViewHolder {
        final View bg;
        final TextView chord;
        VH(@NonNull View v) {
            super(v);
            bg = v.findViewById(R.id.v_grid_bg);
            chord = v.findViewById(R.id.tv_grid_chord);
        }
    }

    /**
     * Gaps between tiles and the bar line: an accent bar in the gap before
     * each bar that does not start a row.
     */
    public static final class BarDecoration extends RecyclerView.ItemDecoration {
        private final ChordGridAdapter adapter;
        private final int gap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public BarDecoration(ChordGridAdapter adapter, float density) {
            this.adapter = adapter;
            this.gap = Math.round(3f * density);
            paint.setStrokeWidth(2f * density);
        }

        @Override
        public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
                                   @NonNull RecyclerView.State state) {
            out.set(gap, gap, gap, gap);
        }

        @Override
        public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            paint.setColor(com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(parent.getContext()));
            int bar = adapter.columns > adapter.beatsPerBar ? adapter.beatsPerBar : adapter.columns;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View v = parent.getChildAt(i);
                int slot = parent.getChildAdapterPosition(v);
                if (slot == RecyclerView.NO_POSITION) continue;
                int col = slot % adapter.columns;
                if (col == 0 || col % bar != 0) continue;
                float x = v.getLeft() - gap;
                float h = v.getHeight() * 0.62f;
                float cy = (v.getTop() + v.getBottom()) / 2f + v.getTranslationY();
                float w = paint.getStrokeWidth();
                r.set(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f);
                c.drawRoundRect(r, w / 2f, w / 2f, paint);
            }
        }
    }
}
