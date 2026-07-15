package com.jotapeveloper.fision;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.jotapeveloper.fision.cloud.AuthManager;
import com.jotapeveloper.fision.cloud.ServerPrefs;

/**
 * First-run and Settings login dialog. Blurs the host activity decorView, matches
 * the Fision BlurDialog style. Offers a bold text-button at the bottom to skip
 * into local-only mode.
 */
public class LoginDialog {

    public interface Callback {
        /** Called on successful login. */
        default void onLoggedIn(String username) {}
        /** Called when the user picks "Continue in local mode". */
        default void onLocalMode() {}
        /** Called if the user dismisses the dialog without either. */
        default void onCancelled() {}
    }

    private LoginDialog() {}

    public static void show(Activity host, boolean allowLocalMode, Callback cb) {
        View v = host.getLayoutInflater().inflate(R.layout.login_dialog, null);
        TextInputEditText etUrl  = v.findViewById(R.id.et_loginUrl);
        TextInputEditText etUser = v.findViewById(R.id.et_loginUser);
        TextInputEditText etPass = v.findViewById(R.id.et_loginPass);
        android.widget.TextView tvError = v.findViewById(R.id.tv_loginError);
        android.widget.ProgressBar spinner = v.findViewById(R.id.pb_loginSpinner);
        MaterialButton bSubmit = v.findViewById(R.id.b_loginSubmit);
        MaterialButton bCancel = v.findViewById(R.id.b_loginCancel);
        android.widget.TextView tvLocal = v.findViewById(R.id.tv_loginLocalMode);

        ServerPrefs prefs = ServerPrefs.get(host);
        String prevUrl = prefs.getUrl();
        if (!prevUrl.isEmpty()) etUrl.setText(prevUrl);
        String prevUser = prefs.getUsername();
        if (!prevUser.isEmpty()) etUser.setText(prevUser);

        AlertDialog dlg = new AlertDialog.Builder(host, R.style.Theme_Fision_BlurDialog)
                .setView(v)
                .setCancelable(allowLocalMode)
                .create();
        Window win = dlg.getWindow();
        if (win != null) win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        applyActivityBlur(host);
        // Only signal onCancelled when the user actually dismisses without acting
        final boolean[] handled = { false };
        dlg.setOnDismissListener(d -> {
            removeActivityBlur(host);
            if (!handled[0]) cb.onCancelled();
        });

        if (!allowLocalMode) tvLocal.setVisibility(View.GONE);

        bCancel.setText(allowLocalMode ? R.string.NO : R.string.NO);
        bCancel.setOnClickListener(x -> dlg.dismiss());

        tvLocal.setOnClickListener(x -> {
            handled[0] = true;
            AuthManager.get(host).enterLocalMode();
            dlg.dismiss();
            cb.onLocalMode();
        });

        bSubmit.setOnClickListener(x -> {
            String url  = etUrl.getText()  != null ? etUrl.getText().toString()  : "";
            String user = etUser.getText() != null ? etUser.getText().toString() : "";
            String pass = etPass.getText() != null ? etPass.getText().toString() : "";
            tvError.setVisibility(View.GONE);
            bSubmit.setEnabled(false);
            bCancel.setEnabled(false);
            tvLocal.setEnabled(false);
            spinner.setVisibility(View.VISIBLE);
            AuthManager.get(host).login(url, user, pass, new AuthManager.LoginCallback() {
                @Override public void onSuccess(String username) {
                    handled[0] = true;
                    spinner.setVisibility(View.GONE);
                    dlg.dismiss();
                    cb.onLoggedIn(username);
                }
                @Override public void onFailure(int code, String reason) {
                    spinner.setVisibility(View.GONE);
                    bSubmit.setEnabled(true);
                    bCancel.setEnabled(true);
                    tvLocal.setEnabled(true);
                    tvError.setText(errorForReason(host, reason));
                    tvError.setVisibility(View.VISIBLE);
                }
            });
        });

        dlg.show();
    }

    private static String errorForReason(Activity host, String reason) {
        switch (reason) {
            case "empty_fields":    return host.getString(R.string.login_err_empty_fields);
            case "bad_credentials": return host.getString(R.string.login_err_bad_credentials);
            case "network_error":   return host.getString(R.string.login_err_network);
            case "bad_url":         return host.getString(R.string.login_err_bad_url);
            default:                return host.getString(R.string.login_err_server);
        }
    }

    private static void applyActivityBlur(Activity host) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            host.getWindow().getDecorView().setRenderEffect(
                    RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        }
    }

    private static void removeActivityBlur(Activity host) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            host.getWindow().getDecorView().setRenderEffect(null);
        }
    }
}
