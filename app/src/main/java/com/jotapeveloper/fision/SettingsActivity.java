package com.jotapeveloper.fision;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.jotapeveloper.fision.cloud.ApiClient;
import com.jotapeveloper.fision.cloud.AuthManager;
import com.jotapeveloper.fision.cloud.AvatarManager;
import com.jotapeveloper.fision.cloud.CloudRepository;
import com.jotapeveloper.fision.cloud.FisionApi;
import com.jotapeveloper.fision.cloud.ServerInfoManager;
import com.jotapeveloper.fision.cloud.ServerPrefs;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

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

        // Content-picker for the avatar. Registered at fragment creation time
        // because ActivityResultLauncher must be wired before STARTED.
        private ActivityResultLauncher<String> avatarPicker;
        private Preference accountPrefRef;
        private Preference gpuPrefRef;
        private final ServerInfoManager.Listener serverInfoListener = this::refreshGpuSummary;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            avatarPicker = registerForActivityResult(
                    new ActivityResultContracts.GetContent(), uri -> {
                        if (uri == null) return;
                        Intent i = new Intent(requireContext(), CropAvatarActivity.class);
                        i.putExtra(CropAvatarActivity.EXTRA_SOURCE_URI, uri);
                        startActivity(i);
                    });
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            setupLanguage();
            setupAccount();
            setupGpu();
            setupServerTest();
            setupAbout();
        }

        @Override
        public void onResume() {
            super.onResume();
            ServerInfoManager.get(requireContext()).addListener(serverInfoListener);
            refreshGpuSummary();
        }

        @Override
        public void onPause() {
            super.onPause();
            ServerInfoManager.get(requireContext()).removeListener(serverInfoListener);
        }

        // ---- GPU status row: reflects the cached /server/info response ---
        private void setupGpu() {
            gpuPrefRef = findPreference("server_gpu");
            if (gpuPrefRef == null) return;
            gpuPrefRef.setOnPreferenceClickListener(p -> {
                if (AuthManager.get(requireContext()).isLoggedIn()) {
                    ServerInfoManager.get(requireContext()).refresh();
                }
                return true;
            });
            refreshGpuSummary();
        }

        /** Icon + summary reflect the cached ServerInfo. Called on resume, on
         *  every listener notification and on the manual refresh tap. */
        private void refreshGpuSummary() {
            if (gpuPrefRef == null) return;
            Context ctx = requireContext();
            ServerInfoManager mgr = ServerInfoManager.get(ctx);
            if (!AuthManager.get(ctx).isLoggedIn() || !mgr.isLoaded()) {
                gpuPrefRef.setIcon(null);
                gpuPrefRef.setSummary(getString(R.string.server_gpu_unknown));
                return;
            }
            if (mgr.hasGpu()) {
                gpuPrefRef.setIcon(R.drawable.ic_check_circle);
                gpuPrefRef.getIcon().setTint(
                        ContextCompat.getColor(ctx, R.color.colorPrimary));
                int vramGb = Math.max(0, mgr.getVramMb() / 1024);
                String name = mgr.getGpuName();
                if (name == null || name.isEmpty()) name = getString(R.string.server_gpu_ok_short);
                gpuPrefRef.setSummary(getString(R.string.server_gpu_ok_fmt, name, vramGb));
            } else {
                gpuPrefRef.setIcon(R.drawable.ic_cross_circle);
                gpuPrefRef.getIcon().setTint(
                        ContextCompat.getColor(ctx, R.color.cloud_offline));
                gpuPrefRef.setSummary(getString(R.string.server_gpu_missing));
            }
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
            String vName = "?";
            try {
                vName = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Throwable ignored) {}
            tvVersion.setText(getString(R.string.about_version_fmt, vName));
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
                Intent intent = new Intent("com.jotapeveloper.fision.LANGUAGE_CHANGED");
                intent.setPackage(requireActivity().getPackageName());
                requireActivity().sendBroadcast(intent);
                requireActivity().recreate();
                return true;
            });
            int currentIndex = languagePreference.findIndexOfValue(languagePreference.getValue());
            if (currentIndex >= 0) languagePreference.setSummary(entries[currentIndex]);
        }

        // ---- account: single row, opens login dialog or account dialog ---
        private void setupAccount() {
            Preference accountPref = findPreference("account");
            if (accountPref == null) return;
            refreshAccountSummary(accountPref);
            accountPref.setOnPreferenceClickListener(p -> {
                if (AuthManager.get(requireContext()).isLoggedIn()) {
                    showAccountDialog(accountPref);
                } else {
                    LoginDialog.show(requireActivity(), true, new LoginDialog.Callback() {
                        @Override public void onLoggedIn(String u) { refreshAccountSummary(accountPref); }
                        @Override public void onLocalMode()        { refreshAccountSummary(accountPref); }
                    });
                }
                return true;
            });
        }

        private void refreshAccountSummary(Preference pref) {
            ServerPrefs sp = ServerPrefs.get(requireContext());
            if (sp.isLoggedIn()) {
                pref.setSummary(getString(R.string.account_summary_logged, sp.getUsername()));
            } else {
                pref.setSummary(getString(R.string.account_summary_local));
            }
        }

        private void showAccountDialog(Preference accountPref) {
            FragmentActivity host = requireActivity();
            View v = getLayoutInflater().inflate(R.layout.account_dialog, null);
            android.widget.TextView tvInitial = v.findViewById(R.id.tv_accountInitial);
            android.widget.TextView tvUser    = v.findViewById(R.id.tv_accountUser);
            android.widget.TextView tvUrl     = v.findViewById(R.id.tv_accountUrl);
            com.google.android.material.imageview.ShapeableImageView ivAvatar =
                    v.findViewById(R.id.iv_accountAvatar);
            View avatarWrap = v.findViewById(R.id.fl_accountAvatarWrap);
            com.google.android.material.button.MaterialButton bRemovePhoto =
                    v.findViewById(R.id.b_accountRemovePhoto);
            com.google.android.material.button.MaterialButton bChange =
                    v.findViewById(R.id.b_accountChangePassword);
            com.google.android.material.button.MaterialButton bSwitch =
                    v.findViewById(R.id.b_accountSwitch);
            com.google.android.material.button.MaterialButton bLogout =
                    v.findViewById(R.id.b_accountLogout);

            ServerPrefs sp = ServerPrefs.get(host);
            String user = sp.getUsername();
            tvInitial.setText(user.isEmpty() ? "?" : user.substring(0, 1).toUpperCase(Locale.ROOT));
            tvUser.setText(user);
            tvUrl.setText(getString(R.string.account_url_line, sp.getUrl()));
            paintAvatarInto(user, tvInitial, ivAvatar, bRemovePhoto);

            AlertDialog dlg = new AlertDialog.Builder(host, R.style.Theme_Fision_BlurDialog)
                    .setView(v)
                    .setCancelable(true)
                    .create();
            android.view.Window win = dlg.getWindow();
            if (win != null) win.setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            applyActivityBlur();
            // Live-refresh the avatar card if the user finishes the crop flow
            // while this dialog is still on-screen.
            AvatarManager.Listener liveListener = uname ->
                    paintAvatarInto(sp.getUsername(), tvInitial, ivAvatar, bRemovePhoto);
            AvatarManager.get(host).addListener(liveListener);
            dlg.setOnDismissListener(d -> {
                AvatarManager.get(host).removeListener(liveListener);
                removeActivityBlur();
            });

            avatarWrap.setOnClickListener(x -> {
                accountPrefRef = accountPref;
                try { avatarPicker.launch("image/*"); }
                catch (Throwable t) {
                    Toast.makeText(host, R.string.crop_read_fail, Toast.LENGTH_LONG).show();
                }
            });
            bRemovePhoto.setOnClickListener(x -> {
                bRemovePhoto.setEnabled(false);
                AvatarManager.get(host).deleteAvatar(user, new AvatarManager.UploadCallback() {
                    @Override public void onSuccess() {
                        paintAvatarInto(user, tvInitial, ivAvatar, bRemovePhoto);
                    }
                    @Override public void onFailure(int code, String reason) {
                        bRemovePhoto.setEnabled(true);
                        Toast.makeText(host, R.string.login_err_server, Toast.LENGTH_LONG).show();
                    }
                });
            });
            bChange.setOnClickListener(x -> {
                dlg.dismiss();
                ChangePasswordDialog.show(host, new ChangePasswordDialog.Callback() {});
            });
            bSwitch.setOnClickListener(x -> {
                dlg.dismiss();
                AuthManager.get(host).logout();
                refreshAccountSummary(accountPref);
                LoginDialog.show(host, true, new LoginDialog.Callback() {
                    @Override public void onLoggedIn(String u) { refreshAccountSummary(accountPref); }
                    @Override public void onLocalMode()        { refreshAccountSummary(accountPref); }
                });
            });
            bLogout.setOnClickListener(x -> {
                AuthManager.get(host).logout();
                refreshAccountSummary(accountPref);
                dlg.dismiss();
            });
            dlg.show();
        }

        /** Paints the avatar card: image if cached, initial fallback otherwise.
         *  Also toggles the "Remove photo" button visibility. */
        private void paintAvatarInto(String user,
                                     android.widget.TextView tvInitial,
                                     com.google.android.material.imageview.ShapeableImageView ivAvatar,
                                     com.google.android.material.button.MaterialButton bRemove) {
            AvatarManager am = AvatarManager.get(requireContext());
            if (am.hasLocalAvatar(user)) {
                android.graphics.Bitmap bm = android.graphics.BitmapFactory
                        .decodeFile(am.getLocalFile(user).getAbsolutePath());
                if (bm != null) {
                    ivAvatar.setImageBitmap(bm);
                    ivAvatar.setVisibility(View.VISIBLE);
                    tvInitial.setVisibility(View.GONE);
                    bRemove.setVisibility(View.VISIBLE);
                    bRemove.setEnabled(true);
                    return;
                }
            }
            ivAvatar.setVisibility(View.GONE);
            tvInitial.setVisibility(View.VISIBLE);
            bRemove.setVisibility(View.GONE);
        }

        // ---- server test kept as a debug affordance ---------------------
        private void setupServerTest() {
            Preference testPref = findPreference("server_test");
            if (testPref == null) return;
            testPref.setOnPreferenceClickListener(p -> {
                runConnectionTest(testPref);
                return true;
            });
        }

        private void runConnectionTest(Preference pref) {
            Context ctx = requireContext();
            ServerPrefs sp = ServerPrefs.get(ctx);
            if (!sp.hasUrl()) {
                Toast.makeText(ctx, R.string.server_test_no_url, Toast.LENGTH_LONG).show();
                return;
            }
            ApiClient.get(ctx).notifyConfigChanged();
            FisionApi api = ApiClient.get(ctx).api();
            if (api == null) {
                Toast.makeText(ctx, R.string.server_test_fail, Toast.LENGTH_LONG).show();
                return;
            }

            View dlgView = getLayoutInflater().inflate(R.layout.ping_dialog, null);
            android.widget.ProgressBar spinner = dlgView.findViewById(R.id.pb_pingSpinner);
            android.widget.ImageView ivResult = dlgView.findViewById(R.id.iv_pingResult);
            android.widget.TextView tvText = dlgView.findViewById(R.id.tv_pingText);
            com.google.android.material.button.MaterialButton bClose =
                    dlgView.findViewById(R.id.b_pingClose);

            AlertDialog dlg = new AlertDialog.Builder(ctx, R.style.Theme_Fision_BlurDialog)
                    .setView(dlgView)
                    .setCancelable(false)
                    .create();
            android.view.Window win = dlg.getWindow();
            if (win != null) win.setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

            pref.setEnabled(false);
            applyActivityBlur();
            dlg.setOnDismissListener(d -> { pref.setEnabled(true); removeActivityBlur(); });
            dlg.show();

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
