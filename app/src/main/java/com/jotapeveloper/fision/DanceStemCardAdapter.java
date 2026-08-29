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
 * Card list for the stems dance dialog. Each row shows the stem icon and
 * name; tapping the card toggles its participation in the dance, and the
 * sine-wave icon (scene mode only) toggles the vertical up/down oscillation
 * for that stem.
 */
public class DanceStemCardAdapter extends RecyclerView.Adapter<DanceStemCardAdapter.VH> {

    public static class VH extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView icon;
        final TextView name;
        final ImageView sine;
        final View ring;
        VH(@NonNull View itemView) {
            super(itemView);
            card  = itemView.findViewById(R.id.card_dance_stem);
            icon  = itemView.findViewById(R.id.iv_dance_stem_icon);
            name  = itemView.findViewById(R.id.tv_dance_stem_name);
            sine  = itemView.findViewById(R.id.iv_dance_stem_sine);
            ring  = itemView.findViewById(R.id.v_dance_stem_ring);
        }
    }

    private final java.util.List<String> stems;
    private final java.util.Set<String> selected;
    private final java.util.Set<String> sine;
    private final boolean hasVocals2;
    private final boolean showSine;
    private static final int SINE_DIM_COLOR = 0x55FFFFFF;

    public DanceStemCardAdapter(java.util.List<String> stems,
                                java.util.Set<String> selected,
                                java.util.Set<String> sine,
                                boolean hasVocals2,
                                boolean showSine) {
        this.stems = stems;
        this.selected = selected;
        this.sine = sine;
        this.hasVocals2 = hasVocals2;
        this.showSine = showSine;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.dance_stem_card_item, parent, false);
        // Explicit pixel width for a constant card size across the list —
        // match_parent let individual cards re-measure to their content
        // width after a selection change. The XML margins are kept (only
        // the width is forced).
        RecyclerView.LayoutParams lp =
                (RecyclerView.LayoutParams) v.getLayoutParams();
        lp.width = parent.getWidth() > 0
                ? parent.getWidth()
                : RecyclerView.LayoutParams.MATCH_PARENT;
        v.setLayoutParams(lp);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        String stem = stems.get(position);
        int iconRes = StemSpec.iconResource(stem, hasVocals2);
        if (iconRes != 0) h.icon.setImageResource(iconRes);
        int titleRes = StemSpec.titleResource(stem, hasVocals2);
        h.name.setText(titleRes != 0
                ? h.itemView.getContext().getString(titleRes)
                : StemSpec.displayLabel(stem));

        boolean sel = selected.contains(stem);
        int accent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(
                h.itemView.getContext());
        // Selection is signalled by an overlay ring whose alpha is toggled
        // (no card stroke change — that triggered a re-measure and the
        // card collapsed to the text width).
        android.graphics.drawable.GradientDrawable ring =
                new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        float density = h.itemView.getContext().getResources().getDisplayMetrics().density;
        ring.setCornerRadius(14f * density);
        ring.setStroke(Math.round(1.5f * density), accent);
        h.ring.setBackground(ring);
        h.ring.setAlpha(sel ? 1f : 0f);
        h.card.setAlpha(sel ? 1f : 0.55f);

        // Sine-wave toggle: scene mode only; tinted with the accent when on.
        h.sine.setVisibility(showSine ? View.VISIBLE : View.INVISIBLE);
        h.sine.setColorFilter(sine.contains(stem) ? accent : SINE_DIM_COLOR);

        h.card.setOnClickListener(v -> {
            if (selected.contains(stem)) selected.remove(stem);
            else selected.add(stem);
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
        });
        h.sine.setOnClickListener(v -> {
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

    /** Stems with the vertical sine-wave oscillation enabled. */
    public java.util.Set<String> getSine() { return sine; }
}
