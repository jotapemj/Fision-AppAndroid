package com.jotapeveloper.fision;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Horizontal recycler of coloured circles. One row for subject tint and one
 *  for background tint of the avatar studio. */
public class AvatarColorAdapter extends RecyclerView.Adapter<AvatarColorAdapter.VH> {

    public interface OnColorSelected { void onColorPicked(int color); }

    private final int[] colors;
    private int selectedIndex;
    private final OnColorSelected listener;

    public AvatarColorAdapter(int[] colors, int selectedIndex, OnColorSelected l) {
        this.colors = colors;
        this.selectedIndex = selectedIndex;
        this.listener = l;
    }

    public void setSelectedColor(int color) {
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] == color) {
                setSelectedIndex(i);
                return;
            }
        }
    }

    public void setSelectedIndex(int idx) {
        int old = selectedIndex;
        selectedIndex = idx;
        if (old >= 0) notifyItemChanged(old);
        if (idx >= 0) notifyItemChanged(idx);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_avatar_color_swatch, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        int color = colors[position];
        GradientDrawable dot = (GradientDrawable) holder.fill.getBackground().mutate();
        dot.setColor(color);
        holder.ring.setVisibility(position == selectedIndex ? View.VISIBLE : View.INVISIBLE);
        holder.itemView.setOnClickListener(v -> {
            setSelectedIndex(position);
            if (listener != null) listener.onColorPicked(color);
        });
    }

    @Override public int getItemCount() { return colors.length; }

    static class VH extends RecyclerView.ViewHolder {
        final View ring;
        final View fill;
        VH(@NonNull View v) {
            super(v);
            ring = v.findViewById(R.id.v_swatchRing);
            fill = v.findViewById(R.id.v_swatchFill);
        }
    }
}
