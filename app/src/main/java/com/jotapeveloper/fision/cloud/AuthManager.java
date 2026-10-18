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

    /** Login failure reason for HTTP 429, followed by the wait in minutes. */
    public static final String REASON_TOO_MANY_ATTEMPTS_PREFIX = "too_many_attempts:";

    /** Retry-After seconds rounded up to whole minutes; 1 when missing or invalid. */
    static int retryAfterMinutes(String header) {
        try {
            long s = Long.parseLong(header.trim());
            return (int) Math.max(1, (s + 59) / 60);
        } catch (Exception e) {
            return 1;
        }
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
        prefs.setSessionExpired(false);
        // Wipe any lingering token so the ApiClient does not try to auth
        prefs.setToken("");
        prefs.setUsername("");
        ApiClient.get(appCtx).notifyConfigChanged();
        CloudRepository.get(appCtx).notifyConfigChanged();
        AvatarManager.get(appCtx).clearLocal(outgoing);
        ServerInfoManager.get(appCtx).clear();
        PlaylistRepository.get(appCtx).clear();
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
                    prefs.setSessionExpired(false);
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
                    // The user's playlists, for the Playlists tab.
                    PlaylistRepository.get(appCtx).clear();
                    PlaylistRepository.get(appCtx).sync();
                    main.post(() -> cb.onSuccess(uname));
                } else {
                    int code = r.code();
                    String reason;
                    if (code == 401) {
                        reason = "bad_credentials";
                    } else if (code == 429) {
                        // Server lockout: wait time in minutes, rounded up.
                        reason = REASON_TOO_MANY_ATTEMPTS_PREFIX
                                + retryAfterMinutes(r.headers().get("Retry-After"));
                    } else {
                        reason = "server_error";
                    }
                    main.post(() -> cb.onFailure(code, reason));
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

    private boolean refreshedThisProcess;

    /**
     * Renews the token once per app start, so a session used at least once per
     * TTL never expires. A 401 goes through the regular expired-session path
     * (ApiClient interceptor); network errors keep the current token.
     */
    public void refreshSessionOnce() {
        if (refreshedThisProcess) return;
        ServerPrefs prefs = ServerPrefs.get(appCtx);
        if (!prefs.isLoggedIn()) return;
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        refreshedThisProcess = true;
        String sent = prefs.getToken();
        api.refresh().enqueue(new Callback<LoginResponse>() {
            @Override public void onResponse(Call<LoginResponse> c, Response<LoginResponse> r) {
                if (!r.isSuccessful() || r.body() == null || r.body().token == null) {
                    if (r.code() != 401) refreshedThisProcess = false;
                    return;
                }
                String tok = r.body().token;
                main.post(() -> {
                    // A login or logout in between wins over this response.
                    if (!sent.equals(prefs.getToken())) return;
                    prefs.setToken(tok);
                    ApiClient.get(appCtx).notifyConfigChanged();
                });
            }
            @Override public void onFailure(Call<LoginResponse> c, Throwable t) {
                Log.w(TAG, "token refresh failed, keeping the current token", t);
                refreshedThisProcess = false;
            }
        });
    }

    /** Result of {@link #refreshBeforeUpload}. */
    public interface RefreshCallback {
        /** Session usable (renewed, or kept after a network error). */
        void onReady();
        /** Token missing or expired: the upload would be rejected unread. */
        void onExpired();
    }

    /**
     * Renews the token right before a large upload: the server answers 401
     * without reading the body, so an expired token would only show up after
     * sending the whole file. Main thread callbacks; a network error keeps
     * the current token and lets the upload try.
     */
    public void refreshBeforeUpload(RefreshCallback cb) {
        ServerPrefs prefs = ServerPrefs.get(appCtx);
        FisionApi api = ApiClient.get(appCtx).api();
        if (!prefs.isLoggedIn() || api == null) { cb.onExpired(); return; }
        String sent = prefs.getToken();
        api.refresh().enqueue(new Callback<LoginResponse>() {
            @Override public void onResponse(Call<LoginResponse> c, Response<LoginResponse> r) {
                main.post(() -> {
                    if (r.code() == 401) { cb.onExpired(); return; }
                    if (r.isSuccessful() && r.body() != null && r.body().token != null
                            && sent.equals(prefs.getToken())) {
                        prefs.setToken(r.body().token);
                        ApiClient.get(appCtx).notifyConfigChanged();
                    }
                    cb.onReady();
                });
            }
            @Override public void onFailure(Call<LoginResponse> c, Throwable t) {
                main.post(cb::onReady);
            }
        });
    }

    /** Notified on the main thread when the server rejects the session. */
    public interface SessionExpiredListener { void onSessionExpired(); }

    private final java.util.Set<SessionExpiredListener> expiredListeners =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    public void addSessionExpiredListener(SessionExpiredListener l) { expiredListeners.add(l); }
    public void removeSessionExpiredListener(SessionExpiredListener l) { expiredListeners.remove(l); }

    public boolean isSessionExpired() { return ServerPrefs.get(appCtx).isSessionExpired(); }

    /** The prompt for an expired session is being shown: do not show it again. */
    public void acknowledgeSessionExpired() { ServerPrefs.get(appCtx).setSessionExpired(false); }

    /**
     * A request authenticated with [rejectedToken] got 401: the token expired or
     * was revoked. Drops it (polling stops, nothing keeps resending it), keeps
     * URL and username to prefill the login and flags the session as expired.
     * Responses for an older token (already replaced by a new login) are ignored.
     * Called from OkHttp threads.
     */
    void onUnauthorized(String rejectedToken) {
        main.post(() -> {
            ServerPrefs prefs = ServerPrefs.get(appCtx);
            String current = prefs.getToken();
            if (current.isEmpty() || !current.equals(rejectedToken)) return;
            Log.w(TAG, "session rejected by the server (401), sign-in required");
            prefs.setToken("");
            prefs.setSessionExpired(true);
            ApiClient.get(appCtx).notifyConfigChanged();
            CloudRepository.get(appCtx).notifyConfigChanged();
            ServerInfoManager.get(appCtx).clear();
            for (SessionExpiredListener l : expiredListeners) l.onSessionExpired();
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
        prefs.setSessionExpired(false);
        ApiClient.get(appCtx).notifyConfigChanged();
        CloudRepository.get(appCtx).notifyConfigChanged();
        AvatarManager.get(appCtx).clearLocal(outgoing);
        ServerInfoManager.get(appCtx).clear();
        PlaylistRepository.get(appCtx).clear();
    }
}
