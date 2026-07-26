package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

/** Renders one lyric line per row. The active line (whose [start,end] contains
 *  the current playback position) is drawn bold, larger and in the primary
 *  accent colour; the rest fade to the dim colour defined in colors.xml. */
public class LyricsLinesAdapter extends RecyclerView.Adapter<LyricsLinesAdapter.VH> {

    private List<Lyrics.Segment> segments = Collections.emptyList();
    private int activeIndex = -1;

    public void setSegments(List<Lyrics.Segment> segments) {
        this.segments   = segments != null ? segments : Collections.emptyList();
        this.activeIndex = -1;
        notifyDataSetChanged();
    }

    public int getActiveIndex() { return activeIndex; }

    public void setActiveIndex(int index) {
        if (index == activeIndex) return;
        int prev = activeIndex;
        activeIndex = index;
        if (prev >= 0 && prev < segments.size())   notifyItemChanged(prev);
        if (index >= 0 && index < segments.size()) notifyItemChanged(index);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.lyrics_line_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Lyrics.Segment seg = segments.get(position);
        h.text.setText(seg.text == null ? "" : seg.text.trim());
        Context ctx = h.itemView.getContext();
        boolean active = position == activeIndex;
        if (active) {
            h.text.setTextColor(ContextCompat.getColor(ctx, R.color.colorPrimary));
            h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
            h.text.setTypeface(h.text.getTypeface(), Typeface.BOLD);
            h.text.setAlpha(1f);
        } else {
            h.text.setTextColor(ContextCompat.getColor(ctx, R.color.lyrics_line_dim));
            h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            h.text.setTypeface(Typeface.create(h.text.getTypeface(), Typeface.NORMAL));
            h.text.setAlpha(0.85f);
        }
    }

    @Override public int getItemCount() { return segments.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView text;
        VH(@NonNull View v) {
            super(v);
            text = v.findViewById(R.id.tv_lyricsLine);
        }
    }
}
