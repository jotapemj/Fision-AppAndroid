package com.jotapeveloper.fision.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Cache and refresh facade for GET /server/info. The response only changes
 * across server reboots, so the client fetches it once after login and stores
 * the fields in SharedPreferences. Any UI that depends on the GPU flag
 * (Settings row, Import warning, submitted config_json) reads it from here.
 *
 * A listener bus notifies the toolbar/import/settings as soon as a fresh
 * fetch lands so screens can enable their controls without polling.
 */
public class ServerInfoManager {

    private static final String TAG = "ServerInfoManager";

    private static final String PREFS = "fision_server_info";
    private static final String KEY_LOADED  = "loaded";
    private static final String KEY_GPU     = "gpu";
    private static final String KEY_NAME    = "gpu_name";
    private static final String KEY_VRAM_MB = "vram_total_mb";
    private static final String KEY_DRIVER  = "driver";

    public interface Listener { void onServerInfoChanged(); }

    private static ServerInfoManager INSTANCE;
    public static synchronized ServerInfoManager get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new ServerInfoManager(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final Context appCtx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean inFlight = false;

    private ServerInfoManager(Context ctx) {
        this.appCtx = ctx;
        this.prefs  = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) {
                try { l.onServerInfoChanged(); }
                catch (Throwable t) { Log.w(TAG, "listener threw", t); }
            }
        });
    }

    /** True when a successful fetch has been persisted at least once. */
    public boolean isLoaded()   { return prefs.getBoolean(KEY_LOADED, false); }
    public boolean hasGpu()     { return prefs.getBoolean(KEY_GPU, false); }
    public String  getGpuName() { return prefs.getString(KEY_NAME, ""); }
    public int     getVramMb()  { return prefs.getInt(KEY_VRAM_MB, 0); }
    public String  getDriver()  { return prefs.getString(KEY_DRIVER, ""); }

    /** Wipes the cache on logout / server switch so the next session refetches. */
    public void clear() {
        prefs.edit().clear().apply();
        notifyChanged();
    }

    /** Fires a GET /server/info in the background. When it lands, the fields
     *  are persisted and every listener is notified on the main thread. */
    public void refresh() { refresh(null); }

    public void refresh(Runnable onDone) {
        if (inFlight) return;
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) {
            if (onDone != null) main.post(onDone);
            return;
        }
        inFlight = true;
        api.getServerInfo().enqueue(new Callback<ServerInfoResponse>() {
            @Override public void onResponse(Call<ServerInfoResponse> c,
                                             Response<ServerInfoResponse> r) {
                inFlight = false;
                if (r.isSuccessful() && r.body() != null) {
                    ServerInfoResponse info = r.body();
                    prefs.edit()
                            .putBoolean(KEY_LOADED, true)
                            .putBoolean(KEY_GPU, info.gpu)
                            .putString(KEY_NAME, info.gpuName == null ? "" : info.gpuName)
                            .putInt(KEY_VRAM_MB, info.vramTotalMb)
                            .putString(KEY_DRIVER, info.driver == null ? "" : info.driver)
                            .apply();
                    notifyChanged();
                } else {
                    Log.w(TAG, "getServerInfo HTTP " + r.code());
                }
                if (onDone != null) main.post(onDone);
            }
            @Override public void onFailure(Call<ServerInfoResponse> c, Throwable t) {
                inFlight = false;
                Log.w(TAG, "getServerInfo network failure", t);
                if (onDone != null) main.post(onDone);
            }
        });
    }
}
