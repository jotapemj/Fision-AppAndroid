package com.jotapeveloper.fision;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** Bottom sheet triggered by the overflow dots on the lyrics view.
 *  Exposes the karaoke toggle (available only when the loaded lyrics carry
 *  per-word timestamps) and a "Reprocess" entry. */
public class LyricsOptionsBottomSheet {

    public interface Callback {
        void onReprocess();
        void onKaraokeToggled(boolean enabled);
    }

    public static void show(Context ctx, boolean karaokeEnabled,
                            boolean karaokeAvailable, Callback cb) {
        BottomSheetDialog dlg = new BottomSheetDialog(ctx, R.style.TransparentBottomSheetDialog);
        View content = View.inflate(ctx, R.layout.lyrics_options_sheet, null);

        LinearLayout rowKaraoke = content.findViewById(R.id.row_lyricsKaraoke);
        SwitchMaterial swKaraoke = content.findViewById(R.id.sw_lyricsKaraoke);
        TextView tvSubtitle = content.findViewById(R.id.tv_lyricsKaraokeSubtitle);

        // Suppress the callback while syncing the initial state so the row
        // does not fire a toggled event just from opening the sheet.
        swKaraoke.setOnCheckedChangeListener(null);
        swKaraoke.setChecked(karaokeEnabled && karaokeAvailable);
        rowKaraoke.setEnabled(karaokeAvailable);
        swKaraoke.setEnabled(karaokeAvailable);
        rowKaraoke.setAlpha(karaokeAvailable ? 1f : 0.5f);
        tvSubtitle.setVisibility(karaokeAvailable ? View.GONE : View.VISIBLE);

        swKaraoke.setOnCheckedChangeListener((btn, checked) -> {
            if (cb != null) cb.onKaraokeToggled(checked);
        });
        rowKaraoke.setOnClickListener(v -> {
            if (!karaokeAvailable) return;
            swKaraoke.toggle();
        });

        LinearLayout rowReprocess = content.findViewById(R.id.row_lyricsReprocess);
        rowReprocess.setOnClickListener(v -> {
            dlg.dismiss();
            if (cb != null) cb.onReprocess();
        });

        dlg.setContentView(content);
        dlg.show();
    }
}
