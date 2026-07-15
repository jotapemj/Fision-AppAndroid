package com.jotapeveloper.fision;

import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.jotapeveloper.fision.cloud.AuthManager;

/**
 * Change-password dialog gated by biometrics. If the device has no biometrics
 * nor a device credential set, the dialog is shown anyway (a warning is toasted).
 */
public class ChangePasswordDialog {

    public interface Callback {
        default void onChanged() {}
        default void onCancelled() {}
    }

    private ChangePasswordDialog() {}

    public static void show(FragmentActivity host, Callback cb) {
        BiometricManager bm = BiometricManager.from(host);
        int auth = BiometricManager.Authenticators.BIOMETRIC_WEAK
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        int can = bm.canAuthenticate(auth);
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(host, R.string.server_auth_unavailable, Toast.LENGTH_LONG).show();
            showDialog(host, cb);
            return;
        }
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(host.getString(R.string.change_password_biometric_title))
                .setSubtitle(host.getString(R.string.change_password_biometric_sub))
                .setAllowedAuthenticators(auth)
                .build();
        BiometricPrompt prompt = new BiometricPrompt(host,
                ContextCompat.getMainExecutor(host),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        showDialog(host, cb);
                    }
                    @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                        cb.onCancelled();
                    }
                });
        prompt.authenticate(info);
    }

    private static void showDialog(FragmentActivity host, Callback cb) {
        View v = host.getLayoutInflater().inflate(R.layout.change_password_dialog, null);
        TextInputEditText etOld     = v.findViewById(R.id.et_cpOld);
        TextInputEditText etNew     = v.findViewById(R.id.et_cpNew);
        TextInputEditText etConfirm = v.findViewById(R.id.et_cpConfirm);
        android.widget.TextView tvError = v.findViewById(R.id.tv_cpError);
        android.widget.ProgressBar spinner = v.findViewById(R.id.pb_cpSpinner);
        MaterialButton bSubmit = v.findViewById(R.id.b_cpSubmit);
        MaterialButton bCancel = v.findViewById(R.id.b_cpCancel);

        AlertDialog dlg = new AlertDialog.Builder(host, R.style.Theme_Fision_BlurDialog)
                .setView(v)
                .setCancelable(true)
                .create();
        Window win = dlg.getWindow();
        if (win != null) win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        applyBlur(host);
        final boolean[] handled = { false };
        dlg.setOnDismissListener(d -> {
            removeBlur(host);
            if (!handled[0]) cb.onCancelled();
        });

        bCancel.setOnClickListener(x -> dlg.dismiss());
        bSubmit.setOnClickListener(x -> {
            String oldP = etOld.getText()     != null ? etOld.getText().toString()     : "";
            String newP = etNew.getText()     != null ? etNew.getText().toString()     : "";
            String conf = etConfirm.getText() != null ? etConfirm.getText().toString() : "";
            tvError.setVisibility(View.GONE);
            if (oldP.isEmpty() || newP.isEmpty() || conf.isEmpty()) {
                tvError.setText(R.string.login_err_empty_fields);
                tvError.setVisibility(View.VISIBLE);
                return;
            }
            if (!newP.equals(conf)) {
                tvError.setText(R.string.change_password_err_mismatch);
                tvError.setVisibility(View.VISIBLE);
                return;
            }
            bSubmit.setEnabled(false);
            bCancel.setEnabled(false);
            spinner.setVisibility(View.VISIBLE);
            AuthManager.get(host).changePassword(oldP, newP, new AuthManager.ChangePasswordCallback() {
                @Override public void onSuccess() {
                    handled[0] = true;
                    spinner.setVisibility(View.GONE);
                    Toast.makeText(host, R.string.change_password_ok, Toast.LENGTH_SHORT).show();
                    dlg.dismiss();
                    cb.onChanged();
                }
                @Override public void onFailure(int code, String reason) {
                    spinner.setVisibility(View.GONE);
                    bSubmit.setEnabled(true);
                    bCancel.setEnabled(true);
                    tvError.setText(errorForReason(host, reason));
                    tvError.setVisibility(View.VISIBLE);
                }
            });
        });
        dlg.show();
    }

    private static String errorForReason(FragmentActivity host, String reason) {
        switch (reason) {
            case "bad_old_password": return host.getString(R.string.change_password_err_bad_old);
            case "bad_new_password": return host.getString(R.string.change_password_err_bad_new);
            case "network_error":    return host.getString(R.string.login_err_network);
            case "empty_fields":     return host.getString(R.string.login_err_empty_fields);
            default:                 return host.getString(R.string.login_err_server);
        }
    }

    private static void applyBlur(FragmentActivity host) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            host.getWindow().getDecorView().setRenderEffect(
                    RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        }
    }

    private static void removeBlur(FragmentActivity host) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            host.getWindow().getDecorView().setRenderEffect(null);
        }
    }
}
