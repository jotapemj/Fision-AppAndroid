package com.jotapeveloper.fision;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Vertical picker used inside the "Lyrics language" dialog. Highlights the
 *  currently selected code with a check on the right. */
public class LyricsLanguageAdapter extends RecyclerView.Adapter<LyricsLanguageAdapter.VH> {

    public interface OnPick { void onPicked(LyricsLanguage lang); }

    private String selectedCode;   // null = Auto
    private final OnPick onPick;

    public LyricsLanguageAdapter(String selectedCode, OnPick onPick) {
        this.selectedCode = selectedCode;
        this.onPick       = onPick;
    }

    public void setSelected(String code) {
        String prev = selectedCode;
        selectedCode = code;
        for (int i = 0; i < LyricsLanguage.LANGUAGES.length; i++) {
            String c = LyricsLanguage.LANGUAGES[i].code;
            if (equal(c, prev) || equal(c, code)) notifyItemChanged(i);
        }
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.lyrics_language_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LyricsLanguage lang = LyricsLanguage.LANGUAGES[position];
        h.flag.setImageResource(lang.flagRes);
        if (lang.isAuto()) {
            int pad = (int) (4 * h.itemView.getResources().getDisplayMetrics().density);
            h.flag.setPadding(pad, pad, pad, pad);
            h.flag.setScaleType(ScaleType.FIT_CENTER);
        } else {
            h.flag.setPadding(0, 0, 0, 0);
            h.flag.setScaleType(ScaleType.FIT_XY);
        }
        h.label.setText(lang.labelRes);
        h.check.setVisibility(equal(lang.code, selectedCode) ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(v -> {
            if (onPick != null) onPick.onPicked(lang);
        });
    }

    @Override public int getItemCount() { return LyricsLanguage.LANGUAGES.length; }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView flag;
        final TextView  label;
        final ImageView check;
        VH(@NonNull View v) {
            super(v);
            flag  = v.findViewById(R.id.iv_langFlag);
            label = v.findViewById(R.id.tv_langLabel);
            check = v.findViewById(R.id.iv_langCheck);
        }
    }
}
