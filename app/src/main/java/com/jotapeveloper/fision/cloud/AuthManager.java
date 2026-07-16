package com.jotapeveloper.fision.cloud;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Session façade on top of ServerPrefs + FisionApi. Owns login / logout /
 * change-password and the local_mode + first_run flags. UI dialogs and
 * MainActivity read the session state from here.
 */
public class AuthManager {

    private static final String TAG = "AuthManager";

    public interface LoginCallback {
        void onSuccess(String username);
        /** userMessage is already localised (falls back to a generic error). */
        void onFailure(int httpCode, String userMessage);
    }

    public interface ChangePasswordCallback {
        void onSuccess();
        void onFailure(int httpCode, String userMessage);
    }

    private static AuthManager INSTANCE;
    public static synchronized AuthManager get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new AuthManager(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AuthManager(Context ctx) {
        this.appCtx = ctx;
        migrateLegacyToken();
    }

    /** Legacy sessions stored the bearer token but never the username. If a
     *  token exists without a matching username, decode the JWT payload and
     *  pull `sub` from it. The signature is not verified here — the server
     *  will still reject an invalid token on the next request. */
    private void migrateLegacyToken() {
        ServerPrefs sp = ServerPrefs.get(appCtx);
        if (!sp.hasToken() || !sp.getUsername().isEmpty()) return;
        String sub = extractSubject(sp.getToken());
        if (sub != null && !sub.isEmpty()) {
            sp.setUsername(sub);
            // Older installs may have left first_run unset while still holding a
            // token. Treat that as a completed first-run so the dialog does not
            // pop up on top of an already-signed-in user.
            sp.setFirstRunCompleted();
        }
    }

    private static String extractSubject(String jwt) {
        if (jwt == null) return null;
        int a = jwt.indexOf('.');
        int b = jwt.indexOf('.', a + 1);
        if (a <= 0 || b <= a) return null;
        try {
            String payload = jwt.substring(a + 1, b);
            byte[] raw = android.util.Base64.decode(payload,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                            | android.util.Base64.NO_WRAP);
            String json = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(json);
            return o.optString("sub", null);
        } catch (Throwable t) {
            Log.w(TAG, "JWT payload parse failed", t);
            return null;
        }
    }

    public boolean isFirstRun()  { return ServerPrefs.get(appCtx).isFirstRun(); }
    public boolean isLoggedIn()  { return ServerPrefs.get(appCtx).isLoggedIn(); }
    public boolean isLocalMode() { return ServerPrefs.get(appCtx).isLocalMode(); }
    public String  getUsername() { return ServerPrefs.get(appCtx).getUsername(); }
    public String  getServerUrl(){ return ServerPrefs.get(appCtx).getUrl(); }

    /** Marks first-run as completed and switches the app into local mode. */
    public void enterLocalMode() {
        ServerPrefs prefs = ServerPrefs.get(appCtx);
        String outgoing = prefs.getUsername();
        prefs.setLocalMode(true);
        prefs.setFirstRunCompleted();
        // Wipe any lingering token so the ApiClient does not try to auth
        prefs.setToken("");
        prefs.setUsername("");
        ApiClient.get(appCtx).notifyConfigChanged();
        CloudRepository.get(appCtx).notifyConfigChanged();
        AvatarManager.get(appCtx).clearLocal(outgoing);
        ServerInfoManager.get(appCtx).clear();
    }

    /** Attempts a login against the given URL and stores everything on success. */
    public void login(String url, String username, String password, LoginCallback cb) {
        ServerPrefs prefs = ServerPrefs.get(appCtx);
        String u = url == null ? "" : url.trim();
        String user = username == null ? "" : username.trim().toLowerCase();
        String pass = password == null ? "" : password;
        if (u.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            cb.onFailure(0, "empty_fields");
            return;
        }
        prefs.setUrl(u);
        ApiClient.get(appCtx).notifyConfigChanged();

        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) {
            cb.onFailure(0, "bad_url");
            return;
        }
        api.login(new LoginRequest(user, pass)).enqueue(new Callback<LoginResponse>() {
            @Override public void onResponse(Call<LoginResponse> c, Response<LoginResponse> r) {
                if (r.isSuccessful() && r.body() != null && r.body().token != null) {
                    String tok = r.body().token;
                    String uname = r.body().user != null ? r.body().user : user;
                    prefs.setToken(tok);
                    prefs.setUsername(uname);
                    prefs.setLocalMode(false);
                    prefs.setFirstRunCompleted();
                    ApiClient.get(appCtx).notifyConfigChanged();
                    CloudRepository.get(appCtx).notifyConfigChanged();
                    // Pull the avatar into the local cache so the toolbar can
                    // display it as soon as MainActivity refreshes. 404 is a
                    // no-op inside the manager (initial fallback stays).
                    // ensureFetched marks the user as attempted so the safety
                    // net in refreshToolbarAvatar does not re-fire this call.
                    AvatarManager.get(appCtx).ensureFetched(uname);
                    // Cache /server/info so Settings and Import can immediately
                    // reflect GPU availability without another round-trip.
                    ServerInfoManager.get(appCtx).refresh();
                    main.post(() -> cb.onSuccess(uname));
                } else {
                    int code = r.code();
                    main.post(() -> cb.onFailure(code,
                            code == 401 ? "bad_credentials" : "server_error"));
                }
            }
            @Override public void onFailure(Call<LoginResponse> c, Throwable t) {
                Log.w(TAG, "login network failure", t);
                main.post(() -> cb.onFailure(0, "network_error"));
            }
        });
    }

    /** Requires an active session. Rotates the password server-side and keeps
     *  the fresh token that the server returns (the old one is dead). */
    public void changePassword(String oldPass, String newPass, ChangePasswordCallback cb) {
        ServerPrefs prefs = ServerPrefs.get(appCtx);
        if (!prefs.isLoggedIn()) {
            cb.onFailure(0, "not_logged_in");
            return;
        }
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) {
            cb.onFailure(0, "bad_url");
            return;
        }
        api.changePassword(new ChangePasswordRequest(oldPass, newPass))
                .enqueue(new Callback<LoginResponse>() {
            @Override public void onResponse(Call<LoginResponse> c, Response<LoginResponse> r) {
                if (r.isSuccessful() && r.body() != null && r.body().token != null) {
                    prefs.setToken(r.body().token);
                    ApiClient.get(appCtx).notifyConfigChanged();
                    main.post(cb::onSuccess);
                } else {
                    int code = r.code();
                    String reason;
                    if (code == 403) reason = "bad_old_password";
                    else if (code == 400) reason = "bad_new_password";
                    else reason = "server_error";
                    main.post(() -> cb.onFailure(code, reason));
                }
            }
            @Override public void onFailure(Call<LoginResponse> c, Throwable t) {
                Log.w(TAG, "changePassword network failure", t);
                main.post(() -> cb.onFailure(0, "network_error"));
            }
        });
    }

    /** Wipes the token/username and switches to local mode. The URL is kept
     *  so the login dialog can pre-fill it next time. The avatar cache for the
     *  outgoing user is dropped so the next login starts from a clean slate. */
    public void logout() {
        ServerPrefs prefs = ServerPrefs.get(appCtx);
        String outgoing = prefs.getUsername();
        prefs.setToken("");
        prefs.setUsername("");
        prefs.setLocalMode(true);
        ApiClient.get(appCtx).notifyConfigChanged();
        CloudRepository.get(appCtx).notifyConfigChanged();
        AvatarManager.get(appCtx).clearLocal(outgoing);
        ServerInfoManager.get(appCtx).clear();
    }
}
