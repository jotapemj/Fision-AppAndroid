package com.jotapeveloper.fision.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jotapeveloper.fision.SongOrder;
import com.jotapeveloper.fision.SongStorage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Unified state of "my cloud" + jobs in flight. Source of truth consumed by
 * MainActivity to render the merged list (local + processing + downloading +
 * cloud-only).
 *
 * Polling is controlled via start/stop. While MainActivity is onResume(), the
 * polling stays alive: lists /library every LIST_POLL_MS and refreshes each
 * active job every JOB_POLL_MS. When a job reaches "done", the download is
 * kicked off via DownloadManagerLite.
 *
 * Persists in-flight job identifiers (jobId+hash) in SharedPreferences to
 * survive the app being closed. On the next start, the polling resumes.
 */
public class CloudRepository {

    private static final String TAG = "CloudRepository";
    private static final long LIST_POLL_MS = 8_000L;
    private static final long JOB_POLL_MS  = 3_000L;
    private static final long PING_POLL_MS = 10_000L;

    private static final String PREFS = "fision_cloud_state";
    private static final String KEY_PENDING_JOBS = "pending_jobs";    // hash:jobId;hash:jobId;...
    /** Sticky per-hash metadata for items that used to be LOCAL and were deleted, leaving
     *  a CLOUD_ONLY entry. Persisted so that after restarting the app they keep their
     *  original position with title, artist and cover. Key format: "sticky_<field>_<hash>". */
    private static final String KEY_STICKY_PREFIX = "sticky_";
    /** Last known server order date per hash ("order_ts_<hash>"). Keeps the
     *  list order stable from the first render and while offline. */
    private static final String KEY_ORDER_TS_PREFIX = "order_ts_";
    // Server updated_at whose chord files are already on the device.
    private static final String KEY_CHORDS_TS_PREFIX = "chords_ts_";
    private static final String[] CHORD_FILES = {"chords.txt", "chords_basic.txt"};
    private final java.util.Set<String> chordsInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public interface Listener {
        void onCloudChanged();
        default void onConnectivityChanged(boolean reachable) {}
        /** Server aborted the job for a reason a plain retry can fix (see
         *  {@link #isRetryableJobError}). The UI is expected to surface a
         *  retry dialog. Called once per transition into the ERROR state. */
        default void onRetryableJobError(String hash) {}
    }

    /** Stable job failure reasons of the server (`error_code`). */
    public static final String ERR_CANCELLED       = "cancelled";
    public static final String ERR_GPU_BUSY        = "gpu_busy";
    public static final String ERR_TIMEOUT         = "timeout";
    public static final String ERR_PIPELINE_FAILED = "pipeline_failed";
    public static final String ERR_LYRICS_FAILED   = "lyrics_failed";
    public static final String ERR_INPUT_MISSING   = "input_missing";
    public static final String ERR_VOCALS_MISSING  = "vocals_missing";
    public static final String ERR_INTERNAL        = "internal";

    /** True for the server's processing time limit abort. */
    public static boolean isTimeoutError(String code) {
        return ERR_TIMEOUT.equals(code);
    }

    /** Errors solved by reprocessing with the same config: no free VRAM
     *  after the wait, or the processing time limit. */
    public static boolean isRetryableJobError(String code) {
        return ERR_GPU_BUSY.equals(code) || ERR_TIMEOUT.equals(code);
    }

    /** Translated reason for an `error_code`; generic "Error" when unknown. */
    public static String errorText(Context ctx, String code) {
        int res;
        if (code == null) return ctx.getString(com.jotapeveloper.fision.R.string.cloud_state_error);
        switch (code) {
            case ERR_CANCELLED:       res = com.jotapeveloper.fision.R.string.job_err_cancelled; break;
            case ERR_GPU_BUSY:        res = com.jotapeveloper.fision.R.string.job_err_gpu_busy; break;
            case ERR_TIMEOUT:         res = com.jotapeveloper.fision.R.string.job_err_timeout; break;
            case ERR_PIPELINE_FAILED: res = com.jotapeveloper.fision.R.string.job_err_pipeline_failed; break;
            case ERR_LYRICS_FAILED:   res = com.jotapeveloper.fision.R.string.job_err_lyrics_failed; break;
            case ERR_INPUT_MISSING:   res = com.jotapeveloper.fision.R.string.job_err_input_missing; break;
            case ERR_VOCALS_MISSING:  res = com.jotapeveloper.fision.R.string.job_err_vocals_missing; break;
            case ERR_INTERNAL:        res = com.jotapeveloper.fision.R.string.job_err_internal; break;
            default:                  res = com.jotapeveloper.fision.R.string.cloud_state_error; break;
        }
        return ctx.getString(res);
    }

    private static CloudRepository INSTANCE;
    public static synchronized CloudRepository get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new CloudRepository(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final Context appCtx;
    /** State of the active account's library (see {@link SongStorage}); swapped on account change. */
    private volatile SharedPreferences prefs;
    /** Bumped on account change: list responses of the previous session are dropped. */
    private volatile int accountGen;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Told on the main thread when a song's chord files were replaced. */
    public interface ChordsListener { void onChordsUpdated(String hash); }
    private final java.util.Set<ChordsListener> chordsListeners =
            new java.util.concurrent.CopyOnWriteArraySet<>();
    public void addChordsListener(ChordsListener l)    { chordsListeners.add(l); }
    public void removeChordsListener(ChordsListener l) { chordsListeners.remove(l); }
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedHashMap<String, CloudItem> items = new LinkedHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> cachedOrderTs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private boolean reachable = false;
    private boolean polling = false;

    /** In-flight ping tracking. Every ping() gets a monotonically increasing
     *  sequence; callbacks with a stale seq are ignored so a slow "offline"
     *  response cannot overwrite a fresh "online" one. */
    private int pingSeq = 0;
    private Call<Map<String, Object>> pingCall = null;

    private final Runnable listPoll = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            refreshLibrary();
            // Also hydrates jobId+position for jobs owned by other instances
            syncActiveJobs();
            main.postDelayed(this, LIST_POLL_MS);
        }
    };

    private final Runnable jobPoll = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            refreshActiveJobs();
            main.postDelayed(this, JOB_POLL_MS);
        }
    };

    private final Runnable pingPoll = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            ping();
            main.postDelayed(this, PING_POLL_MS);
        }
    };

    private CloudRepository(Context ctx) {
        this.appCtx = ctx;
        this.prefs  = prefsFor(ctx, SongStorage.activeKey(ctx));
        restorePendingJobs();
        restoreStickyMetadata();
        restoreOrderTs();
        sanitizeCoverCache();
    }

    private static SharedPreferences prefsFor(Context ctx, String accountKey) {
        return ctx.getSharedPreferences(PREFS + "_" + accountKey, Context.MODE_PRIVATE);
    }

    /**
     * The active library switched to another account (login, logout, local
     * mode): forgets every item of the previous one and loads the persisted
     * state of the new one. Called before polling restarts.
     */
    public void onAccountChanged() {
        synchronized (this) {
            accountGen++;
            items.clear();
            cachedOrderTs.clear();
            chordsInFlight.clear();
            prefs = prefsFor(appCtx, SongStorage.activeKey(appCtx));
            restorePendingJobs();
            restoreStickyMetadata();
            restoreOrderTs();
        }
        notifyChanged();
    }

    /** Last known server order date of [hash], 0 when never seen. */
    public long getCachedOrderTs(@Nullable String hash) {
        if (hash == null) return 0L;
        Long v = cachedOrderTs.get(hash);
        return v != null ? v : 0L;
    }

    /** Stores [ts] as the order date of [hash] unless one is already known.
     *  Returns the date in effect. A later server date replaces it. */
    public long freezeOrderTs(@NonNull String hash, long ts) {
        if (ts <= 0) return 0L;
        Long prev = cachedOrderTs.putIfAbsent(hash, ts);
        if (prev != null) return prev;
        prefs.edit().putLong(KEY_ORDER_TS_PREFIX + hash, ts).apply();
        return ts;
    }

    private void restoreOrderTs() {
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(KEY_ORDER_TS_PREFIX)) continue;
            Object v = e.getValue();
            if (!(v instanceof Long) || (Long) v <= 0) continue;
            cachedOrderTs.put(key.substring(KEY_ORDER_TS_PREFIX.length()), (Long) v);
        }
    }

    /** Deletes cached cover files that no longer decode as images. Old versions
     *  of fetchCover would happily write a 200-body to disk even when the
     *  bytes were not a valid picture (server error page, empty response),
     *  and mergeLibrary treated the presence of the file as "cover already
     *  cached" so those items were stuck on the fallback image. Runs once at
     *  singleton creation, synchronous so mergeLibrary is guaranteed to see
     *  the fresh state and re-issue fetchCover for the affected items. */
    private void sanitizeCoverCache() {
        java.io.File dir = appCtx.getCacheDir();
        if (dir == null) return;
        java.io.File[] files = dir.listFiles((d, name) ->
                name.startsWith("cover_") && name.endsWith(".png"));
        if (files == null) return;
        android.graphics.BitmapFactory.Options probe =
                new android.graphics.BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        for (java.io.File f : files) {
            probe.outWidth = probe.outHeight = 0;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                Log.w(TAG, "sanitizeCoverCache dropping invalid " + f.getName()
                        + " size=" + f.length());
                f.delete();
            }
        }
    }

    // ---- listeners -----------------------------------------------------
    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void notifyChanged() {
        // ALWAYS on the main thread: breaks direct recursion when a synchronous call
        // mutates items (mergeLocalFolders called from refreshSongList).
        main.post(() -> {
            for (Listener l : listeners) {
                try { l.onCloudChanged(); }
                catch (Throwable t) { Log.w(TAG, "listener.onCloudChanged threw", t); }
            }
        });
    }
    private void notifyConnectivity(boolean ok) {
        if (this.reachable == ok) return;
        this.reachable = ok;
        main.post(() -> {
            for (Listener l : listeners) {
                try { l.onConnectivityChanged(ok); }
                catch (Throwable t) { Log.w(TAG, "listener.onConnectivityChanged threw", t); }
            }
        });
    }
    private void notifyRetryableJobError(String hash) {
        main.post(() -> {
            for (Listener l : listeners) {
                try { l.onRetryableJobError(hash); }
                catch (Throwable t) { Log.w(TAG, "listener.onRetryableJobError threw", t); }
            }
        });
    }

    public boolean isReachable() { return reachable; }

    // ---- UI snapshot ---------------------------------------------------
    public synchronized List<CloudItem> snapshot() {
        return new ArrayList<>(items.values());
    }

    public synchronized CloudItem getByHash(String hash) {
        return items.get(hash);
    }

    // ---- polling on/off ------------------------------------------------
    public void startPolling() {
        if (polling) return;
        // Skip network work while the session is missing: local mode has nothing
        // to poll and unauthenticated pings would produce noise. Reachable stays
        // false so the toolbar renders the no-session icon.
        ServerPrefs sp = ServerPrefs.get(appCtx);
        if (!sp.isLoggedIn()) {
            notifyConnectivity(false);
            return;
        }
        polling = true;
        // On app open, the first sweep syncs the live server queue
        // (jobs submitted from other instances become visible).
        main.post(this::syncActiveJobs);
        main.post(pingPoll);
        main.post(listPoll);
        main.post(jobPoll);
    }

    public void stopPolling() {
        polling = false;
        main.removeCallbacks(listPoll);
        main.removeCallbacks(jobPoll);
        main.removeCallbacks(pingPoll);
    }

    public void notifyConfigChanged() {
        ApiClient.get(appCtx).notifyConfigChanged();
        ServerPrefs sp = ServerPrefs.get(appCtx);
        if (!sp.isLoggedIn()) {
            // Session vanished (logout / switch account): stop polling and mark offline.
            stopPolling();
            notifyConnectivity(false);
            return;
        }
        if (!polling) {
            startPolling();
            return;
        }
        main.post(pingPoll);
        main.post(listPoll);
    }

    // ---- local snapshot: what currently sits under Songs/ --------------
    /**
     * Informs the repo about the local folders currently present in Songs/.
     * MainActivity calls it inside refreshSongList, so notifyChanged is NOT
     * fired here: the caller is already redrawing. Firing here would cause a
     * loop refreshSongList -> mergeLocalFolders -> notify -> refreshSongList.
     */
    public synchronized void mergeLocalFolders(List<String> localFolderNames) {
        // Marks entries as LOCAL_ONLY/LOCAL_AND_CLOUD based on the current items map.
        for (String folder : localFolderNames) {
            CloudItem it = items.get(folder);  // when folder == hash, hit
            if (it != null) {
                it.localFolderName = folder;
                if (it.state == CloudItem.State.CLOUD_ONLY)
                    it.state = CloudItem.State.LOCAL_AND_CLOUD;
            } else {
                // Local folder without server counterpart: LOCAL_ONLY (legacy).
                CloudItem fake = new CloudItem(folder);
                fake.localFolderName = folder;
                fake.displayName = folder;
                fake.state = CloudItem.State.LOCAL_ONLY;
                items.put(folder, fake);
            }
        }
        // Remove LOCAL_ONLY items whose folder no longer exists
        Iterator<Map.Entry<String, CloudItem>> it = items.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CloudItem> e = it.next();
            CloudItem ci = e.getValue();
            if (ci.state == CloudItem.State.LOCAL_ONLY && !localFolderNames.contains(ci.localFolderName)) {
                it.remove();
            }
        }
        // No notifyChanged: the caller (refreshSongList) is already drawing.
    }

    /** After download + unzip: the hash now has local content. */
    public synchronized void onLocalCommit(String hash, String folderName) {
        CloudItem it = items.get(hash);
        if (it == null) {
            it = new CloudItem(hash);
            items.put(hash, it);
        }
        it.localFolderName = folderName;
        it.state = CloudItem.State.LOCAL_AND_CLOUD;
        it.downloadPct = 0;
        // The fresh ZIP already carries the current chords.
        if (it.serverUpdated != null) {
            prefs.edit().putString(KEY_CHORDS_TS_PREFIX + hash, it.serverUpdated).apply();
        }
        it.stickyOrderTs = 0;     // now local, sticky order no longer needed
        clearStickyMetadata(hash);
        notifyChanged();
    }

    // ---- jobs ----------------------------------------------------------
    /** Registers a freshly-submitted job: when cached=true, nothing is queued for polling. */
    public synchronized void onJobSubmitted(JobSubmitResponse r, String displayName) {
        onJobSubmitted(r, displayName, null, null);
    }

    /** Registers a freshly-submitted job + local metadata (title/artist/cover) so the list
     *  can display it while the server processes, without waiting for the final ZIP. */
    public synchronized void onJobSubmitted(JobSubmitResponse r, String displayName,
                                            String artist, String coverPath) {
        if (r == null || !CloudItem.isServerHash(r.hash)) return;
        CloudItem it = items.get(r.hash);
        if (it == null) {
            it = new CloudItem(r.hash);
            items.put(r.hash, it);
        }
        it.submittedAt = System.currentTimeMillis();
        if (displayName != null && !displayName.isEmpty()) it.displayName = displayName;
        if (artist != null && !artist.isEmpty()) it.artist = artist;
        if (coverPath != null) it.coverPath = coverPath;
        // Persist metadata in SharedPreferences so it survives an app restart while
        // the server processes. Without this, closing and reopening showed a spinner
        // with no cover nor title (only the hash). stickyOrderTs=0 → no reorder.
        if (displayName != null || artist != null || coverPath != null) {
            saveStickyMetadata(r.hash, displayName, artist, coverPath, 0L);
        }
        if (r.cached) {
            it.state = CloudItem.State.CLOUD_ONLY;
            it.jobId = null;
        } else {
            it.jobId = r.jobId;
            it.state = "running".equalsIgnoreCase(r.state)
                    ? CloudItem.State.RUNNING : CloudItem.State.QUEUED;
            savePendingJobs();
        }
        notifyChanged();
    }

    // ---- download progress ---------------------------------------------
    public synchronized void onDownloadProgress(String hash, long done, long total) {
        CloudItem it = items.get(hash);
        if (it == null) return;
        it.state = CloudItem.State.DOWNLOADING;
        it.downloadedBytes = done;
        it.totalBytes = total;
        it.downloadPct = total > 0 ? (int) Math.min(100, (done * 100L) / total) : 0;
        notifyChanged();
    }

    public synchronized void onDownloadStarted(String hash) {
        CloudItem it = items.get(hash);
        if (it == null) return;
        it.state = CloudItem.State.DOWNLOADING;
        it.downloadPct = 0;
        notifyChanged();
    }

    public synchronized void onDownloadFailed(String hash, String msg) {
        CloudItem it = items.get(hash);
        if (it == null) return;
        it.state = CloudItem.State.CLOUD_ONLY;
        it.errorMessage = msg;
        notifyChanged();
    }

    /** After a local delete: the hash still lives on the server (CLOUD_ONLY). */
    public synchronized void onLocalDeleted(String folderOrHash) {
        CloudItem it = items.get(folderOrHash);
        if (it == null) return;
        if (it.state == CloudItem.State.LOCAL_ONLY) {
            items.remove(folderOrHash);
        } else {
            it.state = CloudItem.State.CLOUD_ONLY;
            it.localFolderName = null;
        }
        notifyChanged();
    }

    /** Re-submits a job that failed with a retryable error (GPU busy, time
     *  limit) by calling POST /library/{hash}/reprocess with the same config
     *  that produced it. The server preserves the input file so nothing has
     *  to be re-uploaded. */
    public void retryJob(String hash) {
        String cfgJson;
        synchronized (this) {
            CloudItem it = items.get(hash);
            if (it == null || it.config == null) return;
            cfgJson = new com.google.gson.Gson().toJson(it.config);
        }
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        api.reprocess(hash, cfgJson).enqueue(new Callback<JobSubmitResponse>() {
            @Override public void onResponse(Call<JobSubmitResponse> c,
                                             Response<JobSubmitResponse> r) {
                if (!r.isSuccessful() || r.body() == null) return;
                JobSubmitResponse resp = r.body();
                synchronized (CloudRepository.this) {
                    CloudItem ci = items.get(hash);
                    if (ci == null) return;
                    ci.jobId = resp.jobId;
                    ci.state = "running".equalsIgnoreCase(resp.state)
                            ? CloudItem.State.RUNNING : CloudItem.State.QUEUED;
                    ci.errorMessage = null;
                    ci.errorCode = null;
                    savePendingJobs();
                }
                notifyChanged();
            }
            @Override public void onFailure(Call<JobSubmitResponse> c, Throwable t) {
                Log.w(TAG, "retryJob network failure", t);
            }
        });
    }

    /** Cancels a queued job (not yet started): DELETE /jobs/{jobId}. */
    public void cancelQueuedJob(String hash) {
        CloudItem it;
        synchronized (this) { it = items.get(hash); }
        if (it == null || it.jobId == null) return;
        if (it.state != CloudItem.State.QUEUED) return;
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        final String jid = it.jobId;
        api.cancelJob(jid).enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                boolean changed = false;
                synchronized (CloudRepository.this) {
                    CloudItem ci = items.get(hash);
                    if (ci != null) {
                        ci.state = CloudItem.State.CANCELLED;
                        ci.jobId = null;
                        savePendingJobs();
                        changed = true;
                    }
                }
                if (changed) notifyChanged();
            }
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                Log.w(TAG, "cancelJob failed", t);
            }
        });
    }

    /** Permanent cloud deletion (DELETE /library/{h}). */
    /** Outcome of {@link #deleteFromCloud} the UI has to tell. */
    public interface DeleteCallback {
        /** 409: the server could not stop the song's job in time. */
        void onBusy();
    }

    /** DELETE /library/{h}. The server cancels a live job of the song first;
     *  409 when it could not stop it (the song stays). */
    public void deleteFromCloud(String hash, @androidx.annotation.Nullable DeleteCallback cb) {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        api.deleteLibrary(hash).enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                if (r.code() == 409) {
                    if (cb != null) cb.onBusy();
                    return;
                }
                if (!r.isSuccessful() && r.code() != 404) {
                    Log.w(TAG, "deleteLibrary failed: HTTP " + r.code());
                    return;
                }
                synchronized (CloudRepository.this) { items.remove(hash); }
                clearStickyMetadata(hash);
                notifyChanged();
                // The server drops the song from every playlist: counts change.
                PlaylistRepository.get(appCtx).sync();
            }
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                Log.w(TAG, "deleteLibrary failed", t);
            }
        });
    }

    // ---- polling impl --------------------------------------------------
    /**
     * GET /jobs: lists live server jobs with job_id + position. Used to recover
     * jobIds for processes launched by another instance (or by the same app
     * before a reinstall) so /jobs/{id} can be polled afterwards.
     */
    private void syncActiveJobs() {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        final int gen = accountGen;
        api.listActiveJobs().enqueue(new Callback<List<JobStatusResponse>>() {
            @Override public void onResponse(Call<List<JobStatusResponse>> c, Response<List<JobStatusResponse>> r) {
                if (gen != accountGen || !r.isSuccessful() || r.body() == null) return;
                mergeActiveJobs(r.body());
            }
            @Override public void onFailure(Call<List<JobStatusResponse>> c, Throwable t) { /* keep going */ }
        });
    }

    private synchronized void mergeActiveJobs(List<JobStatusResponse> active) {
        boolean dirty = false;
        for (JobStatusResponse js : active) {
            if (js == null || !CloudItem.isServerHash(js.hash)) continue;
            CloudItem it = items.get(js.hash);
            if (it == null) {
                it = new CloudItem(js.hash);
                items.put(js.hash, it);
                dirty = true;
            }
            if (js.filename != null && (it.displayName == null || it.displayName.isEmpty())) {
                it.displayName = js.filename;
                dirty = true;
            }
            if (js.jobId != null && !js.jobId.equals(it.jobId)) {
                it.jobId = js.jobId;
                savePendingJobs();
                dirty = true;
            }
            if (js.position != null && js.position != it.queuePosition) {
                it.queuePosition = js.position;
                dirty = true;
            }
            String est = js.state != null ? js.state.toLowerCase() : "";
            CloudItem.State target = "running".equals(est)     ? CloudItem.State.RUNNING
                                    : "waiting_gpu".equals(est) ? CloudItem.State.WAITING_GPU
                                    : "queued".equals(est)      ? CloudItem.State.QUEUED
                                    : null;
            if (target != null && it.state != target && !it.isLocal()) {
                it.state = target;
                if (target == CloudItem.State.RUNNING
                        || target == CloudItem.State.WAITING_GPU) {
                    it.queuePosition = 0;
                }
                dirty = true;
            }
            // Server-side abort (gpu_busy, timeout, ...). Keep the reason so
            // the UI can offer the retry dialog.
            if ("error".equals(est) && !it.isLocal()) {
                CloudItem.State prev = it.state;
                it.state = CloudItem.State.ERROR;
                it.errorMessage = js.error;
                it.errorCode = js.errorCode;
                if (prev != CloudItem.State.ERROR) dirty = true;
            }
        }
        if (dirty) notifyChanged();
    }

    private void refreshLibrary() {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) { notifyConnectivity(false); return; }
        final int gen = accountGen;
        api.listLibrary().enqueue(new Callback<List<LibraryEntry>>() {
            @Override public void onResponse(Call<List<LibraryEntry>> c, Response<List<LibraryEntry>> r) {
                if (gen != accountGen) return;
                if (!r.isSuccessful() || r.body() == null) { notifyConnectivity(false); return; }
                notifyConnectivity(true);
                mergeLibrary(r.body());
            }
            @Override public void onFailure(Call<List<LibraryEntry>> c, Throwable t) {
                notifyConnectivity(false);
            }
        });
    }

    private synchronized void mergeLibrary(List<LibraryEntry> remote) {
        // Track everything from the server. Anything missing that is not LOCAL_ONLY gets purged.
        java.util.Set<String> seen = new java.util.HashSet<>();
        // Hashes that transitioned from processing to ready in this pass (auto-download)
        java.util.List<String> readyForDownload = new java.util.ArrayList<>();
        // Hashes whose cover needs to be fetched from the server (not cached yet)
        java.util.List<String> coversToFetch = new java.util.ArrayList<>();
        java.util.Map<String, String> chordsToRefresh = new java.util.HashMap<>();
        // Server order dates that changed in this pass, written in one apply().
        SharedPreferences.Editor orderEdit = null;
        for (LibraryEntry e : remote) {
            if (e == null || !CloudItem.isServerHash(e.hash)) continue;
            seen.add(e.hash);
            CloudItem it = items.get(e.hash);
            if (it == null) {
                it = new CloudItem(e.hash);
                items.put(e.hash, it);
            }
            // Before the server fields: a readable sticky title beats a bare filename.
            applyPersistedSticky(it);
            // displayName preference: title from meta.json > filename > hash.
            // When the server sends a readable title, it wins absolutely.
            if (e.title != null && !e.title.isEmpty()) {
                it.displayName = e.title;
            } else if (e.filename != null && !e.filename.isEmpty()) {
                boolean looksLikeFilename = e.filename.matches("(?i).+\\.(mp3|wav|m4a|aac|flac|ogg|opus|aiff?)$");
                boolean haveLegibleSticky = it.displayName != null && !it.displayName.isEmpty()
                        && !it.displayName.equals(it.hash)
                        && !it.displayName.matches("(?i).+\\.(mp3|wav|m4a|aac|flac|ogg|opus|aiff?)$");
                if (!(looksLikeFilename && haveLegibleSticky)) {
                    it.displayName = e.filename;
                }
            }
            if (e.artist != null && !e.artist.isEmpty()) it.artist = e.artist;
            it.serverUpdated = e.updatedAt;
            it.serverCreated = e.createdAt;
            long orderTs = SongOrder.parseIsoTs(it.serverCreated);
            if (orderTs <= 0) orderTs = SongOrder.parseIsoTs(it.serverUpdated);
            if (orderTs > 0 && orderTs != getCachedOrderTs(e.hash)) {
                cachedOrderTs.put(e.hash, orderTs);
                if (orderEdit == null) orderEdit = prefs.edit();
                orderEdit.putLong(KEY_ORDER_TS_PREFIX + e.hash, orderTs);
            }
            // Current server config, kept so the reprocess wizard can prefill without asking again.
            if (e.config != null) it.config = e.config;
            // Server cover_url: when the CloudItem has no local coverPath yet, trigger
            // an async download to cache. Arrives asynchronously and refreshes the UI.
            if (e.coverUrl != null && !e.coverUrl.isEmpty()) {
                it.coverUrl = e.coverUrl;
                boolean needFetch = it.coverPath == null
                        || !new java.io.File(it.coverPath).exists();
                if (needFetch) coversToFetch.add(it.hash);
            }

            String est = e.state != null ? e.state.toLowerCase() : "";
            if ("ready".equals(est)) {
                // With a matching local folder, it is LOCAL_AND_CLOUD; otherwise CLOUD_ONLY.
                // Do not stomp DOWNLOADING (already downloading). RUNNING/QUEUED/WAITING_GPU: server confirms
                // completion, so move the item to CLOUD_ONLY and start the download.
                boolean local = it.localFolderName != null;
                if (it.state != CloudItem.State.DOWNLOADING) {
                    boolean wasInFlight = it.isProcessing();
                    it.state = local ? CloudItem.State.LOCAL_AND_CLOUD : CloudItem.State.CLOUD_ONLY;
                    it.queuePosition = 0;
                    if (wasInFlight) {
                        it.jobId = null;
                        savePendingJobs();
                        // Always download after a completed job, even when a local folder exists:
                        // this way a reprocess brings the new ZIP and replaces the old stems.
                        // DownloadManagerLite already wipes Songs/<hash>/ before unzipping.
                        readyForDownload.add(it.hash);
                    }
                }
            } else if ("processing".equals(est)) {
                // Server thinks it is processing; the job poll refines it to
                // RUNNING/QUEUED/WAITING_GPU.
                if (it.state != CloudItem.State.DOWNLOADING) {
                    it.state = CloudItem.State.RUNNING;
                }
            } else if ("waiting_gpu".equals(est)) {
                if (it.state != CloudItem.State.DOWNLOADING) {
                    it.state = CloudItem.State.WAITING_GPU;
                }
            } else if ("error".equals(est)) {
                it.state = CloudItem.State.ERROR;
            } else if ("cancelled".equals(est)) {
                it.state = CloudItem.State.CANCELLED;
            }
        }
        // Downloaded songs whose server entry changed since their chord files
        // were fetched (e.g. chords regenerated with a new notation): only the
        // small chord files are fetched again, not the whole ZIP.
        for (CloudItem ci : items.values()) {
            boolean playable = ci.state == CloudItem.State.LOCAL_AND_CLOUD
                    || ci.state == CloudItem.State.ERROR;   // failed reprocess, old stems kept
            if (!playable || ci.localFolderName == null) continue;
            if (ci.serverUpdated == null || readyForDownload.contains(ci.hash)) continue;
            if (ci.serverUpdated.equals(prefs.getString(KEY_CHORDS_TS_PREFIX + ci.hash, null))) continue;
            if (chordsInFlight.contains(ci.hash)) continue;
            chordsToRefresh.put(ci.hash, ci.serverUpdated);
        }
        // Purge: items no longer on the server and neither LOCAL_ONLY nor in-flight.
        Iterator<Map.Entry<String, CloudItem>> it = items.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CloudItem> e = it.next();
            CloudItem ci = e.getValue();
            if (ci.state == CloudItem.State.LOCAL_ONLY) continue;
            if (ci.isInFlight()) continue;
            if (!seen.contains(ci.hash)) it.remove();
        }
        if (orderEdit != null) orderEdit.apply();
        notifyChanged();
        // Auto-start the download of items the server reports as "ready" while they
        // were in flight (RUNNING/QUEUED/WAITING_GPU). Done OUTSIDE the lock to avoid blocking.
        for (String h : readyForDownload) {
            DownloadManagerLite.get(appCtx).start(h);
        }
        // Fetch pending covers outside the lock: each one caches into
        // getCacheDir()/cover_<hash>.png and refreshes the UI on completion.
        for (String h : coversToFetch) fetchCover(h);
        for (Map.Entry<String, String> e : chordsToRefresh.entrySet()) {
            refreshChords(e.getKey(), e.getValue());
        }
    }

    /** Fetches only the chord files of a downloaded song (GET
     *  /library/{h}/stems/{file}) and replaces them atomically. The stamp is
     *  saved once the server answered (200 or 404); a network error retries
     *  on a later poll. The player reads the new chords on the next load. */
    private void refreshChords(String hash, String updatedAt) {
        FisionApi api = ApiClient.get(appCtx).api();
        String base = ApiClient.get(appCtx).baseUrl();
        if (api == null || base == null || !CloudItem.isServerHash(hash)) return;
        if (!chordsInFlight.add(hash)) return;
        java.io.File dir = new java.io.File(DownloadManagerLite.songsDir(appCtx), hash);
        new Thread(() -> {
            boolean answered = true;
            boolean replaced = false;
            try {
                for (String name : CHORD_FILES) {
                    Response<okhttp3.ResponseBody> r = api.downloadCover(
                            base + "library/" + hash + "/stems/" + name).execute();
                    try (okhttp3.ResponseBody body = r.body()) {
                        if (r.code() == 404) continue;           // song without that variant
                        if (!r.isSuccessful() || body == null) { answered = false; continue; }
                        if (!dir.isDirectory()) return;           // deleted meanwhile
                        java.io.File tmp = new java.io.File(dir, name + ".tmp");
                        try (java.io.InputStream in = body.byteStream();
                             java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                            byte[] buf = new byte[8 * 1024];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        java.io.File dst = new java.io.File(dir, name);
                        if (!tmp.renameTo(dst)) {
                            dst.delete();
                            if (!tmp.renameTo(dst)) { tmp.delete(); answered = false; continue; }
                        }
                        replaced = true;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "refreshChords failed " + hash, t);
                answered = false;
            } finally {
                if (answered) prefs.edit().putString(KEY_CHORDS_TS_PREFIX + hash, updatedAt).apply();
                chordsInFlight.remove(hash);
                if (replaced) {
                    main.post(() -> { for (ChordsListener l : chordsListeners) l.onChordsUpdated(hash); });
                }
            }
        }, "ChordsRefresh").start();
    }

    /** Async download of the server artcover, stored in cacheDir. */
    private void fetchCover(String hash) {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        CloudItem it;
        synchronized (this) { it = items.get(hash); }
        if (it == null || it.coverUrl == null) return;
        String base = ApiClient.get(appCtx).baseUrl();
        if (base == null) return;
        // Compose the absolute URL: if coverUrl already begins with http(s), use as-is;
        // when relative, concatenate with the ApiClient baseUrl.
        String url;
        if (it.coverUrl.startsWith("http://") || it.coverUrl.startsWith("https://")) {
            url = it.coverUrl;
        } else {
            url = base + (it.coverUrl.startsWith("/") ? it.coverUrl.substring(1) : it.coverUrl);
        }
        final String hashFinal = hash;
        api.downloadCover(url).enqueue(new Callback<okhttp3.ResponseBody>() {
            @Override public void onResponse(Call<okhttp3.ResponseBody> c,
                                             Response<okhttp3.ResponseBody> r) {
                final okhttp3.ResponseBody body = r.body();
                if (!r.isSuccessful() || body == null) {
                    if (body != null) body.close();
                    return;
                }
                // Retrofit dispatches on the main thread by default; run the
                // stream copy off-main to keep StrictMode happy.
                new Thread(() -> {
                    java.io.File dst = new java.io.File(appCtx.getCacheDir(),
                            "cover_" + hashFinal + ".png");
                    try (java.io.InputStream in = body.byteStream();
                         java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } catch (Throwable t) {
                        Log.w(TAG, "fetchCover write failed " + hashFinal, t);
                        return;
                    }
                    // Validate what we just wrote. A 200 response with a body
                    // that is not a decodable image (server error page, empty,
                    // truncated download) would leave a corrupt file cached
                    // forever, because needFetch would be false on subsequent
                    // merges. Drop it and skip setting the coverPath so the
                    // next poll re-attempts the download.
                    android.graphics.BitmapFactory.Options probe =
                            new android.graphics.BitmapFactory.Options();
                    probe.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(dst.getAbsolutePath(), probe);
                    if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                        Log.w(TAG, "fetchCover invalid image " + hashFinal
                                + " size=" + dst.length());
                        dst.delete();
                        return;
                    }
                    synchronized (CloudRepository.this) {
                        CloudItem cur = items.get(hashFinal);
                        if (cur != null) cur.coverPath = dst.getAbsolutePath();
                    }
                    notifyChanged();
                }, "cover-fetch").start();
            }
            @Override public void onFailure(Call<okhttp3.ResponseBody> c, Throwable t) {
                Log.w(TAG, "downloadCover failed " + hashFinal, t);
            }
        });
    }

    private void refreshActiveJobs() {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        List<CloudItem> snap;
        synchronized (this) { snap = new ArrayList<>(items.values()); }
        for (CloudItem it : snap) {
            if (it.jobId == null) continue;
            if (it.state == CloudItem.State.DOWNLOADING) continue;
            pollSingleJob(api, it);
        }
    }

    private void pollSingleJob(FisionApi api, CloudItem ref) {
        final String jid = ref.jobId;
        final String hash = ref.hash;
        api.getJob(jid).enqueue(new Callback<JobStatusResponse>() {
            @Override public void onResponse(Call<JobStatusResponse> c, Response<JobStatusResponse> r) {
                if (!r.isSuccessful() || r.body() == null) return;
                applyJobStatus(hash, r.body());
            }
            @Override public void onFailure(Call<JobStatusResponse> c, Throwable t) { /* keep trying */ }
        });
    }

    private void applyJobStatus(String hash, JobStatusResponse js) {
        boolean trigger = false;
        boolean retryable = false;
        synchronized (this) {
            CloudItem it = items.get(hash);
            if (it == null) return;
            String est = js.state != null ? js.state.toLowerCase() : "";
            // 1-based queue position; 0 when running; others ignored.
            if (js.position != null) it.queuePosition = js.position;
            switch (est) {
                case "queued":
                    it.state = CloudItem.State.QUEUED;
                    break;
                case "running":
                    it.state = CloudItem.State.RUNNING;
                    it.queuePosition = 0;
                    break;
                case "waiting_gpu":
                    it.state = CloudItem.State.WAITING_GPU;
                    it.queuePosition = 0;
                    break;
                case "cancelled":
                    it.state = CloudItem.State.CANCELLED;
                    it.jobId = null;
                    savePendingJobs();
                    break;
                case "error":
                    boolean wasError = it.state == CloudItem.State.ERROR;
                    it.state = CloudItem.State.ERROR;
                    it.errorMessage = js.error;
                    it.errorCode = js.errorCode;
                    it.jobId = null;
                    savePendingJobs();
                    if (!wasError && isRetryableJobError(js.errorCode)) {
                        retryable = true;
                    }
                    break;
                case "done":
                    it.jobId = null;
                    savePendingJobs();
                    it.state = CloudItem.State.CLOUD_ONLY;
                    trigger = true;
                    break;
            }
        }
        notifyChanged();
        if (retryable) notifyRetryableJobError(hash);
        if (trigger) {
            DownloadManagerLite.get(appCtx).start(hash);
        }
    }

    private void ping() {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) { notifyConnectivity(false); return; }
        // Cancel the previous in-flight ping so its late response cannot
        // overwrite the state set by this one.
        if (pingCall != null) {
            try { pingCall.cancel(); } catch (Throwable ignored) {}
        }
        // Evict cached sockets when the last ping said offline: a stale
        // connection (e.g. after Tailscale went down and back up) would
        // otherwise consume the full connectTimeout before failing, blocking
        // the reconnect signal for tens of seconds.
        if (!reachable) ApiClient.get(appCtx).evictConnections();
        final int seq = ++pingSeq;
        // GET /health without token: minimal cost and no auth dependency.
        Call<Map<String, Object>> call = api.health();
        pingCall = call;
        call.enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                if (seq != pingSeq) return;      // stale response
                notifyConnectivity(r.isSuccessful());
            }
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                if (seq != pingSeq) return;      // cancelled or stale
                notifyConnectivity(false);
            }
        });
    }

    // ---- pending jobs persistence -------------------------------------
    private void savePendingJobs() {
        StringBuilder sb = new StringBuilder();
        for (CloudItem it : items.values()) {
            if (it.jobId == null) continue;
            if (sb.length() > 0) sb.append(';');
            // hash:jobId:submittedAt (the timestamp keeps the in-flight order across restarts)
            sb.append(it.hash).append(':').append(it.jobId).append(':').append(it.submittedAt);
        }
        prefs.edit().putString(KEY_PENDING_JOBS, sb.toString()).apply();
    }

    private void restorePendingJobs() {
        String s = prefs.getString(KEY_PENDING_JOBS, "");
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) {
            // "hash:jobId" (older entries) or "hash:jobId:submittedAt"
            String[] f = part.split(":");
            if (f.length < 2 || !CloudItem.isServerHash(f[0])) continue;
            String h = f[0];
            String j = f[1];
            CloudItem it = new CloudItem(h);
            it.jobId = j;
            if (f.length > 2) {
                try { it.submittedAt = Long.parseLong(f[2]); } catch (NumberFormatException ignored) {}
            }
            it.state = CloudItem.State.QUEUED;
            items.put(h, it);
        }
    }

    // ---- sticky metadata persistence (CLOUD_ONLY items that used to be local) ---
    /** Saves title/artist/coverPath/orderTs in SharedPreferences keyed by hash.
     *  Called from MainActivity after a local delete with cloud so the entry survives an app restart. */
    public void saveStickyMetadata(@NonNull String hash, String title, String artist,
                                    String coverPath, long orderTs) {
        android.content.SharedPreferences.Editor e = prefs.edit();
        if (title != null)     e.putString(KEY_STICKY_PREFIX + "title_"  + hash, title);
        if (artist != null)    e.putString(KEY_STICKY_PREFIX + "artist_" + hash, artist);
        if (coverPath != null) e.putString(KEY_STICKY_PREFIX + "cover_"  + hash, coverPath);
        if (orderTs > 0)       e.putLong  (KEY_STICKY_PREFIX + "ts_"     + hash, orderTs);
        e.apply();
        // Also apply to the in-memory CloudItem when present.
        synchronized (this) {
            CloudItem it = items.get(hash);
            if (it != null) {
                if (title != null)     it.displayName = title;
                if (artist != null)    it.artist = artist;
                if (coverPath != null) it.coverPath = coverPath;
                if (orderTs > 0)       it.stickyOrderTs = orderTs;
            }
        }
        notifyChanged();
    }

    /** Clears persisted sticky data for a hash (used after a re-download or a server delete). */
    public void clearStickyMetadata(@NonNull String hash) {
        prefs.edit()
                .remove(KEY_STICKY_PREFIX + "title_"  + hash)
                .remove(KEY_STICKY_PREFIX + "artist_" + hash)
                .remove(KEY_STICKY_PREFIX + "cover_"  + hash)
                .remove(KEY_STICKY_PREFIX + "ts_"     + hash)
                .apply();
        //noinspection ResultOfMethodCallIgnored
        stickyCoverFile(hash).delete();
    }

    /** Kept cover of a song with no local folder (import in progress or
     *  deleted locally). In filesDir: cacheDir can be purged by the system. */
    @NonNull
    public java.io.File stickyCoverFile(@NonNull String hash) {
        java.io.File dir = new java.io.File(appCtx.getFilesDir(), "sticky_covers");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // Only a server hash names the file; anything else stays inside the dir.
        String name = CloudItem.isServerHash(hash) ? hash : "x" + Integer.toHexString(hash.hashCode());
        return new java.io.File(dir, name + ".png");
    }

    /** Fills title, artist and cover the server does not provide (yet) from
     *  the persisted sticky data. Covers in-flight imports, which have no
     *  sticky order date and so are not restored at boot. */
    private void applyPersistedSticky(CloudItem it) {
        if (it.displayName == null || it.displayName.isEmpty()) {
            String t = prefs.getString(KEY_STICKY_PREFIX + "title_" + it.hash, null);
            if (t != null) it.displayName = t;
        }
        if (it.artist == null || it.artist.isEmpty()) {
            String a = prefs.getString(KEY_STICKY_PREFIX + "artist_" + it.hash, null);
            if (a != null) it.artist = a;
        }
        if (it.coverPath == null || !new java.io.File(it.coverPath).exists()) {
            String c = prefs.getString(KEY_STICKY_PREFIX + "cover_" + it.hash, null);
            if (c != null && new java.io.File(c).exists()) it.coverPath = c;
        }
    }

    /** Restores sticky metadata when the repo boots. Creates CloudItems with the info so
     *  the list shows them from the first render, before the server responds. */
    private void restoreStickyMetadata() {
        java.util.Map<String, ?> all = prefs.getAll();
        for (String key : all.keySet()) {
            if (!key.startsWith(KEY_STICKY_PREFIX + "ts_")) continue;
            String hash = key.substring((KEY_STICKY_PREFIX + "ts_").length());
            if (hash.isEmpty()) continue;
            Object v = all.get(key);
            if (!(v instanceof Long)) continue;
            long ts = (Long) v;
            if (ts <= 0) continue;
            CloudItem it = items.get(hash);
            if (it == null) {
                it = new CloudItem(hash);
                items.put(hash, it);
            }
            it.stickyOrderTs = ts;
            String t = prefs.getString(KEY_STICKY_PREFIX + "title_"  + hash, null);
            String a = prefs.getString(KEY_STICKY_PREFIX + "artist_" + hash, null);
            String c = prefs.getString(KEY_STICKY_PREFIX + "cover_"  + hash, null);
            if (t != null) it.displayName = t;
            if (a != null) it.artist = a;
            if (c != null && new java.io.File(c).exists()) it.coverPath = c;
            // Default state until mergeLibrary confirms it: CLOUD_ONLY (assume still on
            // the server; if not, mergeLibrary will remove it during the purge).
            if (it.state == null) it.state = CloudItem.State.CLOUD_ONLY;
        }
    }
}
