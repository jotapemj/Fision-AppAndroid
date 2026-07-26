package com.jotapeveloper.fision;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Bottom sheet triggered by the overflow dots on the lyrics view.
 *  Currently exposes a single "Reprocess" entry; more actions can be
 *  appended later without changing the caller signature. */
public class LyricsOptionsBottomSheet {

    public interface Callback { void onReprocess(); }

    public static void show(Context ctx, Callback cb) {
        BottomSheetDialog dlg = new BottomSheetDialog(ctx, R.style.TransparentBottomSheetDialog);
        View content = View.inflate(ctx, R.layout.lyrics_options_sheet, null);
        LinearLayout row = content.findViewById(R.id.row_lyricsReprocess);
        row.setOnClickListener(v -> {
            dlg.dismiss();
            if (cb != null) cb.onReprocess();
        });
        dlg.setContentView(content);
        dlg.show();
    }
}
