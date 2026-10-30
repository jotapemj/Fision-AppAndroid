package com.jotapeveloper.fision;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

/** Shown when local mode is entered: where to get the demo song and how to
 *  import it as a song folder. */
public final class LocalDemoDialog {

    /** Demo song attached to the server release, already split and analysed. */
    private static final String DEMO_SONG_URL =
            "https://github.com/jotapemj/Fision-Server/releases/download/v2.1.0/fision-demo.zip";

    /** README section with the download link and the import steps. */
    private static final String DEMO_GUIDE_URL =
            "https://github.com/jotapemj/Fision-AppAndroid#try-it-without-a-server";

    private LocalDemoDialog() {}

    /** [after] runs once the dialog is gone. Shown after a short delay, so the
     *  dialog that led here clears its own blur first. */
    public static void show(Activity a, @Nullable Runnable after) {
        a.getWindow().getDecorView().postDelayed(() -> showNow(a, after), 180);
    }

    private static void showNow(Activity a, @Nullable Runnable after) {
        if (a.isFinishing() || a.isDestroyed()) {
            if (after != null) after.run();
            return;
        }
        View v = a.getLayoutInflater().inflate(R.layout.local_demo_dialog, null);
        TextView body = v.findViewById(R.id.tv_localDemoBody);
        body.setText(android.text.Html.fromHtml(a.getString(R.string.local_demo_body, DEMO_GUIDE_URL),
                android.text.Html.FROM_HTML_MODE_COMPACT));
        body.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        AlertDialog dlg = BlurDialogs.show(a, v);
        BlurDialogs.noKeyboard(dlg);
        dlg.setOnDismissListener(d -> {
            BlurDialogs.removeBlur(a);
            if (after != null) after.run();
        });
        v.findViewById(R.id.b_localDemoClose).setOnClickListener(x -> dlg.dismiss());
        v.findViewById(R.id.b_localDemoDownload).setOnClickListener(x -> {
            dlg.dismiss();
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DEMO_SONG_URL)));
            } catch (Exception ignored) {}
        });
    }
}
