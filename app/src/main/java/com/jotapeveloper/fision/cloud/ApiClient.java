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

        OkHttpClient http = new OkHttpClient.Builder()
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

    private static String normalizeBase(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        if (!u.endsWith("/")) u = u + "/";
        return u;
    }

    /** Injects Bearer when a token is present. Otherwise the request goes without auth
     *  (the server allows it in open mode). */
    private final class AuthInterceptor implements Interceptor {
        @Override public Response intercept(Chain chain) throws java.io.IOException {
            Request original = chain.request();
            String token;
            synchronized (ApiClient.this) { token = currentToken; }
            if (token == null || token.isEmpty()) return chain.proceed(original);
            Request authed = original.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .build();
            return chain.proceed(authed);
        }
    }
}
