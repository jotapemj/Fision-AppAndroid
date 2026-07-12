package com.jotapeveloper.fision.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

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

    public interface Listener {
        void onCloudChanged();
        default void onConnectivityChanged(boolean reachable) {}
    }

    private static CloudRepository INSTANCE;
    public static synchronized CloudRepository get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new CloudRepository(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final Context appCtx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedHashMap<String, CloudItem> items = new LinkedHashMap<>();
    private boolean reachable = false;
    private boolean polling = false;

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
        this.prefs  = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        restorePendingJobs();
        restoreStickyMetadata();
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
        if (polling) {
            main.post(pingPoll);
            main.post(listPoll);
        }
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
        if (r == null || r.hash == null) return;
        CloudItem it = items.get(r.hash);
        if (it == null) {
            it = new CloudItem(r.hash);
            items.put(r.hash, it);
        }
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
            it.state = "running".equalsIgnoreCase(r.estado)
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
    public void deleteFromCloud(String hash) {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        api.deleteLibrary(hash).enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                synchronized (CloudRepository.this) { items.remove(hash); }
                clearStickyMetadata(hash);
                notifyChanged();
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
        api.listActiveJobs().enqueue(new Callback<List<JobStatusResponse>>() {
            @Override public void onResponse(Call<List<JobStatusResponse>> c, Response<List<JobStatusResponse>> r) {
                if (!r.isSuccessful() || r.body() == null) return;
                mergeActiveJobs(r.body());
            }
            @Override public void onFailure(Call<List<JobStatusResponse>> c, Throwable t) { /* keep going */ }
        });
    }

    private synchronized void mergeActiveJobs(List<JobStatusResponse> active) {
        boolean dirty = false;
        for (JobStatusResponse js : active) {
            if (js == null || js.hash == null) continue;
            CloudItem it = items.get(js.hash);
            if (it == null) {
                it = new CloudItem(js.hash);
                items.put(js.hash, it);
                dirty = true;
            }
            if (js.nombre != null && (it.displayName == null || it.displayName.isEmpty())) {
                it.displayName = js.nombre;
                dirty = true;
            }
            if (js.jobId != null && !js.jobId.equals(it.jobId)) {
                it.jobId = js.jobId;
                savePendingJobs();
                dirty = true;
            }
            if (js.posicion != null && js.posicion != it.queuePosition) {
                it.queuePosition = js.posicion;
                dirty = true;
            }
            String est = js.estado != null ? js.estado.toLowerCase() : "";
            CloudItem.State target = "running".equals(est) ? CloudItem.State.RUNNING
                                    : "queued".equals(est) ? CloudItem.State.QUEUED
                                    : null;
            if (target != null && it.state != target && !it.isLocal()) {
                it.state = target;
                if (target == CloudItem.State.RUNNING) it.queuePosition = 0;
                dirty = true;
            }
        }
        if (dirty) notifyChanged();
    }

    private void refreshLibrary() {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) { notifyConnectivity(false); return; }
        api.listLibrary().enqueue(new Callback<List<LibraryEntry>>() {
            @Override public void onResponse(Call<List<LibraryEntry>> c, Response<List<LibraryEntry>> r) {
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
        for (LibraryEntry e : remote) {
            if (e == null || e.hash == null) continue;
            seen.add(e.hash);
            CloudItem it = items.get(e.hash);
            if (it == null) {
                it = new CloudItem(e.hash);
                items.put(e.hash, it);
            }
            // displayName preference: title from meta.json > nombre (filename) > hash.
            // When the server sends a readable title, it wins absolutely.
            if (e.title != null && !e.title.isEmpty()) {
                it.displayName = e.title;
            } else if (e.nombre != null && !e.nombre.isEmpty()) {
                boolean looksLikeFilename = e.nombre.matches("(?i).+\\.(mp3|wav|m4a|aac|flac|ogg|opus|aiff?)$");
                boolean haveLegibleSticky = it.displayName != null && !it.displayName.isEmpty()
                        && !it.displayName.equals(it.hash)
                        && !it.displayName.matches("(?i).+\\.(mp3|wav|m4a|aac|flac|ogg|opus|aiff?)$");
                if (!(looksLikeFilename && haveLegibleSticky)) {
                    it.displayName = e.nombre;
                }
            }
            if (e.artist != null && !e.artist.isEmpty()) it.artist = e.artist;
            // serverUpdated: prefer the new updated_at; fall back to legacy actualizado.
            it.serverUpdated = e.updatedAt != null ? e.updatedAt : e.actualizado;
            it.serverCreated = e.createdAt;
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

            String est = e.estado != null ? e.estado.toLowerCase() : "";
            if ("listo".equals(est)) {
                // With a matching local folder, it is LOCAL_AND_CLOUD; otherwise CLOUD_ONLY.
                // Do not stomp DOWNLOADING (already downloading). RUNNING/QUEUED: server confirms
                // completion, so move the item to CLOUD_ONLY and start the download.
                boolean local = it.localFolderName != null;
                if (it.state != CloudItem.State.DOWNLOADING) {
                    boolean wasInFlight = it.state == CloudItem.State.RUNNING
                                       || it.state == CloudItem.State.QUEUED;
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
            } else if ("procesando".equals(est)) {
                // Server thinks it is processing; the job poll refines it to RUNNING/QUEUED.
                if (it.state != CloudItem.State.DOWNLOADING) {
                    it.state = CloudItem.State.RUNNING;
                }
            } else if ("error".equals(est)) {
                it.state = CloudItem.State.ERROR;
            } else if ("cancelado".equals(est)) {
                it.state = CloudItem.State.CANCELLED;
            }
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
        notifyChanged();
        // Auto-start the download of items the server reports as "listo" while they
        // were in flight (RUNNING/QUEUED). Done OUTSIDE the lock to avoid blocking.
        for (String h : readyForDownload) {
            DownloadManagerLite.get(appCtx).start(h);
        }
        // Fetch pending covers outside the lock: each one caches into
        // getCacheDir()/cover_<hash>.png and refreshes the UI on completion.
        for (String h : coversToFetch) fetchCover(h);
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
                if (!r.isSuccessful() || r.body() == null) return;
                java.io.File dst = new java.io.File(appCtx.getCacheDir(),
                        "cover_" + hashFinal + ".png");
                try (java.io.InputStream in = r.body().byteStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } catch (Throwable ignored) { return; }
                synchronized (CloudRepository.this) {
                    CloudItem cur = items.get(hashFinal);
                    if (cur != null) cur.coverPath = dst.getAbsolutePath();
                }
                notifyChanged();
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
        synchronized (this) {
            CloudItem it = items.get(hash);
            if (it == null) return;
            String est = js.estado != null ? js.estado.toLowerCase() : "";
            // 1-based queue position; 0 when running; others ignored.
            if (js.posicion != null) it.queuePosition = js.posicion;
            switch (est) {
                case "queued":
                    it.state = CloudItem.State.QUEUED;
                    break;
                case "running":
                    it.state = CloudItem.State.RUNNING;
                    it.queuePosition = 0;
                    break;
                case "cancelled":
                    it.state = CloudItem.State.CANCELLED;
                    it.jobId = null;
                    savePendingJobs();
                    break;
                case "error":
                    it.state = CloudItem.State.ERROR;
                    it.errorMessage = js.error;
                    it.jobId = null;
                    savePendingJobs();
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
        if (trigger) {
            DownloadManagerLite.get(appCtx).start(hash);
        }
    }

    private void ping() {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) { notifyConnectivity(false); return; }
        // GET /health without token: minimal cost and no auth dependency.
        api.health().enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                notifyConnectivity(r.isSuccessful());
            }
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
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
            sb.append(it.hash).append(':').append(it.jobId);
        }
        prefs.edit().putString(KEY_PENDING_JOBS, sb.toString()).apply();
    }

    private void restorePendingJobs() {
        String s = prefs.getString(KEY_PENDING_JOBS, "");
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) {
            int idx = part.indexOf(':');
            if (idx <= 0) continue;
            String h = part.substring(0, idx);
            String j = part.substring(idx + 1);
            CloudItem it = new CloudItem(h);
            it.jobId = j;
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
