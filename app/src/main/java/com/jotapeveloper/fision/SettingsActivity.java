package com.jotapeveloper.fision;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.jotapeveloper.fision.cloud.ApiClient;
import com.jotapeveloper.fision.cloud.CloudRepository;
import com.jotapeveloper.fision.cloud.FisionApi;
import com.jotapeveloper.fision.cloud.ServerPrefs;

import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new SettingsFragment())
                    .commit();
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            setupLanguage();
            setupServer();
            setupAbout();
        }

        // ---- About: dialog with name, version, developer and libraries ----
        private void setupAbout() {
            Preference aboutPref = findPreference("about");
            if (aboutPref == null) return;
            aboutPref.setOnPreferenceClickListener(p -> {
                showAboutDialog();
                return true;
            });
        }

        private void showAboutDialog() {
            Context ctx = requireContext();
            View dlgView = getLayoutInflater().inflate(R.layout.about_dialog, null);
            android.widget.TextView tvVersion = dlgView.findViewById(R.id.tv_about_version);
            android.widget.TextView tvLibs    = dlgView.findViewById(R.id.tv_about_libraries);
            com.google.android.material.button.MaterialButton bClose =
                    dlgView.findViewById(R.id.b_about_close);
            // Dynamic version from PackageInfo (versionName from build.gradle)
            String vName = "?";
            try {
                vName = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {}
            tvVersion.setText(getString(R.string.about_version_fmt, vName));
            // HTML body (<b>) → parsed into spannable to keep boldface
            tvLibs.setText(androidx.core.text.HtmlCompat.fromHtml(
                    getString(R.string.about_libraries_body),
                    androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT));

            AlertDialog dlg = new AlertDialog.Builder(ctx, R.style.Theme_Fision_BlurDialog)
                    .setView(dlgView)
                    .setCancelable(true)
                    .create();
            android.view.Window win = dlg.getWindow();
            if (win != null) win.setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

            applyActivityBlur();
            dlg.setOnDismissListener(d -> removeActivityBlur());
            bClose.setOnClickListener(v -> dlg.dismiss());
            dlg.show();
        }

        private void setupLanguage() {
            ListPreference languagePreference = findPreference("language");
            if (languagePreference == null) return;
            CharSequence[] entries = languagePreference.getEntries();

            languagePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String newLanguage = newValue.toString();
                int index = languagePreference.findIndexOfValue(newLanguage);
                if (index >= 0) preference.setSummary(entries[index]);
                updateLocale(newLanguage);
                // Android 13+ requires an explicit target package for broadcasts
                // that reach receivers registered with RECEIVER_NOT_EXPORTED.
                // Without setPackage, MainActivity's receiver would never fire
                // and its UI kept the previous locale until the app restarted.
                Intent intent = new Intent("com.jotapeveloper.fision.LANGUAGE_CHANGED");
                intent.setPackage(requireActivity().getPackageName());
                requireActivity().sendBroadcast(intent);
                requireActivity().recreate();
                return true;
            });
            int currentIndex = languagePreference.findIndexOfValue(languagePreference.getValue());
            if (currentIndex >= 0) languagePreference.setSummary(entries[currentIndex]);
        }

        // ---- server: URL + token gated by biometrics ----------------------
        private void setupServer() {
            Context ctx = requireContext();
            ServerPrefs prefs = ServerPrefs.get(ctx);

            Preference urlPref = findPreference("server_url");
            if (urlPref != null) {
                urlPref.setSummary(prefs.getUrl().isEmpty()
                        ? getString(R.string.server_url_summary) : prefs.getUrl());
                urlPref.setOnPreferenceClickListener(p -> {
                    showTextInputDialog(
                            getString(R.string.server_url_title),
                            getString(R.string.server_url_summary),
                            prefs.getUrl(),
                            false,
                            value -> {
                                String newUrl = value == null ? "" : value.trim();
                                prefs.setUrl(newUrl);
                                p.setSummary(newUrl.isEmpty()
                                        ? getString(R.string.server_url_summary) : newUrl);
                                ApiClient.get(ctx).notifyConfigChanged();
                                CloudRepository.get(ctx).notifyConfigChanged();
                            });
                    return true;
                });
            }

            Preference tokenPref = findPreference("server_token");
            if (tokenPref != null) {
                tokenPref.setSummary(prefs.hasToken()
                        ? getString(R.string.server_token_set)
                        : getString(R.string.server_token_unset));
                tokenPref.setOnPreferenceClickListener(p -> {
                    promptBiometricThen(() -> showTokenDialog(prefs, tokenPref));
                    return true;
                });
            }

            Preference testPref = findPreference("server_test");
            if (testPref != null) {
                testPref.setOnPreferenceClickListener(p -> {
                    runConnectionTest(testPref);
                    return true;
                });
            }
        }

        private void runConnectionTest(Preference pref) {
            Context ctx = requireContext();
            ServerPrefs sp = ServerPrefs.get(ctx);
            if (!sp.hasUrl()) {
                Toast.makeText(ctx, R.string.server_test_no_url, Toast.LENGTH_LONG).show();
                return;
            }
            // Force a rebuild in case the URL was just changed in the same viewport
            ApiClient.get(ctx).notifyConfigChanged();
            FisionApi api = ApiClient.get(ctx).api();
            if (api == null) {
                Toast.makeText(ctx, R.string.server_test_fail, Toast.LENGTH_LONG).show();
                return;
            }

            // Inflate dialog and show spinner + "Testing server..." text
            View dlgView = getLayoutInflater().inflate(R.layout.ping_dialog, null);
            android.widget.ProgressBar spinner = dlgView.findViewById(R.id.pb_pingSpinner);
            android.widget.ImageView ivResult = dlgView.findViewById(R.id.iv_pingResult);
            android.widget.TextView tvText = dlgView.findViewById(R.id.tv_pingText);
            com.google.android.material.button.MaterialButton bClose = dlgView.findViewById(R.id.b_pingClose);

            AlertDialog dlg = new AlertDialog.Builder(ctx, R.style.Theme_Fision_BlurDialog)
                    .setView(dlgView)
                    .setCancelable(false)
                    .create();
            android.view.Window win = dlg.getWindow();
            if (win != null) win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

            pref.setEnabled(false);
            applyActivityBlur();
            dlg.setOnDismissListener(d -> { pref.setEnabled(true); removeActivityBlur(); });
            dlg.show();

            // Mutable state shared with the timeout and the callback
            final Call<Map<String, Object>> call = api.health();
            final boolean[] finished = { false };
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

            Runnable showFail = () -> {
                spinner.setVisibility(View.GONE);
                ivResult.setImageResource(R.drawable.ic_cross_circle);
                ivResult.setImageTintList(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.cloud_offline)));
                ivResult.setVisibility(View.VISIBLE);
                tvText.setText(R.string.server_test_fail);
                bClose.setVisibility(View.VISIBLE);
            };
            Runnable showOk = () -> {
                spinner.setVisibility(View.GONE);
                ivResult.setImageResource(R.drawable.ic_check_circle);
                ivResult.setImageTintList(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.cloud_online)));
                ivResult.setVisibility(View.VISIBLE);
                tvText.setText(R.string.server_test_ok);
                bClose.setVisibility(View.VISIBLE);
            };

            // 5s timeout: on no response, cancel AND show fail
            handler.postDelayed(() -> {
                if (finished[0]) return;
                finished[0] = true;
                try { call.cancel(); } catch (Throwable ignored) {}
                tvText.setText(R.string.server_test_timeout);
                showFail.run();
            }, 5000);

            bClose.setOnClickListener(v -> dlg.dismiss());

            call.enqueue(new Callback<Map<String, Object>>() {
                @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                    if (finished[0]) return;
                    finished[0] = true;
                    handler.removeCallbacksAndMessages(null);
                    if (r.isSuccessful()) {
                        showOk.run();
                        CloudRepository.get(ctx).notifyConfigChanged();
                    } else {
                        tvText.setText(getString(R.string.server_test_fail) + " (HTTP " + r.code() + ")");
                        showFail.run();
                    }
                }
                @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                    if (finished[0]) return;
                    finished[0] = true;
                    handler.removeCallbacksAndMessages(null);
                    showFail.run();
                }
            });
        }

        private void promptBiometricThen(Runnable onSuccess) {
            FragmentActivity activity = requireActivity();
            BiometricManager bm = BiometricManager.from(activity);
            int auth = BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
            int can = bm.canAuthenticate(auth);

            if (can != BiometricManager.BIOMETRIC_SUCCESS) {
                // Device without biometrics nor a configured PIN: let the user through anyway
                // (visible warning) — the token was already in EncryptedSharedPreferences.
                Toast.makeText(activity, R.string.server_auth_unavailable, Toast.LENGTH_LONG).show();
                onSuccess.run();
                return;
            }

            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.server_auth_required))
                    .setSubtitle(getString(R.string.server_auth_subtitle))
                    .setAllowedAuthenticators(auth)
                    .build();

            BiometricPrompt prompt = new BiometricPrompt(activity,
                    ContextCompat.getMainExecutor(activity),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            onSuccess.run();
                        }
                    });
            prompt.authenticate(info);
        }

        private void showTokenDialog(ServerPrefs prefs, Preference pref) {
            Context ctx = requireContext();
            showTextInputDialog(
                    getString(R.string.server_token_dialog_title),
                    getString(R.string.server_token_hint),
                    prefs.getToken(),
                    true,
                    value -> {
                        prefs.setToken(value == null ? "" : value.trim());
                        pref.setSummary(prefs.hasToken()
                                ? getString(R.string.server_token_set)
                                : getString(R.string.server_token_unset));
                        ApiClient.get(ctx).notifyConfigChanged();
                        CloudRepository.get(ctx).notifyConfigChanged();
                    });
        }

        /** Generic input dialog styled like the app (background, colorPrimary buttons). */
        private interface OnTextInputSaved { void onSave(String value); }

        private void showTextInputDialog(String title, String hint, String initialValue,
                                          boolean password, OnTextInputSaved cb) {
            Context ctx = requireContext();
            View dlgView = getLayoutInflater().inflate(R.layout.text_input_dialog, null);
            android.widget.TextView tvTitle = dlgView.findViewById(R.id.tv_textInputTitle);
            com.google.android.material.textfield.TextInputLayout til = dlgView.findViewById(R.id.til_textInput);
            com.google.android.material.textfield.TextInputEditText et = dlgView.findViewById(R.id.et_textInput);
            com.google.android.material.button.MaterialButton bCancel = dlgView.findViewById(R.id.b_textInputCancel);
            com.google.android.material.button.MaterialButton bSave = dlgView.findViewById(R.id.b_textInputSave);

            tvTitle.setText(title);
            til.setHint(hint);
            if (initialValue != null) et.setText(initialValue);
            int input = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            if (password) input |= InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
            else input |= InputType.TYPE_TEXT_VARIATION_URI;
            et.setInputType(input);

            AlertDialog dlg = new AlertDialog.Builder(ctx, R.style.Theme_Fision_BlurDialog)
                    .setView(dlgView)
                    .setCancelable(true)
                    .create();
            android.view.Window win = dlg.getWindow();
            if (win != null) win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

            applyActivityBlur();
            dlg.setOnDismissListener(d -> removeActivityBlur());
            bCancel.setOnClickListener(v -> dlg.dismiss());
            bSave.setOnClickListener(v -> {
                cb.onSave(et.getText() == null ? "" : et.getText().toString());
                dlg.dismiss();
            });
            dlg.show();
        }

        /** Blurs the host activity decorView (Android 12+). */
        private void applyActivityBlur() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                requireActivity().getWindow().getDecorView().setRenderEffect(
                        android.graphics.RenderEffect.createBlurEffect(
                                40f, 40f, android.graphics.Shader.TileMode.CLAMP));
            }
        }

        private void removeActivityBlur() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                requireActivity().getWindow().getDecorView().setRenderEffect(null);
            }
        }

        private void updateLocale(String languageCode) {
            Locale locale = new Locale(languageCode);
            Locale.setDefault(locale);
            Configuration config = new Configuration();
            config.setLocale(locale);
            requireActivity().getResources().updateConfiguration(config,
                    requireActivity().getResources().getDisplayMetrics());
        }
    }

    public void closeSettings(View view) { onBackPressed(); }
}
