package com.jotapeveloper.fision;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AlertDialog;

/** Blur dialog recipe (blur on the host decorView, transparent window,
 *  delete_alert_dialog layout). {@link #applyBlur} is the one blur of every
 *  dialog in the app. */
public final class BlurDialogs {

    private BlurDialogs() {}

    /** Blurs the host behind a dialog (Android 12+). Running drawable
     *  animations (the ripple of the tapped row) end first: one still running
     *  under the blur redraws only its own rect, and the blur recomputed in
     *  that rect alone leaves a hard-edged box. */
    public static void applyBlur(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            View decor = a.getWindow().getDecorView();
            decor.jumpDrawablesToCurrentState();
            decor.setRenderEffect(
                    RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        }
    }

    public static void removeBlur(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            a.getWindow().getDecorView().setRenderEffect(null);
        }
    }

    /** Shows a blurred dialog with the given custom view. */
    public static AlertDialog show(Activity a, View content) {
        return show(a, content, 0f);
    }

    /** Same, with the window [widthFraction] of the screen wide (0 = wrap).
     *  A fixed width is required when the content holds a RecyclerView: with
     *  a wrapping window the rows are measured before the final width and
     *  keep a narrow layout. */
    public static AlertDialog show(Activity a, View content, float widthFraction) {
        AlertDialog dlg = new AlertDialog.Builder(a, R.style.Theme_Fision_BlurDialog)
                .setView(content)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        applyBlur(a);
        dlg.setOnDismissListener(d -> removeBlur(a));
        dlg.show();
        if (widthFraction > 0f && dlg.getWindow() != null) {
            int w = (int) (a.getResources().getDisplayMetrics().widthPixels * widthFraction);
            dlg.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dlg;
    }

    /** Yes/no confirmation; [onYes] runs after the dialog is dismissed. */
    public static void confirm(Activity a, @DrawableRes int icon, CharSequence message,
                               CharSequence yes, CharSequence no, Runnable onYes) {
        View v = LayoutInflater.from(a).inflate(R.layout.delete_alert_dialog, null);
        ImageView iv = v.findViewById(R.id.iv_alertDialog_ic);
        TextView tv = v.findViewById(R.id.tv_alertDialog_text);
        Button bYes = v.findViewById(R.id.b_yes);
        Button bNo = v.findViewById(R.id.b_no);
        iv.setImageResource(icon);
        tv.setText(message);
        bYes.setText(yes);
        bNo.setText(no);
        AlertDialog dlg = show(a, v);
        bNo.setOnClickListener(x -> dlg.dismiss());
        bYes.setOnClickListener(x -> {
            dlg.dismiss();
            onYes.run();
        });
    }
}
