package com.jotapeveloper.fision;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

/**
 * Tile grid for the import screen. Each tile shows a stem the server can
 * separate; the footer shows its name, or a two-option variant switch for
 * vocals and drums. A variant change cross-fades the icon. The host owns
 * all state and rebinds after every change.
 */
public class ImportStemTileAdapter extends RecyclerView.Adapter<ImportStemTileAdapter.VH> {

    /** Stems that always come out of the separation and cannot be deselected. */
    public static final String LOCKED_STEM = "vocals";
    /** Rest of the mix: always produced, shown permanently selected. */
    public static final String REST_STEM = "other";

    public interface Host {
        boolean isSelected(String stem);
        /** Name of the rest of the mix (other instruments / accompaniment). */
        int restNameResource();
        boolean isVocalsSplit();
        boolean isDrumsSplit();
        void onTileTapped(String stem);
        /** A variant half was tapped: split = right half. */
        void onVariantTapped(String stem, boolean split);
    }

    public static class VH extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView icon;
        final View diag;
        final TextView title;
        final View variantRow;
        final TextView variantA, variantB;
        final View ring;
        VH(@NonNull View itemView) {
            super(itemView);
            card       = itemView.findViewById(R.id.card_import_stem);
            icon       = itemView.findViewById(R.id.iv_import_stem_icon);
            diag       = itemView.findViewById(R.id.v_import_stem_diag);
            title      = itemView.findViewById(R.id.tv_import_stem_title);
            variantRow = itemView.findViewById(R.id.ll_import_stem_variant);
            variantA   = itemView.findViewById(R.id.tv_import_stem_variantA);
            variantB   = itemView.findViewById(R.id.tv_import_stem_variantB);
            ring       = itemView.findViewById(R.id.v_import_stem_ring);
        }
    }

    private static final int OPTION_IDLE_COLOR = 0x99FFFFFF;
    private static final int OPTION_OUTLINE_COLOR = 0x4D808080;
    private static final int ICON_FADE_MS = 240;

    private final java.util.List<String> stems;
    private final Host host;

    public ImportStemTileAdapter(java.util.List<String> stems, Host host) {
        this.stems = stems;
        this.host = host;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.import_stem_tile_item, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        String stem = stems.get(position);
        android.content.Context ctx = h.itemView.getContext();
        boolean hasVariant = isVariantStem(stem);
        boolean split = hasVariant && variantOn(stem);

        bindIcon(h, stem, iconResource(stem, split), "vocals".equals(stem) && split);
        int nameRes = REST_STEM.equals(stem) ? host.restNameResource() : nameResource(stem);
        h.icon.setContentDescription(ctx.getString(nameRes));

        boolean sel = host.isSelected(stem);
        int accent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable ring =
                new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        ring.setCornerRadius(14f * density);
        ring.setStroke(Math.round(1.5f * density), accent);
        h.ring.setBackground(ring);
        h.ring.setAlpha(sel ? 1f : 0f);
        h.card.setAlpha(sel ? 1f : 0.45f);

        h.title.setText(nameRes);
        h.variantRow.setVisibility(hasVariant ? View.VISIBLE : View.GONE);
        if (hasVariant) {
            h.variantA.setText(variantLabel(stem, false));
            h.variantB.setText(variantLabel(stem, true));
            styleOption(h.variantA, !split, accent, density);
            styleOption(h.variantB, split, accent, density);
            h.variantA.setOnClickListener(v -> host.onVariantTapped(stem, false));
            h.variantB.setOnClickListener(v -> host.onVariantTapped(stem, true));
        }

        h.card.setOnClickListener(v -> host.onTileTapped(stem));
    }

    /** Sets the icon; a variant change on a tile already showing the same
     *  stem cross-fades. A holder rebound to another stem swaps instantly. */
    private void bindIcon(VH h, String stem, int res, boolean showDiag) {
        Object prev = h.icon.getTag(R.id.tag_import_icon_res);
        String key = stem + ":" + res;
        h.icon.setTag(R.id.tag_import_icon_res, key);
        boolean sameStemNewIcon = prev instanceof String
                && ((String) prev).startsWith(stem + ":") && !prev.equals(key);
        float diagAlpha = showDiag ? 1f : 0f;
        if (sameStemNewIcon && h.icon.getDrawable() != null) {
            Drawable from = h.icon.getDrawable();
            if (from instanceof TransitionDrawable) {
                TransitionDrawable td = (TransitionDrawable) from;
                from = td.getDrawable(td.getNumberOfLayers() - 1);
            }
            Drawable to = ContextCompat.getDrawable(h.icon.getContext(), res);
            TransitionDrawable td = new TransitionDrawable(new Drawable[]{from, to});
            td.setCrossFadeEnabled(true);
            h.icon.setImageDrawable(td);
            td.startTransition(ICON_FADE_MS);
            if (showDiag) { h.diag.setVisibility(View.VISIBLE); h.diag.setAlpha(0f); }
            h.diag.animate().alpha(diagAlpha).setDuration(ICON_FADE_MS)
                    .withEndAction(() -> { if (!showDiag) h.diag.setVisibility(View.GONE); })
                    .start();
        } else {
            h.icon.setImageResource(res);
            h.diag.animate().cancel();
            h.diag.setAlpha(diagAlpha);
            h.diag.setVisibility(showDiag ? View.VISIBLE : View.GONE);
        }
    }

    private Typeface baseFont;

    /** Option button: accent pill when active, thin grey outline otherwise. */
    private void styleOption(TextView tv, boolean active, int accent, float density) {
        if (baseFont == null) {
            baseFont = androidx.core.content.res.ResourcesCompat.getFont(
                    tv.getContext(), R.font.yugothic_ui_regular);
        }
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(18f * density);
        if (active) bg.setColor(accent);
        else bg.setStroke(Math.round(density), OPTION_OUTLINE_COLOR);
        tv.setBackground(bg);
        tv.setTextColor(active ? 0xFFFFFFFF : OPTION_IDLE_COLOR);
        // Derived from the regular face each time: a bold typeface cannot be un-bolded.
        tv.setTypeface(Typeface.create(baseFont, active ? Typeface.BOLD : Typeface.NORMAL));
    }

    @Override
    public int getItemCount() { return stems.size(); }

    private static boolean isVariantStem(String stem) {
        return "vocals".equals(stem) || "drums".equals(stem);
    }

    private boolean variantOn(String stem) {
        return "vocals".equals(stem) ? host.isVocalsSplit() : host.isDrumsSplit();
    }

    private static int iconResource(String stem, boolean split) {
        if ("vocals".equals(stem)) return split ? R.raw.ic_vocals_split : R.raw.ic_vocals;
        if ("drums".equals(stem))  return split ? R.raw.ic_drums_split  : R.raw.ic_drums;
        return StemSpec.iconResource(stem, false);
    }

    private static int variantLabel(String stem, boolean split) {
        if ("vocals".equals(stem)) return split ? R.string.import_tile_vocals_split
                                                : R.string.import_tile_vocals_single;
        return split ? R.string.import_tile_drums_split : R.string.import_tile_drums_full;
    }

    private static int nameResource(String stem) {
        switch (stem) {
            case "vocals": return R.string.import_vocals_always;
            case "guitar": return R.string.import_stem_guitar;
            case "bass":   return R.string.import_stem_bass;
            case "drums":  return R.string.import_stem_drums;
            case "piano":  return R.string.import_stem_piano;
            case "winds":  return R.string.import_stem_brass;
            default:       return R.string.title_other;
        }
    }
}
