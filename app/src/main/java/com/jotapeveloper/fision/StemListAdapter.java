package com.jotapeveloper.fision;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

/**
 * Data-driven adapter: each item is a generic stem (vocals, drums, piano, etc.).
 * Icons come from StemSpec.iconResource; changes go through PlayerActivity.applyStemVolume.
 *
 * When the song ships split drums (kick/snare/hh/crash/ride/toms) the adapter
 * groups them under a collapsible "Drum kit" header to avoid crowding the UI
 * with six small items. The expanded state lives in memory and starts
 * collapsed on every PlayerActivity open.
 */
public class StemListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_STEM = 0;
    private static final int TYPE_DRUMSET_HEADER = 1;

    Context context;
    PlayerActivity ParentActivity;
    private final boolean hasVocals2Sibling;

    /** Non-drum stems in original order. */
    private final java.util.List<StemListData> nonDrumStems = new java.util.ArrayList<>();
    /** Drum-part stems in order. */
    private final java.util.List<StemListData> drumStems = new java.util.ArrayList<>();
    /** At least one drum-part is present in the song (split drums active). */
    private final boolean hasDrumset;
    /** Expanded/collapsed state of the drumset header. */
    private boolean drumsetExpanded = false;
    /** While true, the slider bind starts at 0 (PlayerActivity animates to
     *  saved afterwards). Avoids the "saved → 0 → animated" flash on song load. */
    private boolean animateInitial = false;

    /** Set BEFORE the first layout pass. When true, sliders bind to 0. */
    public void setAnimateInitial(boolean b) { animateInitial = b; }
    public boolean isAnimateInitial()        { return animateInitial; }

    /** When true (3D mode active), sliders and buttons stay disabled with reduced
     *  alpha; engine values are driven by the stem's 3D position instead. */
    private boolean editingLocked = false;
    public void setEditingLocked(boolean locked) { editingLocked = locked; }

    // Alpha applied to the icon when the stem is muted (volume 0)
    public static final float ALPHA_DISABLED = 0.35f;
    public static final float ALPHA_ACTIVE   = 1.0f;
    // Compat: a legacy caller still uses disabledColor() to tint some text views
    private static final int COLOR_DISABLED = Color.argb(255, 100, 100, 100);

    public StemListAdapter(Context context, StemListData[] stemData, PlayerActivity ParentActivity){
        this.context        = context;
        this.ParentActivity = ParentActivity;
        boolean v2 = false;
        for (StemListData d : stemData) {
            if ("vocals2".equals(d.getStemType())) v2 = true;
            if (StemSpec.isDrumPart(d.getStemType())) drumStems.add(d);
            else nonDrumStems.add(d);
        }
        this.hasVocals2Sibling = v2;
        this.hasDrumset = !drumStems.isEmpty();
    }

    /** Returns the stem type at the visual position (or null when it is the header). */
    public String getStemType(int position) {
        StemListData d = stemAt(position);
        return d != null ? d.getStemType() : null;
    }

    /** Visual position of the stem in the RecyclerView (or RecyclerView.NO_POSITION when missing). */
    public int adapterPositionForStem(String stem) {
        for (int i = 0; i < nonDrumStems.size(); i++) {
            if (nonDrumStems.get(i).getStemType().equals(stem)) return i;
        }
        if (hasDrumset && drumsetExpanded) {
            int base = nonDrumStems.size() + 1;
            for (int i = 0; i < drumStems.size(); i++) {
                if (drumStems.get(i).getStemType().equals(stem)) return base + i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    /** Resolves the StemListData at the visual recycler position, or null when it is the header. */
    private StemListData stemAt(int pos) {
        if (pos < 0) return null;
        if (pos < nonDrumStems.size()) return nonDrumStems.get(pos);
        if (!hasDrumset) return null;
        int rel = pos - nonDrumStems.size();
        if (rel == 0) return null; // header
        int childIdx = rel - 1;
        if (childIdx >= drumStems.size()) return null;
        return drumStems.get(childIdx);
    }

    @Override
    public int getItemViewType(int position) {
        if (!hasDrumset) return TYPE_STEM;
        if (position == nonDrumStems.size()) return TYPE_DRUMSET_HEADER;
        return TYPE_STEM;
    }

    @Override
    public int getItemCount() {
        int n = nonDrumStems.size();
        if (hasDrumset) {
            n += 1; // header
            if (drumsetExpanded) n += drumStems.size();
        }
        return n;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        if (viewType == TYPE_DRUMSET_HEADER) {
            View v = inflater.inflate(R.layout.stem_drumset_header, parent, false);
            return new DrumsetHeaderViewHolder(v);
        }
        View v = inflater.inflate(R.layout.stem_control_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof DrumsetHeaderViewHolder) {
            bindDrumsetHeader((DrumsetHeaderViewHolder) holder);
            return;
        }
        bindStem((ViewHolder) holder, position);
    }

    private void bindDrumsetHeader(DrumsetHeaderViewHolder h) {
        h.iv_chevron.setImageResource(drumsetExpanded
                ? R.drawable.ic_expanded : R.drawable.ic_collapsed);
        h.itemView.setOnClickListener(v -> toggleDrumset());
    }

    private void toggleDrumset() {
        drumsetExpanded = !drumsetExpanded;
        int headerPos = nonDrumStems.size();
        notifyItemChanged(headerPos);
        if (drumsetExpanded) {
            notifyItemRangeInserted(headerPos + 1, drumStems.size());
        } else {
            notifyItemRangeRemoved(headerPos + 1, drumStems.size());
        }
    }

    private void bindStem(@NonNull ViewHolder holder, int position) {
        StemListData data = stemAt(position);
        if (data == null) return;
        final String stem = data.getStemType();

        holder.iv_stemIcon.setImageResource(StemSpec.iconResource(stem, hasVocals2Sibling));

        holder.sb_stemVol.setLabelFormatter(new LabelFormatter() {
            @NonNull @Override
            public String getFormattedValue(float value) { return String.valueOf((int) value); }
        });

        // Clear previous listeners of the recycled slider to avoid stacking handlers
        holder.sb_stemVol.clearOnChangeListeners();

        // Accent colors from the context (follows the player's runtime accent
        // when it adopts the song colors; the XML theme values otherwise).
        android.content.Context ctx = holder.itemView.getContext();
        holder.sb_stemVol.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(
                com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(ctx)));
        holder.sb_stemVol.setThumbTintList(android.content.res.ColorStateList.valueOf(
                com.jotapeveloper.fision.design.AccentColorManager.resolveAccentSecondary(ctx)));

        // Set the initial slider value from the saved setting + icon alpha.
        // During the post-load initial pass, start at 0 (PlayerActivity animates
        // to saved afterwards). Prevents the "saved → 0 → animated" flash.
        int savedVol = ParentActivity.getStemVolume(stem);
        holder.sb_stemVol.setValue(animateInitial ? 0f : savedVol);
        holder.iv_stemIcon.setAlpha(savedVol == 0 ? ALPHA_DISABLED : ALPHA_ACTIVE);

        // Slider changes → apply to engine + persist (only when fromUser)
        holder.sb_stemVol.addOnChangeListener((slider, value, fromUser) -> {
            ParentActivity.onStemVolumeChanged(stem, value, fromUser, holder.iv_stemIcon);
        });

        holder.iv_stemIcon.setOnClickListener(v ->
                ParentActivity.showStemActionMenu(v, stem, holder.sb_stemVol, holder.iv_stemIcon));

        holder.ib_restoreVol.setOnClickListener(v ->
                ParentActivity.onStemRestoreVolume(stem, holder.sb_stemVol, holder.iv_stemIcon));

        holder.ib_options.setOnClickListener(v ->
                ParentActivity.showOptionsBottomDialog(stem));
        // Options button in the accent while the stem has FX on.
        holder.ib_options.setImageTintList(ParentActivity.stemHasFx(stem)
                ? android.content.res.ColorStateList.valueOf(
                        com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(ctx))
                : null);

        // Visual + functional lock when 3D mode is active.
        boolean interactive = !editingLocked;
        holder.sb_stemVol.setEnabled(interactive);
        holder.ib_restoreVol.setEnabled(interactive);
        holder.ib_options.setEnabled(interactive);
        holder.iv_stemIcon.setEnabled(interactive);
        float rowAlpha = interactive ? 1f : 0.35f;
        holder.sb_stemVol.setAlpha(rowAlpha);
        holder.ib_restoreVol.setAlpha(rowAlpha);
        holder.ib_options.setAlpha(rowAlpha);

        // Visual indent for drum-parts (children of the drum kit header)
        boolean isDrumChild = StemSpec.isDrumPart(stem);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.iv_stemIcon.getLayoutParams();
        float density = context.getResources().getDisplayMetrics().density;
        lp.leftMargin = (int) ((isDrumChild ? 44f : 20f) * density);
        holder.iv_stemIcon.setLayoutParams(lp);
    }

    /** Legacy desaturated color — kept only for potential legacy callers. */
    public static int disabledColor() { return COLOR_DISABLED; }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ImageView   iv_stemIcon;
        Slider      sb_stemVol;
        ImageButton ib_restoreVol;
        ImageButton ib_options;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            iv_stemIcon    = itemView.findViewById(R.id.iv_stemIcon);
            sb_stemVol     = itemView.findViewById(R.id.sb_stemVol);
            ib_restoreVol  = itemView.findViewById(R.id.ib_restoreVol);
            ib_options     = itemView.findViewById(R.id.ib_options);
        }
    }

    public static class DrumsetHeaderViewHolder extends RecyclerView.ViewHolder {
        ImageView iv_drumsetIcon;
        ImageView iv_chevron;
        TextView  tv_label;

        public DrumsetHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            iv_drumsetIcon = itemView.findViewById(R.id.iv_drumsetIcon);
            iv_chevron     = itemView.findViewById(R.id.iv_drumsetChevron);
            tv_label       = itemView.findViewById(R.id.tv_drumsetLabel);
        }
    }
}
