package com.jotapeveloper.fision.cloud;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * HTTP client singleton. Rebuilt whenever the URL or token changes in Settings
 * (notifyConfigChanged). High timeouts due to heavy uploads:
 * connect 30s, write 30 min, read 30 min (the job can take as long as needed,
 * but the regular response body does not wait for processing — that is polled).
 */
public class ApiClient {

    private static final String TAG = "ApiClient";

    private static ApiClient INSTANCE;
    private final Context appCtx;
    private FisionApi api;
    private OkHttpClient http;
    private String currentBase = "";
    private String currentToken = "";

    public static synchronized ApiClient get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new ApiClient(ctx.getApplicationContext());
        return INSTANCE;
    }

    private ApiClient(Context ctx) {
        this.appCtx = ctx;
        rebuild();
    }

    /** Call after changes in SettingsActivity to pick up the new URL/token. */
    public synchronized void notifyConfigChanged() { rebuild(); }

    public synchronized FisionApi api() { return api; }

    /** Exposes the underlying OkHttpClient so callers can evict cached
     *  connections after a network drop (Tailscale up/down leaves stale
     *  sockets in the pool that would otherwise block the reconnect ping). */
    public synchronized OkHttpClient okHttpClient() { return http; }

    /** Drops every idle connection from the pool. Called when a ping fails so
     *  the next attempt opens a fresh socket instead of reusing a dead one. */
    public synchronized void evictConnections() {
        if (http != null) http.connectionPool().evictAll();
    }

    /** Normalized base URL (with trailing "/"). Used to compose absolute URLs
     *  when the server returns relative paths (e.g. cover_url). */
    public synchronized String baseUrl() { return currentBase; }

    public synchronized boolean isConfigured() {
        return ServerPrefs.get(appCtx).isConfigured() && api != null;
    }

    private void rebuild() {
        ServerPrefs prefs = ServerPrefs.get(appCtx);
        String url = prefs.getUrl();
        String token = prefs.getToken();
        currentBase = normalizeBase(url);
        currentToken = token;

        if (currentBase.isEmpty()) {
            api = null;
            return;
        }

        HttpLoggingInterceptor log = new HttpLoggingInterceptor();
        log.setLevel(HttpLoggingInterceptor.Level.BASIC);

        http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.MINUTES)
                .readTimeout(30, TimeUnit.MINUTES)
                .callTimeout(0, TimeUnit.SECONDS)         // no global cap
                .retryOnConnectionFailure(true)
                .addInterceptor(new AuthInterceptor())
                .addInterceptor(log)
                .build();

        try {
            Retrofit r = new Retrofit.Builder()
                    .baseUrl(currentBase)
                    .client(http)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
            api = r.create(FisionApi.class);
        } catch (Throwable t) {
            Log.w(TAG, "Invalid URL: " + currentBase, t);
            api = null;
        }
    }

    /** True when the URL would carry the password and token in clear text
     *  over a network others can read: plain http outside loopback and
     *  Tailscale (whose tunnel is already encrypted). */
    public static boolean isCleartextExposed(String url) {
        okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(normalizeBase(url));
        if (u == null || u.isHttps()) return false;
        String host = u.host().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("localhost") || host.startsWith("127.") || host.equals("::1")) return false;
        if (host.endsWith(".ts.net") || host.startsWith("fd7a:115c:a1e0:")) return false;
        String[] o = host.split("\\.");
        if (o.length == 4 && o[0].equals("100")) {
            // Tailscale addresses: 100.64.0.0/10.
            try {
                int b = Integer.parseInt(o[1]);
                if (b >= 64 && b <= 127) return false;
            } catch (NumberFormatException ignored) {}
        }
        return true;
    }

    private static String normalizeBase(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        if (!u.endsWith("/")) u = u + "/";
        return u;
    }

    /** Injects Bearer when a token is present. Otherwise the request goes without auth
     *  (the server allows it in open mode). A 401 on an authenticated request
     *  means the session is over (expired or revoked): AuthManager drops the
     *  token instead of resending it. On /login, 401 is a wrong password. */
    private final class AuthInterceptor implements Interceptor {
        @Override public Response intercept(Chain chain) throws java.io.IOException {
            Request original = chain.request();
            String token;
            String base;
            synchronized (ApiClient.this) { token = currentToken; base = currentBase; }
            if (token == null || token.isEmpty() || !isOwnServer(original.url(), base)) {
                return chain.proceed(original);
            }
            Request authed = original.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .build();
            Response resp = chain.proceed(authed);
            if (resp.code() == 401 && !isLoginPath(original)) {
                AuthManager.get(appCtx).onUnauthorized(token);
            }
            return resp;
        }

        /** Absolute URLs from the server (e.g. a cover_url) may point elsewhere:
         *  the token only goes to the configured scheme, host and port. */
        private boolean isOwnServer(okhttp3.HttpUrl url, String base) {
            okhttp3.HttpUrl own = okhttp3.HttpUrl.parse(base);
            return own != null
                    && own.scheme().equals(url.scheme())
                    && own.host().equalsIgnoreCase(url.host())
                    && own.port() == url.port();
        }

        private boolean isLoginPath(Request r) {
            java.util.List<String> seg = r.url().pathSegments();
            return !seg.isEmpty() && "login".equals(seg.get(seg.size() - 1));
        }
    }
}
