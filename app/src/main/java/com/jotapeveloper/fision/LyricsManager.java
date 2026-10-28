package com.jotapeveloper.fision;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.jotapeveloper.fision.cloud.ApiClient;
import com.jotapeveloper.fision.cloud.FisionApi;
import com.jotapeveloper.fision.cloud.JobStatusResponse;
import com.jotapeveloper.fision.cloud.JobSubmitResponse;
import com.jotapeveloper.fision.cloud.LyricsRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Map;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Coordinates the opt-in lyrics workflow: enqueues a job on the server,
 * polls its state, downloads the resulting lyrics.json to the song folder,
 * and exposes the parsed Lyrics via a listener. Scope is per-activity —
 * no persistence across process death. Ongoing jobs continue on the server
 * even if the activity dies; a follow-up open just tries GET first.
 */
public class LyricsManager {

    public enum State {
        IDLE,          // no job running for the current hash
        ENQUEUED,      // POST accepted, waiting for server queue / GPU
        RUNNING,       // server is transcribing
        DOWNLOADING,   // server done, client fetching lyrics.json
        DONE,          // lyrics.json parsed and available
        ERROR,         // server or transport failure
        CANCELLED      // user aborted the job
    }

    public interface Listener {
        void onLyricsStateChanged(State state, String errorMsg);
        void onLyricsLoaded(Lyrics lyrics);
    }

    public static final String LYRICS_FILE_NAME = "lyrics.json";

    private static final long POLL_INTERVAL_MS = 2500L;

    private final Context ctx;
    private final Handler main;
    private final Handler poll;
    private final Gson gson = new Gson();

    private FisionApi api() { return ApiClient.get(ctx).api(); }

    private Listener listener;
    private String currentHash;
    private File currentFolder;
    private String currentJobId;
    private Call<?> inFlightCall;
    private Runnable pollTask;
    private State state = State.IDLE;
    /** Bumped by release(): late results of a previous song are dropped. */
    private int generation = 0;

    public LyricsManager(Context ctx) {
        this.ctx  = ctx.getApplicationContext();
        this.main = new Handler(Looper.getMainLooper());
        this.poll = new Handler(Looper.getMainLooper());
    }

    public void setListener(Listener l) { this.listener = l; }

    public State getState() { return state; }

    public boolean isActive() {
        return state == State.ENQUEUED || state == State.RUNNING || state == State.DOWNLOADING;
    }

    public static File lyricsFile(File songFolder) {
        return new File(songFolder, LYRICS_FILE_NAME);
    }

    public static boolean hasLocal(File songFolder) {
        File f = lyricsFile(songFolder);
        return f.exists() && f.length() > 0;
    }

    /** Blocking local read + parse; call from a background thread. */
    public Lyrics parseLocal(File songFolder) throws IOException {
        File f = lyricsFile(songFolder);
        try (Reader r = new InputStreamReader(new java.io.FileInputStream(f))) {
            return Lyrics.sanitize(gson.fromJson(r, Lyrics.class));
        } catch (com.google.gson.JsonParseException e) {
            // Malformed file: reported like an unreadable one.
            throw new IOException(e);
        }
    }

    /** POST /library/{hash}/lyrics — enqueues the transcription. language==null
     *  asks for auto-detect. The songFolder is captured now so the eventual
     *  download can write straight to disk without another round-trip. */
    public void startJob(String hash, File songFolder, String language) {
        cancelPolling();
        currentHash   = hash;
        currentFolder = songFolder;
        currentJobId  = null;
        publishState(State.ENQUEUED, null);
        Call<JobSubmitResponse> call = api().submitLyrics(hash, new LyricsRequest(language));
        inFlightCall = call;
        call.enqueue(new Callback<JobSubmitResponse>() {
            @Override public void onResponse(Call<JobSubmitResponse> c, Response<JobSubmitResponse> resp) {
                if (call.isCanceled()) return;
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (resp.code() == 409)      publishState(State.ERROR, "409");
                    else if (resp.code() == 429) publishState(State.ERROR, "429");
                    else                         publishState(State.ERROR, "HTTP " + resp.code());
                    return;
                }
                currentJobId = resp.body().jobId;
                startPolling();
            }
            @Override public void onFailure(Call<JobSubmitResponse> c, Throwable t) {
                if (call.isCanceled()) return;
                publishState(State.ERROR, t.getMessage());
            }
        });
    }

    /** DELETE /jobs/{id} — aborts the current lyrics job. */
    public void cancelJob() {
        cancelPolling();
        if (inFlightCall != null) {
            inFlightCall.cancel();
            inFlightCall = null;
        }
        if (currentJobId != null) {
            final String jobId = currentJobId;
            api().cancelJob(jobId).enqueue(new Callback<Map<String, Object>>() {
                @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) { }
                @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) { }
            });
        }
        publishState(State.CANCELLED, null);
    }

    /** DELETE /library/{hash}/lyrics — clears the server-side JSON so the next
     *  POST can pick a different language. Fire-and-forget. */
    public void deleteRemote(String hash) {
        api().deleteLyrics(hash).enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) { }
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) { }
        });
    }

    /** Reason sent with IDLE when the server has no lyrics for the hash (or
     *  cannot be reached): the caller offers to process them. */
    public static final String NOT_ON_SERVER = "not_on_server";

    /** GET /library/{hash}/lyrics — fetches and caches lyrics the server
     *  already has without a job of this client (song downloaded again,
     *  another device, app closed during the job). 404 or no network end in
     *  IDLE + {@link #NOT_ON_SERVER}. */
    public void tryFetchExisting(String hash, File songFolder) {
        currentHash   = hash;
        currentFolder = songFolder;
        currentJobId  = null;   // no job: a cancel only drops the request
        publishState(State.DOWNLOADING, null);
        downloadInto(hash, songFolder, true);
    }

    private void startPolling() {
        publishState(State.RUNNING, null);
        pollTask = new Runnable() {
            @Override public void run() {
                if (currentJobId == null) return;
                final String jobId = currentJobId;
                api().getJob(jobId).enqueue(new Callback<JobStatusResponse>() {
                    @Override public void onResponse(Call<JobStatusResponse> c, Response<JobStatusResponse> resp) {
                        if (!resp.isSuccessful() || resp.body() == null) {
                            publishState(State.ERROR, "HTTP " + resp.code());
                            return;
                        }
                        String est = resp.body().state;
                        if ("done".equalsIgnoreCase(est)) {
                            cancelPolling();
                            publishState(State.DOWNLOADING, null);
                            downloadInto(currentHash, currentFolder, false);
                        } else if ("error".equalsIgnoreCase(est)) {
                            cancelPolling();
                            // Stable reason; the free text only when the server sent none.
                            String code = resp.body().errorCode;
                            publishState(State.ERROR, code != null ? code : resp.body().error);
                        } else if ("cancelled".equalsIgnoreCase(est)) {
                            cancelPolling();
                            publishState(State.CANCELLED, null);
                        } else {
                            // queued / waiting_gpu / running — keep polling.
                            poll.postDelayed(pollTask, POLL_INTERVAL_MS);
                        }
                    }
                    @Override public void onFailure(Call<JobStatusResponse> c, Throwable t) {
                        // Transient network hiccup — retry rather than error out.
                        poll.postDelayed(pollTask, POLL_INTERVAL_MS);
                    }
                });
            }
        };
        poll.postDelayed(pollTask, POLL_INTERVAL_MS);
    }

    private void cancelPolling() {
        if (pollTask != null) poll.removeCallbacks(pollTask);
        pollTask = null;
    }

    /** [existing]: probe for lyrics made outside a job of this client; a
     *  miss is not an error then (see {@link #tryFetchExisting}). */
    private void downloadInto(String hash, File songFolder, boolean existing) {
        if (songFolder == null) {
            publishState(State.ERROR, "no_folder");
            return;
        }
        final int gen = generation;
        Call<ResponseBody> call = api().downloadLyrics(hash);
        inFlightCall = call;
        call.enqueue(new Callback<ResponseBody>() {
            @Override public void onResponse(Call<ResponseBody> c, Response<ResponseBody> resp) {
                if (call.isCanceled() || gen != generation) return;
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (existing && resp.code() == 404) publishState(State.IDLE, NOT_ON_SERVER);
                    else publishState(State.ERROR, "HTTP " + resp.code());
                    return;
                }
                // Retrofit dispatches this on the main thread. Writing to disk
                // there would trip StrictMode and drain the socket inline, so
                // hand it off to a worker.
                final ResponseBody body = resp.body();
                new Thread(() -> {
                    File out = lyricsFile(songFolder);
                    try (InputStream in = body.byteStream();
                         FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    } catch (Exception e) {
                        publishFrom(gen, State.ERROR, e.getMessage());
                        return;
                    } finally {
                        body.close();
                    }
                    try {
                        Lyrics ly = parseLocal(songFolder);
                        main.post(() -> {
                            // Released meanwhile (song changed): the file stays, no callback.
                            if (gen != generation) return;
                            state = State.DONE;
                            if (listener != null) {
                                listener.onLyricsStateChanged(State.DONE, null);
                                listener.onLyricsLoaded(ly);
                            }
                        });
                    } catch (IOException e) {
                        publishFrom(gen, State.ERROR, e.getMessage());
                    }
                }, "lyrics-dl").start();
            }
            @Override public void onFailure(Call<ResponseBody> c, Throwable t) {
                if (call.isCanceled() || gen != generation) return;
                // Unreachable server while probing: same as having none.
                if (existing) publishState(State.IDLE, NOT_ON_SERVER);
                else publishState(State.ERROR, t.getMessage());
            }
        });
    }

    private void publishState(State s, String errorMsg) {
        state = s;
        final int gen = generation;
        if (listener != null) {
            main.post(() -> {
                if (listener != null && gen == generation) listener.onLyricsStateChanged(s, errorMsg);
            });
        }
    }

    /** publishState from a worker thread, dropped when released meanwhile. */
    private void publishFrom(int gen, State s, String errorMsg) {
        main.post(() -> {
            if (gen == generation) publishState(s, errorMsg);
        });
    }

    /** Cancels pending polling / calls and forgets the current hash, so the
     *  next song starts idle. Safe to call from onDestroy. */
    public void release() {
        cancelPolling();
        if (inFlightCall != null) inFlightCall.cancel();
        inFlightCall = null;
        listener = null;
        currentJobId = null;
        generation++;
        state = State.IDLE;
    }
}
