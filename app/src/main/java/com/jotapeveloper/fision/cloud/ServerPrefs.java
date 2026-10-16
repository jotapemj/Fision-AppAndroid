package com.jotapeveloper.fision.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Persists server credentials:
 *   - URL: plain (not a secret, just configuration).
 *   - Bearer token: EncryptedSharedPreferences (AES-GCM with a Keystore-backed key).
 *
 * When EncryptedSharedPreferences fails on some odd device, it falls back to
 * plain SharedPreferences (better to work than to fail). The token is still
 * gated by biometrics at the UI level.
 */
public class ServerPrefs {

    private static final String TAG = "ServerPrefs";
    private static final String PREFS_PLAIN   = "fision_server_prefs";
    private static final String PREFS_SECURE  = "fision_server_secure";
    private static final String KEY_URL        = "server_url";
    private static final String KEY_TOKEN      = "server_token";
    private static final String KEY_USER       = "server_user";
    private static final String KEY_LOCAL_MODE = "local_mode";
    private static final String KEY_FIRST_RUN  = "first_run";
    private static final String KEY_SESSION_EXPIRED = "session_expired";

    private final SharedPreferences plain;
    private final SharedPreferences secure;

    private static ServerPrefs INSTANCE;

    public static synchronized ServerPrefs get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new ServerPrefs(ctx.getApplicationContext());
        return INSTANCE;
    }

    private ServerPrefs(Context ctx) {
        this.plain = ctx.getSharedPreferences(PREFS_PLAIN, Context.MODE_PRIVATE);
        SharedPreferences sec;
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            sec = EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_SECURE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Throwable t) {
            Log.w(TAG, "EncryptedSharedPreferences failed, using plain fallback", t);
            sec = ctx.getSharedPreferences(PREFS_SECURE, Context.MODE_PRIVATE);
        }
        this.secure = sec;
    }

    public String getUrl() {
        String u = plain.getString(KEY_URL, "");
        return u == null ? "" : u.trim();
    }

    public void setUrl(String url) {
        plain.edit().putString(KEY_URL, url == null ? "" : url.trim()).apply();
    }

    public String getToken() {
        String t = secure.getString(KEY_TOKEN, "");
        return t == null ? "" : t;
    }

    public void setToken(String token) {
        secure.edit().putString(KEY_TOKEN, token == null ? "" : token).apply();
    }

    public boolean hasUrl()   { return !getUrl().isEmpty(); }
    public boolean hasToken() { return !getToken().isEmpty(); }
    public boolean isConfigured() { return hasUrl(); }

    public String getUsername() {
        String u = plain.getString(KEY_USER, "");
        return u == null ? "" : u.trim();
    }

    public void setUsername(String user) {
        plain.edit().putString(KEY_USER, user == null ? "" : user.trim()).apply();
    }

    public boolean isLocalMode() {
        return plain.getBoolean(KEY_LOCAL_MODE, false);
    }

    public void setLocalMode(boolean local) {
        plain.edit().putBoolean(KEY_LOCAL_MODE, local).apply();
    }

    /** True until the user finishes the first-run login flow (login or local mode). */
    public boolean isFirstRun() {
        return plain.getBoolean(KEY_FIRST_RUN, true);
    }

    public void setFirstRunCompleted() {
        plain.edit().putBoolean(KEY_FIRST_RUN, false).apply();
    }

    /** Set when the server rejected the stored token: the next screen able to
     *  sign in asks for the password again. Cleared on login or local mode. */
    public boolean isSessionExpired() {
        return plain.getBoolean(KEY_SESSION_EXPIRED, false);
    }

    public void setSessionExpired(boolean expired) {
        plain.edit().putBoolean(KEY_SESSION_EXPIRED, expired).apply();
    }

    /** True when a login was completed and the token has not been wiped. */
    public boolean isLoggedIn() {
        return hasToken() && !getUsername().isEmpty();
    }
}
