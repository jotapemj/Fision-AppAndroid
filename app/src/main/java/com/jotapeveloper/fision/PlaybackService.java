package com.jotapeveloper.fision;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.session.MediaButtonReceiver;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Foreground service that hosts the audio engine.
 * Survives when activities are destroyed → persistent playback.
 */
public class PlaybackService extends Service {

    private static final String TAG = "PlaybackService";
    private static final String CHANNEL_ID = "MEDIA_PLAYBACK_CHANNEL";
    public  static final int    NOTIF_ID   = 1;

    // === Local binder ===
    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        public PlaybackService getService() { return PlaybackService.this; }
    }

    // === State ===
    private AudioEngine engine;
    private String currentSongPath;
    private String currentSongName;
    private int    currentSongId = -1;
    private long   songDurationMs = 0;
    private boolean isPlaying = false;
    // Metadata cached at load time (display title + artist + cover)
    private SongMetadata currentMetadata = new SongMetadata();

    // Dynamic stem→trackId map. Keys come from StemSpec.ORDER.
    private final java.util.Map<String, Integer> stemTracks = new java.util.LinkedHashMap<>();
    private final java.util.List<String>         stemOrder  = new java.util.ArrayList<>();

    // Asynchronous load (loadSongAsync): decode thread, the generation that
    // invalidates superseded results, and the in-progress flag (main thread).
    private final java.util.concurrent.ExecutorService loadExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private int loadGeneration = 0;
    private boolean loading = false;

    // Metronomes are special (always looping)
    private int metronomeTrack      = -1;
    private int metronomeHalfTrack  = -1;
    private int metronomeDoubleTrack= -1;

    // === Media session + notification ===
    private MediaSessionCompat mediaSession;
    private NotificationCompat.Builder builder;
    private NotificationManagerCompat notificationManager;
    private PlaybackStateCompat.Builder stateBuilder;

    // === Audio focus ===
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    // Remembers whether the pause was caused by a transient loss (to resume on focus regain)
    private boolean wasPlayingBeforeFocusLoss = false;
    // Timestamp of the LOSS_TRANSIENT that set wasPlayingBeforeFocusLoss. A GAIN
    // that arrives later than FOCUS_RESUME_WINDOW_MS is treated as another player
    // yielding focus back to us for an unrelated reason (e.g. Spotify switching
    // songs) and MUST NOT auto-resume us.
    private long focusLossAtMs = 0L;
    private static final long FOCUS_RESUME_WINDOW_MS = 60_000L;
    // Paused with no app UI on screen for this long → the song is released and
    // the service stops, so a car head unit connecting hours later cannot
    // wake Fision with a MEDIA_PLAY key.
    private static final long IDLE_STOP_MS = 10 * 60_000L;
    private final Runnable idleStop = () -> {
        if (!isIdle()) return;
        Log.i(TAG, "Idle in pause with no UI → releasing song and stopping");
        releaseSongAndStop();
    };
    // Cached cover to avoid decoding the PNG multiple times per song
    private android.graphics.Bitmap cachedCoverBitmap = null;

    // === Listeners for UI (mini-bar, etc.) ===
    public interface Listener {
        void onPlaybackChanged();
        /** Fired when the song wrapped around because of the repeat mode.
         *  UI listeners use it to reset beat/chord counters without a full reload. */
        default void onSongLooped() {}
    }
    private final Set<Listener> listeners = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // === Repeat + playlist continuation ===
    public enum RepeatMode { OFF, ALL, ONE }
    private static final String PREFS_SONGS       = "prefs_songs";
    private static final String KEY_REPEAT_MODE   = "repeat_mode";
    private static final String KEY_PLAYLIST_MODE = "playlist_mode";
    private RepeatMode repeatMode  = RepeatMode.OFF;
    private boolean playlistMode   = false;
    /** ONE mode plays the song one extra time; this flag latches after that
     *  extra pass so the following end triggers a stop instead of another loop. */
    private boolean repeatOneUsed  = false;

    public RepeatMode getRepeatMode()   { return repeatMode; }
    public boolean isPlaylistMode()     { return playlistMode; }
    public void setRepeatMode(RepeatMode m) {
        if (m == null) m = RepeatMode.OFF;
        repeatMode = m;
        repeatOneUsed = false;
        getSharedPreferences(PREFS_SONGS, MODE_PRIVATE).edit()
                .putInt(KEY_REPEAT_MODE, m.ordinal()).apply();
    }
    public void setPlaylistMode(boolean enabled) {
        playlistMode = enabled;
        getSharedPreferences(PREFS_SONGS, MODE_PRIVATE).edit()
                .putBoolean(KEY_PLAYLIST_MODE, enabled).apply();
    }
    private void loadRepeatPrefs() {
        android.content.SharedPreferences p = getSharedPreferences(PREFS_SONGS, MODE_PRIVATE);
        int mode = p.getInt(KEY_REPEAT_MODE, 0);
        if (mode < 0 || mode >= RepeatMode.values().length) mode = 0;
        repeatMode   = RepeatMode.values()[mode];
        playlistMode = p.getBoolean(KEY_PLAYLIST_MODE, false);
        repeatOneUsed = false;
    }

    /** Optional hook that lets a foreground Activity (typically PlayerActivity)
     *  intercept skip requests so the in-place song switch runs through its own
     *  UI flow instead of the service recreating the engine underneath. */
    public interface SkipRequestHandler { boolean handleSkip(int delta); }
    private SkipRequestHandler skipHandler;
    public void setSkipRequestHandler(SkipRequestHandler h) { skipHandler = h; }
    public void clearSkipRequestHandler(SkipRequestHandler h) {
        if (skipHandler == h) skipHandler = null;
    }

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    private void notifyListeners() {
        mainHandler.post(() -> { for (Listener l : new HashSet<>(listeners)) l.onPlaybackChanged(); });
    }

    // === Lifecycle ===
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        notificationManager = NotificationManagerCompat.from(this);
        setupMediaSession();
        setupAudioFocus();
        loadRepeatPrefs();
        FisionApplication.setForegroundListener(fg -> mainHandler.post(this::reevaluateIdleStop));
    }

    /** Song loaded, not playing (and not waiting to resume after a call),
     *  with no app activity on screen. */
    private boolean isIdle() {
        return currentSongPath != null && !isPlaying && !wasPlayingBeforeFocusLoss
                && !FisionApplication.isInForeground();
    }

    /** Arms the idle stop timer while idle; disarms it otherwise. */
    private void reevaluateIdleStop() {
        mainHandler.removeCallbacks(idleStop);
        if (isIdle()) mainHandler.postDelayed(idleStop, IDLE_STOP_MS);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 12+ requires startForeground within 5s of startForegroundService no matter what.
        // When loadSong failed (missing files) it was never called and the app crashed with
        // ForegroundServiceDidNotStartInTimeException. Now the promise is always kept.
        startForegroundWithNotif(PlaybackStateCompat.STATE_NONE);
        // Media buttons (headset, notification, bluetooth)
        if (intent != null) {
            MediaButtonReceiver.handleIntent(mediaSession, intent);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        FisionApplication.setForegroundListener(null);
        mainHandler.removeCallbacks(idleStop);
        unregisterNoisyReceiver();
        // A decode still running finishes on its own; its result is discarded
        // (and its engine disposed) because the generation no longer matches.
        loadGeneration++;
        loading = false;
        loadExecutor.shutdown();
        settingsExecutor.shutdown();
        releaseInternal();
        if (mediaSession != null) { mediaSession.release(); mediaSession = null; }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The user swiped the app from recents → release everything and kill the process
        super.onTaskRemoved(rootIntent);
        releaseInternal();
        stopForeground(true);
        if (notificationManager != null) notificationManager.cancel(NOTIF_ID);
        stopSelf();
        // Killing the process guarantees an immediate audio cut
        // (Android does not always call onDestroy on time when swiping from recents)
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // === Getters ===
    public AudioEngine getEngine()            { return engine; }
    public boolean isSongLoaded()             { return engine != null && currentSongPath != null; }
    public String  getCurrentSongName()       { return currentSongName; }
    public String  getCurrentSongPath()       { return currentSongPath; }
    public int     getCurrentSongId()         { return currentSongId; }
    public SongMetadata getCurrentMetadata()  { return currentMetadata; }
    /** Display title: metadata > server-side displayName > folder name.
     *  When the local metadata.txt is missing (server pipeline dropped it),
     *  fall back to the title stored server-side in CloudRepository so the
     *  media session / notification / lock-screen do not surface the hash. */
    public String  getDisplayTitle() {
        if (currentMetadata != null && currentMetadata.displayTitle != null
                && !currentMetadata.displayTitle.isEmpty()) {
            return currentMetadata.displayTitle;
        }
        String cloudTitle = resolveCloudTitle();
        if (cloudTitle != null) return cloudTitle;
        return currentSongName;
    }

    /** Server-side artist fallback for the media session / notification when
     *  the local metadata is missing. Returns null if nothing is known. */
    public String getResolvedArtist() {
        if (currentMetadata != null && currentMetadata.artist != null
                && !currentMetadata.artist.isEmpty()) {
            return currentMetadata.artist;
        }
        com.jotapeveloper.fision.cloud.CloudItem ci = lookupCloudItem();
        if (ci != null && ci.artist != null && !ci.artist.isEmpty()) return ci.artist;
        return null;
    }

    private String resolveCloudTitle() {
        com.jotapeveloper.fision.cloud.CloudItem ci = lookupCloudItem();
        if (ci != null && ci.displayName != null && !ci.displayName.isEmpty()) {
            return ci.displayName;
        }
        return null;
    }

    private com.jotapeveloper.fision.cloud.CloudItem lookupCloudItem() {
        if (currentSongName == null) return null;
        try {
            return com.jotapeveloper.fision.cloud.CloudRepository.get(this)
                    .getByHash(currentSongName);
        } catch (Throwable ignored) { return null; }
    }
    public boolean isPlaying()                { return isPlaying; }
    public long    getCurrentPositionMs()     { return engine != null ? engine.getCurrentPositionMs() : 0; }
    public long    getDurationMs()            { return songDurationMs; }

    // Dynamic stem access
    public java.util.List<String> getStemOrder()  { return stemOrder; }
    public Integer getStemTrack(String stem)      { return stemTracks.get(stem); }
    public boolean hasStem(String stem)           { return stemTracks.containsKey(stem); }

    // Compat wrappers for the legacy code path (4 stems). Return -1 when the stem is missing.
    private int trackOrMinus1(String stem) {
        Integer t = stemTracks.get(stem); return t == null ? -1 : t;
    }
    public int getBassTrack()   { return trackOrMinus1("bass"); }
    public int getDrumsTrack()  { return trackOrMinus1("drums"); }
    public int getOtherTrack()  { return trackOrMinus1("other"); }
    public int getVocalsTrack() { return trackOrMinus1("vocals"); }

    public int getMetronomeTrack()       { return metronomeTrack; }
    public int getMetronomeHalfTrack()   { return metronomeHalfTrack; }
    public int getMetronomeDoubleTrack() { return metronomeDoubleTrack; }

    public boolean isCurrentSong(String path) {
        return currentSongPath != null && currentSongPath.equals(path);
    }

    /**
     * Loads the song. If it is already the current one, no reload happens.
     * @return true when the song is loaded (or already was).
     */
    public boolean loadSong(String songPath, String songName, int songId) {
        if (isCurrentSong(songPath) && engine != null) {
            currentSongName = songName;
            currentSongId   = songId;
            return true;
        }
        // A synchronous load supersedes any asynchronous one still decoding.
        loadGeneration++;
        loading = false;

        // Release the previous one
        releaseInternal();

        DecodedSong song = decodeSong(songPath);
        if (song == null) return false;
        commitSong(song, songName, songId);
        return true;
    }

    /** True while {@link #loadSongAsync} is decoding (engine is still null). */
    public boolean isLoading() { return loading; }

    /**
     * Same as {@link #loadSong} with the decode on a background thread, so the
     * caller's UI keeps animating. The previous song is released at once; the
     * engine stays null until the result is committed on the main thread, so
     * nothing can start playback halfway. [done] runs on the main thread with
     * the result; it is not called when a newer load supersedes this one.
     */
    public void loadSongAsync(String songPath, String songName, int songId,
                              java.util.function.Consumer<Boolean> done) {
        if (isCurrentSong(songPath) && engine != null) {
            currentSongName = songName;
            currentSongId   = songId;
            done.accept(true);
            return;
        }
        final int generation = ++loadGeneration;
        loading = true;
        releaseInternal();
        loadExecutor.execute(() -> {
            DecodedSong song = decodeSong(songPath);
            mainHandler.post(() -> {
                if (generation != loadGeneration) {
                    if (song != null) song.engine.dispose();
                    return;
                }
                loading = false;
                if (song == null) {
                    done.accept(false);
                    return;
                }
                commitSong(song, songName, songId);
                done.accept(true);
            });
        });
    }

    /** Result of {@link #decodeSong}: built off the main thread, applied by
     *  {@link #commitSong} on it. */
    private static final class DecodedSong {
        AudioEngine engine;
        final java.util.Map<String, Integer> stems = new java.util.HashMap<>();
        int metronome = -1, metronomeHalf = -1, metronomeDouble = -1;
        String path;
        SongMetadata metadata;
    }

    /**
     * Builds a new engine for the song and decodes its stems in parallel.
     * Touches no service state, so it is safe on a background thread. Returns
     * null (engine disposed) when the folder is invalid or the minimum stems
     * are missing.
     */
    @androidx.annotation.Nullable
    private DecodedSong decodeSong(String songPath) {
        File folder = new File(songPath);
        if (!folder.isDirectory()) {
            Log.e(TAG, "Invalid song folder: " + songPath);
            return null;
        }

        // Enumerate with a direct File.listFiles (more reliable than DocumentFile for paths
        // with spaces, dots or accented characters — the URI escaping was flaky).
        File[] files = folder.listFiles();
        if (files == null) {
            Log.e(TAG, "listFiles returned null: " + songPath);
            return null;
        }
        Log.i(TAG, "loadSong scanning " + files.length + " files in " + folder.getName());

        final DecodedSong song = new DecodedSong();
        song.path = songPath;
        final AudioEngine engine = new AudioEngine();
        song.engine = engine;
        final java.util.Map<String, Integer> stemTracks = song.stems;
        // HRTF: load the HRIR bank when the asset exists. It is propagated to each Track
        // in the subsequent addTrack. When the file is missing, the engine falls back to ITD/ILD.
        try {
            engine.initHrtf(getAssets(), "hrtf/hrtf_horizontal.bin");
        } catch (Throwable t) {
            Log.w(TAG, "HRTF init failed", t);
        }

        // Pre-filter valid audio files once so the parallel decode pool knows the
        // amount of work up front and can size itself accordingly.
        java.util.List<File> workFiles = new java.util.ArrayList<>();
        for (File f : files) {
            if (!f.isFile()) continue;
            String rawName = f.getName();
            if (rawName == null) continue;
            String name = rawName.toLowerCase(java.util.Locale.ROOT).trim();
            if (name.endsWith(".wav") || name.endsWith(".flac")) workFiles.add(f);
        }

        // Adaptive worker count: half of the available cores, capped at 4 to
        // avoid saturating slower storage on low-end devices while still letting
        // flagships decode 4 stems in parallel. Never more workers than files.
        int cores = Runtime.getRuntime().availableProcessors();
        int workers = Math.max(1, Math.min(4, Math.min(workFiles.size(), cores / 2)));
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(workers);

        for (File f : workFiles) {
            pool.submit(() -> {
                String rawName = f.getName();
                String name = rawName.toLowerCase(java.util.Locale.ROOT).trim();
                boolean isFlac = name.endsWith(".flac");
                int extLen = isFlac ? 5 : 4;
                String bare = name.substring(0, name.length() - extLen);
                String absPath = f.getAbsolutePath();
                switch (bare) {
                    case "metronome": {
                        int id = engine.addTrack(absPath, true);
                        if (id >= 0) { engine.setVolume(id, 0f, 0f); song.metronome = id; }
                        return;
                    }
                    case "metronome_half": {
                        int id = engine.addTrack(absPath, true);
                        if (id >= 0) { engine.setVolume(id, 0f, 0f); song.metronomeHalf = id; }
                        return;
                    }
                    case "metronome_double": {
                        int id = engine.addTrack(absPath, true);
                        if (id >= 0) { engine.setVolume(id, 0f, 0f); song.metronomeDouble = id; }
                        return;
                    }
                }
                String stem = bare;
                if (!StemSpec.isKnown(stem)) {
                    Log.w(TAG, "  ignored (not in StemSpec catalog): " + rawName);
                    return;
                }
                int tid = engine.addTrack(absPath, false);
                if (tid < 0) {
                    Log.e(TAG, "  addTrack rejected: " + rawName);
                    return;
                }
                engine.setVolume(tid, 0.75f, 0.75f);
                synchronized (stemTracks) {
                    stemTracks.put(stem, tid);
                }
            });
        }
        pool.shutdown();
        try {
            // 60 s is comfortably above the worst-case decode budget for even
            // very long songs on slow storage; anything longer would already be
            // a user-visible timeout that the caller should surface.
            if (!pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.e(TAG, "loadSong decode timed out");
                pool.shutdownNow();
                engine.dispose();
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            engine.dispose();
            return null;
        }

        Log.i(TAG, "loadSong tracks: stems=" + stemTracks + " metronome=" + song.metronome
                + " metronomeHalf=" + song.metronomeHalf + " metronomeDouble=" + song.metronomeDouble);

        // Validate: at least vocals + (other or instrumental) are required
        if (!stemTracks.containsKey("vocals")
                || (!stemTracks.containsKey("other") && !stemTracks.containsKey("instrumental"))) {
            Log.e(TAG, "Minimum stems not found. Detected=" + stemTracks.keySet()
                    + ". Expected at least 'vocals' + ('other' or 'instrumental').");
            engine.dispose();
            return null;
        }
        // Optional metadata from info.txt + artcover.png
        song.metadata = SongMetadata.read(folder);
        return song;
    }

    /** Applies a decoded song to the service state (main thread). */
    private void commitSong(DecodedSong song, String songName, int songId) {
        engine = song.engine;
        stemTracks.clear();
        stemTracks.putAll(song.stems);
        metronomeTrack       = song.metronome;
        metronomeHalfTrack   = song.metronomeHalf;
        metronomeDoubleTrack = song.metronomeDouble;

        // Sort by StemSpec.ORDER
        stemOrder.clear();
        stemOrder.addAll(stemTracks.keySet());
        java.util.Collections.sort(stemOrder,
                (a, b) -> Integer.compare(StemSpec.orderIndex(a), StemSpec.orderIndex(b)));

        currentSongPath = song.path;
        currentSongName = songName;
        currentSongId   = songId;
        songDurationMs  = engine.getDurationMs();
        // Loading a new song clears the ONE-mode latch so the extra repeat
        // pass runs on the new song too.
        repeatOneUsed   = false;
        currentMetadata = song.metadata;
        // Invalidate the cover cache when switching songs (do not recycle: the previous
        // MediaSession may still reference it for a few ms — leave it to the GC).
        cachedCoverBitmap = null;

        updateMediaMetadata(getDisplayTitle(), songDurationMs);
        notifyListeners();
        reevaluateIdleStop();
    }

    // === Controls ===
    public void play() {
        if (engine == null) return;
        if (audioManager != null && focusRequest != null) {
            audioManager.requestAudioFocus(focusRequest);
        }
        // Re-claim the media session: a persistent pause deactivates it so the
        // system stops routing MEDIA_PLAY key events to us; we own playback
        // again from here.
        if (mediaSession != null && !mediaSession.isActive()) {
            mediaSession.setActive(true);
        }
        registerNoisyReceiver();
        if (engine.getCurrentPositionMs() == 0) engine.start();
        else                                    engine.resume();
        isPlaying = true;
        startForegroundWithNotif(PlaybackStateCompat.STATE_PLAYING);
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        notifyListeners();
        // Start the end-of-song watcher: pause+seek0 needs to happen even when no
        // Activity is alive (previously the detection lived in PlayerActivity.timer
        // and the mini-player got stuck with the pause icon when PlayerActivity died first).
        startEndOfSongWatcher();
        reevaluateIdleStop();
    }

    /** User-initiated / end-of-song pause: releases the media session so the
     *  system stops treating us as the active player. External MEDIA_PLAY
     *  events (Bluetooth head units after connect, car head unit scanning
     *  active sessions) no longer wake the service on their own. */
    public void pause() {
        pauseInternal(/*transientLoss=*/ false);
    }

    /** Focus-loss-transient pause: keeps the media session active so we can
     *  auto-resume when the interruption ends (call, alarm). Any GAIN that
     *  arrives past FOCUS_RESUME_WINDOW_MS is still discarded by the focus
     *  listener even though the session stays alive. */
    private void pauseForFocusLoss() {
        pauseInternal(/*transientLoss=*/ true);
    }

    private void pauseInternal(boolean transientLoss) {
        if (engine == null) return;
        stopEndOfSongWatcher();
        engine.pause();
        unregisterNoisyReceiver();
        isPlaying = false;
        if (!transientLoss && mediaSession != null && mediaSession.isActive()) {
            mediaSession.setActive(false);
        }
        // Stay in foreground with the paused notification. Leaving foreground
        // during pause let the system reclaim the service after a few minutes,
        // which broke tap-to-resume from the mini player.
        startForegroundWithNotif(PlaybackStateCompat.STATE_PAUSED);
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
        notifyListeners();
        reevaluateIdleStop();
    }

    public void seekTo(long ms) {
        if (engine != null) engine.seekTo(ms);
    }

    /** Loads the next song (in the same folder as the current one) and starts
     *  playback. Triggered by Bluetooth SKIP_TO_NEXT and by the notification. */
    public void skipToNext()     { dispatchSkip(+1); }

    /** Loads the previous song. Applies the common "rewind to 00:00 when more
     *  than 5s in" rule before falling back to loading the previous song. */
    public void skipToPrevious() {
        if (engine != null && engine.getCurrentPositionMs() > REWIND_THRESHOLD_MS) {
            seekTo(0);
            updatePlaybackState(isPlaying
                    ? PlaybackStateCompat.STATE_PLAYING
                    : PlaybackStateCompat.STATE_PAUSED);
            return;
        }
        dispatchSkip(-1);
    }

    /** Position threshold that separates "restart current song" from
     *  "go to previous song" for the previous action. */
    public static final long REWIND_THRESHOLD_MS = 5000;

    /** Runs the skip on the main thread. If a foreground Activity registered a
     *  handler, the request goes through it; otherwise the service performs the
     *  in-service load. */
    private void dispatchSkip(int delta) {
        mainHandler.post(() -> {
            SkipRequestHandler h = skipHandler;
            if (h != null && h.handleSkip(delta)) return;
            skipAdjacent(delta);
        });
    }

    private void skipAdjacent(int delta) {
        if (currentSongPath == null) return;
        File currentFolder = new File(currentSongPath);
        File songsRoot     = currentFolder.getParentFile();
        if (songsRoot == null) return;
        File[] siblings = songsRoot.listFiles(File::isDirectory);
        if (siblings == null || siblings.length == 0) return;
        int sortOrder = getSharedPreferences("prefs_songs", MODE_PRIVATE)
                .getInt("songs_sort_order", 0);
        java.util.Arrays.sort(siblings, (a, b) -> sortOrder == 0
                ? Long.compare(b.lastModified(), a.lastModified())
                : Long.compare(a.lastModified(), b.lastModified()));
        int idx = -1;
        for (int i = 0; i < siblings.length; i++) {
            if (siblings[i].getName().equals(currentFolder.getName())) { idx = i; break; }
        }
        if (idx < 0) return;
        int nextIdx = (idx + delta + siblings.length) % siblings.length;
        loadAndPlayWithVolumes(siblings[nextIdx]);
    }

    /** Loads the song, applies its saved mix, then plays. Used by the skips
     *  when no Activity drives the switch. */
    private void loadAndPlayWithVolumes(File folder) {
        final String path = folder.getAbsolutePath();
        final String name = folder.getName();
        final int    id   = name.hashCode();
        boolean ok = loadSong(path, name, id);
        if (!ok) return;
        applySavedSongSettings(id, this::play);
    }

    private final java.util.concurrent.ExecutorService settingsExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * Applies the saved mix of the loaded song from Room: per-stem volume and
     * pan, tempo, and pitch on the pitched stems (drums and percussion are
     * excluded by design). Metronomes stay silent and 3D stays off: this is
     * the playback-only mix used whenever the Player is not driving the engine
     * (mini-player, playlist, notification and car skips, Android Auto).
     * The Room read runs off the main thread; the engine is touched on the
     * main thread and only if the same song is still loaded. [done] (may be
     * null) runs on the main thread afterwards, also when nothing was saved.
     */
    public void applySavedSongSettings(int songId, @Nullable Runnable done) {
        final AudioEngine target = engine;
        if (target == null) { if (done != null) done.run(); return; }
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        settingsExecutor.execute(() -> {
            java.util.List<db_SongParameters> list =
                    db.DAO_db_SongParameters().getParametersByElementId(songId);
            final db_SongParameters p = list.isEmpty() ? null : list.get(0);
            mainHandler.post(() -> {
                // A later load replaced the engine: these settings are stale.
                if (engine != target) return;
                if (p != null) applySongParameters(p);
                if (done != null) done.run();
            });
        });
    }

    private void applySongParameters(db_SongParameters p) {
        java.util.Map<String, StemSetting> settings = StemSettingsCodec.parse(p.stemSettingsJson);
        for (java.util.Map.Entry<String, StemSetting> e : settings.entrySet()) {
            Integer t = stemTracks.get(e.getKey());
            if (t == null) continue;
            float vol = e.getValue().volume * 0.01f;
            float L = vol, R = vol;
            float pan = e.getValue().pan;
            if (pan > 0)      L = vol * (100f - pan) * 0.01f;
            else if (pan < 0) R = vol * (100f - Math.abs(pan)) * 0.01f;
            engine.setVolume(t, L, R);
        }
        // Tempo applies to every stem and to the (silent) metronomes, so they
        // stay in step if the Player later turns one on.
        float tempo = (p.tempoMultMilli > 0 ? p.tempoMultMilli : 1000) / 1000f;
        for (Integer t : stemTracks.values()) engine.setTempo(t, tempo);
        if (metronomeTrack >= 0)       engine.setTempo(metronomeTrack, tempo);
        if (metronomeHalfTrack >= 0)   engine.setTempo(metronomeHalfTrack, tempo);
        if (metronomeDoubleTrack >= 0) engine.setTempo(metronomeDoubleTrack, tempo);
        int pitchIndex = p.pitchIndex > 0 ? p.pitchIndex : numberPickerData.CENTRAL_PITCH_INDEX;
        float semitones = Float.parseFloat(numberPickerData.PITCH_VALUES[pitchIndex - 1]);
        for (java.util.Map.Entry<String, Integer> e : stemTracks.entrySet()) {
            if (StemSpec.isPitched(e.getKey())) engine.setPitch(e.getValue(), semitones);
        }
    }

    /** Releases the song and stops the foreground service. */
    public void releaseSongAndStop() {
        releaseInternal();
        stopForeground(true);
        notificationManager.cancel(NOTIF_ID);
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
        notifyListeners();
        stopSelf();
    }

    private void releaseInternal() {
        if (engine != null) {
            engine.stop();
            engine.dispose();
            engine = null;
        }
        currentSongPath = null;
        currentSongName = null;
        currentSongId   = -1;
        songDurationMs  = 0;
        isPlaying       = false;
        stemTracks.clear();
        stemOrder.clear();
        metronomeTrack = metronomeHalfTrack = metronomeDoubleTrack = -1;
    }

    // === Notification + MediaSession ===
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Fision", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Fision player controls");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(getApplicationContext(), "Fision");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()  {
                // Filter phantom play during a call (HFP/BT can emit KEYCODE_MEDIA_PLAY
                // when activating the hands-free profile → the music started on its own)
                if (isPhoneCallActive()) {
                    Log.i(TAG, "MediaSession.onPlay ignored: call active");
                    return;
                }
                play();
            }
            @Override public void onPause() { pause(); }
            @Override public void onStop()  { pause(); }
            @Override public void onSkipToNext()     { skipToNext(); }
            @Override public void onSkipToPrevious() { skipToPrevious(); }
        });
        stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        mediaSession.setPlaybackState(stateBuilder.build());
        // Born inactive: loading a song does not make Fision the system's
        // active player; play() claims the session. No media button receiver
        // either, so the system cannot restart the app with a MEDIA_PLAY key
        // once the service is gone (media button resumption).
        mediaSession.setMediaButtonReceiver(null);
    }

    private void setupAudioFocus() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                // delayed=false: the system must not grant delayed focus after a call
                // (it resumed on its own otherwise).
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChange -> {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            // Permanent loss: pause and forget "wasPlaying".
                            // Persistent pause releases the media session too.
                            wasPlayingBeforeFocusLoss = false;
                            focusLossAtMs = 0L;
                            if (engine != null) engine.setDuckFactor(1f);
                            pause();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            // Temporary loss but NO ducking (calls, alarms).
                            // Pause and remember to resume on focus regain. Keeps
                            // the media session active so a same-cycle GAIN can
                            // still resume without going through play().
                            wasPlayingBeforeFocusLoss = isPlaying;
                            focusLossAtMs = System.currentTimeMillis();
                            if (engine != null) engine.setDuckFactor(1f);
                            pauseForFocusLoss();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            // Navigation / assistants: attenuate volume but do NOT pause.
                            // Pausing here previously meant the music never came back in the car.
                            if (engine != null) engine.setDuckFactor(0.25f);
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            // Focus regained: restore volume.
                            if (engine != null) engine.setDuckFactor(1f);
                            // Coming from LOSS_TRANSIENT (no active call) AND within
                            // FOCUS_RESUME_WINDOW_MS: resume. Anything older is very
                            // likely another player yielding focus (Spotify switching
                            // songs, notification sound finishing) and must not
                            // auto-start us.
                            long since = focusLossAtMs == 0L
                                    ? Long.MAX_VALUE
                                    : System.currentTimeMillis() - focusLossAtMs;
                            if (wasPlayingBeforeFocusLoss && !isPlaying
                                    && !isPhoneCallActive()
                                    && since <= FOCUS_RESUME_WINDOW_MS) {
                                wasPlayingBeforeFocusLoss = false;
                                focusLossAtMs = 0L;
                                play();
                            } else {
                                wasPlayingBeforeFocusLoss = false;
                                focusLossAtMs = 0L;
                                reevaluateIdleStop();
                            }
                            break;
                    }
                })
                .build();
    }

    /** Receiver for ACTION_AUDIO_BECOMING_NOISY: the output is about to move to the phone
     *  speakers (BT/jack disconnect). Pause to avoid the song bursting out on the speakers. */
    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.i(TAG, "AUDIO_BECOMING_NOISY → pause");
                pause();
            }
        }
    };
    private boolean noisyRegistered = false;

    // === End-of-song watcher (independent of Activities) ===
    private final Runnable endOfSongTick = new Runnable() {
        @Override public void run() {
            if (engine == null || !isPlaying) return;
            long dur = songDurationMs;
            long pos = engine.getCurrentPositionMs();
            if (dur > 0 && pos >= dur - 250) {
                EndOfSongResult r = handleSongEnded();
                // LOOPED_SAME keeps playing → keep watching for the next end.
                // STOPPED already called pause() which stops the watcher.
                // ADVANCED_NEXT reloads the song and play() re-arms the watcher.
                if (r == EndOfSongResult.LOOPED_SAME) {
                    mainHandler.postDelayed(this, 500);
                }
                return;
            }
            // Notification progress ~1x per second, only while the screen is
            // on (nobody sees it otherwise).
            if (++notificationTicks >= 2) {
                notificationTicks = 0;
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null || pm.isInteractive()) refreshNotificationProgress();
            }
            mainHandler.postDelayed(this, 500);
        }
    };
    private int notificationTicks = 0;

    /** Single source of truth for the end-of-song transition, shared by the
     *  service watcher and PlayerActivity's fast UI timer. Encodes the combined
     *  repeat + playlist behaviour:
     *   - Repeat.ALL          → seek 0 and keep playing (infinite loop).
     *   - Repeat.ONE + fresh  → seek 0 and keep playing (one extra pass).
     *   - Repeat.ONE + used   → stop.
     *   - Repeat.OFF + list   → advance to the next song via the skip bridge.
     *   - Repeat.OFF + no list→ stop and reset position.
     *  Repeat takes priority over playlist continuation. */
    public enum EndOfSongResult { LOOPED_SAME, ADVANCED_NEXT, STOPPED }

    // Both watchers (fast UI timer + service tick) can hit the end-of-song
    // window in the same seek(0) settle interval. Debounce them so ONE mode
    // does not exhaust its extra pass on the second reentrant tick.
    private long lastEndHandledAtNs = 0;
    private EndOfSongResult lastEndResult = EndOfSongResult.STOPPED;
    private static final long END_DEBOUNCE_NS = 800_000_000L;

    public EndOfSongResult handleSongEnded() {
        long now = System.nanoTime();
        if (now - lastEndHandledAtNs < END_DEBOUNCE_NS) return lastEndResult;
        lastEndHandledAtNs = now;
        EndOfSongResult r = computeEndAction();
        lastEndResult = r;
        return r;
    }

    private EndOfSongResult computeEndAction() {
        if (engine == null) return EndOfSongResult.STOPPED;
        if (repeatMode == RepeatMode.ALL) {
            seekTo(0);
            notifyLoopedListeners();
            return EndOfSongResult.LOOPED_SAME;
        }
        if (repeatMode == RepeatMode.ONE) {
            if (!repeatOneUsed) {
                repeatOneUsed = true;
                seekTo(0);
                notifyLoopedListeners();
                return EndOfSongResult.LOOPED_SAME;
            }
            repeatOneUsed = false;
            pause();
            seekTo(0);
            return EndOfSongResult.STOPPED;
        }
        // Repeat OFF branch.
        if (playlistMode) {
            dispatchSkip(+1);
            return EndOfSongResult.ADVANCED_NEXT;
        }
        pause();
        seekTo(0);
        return EndOfSongResult.STOPPED;
    }

    private void notifyLoopedListeners() {
        mainHandler.post(() -> {
            for (Listener l : new HashSet<>(listeners)) l.onSongLooped();
        });
    }

    private void startEndOfSongWatcher() {
        mainHandler.removeCallbacks(endOfSongTick);
        mainHandler.postDelayed(endOfSongTick, 500);
    }

    private void stopEndOfSongWatcher() {
        mainHandler.removeCallbacks(endOfSongTick);
    }

    private void registerNoisyReceiver() {
        if (noisyRegistered) return;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires an explicit flag to register receivers at runtime.
            registerReceiver(becomingNoisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(becomingNoisyReceiver, f);
        }
        noisyRegistered = true;
    }

    private void unregisterNoisyReceiver() {
        if (!noisyRegistered) return;
        try { unregisterReceiver(becomingNoisyReceiver); } catch (IllegalArgumentException ignored) {}
        noisyRegistered = false;
    }

    private boolean isPhoneCallActive() {
        try {
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm == null) return false;
            int state = tm.getCallState();
            return state == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
                || state == android.telephony.TelephonyManager.CALL_STATE_RINGING;
        } catch (SecurityException | NullPointerException e) {
            return false;
        }
    }

    private void updateMediaMetadata(String title, long durationMs) {
        if (mediaSession == null) return;
        MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putLong  (MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        String artist = getResolvedArtist();
        if (artist != null) {
            b.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
        }
        // The cover in MediaSession is what the Pixel/Material You media notification renders
        // at high resolution (NOT the largeIcon, which stays as a small thumbnail).
        android.graphics.Bitmap cover = getOrLoadCover();
        if (cover != null) {
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover);
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_ART,       cover);
        }
        mediaSession.setMetadata(b.build());
    }

    /** Loads the original artcover (without rescaling) on first access and caches it.
     *  Fallback to artcover_generic in res/raw when the song has no dedicated artcover. */
    private android.graphics.Bitmap getOrLoadCover() {
        if (cachedCoverBitmap != null) return cachedCoverBitmap;
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
        if (currentMetadata != null && currentMetadata.coverPath != null) {
            cachedCoverBitmap = android.graphics.BitmapFactory.decodeFile(currentMetadata.coverPath, opts);
            if (cachedCoverBitmap != null) return cachedCoverBitmap;
        }
        // Server-side cover fallback when the local artcover is missing.
        com.jotapeveloper.fision.cloud.CloudItem ci = lookupCloudItem();
        if (ci != null && ci.coverPath != null) {
            java.io.File f = new java.io.File(ci.coverPath);
            if (f.exists()) {
                cachedCoverBitmap = android.graphics.BitmapFactory.decodeFile(
                        ci.coverPath, opts);
                if (cachedCoverBitmap != null) return cachedCoverBitmap;
            }
        }
        // Generic fallback (res/raw/artcover_generic.png). Its resolution is assumed
        // to fit the modern notification; no unnecessary rescaling.
        cachedCoverBitmap = android.graphics.BitmapFactory.decodeResource(
                getResources(), R.raw.artcover_generic, opts);
        return cachedCoverBitmap;
    }

    public void updatePlaybackState(int state) {
        if (mediaSession == null || stateBuilder == null) return;
        long pos = engine != null ? engine.getCurrentPositionMs() : 0;
        stateBuilder.setState(state, pos, isPlaying ? 1f : 0f);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private Notification buildNotification(int playbackState) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (currentSongPath != null) intent.putExtra("SongPathString", currentSongPath);
        if (currentSongName != null) intent.putExtra("SongName",       currentSongName);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Cover: cached native bitmap (artcover or fallback to the HD app logo).
        // The system picks it up from the MediaMetadata for the modern (HD) notification.
        android.graphics.Bitmap largeIcon = getOrLoadCover();

        String notifArtist = getResolvedArtist();
        builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getDisplayTitle() != null ? getDisplayTitle() : "Fision")
                .setContentText(notifArtist != null ? notifArtist : "")
                .setSmallIcon(R.drawable.ic_media)
                .setLargeIcon(largeIcon)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .addAction(new NotificationCompat.Action(
                        R.drawable.ic_previous,
                        "Previous",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)))
                .addAction(new NotificationCompat.Action(
                        playbackState == PlaybackStateCompat.STATE_PLAYING ? R.drawable.ic_pause : R.drawable.ic_play,
                        playbackState == PlaybackStateCompat.STATE_PLAYING ? "Pause" : "Play",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                                playbackState == PlaybackStateCompat.STATE_PLAYING
                                        ? PlaybackStateCompat.ACTION_PAUSE
                                        : PlaybackStateCompat.ACTION_PLAY)))
                .addAction(new NotificationCompat.Action(
                        R.drawable.ic_next,
                        "Next",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(this,
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT)))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)
                        .setShowCancelButton(true));
        return builder.build();
    }

    private void startForegroundWithNotif(int state) {
        Notification n = buildNotification(state);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            // Android 12+: startForeground from background throws ForegroundServiceStartNotAllowedException.
            // Audio focus gain (background callback) can hit this path when resuming after a call/duck.
            // Keep the audio playing and only refresh the notification via NotificationManager:
            // the service goes back to foreground state on the next play() triggered by a valid action
            // (notification tap, MediaSession key, foreground activity).
            if (notificationManager != null
                    && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED) {
                try { notificationManager.notify(NOTIF_ID, n); } catch (Throwable ignored) {}
            }
        }
    }

    /** Refreshes the progress in the notification (end-of-song watcher, ~1s). */
    private void refreshNotificationProgress() {
        if (builder == null || notificationManager == null || engine == null) return;
        builder.setProgress((int) songDurationMs, (int) engine.getCurrentPositionMs(), false);
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            notificationManager.notify(NOTIF_ID, builder.build());
        }
    }
}
