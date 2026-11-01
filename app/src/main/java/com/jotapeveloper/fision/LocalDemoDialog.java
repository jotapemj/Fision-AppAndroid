package com.jotapeveloper.fision;

import android.app.Activity;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

/** Asks before downloading the demo song from GitHub and importing it ({@link DemoSong}). */
public final class LocalDemoDialog {

    private LocalDemoDialog() {}

    /** Shown after a short delay, so a dialog or sheet that led here clears
     *  its own blur first. Download starts {@link DemoSong#start}. */
    public static void show(Activity a) {
        a.getWindow().getDecorView().postDelayed(() -> showNow(a), 180);
    }

    private static void showNow(Activity a) {
        if (a.isFinishing() || a.isDestroyed()) return;
        View v = a.getLayoutInflater().inflate(R.layout.local_demo_dialog, null);
        int mb = (int) Math.round(DemoSong.ZIP_BYTES / 1_000_000.0);
        ((android.widget.TextView) v.findViewById(R.id.tv_localDemoBody))
                .setText(a.getString(R.string.demo_dialog_body, mb));
        AlertDialog dlg = BlurDialogs.show(a, v);
        BlurDialogs.noKeyboard(dlg);
        v.findViewById(R.id.b_localDemoClose).setOnClickListener(x -> dlg.dismiss());
        v.findViewById(R.id.b_localDemoDownload).setOnClickListener(x -> {
            dlg.dismiss();
            DemoSong.start(a.getApplicationContext());
        });
    }
}
