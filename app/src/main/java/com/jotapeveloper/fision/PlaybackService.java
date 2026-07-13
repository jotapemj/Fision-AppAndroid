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
    // Cached cover to avoid decoding the PNG multiple times per song
    private android.graphics.Bitmap cachedCoverBitmap = null;

    // === Listeners for UI (mini-bar, etc.) ===
    public interface Listener { void onPlaybackChanged(); }
    private final Set<Listener> listeners = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        unregisterNoisyReceiver();
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
    /** Display title: metadata > folder name. */
    public String  getDisplayTitle() {
        return currentMetadata != null && currentMetadata.displayTitle != null
                ? currentMetadata.displayTitle : currentSongName;
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

        // Release the previous one
        releaseInternal();

        File folder = new File(songPath);
        if (!folder.isDirectory()) {
            Log.e(TAG, "Invalid song folder: " + songPath);
            return false;
        }

        // Enumerate with a direct File.listFiles (more reliable than DocumentFile for paths
        // with spaces, dots or accented characters — the URI escaping was flaky).
        File[] files = folder.listFiles();
        if (files == null) {
            Log.e(TAG, "listFiles returned null: " + songPath);
            return false;
        }
        Log.i(TAG, "loadSong scanning " + files.length + " files in " + folder.getName());

        engine = new AudioEngine();
        // HRTF: load the HRIR bank when the asset exists. It is propagated to each Track
        // in the subsequent addTrack. When the file is missing, the engine falls back to ITD/ILD.
        try {
            engine.initHrtf(getAssets(), "hrtf/hrtf_horizontal.bin");
        } catch (Throwable t) {
            Log.w(TAG, "HRTF init failed", t);
        }
        stemTracks.clear();
        stemOrder.clear();

        for (File f : files) {
            if (!f.isFile()) continue;
            String rawName = f.getName();
            if (rawName == null) continue;
            // Use Locale.ROOT to avoid surprises with odd locales (e.g. Turkish i/I)
            String name = rawName.toLowerCase(java.util.Locale.ROOT).trim();
            // Accept WAV and FLAC. The C++ engine picks the decoder by extension.
            boolean isWav  = name.endsWith(".wav");
            boolean isFlac = name.endsWith(".flac");
            if (!isWav && !isFlac) continue;
            int extLen = isFlac ? 5 : 4;
            String bare = name.substring(0, name.length() - extLen);
            String absPath = f.getAbsolutePath();
            // Metronomes (always looping) — matched by base name, format-agnostic.
            switch (bare) {
                case "metronome":        metronomeTrack       = engine.addTrack(absPath, true); engine.setVolume(metronomeTrack,       0f, 0f); continue;
                case "metronome_half":   metronomeHalfTrack   = engine.addTrack(absPath, true); engine.setVolume(metronomeHalfTrack,   0f, 0f); continue;
                case "metronome_double": metronomeDoubleTrack = engine.addTrack(absPath, true); engine.setVolume(metronomeDoubleTrack, 0f, 0f); continue;
            }
            // Dynamic stems from the catalog (no loop)
            String stem = bare;
            if (!StemSpec.isKnown(stem)) {
                Log.w(TAG, "  ignored (not in StemSpec catalog): " + rawName);
                continue;
            }
            int tid = engine.addTrack(absPath, false);
            if (tid < 0) {
                Log.e(TAG, "  addTrack rejected: " + rawName);
                continue;
            }
            engine.setVolume(tid, 0.75f, 0.75f);
            stemTracks.put(stem, tid);
        }

        Log.i(TAG, "loadSong tracks: stems=" + stemTracks + " metronome=" + metronomeTrack
                + " metronomeHalf=" + metronomeHalfTrack + " metronomeDouble=" + metronomeDoubleTrack);

        // Validate: at least vocals + (other or instrumental) are required
        if (!stemTracks.containsKey("vocals")
                || (!stemTracks.containsKey("other") && !stemTracks.containsKey("instrumental"))) {
            Log.e(TAG, "Minimum stems not found. Detected=" + stemTracks.keySet()
                    + ". Expected at least 'vocals' + ('other' or 'instrumental').");
            return false;
        }

        // Sort by StemSpec.ORDER
        stemOrder.addAll(stemTracks.keySet());
        java.util.Collections.sort(stemOrder,
                (a, b) -> Integer.compare(StemSpec.orderIndex(a), StemSpec.orderIndex(b)));

        currentSongPath = songPath;
        currentSongName = songName;
        currentSongId   = songId;
        songDurationMs  = engine.getDurationMs();
        // Optional metadata from info.txt + artcover.png
        currentMetadata = SongMetadata.read(folder);
        // Invalidate the cover cache when switching songs (do not recycle: the previous
        // MediaSession may still reference it for a few ms — leave it to the GC).
        cachedCoverBitmap = null;

        updateMediaMetadata(getDisplayTitle(), songDurationMs);
        notifyListeners();
        return true;
    }

    // === Controls ===
    public void play() {
        if (engine == null) return;
        if (audioManager != null && focusRequest != null) {
            audioManager.requestAudioFocus(focusRequest);
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
    }

    public void pause() {
        if (engine == null) return;
        stopEndOfSongWatcher();
        engine.pause();
        unregisterNoisyReceiver();
        isPlaying = false;
        // Stay in foreground with the paused notification. Leaving foreground
        // during pause let the system reclaim the service after a few minutes,
        // which broke tap-to-resume from the mini player.
        startForegroundWithNotif(PlaybackStateCompat.STATE_PAUSED);
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
        notifyListeners();
    }

    public void seekTo(long ms) {
        if (engine != null) engine.seekTo(ms);
    }

    /** Loads the next song (in the same folder as the current one) and starts
     *  playback. Triggered by Bluetooth SKIP_TO_NEXT and by the notification. */
    public void skipToNext()     { skipAdjacent(+1); }

    /** Loads the previous song (in the same folder as the current one) and
     *  starts playback. Triggered by Bluetooth SKIP_TO_PREVIOUS. */
    public void skipToPrevious() { skipAdjacent(-1); }

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

    /** Loads the song, applies stem volumes/pan from Room, then plays.
     *  Mirrors MainActivity.loadSongInServiceWithVolumes so skip works when
     *  no Activity is alive. */
    private void loadAndPlayWithVolumes(File folder) {
        final String path = folder.getAbsolutePath();
        final String name = folder.getName();
        final int    id   = name.hashCode();
        boolean ok = loadSong(path, name, id);
        if (!ok) return;
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            java.util.List<db_SongParameters> list =
                    db.DAO_db_SongParameters().getParametersByElementId(id);
            if (!list.isEmpty() && engine != null) {
                db_SongParameters p = list.get(0);
                java.util.Map<String, StemSetting> settings =
                        StemSettingsCodec.parse(p.stemSettingsJson);
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
            }
            mainHandler.post(this::play);
        });
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
        mediaSession.setActive(true);
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
                            // Permanent loss: pause and forget "wasPlaying"
                            wasPlayingBeforeFocusLoss = false;
                            if (engine != null) engine.setDuckFactor(1f);
                            pause();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            // Temporary loss but NO ducking (calls, alarms).
                            // Pause and remember to resume on focus regain.
                            wasPlayingBeforeFocusLoss = isPlaying;
                            if (engine != null) engine.setDuckFactor(1f);
                            pause();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            // Navigation / assistants: attenuate volume but do NOT pause.
                            // Pausing here previously meant the music never came back in the car.
                            if (engine != null) engine.setDuckFactor(0.25f);
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            // Focus regained: restore volume.
                            if (engine != null) engine.setDuckFactor(1f);
                            // Coming from LOSS_TRANSIENT (no active call): resume.
                            if (wasPlayingBeforeFocusLoss && !isPlaying && !isPhoneCallActive()) {
                                wasPlayingBeforeFocusLoss = false;
                                play();
                            } else {
                                wasPlayingBeforeFocusLoss = false;
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
                pause();
                seekTo(0);
                return; // pause() already stops the watcher
            }
            mainHandler.postDelayed(this, 500);
        }
    };

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
        if (currentMetadata != null && currentMetadata.artist != null) {
            b.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentMetadata.artist);
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

        builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getDisplayTitle() != null ? getDisplayTitle() : "Fision")
                .setContentText(currentMetadata != null && currentMetadata.artist != null
                        ? currentMetadata.artist : "")
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

    /** Refreshes the progress in the notification. The UI calls it every ~1s. */
    public void refreshNotificationProgress() {
        if (builder == null || notificationManager == null || engine == null) return;
        builder.setProgress((int) songDurationMs, (int) engine.getCurrentPositionMs(), false);
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            notificationManager.notify(NOTIF_ID, builder.build());
        }
    }
}
