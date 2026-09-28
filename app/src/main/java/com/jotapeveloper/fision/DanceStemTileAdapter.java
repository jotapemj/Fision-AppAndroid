package com.jotapeveloper.fision;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

/**
 * Tile grid for the stems dance dialog. Each tile shows the stem icon;
 * tapping it toggles the stem's participation in the dance, and the
 * "swing" toggle below (scene mode only) enables the vertical up/down
 * oscillation for that stem.
 */
public class DanceStemTileAdapter extends RecyclerView.Adapter<DanceStemTileAdapter.VH> {

    public static class VH extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView icon;
        final View divider;
        final TextView swing;
        final View ring;
        VH(@NonNull View itemView) {
            super(itemView);
            card    = itemView.findViewById(R.id.card_dance_stem);
            icon    = itemView.findViewById(R.id.iv_dance_stem_icon);
            divider = itemView.findViewById(R.id.v_dance_stem_divider);
            swing   = itemView.findViewById(R.id.tv_dance_stem_swing);
            ring    = itemView.findViewById(R.id.v_dance_stem_ring);
        }
    }

    private final java.util.List<String> stems;
    private final java.util.Set<String> selected;
    private final java.util.Set<String> sine;
    private final boolean hasVocals2;
    private final boolean showSwing;
    private final Runnable onSelectionChanged;
    private static final int SWING_DIM_COLOR = 0x66FFFFFF;

    public DanceStemTileAdapter(java.util.List<String> stems,
                                java.util.Set<String> selected,
                                java.util.Set<String> sine,
                                boolean hasVocals2,
                                boolean showSwing,
                                Runnable onSelectionChanged) {
        this.stems = stems;
        this.selected = selected;
        this.sine = sine;
        this.hasVocals2 = hasVocals2;
        this.showSwing = showSwing;
        this.onSelectionChanged = onSelectionChanged;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.dance_stem_tile_item, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        String stem = stems.get(position);
        int iconRes = StemSpec.iconResource(stem, hasVocals2);
        if (iconRes != 0) h.icon.setImageResource(iconRes);
        int titleRes = StemSpec.titleResource(stem, hasVocals2);
        h.icon.setContentDescription(titleRes != 0
                ? h.itemView.getContext().getString(titleRes)
                : StemSpec.displayLabel(stem));

        boolean sel = selected.contains(stem);
        int accent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(
                h.itemView.getContext());
        android.graphics.drawable.GradientDrawable ring =
                new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        float density = h.itemView.getContext().getResources().getDisplayMetrics().density;
        ring.setCornerRadius(14f * density);
        ring.setStroke(Math.round(1.5f * density), accent);
        h.ring.setBackground(ring);
        h.ring.setAlpha(sel ? 1f : 0f);
        h.card.setAlpha(sel ? 1f : 0.45f);

        // Swing toggle: scene mode only; accent when on.
        int swingVis = showSwing ? View.VISIBLE : View.GONE;
        h.divider.setVisibility(swingVis);
        h.swing.setVisibility(swingVis);
        h.swing.setTextColor(sine.contains(stem) ? accent : SWING_DIM_COLOR);

        h.card.setOnClickListener(v -> {
            if (selected.contains(stem)) selected.remove(stem);
            else selected.add(stem);
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
            if (onSelectionChanged != null) onSelectionChanged.run();
        });
        h.swing.setOnClickListener(v -> {
            if (sine.contains(stem)) sine.remove(stem);
            else sine.add(stem);
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
        });
    }

    @Override
    public int getItemCount() { return stems.size(); }

    /** Stems included in the dance. */
    public java.util.Set<String> getSelected() { return selected; }

    /** Stems with the vertical swing oscillation enabled. */
    public java.util.Set<String> getSine() { return sine; }
}
