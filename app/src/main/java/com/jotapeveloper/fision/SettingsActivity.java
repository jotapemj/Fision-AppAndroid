package com.jotapeveloper.fision;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.jotapeveloper.fision.cloud.ApiClient;
import com.jotapeveloper.fision.cloud.AuthManager;
import com.jotapeveloper.fision.cloud.AvatarManager;
import com.jotapeveloper.fision.cloud.CloudRepository;
import com.jotapeveloper.fision.cloud.FisionApi;
import com.jotapeveloper.fision.cloud.ServerInfoManager;
import com.jotapeveloper.fision.cloud.ServerPrefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Redesigned settings screen with a card-based layout. Every option is
 *  a tappable card that opens the relevant dialog or triggers the action. */
public class SettingsActivity extends AppCompatActivity
        implements SettingsAdapter.OnItemClickListener {

    // Card ids used by the adapter to dispatch clicks
    private static final String ID_ACCOUNT   = "account";
    private static final String ID_DESIGN    = "design";
    private static final String ID_LANGUAGE  = "language";
    private static final String ID_GPU       = "gpu";
    private static final String ID_VERSION   = "server_version";
    private static final String ID_TEST      = "server_test";
    private static final String ID_ABOUT     = "about";
    private static final String ID_LOGOUT    = "logout";
    private static final String ID_GITHUB    = "github";
    private static final String GITHUB_URL   = "https://github.com/jotapemj/FisionApp-Android";

    private RecyclerView rv;
    private SettingsAdapter adapter;
    private final List<SettingsItem> items = new ArrayList<>();

    private ActivityResultLauncher<String> avatarPicker;
    private final ServerInfoManager.Listener serverInfoListener = this::rebuildItems;
    private final AvatarManager.Listener avatarListener = user -> rebuildItems();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.jotapeveloper.fision.design.AccentColorManager.get(this).applyOverlayTo(this);
        setContentView(R.layout.settings_activity);

        avatarPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri == null) return;
                    Intent i = new Intent(this, CropAvatarActivity.class);
                    i.putExtra(CropAvatarActivity.EXTRA_SOURCE_URI, uri);
                    startActivity(i);
                });

        rv = findViewById(R.id.rv_settings);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SettingsAdapter(items, this);
        rv.setAdapter(adapter);

        rebuildItems();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ServerInfoManager.get(this).addListener(serverInfoListener);
        AvatarManager.get(this).addListener(avatarListener);
        // Poll the server for fresh info every time Settings opens so a
        // version bump on the server side is reflected without needing a
        // logout / login cycle. The listener repaints when the fetch lands.
        if (AuthManager.get(this).isLoggedIn()) {
            ServerInfoManager.get(this).refresh();
        }
        rebuildItems();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ServerInfoManager.get(this).removeListener(serverInfoListener);
        AvatarManager.get(this).removeListener(avatarListener);
    }

    // ---------------- build item list from current state ----------------
    private void rebuildItems() {
        items.clear();
        AuthManager auth = AuthManager.get(this);
        ServerPrefs sp = ServerPrefs.get(this);

        // Header card: user info if logged in, "local mode" otherwise
        String user  = auth.isLoggedIn() ? sp.getUsername() : "";
        String email = auth.isLoggedIn() ? sp.getUrl() : "";
        items.add(SettingsItem.header(ID_ACCOUNT, user, email));

        // Section: Server
        items.add(SettingsItem.section("sec_server",
                getString(R.string.settings_section_server)));
        items.add(SettingsItem.card(ID_GPU, R.drawable.ic_gpu,
                getString(R.string.server_gpu_title),
                gpuSubtitle()));
        // Server version card. Hidden when there is no session (no server to
        // ask) or when the server did not report a version (pre-1.1.0). The
        // card is read-only: no chevron and no click handler in the dispatch.
        String serverVersion = ServerInfoManager.get(this).getVersion();
        if (auth.isLoggedIn() && serverVersion != null) {
            SettingsItem versionCard = SettingsItem.card(ID_VERSION, R.drawable.ic_info,
                    getString(R.string.server_version_title),
                    getString(R.string.server_version_fmt, serverVersion));
            versionCard.showChevron = false;
            items.add(versionCard);
        }
        items.add(SettingsItem.card(ID_TEST, R.drawable.ic_signal,
                getString(R.string.server_test_title),
                getString(R.string.server_test_summary)));

        // Section: App
        items.add(SettingsItem.section("sec_app",
                getString(R.string.settings_section_app)));
        items.add(SettingsItem.card(ID_DESIGN, R.drawable.ic_design,
                getString(R.string.design_title),
                getString(R.string.design_summary)));
        items.add(SettingsItem.card(ID_LANGUAGE, R.drawable.ic_language,
                getString(R.string.settings_language_title),
                currentLanguageLabel()));

        // Section: About
        items.add(SettingsItem.section("sec_about",
                getString(R.string.settings_section_about)));
        items.add(SettingsItem.card(ID_ABOUT, R.drawable.ic_info,
                getString(R.string.about_title),
                null));

        SettingsItem github = SettingsItem.card(ID_GITHUB, R.drawable.ic_github,
                getString(R.string.settings_donate_title),
                null);
        github.externalLink = true;
        items.add(github);

        // Optional sign-out card only when there is an active session
        if (auth.isLoggedIn()) {
            SettingsItem logout = SettingsItem.card(ID_LOGOUT, R.drawable.ic_logout,
                    getString(R.string.settings_logout_title),
                    null);
            logout.iconTintOverride = R.color.cloud_offline;
            items.add(logout);
        }
        adapter.notifyDataSetChanged();
    }

    private CharSequence gpuSubtitle() {
        AuthManager auth = AuthManager.get(this);
        ServerInfoManager mgr = ServerInfoManager.get(this);
        if (!auth.isLoggedIn() || !mgr.isLoaded()) {
            return getString(R.string.server_gpu_unknown);
        }
        if (mgr.hasGpu()) {
            int vramGb = Math.max(0, mgr.getVramMb() / 1024);
            String name = mgr.getGpuName();
            if (name == null || name.isEmpty()) name = getString(R.string.server_gpu_ok_short);
            return getString(R.string.server_gpu_ok_fmt, name, vramGb);
        }
        return getString(R.string.server_gpu_missing);
    }

    private CharSequence currentLanguageLabel() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        String code = sp.getString("language", Locale.getDefault().getLanguage());
        String[] entries = getResources().getStringArray(R.array.lang_entries);
        String[] values = getResources().getStringArray(R.array.lang_entries_values);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(code)) return entries[i];
        }
        return code;
    }

    // ---------------- click dispatch ----------------
    @Override
    public void onSettingsItemClick(SettingsItem item) {
        switch (item.id) {
            case ID_ACCOUNT:  onAccountClicked();  break;
            case ID_DESIGN:   showDesignDialog();  break;
            case ID_LANGUAGE: onLanguageClicked(); break;
            case ID_GPU:      onGpuClicked();      break;
            case ID_TEST:     runConnectionTest(); break;
            case ID_ABOUT:    showAboutDialog();   break;
            case ID_LOGOUT:   onLogoutClicked();   break;
            case ID_GITHUB:   openGithub();        break;
        }
    }

    private void openGithub() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.login_err_server, Toast.LENGTH_SHORT).show();
        }
    }

    private void onAccountClicked() {
        if (AuthManager.get(this).isLoggedIn()) {
            showAccountDialog();
        } else {
            LoginDialog.show(this, true, new LoginDialog.Callback() {
                @Override public void onLoggedIn(String u) { rebuildItems(); }
                @Override public void onLocalMode()        { rebuildItems(); }
            });
        }
    }

    private void onGpuClicked() {
        showGpuDialog();
    }

    private void showGpuDialog() {
        View v = getLayoutInflater().inflate(R.layout.gpu_status_dialog, null);
        View badgeWrap    = v.findViewById(R.id.fl_gpu_dialogBadge);
        ImageView ivBadge = v.findViewById(R.id.iv_gpu_dialogBadge);
        TextView tvState  = v.findViewById(R.id.tv_gpu_dialogState);
        TextView tvName   = v.findViewById(R.id.tv_gpu_dialogName);
        TextView tvVram   = v.findViewById(R.id.tv_gpu_dialogVram);
        TextView tvDriver = v.findViewById(R.id.tv_gpu_dialogDriver);
        View detailsWrap  = v.findViewById(R.id.ll_gpu_dialogDetailsWrap);
        MaterialButton bRefresh = v.findViewById(R.id.b_gpu_dialogRefresh);
        MaterialButton bClose   = v.findViewById(R.id.b_gpu_dialogClose);

        AlertDialog dlg = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(v)
                .setCancelable(true)
                .create();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // Paint the dialog against the current cache state
        Runnable paint = () -> paintGpuDialog(badgeWrap, ivBadge, tvState, tvName, tvVram,
                tvDriver, detailsWrap, bRefresh);
        paint.run();

        // Live-refresh: repaint on every ServerInfoManager notification
        ServerInfoManager.Listener liveListener = paint::run;
        ServerInfoManager.get(this).addListener(liveListener);

        applyActivityBlur();
        dlg.setOnDismissListener(d -> {
            ServerInfoManager.get(this).removeListener(liveListener);
            removeActivityBlur();
        });

        bRefresh.setOnClickListener(x -> {
            if (!AuthManager.get(this).isLoggedIn()) {
                dlg.dismiss();
                onAccountClicked();
                return;
            }
            bRefresh.setEnabled(false);
            ServerInfoManager.get(this).refresh(() -> {
                bRefresh.setEnabled(true);
                paint.run();
                rebuildItems();
            });
        });
        bClose.setOnClickListener(x -> dlg.dismiss());
        dlg.show();
    }

    private void paintGpuDialog(View badgeWrap, ImageView ivBadge, TextView tvState,
                                 TextView tvName, TextView tvVram,
                                 TextView tvDriver, View detailsWrap,
                                 MaterialButton bRefresh) {
        AuthManager auth = AuthManager.get(this);
        ServerInfoManager mgr = ServerInfoManager.get(this);

        if (!auth.isLoggedIn() || !mgr.isLoaded()) {
            badgeWrap.setBackgroundResource(R.drawable.gpu_cross_badge_bg);
            badgeWrap.getBackground().setTint(
                    ContextCompat.getColor(this, R.color.AppDarkGrey));
            ivBadge.setImageResource(R.drawable.ic_cross_thin);
            tvState.setText(R.string.gpu_dialog_title_unknown);
            tvName.setText(R.string.gpu_dialog_no_data);
            detailsWrap.setVisibility(View.GONE);
            return;
        }
        if (mgr.hasGpu()) {
            badgeWrap.setBackgroundResource(R.drawable.gpu_check_badge_bg);
            ivBadge.setImageResource(R.drawable.ic_check_thin);
            tvState.setText(R.string.gpu_dialog_title_ok);
            String name = mgr.getGpuName();
            if (name == null || name.isEmpty()) name = getString(R.string.server_gpu_ok_short);
            tvName.setText(name);
            int vramGb = Math.max(0, mgr.getVramMb() / 1024);
            tvVram.setText(getString(R.string.vram_gb_fmt, vramGb));
            String driver = mgr.getDriver();
            tvDriver.setText(driver == null || driver.isEmpty() ? "—" : driver);
            detailsWrap.setVisibility(View.VISIBLE);
        } else {
            badgeWrap.setBackgroundResource(R.drawable.gpu_cross_badge_bg);
            ivBadge.setImageResource(R.drawable.ic_cross_thin);
            tvState.setText(R.string.gpu_dialog_title_missing);
            tvName.setText(R.string.gpu_dialog_no_gpu_body);
            detailsWrap.setVisibility(View.GONE);
        }
    }

    private void onLogoutClicked() {
        AuthManager.get(this).logout();
        rebuildItems();
    }

    // ---------------- language dialog ----------------
    private void onLanguageClicked() {
        String[] entries = getResources().getStringArray(R.array.lang_entries);
        String[] values  = getResources().getStringArray(R.array.lang_entries_values);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        String current = sp.getString("language", Locale.getDefault().getLanguage());
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(current)) { checked = i; break; }
        }

        // Same look and feel as every other single-choice dialog in the app
        // (single_choice_dialog + chip rows + activity blur).
        View dialogView = LayoutInflater.from(this).inflate(R.layout.single_choice_dialog, null);
        TextView tvTitle = dialogView.findViewById(R.id.tv_singleChoiceTitle);
        LinearLayout opts = dialogView.findViewById(R.id.ll_singleChoiceOptions);
        MaterialButton bCancel = dialogView.findViewById(R.id.b_singleChoiceCancel);
        tvTitle.setText(R.string.settings_language_dialog_title);

        AlertDialog dlg = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        int primary = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this);
        int white   = ContextCompat.getColor(this, R.color.AppWhite);
        float density = getResources().getDisplayMetrics().density;
        int padH  = (int) (14f * density);
        int padV  = (int) (12f * density);
        int gapPx = (int) (8f  * density);

        for (int i = 0; i < entries.length; i++) {
            final int idx = i;
            TextView row = new TextView(this);
            row.setText(entries[i]);
            row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            row.setTypeface(
                    androidx.core.content.res.ResourcesCompat.getFont(this, R.font.yugothic_ui_regular),
                    idx == checked ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            row.setTextColor(idx == checked ? primary : white);
            row.setBackgroundResource(idx == checked
                    ? R.drawable.dialog_option_active
                    : R.drawable.dialog_option_inactive);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                row.setForeground(ContextCompat.getDrawable(this, R.drawable.settings_card_ripple));
            }
            row.setPadding(padH, padV, padH, padV);
            row.setClickable(true);
            row.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = gapPx;
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                dlg.dismiss();
                String chosen = values[idx];
                sp.edit().putString("language", chosen).apply();
                updateLocale(chosen);
                Intent i2 = new Intent("com.jotapeveloper.fision.LANGUAGE_CHANGED");
                i2.setPackage(getPackageName());
                sendBroadcast(i2);
                recreate();
            });
            opts.addView(row);
        }

        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());
        bCancel.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    private void updateLocale(String languageCode) {
        Locale locale = new Locale(languageCode);
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
    }

    // ---------------- account dialog (same look as before) ----------------
    private void showAccountDialog() {
        View v = getLayoutInflater().inflate(R.layout.account_dialog, null);
        TextView tvInitial = v.findViewById(R.id.tv_accountInitial);
        TextView tvUser    = v.findViewById(R.id.tv_accountUser);
        TextView tvUrl     = v.findViewById(R.id.tv_accountUrl);
        ShapeableImageView ivAvatar = v.findViewById(R.id.iv_accountAvatar);
        View avatarWrap = v.findViewById(R.id.fl_accountAvatarWrap);
        MaterialButton bRemovePhoto = v.findViewById(R.id.b_accountRemovePhoto);
        MaterialButton bChange = v.findViewById(R.id.b_accountChangePassword);

        ServerPrefs sp = ServerPrefs.get(this);
        String user = sp.getUsername();
        tvInitial.setText(user.isEmpty() ? "?" : user.substring(0, 1).toUpperCase(Locale.ROOT));
        tvUser.setText(user);
        tvUrl.setText(getString(R.string.account_url_line, sp.getUrl()));
        paintAvatarInto(user, tvInitial, ivAvatar, bRemovePhoto);

        AlertDialog dlg = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(v)
                .setCancelable(true)
                .create();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        applyActivityBlur();
        AvatarManager.Listener live = uname ->
                paintAvatarInto(sp.getUsername(), tvInitial, ivAvatar, bRemovePhoto);
        AvatarManager.get(this).addListener(live);
        dlg.setOnDismissListener(d -> {
            AvatarManager.get(this).removeListener(live);
            removeActivityBlur();
        });

        avatarWrap.setOnClickListener(x -> {
            // Open the preset-based avatar studio first. It exposes a
            // "Pick from device" button that falls back to the gallery picker
            // for users who prefer their own photo.
            dlg.dismiss();
            openAvatarStudio();
        });
        bRemovePhoto.setOnClickListener(x -> {
            bRemovePhoto.setEnabled(false);
            AvatarManager.get(this).deleteAvatar(user, new AvatarManager.UploadCallback() {
                @Override public void onSuccess() {
                    paintAvatarInto(user, tvInitial, ivAvatar, bRemovePhoto);
                    rebuildItems();
                }
                @Override public void onFailure(int code, String reason) {
                    bRemovePhoto.setEnabled(true);
                    Toast.makeText(SettingsActivity.this,
                            R.string.login_err_server, Toast.LENGTH_LONG).show();
                }
            });
        });
        bChange.setOnClickListener(x -> {
            dlg.dismiss();
            ChangePasswordDialog.show(this, new ChangePasswordDialog.Callback() {});
        });
        dlg.show();
    }

    /** Opens the preset-based avatar studio. Compose = upload directly.
     *  "Pick from device" = fall back to the gallery + crop flow. */
    private void openAvatarStudio() {
        new AvatarStudioDialog(this, new AvatarStudioDialog.Callback() {
            @Override public void onAvatarComposed(byte[] jpegBytes) {
                uploadStudioBytes(jpegBytes);
            }
            @Override public void onPickFromDevice() {
                try { avatarPicker.launch("image/*"); }
                catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this,
                            R.string.crop_read_fail, Toast.LENGTH_LONG).show();
                }
            }
        }).show();
    }

    /** Writes the composed studio JPEG to cacheDir and uploads it via
     *  AvatarManager so it appears immediately in the toolbar and syncs
     *  with the server. */
    private void uploadStudioBytes(byte[] jpegBytes) {
        String user = com.jotapeveloper.fision.cloud.ServerPrefs.get(this).getUsername();
        if (user == null || user.isEmpty()) {
            Toast.makeText(this, R.string.login_status_unauthenticated,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        java.io.File out = new java.io.File(getCacheDir(),
                "studio_avatar_" + System.currentTimeMillis() + ".jpg");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            fos.write(jpegBytes);
        } catch (java.io.IOException e) {
            Toast.makeText(this, R.string.login_err_server, Toast.LENGTH_LONG).show();
            return;
        }
        AvatarManager.get(this).uploadAvatar(user, out, new AvatarManager.UploadCallback() {
            @Override public void onSuccess() {
                rebuildItems();
                out.delete();
            }
            @Override public void onFailure(int code, String reason) {
                Toast.makeText(SettingsActivity.this,
                        R.string.login_err_server, Toast.LENGTH_LONG).show();
                out.delete();
            }
        });
    }

    private void paintAvatarInto(String user, TextView tvInitial,
                                 ShapeableImageView ivAvatar,
                                 MaterialButton bRemove) {
        AvatarManager am = AvatarManager.get(this);
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

    // ---------------- design dialog ----------------
    private void showDesignDialog() {
        View v = getLayoutInflater().inflate(R.layout.design_dialog, null);
        com.google.android.material.switchmaterial.SwitchMaterial swNebula =
                v.findViewById(R.id.sw_designNebulaFixed);
        MaterialButton bClose = v.findViewById(R.id.b_designClose);

        int[] swatchIds = new int[] {
                R.id.fl_accent_green,
                R.id.fl_accent_blue,
                R.id.fl_accent_purple,
                R.id.fl_accent_amber };
        int[] checkIds = new int[] {
                R.id.iv_accent_green_check,
                R.id.iv_accent_blue_check,
                R.id.iv_accent_purple_check,
                R.id.iv_accent_amber_check };

        com.jotapeveloper.fision.design.AccentColorManager mgr =
                com.jotapeveloper.fision.design.AccentColorManager.get(this);

        AlertDialog dlg = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(v)
                .setCancelable(true)
                .create();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());

        // Highlight the currently active swatch.
        Runnable refreshChecks = () -> {
            int active = mgr.getAccentId();
            for (int i = 0; i < checkIds.length; i++) {
                v.findViewById(checkIds[i]).setVisibility(
                        i == active ? View.VISIBLE : View.GONE);
            }
        };
        refreshChecks.run();

        // Applying an accent recreates the Activity so ?attr/colorPrimary can
        // resolve to the new palette. The dialog closes first so removeActivityBlur
        // fires before recreate wipes the window.
        for (int i = 0; i < swatchIds.length; i++) {
            final int accentId = i;
            v.findViewById(swatchIds[i]).setOnClickListener(x -> {
                if (mgr.getAccentId() == accentId) return;
                mgr.setAccentId(accentId);
                dlg.dismiss();
                recreate();
            });
        }

        swNebula.setChecked(mgr.isNebulaFixedAccent());
        swNebula.setOnCheckedChangeListener((btn, isChecked) ->
                mgr.setNebulaFixedAccent(isChecked));

        bClose.setOnClickListener(x -> dlg.dismiss());
        dlg.show();
        // Match the wizard/upload dialog width lock so the swatch row does not
        // reflow when the switch toggles below.
        if (w != null) {
            float density = getResources().getDisplayMetrics().density;
            w.setLayout((int) (320f * density),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    // ---------------- about dialog ----------------
    private void showAboutDialog() {
        View dlgView = getLayoutInflater().inflate(R.layout.about_dialog, null);
        TextView tvVersion = dlgView.findViewById(R.id.tv_about_version);
        MaterialButton bLicenses = dlgView.findViewById(R.id.b_about_licenses);
        MaterialButton bClose = dlgView.findViewById(R.id.b_about_close);
        String vName = "?";
        try {
            vName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {}
        tvVersion.setText(getString(R.string.about_version_fmt, vName));

        AlertDialog dlg = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(dlgView)
                .setCancelable(true)
                .create();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());
        bLicenses.setOnClickListener(x -> {
            dlg.dismiss();
            startActivity(new Intent(this, LicensesActivity.class));
        });
        bClose.setOnClickListener(x -> dlg.dismiss());
        dlg.show();
    }

    // ---------------- server test ----------------
    private void runConnectionTest() {
        ServerPrefs sp = ServerPrefs.get(this);
        if (!sp.hasUrl()) {
            Toast.makeText(this, R.string.server_test_no_url, Toast.LENGTH_LONG).show();
            return;
        }
        ApiClient.get(this).notifyConfigChanged();
        FisionApi api = ApiClient.get(this).api();
        if (api == null) {
            Toast.makeText(this, R.string.server_test_fail, Toast.LENGTH_LONG).show();
            return;
        }
        View dlgView = LayoutInflater.from(this).inflate(R.layout.ping_dialog, null);
        ProgressBar spinner = dlgView.findViewById(R.id.pb_pingSpinner);
        ImageView ivResult = dlgView.findViewById(R.id.iv_pingResult);
        TextView tvText = dlgView.findViewById(R.id.tv_pingText);
        MaterialButton bClose = dlgView.findViewById(R.id.b_pingClose);

        AlertDialog dlg = new AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(dlgView)
                .setCancelable(false)
                .create();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());
        dlg.show();

        final Call<Map<String, Object>> call = api.health();
        final boolean[] finished = {false};
        final Handler handler = new Handler(Looper.getMainLooper());

        Runnable showFail = () -> {
            spinner.setVisibility(View.GONE);
            ivResult.setImageResource(R.drawable.ic_cross_circle);
            ivResult.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.cloud_offline)));
            ivResult.setVisibility(View.VISIBLE);
            tvText.setText(R.string.server_test_fail);
            bClose.setVisibility(View.VISIBLE);
        };
        Runnable showOk = () -> {
            spinner.setVisibility(View.GONE);
            ivResult.setImageResource(R.drawable.ic_check_circle);
            ivResult.setImageTintList(android.content.res.ColorStateList.valueOf(
                    com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this)));
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
                    CloudRepository.get(SettingsActivity.this).notifyConfigChanged();
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

    // ---------------- blur helpers ----------------
    private void applyActivityBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().getDecorView().setRenderEffect(
                    RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        }
    }

    private void removeActivityBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().getDecorView().setRenderEffect(null);
        }
    }

    public void closeSettings(View view) { onBackPressed(); }
}
