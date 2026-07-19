package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;
import com.jotapeveloper.fision.cloud.AvatarManager;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** RecyclerView adapter for the redesigned Settings screen.
 *  Three view types share the item list: HEADER (account card),
 *  SECTION_TITLE, and CARD. Item clicks are dispatched by id through
 *  {@link OnItemClickListener}. */
public class SettingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnItemClickListener {
        void onSettingsItemClick(SettingsItem item);
    }

    private static final int VT_HEADER  = 0;
    private static final int VT_SECTION = 1;
    private static final int VT_CARD    = 2;

    private final List<SettingsItem> data;
    private final OnItemClickListener listener;

    public SettingsAdapter(List<SettingsItem> data, OnItemClickListener listener) {
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override public int getItemViewType(int position) {
        switch (data.get(position).type) {
            case HEADER:        return VT_HEADER;
            case SECTION_TITLE: return VT_SECTION;
            default:            return VT_CARD;
        }
    }

    @Override public long getItemId(int position) {
        return data.get(position).id.hashCode();
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VT_HEADER:
                return new HeaderVH(inf.inflate(R.layout.item_settings_header, parent, false));
            case VT_SECTION:
                return new SectionVH(inf.inflate(R.layout.item_settings_section, parent, false));
            default:
                return new CardVH(inf.inflate(R.layout.item_settings_card, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        SettingsItem item = data.get(position);
        if (holder instanceof HeaderVH)   ((HeaderVH) holder).bind(item, listener);
        else if (holder instanceof CardVH) ((CardVH) holder).bind(item, listener);
        else if (holder instanceof SectionVH) ((SectionVH) holder).bind(item);
    }

    @Override public int getItemCount() { return data.size(); }

    // ---------------- Header (account) ----------------
    static class HeaderVH extends RecyclerView.ViewHolder {
        final View root;
        final TextView tvInitial;
        final TextView tvUser;
        final TextView tvEmail;
        final ShapeableImageView ivAvatar;

        HeaderVH(@NonNull View v) {
            super(v);
            root = v;
            tvInitial = v.findViewById(R.id.tv_settings_initial);
            tvUser    = v.findViewById(R.id.tv_settings_userName);
            tvEmail   = v.findViewById(R.id.tv_settings_userEmail);
            ivAvatar  = v.findViewById(R.id.iv_settings_avatar);
        }

        void bind(SettingsItem item, OnItemClickListener l) {
            Context ctx = root.getContext();
            String user = item.user == null ? "" : item.user;
            tvUser.setText(user.isEmpty() ? ctx.getString(R.string.settings_header_local_title) : user);
            if (item.email == null || item.email.isEmpty()) {
                tvEmail.setText(ctx.getString(R.string.settings_header_local_email));
            } else {
                tvEmail.setText(item.email);
            }
            tvInitial.setText(user.isEmpty()
                    ? "?"
                    : user.substring(0, 1).toUpperCase(Locale.ROOT));

            AvatarManager am = AvatarManager.get(ctx);
            Bitmap bm = null;
            if (!user.isEmpty() && am.hasLocalAvatar(user)) {
                File f = am.getLocalFile(user);
                if (f != null) bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            }
            if (bm != null) {
                ivAvatar.setImageBitmap(bm);
                ivAvatar.setVisibility(View.VISIBLE);
                tvInitial.setVisibility(View.GONE);
            } else {
                ivAvatar.setVisibility(View.GONE);
                tvInitial.setVisibility(View.VISIBLE);
            }
            root.setOnClickListener(v -> { if (l != null) l.onSettingsItemClick(item); });
        }
    }

    // ---------------- Section title ----------------
    static class SectionVH extends RecyclerView.ViewHolder {
        final TextView tv;
        SectionVH(@NonNull View v) { super(v); tv = (TextView) v; }
        void bind(SettingsItem item) { tv.setText(item.title); }
    }

    // ---------------- Card ----------------
    static class CardVH extends RecyclerView.ViewHolder {
        final View root;
        final View iconHolder;
        final ImageView ivIcon;
        final TextView tvTitle;
        final TextView tvSubtitle;
        final ImageView ivChevron;

        CardVH(@NonNull View v) {
            super(v);
            root       = v;
            iconHolder = v.findViewById(R.id.fl_settings_cardIconWrap);
            ivIcon     = v.findViewById(R.id.iv_settings_cardIcon);
            tvTitle    = v.findViewById(R.id.tv_settings_cardTitle);
            tvSubtitle = v.findViewById(R.id.tv_settings_cardSubtitle);
            ivChevron  = v.findViewById(R.id.iv_settings_cardChevron);
        }

        void bind(SettingsItem item, OnItemClickListener l) {
            Context ctx = root.getContext();
            ivIcon.setImageResource(item.iconRes);
            int tint = item.iconTintOverride != 0
                    ? ContextCompat.getColor(ctx, item.iconTintOverride)
                    : ContextCompat.getColor(ctx, R.color.colorPrimary);
            ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
            // Icon holder background matches the accent color at ~15% alpha,
            // so amber or red accents keep a consistent look.
            int holderTint = (tint & 0x00FFFFFF) | 0x26000000;
            iconHolder.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(holderTint));
            tvTitle.setText(item.title);
            if (item.subtitle == null || item.subtitle.length() == 0) {
                tvSubtitle.setVisibility(View.GONE);
            } else {
                tvSubtitle.setVisibility(View.VISIBLE);
                tvSubtitle.setText(item.subtitle);
            }
            if (item.externalLink) {
                ivChevron.setImageResource(R.drawable.ic_open_external);
            } else {
                ivChevron.setImageResource(R.drawable.ic_chevron_right);
            }
            ivChevron.setImageTintList(
                    android.content.res.ColorStateList.valueOf(0xFF5A5A5A));
            ivChevron.setVisibility(item.showChevron ? View.VISIBLE : View.GONE);
            root.setOnClickListener(v -> { if (l != null) l.onSettingsItemClick(item); });
        }
    }
}
