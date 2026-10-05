package com.jotapeveloper.fision;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.shawnlin.numberpicker.NumberPicker;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends androidx.appcompat.app.AppCompatActivity {
    private String SongName = "";
    public int SongId;

    private File SongFolder;
    private String FolderName;
    private StemListAdapter stemsAdapter;

    private SeekBar sb_progressBar;
    private boolean mUserSeekingProgress = false;

    private TextView tv_duration;
    private TextView tv_currentPos;
    private TextView tv_SongTitle;
    private TextView tv_bpm;

    private RecyclerView rvChords;
    private RecyclerView rvStems;

    // Controls

    private MaterialButton b_play;
    // Play/pause icon morph state (avd_play_to_pause / avd_pause_to_play).
    private boolean playIconState = false;   // false = play icon (paused), true = pause icon (playing)
    private boolean playIconInitialized = false;
    private MaterialButton b_previous;
    private MaterialButton b_next;
    private MaterialButton b_seconds_back;
    private MaterialButton b_seconds_forward;
    private MaterialButton b_metronome;
    private MaterialButton b_chord;
    private MaterialButton b_pitch;
    private MaterialButton b_back;
    private MaterialButton b_menu;
    private MaterialButton b_repeat;
    // "+" / "-" sign overlay on top of the 5s buttons — animated on click
    private TextView tv_seconds_back_sign;
    private TextView tv_seconds_forward_sign;
    private androidx.drawerlayout.widget.DrawerLayout drawerLayout;
    private TextView tv_drawer_chords_subtitle;

    // Per-song chord mode: true=basic (chords_basic.txt), false=advanced (chords.txt)
    // Filled from Room in readDataBase and persisted via updateChordsBasicMode.
    private boolean chordsBasicMode = false;
    // True when chords_basic.txt exists in the song folder. When missing, the drawer
    // option is disabled and advanced is forced (previous behavior).
    private boolean hasBasicChordsFile = false;
    private View drawerChordsItem;
    // Flags that onServiceReady wanted autoplay; fires in updateUI after applying volumes
    private boolean autoplayPending = false;
    // Pending tile highlight runnable: delayed to match the smoothScroll end
    // (without this, the tile lit up before the movement started)
    private Runnable pendingTileHighlight = null;

    // Global preferences (drawer): notation + accidental + visible transposition
    private TextView tv_drawer_cifrado_subtitle;
    private TextView tv_drawer_notation_subtitle;
    private com.google.android.material.switchmaterial.SwitchMaterial sw_drawer_transposition;
    private boolean latinNotation         = false; // notation preference (American/Latin)
    private boolean preferFlat            = false; // accidental preference (flat/sharp)
    private boolean transpositionAffectsChords = false;
    private static final String PREF_LATIN_NOTATION   = "chord_notation_latin";
    private static final String PREF_PREFER_FLAT      = "chord_prefer_flat";
    private static final String PREF_TRANSP_AFFECTS   = "chord_transp_affects_view";
    // Downbeat sound: GLOBAL (not per song). Previously stored in Room v5
    // (legacy columns are kept). Reason for the change: the sound choice should
    // persist across songs without having to reconfigure each one.
    private static final String PREF_DOWNBEAT_ENABLED = "downbeat_sound_enabled";
    private static final String PREF_DOWNBEAT_TYPE    = "downbeat_sound_type";
    // Ambient nebula background (global switch)
    private static final String PREF_NEBULA_ENABLED   = "nebula_enabled";
    private boolean            nebulaEnabled         = false;
    private NebulaBackgroundView nebulaView;

    // Sub-option: pulse the phone motor on each beat while nebula is on.
    private static final String PREF_NEBULA_HAPTICS_ENABLED = "nebula_haptics_enabled";
    private boolean            nebulaHapticsEnabled  = false;
    // Frequency cap: never fire more than ~140 pulses per minute (≈ every 428ms)
    // to protect the linear vibration motor on fast songs.
    private static final long  MIN_BEAT_HAPTIC_INTERVAL_MS = 428L;
    /** Extra latency subtracted from the source position to align beat-driven
     *  visuals + haptics with the audio the user actually hears. Compensates
     *  the SoundTouch WSOLA processing latency (not reported by Oboe) plus
     *  the systematic early marking of beat_this. Calibrated by ear on the
     *  primary test device — promote to a user-facing slider once it is
     *  clear that a single constant does not cover every headphone / BT chain. */
    private static final long  BEAT_SYNC_OFFSET_MS = 130L;
    private long               mLastBeatHapticMs     = 0L;
    private android.os.Vibrator mBeatVibrator;

    // Per-song preferences (drawer): 0.5x metronome variant + sound on downbeat
    private com.google.android.material.switchmaterial.SwitchMaterial sw_drawer_downbeat;
    private View drawerDownbeatSoundItem;
    private TextView tv_drawer_downbeat_subtitle;
    private int     metronomeHalfVariant  = 0;     // 0=A, 1=B
    private boolean downbeatSoundEnabled  = false;
    private int     downbeatSoundType     = 0;
    // Sound currently loaded in the engine (avoids redundant reloads)
    private int     loadedDownbeatType    = -1;
    // Index of the last downbeat scheduled in the engine (avoids duplicates when detecting beats)
    private int     lastScheduledDownbeatIdx = -1;

    // Global BPM of the song (first line of beats.txt) — used for the A/B shift
    private float globalBpm = 0f;

    private android.widget.ProgressBar pb_stemsLoading;

    private Timer UpdateProgressBar;
    // The song wants the UI timer (set once loaded) vs. the Activity is started.
    private boolean progressTimerWanted = false;
    private boolean uiStarted = false;
    private volatile boolean resyncOnNextTick = false;
    boolean relocating = false;
    boolean movingChords = false;
    boolean chordsActive = true;
    boolean paused = true;
    private volatile boolean isDestroyed = false;
    // Loading curtain shown from onCreate until the first full load; null after.
    @androidx.annotation.Nullable private com.jotapeveloper.fision.brand.LoadingCurtain loadingCurtain;


    // These fields are populated after binding to the PlaybackService
    public AudioEngine engine;
    private PlaybackService playbackService;
    private boolean serviceBound = false;

    public int metronomeTrack = -1;
    public int metronomeHalfTrack = -1;
    public int metronomeDoubleTrack = -1;

    public int songDuration = 0;

    public boolean metronomeEnabled = false;
    public int selectedMetronome = 1;
    /** Playback speed as multiplier * 1000. 1000 = 1.0x (neutral). The ruler
     *  picker works in absolute BPM (globalBpm * mult) but persistence and the
     *  engine keep using the multiplier so the value is meaningful across
     *  songs with different tempos. */
    public int selectedTempoMultMilli = 1000;
    public int selectedPitch = numberPickerData.CENTRAL_PITCH_INDEX;

    public float metronomeVol = 100;
    public float metronomePan = 0;

    // Dynamic per-stem settings (volume + pan), persisted as JSON
    public java.util.Map<String, StemSetting> stemSettings = new java.util.HashMap<>();
    // Previous volume of each stem (used to restore on unmute)
    private final java.util.Map<String, Integer> stemLastVol = new java.util.HashMap<>();

    List<ChordsFile> chordList = new ArrayList<>();
    List<Float> beatsList = new ArrayList<>();
    private int chordCounter = 0;

    // === Bar visualizer ===
    private final List<BeatCell> beatCells = new ArrayList<>();
    private int beatsPerBar = 4;
    private BeatCellAdapter beatAdapter;
    private int currentBeatIdx = -1;
    private int beatCounter = 1;
    /** Window size used to average the interval between beats for the tv_bpm
     *  display. Six beats keeps the readout responsive but stable. */
    private static final int LOCAL_BPM_WINDOW = 6;

    SnapHelper helper = new LinearSnapHelper();

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private PlaybackService.Listener playbackListener = new PlaybackService.Listener() {
        @Override
        public void onPlaybackChanged() {
            // Sync the UI with the service state (MainActivity mini-bar handles its own)
            if (playbackService == null) return;
            // Song released by the service's idle stop while the app was in
            // the background: nothing to show, so the player closes.
            if (!playbackService.isSongLoaded() && !FisionApplication.isInForeground()) {
                finish();
                return;
            }
            boolean playing = playbackService.isPlaying();
            paused = !playing;
            applyPlayIcon(playing, true);
            if (playing) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
        @Override
        public void onSongLooped() {
            // Repeat wrapped the song around: the engine is at 0 and still
            // playing. Reset the visual iterators so beats/chords advance again
            // from the top instead of remaining stuck at the last position.
            currentBeatIdx = -1;
            chordCounter   = 0;
            beatCounter    = 1;
            lastScheduledDownbeatIdx = -1;
            cancelPendingDownbeats();
            applyMetronomeHalfPhase(0);
            if (pendingTileHighlight != null) rvChords.removeCallbacks(pendingTileHighlight);
            if (beatAdapter != null) beatAdapter.setHighlight(-1);
            sb_progressBar.setProgress(0);
            tv_currentPos.setText("00:00");
            if (!chordList.isEmpty()) rvChords.scrollToPosition(0);
            // Re-arm the upcoming downbeats for the next pass through the song.
            scheduleUpcomingDownbeats();
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder iBinder) {
            playbackService = ((PlaybackService.LocalBinder) iBinder).getService();
            serviceBound = true;
            playbackService.addListener(playbackListener);
            playbackService.setSkipRequestHandler(skipRequestHandler);
            onServiceReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (playbackService != null) {
                playbackService.removeListener(playbackListener);
                playbackService.clearSkipRequestHandler(skipRequestHandler);
            }
            playbackService = null;
            serviceBound = false;
        }
    };

    /**
     * Called after the service is bound. Loads the song if needed, syncs the
     * track IDs and starts playback when this is the first open.
     */
    private void onServiceReady() {
        String SongPath = SongFolder != null ? SongFolder.getAbsolutePath() : null;

        // Load the song in the service when it is not the current one. The
        // decode runs off the main thread so the loading curtain keeps animating.
        if (SongPath != null && !playbackService.isCurrentSong(SongPath)) {
            playbackService.loadSongAsync(SongPath, SongName, SongId, ok -> {
                if (isFinishing() || isDestroyed || playbackService == null) return;
                if (!ok) {
                    Toast.makeText(this, "Error opening song. Missing files.", Toast.LENGTH_LONG).show();
                    // Release the service so no phantom notification is left with the
                    // startForegroundService promise dangling
                    playbackService.releaseSongAndStop();
                    finish();
                    return;
                }
                onSongLoaded();
            });
            return;
        }
        // Already loaded (back from the mini-player): nothing new to show off,
        // the curtain is a standard spinner with a quick fade.
        curtainResolves = false;
        onSongLoaded();
    }

    /** Second half of onServiceReady, once the song is loaded in the service. */
    private void onSongLoaded() {
        // Sync references (only metronomes are loose IDs; stems come from service.getStemOrder)
        engine = playbackService.getEngine();
        metronomeTrack       = playbackService.getMetronomeTrack();
        metronomeHalfTrack   = playbackService.getMetronomeHalfTrack();
        metronomeDoubleTrack = playbackService.getMetronomeDoubleTrack();
        songDuration         = (int) playbackService.getDurationMs();

        SetControlsEnabled(true);
        PrepareControls();
        setupRecyclerViews();
        refreshRepeatButtonUi();
        bindPlaylistSwitch();
        // Auto-play only when this is a fresh open (service was not playing).
        // Flag as pending: play is triggered from updateUI() after the stored volumes
        // are pushed to the engine — before, play started here and a moment of 75%
        // default volume was audible until the async Room read arrived.
        // NoAutoplay (via mini-player): just enter, do not touch state.
        boolean noAutoplay = getIntent().getBooleanExtra("NoAutoplay", false);
        if (!playbackService.isPlaying()) {
            autoplayPending = !noAutoplay;
            if (noAutoplay) {
                paused = true;
                applyPlayIcon(false, false);
            }
        } else {
            // Already playing: sync UI with state
            paused = false;
            applyPlayIcon(true, false);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        readDataBase(SongId);
        setupTimers();
    }


    @Override
    protected void onStart() {
        super.onStart();
        uiStarted = true;
        // Back on screen: the first tick resyncs bar, clock and beat at once.
        resyncOnNextTick = true;
        startProgressTimer(0);
        // Pick up an accent change performed while the player was in the
        // background; recreate re-inflates layouts with the new palette.
        com.jotapeveloper.fision.design.AccentColorManager.get(this)
                .maybeRecreateForAccent(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.jotapeveloper.fision.design.AccentColorManager.get(this).applyOverlayTo(this);
        setContentView(R.layout.new_activity_player);
        // Black curtain with the loading spinner until the player is fully
        // loaded (revealed from updateUI, when the stem sliders animate).
        loadingCurtain = com.jotapeveloper.fision.brand.LoadingCurtain.show(this);
        // load preferences language
        PreferenceManager.setDefaultValues(this, R.xml.root_preferences, false);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String language = sharedPreferences.getString("language", "default_language");

        setAppLocale(language);

        // Get data from main activity
        String SongPath = getIntent().getStringExtra("SongPathString");
        SongName = getIntent().getStringExtra("SongName");

        getSongInfoFromDB();

        if (SongPath != null) {
            SongFolder = new File(SongPath);
        } else {
            Toast.makeText(this, "Extra is null", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Chord mode: advanced by default. readDataBase overrides with the per-song
        // value stored in Room and, when it differs, reloadChords() reloads the file.
        chordsBasicMode = false;
        // Detect whether chords_basic.txt exists; when missing, legacy behavior (advanced only)
        hasBasicChordsFile = new File(SongPath, "chords_basic.txt").exists();

        // Global drawer preferences (notation + accidental + visible transposition)
        SharedPreferences globalPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        latinNotation              = globalPrefs.getBoolean(PREF_LATIN_NOTATION, false);
        preferFlat                 = globalPrefs.getBoolean(PREF_PREFER_FLAT, false);
        transpositionAffectsChords = globalPrefs.getBoolean(PREF_TRANSP_AFFECTS, false);
        // Downbeat sound: now global (see PREF_DOWNBEAT_*).
        downbeatSoundEnabled       = globalPrefs.getBoolean(PREF_DOWNBEAT_ENABLED, false);
        downbeatSoundType          = globalPrefs.getInt    (PREF_DOWNBEAT_TYPE, 0);
        nebulaEnabled              = globalPrefs.getBoolean(PREF_NEBULA_ENABLED, false);
        nebulaHapticsEnabled       = globalPrefs.getBoolean(PREF_NEBULA_HAPTICS_ENABLED, false);
        mLyricsKaraokeMode         = globalPrefs.getBoolean(PREF_LYRICS_KARAOKE_MODE, false);

        initializeUIComponents();
        readChordsFile(SongPath);
        readBeatsFile(SongPath);
        filterGhostChords();

        // The rest (song load, recyclerviews, timers, play) happens once the
        // PlaybackService is bound → see onServiceReady().
        SetControlsEnabled(false);

        // Launch and bind the service
        Intent svc = new Intent(this, PlaybackService.class);
        ContextCompat.startForegroundService(this, svc);
        bindService(svc, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void getSongInfoFromDB() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        DAO_db_SongParameters dao = db.DAO_db_SongParameters();

        executor.execute(new Runnable() {
            @Override
            public void run() {


                SongId = dao.getSongIdByName(SongName);
                Log.d("DatabaseQuery", "Song ID: " + SongId);

                // To update the UI, use runOnUiThread
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Update the UI here with songId
                    }
                });
            }
        });
    }

    private void readDataBase(int songId) {
        // Run the database query on a separate thread
        new Thread(() -> {
            // Get the database instance
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());

            // On re-entry the service binds fast and SongId may still be 0
            // (getSongInfoFromDB is async). Resolve by name in-thread so the
            // query never reads an empty row.
            int id = songId;
            if (id <= 0) {
                id = db.DAO_db_SongParameters().getSongIdByName(SongName);
                SongId = id;
            }

            // Query the database for this song's parameters
            List<db_SongParameters> parametersList = db.DAO_db_SongParameters().getParametersByElementId(id);

            if (!parametersList.isEmpty()) {
                db_SongParameters parameters = parametersList.get(0);
                chordsActive       = parameters.chordsActive;
                chordsBasicMode    = parameters.chordsBasicMode;
                metronomeEnabled   = parameters.metronomeActive;
                selectedMetronome  = parameters.selectedMetronome;
                metronomeVol       = parameters.metronomeVol;
                metronomePan       = parameters.metronomePan;
                metronomeHalfVariant = parameters.metronomeHalfVariant;
                // downbeatSoundEnabled / downbeatSoundType already loaded as globals
                // in onCreate (PREF_DOWNBEAT_*). Legacy Room columns ignored.
                selectedPitch = (parameters.pitchIndex > 0)
                        ? parameters.pitchIndex : numberPickerData.CENTRAL_PITCH_INDEX;
                // v7: multiplier persisted directly (mult * 1000). Migration
                // populates this column from the legacy `tempoIndex`, so a value
                // of 1000 always represents neutral 1.0x.
                selectedTempoMultMilli = (parameters.tempoMultMilli > 0)
                        ? parameters.tempoMultMilli : 1000;

                // v3: per-stem settings from JSON. Soft migration from v2 columns.
                java.util.Map<String, StemSetting> parsed = StemSettingsCodec.parse(parameters.stemSettingsJson);
                stemSettings.clear();
                stemSettings.putAll(parsed);
                // v6: 3D audio flag + per-stem (x/y) positions restored from the JSON.
                is3DEnabled = parameters.audio3DEnabled;
                stem3DPositions.clear();
                for (java.util.Map.Entry<String, StemSetting> e : parsed.entrySet()) {
                    StemSetting st = e.getValue();
                    if (!Float.isNaN(st.pos3DX) && !Float.isNaN(st.pos3DY)) {
                        stem3DPositions.put(e.getKey(), new float[]{ st.pos3DX, st.pos3DY });
                    }
                }
                // Fallback: when the JSON is empty and there is legacy column data, use it
                if (stemSettings.isEmpty()) {
                    seedFromLegacy("bass",   parameters.bass,    parameters.bassPan);
                    seedFromLegacy("drums",  parameters.drums,   parameters.drumsPan);
                    seedFromLegacy("other",  parameters.other,   parameters.otherPan);
                    seedFromLegacy("vocals", parameters.vocals,  parameters.vocalsPan);
                    seedFromLegacy("instrumental", parameters.instrumental, 0f);
                }
            }
            // Update the UI on the main thread
            runOnUiThread(this::updateUI);

        }).start();

    }

    /** Creates a StemSetting with legacy values when the stem exists in the engine. */
    private void seedFromLegacy(String stem, int vol, float pan) {
        if (playbackService == null || !playbackService.hasStem(stem)) return;
        stemSettings.put(stem, new StemSetting(vol, pan));
    }

    private void updateUI() {

        // Apply the saved tempo to ALL stems + metronomes
        float tempoFactor = selectedTempoMultMilli / 1000f;
        for (String stem : playbackService.getStemOrder()) {
            Integer t = playbackService.getStemTrack(stem);
            if (t != null) engine.setTempo(t, tempoFactor);
        }
        if (metronomeTrack >= 0)       engine.setTempo(metronomeTrack, tempoFactor);
        if (metronomeHalfTrack >= 0)   engine.setTempo(metronomeHalfTrack, tempoFactor);
        if (metronomeDoubleTrack >= 0) engine.setTempo(metronomeDoubleTrack, tempoFactor);

        // Apply pitch to the pitched stems (drums and other percussion excluded by design)
        float pitchSemitones = Float.parseFloat(numberPickerData.PITCH_VALUES[selectedPitch - 1]);
        for (String stem : playbackService.getStemOrder()) {
            if (!StemSpec.isPitched(stem)) continue;
            Integer t = playbackService.getStemTrack(stem);
            if (t != null) engine.setPitch(t, pitchSemitones);
        }
        // Pitch button tint based on the saved value
        if (selectedPitch != numberPickerData.CENTRAL_PITCH_INDEX) {
            b_pitch.setIconTint(ColorStateList.valueOf(accent()));
        } else {
            b_pitch.setIconTint(ColorStateList.valueOf(Color.rgb(255, 255, 255)));
        }
        // Apply the shift to the adapter now that selectedPitch is in sync with the DB
        applyDisplayShiftToAdapter();

        // Sync drawer UI with per-song values (downbeat)
        if (sw_drawer_downbeat != null) {
            sw_drawer_downbeat.setOnCheckedChangeListener(null);
            sw_drawer_downbeat.setChecked(downbeatSoundEnabled);
            // Reinstall listener
            sw_drawer_downbeat.setOnCheckedChangeListener((btn, isChecked) -> {
                downbeatSoundEnabled = isChecked;
                // Global: persist in SharedPreferences (was previously per-song in Room).
                PreferenceManager.getDefaultSharedPreferences(PlayerActivity.this)
                        .edit().putBoolean(PREF_DOWNBEAT_ENABLED, isChecked).apply();
                if (drawerDownbeatSoundItem != null) {
                    drawerDownbeatSoundItem.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
                if (isChecked) {
                    ensureDownbeatSoundLoaded();
                    if (engine != null) engine.setClickEnabled(metronomeEnabled);
                    applyClickDuckTrack();
                    rescheduleClicksFromCurrentBeat();
                } else {
                    if (engine != null) engine.setClickEnabled(false);
                    applyClickDuckTrack();
                    cancelPendingDownbeats();
                }
            });
        }
        if (drawerDownbeatSoundItem != null) {
            drawerDownbeatSoundItem.setVisibility(downbeatSoundEnabled ? View.VISIBLE : View.GONE);
        }
        if (tv_drawer_downbeat_subtitle != null) {
            tv_drawer_downbeat_subtitle.setText(DownbeatSounds.labelRes(downbeatSoundType));
        }
        if (downbeatSoundEnabled) ensureDownbeatSoundLoaded();
        // Apply the half metronome offset when variant = B
        applyMetronomeHalfPhase();

        // Show chords section: initial state on song load
        View chordsContainer = findViewById(R.id.chords_container);
        if (isChordDiagramShown) {
            // Diagrams view keeps the carousel on and the button in its diagrams state.
            b_chord.setIconTint(ColorStateList.valueOf(accent()));
        } else if(chordsActive){
            b_chord.setIconTint(ColorStateList.valueOf(accent()));
        }
        else{
            chordsContainer.setVisibility(View.GONE);
            b_chord.setIconTint(ColorStateList.valueOf(Color.rgb(255,255,255)));
        }

        // Per-song saved chord mode (Room). Activity load read chords.txt (advanced)
        // by default; when the song had "basic" saved, reload from chords_basic.txt.
        // When chords_basic.txt is missing, force advanced (legacy behavior).
        if (!hasBasicChordsFile && chordsBasicMode) {
            chordsBasicMode = false;
            updateDB("chordsBasic", 0);
        }
        updateChordsDrawerSubtitle();
        applyChordsItemEnabled();
        if (chordsBasicMode) reloadChords();

        // Metronome section
        if(metronomeEnabled){
            b_metronome.setIconTint(ColorStateList.valueOf(accent()));
            if(selectedMetronome == 0){engine.setVolume(metronomeHalfTrack, metronomeVol*0.01f, metronomeVol*0.01f);}
            if(selectedMetronome == 1){engine.setVolume(metronomeTrack, metronomeVol*0.01f, metronomeVol*0.01f);}
            if(selectedMetronome == 2){engine.setVolume(metronomeDoubleTrack, metronomeVol*0.01f, metronomeVol*0.01f);}
        }


        // Data is now available: hide the spinner. Slider animations keep running behind.
        if (pb_stemsLoading != null) pb_stemsLoading.setVisibility(View.GONE);

        // Push ALL saved volumes to the engine at once BEFORE the autoplay.
        // Otherwise the stems play at 75% (loadSong default) for a moment until each
        // animation's postDelayed fires its own applyStemVolumeToEngine.
        java.util.List<String> order = playbackService.getStemOrder();
        for (String stem : order) applyStemVolumeToEngine(stem);

        // Reconcile the 3D view + engine + switch state with the value read from
        // the current song. Handles Activity-open, song-switch and end-of-song
        // autoplay uniformly — old-song vista state gets cleaned or rebuilt.
        refresh3DForCurrentSong();

        // Refresh progressbar + clock when entering the Activity (useful when arriving
        // from the mini-player with NoAutoplay: the normal timer does not update while
        // paused=true, so the seekbar stayed at 0 until play was pressed).
        if (engine != null && songDuration > 0) {
            int curMs = (int) engine.getCurrentPositionMs();
            int permille = (int) ((curMs * 10000L) / songDuration);
            if (permille < 0) permille = 0; if (permille > 10000) permille = 10000;
            sb_progressBar.setProgress(permille);
            tv_currentPos.setText(getTime(curMs));
            // Seed the BPM readout with the current beat so it is not blank
            // while paused (mini-player entry) or right after re-entering the
            // Activity from MainActivity without hitting play first.
            if (!beatCells.isEmpty()) {
                float tSec = curMs / 1000f;
                int seedIdx = -1;
                for (int i = 0; i < beatCells.size(); i++) {
                    if (beatCells.get(i).time <= tSec) seedIdx = i;
                    else break;
                }
                if (seedIdx >= 0) updateLocalBpmDisplay(seedIdx);
            }
        }

        // The player is fully loaded. Behind the loading curtain (open or song
        // change) it closes its ring and lifts once the colors are in;
        // autoplay and the slider animation start as it lifts, so they are seen.
        revealCurtainWhenReady();
    }

    /** Autoplay and stem slider animation: the moment the loaded player shows. */
    private void startPlayerReveal() {
        if (isDestroyed || playbackService == null) return;
        java.util.List<String> order = playbackService.getStemOrder();

        // Now it is safe to start. The engine already has the correct volumes.
        if (autoplayPending) {
            autoplayPending = false;
            b_play.performClick();
        }

        // Stems controls section: dynamic based on the loaded stems.
        // Animate ONLY the ViewHolders visible on screen. Stems off-viewport
        // already got their real value at bind time (StemListAdapter).
        // When arriving from the mini-player (NoAutoplay), NO animation — the song
        // was already playing and the slider must show its real value from the start.
        boolean noAutoplay = getIntent().getBooleanExtra("NoAutoplay", false);
        if (noAutoplay) {
            // No animation: the first bind ran before this DB load, so rebind
            // now that stemSettings is populated with the saved values.
            if (stemsAdapter != null) stemsAdapter.notifyDataSetChanged();
            return;
        }
        // animateInitial is already on since setupRecyclerViews — the bind left the
        // sliders at 0. Here only the per-delay animations are launched.
        int delay = 150;
        for(int i = 0; i < order.size(); i++) {
            final String stem = order.get(i);
            new Handler().postDelayed(() -> {
                if (stemsAdapter == null) return;
                int position = stemsAdapter.adapterPositionForStem(stem);
                if (position == RecyclerView.NO_POSITION) return;
                RecyclerView.ViewHolder viewHolder = rvStems.findViewHolderForAdapterPosition(position);
                if (!(viewHolder instanceof StemListAdapter.ViewHolder)) return;
                StemListAdapter.ViewHolder svh = (StemListAdapter.ViewHolder) viewHolder;

                StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
                int targetValue = s.volume;

                // Push the final volume to the engine at once — the animation is visual only
                applyStemVolumeToEngine(stem);
                svh.iv_stemIcon.setAlpha(targetValue == 0
                        ? StemListAdapter.ALPHA_DISABLED : StemListAdapter.ALPHA_ACTIVE);

                // The bind already left the slider at 0 (animateInitial=true). Animate 0 → target.
                ObjectAnimator anim = ObjectAnimator.ofFloat(svh.sb_stemVol, "value", 0f, (float) targetValue);
                anim.setDuration(300);
                anim.start();
            }, delay * i);
        }
        // After the last delay ends, turn the initial-animation mode off so future
        // binds (scroll, expand drumset) show the saved value directly.
        if (stemsAdapter != null) {
            new Handler().postDelayed(() -> stemsAdapter.setAnimateInitial(false),
                    delay * order.size() + 350);
        }
    }

    private void setupTimers(){
        progressTimerWanted = true;
        startProgressTimer(500);
    }

    /** Runs the UI timer only while the Activity is started: the tick only
     *  drives visuals. End of song and the notification belong to the
     *  service; downbeat clicks are queued in the engine ahead of time. */
    private void startProgressTimer(long delayMs) {
        if (!uiStarted || !progressTimerWanted || UpdateProgressBar != null) return;
        UpdateProgressBar = new Timer();
        // 30 ms period keeps the beat-crossing detection tight enough that the
        // nebula pulse and its optional haptic feel locked to the audio (jitter
        // within musical perception threshold ≈ 30 ms).
        UpdateProgressBar.scheduleAtFixedRate(new UpdateProgressBarTask(), delayMs, 30);
    }

    private void cancelProgressTimer() {
        if (UpdateProgressBar != null) {
            UpdateProgressBar.cancel();
            UpdateProgressBar.purge();
            UpdateProgressBar = null;
        }
    }

    private void stopProgressTimer() {
        progressTimerWanted = false;
        cancelProgressTimer();
    }
    private void setupRecyclerViews() {
        // Dynamic: the list reflects the stems really loaded in the engine
        java.util.List<String> order = playbackService.getStemOrder();
        StemListData[] stems = new StemListData[order.size()];
        for (int i = 0; i < order.size(); i++) stems[i] = new StemListData(order.get(i));

        rvStems = (RecyclerView) findViewById(R.id.rv_stemList);
        stemsAdapter = new StemListAdapter(this, stems, this);
        // When animation will happen (not entering from the mini-player), enable
        // animateInitial BEFORE setAdapter so the FIRST bind already leaves sliders at 0.
        // Otherwise the first bind sets saved and a "saved → 0 → animated" flash shows.
        boolean noAutoplay = getIntent().getBooleanExtra("NoAutoplay", false);
        if (!noAutoplay) stemsAdapter.setAnimateInitial(true);
        rvStems.setHasFixedSize(true);
        rvStems.setLayoutManager(new LinearLayoutManager(this));
        rvStems.setAdapter(stemsAdapter);


        // Rhythm visualizer: builds cells from beats/chords/downbeats
        buildBeatCells();
        if (chordDiagrams != null) chordDiagrams.setCells(beatCells);

        rvChords = (RecyclerView) findViewById(R.id.rv_chords);
        beatAdapter = new BeatCellAdapter(this, beatCells, beatsPerBar);
        beatAdapter.setLatinNotation(latinNotation);
        beatAdapter.setPreferFlat(preferFlat);
        beatAdapter.setSemitonesShift(currentDisplayShift());
        // Tap on a beat cell jumps the song there.
        beatAdapter.setOnCellClickListener(this::seekToBeat);
        rvChords.setHasFixedSize(true);
        rvChords.setLayoutManager(new SpeedyLinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvChords.setAdapter(beatAdapter);
        // No item animator: avoids the grey flicker on notifyItemChanged
        rvChords.setItemAnimator(null);
        helper.attachToRecyclerView(rvChords);
        // Asymmetric padding: the active tile (snap) sits skewed to the left
        // (~30% of the free space on the left, ~70% on the right).
        // Item width = 44dp + 14dp marginStart + 2dp marginEnd = 60dp
        final int itemWidthPx = (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 60,
                getResources().getDisplayMetrics());
        rvChords.setClipToPadding(false);
        rvChords.post(() -> {
            int total = rvChords.getWidth() - itemWidthPx;
            if (total < 0) total = 0;
            int leftPad  = (int) (total * 0.30f);
            int rightPad = total - leftPad;
            rvChords.setPadding(leftPad, 0, rightPad, 0);
        });

        rvChords.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                // Dragging browses the chords without seeking (a tap on a cell
                // seeks). While browsing the carousel stops following the song;
                // on release a magnet brings it back to the current beat.
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    movingChords = true;
                    browsingChords = true;
                    rvChords.removeCallbacks(chordsMagnet);
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE && movingChords) {
                    movingChords = false;
                    View snappedView = helper.findSnapView(recyclerView.getLayoutManager());
                    int position = snappedView != null
                            ? recyclerView.getLayoutManager().getPosition(snappedView)
                            : Math.max(0, currentBeatIdx);
                    scheduleChordsMagnet(Math.abs(position - Math.max(0, currentBeatIdx)));
                }
            }
        });
    }

    /**
     * Builds the BeatCell list from beatsList + chordList + downbeats.txt.
     * Each cell represents a beat. When a new chord starts at that beat time,
     * it is attached. When downbeats.txt exists, its positions drive it
     * (3/4 vs 4/4); otherwise 4/4 is assumed starting from the first beat.
     */
    private void buildBeatCells() {
        beatCells.clear();
        if (beatsList.isEmpty()) return;

        // 1) Determine beatsPerBar and the position of each beat.
        //    downbeats.txt only carries the "1" instants. For each beat: look up
        //    the downbeat immediately <= beat.time and count how many beats sit
        //    between them. beatsPerBar is estimated as the mode of the beat count
        //    between consecutive downbeats (clamped to 3/4/6).
        Downbeats db = (SongFolder != null) ? Downbeats.read(SongFolder) : null;
        int[] beatPositions = new int[beatsList.size()];
        if (db != null && !db.times.isEmpty()) {
            // Beat index corresponding to each downbeat (nearest in time).
            int nBeats = beatsList.size();
            int nDb    = db.times.size();
            int[] downbeatBeatIdx = new int[nDb];
            int j = 0;
            for (int i = 0; i < nDb; i++) {
                float t = db.times.get(i);
                while (j + 1 < nBeats
                        && Math.abs(beatsList.get(j + 1) - t)
                         < Math.abs(beatsList.get(j) - t)) {
                    j++;
                }
                downbeatBeatIdx[i] = j;
            }
            // Estimate beatsPerBar via mode of the beat distances.
            int[] histo = new int[8]; // counts for 2..7
            for (int i = 1; i < nDb; i++) {
                int diff = downbeatBeatIdx[i] - downbeatBeatIdx[i - 1];
                if (diff >= 2 && diff < histo.length) histo[diff]++;
            }
            int bestDiff = 4, bestCount = -1;
            for (int i = 2; i < histo.length; i++) {
                if (histo[i] > bestCount) { bestCount = histo[i]; bestDiff = i; }
            }
            if (bestDiff == 3 || bestDiff == 4 || bestDiff == 6) beatsPerBar = bestDiff;
            else beatsPerBar = 4;

            // Assign position: for each beat, find the last downbeat with
            // beatIdx <= i; position = 1 + (i - beatIdxOfDownbeat).
            int k = 0; // index into downbeatBeatIdx
            for (int i = 0; i < nBeats; i++) {
                while (k + 1 < nDb && downbeatBeatIdx[k + 1] <= i) k++;
                int anchor = downbeatBeatIdx[k];
                int pos;
                if (i < anchor) {
                    // beats before the first downbeat: count backwards from anchor.
                    int back = anchor - i; // how many beats back
                    pos = ((beatsPerBar - (back % beatsPerBar)) % beatsPerBar) + 1;
                    if (pos > beatsPerBar) pos = 1;
                } else {
                    pos = ((i - anchor) % beatsPerBar) + 1;
                }
                beatPositions[i] = pos;
            }
        } else {
            beatsPerBar = 4;
            for (int i = 0; i < beatsList.size(); i++) beatPositions[i] = (i % beatsPerBar) + 1;
        }

        // 2) Quantize each chord to the nearest beat. Prevents a chord change that
        //    happens midway between two beats from being pushed to the "next" one
        //    (musically wrong): assign it to the temporally closest beat.
        String[] chordPerBeat = new String[beatsList.size()];
        for (ChordsFile c : chordList) {
            float t = c.getTime();
            // Find the beat index closest to t
            int nearest = 0;
            float minDiff = Math.abs(beatsList.get(0) - t);
            for (int i = 1; i < beatsList.size(); i++) {
                float diff = Math.abs(beatsList.get(i) - t);
                if (diff < minDiff) { minDiff = diff; nearest = i; }
                else if (beatsList.get(i) > t) break; // already past; no need to keep going
            }
            // When a change is already annotated on that beat, overwrite only when the
            // new one is temporally later (keeps the closest late change).
            chordPerBeat[nearest] = c.getChord();
        }

        // Propagate and drop consecutive repeats
        String lastChord = null;
        for (int i = 0; i < beatsList.size(); i++) {
            String at = chordPerBeat[i];
            String cellChord = null;
            if (at != null && !at.equals(lastChord)) {
                cellChord = at;
                lastChord = at;
            }
            beatCells.add(new BeatCell(beatsList.get(i), beatPositions[i], beatsPerBar, cellChord));
        }
    }

    private void readBeatsFile(String SongPath) {
        File beatsFilePath = new File(SongPath, "beats.txt");
        try {
            BufferedReader reader = new BufferedReader(new FileReader(beatsFilePath));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    float time = Float.parseFloat(line);
                    beatsList.add(time);
                } catch (NumberFormatException ignored) {}
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Some beats.txt files from the Python script carry the BPM as the first line (e.g. "130.43...").
        // Detected because it breaks monotonicity with the second entry and dropped here:
        // otherwise buildBeatCells matches downbeats to an absurd time and NEVER paints bar markers.
        if (beatsList.size() >= 2 && beatsList.get(0) > beatsList.get(1)) {
            globalBpm = beatsList.get(0);
            Log.w("PlayerActivity", "beats.txt: header BPM=" + globalBpm + " (dropped from beatsList, kept in globalBpm)");
            beatsList.remove(0);
        }
    }

    private void readChordsFile(String SongPath) {
        chordList.clear();
        // Basic mode tries chords_basic.txt; when missing, falls back to chords.txt
        String fileName = chordsBasicMode ? "chords_basic.txt" : "chords.txt";
        File chordFilePath = new File(SongPath, fileName);
        if (!chordFilePath.exists() && chordsBasicMode) {
            Log.w("PlayerActivity", "chords_basic.txt missing, falling back to chords.txt");
            chordFilePath = new File(SongPath, "chords.txt");
        }
        try {
            BufferedReader reader = new BufferedReader(new FileReader(chordFilePath));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length == 2) {
                    String chord;
                    if ("N".equals(parts[0])){/*chord = "-";*/continue;}else{chord = parts[0];}
                    float time = Float.parseFloat(parts[1]);
                    chordList.add(new ChordsFile(chord, time));
                }
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Filters "phantom" chords: those lasting less than half a beat (~half of the
     * interval between beats). Usually analysis noise between two real chords.
     * After filtering, consecutive equal chords are merged.
     */
    private void filterGhostChords() {
        if (chordList.size() < 2 || beatsList.size() < 2) return;

        // 1) Median beat interval (more robust than mean against outliers)
        List<Float> intervals = new ArrayList<>();
        for (int i = 1; i < beatsList.size(); i++) {
            intervals.add(beatsList.get(i) - beatsList.get(i - 1));
        }
        Collections.sort(intervals);
        float medianBeatInterval = intervals.get(intervals.size() / 2);
        // Chords shorter than half a beat get dropped
        float minDuration = 0.5f * medianBeatInterval;

        // 2) Drop the short ones. The last chord has no successor, kept as-is.
        List<ChordsFile> filtered = new ArrayList<>();
        for (int i = 0; i < chordList.size(); i++) {
            float duration = (i + 1 < chordList.size())
                    ? chordList.get(i + 1).getTime() - chordList.get(i).getTime()
                    : Float.MAX_VALUE;
            if (duration >= minDuration) filtered.add(chordList.get(i));
        }

        // 3) Merge consecutive equals (after filtering A-A appears where A-X-A was)
        List<ChordsFile> merged = new ArrayList<>();
        for (ChordsFile c : filtered) {
            if (merged.isEmpty() || !merged.get(merged.size() - 1).getChord().equals(c.getChord())) {
                merged.add(c);
            }
        }

        chordList.clear();
        chordList.addAll(merged);
    }

    private void initializeUIComponents() {
        sb_progressBar = findViewById(R.id.sb_progressBar);
        pb_stemsLoading = findViewById(R.id.pb_stemsLoading);
        if (pb_stemsLoading != null) pb_stemsLoading.setVisibility(View.VISIBLE);
        b_play = findViewById(R.id.b_play);
        b_previous = findViewById(R.id.b_previous);
        b_next = findViewById(R.id.b_next);
        b_previous.setOnClickListener(v -> handlePreviousRequest());
        b_next.setOnClickListener(v -> openAdjacentSong(+1));
        b_seconds_back = findViewById(R.id.b_seconds_back);
        b_seconds_forward = findViewById(R.id.b_seconds_forward);
        tv_seconds_back_sign    = findViewById(R.id.tv_seconds_back_sign);
        tv_seconds_forward_sign = findViewById(R.id.tv_seconds_forward_sign);
        attachSeekButtonBounce(b_seconds_back);
        attachSeekButtonBounce(b_seconds_forward);
        b_metronome = findViewById(R.id.b_metronome);
        b_chord = findViewById(R.id.b_chord);
        chordIcon = b_chord.getIcon();
        b_chord.setOnClickListener(v -> onChordButton());
        b_pitch = findViewById(R.id.b_pitch);
        b_back = findViewById(R.id.b_back);
        b3d = findViewById(R.id.b_3d);
        if (b3d != null) b3d.setOnClickListener(v -> toggle3DView());
        bLyrics = findViewById(R.id.b_lyrics);
        if (bLyrics != null) bLyrics.setOnClickListener(v -> toggleLyricsView());
        b_repeat = findViewById(R.id.b_repeat);
        if (b_repeat != null) b_repeat.setOnClickListener(v -> cycleRepeatMode());

        tv_duration = findViewById(R.id.tv_duration);
        tv_currentPos = findViewById(R.id.tv_currentPos);
        tv_SongTitle = findViewById(R.id.tv_SongTitle);
        tv_SongTitle.setMarqueeRepeatLimit(TITLE_MARQUEE_PASSES);
        restartTitleMarquee();
        tv_bpm = findViewById(R.id.tv_bpm);

        // Popup menu setup

        // Listener for metronome button
        b_metronome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showMetronomeBottomDialog();
            }
        });

        b_metronome.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if(!metronomeEnabled) {
                    updateDB("metronome", 1);
                    metronomeEnabled = true;
                    b_metronome.setIconTint(ColorStateList.valueOf(accent()));

                    if(selectedMetronome == 0){engine.setVolume(metronomeHalfTrack, metronomeVol*0.01f, metronomeVol*0.01f);}
                    if(selectedMetronome == 1){engine.setVolume(metronomeTrack, metronomeVol*0.01f, metronomeVol*0.01f);}
                    if(selectedMetronome == 2){engine.setVolume(metronomeDoubleTrack, metronomeVol*0.01f, metronomeVol*0.01f);}
                    // Downbeat click depends on the metronome: enable it when both are ON
                    if (downbeatSoundEnabled && engine != null) {
                        ensureDownbeatSoundLoaded();
                        engine.setClickEnabled(true);
                        applyClickDuckTrack();
                        rescheduleClicksFromCurrentBeat();
                    }
                }
                else{
                    updateDB("metronome", 0);
                    metronomeEnabled = false;
                    b_metronome.setIconTint(ColorStateList.valueOf(Color.rgb(255,255,255)));
                    if(selectedMetronome == 0){engine.setVolume(metronomeHalfTrack, 0, 0);}
                    if(selectedMetronome == 1){engine.setVolume(metronomeTrack, 0, 0);}
                    if(selectedMetronome == 2){engine.setVolume(metronomeDoubleTrack, 0, 0);}
                    if (engine != null) engine.setClickEnabled(false);
                    applyClickDuckTrack();
                    cancelPendingDownbeats();
                }
                return true;
            }
        });

        // Listener for pitch button
        b_pitch.setOnClickListener(new View.OnClickListener() {
           @Override
           public void onClick(View view) {
               showPitchBottomDialog();
           }
        });

        // Listener for manual changes in song progress seekback
        sb_progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                // Live-refresh the time readout while the user drags so the
                // number matches the thumb position.
                if (b && engine != null) {
                    int dur = (int) engine.getDurationMs();
                    if (dur > 0) {
                        int previewMs = (int) ((long) i * dur / 10000L);
                        tv_currentPos.setText(getTime(previewMs));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mUserSeekingProgress = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mUserSeekingProgress = false;
                if (engine == null) return;
                int newPosPermille = sb_progressBar.getProgress();
                int songDuration = (int) engine.getDurationMs();
                seekToMs((int) ((long) newPosPermille * songDuration / 10000L));
            }

        });

        b_back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });

        // Right-side drawer
        drawerLayout = findViewById(R.id.drawer_layout);
        b_menu = findViewById(R.id.b_menu);
        tv_drawer_chords_subtitle = findViewById(R.id.tv_drawer_chords_subtitle);
        updateChordsDrawerSubtitle();

        b_menu.setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.END)) {
                drawerLayout.closeDrawer(androidx.core.view.GravityCompat.END);
            } else {
                drawerLayout.openDrawer(androidx.core.view.GravityCompat.END);
            }
        });

        drawerChordsItem = findViewById(R.id.drawer_item_chords);
        if (drawerChordsItem != null) {
            drawerChordsItem.setOnClickListener(v -> showChordsModeDialog());
        }
        applyChordsItemEnabled();

        // Notation: American vs Latin (global)
        tv_drawer_cifrado_subtitle = findViewById(R.id.tv_drawer_cifrado_subtitle);
        updateCifradoDrawerSubtitle();
        View cifradoItem = findViewById(R.id.drawer_item_cifrado);
        if (cifradoItem != null) cifradoItem.setOnClickListener(v -> showCifradoDialog());

        // Accidental: flat vs sharp (global)
        tv_drawer_notation_subtitle = findViewById(R.id.tv_drawer_notation_subtitle);
        updateNotationDrawerSubtitle();
        View notationItem = findViewById(R.id.drawer_item_notation);
        if (notationItem != null) notationItem.setOnClickListener(v -> showNotationDialog());

        // Switch "transposition affects chords" (global)
        sw_drawer_transposition = findViewById(R.id.sw_drawer_transposition);
        if (sw_drawer_transposition != null) {
            sw_drawer_transposition.setChecked(transpositionAffectsChords);
            sw_drawer_transposition.setOnCheckedChangeListener((btn, isChecked) -> {
                transpositionAffectsChords = isChecked;
                PreferenceManager.getDefaultSharedPreferences(PlayerActivity.this)
                        .edit().putBoolean(PREF_TRANSP_AFFECTS, isChecked).apply();
                applyDisplayShiftToAdapter();
            });
        }

        // Switch "sound on downbeat" (per song)
        sw_drawer_downbeat = findViewById(R.id.sw_drawer_downbeat);
        drawerDownbeatSoundItem = findViewById(R.id.drawer_item_downbeat_sound);
        tv_drawer_downbeat_subtitle = findViewById(R.id.tv_drawer_downbeat_subtitle);
        if (sw_drawer_downbeat != null) {
            sw_drawer_downbeat.setOnCheckedChangeListener((btn, isChecked) -> {
                downbeatSoundEnabled = isChecked;
                PreferenceManager.getDefaultSharedPreferences(PlayerActivity.this)
                        .edit().putBoolean(PREF_DOWNBEAT_ENABLED, isChecked).apply();
                if (drawerDownbeatSoundItem != null) {
                    drawerDownbeatSoundItem.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
                if (isChecked) {
                    ensureDownbeatSoundLoaded();
                    if (engine != null) engine.setClickEnabled(metronomeEnabled);
                    applyClickDuckTrack();
                    rescheduleClicksFromCurrentBeat();
                } else {
                    if (engine != null) engine.setClickEnabled(false);
                    applyClickDuckTrack();
                    cancelPendingDownbeats();
                }
            });
        }
        if (drawerDownbeatSoundItem != null) {
            drawerDownbeatSoundItem.setOnClickListener(v -> showDownbeatSoundDialog());
        }

        // Export item — opens the multi-step export flow
        View exportItem = findViewById(R.id.drawer_item_export);
        if (exportItem != null) {
            exportItem.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(androidx.core.view.GravityCompat.END);
                showExportChoiceDialog();
            });
        }

        // "Reset to default" item — confirms, then returns volumes+pan to defaults
        View resetItem = findViewById(R.id.drawer_item_reset);
        if (resetItem != null) {
            resetItem.setOnClickListener(v -> showResetToDefaultDialog());
        }

        // Reprocess item — reopens the wizard over the mp3 already stored on the server
        View reprocessItem = findViewById(R.id.drawer_item_reprocess);
        if (reprocessItem != null) {
            reprocessItem.setOnClickListener(v -> openReprocessWizard());
        }
        refreshReprocessDrawerVisibility();

        // Ambient nebula background: apply the saved state without animation
        // on first inflate, then bind the drawer switch and kick off a palette
        // extraction from the current artcover. Reset the palette handshake
        // so the view stays invisible until the first real palette lands and
        // no default-colour flash is shown.
        nebulaView = findViewById(R.id.v_nebula);
        if (nebulaView != null) {
            nebulaView.resetPaletteFade();
            nebulaView.setNebulaEnabled(nebulaEnabled, false);
        }
        bindNebulaSwitch();
        refreshSongPalette();

        SetControlsEnabled(false);
    }

    /** Binds the drawer switch that toggles the nebula background and its
     *  child "haptic beat" switch. Persists both choices as global prefs and
     *  keeps the child row visible only while the parent is on. */
    private void bindNebulaSwitch() {
        com.google.android.material.switchmaterial.SwitchMaterial sw =
                findViewById(R.id.sw_drawer_nebula);
        View rowHaptics = findViewById(R.id.row_drawer_nebula_haptics);
        com.google.android.material.switchmaterial.SwitchMaterial swHaptics =
                findViewById(R.id.sw_drawer_nebula_haptics);
        if (sw == null) return;
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(nebulaEnabled);
        if (rowHaptics != null) rowHaptics.setVisibility(nebulaEnabled ? View.VISIBLE : View.GONE);
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            nebulaEnabled = isChecked;
            PreferenceManager.getDefaultSharedPreferences(PlayerActivity.this)
                    .edit().putBoolean(PREF_NEBULA_ENABLED, isChecked).apply();
            if (nebulaView != null) nebulaView.setNebulaEnabled(isChecked, true);
            if (isChecked) refreshSongPalette();
            if (rowHaptics != null) rowHaptics.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            // Disabling the parent implicitly disables the haptics too so the
            // motor cannot keep firing without the visual layer running.
            if (!isChecked && nebulaHapticsEnabled) {
                nebulaHapticsEnabled = false;
                PreferenceManager.getDefaultSharedPreferences(PlayerActivity.this)
                        .edit().putBoolean(PREF_NEBULA_HAPTICS_ENABLED, false).apply();
                if (swHaptics != null) {
                    swHaptics.setOnCheckedChangeListener(null);
                    swHaptics.setChecked(false);
                    bindNebulaHapticsListener(swHaptics);
                }
            }
        });
        if (swHaptics != null) {
            swHaptics.setOnCheckedChangeListener(null);
            swHaptics.setChecked(nebulaHapticsEnabled);
            bindNebulaHapticsListener(swHaptics);
        }
    }

    private void bindNebulaHapticsListener(
            com.google.android.material.switchmaterial.SwitchMaterial swHaptics) {
        swHaptics.setOnCheckedChangeListener((btn, isChecked) -> {
            nebulaHapticsEnabled = isChecked;
            PreferenceManager.getDefaultSharedPreferences(PlayerActivity.this)
                    .edit().putBoolean(PREF_NEBULA_HAPTICS_ENABLED, isChecked).apply();
        });
    }

    /** Fires one haptic pulse in sync with the ambient nebula pulse. Gated by
     *  the drawer switch, the Activity lifecycle (no vibration when the user
     *  left the screen) and a frequency cap so fast tempos do not exhaust the
     *  linear motor. Intensity comes from the same soft/strong classification
     *  used for the visual pulse. */
    private void triggerBeatHaptic(float intensity) {
        if (!nebulaHapticsEnabled) return;
        if (!getLifecycle().getCurrentState()
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastBeatHapticMs < MIN_BEAT_HAPTIC_INTERVAL_MS) return;
        mLastBeatHapticMs = now;
        if (mBeatVibrator == null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.os.VibratorManager vm = (android.os.VibratorManager)
                        getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                mBeatVibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                mBeatVibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
        }
        if (mBeatVibrator == null || !mBeatVibrator.hasVibrator()) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                int effect = intensity >= 0.6f
                        ? android.os.VibrationEffect.EFFECT_HEAVY_CLICK
                        : android.os.VibrationEffect.EFFECT_TICK;
                mBeatVibrator.vibrate(android.os.VibrationEffect.createPredefined(effect));
            } else {
                mBeatVibrator.vibrate(intensity >= 0.6f ? 28L : 10L);
            }
        } catch (Throwable ignored) { /* motor busy or effect unsupported */ }
    }

    // ================================================================
    // Song colors: accent, nebula and 3D room from the artwork
    // ================================================================
    /** Accent pair currently painted on the player (runtime accent). */
    private int uiAccent, uiAccentSecondary;
    private boolean uiAccentApplied = false;
    private android.animation.ValueAnimator uiAccentAnim;
    private static final long UI_ACCENT_FADE_MS = 600L;

    /** Resolves the player colors for the current song and applies them.
     *  "Adopt song colors" off, or artwork without usable color: the picked
     *  accent drives everything (nebula in tonal variants of it). On: the
     *  palette is extracted from artcover.png off the UI thread. */
    private void refreshSongPalette() {
        if (SongFolder == null) return;
        com.jotapeveloper.fision.design.AccentColorManager mgr =
                com.jotapeveloper.fision.design.AccentColorManager.get(this);
        final com.jotapeveloper.fision.design.SongPalette fallback =
                com.jotapeveloper.fision.design.SongPalette.fromAccent(
                        mgr.getAccentColor(this), mgr.getAccentSecondaryColor(this));
        final File cover = new File(SongFolder, "artcover.png");
        if (!mgr.isSongColorsEnabled() || !cover.exists()) {
            applySongPalette(fallback);
            return;
        }
        final File folder = SongFolder;
        final String cacheKey = folder.getName();
        final String signature = cover.lastModified() + ":" + cover.length();
        // Cached palette: applied synchronously, so the player never shows
        // the picked accent before the artwork colors on re-entry.
        com.jotapeveloper.fision.design.SongPalette.Cached cached =
                com.jotapeveloper.fision.design.SongPalette.loadCached(this, cacheKey, signature);
        if (cached.hit) {
            applySongPalette(cached.palette != null ? cached.palette : fallback);
            return;
        }
        final android.content.Context appCtx = getApplicationContext();
        new Thread(() -> {
            com.jotapeveloper.fision.design.SongPalette sp = null;
            try {
                android.graphics.BitmapFactory.Options probe =
                        new android.graphics.BitmapFactory.Options();
                probe.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(cover.getAbsolutePath(), probe);
                int sample = 1;
                while (probe.outWidth / sample > 512 && probe.outHeight / sample > 512) sample *= 2;
                android.graphics.BitmapFactory.Options opts =
                        new android.graphics.BitmapFactory.Options();
                opts.inSampleSize = sample;
                android.graphics.Bitmap bm =
                        android.graphics.BitmapFactory.decodeFile(cover.getAbsolutePath(), opts);
                if (bm != null) {
                    sp = com.jotapeveloper.fision.design.SongPalette.fromBitmap(bm);
                    com.jotapeveloper.fision.design.SongPalette.storeCached(appCtx, cacheKey, signature, sp);
                }
            } catch (Throwable ignored) {}
            final com.jotapeveloper.fision.design.SongPalette out = sp != null ? sp : fallback;
            runOnUiThread(() -> {
                // Drop a result that belongs to a song already switched away.
                if (isDestroyed() || !folder.equals(SongFolder)) return;
                applySongPalette(out);
            });
        }, "song-palette").start();
    }

    private void applySongPalette(com.jotapeveloper.fision.design.SongPalette sp) {
        songPaletteFolder = SongFolder;
        if (nebulaView != null) nebulaView.setPalette(sp.nebula, sp.nebulaAnchor);
        applyUiAccent(sp.accent, sp.accentSecondary);
        // Texture re-bake, instant; on a song change it runs under the black veil.
        if (audio3DScene != null) audio3DScene.setRoomAccent(sp.accent);
    }

    /** Dialog and bottom-sheet layouts resolve ?attr/colorPrimary to the
     *  picked theme accent; moves their accent-painted widgets (and
     *  switches) to the player's runtime accent, instantly. */
    private void recolorToPlayerAccent(View root) {
        if (!uiAccentApplied || root == null) return;
        com.jotapeveloper.fision.design.AccentColorManager mgr =
                com.jotapeveloper.fision.design.AccentColorManager.get(this);
        com.jotapeveloper.fision.design.AccentRecolorer.recolor(
                java.util.Collections.singletonList(root),
                mgr.getAccentColor(this), mgr.getAccentSecondaryColor(this),
                uiAccent, uiAccentSecondary, 0L);
    }

    /** show() + {@link #recolorToPlayerAccent} on the dialog window. */
    private void showWithPlayerAccent(android.app.Dialog d) {
        d.show();
        if (d.getWindow() != null) recolorToPlayerAccent(d.getWindow().getDecorView());
    }

    /** Installs [primary]/[secondary] as the player's runtime accent.
     *  Buttons, sliders, switches and the seek bar fade from the previous
     *  accent; everything else (text, highlights, adapters) switches
     *  instantly. */
    private void applyUiAccent(int primary, int secondary) {
        com.jotapeveloper.fision.design.AccentColorManager mgr =
                com.jotapeveloper.fision.design.AccentColorManager.get(this);
        int fromP = uiAccentApplied ? uiAccent : mgr.getAccentColor(this);
        int fromS = uiAccentApplied ? uiAccentSecondary : mgr.getAccentSecondaryColor(this);
        if (uiAccentApplied && fromP == primary && fromS == secondary) return;
        // Land any running fade on its target first: detection works by
        // matching the old accent, so widgets must not sit mid-fade.
        if (uiAccentAnim != null) uiAccentAnim.end();
        // First application (player opening) is instant: nothing should be
        // seen fading from the picked accent. Later changes (song switch) fade.
        long fade = uiAccentApplied ? UI_ACCENT_FADE_MS : 0L;
        com.jotapeveloper.fision.design.AccentColorManager.setRuntimeAccent(this, primary, secondary);
        uiAccent = primary;
        uiAccentSecondary = secondary;
        uiAccentApplied = true;
        java.util.List<View> roots = java.util.Collections.singletonList(getWindow().getDecorView());
        uiAccentAnim = com.jotapeveloper.fision.design.AccentRecolorer.recolor(
                roots, fromP, fromS, primary, secondary, fade);
        // Instant consumers that paint from resolveAccent() at bind/draw time.
        if (beatAdapter != null) beatAdapter.notifyDataSetChanged();
        if (mLyricsLinesAdapter != null) mLyricsLinesAdapter.notifyDataSetChanged();
    }

    /** Shows or hides the "Reprocess" item depending on whether the current song has
     *  an associated CloudItem (i.e. it came from the server). Called at drawer setup
     *  and when switching songs in-place. */
    private void refreshReprocessDrawerVisibility() {
        View item = findViewById(R.id.drawer_item_reprocess);
        if (item == null) return;
        String hash = getCurrentSongHash();
        boolean visible = hash != null
                && com.jotapeveloper.fision.cloud.CloudRepository.get(this).getByHash(hash) != null;
        item.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Current folder name (== hash when it comes from the server). */
    private String getCurrentSongHash() {
        if (SongFolder == null) return null;
        return SongFolder.getName();
    }

    /** Launches ImportActivity in reprocess mode for the current hash. Blocks when
     *  a job is already in flight for that song (avoids duplicating the queue). */
    private void openReprocessWizard() {
        if (drawerLayout != null) drawerLayout.closeDrawer(androidx.core.view.GravityCompat.END);
        String hash = getCurrentSongHash();
        if (hash == null) return;
        com.jotapeveloper.fision.cloud.CloudItem ci =
                com.jotapeveloper.fision.cloud.CloudRepository.get(this).getByHash(hash);
        if (ci == null) return;
        if (ci.isInFlight()) {
            Toast.makeText(this, R.string.reprocess_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, ImportActivity.class);
        i.putExtra(ImportActivity.EXTRA_REPROCESS_HASH, hash);
        startActivity(i);
    }

    /** Refreshes the drawer "Chords" item subtitle with the selected mode. */
    private void updateChordsDrawerSubtitle() {
        if (tv_drawer_chords_subtitle == null) return;
        tv_drawer_chords_subtitle.setText(
                chordsBasicMode ? R.string.chords_basic : R.string.chords_advanced);
    }

    /** Enables or disables the drawer item based on whether chords_basic.txt exists. */
    private void applyChordsItemEnabled() {
        if (drawerChordsItem == null) return;
        boolean enabled = hasBasicChordsFile;
        drawerChordsItem.setEnabled(enabled);
        drawerChordsItem.setClickable(enabled);
        drawerChordsItem.setAlpha(enabled ? 1f : 0.4f);
    }

    /** "Notation" item subtitle: shows the current notation (American/Latin). */
    private void updateCifradoDrawerSubtitle() {
        if (tv_drawer_cifrado_subtitle == null) return;
        tv_drawer_cifrado_subtitle.setText(
                latinNotation ? R.string.cifrado_latin : R.string.cifrado_american);
    }

    /** "Accidental" item subtitle: flat or sharp. */
    private void updateNotationDrawerSubtitle() {
        if (tv_drawer_notation_subtitle == null) return;
        tv_drawer_notation_subtitle.setText(
                preferFlat ? R.string.notation_flat : R.string.notation_sharp);
    }

    /** American vs Latin notation dialog. Persists and refreshes the adapter. */
    private void showCifradoDialog() {
        String[] labels = {
                getString(R.string.cifrado_american),
                getString(R.string.cifrado_latin)
        };
        int currentIdx = latinNotation ? 1 : 0;
        showSingleChoiceBlurDialog(R.string.cifrado_dialog_title, labels, currentIdx, which -> {
            boolean newLatin = (which == 1);
            if (newLatin == latinNotation) return;
            latinNotation = newLatin;
            PreferenceManager.getDefaultSharedPreferences(PlayerActivity.this)
                    .edit().putBoolean(PREF_LATIN_NOTATION, latinNotation).apply();
            updateCifradoDrawerSubtitle();
            if (beatAdapter != null) beatAdapter.setLatinNotation(latinNotation);
            if (chordDiagrams != null) chordDiagrams.setNotation(latinNotation, preferFlat, chordTypeface());
        });
    }

    /** Flat vs sharp dialog. */
    private void showNotationDialog() {
        String[] labels = {
                getString(R.string.notation_sharp),
                getString(R.string.notation_flat)
        };
        int currentIdx = preferFlat ? 1 : 0;
        showSingleChoiceBlurDialog(R.string.notation_dialog_title, labels, currentIdx, which -> {
            boolean newFlat = (which == 1);
            if (newFlat == preferFlat) return;
            preferFlat = newFlat;
            PreferenceManager.getDefaultSharedPreferences(PlayerActivity.this)
                    .edit().putBoolean(PREF_PREFER_FLAT, preferFlat).apply();
            updateNotationDrawerSubtitle();
            if (beatAdapter != null) beatAdapter.setPreferFlat(preferFlat);
            if (chordDiagrams != null) chordDiagrams.setNotation(latinNotation, preferFlat, chordTypeface());
        });
    }

    /** Downbeat sound picker dialog: lists the `DownbeatSounds` catalog. */
    private void showDownbeatSoundDialog() {
        int n = DownbeatSounds.count();
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = getString(DownbeatSounds.labelRes(i));
        int currentIdx = (downbeatSoundType < 0 || downbeatSoundType >= n) ? 0 : downbeatSoundType;
        showSingleChoiceBlurDialog(R.string.downbeat_sound_dialog_title, labels, currentIdx, which -> {
            if (which == downbeatSoundType) return;
            downbeatSoundType = which;
            PreferenceManager.getDefaultSharedPreferences(PlayerActivity.this)
                    .edit().putInt(PREF_DOWNBEAT_TYPE, which).apply();
            if (tv_drawer_downbeat_subtitle != null) {
                tv_drawer_downbeat_subtitle.setText(labels[which]);
            }
            ensureDownbeatSoundLoaded();
        });
    }

    /** Simple callback for the single-choice dialog. */
    private interface SingleChoiceCallback { void onSelected(int which); }

    /** Custom dialog with a blurred background + active option in colorPrimary (same style
     *  as the MainActivity dialogs). Closes the drawer when open and inflates options dynamically. */
    private void showSingleChoiceBlurDialog(int titleRes, String[] labels, int currentIdx,
                                            SingleChoiceCallback cb) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.single_choice_dialog, null);
        TextView title = dialogView.findViewById(R.id.tv_singleChoiceTitle);
        LinearLayout opts = dialogView.findViewById(R.id.ll_singleChoiceOptions);
        com.google.android.material.button.MaterialButton cancel =
                dialogView.findViewById(R.id.b_singleChoiceCancel);

        title.setText(titleRes);

        int primary = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this);
        int white   = androidx.core.content.ContextCompat.getColor(this, R.color.AppWhite);

        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(
                this, R.style.Theme_Fision_BlurDialog)
                .setView(dialogView)
                .create();
        android.view.Window win = dlg.getWindow();
        if (win != null) win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        // Build each option as a TextView with rounded stroke (chip-like).
        // Active: colorPrimary stroke + soft tint + primary text + bold.
        // Inactive: grey stroke + white text.
        float density = getResources().getDisplayMetrics().density;
        int padH = (int) (14f * density);
        int padV = (int) (12f * density);
        int gapPx = (int) (8f * density);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView row = new TextView(this);
            row.setText(labels[i]);
            row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            row.setTypeface(
                    androidx.core.content.res.ResourcesCompat.getFont(this, R.font.yugothic_ui_regular),
                    idx == currentIdx ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            row.setTextColor(idx == currentIdx ? primary : white);
            row.setBackgroundResource(idx == currentIdx
                    ? R.drawable.dialog_option_active
                    : R.drawable.dialog_option_inactive);
            // Foreground = ripple over the stroke without losing the background
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                row.setForeground(androidx.core.content.ContextCompat.getDrawable(this, tv.resourceId));
            }
            row.setPadding(padH, padV, padH, padV);
            row.setClickable(true);
            row.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = gapPx;
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                dlg.dismiss();
                cb.onSelected(idx);
            });
            opts.addView(row);
        }

        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());
        cancel.setOnClickListener(v -> dlg.dismiss());
        showWithPlayerAccent(dlg);
    }

    /** Blurs the activity decorView (Android 12+). Only affects the background. */
    private void applyActivityBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().getDecorView().setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                            40f, 40f, android.graphics.Shader.TileMode.CLAMP));
            freezeSceneForBlur();
        }
    }

    private void removeActivityBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().getDecorView().setRenderEffect(null);
            unfreezeSceneAfterBlur();
        }
    }

    /** True while a scene snapshot is wanted (blur active). Guards the async
     *  PixelCopy result against a blur removed before it lands. */
    private boolean sceneSnapshotWanted = false;

    /** The SceneView renders into its own surface, out of reach of the
     *  decorView blur. Copies the current frame (half resolution — it is
     *  blurred anyway) into a regular ImageView over the surface. */
    private void freezeSceneForBlur() {
        if (!is3DViewShown || !isAudio3DScene()) return;
        android.view.SurfaceView sv = findViewById(R.id.v_3d_scene);
        android.widget.ImageView snap = findViewById(R.id.iv_3d_sceneSnapshot);
        if (sv == null || snap == null) return;
        int w = sv.getWidth() / 2;
        int h = sv.getHeight() / 2;
        if (w <= 0 || h <= 0 || !sv.getHolder().getSurface().isValid()) return;
        sceneSnapshotWanted = true;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.view.PixelCopy.request(sv, bmp, result -> {
            if (result == android.view.PixelCopy.SUCCESS && sceneSnapshotWanted) {
                snap.setImageBitmap(bmp);
                snap.setVisibility(View.VISIBLE);
            } else {
                bmp.recycle();
            }
        }, new Handler(android.os.Looper.getMainLooper()));
    }

    private void unfreezeSceneAfterBlur() {
        sceneSnapshotWanted = false;
        android.widget.ImageView snap = findViewById(R.id.iv_3d_sceneSnapshot);
        if (snap == null || snap.getVisibility() != View.VISIBLE) return;
        android.graphics.drawable.Drawable d = snap.getDrawable();
        snap.setVisibility(View.GONE);
        snap.setImageDrawable(null);
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b != null) b.recycle();
        }
    }

    // ========================== EXPORT (wizard) ==========================
    // A single AlertDialog that mutates its content between steps with fades. Keeps
    // the decorView blur alive during the entire wizard session (previously it
    // flickered because of dismiss + show per step) and allows navigation back.
    //
    // Possible steps:
    //   PERM           : explanation + "Open Settings" button when MANAGE_EXTERNAL_STORAGE is missing.
    //   MIX_OR_SEP     : current mixdown vs separate tracks.
    //   MIX_VOLUME     : default vs current volume (mix only).
    //   MIX_FORMAT     : format + "Include metronome" checkbox (mix only).
    //   SEP_FORMAT     : format (no metronome checkbox — separates never have it).
    //   STEM_FORMAT    : format + "Include metronome" checkbox (per-stem).
    //   PROGRESS       : ProgressBar + Cancel.
    private enum WizardStep { PERM, MIX_OR_SEP, MIX_VOLUME, MIX_FORMAT, SEP_FORMAT, STEM_FORMAT, PROGRESS }

    private ExportManager activeExporter;
    private androidx.appcompat.app.AlertDialog wizardDialog;
    private View wizardRoot;
    private TextView wizardTitle, wizardProgressSubtitle;
    private LinearLayout wizardOptions, wizardExtras, wizardProgressBox;
    private com.google.android.material.checkbox.MaterialCheckBox wizardIncludeMetronome;
    private android.widget.ProgressBar wizardProgressBar;
    private com.google.android.material.button.MaterialButton wizardBackBtn, wizardCancelBtn, wizardSaveBtn;
    private java.util.ArrayDeque<WizardStep> wizardStack = new java.util.ArrayDeque<>();
    private ExportManager.VolumeSource pendingVolumeSource = ExportManager.VolumeSource.DEFAULT;
    private boolean pendingIncludeMetronome = false;
    private String  pendingSingleStem = null;
    // Entry mode: signals whether the flow is drawer (mix/sep) or per-stem.
    private boolean wizardForSingleStem = false;

    private void showExportChoiceDialog() {
        wizardForSingleStem = false;
        pendingSingleStem  = null;
        pendingIncludeMetronome = false;
        WizardStep first = needsAllFilesPermission() ? WizardStep.PERM : WizardStep.MIX_OR_SEP;
        openWizard(first);
    }

    /** Per-stem entry (bottom-sheet ic_export button). */
    private void showExportStemFormatChoice(String stem) {
        wizardForSingleStem = true;
        pendingSingleStem  = stem;
        pendingIncludeMetronome = false;
        WizardStep first = needsAllFilesPermission() ? WizardStep.PERM : WizardStep.STEM_FORMAT;
        openWizard(first);
    }

    private boolean needsAllFilesPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !android.os.Environment.isExternalStorageManager();
    }

    private void openWizard(WizardStep first) {
        wizardStack.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        wizardRoot = inflater.inflate(R.layout.export_wizard_dialog, null);

        wizardTitle            = wizardRoot.findViewById(R.id.tv_wizardTitle);
        wizardOptions          = wizardRoot.findViewById(R.id.ll_wizardOptions);
        wizardExtras           = wizardRoot.findViewById(R.id.ll_wizardExtras);
        wizardIncludeMetronome = wizardRoot.findViewById(R.id.cb_wizardIncludeMetronome);
        wizardProgressBox      = wizardRoot.findViewById(R.id.ll_wizardProgress);
        wizardProgressSubtitle = wizardRoot.findViewById(R.id.tv_wizardProgressSubtitle);
        wizardProgressBar      = wizardRoot.findViewById(R.id.pb_wizardExport);
        wizardBackBtn          = wizardRoot.findViewById(R.id.b_wizardBack);
        wizardCancelBtn        = wizardRoot.findViewById(R.id.b_wizardCancel);
        wizardSaveBtn          = wizardRoot.findViewById(R.id.b_wizardSave);

        wizardIncludeMetronome.setOnCheckedChangeListener((b, isChecked) ->
                pendingIncludeMetronome = isChecked);

        wizardBackBtn.setOnClickListener(v -> goBackWizardStep());
        wizardCancelBtn.setOnClickListener(v -> {
            if (activeExporter != null) activeExporter.cancel();
            wizardDialog.dismiss();
        });

        wizardDialog = new androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Fision_BlurDialog)
                .setView(wizardRoot)
                .setCancelable(false)
                .create();
        android.view.Window win = wizardDialog.getWindow();
        if (win != null) win.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

        applyActivityBlur();
        wizardDialog.setOnDismissListener(d -> removeActivityBlur());
        pushWizardStep(first);
        showWithPlayerAccent(wizardDialog);
        // Force the same width as single_choice_dialog (300dp). The Back+Cancel
        // footer was making the root wrap_content grow larger than intended.
        if (win != null) {
            float density = getResources().getDisplayMetrics().density;
            int widthPx = (int) (300f * density);
            win.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void pushWizardStep(WizardStep step) {
        wizardStack.push(step);
        renderWizardStep(/*animate=*/ true);
    }

    private void goBackWizardStep() {
        if (wizardStack.size() <= 1) {
            wizardDialog.dismiss();
            return;
        }
        wizardStack.pop();
        renderWizardStep(true);
    }

    private void renderWizardStep(boolean animate) {
        Runnable apply = () -> {
            WizardStep step = wizardStack.peek();
            wizardOptions.removeAllViews();
            wizardExtras.setVisibility(View.GONE);
            wizardProgressBox.setVisibility(View.GONE);
            wizardSaveBtn.setVisibility(View.GONE);
            wizardBackBtn.setVisibility(wizardStack.size() > 1 ? View.VISIBLE : View.INVISIBLE);
            switch (step) {
                case PERM:        buildStepPerm();        break;
                case MIX_OR_SEP:  buildStepMixOrSep();    break;
                case MIX_VOLUME:  buildStepMixVolume();   break;
                case MIX_FORMAT:  buildStepFormat(/*separate=*/false, /*stem=*/false); break;
                case SEP_FORMAT:  buildStepFormat(true, false); break;
                case STEM_FORMAT: buildStepFormat(false, true); break;
                case PROGRESS:    buildStepProgress();    break;
            }
            // Freshly built step views carry the theme accent.
            recolorToPlayerAccent(wizardOptions.getRootView());
            // Body fade-in
            wizardOptions.setAlpha(0f);
            wizardOptions.animate().alpha(1f).setDuration(140).start();
            wizardExtras.setAlpha(0f);
            wizardExtras.animate().alpha(1f).setDuration(140).start();
        };
        if (animate && wizardOptions.getAlpha() > 0f) {
            wizardOptions.animate().alpha(0f).setDuration(110)
                    .withEndAction(apply).start();
        } else {
            apply.run();
        }
    }

    // -------- Per-step builders --------

    private void buildStepPerm() {
        wizardTitle.setText(R.string.export_permission_title);
        TextView body = new TextView(this);
        body.setText(R.string.export_permission_body);
        body.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.AppWhite));
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        body.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.yugothic_ui_regular));
        float density = getResources().getDisplayMetrics().density;
        body.setPadding((int)(8*density),(int)(4*density),(int)(8*density),(int)(12*density));
        wizardOptions.addView(body);
        // Single option: open Settings
        addOptionRow(getString(R.string.export_permission_continue), false, () -> {
            wizardDialog.dismiss();
            try {
                Intent it = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(it);
            } catch (Exception ignored) {}
        });
    }

    private void buildStepMixOrSep() {
        wizardTitle.setText(R.string.export_dialog_title);
        addOptionRow(getString(R.string.export_choice_mixdown), false,
                () -> pushWizardStep(WizardStep.MIX_VOLUME));
        addOptionRow(getString(R.string.export_choice_separate), false,
                () -> pushWizardStep(WizardStep.SEP_FORMAT));
    }

    private void buildStepMixVolume() {
        wizardTitle.setText(R.string.export_volume_dialog_title);
        addOptionRow(getString(R.string.export_volume_default), false, () -> {
            pendingVolumeSource = ExportManager.VolumeSource.DEFAULT;
            pushWizardStep(WizardStep.MIX_FORMAT);
        });
        addOptionRow(getString(R.string.export_volume_current), false, () -> {
            pendingVolumeSource = ExportManager.VolumeSource.CURRENT;
            pushWizardStep(WizardStep.MIX_FORMAT);
        });
    }

    /** Format step. {@code separate}=true → separate tracks (no metronome cb).
     *  {@code stem}=true → per-stem (metronome cb visible).
     *  MP3 omitted: Android does not expose a native MP3 encoder and rolling a
     *  custom one (LAME/Shine) would be massive. WAV/AAC/M4A cover the ground. */
    private void buildStepFormat(boolean separate, boolean stem) {
        wizardTitle.setText(R.string.export_format_dialog_title);
        addFormatRow(R.string.export_format_wav, ExportManager.Format.WAV, separate, stem);
        addFormatRow(R.string.export_format_aac, ExportManager.Format.AAC, separate, stem);
        addFormatRow(R.string.export_format_m4a, ExportManager.Format.M4A, separate, stem);
        // Metronome checkbox: visible in mix and per-stem; never on separates.
        if (!separate) {
            wizardExtras.setVisibility(View.VISIBLE);
            wizardIncludeMetronome.setChecked(pendingIncludeMetronome);
        }
    }

    private void addFormatRow(int labelRes, ExportManager.Format fmt, boolean separate, boolean stem) {
        addOptionRow(getString(labelRes), false, () -> launchExport(fmt, separate, stem));
    }

    private void buildStepProgress() {
        wizardTitle.setText(R.string.export_progress_title);
        wizardProgressBox.setVisibility(View.VISIBLE);
        wizardBackBtn.setVisibility(View.INVISIBLE);
        wizardProgressBar.setProgress(0);
    }

    /** Inserts a clickable chip-style row (same UX as showSingleChoiceBlurDialog). */
    private void addOptionRow(String label, boolean selected, Runnable onClick) {
        int primary = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this);
        int white   = androidx.core.content.ContextCompat.getColor(this, R.color.AppWhite);
        float density = getResources().getDisplayMetrics().density;
        int padH = (int) (14f * density);
        int padV = (int) (12f * density);
        int gap  = (int) (8f  * density);
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
        row.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.yugothic_ui_regular),
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        row.setTextColor(selected ? primary : white);
        row.setBackgroundResource(selected
                ? R.drawable.dialog_option_active
                : R.drawable.dialog_option_inactive);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Ripple with a rounded mask that matches the option-row corners.
            row.setForeground(androidx.core.content.ContextCompat.getDrawable(
                    this, R.drawable.settings_card_ripple));
        }
        row.setPadding(padH, padV, padH, padV);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (wizardOptions.getChildCount() > 0) lp.topMargin = gap;
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> onClick.run());
        wizardOptions.addView(row);
    }

    private void launchExport(ExportManager.Format format, boolean separate, boolean stem) {
        if (playbackService == null) return;
        File songFolder = new File(playbackService.getCurrentSongPath());
        String displayName = buildExportDisplayName();
        // Clears options and shows progress in the SAME dialog
        pushWizardStep(WizardStep.PROGRESS);

        File metronomeFile = null;
        float metronomeVolN = 0f;
        if (!separate && pendingIncludeMetronome) {
            metronomeFile  = resolveActiveMetronomeFile(songFolder);
            metronomeVolN  = Math.max(0f, Math.min(1f, metronomeVol * 0.01f));
        }
        activeExporter = new ExportManager(this);
        ExportManager.Listener listener = buildWizardListener();
        if (stem) {
            activeExporter.exportSingleStem(songFolder, displayName, pendingSingleStem,
                    format, metronomeFile, metronomeVolN, listener);
        } else if (separate) {
            activeExporter.exportSeparate(songFolder, displayName,
                    playbackService.getStemOrder(), format, listener);
        } else {
            activeExporter.exportMixdown(songFolder, displayName,
                    playbackService.getStemOrder(), stemSettings,
                    pendingVolumeSource, format,
                    metronomeFile, metronomeVolN, listener);
        }
    }

    /** Resolves the active metronome path (0=half, 1=normal, 2=double). Tries
     *  .flac first (new server format), .wav as fallback. */
    private File resolveActiveMetronomeFile(File songFolder) {
        String base;
        switch (selectedMetronome) {
            case 0:  base = "metronome_half";   break;
            case 2:  base = "metronome_double"; break;
            default: base = "metronome";        break;
        }
        File flac = new File(songFolder, base + ".flac");
        if (flac.exists()) return flac;
        File wav = new File(songFolder, base + ".wav");
        return wav.exists() ? wav : null;
    }

    private ExportManager.Listener buildWizardListener() {
        return new ExportManager.Listener() {
            @Override public void onProgress(int percent) {
                if (wizardProgressBar != null) wizardProgressBar.setProgress(percent);
            }
            @Override public void onDone(File outputFolder, int filesWritten) {
                if (wizardDialog != null) wizardDialog.dismiss();
                android.widget.Toast.makeText(PlayerActivity.this,
                        R.string.export_done, android.widget.Toast.LENGTH_LONG).show();
            }
            @Override public void onCancelled() {
                if (wizardDialog != null) wizardDialog.dismiss();
            }
            @Override public void onError(String message) {
                if (wizardDialog != null) wizardDialog.dismiss();
                android.widget.Toast.makeText(PlayerActivity.this,
                        getString(R.string.export_failed) + (message != null ? (": " + message) : ""),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        };
    }

    /** Output folder name: "Artist - Title" from metadata, folder name as fallback. */
    private String buildExportDisplayName() {
        SongMetadata md = playbackService != null ? playbackService.getCurrentMetadata() : null;
        if (md != null && md.displayTitle != null && md.artist != null
                && !md.displayTitle.isEmpty() && !md.artist.isEmpty()) {
            return md.artist + " - " + md.displayTitle;
        }
        return playbackService != null ? playbackService.getCurrentSongName() : "Export";
    }

    /** Computes the semitone shift from the selected pitch and the switch. */
    private int currentDisplayShift() {
        if (!transpositionAffectsChords) return 0;
        // numberPickerData.PITCH_VALUES indexes signed semitone cents; used directly
        try {
            float st = Float.parseFloat(numberPickerData.PITCH_VALUES[selectedPitch - 1]);
            return Math.round(st);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Reapplies the display shift to the adapter (after pitch or switch change). */
    private void applyDisplayShiftToAdapter() {
        if (beatAdapter != null) beatAdapter.setSemitonesShift(currentDisplayShift());
        if (chordDiagrams != null) chordDiagrams.setShift(currentDisplayShift());
    }

    /** Extracts a WAV from res/raw into a cache file so a path can be passed to the engine.
     *  Invalidates the cache when the APK has been updated since the last extraction,
     *  so res/raw changes are picked up after a rebuild without clearing app data. */
    private File extractClickWavToCache(int rawRes) {
        File out = new File(getCacheDir(), "dbclick_" + rawRes + ".wav");
        long apkUpdate = 0;
        try {
            apkUpdate = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) {}
        if (out.exists() && out.length() > 0 && out.lastModified() >= apkUpdate) {
            return out;
        }
        try (java.io.InputStream is = getResources().openRawResource(rawRes);
             java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            return out;
        } catch (java.io.IOException e) {
            Log.e("PlayerActivity", "extractClickWavToCache failed", e);
            return null;
        }
    }

    /** Ensures the current-type WAV is loaded in the engine (lazy + idempotent). */
    private void ensureDownbeatSoundLoaded() {
        if (engine == null) return;
        if (loadedDownbeatType == downbeatSoundType) return;
        int res = DownbeatSounds.fileRes(downbeatSoundType);
        File f = extractClickWavToCache(res);
        if (f == null) return;
        if (engine.setClickWav(f.getAbsolutePath())) {
            loadedDownbeatType = downbeatSoundType;
            // Reapply the enable flag, the duck track and the queue
            engine.setClickEnabled(downbeatSoundEnabled && metronomeEnabled);
            applyClickDuckTrack();
            rescheduleClicksFromCurrentBeat();
        }
    }

    /** Clears the engine queue and reschedules triggers from the current beat onwards. */
    private void rescheduleClicksFromCurrentBeat() {
        if (engine == null) return;
        engine.clearClickSchedule();
        lastScheduledDownbeatIdx = -1;
        if (!downbeatSoundEnabled || !metronomeEnabled) return;
        int from = Math.max(0, currentBeatIdx);
        for (int i = from; i < beatCells.size(); i++) {
            if (beatCells.get(i).isDownbeat) {
                engine.scheduleClickAt((long)(beatCells.get(i).time * 1000f));
                lastScheduledDownbeatIdx = i;
            }
        }
    }

    /** Sync with the engine which track gets ducked during the click (the active
     *  metronome, or -1 when not applicable). */
    private void applyClickDuckTrack() {
        if (engine == null) return;
        int duckId = -1;
        if (downbeatSoundEnabled && metronomeEnabled) {
            switch (selectedMetronome) {
                case 0: duckId = metronomeHalfTrack;   break;
                case 1: duckId = metronomeTrack;       break;
                case 2: duckId = metronomeDoubleTrack; break;
            }
        }
        engine.setClickDuckTrack(duckId);
    }

    /** Schedules every future downbeat in the engine starting from currentBeatIdx + 1.
     *  The engine fires them sample-accurate when the song position crosses them. */
    private void scheduleUpcomingDownbeats() {
        if (!downbeatSoundEnabled || !metronomeEnabled || engine == null) return;
        ensureDownbeatSoundLoaded();
        int from = Math.max(lastScheduledDownbeatIdx + 1, currentBeatIdx + 1);
        if (from < 0) from = 0;
        for (int i = from; i < beatCells.size(); i++) {
            if (beatCells.get(i).isDownbeat) {
                engine.scheduleClickAt((long)(beatCells.get(i).time * 1000f));
                lastScheduledDownbeatIdx = i;
            }
        }
    }

    /** Clears the click queue in the engine. Called after seek / song change. */
    private void cancelPendingDownbeats() {
        if (engine != null) engine.clearClickSchedule();
        lastScheduledDownbeatIdx = -1;
    }

    /** Applies the phase shift to the 0.5x metronome track based on the variant.
     *  Variante A = 0 (sin offset). Variante B = +1 beat = intervalo local entre beats.
     *  The interval between the two beats closest to the destination is used: this
     *  way the shift adapts if the song has tempo changes (rubato, accelerando).
     *  Track loop=true → seek positiva se interpreta como fase. */
    private void applyMetronomeHalfPhase(long songMs) {
        if (engine == null || metronomeHalfTrack < 0) return;
        long shiftMs = (metronomeHalfVariant == 1) ? localBeatIntervalMs(songMs) : 0;
        engine.seekTo(metronomeHalfTrack, songMs + shiftMs);
    }

    /** Overload without explicit position: uses the current one. Useful after a
     *  variant toggle when there is no pending seek (the published position is reliable). */
    private void applyMetronomeHalfPhase() {
        if (engine == null) return;
        applyMetronomeHalfPhase(engine.getCurrentPositionMs());
    }

    /** Interval (ms) between the two beats surrounding the given position.
     *  If the position is out of range, uses the first/last available interval.
     *  Fallback al BPM global si no hay beats. */
    private long localBeatIntervalMs(long posMs) {
        if (beatsList == null || beatsList.size() < 2) {
            return (globalBpm > 0f) ? (long)(60000.0f / globalBpm) : 500L;
        }
        float posSec = posMs / 1000f;
        // Locate i such that beatsList[i] <= posSec < beatsList[i+1]
        int i = 0;
        while (i + 1 < beatsList.size() && beatsList.get(i + 1) <= posSec) i++;
        if (i + 1 >= beatsList.size()) i = beatsList.size() - 2; // clamp to the last interval
        float interval = beatsList.get(i + 1) - beatsList.get(i);
        if (interval <= 0f) interval = 0.5f; // sanity
        return (long)(interval * 1000f);
    }

    /** Binds the drawer switch that persists the playlist continuation flag on
     *  the service. Must run after the service is connected: the switch state
     *  is read from the service and the listener needs a live reference to
     *  push the change back. */
    private void bindPlaylistSwitch() {
        com.google.android.material.switchmaterial.SwitchMaterial sw =
                findViewById(R.id.sw_drawer_playlist);
        if (sw == null || playbackService == null) return;
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(playbackService.isPlaylistMode());
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            if (playbackService != null) playbackService.setPlaylistMode(isChecked);
        });
    }

    /** Cycles the repeat mode OFF → ALL → ONE → OFF and refreshes the icon/tint. */
    private void cycleRepeatMode() {
        if (playbackService == null) return;
        PlaybackService.RepeatMode next;
        switch (playbackService.getRepeatMode()) {
            case OFF: next = PlaybackService.RepeatMode.ALL; break;
            case ALL: next = PlaybackService.RepeatMode.ONE; break;
            default:  next = PlaybackService.RepeatMode.OFF;
        }
        playbackService.setRepeatMode(next);
        refreshRepeatButtonUi();
    }

    /** Repeat button visual sync: ic_repeat (white)=OFF, ic_repeat (primary)=ALL,
     *  ic_repeat_one (primary)=ONE. */
    private void refreshRepeatButtonUi() {
        if (b_repeat == null || playbackService == null) return;
        PlaybackService.RepeatMode mode = playbackService.getRepeatMode();
        int iconRes;
        int tintColor;
        switch (mode) {
            case ALL:
                iconRes = R.drawable.ic_repeat;
                tintColor = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this);
                break;
            case ONE:
                iconRes = R.drawable.ic_repeat_one;
                tintColor = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this);
                break;
            case OFF:
            default:
                iconRes = R.drawable.ic_repeat;
                tintColor = ContextCompat.getColor(this, R.color.white);
                break;
        }
        b_repeat.setIconResource(iconRes);
        b_repeat.setIconTint(android.content.res.ColorStateList.valueOf(tintColor));
    }

    /** Previous button: rewind to 00:00 when the current position is past a
     *  short threshold, otherwise jump to the previous song. Mirrors the
     *  behaviour of common music players and matches the Bluetooth handler. */
    private void handlePreviousRequest() {
        long pos = 0;
        if (playbackService != null) pos = playbackService.getCurrentPositionMs();
        else if (engine != null)      pos = engine.getCurrentPositionMs();
        if (pos > PlaybackService.REWIND_THRESHOLD_MS) {
            if (playbackService != null) playbackService.seekTo(0);
            else if (engine != null)     engine.seekTo(0);
            sb_progressBar.setProgress(0);
            tv_currentPos.setText("00:00");
            currentBeatIdx = -1;
            chordCounter   = 0;
            beatCounter    = 1;
            cancelPendingDownbeats();
            applyMetronomeHalfPhase(0);
            if (beatAdapter != null) beatAdapter.setHighlight(-1);
            if (!chordList.isEmpty()) rvChords.scrollToPosition(0);
            return;
        }
        openAdjacentSong(-1);
    }

    /** Loads the adjacent song (direction = +1 next, -1 previous) without restarting the
     *  Activity. Same global ordering as MainActivity. Wrap-around. */
    private void openAdjacentSong(int direction) {
        if (SongFolder == null) return;
        File parent = SongFolder.getParentFile();
        if (parent == null || !parent.isDirectory()) return;
        File[] subdirs = parent.listFiles(File::isDirectory);
        if (subdirs == null || subdirs.length < 2) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final int sortOrder = prefs.getInt("songs_sort_order", 0);
        java.util.Arrays.sort(subdirs, (a, b) -> sortOrder == 0
                ? Long.compare(b.lastModified(), a.lastModified())
                : Long.compare(a.lastModified(), b.lastModified()));

        int idx = -1;
        for (int i = 0; i < subdirs.length; i++) {
            if (subdirs[i].getName().equals(SongFolder.getName())) { idx = i; break; }
        }
        if (idx < 0) return;
        int target = ((idx + direction) % subdirs.length + subdirs.length) % subdirs.length;
        switchToSong(subdirs[target]);
    }

    // Song change under the loading curtain: it fades in over the current song,
    // the new one loads behind it (async decode, no freeze) and it lifts once
    // the player is fully rebuilt and the new song's colors are applied (or a
    // timeout passes), after the 3D scene has rendered a few frames of the new
    // state. Autoplay and the slider animation start as it lifts.
    private static final long REVEAL_PALETTE_TIMEOUT_MS = 1500L;
    private static final long REVEAL_FRAME_FALLBACK_MS = 600L;
    /** Song folder whose palette was last applied (reveal gate). */
    private File songPaletteFolder;
    /** Latest song requested while a switch is still behind the curtain. */
    @androidx.annotation.Nullable private File queuedSongSwitch;
    /** False when nothing was loaded behind the curtain (song already in the
     *  service): it lifts as a standard spinner, without closing the ring. */
    private boolean curtainResolves = true;

    /** Switches to a song within the current Activity, under the curtain. */
    private void switchToSong(File newFolder) {
        if (playbackService == null || newFolder == null) return;
        if (loadingCurtain != null) {
            // A load is already behind the curtain: the latest request wins
            // and runs once that load is ready.
            queuedSongSwitch = newFolder;
            return;
        }
        loadingCurtain = com.jotapeveloper.fision.brand.LoadingCurtain.showFading(this,
                () -> { if (!isDestroyed) switchToSongNow(newFolder); });
    }

    /** Lifts the loading curtain once the new song's palette is applied (or a
     *  timeout passes) and, with the 3D scene on screen, after it rendered a
     *  few frames. A song queued meanwhile is switched to first. */
    private void revealCurtainWhenReady() {
        final com.jotapeveloper.fision.brand.LoadingCurtain curtain = loadingCurtain;
        if (curtain == null) { startPlayerReveal(); return; }
        final View host = findViewById(android.R.id.content);
        final File folder = SongFolder;
        final long deadline = android.os.SystemClock.uptimeMillis() + REVEAL_PALETTE_TIMEOUT_MS;
        final boolean[] revealed = { false };
        final Runnable reveal = () -> {
            if (revealed[0] || isDestroyed || loadingCurtain != curtain) return;
            revealed[0] = true;
            if (queuedSongSwitch != null) {
                File next = queuedSongSwitch;
                queuedSongSwitch = null;
                switchToSongNow(next);       // stays behind the same curtain
                return;
            }
            loadingCurtain = null;
            curtain.reveal(this::startPlayerReveal, curtainResolves);
            curtainResolves = true;
        };
        host.post(new Runnable() {
            @Override public void run() {
                if (isDestroyed || loadingCurtain != curtain) return;
                boolean paletteReady = folder != null && folder.equals(songPaletteFolder);
                if (!paletteReady && android.os.SystemClock.uptimeMillis() < deadline) {
                    host.postDelayed(this, 50L);
                    return;
                }
                if (is3DViewShown && isAudio3DScene() && audio3DScene != null) {
                    audio3DScene.runAfterFrames(3, reveal);
                    host.postDelayed(reveal, REVEAL_FRAME_FALLBACK_MS);
                } else {
                    reveal.run();
                }
            }
        });
    }

    /** Switches to a song within the current Activity: reloads files, recyclerviews,
     *  state, and triggers autoplay. The saved volumes are applied via
     *  readDataBase → updateUI (synchronous) → autoplayPending → performClick. */
    private void switchToSongNow(File newFolder) {
        if (playbackService == null || newFolder == null) return;
        curtainResolves = true;               // a real load: the curtain resolves

        // 1) Stop timer to avoid callbacks with mixed state
        stopProgressTimer();

        // 2) Reset song fields
        SongFolder = newFolder;
        SongName   = newFolder.getName();
        SongId     = SongName.hashCode();
        String SongPath = newFolder.getAbsolutePath();
        // Keep Intent in sync in case the Activity gets recreated
        getIntent().putExtra("SongPathString", SongPath);
        getIntent().putExtra("SongName",       SongName);
        getIntent().putExtra("SongId",         SongId);

        // 3) Reset song-specific state
        chordList.clear();
        beatsList.clear();
        beatCells.clear();
        stemSettings.clear();
        stemLastVol.clear();
        currentBeatIdx = -1;
        chordCounter   = 0;
        beatCounter    = 1;
        paused         = true;
        globalBpm      = 0f;
        lastScheduledDownbeatIdx = -1;
        if (engine != null) {
            engine.clearClickSchedule();
            engine.setClickEnabled(false);
            engine.setClickDuckTrack(-1);
        }
        loadedDownbeatType = -1;
        // The chord strip's data was just cleared and its new adapter only
        // arrives after the asynchronous load: detach the old one so layout
        // and prefetch never see stale positions meanwhile (behind the curtain).
        stopChordsBrowsing();
        if (rvChords != null) {
            if (pendingTileHighlight != null) rvChords.removeCallbacks(pendingTileHighlight);
            rvChords.stopScroll();
            rvChords.setAdapter(null);
        }

        chordsBasicMode    = false;
        hasBasicChordsFile = new File(SongPath, "chords_basic.txt").exists();

        // 4) Re-read text files
        readChordsFile(SongPath);
        readBeatsFile(SongPath);
        filterGhostChords();

        // 5) Load into the service off the main thread (the previous song is
        // released at once; the stale engine reference is dropped meanwhile).
        engine = null;
        final File loadingFolder = newFolder;
        playbackService.loadSongAsync(SongPath, SongName, SongId, ok -> {
            if (isDestroyed || playbackService == null || !loadingFolder.equals(SongFolder)) return;
            if (!ok) {
                Toast.makeText(this, "Error opening song. Missing files.", Toast.LENGTH_LONG).show();
                if (loadingCurtain != null) {
                    com.jotapeveloper.fision.brand.LoadingCurtain c = loadingCurtain;
                    loadingCurtain = null;
                    queuedSongSwitch = null;
                    c.reveal(null);
                }
                return;
            }
            onSwitchedSongLoaded();
        });
    }

    /** Rest of switchToSongNow once the new song is loaded in the service. */
    private void onSwitchedSongLoaded() {
        // 6) Re-sync engine refs
        engine = playbackService.getEngine();
        metronomeTrack       = playbackService.getMetronomeTrack();
        metronomeHalfTrack   = playbackService.getMetronomeHalfTrack();
        metronomeDoubleTrack = playbackService.getMetronomeDoubleTrack();
        songDuration         = (int) playbackService.getDurationMs();

        // 7) UI: title, controls, new recyclers (replace the previous ones)
        SetControlsEnabled(true);
        PrepareControls();
        setupRecyclerViews();
        refreshRepeatButtonUi();
        bindPlaylistSwitch();
        applyPlayIcon(false, false);
        // Wipe any lyrics state from the previous song. If the overlay was open,
        // resetLyricsForNewSong triggers refreshLyricsViewForCurrentSong so the
        // new song's cached lyrics.json (if any) resolves to CONTENT; otherwise
        // it lands on the picker.
        resetLyricsForNewSong();

        // 8) Pending autoplay: updateUI applies saved volumes before pressing play
        autoplayPending = true;

        // 9) Read DB async → updateUI triggers autoplay with the correct volumes
        readDataBase(SongId);
        setupTimers();

        // 10) Refresh visibility of the "Reprocess" item based on whether the new
        // song exists in CloudRepository (came from the server or is LOCAL_ONLY).
        refreshReprocessDrawerVisibility();

        // 11) Song colors (nebula, player accent, 3D room) follow the new
        // artcover. Reset the nebula handshake so the previous song's colours
        // fade out cleanly and the new palette fades in only when it has
        // actually been extracted.
        if (nebulaView != null) nebulaView.resetPaletteFade();
        refreshSongPalette();
    }

    /** Seek button (5s) bounce: scale-down on press, scale-up overshoot on release.
     *  Works whether the finger releases inside (ACTION_UP) or outside (ACTION_CANCEL). */
    private void attachSeekButtonBounce(View btn) {
        btn.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.92f).scaleY(0.92f)
                            .setDuration(80).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1.1f).scaleY(1.1f)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                            .setDuration(110)
                            .withEndAction(() -> v.animate()
                                    .scaleX(1f).scaleY(1f)
                                    .setDuration(120).start())
                            .start();
                    break;
            }
            return false; // let the MaterialButton onClick through
        });
    }

    /** Animates the "+" / "-" sign rising from the base of the button up to half its height,
     *  fade-in, hold ~500ms and fade-out. Called from moveForward / moveBackward. */
    private void animateSeekSign(TextView sign) {
        if (sign == null) return;
        // Reset in case a previous animation was left mid-way
        sign.animate().cancel();
        sign.setTranslationY(0f);
        sign.setAlpha(0f);
        final float riseDp = -dpToPx(20f);
        sign.animate()
                .translationY(riseDp)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> sign.postDelayed(() ->
                        sign.animate()
                                .alpha(0f)
                                .setDuration(200)
                                .start(), 500))
                .start();
    }

    private float dpToPx(float dp) {
        return android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }

    /** Tints the A/B buttons based on which one is active (green = active). */
    private void tintHalfVariantButtons(TextView a, TextView b) {
        int active   = accent();
        int inactive = Color.rgb(255, 255, 255);
        if (a != null) a.setTextColor(metronomeHalfVariant == 0 ? active : inactive);
        if (b != null) b.setTextColor(metronomeHalfVariant == 1 ? active : inactive);
    }

    /** Fade-out of the recycler when the song ends, jump to position 0 without visible
     *  scroll (a scan through hundreds of tiles would look ugly) and fade-in. */
    private void fadeChordsToStart() {
        if (rvChords == null) return;
        rvChords.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            rvChords.scrollToPosition(0);
            // Wait one frame so the layout applies the scroll before the fade-in
            rvChords.post(() ->
                    rvChords.animate().alpha(1f).setDuration(220).start());
        }).start();
    }

    /** Simple Basic / Advanced selection dialog. Persisted per song in Room. */
    private void showChordsModeDialog() {
        // If there is no chords_basic.txt the dialog is pointless
        if (!hasBasicChordsFile) return;
        String[] labels = {
                getString(R.string.chords_basic),
                getString(R.string.chords_advanced)
        };
        int currentIdx = chordsBasicMode ? 0 : 1;
        showSingleChoiceBlurDialog(R.string.chords_dialog_title, labels, currentIdx, which -> {
            boolean newBasic = (which == 0);
            if (newBasic == chordsBasicMode) return;
            chordsBasicMode = newBasic;
            updateDB("chordsBasic", chordsBasicMode ? 1 : 0);
            updateChordsDrawerSubtitle();
            reloadChords();
        });
    }

    /** Reloads chordList from disk and rebuilds the recycler cells. */
    private void reloadChords() {
        if (SongFolder == null) return;
        readChordsFile(SongFolder.getAbsolutePath());
        filterGhostChords();
        buildBeatCells();
        if (chordDiagrams != null) chordDiagrams.setCells(beatCells);
        if (beatAdapter != null) {
            beatAdapter.notifyDataSetChanged();
            // Reposition to the current beat
            if (engine != null) relocateChords((int) engine.getCurrentPositionMs(), chordList);
        }
    }

    // Swaps the play/pause icon on b_play. Uses an AVD morph when animate=true
    // (state change during playback), or the static vector when animate=false
    // (initial setup / song switch) to avoid firing an animation on entry.
    private void applyPlayIcon(boolean playing, boolean animate) {
        if (b_play == null) return;
        if (playIconInitialized && playIconState == playing) return;
        if (!playIconInitialized || !animate) {
            b_play.setIconResource(playing ? R.drawable.avd_ic_pause : R.drawable.avd_ic_play);
        } else {
            b_play.setIconResource(playing ? R.drawable.avd_play_to_pause : R.drawable.avd_pause_to_play);
            android.graphics.drawable.Drawable icon = b_play.getIcon();
            if (icon instanceof android.graphics.drawable.Animatable) {
                ((android.graphics.drawable.Animatable) icon).start();
            }
        }
        playIconState = playing;
        playIconInitialized = true;
    }

    @SuppressLint("ResourceType")
    public void Play(View view){
        if (playbackService == null) return;
        if (!paused){
            playbackService.pause();
            cancelPendingDownbeats();
            paused = true;
            applyPlayIcon(false, true);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            playbackService.play();
            applyPlayIcon(true, true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            paused = false;
        }
    }

    @SuppressLint("ResourceType")
    public void Stop(){
        StopMedia();
        applyPlayIcon(false, true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (playbackService != null) playbackService.releaseSongAndStop();
    }

    private void PauseMedia() {
        if (playbackService != null) playbackService.pause();
        paused = true;
    }

    private void PlayersSeekTo(int progressMs) {
        if (engine != null) engine.seekTo(progressMs);
    }

    /** Used to store per-stem positions; with the C++ engine that is no longer needed
     *  but the signature is kept so the callers (UI/seek) do not break. */
    private void DefineNewProgress(int newPos){ /* no-op */ }
    private void StopMedia(){
        // The service handles the state. Only UI here.
        tv_currentPos.setText("00:00");
        paused = true;
        sb_progressBar.setProgress(0);
        rvChords.scrollToPosition(0);
    }

    private class UpdateProgressBarTask extends TimerTask {
        @Override
        public void run() {
            if (isDestroyed) return;
            // One tick even while paused after coming back on screen: the
            // position may have changed in the background (notification,
            // end of song, seek from the mini-player).
            if (!paused || resyncOnNextTick) {
                resyncOnNextTick = false;
                executeFunction();
            }
        }

        private void executeFunction() {
            if (isDestroyed) return;
            if (engine == null) return;

            // Source-side position: what the engine is consuming right now,
            // typically ~100-250ms ahead of what the speaker is playing due
            // to Oboe + SoundTouch buffering. Used for end-of-song detection
            // (want to fire slightly before the audio actually ends).
            int CurrentPos = (int)engine.getCurrentPositionMs();
            // Perceived position: what the listener actually hears. This is
            // the value that must drive the UI (progress bar, time display,
            // chord highlight, nebula pulse), otherwise the visuals fire
            // ahead of the audio and everything feels off-beat.
            // BEAT_SYNC_OFFSET_MS accounts for the SoundTouch WSOLA latency
            // that Oboe getOutputLatencyMs() does not report — beat_this may
            // also mark the beat a few ms before the transient. Both stack on
            // top of the buffer latency and are subtracted here so the beat
            // detection aligns with what the ear actually hears.
            long latencyMs = engine.getOutputLatencyMs() + BEAT_SYNC_OFFSET_MS;
            int PerceivedPos = (int) Math.max(0L, CurrentPos - latencyMs);
            // Skip progress refresh while the user is dragging the SeekBar so
            // the manual thumb position is not overwritten by the timer tick.
            if (!mUserSeekingProgress) {
                // Same post-seek floor as the clock and the beat iterator.
                int barPos = PerceivedPos;
                int target = seekTargetMs;
                if (target >= 0 && android.os.SystemClock.uptimeMillis() - seekAtMs < SEEK_SETTLE_MS) {
                    barPos = Math.max(barPos, target);
                }
                int posPermille = songDuration > 0
                        ? (int) ((barPos * 10000L) / songDuration) : 0;
                if (posPermille < 0) posPermille = 0;
                if (posPermille > 10000) posPermille = 10000;
                // Quantised to whole pixels of the track: an unchanged value
                // is ignored by the bar, so it only redraws when the thumb
                // actually moves (a few times per second, not every tick).
                int trackPx = sb_progressBar.getWidth() - sb_progressBar.getPaddingLeft()
                        - sb_progressBar.getPaddingRight();
                if (trackPx > 0) {
                    posPermille = (int) ((long) Math.round(posPermille * trackPx / 10000f)
                            * 10000L / trackPx);
                }
                sb_progressBar.setProgress(posPermille);
            }

            // End of song: delegate to the service so repeat + playlist logic
            // stays in one place. The service returns what happened and the UI
            // updates accordingly (loop → reset counters only; stop → full fade).
            if (!paused && songDuration > 0 && CurrentPos >= songDuration - 250) {
                PlaybackService.EndOfSongResult result =
                        playbackService != null
                                ? playbackService.handleSongEnded()
                                : PlaybackService.EndOfSongResult.STOPPED;
                if (result == PlaybackService.EndOfSongResult.STOPPED) {
                    cancelPendingDownbeats();
                    applyMetronomeHalfPhase(0);
                    paused = true;
                    runOnUiThread(() -> {
                        applyPlayIcon(false, true);
                        sb_progressBar.setProgress(0);
                        tv_currentPos.setText("00:00");
                        chordCounter = 0;
                        beatCounter = 1;
                        currentBeatIdx = -1;
                        if (pendingTileHighlight != null) rvChords.removeCallbacks(pendingTileHighlight);
                        if (beatAdapter != null) beatAdapter.setHighlight(-1);
                        // Fade-to-black of the recycler, reposition to 0 without visible scroll, fade-in
                        fadeChordsToStart();
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    });
                }
                // LOOPED_SAME and ADVANCED_NEXT are handled via listeners
                // (onSongLooped / SkipRequestHandler) so no work is needed here.
                return;
            }

            final int UiPos = PerceivedPos;
            final int tickGeneration = seekGeneration;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // A tick computed before the latest seek carries the old
                    // position: drop it entirely.
                    if (tickGeneration != seekGeneration) return;

                    // Right after a seek the heard position still lags the
                    // target by the output latency: use the target as a floor
                    // (clock and beat), so neither steps back after the jump.
                    int shownPos = UiPos;
                    if (seekTargetMs >= 0) {
                        if (android.os.SystemClock.uptimeMillis() - seekAtMs < SEEK_SETTLE_MS) {
                            shownPos = Math.max(shownPos, seekTargetMs);
                        } else {
                            seekTargetMs = -1;
                        }
                    }

                    // The clock changes once per second: setText rebuilds the
                    // text layout, so skip the ticks that would not change it.
                    String tvNewCurrentPos = getTime(shownPos);
                    if (!tvNewCurrentPos.contentEquals(tv_currentPos.getText())) {
                        tv_currentPos.setText(tvNewCurrentPos);
                    }

                    // Current beat: find the last beat whose time <= shownPos
                    // Keeps running while the carousel is browsed (highlight,
                    // clicks, nebula, diagrams); only its auto-scroll pauses.
                    if (!relocating && !beatCells.isEmpty()) {
                        float tSec = shownPos / 1000f;
                        int idx = Math.min(currentBeatIdx, beatCells.size() - 1);
                        // Step back while the position is behind the current beat:
                        // a seek backwards, or a tick computed before a seek that
                        // advanced past its target. Without it the iterator only
                        // moves forward and the carousel stays stuck until the
                        // song catches up again.
                        while (idx > 0 && beatCells.get(idx).time > tSec) {
                            idx--;
                        }
                        // Linear advance while the next beat has already sounded
                        while (idx + 1 < beatCells.size() && beatCells.get(idx + 1).time <= tSec) {
                            idx++;
                        }
                        if (idx < 0) idx = 0;
                        if (idx != currentBeatIdx) {
                            currentBeatIdx = idx;
                            // Pre-schedule clicks + metronome mute for the next downbeats.
                            // Only when both switches are active (downbeat + metronome).
                            scheduleUpcomingDownbeats();
                            // Ambient nebula pulse. In 4/4 (or 6/8): soft on the
                            // odd beats (1/3/5), strong on the even ones (2/4/6).
                            // In ternary metres (3): only the downbeat pulses hard.
                            int beatPos = beatCells.get(idx).positionInBar;
                            float beatIntensity;
                            if (beatsPerBar == 3) {
                                beatIntensity = beatPos == 1 ? 0.95f : 0.30f;
                            } else {
                                beatIntensity = (beatPos % 2 == 0) ? 0.90f : 0.35f;
                            }
                            if (nebulaView != null && nebulaEnabled) {
                                nebulaView.pulse(beatIntensity);
                                triggerBeatHaptic(beatIntensity);
                            }
                            final int targetIdx = idx;
                            if (!browsingChords) scrollChordsTo(targetIdx);
                            // Delay the highlight so the tile lights up once it has already
                            // scrolled to the center. Cancel the previous one if still alive.
                            if (pendingTileHighlight != null) {
                                rvChords.removeCallbacks(pendingTileHighlight);
                            }
                            pendingTileHighlight = () -> {
                                if (beatAdapter != null) beatAdapter.setHighlight(targetIdx);
                                // Diagrams change together with the carousel highlight.
                                if (isChordDiagramShown && chordDiagrams != null) {
                                    chordDiagrams.onBeat(targetIdx);
                                }
                            };
                            rvChords.postDelayed(pendingTileHighlight, 80);

                            // Refresh the local BPM readout on every beat
                            // transition, so seek and coming back into the
                            // activity always show an up-to-date value.
                            updateLocalBpmDisplay(idx);
                        }
                    }

                    // Advance the lyrics highlight when the overlay is on top.
                    updateLyricsHighlight(UiPos);

                }
            });

        }

    }




    @NonNull
    private String getTime(int instant){
        String StringMinutes = "";
        String StringSeconds = "";
        int seconds = instant / 1000;
        int minutes = seconds / 60;
        int RemainingSeconds = seconds % 60;
        if (minutes < 10) {
            StringMinutes = "0" + String.valueOf(minutes);
        } else {
            StringMinutes = String.valueOf(minutes);
        }
        if (RemainingSeconds < 10) {
            StringSeconds = "0" + String.valueOf(RemainingSeconds);
        } else {
            StringSeconds = String.valueOf(RemainingSeconds);
        }

        return StringMinutes + ":" + StringSeconds;

    }

    public void SetControlsEnabled(boolean option){
        if (!option) {
            b_play.setEnabled(false);
            sb_progressBar.setEnabled(false);
            b_previous.setEnabled(false);
            b_next.setEnabled(false);
            b_seconds_back.setEnabled(false);
            b_seconds_forward.setEnabled(false);
            b_metronome.setEnabled(false);
            b_chord.setEnabled(false);

        }
        if (option){
            b_play.setEnabled(true);
            sb_progressBar.setEnabled(true);
            b_previous.setEnabled(true);
            b_next.setEnabled(true);
            b_seconds_back.setEnabled(true);
            b_seconds_forward.setEnabled(true);
            b_metronome.setEnabled(true);
            b_chord.setEnabled(true);
        }
    }

    public void PrepareControls(){
        if (playbackService != null) {
            songDuration = (int) playbackService.getDurationMs();
        }

        String StringSongDuration = getTime((int) songDuration);
        tv_duration.setText(StringSongDuration);

        // "Artist - Title" caption. Metadata.txt is the primary source; when
        // it is missing or empty (e.g. a server-side pipeline failure omitted
        // it from the ZIP) fall back to the CloudRepository entry — read only,
        // no local file synthesised, so the server stays as source of truth
        // and a later server-side fix propagates automatically.
        SongMetadata meta = playbackService != null ? playbackService.getCurrentMetadata() : null;
        String title  = (meta != null && meta.displayTitle != null && !meta.displayTitle.isEmpty())
                ? meta.displayTitle : null;
        String artist = (meta != null && meta.artist != null && !meta.artist.isEmpty())
                ? meta.artist : null;
        if ((title == null || artist == null) && SongFolder != null) {
            com.jotapeveloper.fision.cloud.CloudItem ci =
                    com.jotapeveloper.fision.cloud.CloudRepository.get(this)
                            .getByHash(SongFolder.getName());
            if (ci != null) {
                if (title == null && ci.displayName != null && !ci.displayName.isEmpty()) {
                    title = ci.displayName;
                    android.util.Log.w("PlayerActivity",
                            "metadata.txt missing title for " + SongFolder.getName()
                                    + " — CloudItem fallback in use");
                }
                if (artist == null && ci.artist != null && !ci.artist.isEmpty()) {
                    artist = ci.artist;
                }
            }
        }
        String shown;
        if (title != null) {
            shown = artist != null ? (artist + " - " + title) : title;
        } else {
            shown = SongName != null ? SongName : "";
        }
        FolderName = shown;
        tv_SongTitle.setText(FolderName);
        restartTitleMarquee();
        // 3D scene song card: mirror the same title/artist on the back
        // wall screen. Static, refreshed only when metadata changes.
        pushSongCardToScene(title, artist);
    }

    /** Feed the 3D scene's back-wall card with the current cover +
     *  title/artist. No-op when the scene has not been built yet — the
     *  controller caches the values and applies them at setup(). */
    private void pushSongCardToScene(String title, String artist) {
        if (audio3DScene == null) return;
        String t = title != null ? title : (SongName != null ? SongName : "");
        String a = artist != null ? artist : "";
        // The scene decodes the artwork off the main thread.
        java.io.File cover = SongFolder != null ? new java.io.File(SongFolder, "artcover.png") : null;
        audio3DScene.setSongCard(cover, t, a);
    }

    public void ShowChords(View view){
        // In ITD view the button should not react: the chords animation does not fit
        // with the 3D overlay and leaves artifacts. Only the flag is persisted.
        if (is3DViewShown) {
            chordsActive = !chordsActive;
            b_chord.setIconTint(ColorStateList.valueOf(chordsActive
                    ? accent() : Color.rgb(255,255,255)));
            updateDB("chords", chordsActive ? 1 : 0);
            return;
        }
        // In lyrics view, delegate the chord row visibility to the overlay
        // helper so the toggle takes effect live without needing to close
        // and reopen the lyrics view. Animate alpha on the chord row and
        // the recycler top padding so the change reads as a smooth fade
        // instead of a snap.
        if (isLyricsViewShown) {
            chordsActive = !chordsActive;
            b_chord.setIconTint(ColorStateList.valueOf(chordsActive
                    ? accent() : Color.rgb(255,255,255)));
            updateDB("chords", chordsActive ? 1 : 0);
            animateChordsOverlayForLyrics(chordsActive);
            return;
        }
        View chordsContainer = findViewById(R.id.chords_container);
        // Manual TransitionSet: the reflow (ChangeBounds) starts quickly so the
        // stems start moving right away; the fade runs in parallel but with tempo
        // fade is smoother, giving a dynamic feel.
        android.view.ViewGroup parent = (android.view.ViewGroup) chordsContainer.getParent();
        androidx.transition.TransitionSet set = new androidx.transition.TransitionSet();
        androidx.transition.ChangeBounds bounds = new androidx.transition.ChangeBounds();
        bounds.setDuration(320);
        bounds.setStartDelay(120); // fade starts alone, move enters afterwards
        bounds.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
        androidx.transition.Fade fade = new androidx.transition.Fade();
        fade.setDuration(300);
        set.addTransition(bounds);
        set.addTransition(fade);
        set.setOrdering(androidx.transition.TransitionSet.ORDERING_TOGETHER);
        // The tiles fade with their container, not one by one: on show the
        // recycler lays them out at the current beat, and per-tile fades would
        // cross-fade the old and new tiles on top of each other.
        if (rvChords != null) set.excludeChildren(rvChords, true);
        androidx.transition.TransitionManager.beginDelayedTransition(parent, set);

        if (chordsActive){
            chordsContainer.setVisibility(View.GONE);
            b_chord.setIconTint(ColorStateList.valueOf(Color.rgb(255,255,255)));
            chordsActive = false;
            updateDB("chords",0);
        } else {
            chordsContainer.setVisibility(View.VISIBLE);
            b_chord.setIconTint(ColorStateList.valueOf(accent()));
            chordsActive = true;
            updateDB("chords",1);
        }
    }

    public void moveForward(View view){
        movePlayer(1);
        animateSeekSign(tv_seconds_forward_sign);
    }

    public void moveBackward(View view){
        movePlayer(-1);
        animateSeekSign(tv_seconds_back_sign);
    }

    public void movePlayer(int direction) {
        int increment = 5000; // 5 seconds in ms
        long currentPos = engine.getCurrentPositionMs();
        long duration   = engine.getDurationMs();

        long newPos = currentPos + (direction * increment);
        if (newPos < 0) newPos = 0;
        if (newPos > duration) newPos = duration;

        // Same path as the progress bar: chords, highlight, metronome phase and
        // clicks follow the seek.
        seekToMs((int) newPos);

        // Resume if it was playing
        if (!paused) engine.resume();
    }

    @Override
    public void onBackPressed(){
        // Scene tuning panel open: only its pill closes it.
        if (sceneTuningOpen) return;
        // If the drawer is open, close it first
        if (drawerLayout != null && drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.END)) {
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.END);
            return;
        }
        // The service stays alive → music does not stop when closing the activity
        super.onBackPressed();
        finish();
    }

    /** Only releases the Activity's local resources. The service keeps running. */
    private void DestroyPlayers() {
        isDestroyed = true;
        stopProgressTimer();
    }

    public void enableMetronome(
            boolean isEnabled,
            MaterialButton metronome,
            com.google.android.material.card.MaterialCardView cardHalf,
            MaterialButton metronomeDouble, int selectedMetronome,
            Slider s_pan, Slider s_volume, ImageButton ib_restorePan) {

        if (isEnabled) {
            enableMetronomeButtons(true, metronome, cardHalf, metronomeDouble);
            selectButton(selectedMetronome, cardHalf, metronomeDouble, metronome);
            enableMetronomeSliders(true, s_volume, s_pan, ib_restorePan);

            android.util.Log.i("Metronome", "enable selected=" + selectedMetronome
                    + " vol=" + metronomeVol
                    + " tracks normal=" + metronomeTrack
                    + " half=" + metronomeHalfTrack
                    + " double=" + metronomeDoubleTrack);

            // Mute all first
            engine.setVolume(metronomeTrack,      0f, 0f);
            engine.setVolume(metronomeHalfTrack,  0f, 0f);
            engine.setVolume(metronomeDoubleTrack,0f, 0f);

            // Enable only selected one
            float vol = metronomeVol * 0.01f;
            switch (selectedMetronome) {
                case 0: // half
                    engine.setVolume(metronomeHalfTrack, vol, vol);
                    updateDB("selectedMetronome", 0);
                    break;

                case 1: // normal
                    engine.setVolume(metronomeTrack, vol, vol);
                    updateDB("selectedMetronome", 1);
                    break;

                case 2: // double
                    engine.setVolume(metronomeDoubleTrack, vol, vol);
                    updateDB("selectedMetronome", 2);
                    break;
            }

        } else {
            enableMetronomeButtons(false, metronome, cardHalf, metronomeDouble);
            enableMetronomeSliders(false, s_volume, s_pan, ib_restorePan);

            // Mute all metronome tracks
            engine.setVolume(metronomeTrack,      0f, 0f);
            engine.setVolume(metronomeHalfTrack,  0f, 0f);
            engine.setVolume(metronomeDoubleTrack,0f, 0f);
        }
    }

    private void enableMetronomeSliders(boolean isEnabled, Slider sVolume, Slider sPan, ImageButton ibRestorePan) {
        if(isEnabled) {
            sVolume.setEnabled(true);
            sPan.setEnabled(true);
            ibRestorePan.setEnabled(true);

            int ThumbColor = com.jotapeveloper.fision.design.AccentColorManager.resolveAccentSecondary(this);
            sVolume.setThumbTintList(ColorStateList.valueOf(ThumbColor));
            sPan.setThumbTintList(ColorStateList.valueOf(ThumbColor));


        }
        else{
            sVolume.setEnabled(false);
            sPan.setEnabled(false);
            ibRestorePan.setEnabled(false);

            int ThumbColor = ContextCompat.getColor(getApplicationContext(), R.color.AppDarkGrey);
            sVolume.setThumbTintList(ColorStateList.valueOf(ThumbColor));
            sPan.setThumbTintList(ColorStateList.valueOf(ThumbColor));
        }
    }

    private void selectButton(int selection,
                              com.google.android.material.card.MaterialCardView cardHalf,
                              MaterialButton metronomeDouble, MaterialButton metronome) {
        int active = accent();
        int inactive = Color.parseColor("#FFFFFF");
        cardHalf.setStrokeColor(selection == 0 ? active : inactive);
        metronome.setStrokeColor(ColorStateList.valueOf(selection == 1 ? active : inactive));
        metronomeDouble.setStrokeColor(ColorStateList.valueOf(selection == 2 ? active : inactive));
    }

    private void enableMetronomeButtons(boolean isEnabled, MaterialButton metronome,
                                        com.google.android.material.card.MaterialCardView cardHalf,
                                        MaterialButton metronomeDouble) {
        int strokeColor = isEnabled ? Color.parseColor("#FFFFFF") : Color.parseColor("#919191");
        int textColor   = strokeColor;

        metronome.setEnabled(isEnabled);
        metronomeDouble.setEnabled(isEnabled);
        cardHalf.setEnabled(isEnabled);

        metronome.setStrokeWidth(4);
        metronomeDouble.setStrokeWidth(4);
        cardHalf.setStrokeWidth(4);

        metronome.setStrokeColor(ColorStateList.valueOf(strokeColor));
        metronomeDouble.setStrokeColor(ColorStateList.valueOf(strokeColor));
        cardHalf.setStrokeColor(strokeColor);

        metronome.setTextColor(ColorStateList.valueOf(textColor));
        metronomeDouble.setTextColor(ColorStateList.valueOf(textColor));
        // The inner "0.5x" TextView is colored separately
        TextView halfLabel = cardHalf.findViewById(R.id.b_metronomeHalf);
        if (halfLabel != null) halfLabel.setTextColor(textColor);
    }

    public void showMetronomeBottomDialog(){
        BottomSheetDialog metronomeBottomSheet = new BottomSheetDialog(this, R.style.Theme_Fision_TransparentBottomSheetDialog);
        View bottomSheetView = getLayoutInflater().inflate(R.layout.metronome_modal_sheet_dialog, null);
        metronomeBottomSheet.setContentView(bottomSheetView);

        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) bottomSheetView.getParent());
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);

        SwitchMaterial sw_enableMetronome = metronomeBottomSheet.findViewById(R.id.sw_enableMetronome);
        MaterialButton b_metronomeNormal = metronomeBottomSheet.findViewById(R.id.b_metronome);
        MaterialButton b_metronomeDouble = metronomeBottomSheet.findViewById(R.id.b_metronomeDouble);
        com.google.android.material.card.MaterialCardView card_metronomeHalf =
                metronomeBottomSheet.findViewById(R.id.card_metronomeHalf);
        TextView b_metronomeHalf = metronomeBottomSheet.findViewById(R.id.b_metronomeHalf);
        LinearLayout panelHalfVariant = metronomeBottomSheet.findViewById(R.id.panel_half_variant);
        View halfVariantDivider = metronomeBottomSheet.findViewById(R.id.half_variant_divider_top);
        TextView b_metronomeHalfA = metronomeBottomSheet.findViewById(R.id.b_metronomeHalfA);
        TextView b_metronomeHalfB = metronomeBottomSheet.findViewById(R.id.b_metronomeHalfB);
        ImageButton ib_metronome_restorePan = metronomeBottomSheet.findViewById(R.id.ib_metronome_restorePan);
        Slider s_metronomeVolume = metronomeBottomSheet.findViewById(R.id.s_metronomeVolume);
        s_metronomeVolume.setValue(metronomeVol);
        Slider s_metronomePan = metronomeBottomSheet.findViewById(R.id.s_metronomePan);
        RulerPickerView rulerTempo   = metronomeBottomSheet.findViewById(R.id.ruler_tempo);
        TextView tvTempoRulerValue   = metronomeBottomSheet.findViewById(R.id.tv_tempoRulerValue);
        TextView tvTempoLocalHud     = metronomeBottomSheet.findViewById(R.id.tv_tempoLocalHud);
        ImageButton ibTempoRestore   = metronomeBottomSheet.findViewById(R.id.ib_tempoRestore);
        TextView tv_metronome_volume = metronomeBottomSheet.findViewById(R.id.tv_metronome_volume);
        TextView tv_metronome_panning = metronomeBottomSheet.findViewById(R.id.tv_metronome_panning);

        configureTempoRuler(rulerTempo, tvTempoRulerValue, tvTempoLocalHud);
        if (ibTempoRestore != null) {
            ibTempoRestore.setOnClickListener(v -> {
                // Return to the ruler's reference value (globalBpm in BPM mode
                // or 100 = 1.00x in the multiplier fallback). Uses the same
                // magnetic animation as the release snap so the transition
                // feels consistent with the rest of the ruler.
                int neutral = globalBpm > 0f ? Math.round(globalBpm) : 100;
                rulerTempo.animateToValue(neutral, 420L);
            });
        }

        sw_enableMetronome.setUseMaterialThemeColors(true);

        if (metronomeEnabled){
            sw_enableMetronome.setChecked(true);
            enableMetronome(metronomeEnabled,  b_metronomeNormal, card_metronomeHalf, b_metronomeDouble, selectedMetronome, s_metronomePan, s_metronomeVolume, ib_metronome_restorePan);
            tv_metronome_volume.setTextColor(Color.parseColor("#FFFFFF"));
            tv_metronome_panning.setTextColor(Color.parseColor("#FFFFFF"));
        }else{
            sw_enableMetronome.setChecked(false);
            enableMetronome(metronomeEnabled,  b_metronomeNormal, card_metronomeHalf, b_metronomeDouble, selectedMetronome, s_metronomePan, s_metronomeVolume, ib_metronome_restorePan);
            int tv_color = ContextCompat.getColor(getApplicationContext(), R.color.AppDarkGrey);
            tv_metronome_volume.setTextColor(ColorStateList.valueOf(tv_color));
            tv_metronome_panning.setTextColor(ColorStateList.valueOf(tv_color));
        }

        sw_enableMetronome.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if(isChecked){
                    b_metronome.setIconTint(ColorStateList.valueOf(accent()));
                    metronomeEnabled = true;
                    enableMetronome(metronomeEnabled,  b_metronomeNormal, card_metronomeHalf, b_metronomeDouble, selectedMetronome, s_metronomePan, s_metronomeVolume, ib_metronome_restorePan);
                    tv_metronome_volume.setTextColor(Color.parseColor("#FFFFFF"));
                    tv_metronome_panning.setTextColor(Color.parseColor("#FFFFFF"));
                    updateDB("metronome", 1);
                    if (downbeatSoundEnabled && engine != null) {
                        ensureDownbeatSoundLoaded();
                        engine.setClickEnabled(true);
                        applyClickDuckTrack();
                        rescheduleClicksFromCurrentBeat();
                    }
                }
                else{
                    b_metronome.setIconTint(ColorStateList.valueOf(Color.rgb(255,255,255)));
                    metronomeEnabled = false;
                    enableMetronome(metronomeEnabled,  b_metronomeNormal, card_metronomeHalf, b_metronomeDouble, selectedMetronome, s_metronomePan, s_metronomeVolume, ib_metronome_restorePan);
                    int tv_color = ContextCompat.getColor(getApplicationContext(), R.color.AppDarkGrey);
                    tv_metronome_volume.setTextColor(ColorStateList.valueOf(tv_color));
                    tv_metronome_panning.setTextColor(ColorStateList.valueOf(tv_color));
                    updateDB("metronome", 0);
                    if (engine != null) engine.setClickEnabled(false);
                    applyClickDuckTrack();
                    cancelPendingDownbeats();
                }
            }
        });

        // Expand/collapse animation for the 0.5x card. AutoTransition on the
        // bottom sheet root so the card grows/shrinks smoothly.
        final ViewGroup animRoot = (ViewGroup) bottomSheetView;
        Runnable expandHalfPanel = () -> {
            android.transition.TransitionManager.beginDelayedTransition(
                    animRoot,
                    new android.transition.AutoTransition().setDuration(220));
            if (panelHalfVariant != null) panelHalfVariant.setVisibility(View.VISIBLE);
            if (halfVariantDivider != null) halfVariantDivider.setVisibility(View.VISIBLE);
        };
        Runnable collapseHalfPanel = () -> {
            android.transition.TransitionManager.beginDelayedTransition(
                    animRoot,
                    new android.transition.AutoTransition().setDuration(220));
            if (panelHalfVariant != null) panelHalfVariant.setVisibility(View.GONE);
            if (halfVariantDivider != null) halfVariantDivider.setVisibility(View.GONE);
        };

        b_metronomeNormal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectedMetronome = 1;
                enableMetronome(metronomeEnabled,  b_metronomeNormal, card_metronomeHalf, b_metronomeDouble, selectedMetronome, s_metronomePan, s_metronomeVolume, ib_metronome_restorePan);
                cancelPendingDownbeats();
                applyClickDuckTrack();
                collapseHalfPanel.run();
            }
        });

        b_metronomeHalf.setOnClickListener(view -> {
            selectedMetronome = 0;
            enableMetronome(metronomeEnabled,  b_metronomeNormal, card_metronomeHalf, b_metronomeDouble, selectedMetronome, s_metronomePan, s_metronomeVolume, ib_metronome_restorePan);
            cancelPendingDownbeats();
            applyClickDuckTrack();
            expandHalfPanel.run();
            tintHalfVariantButtons(b_metronomeHalfA, b_metronomeHalfB);
        });

        b_metronomeDouble.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectedMetronome = 2;
                enableMetronome(metronomeEnabled,  b_metronomeNormal, card_metronomeHalf, b_metronomeDouble, selectedMetronome, s_metronomePan, s_metronomeVolume, ib_metronome_restorePan);
                cancelPendingDownbeats();
                applyClickDuckTrack();
                collapseHalfPanel.run();
            }
        });

        // Initial state of the A/B panel based on the selected metronome.
        // No animation to avoid a jump when the dialog opens.
        if (panelHalfVariant != null) {
            panelHalfVariant.setVisibility(selectedMetronome == 0 ? View.VISIBLE : View.GONE);
        }
        if (halfVariantDivider != null) {
            halfVariantDivider.setVisibility(selectedMetronome == 0 ? View.VISIBLE : View.GONE);
        }
        tintHalfVariantButtons(b_metronomeHalfA, b_metronomeHalfB);

        if (b_metronomeHalfA != null) {
            b_metronomeHalfA.setOnClickListener(v -> {
                if (metronomeHalfVariant != 0) {
                    metronomeHalfVariant = 0;
                    updateDB("metronomeHalfVariant", 0);
                    applyMetronomeHalfPhase();
                }
                tintHalfVariantButtons(b_metronomeHalfA, b_metronomeHalfB);
            });
        }
        if (b_metronomeHalfB != null) {
            b_metronomeHalfB.setOnClickListener(v -> {
                if (metronomeHalfVariant != 1) {
                    metronomeHalfVariant = 1;
                    updateDB("metronomeHalfVariant", 1);
                    applyMetronomeHalfPhase();
                }
                tintHalfVariantButtons(b_metronomeHalfA, b_metronomeHalfB);
            });
        }

        // Tempo control now lives inside configureTempoRuler above.

        // Label Formatters for sliders

        s_metronomeVolume.setLabelFormatter(new LabelFormatter() {
            @NonNull
            @Override
            public String getFormattedValue(float value) {
                if (value > 0){
                    return (int)value+"";
                }
                else{
                    return (int)(value*-1)+"";
                }

            }
        });

        s_metronomePan.setLabelFormatter(new LabelFormatter() {
            @NonNull
            @Override
            public String getFormattedValue(float value) {
                if (value > 0){
                    return (int)value+"%";
                }
                else{
                    return (int)(value*-1)+"%";
                }

            }
        });

        // Listener for volume
        s_metronomeVolume.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                metronomeVol = value;
                enableMetronome(metronomeEnabled,  b_metronomeNormal, card_metronomeHalf, b_metronomeDouble, selectedMetronome, s_metronomePan, s_metronomeVolume, ib_metronome_restorePan);
                updateDB("metronomeVolume", (int)value);
            }
        });

        // Listener for pan
        if (s_metronomePan.getValue() == 0.0){ib_metronome_restorePan.setVisibility(View.INVISIBLE);}

        s_metronomePan.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                if (fromUser) {
                    if (ib_metronome_restorePan.getVisibility() == View.INVISIBLE) {
                        ib_metronome_restorePan.setVisibility(View.VISIBLE);
                        Animation fadeIn = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fade_in);
                        ib_metronome_restorePan.startAnimation(fadeIn);
                    }

                    metronomePan = value;
                    updateDB("metronomePan", value);

                    float volL = metronomeVol * 0.01f;
                    float volR = metronomeVol * 0.01f;

                    if (value > 0) { // Pan right → reduce L
                        float targetToL = (100 - value) * 0.01f;
                        volL *= targetToL;
                    } else if (value < 0) { // Pan left → reduce R
                        float targetToR = (100 - Math.abs(value)) * 0.01f;
                        volR *= targetToR;
                    }

                    engine.setVolume(metronomeTrack, volL, volR);

                    if (value == 0) ib_metronome_restorePan.performClick();
                } else {
                    ib_metronome_restorePan.setVisibility(View.INVISIBLE);
                    // Restore centered pan
                    engine.setVolume(metronomeTrack, metronomeVol*0.01f, metronomeVol*0.01f);
                }
            }
        });

        ib_metronome_restorePan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(ib_metronome_restorePan.getVisibility()==View.VISIBLE) {
                    Animation FadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fade_out);
                    ib_metronome_restorePan.startAnimation(FadeOut);
                    ib_metronome_restorePan.setVisibility(View.INVISIBLE);

                    float pan_startPos = s_metronomePan.getValue();
                    float endPos = 0;
                    ObjectAnimator sb_animator = ObjectAnimator.ofFloat(s_metronomePan, "value", pan_startPos, endPos);
                    sb_animator.setDuration(150);
                    sb_animator.start();

                    metronomePan = 0.0f;
                    updateDB("metronomePan", 0f);
                    restoreVolume("metronome");
                }
            }
        });

        showWithPlayerAccent(metronomeBottomSheet);

    }


    public void showPitchBottomDialog() {
        BottomSheetDialog pitchBottomSheet = new BottomSheetDialog(this, R.style.Theme_Fision_TransparentBottomSheetDialog);
        View bottomSheetView = getLayoutInflater().inflate(R.layout.pitch_modal_sheet_dialog, null);

        pitchBottomSheet.setContentView(bottomSheetView);
        RulerPickerView rulerPitch  = bottomSheetView.findViewById(R.id.ruler_pitch);
        TextView tvPitchRulerValue  = bottomSheetView.findViewById(R.id.tv_pitchRulerValue);
        TextView tv_pitch_semitones = bottomSheetView.findViewById(R.id.tv_pitch_semitones);
        ImageButton ibPitchRestore  = bottomSheetView.findViewById(R.id.ib_pitchRestore);

        configurePitchRuler(rulerPitch, tvPitchRulerValue, tv_pitch_semitones);
        if (ibPitchRestore != null) {
            ibPitchRestore.setOnClickListener(v -> rulerPitch.animateToValue(0, 420L));
        }

        showWithPlayerAccent(pitchBottomSheet);
    }

    /** Sets up the tempo ruler with either BPM units (when globalBpm > 0) or a
     *  multiplier fallback. Also refreshes the "this section plays at X BPM"
     *  HUD from the current playback position. */
    private void configureTempoRuler(RulerPickerView ruler, TextView tvValue, TextView tvHud) {
        // Tempo is a finer adjustment — moderate spacing so many BPM fit on screen.
        ruler.setTickSpacingDp(14f);
        boolean bpmMode = globalBpm > 0f;
        if (bpmMode) {
            ruler.setStep(1);
            ruler.setRange(50, 200);
            int targetBpm = Math.round(globalBpm * (selectedTempoMultMilli / 1000f));
            targetBpm = Math.max(50, Math.min(200, targetBpm));
            ruler.setValue(targetBpm);
            ruler.setNeutralValue(Math.round(globalBpm));   // stronger haptic at original BPM
            ruler.setLabelFormatter(v -> getString(R.string.tempo_display_bpm, v));
            tvValue.setText(getString(R.string.tempo_display_bpm, targetBpm));
        } else {
            // Fallback: ruler counts mult * 100 in steps of 5 (0.50 .. 1.50, 0.05x)
            ruler.setStep(5);
            ruler.setRange(50, 150);
            int centi = Math.round(selectedTempoMultMilli / 10f);
            centi = Math.round(centi / 5f) * 5;
            centi = Math.max(50, Math.min(150, centi));
            ruler.setValue(centi);
            ruler.setNeutralValue(100);                     // 1.00x
            ruler.setLabelFormatter(v -> getString(R.string.tempo_display_mult, v / 100f));
            tvValue.setText(getString(R.string.tempo_display_mult, centi / 100f));
        }
        updateTempoLocalHud(tvHud);

        ruler.setOnValueChangeListener((value, fromUser) -> {
            int milli;
            CharSequence display;
            if (bpmMode) {
                milli = Math.round((value / globalBpm) * 1000f);
                display = getString(R.string.tempo_display_bpm, value);
            } else {
                milli = value * 10;   // centi to milli
                display = getString(R.string.tempo_display_mult, value / 100f);
            }
            tvValue.setText(display);
            if (!fromUser) return;
            selectedTempoMultMilli = milli;
            updateDB("tempoMultMilli", milli);
            float factor = milli / 1000f;
            if (playbackService != null && engine != null) {
                for (String s : playbackService.getStemOrder()) {
                    Integer t = playbackService.getStemTrack(s);
                    if (t != null) engine.setTempo(t, factor);
                }
                if (metronomeTrack >= 0)       engine.setTempo(metronomeTrack, factor);
                if (metronomeDoubleTrack >= 0) engine.setTempo(metronomeDoubleTrack, factor);
                if (metronomeHalfTrack >= 0)   engine.setTempo(metronomeHalfTrack, factor);
            }
        });
    }

    /** Refreshes the local-BPM HUD under the ruler. Shown whenever the local
     *  BPM at the playback cursor is computable — even when it matches the
     *  global BPM, so the reader always has the current section's tempo in
     *  view. Hidden only when the song has no beats data at all. */
    private void updateTempoLocalHud(TextView tvHud) {
        if (tvHud == null) return;
        int localBpm = computeLocalBpmAtCurrentPosition();
        if (localBpm <= 0) { tvHud.setVisibility(View.GONE); return; }
        tvHud.setText(androidx.core.text.HtmlCompat.fromHtml(
                getString(R.string.tempo_local_hud_html, localBpm),
                androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY));
        tvHud.setVisibility(View.VISIBLE);
    }

    /** Original BPM of the beat currently under the playback cursor (60 / delta
     *  to the next beat, from beatsList). Falls back to the global BPM when the
     *  position is before the first beat or past the last one, and returns 0
     *  only when the song has no usable beats at all. */
    private int computeLocalBpmAtCurrentPosition() {
        if (beatsList == null || beatsList.size() < 2) {
            return globalBpm > 0f ? Math.round(globalBpm) : 0;
        }
        long uiPos = 0L;
        if (engine != null) {
            uiPos = Math.max(0L, engine.getCurrentPositionMs() - engine.getOutputLatencyMs());
        }
        float tSec = uiPos / 1000f;
        int idx = -1;
        for (int i = 0; i < beatsList.size(); i++) {
            if (beatsList.get(i) > tSec) break;
            idx = i;
        }
        if (idx < 0 || idx >= beatsList.size() - 1) {
            return globalBpm > 0f ? Math.round(globalBpm) : 0;
        }
        float delta = beatsList.get(idx + 1) - beatsList.get(idx);
        if (delta <= 0f) return globalBpm > 0f ? Math.round(globalBpm) : 0;
        return Math.round(60f / delta);
    }

    /** Sets up the pitch ruler (-12..+12 semitones, step 1). Keeps `selectedPitch`
     *  (legacy 1-based index into PITCH_VALUES) in sync so drawer transposition
     *  and Room persistence continue to work unchanged. */
    private void configurePitchRuler(RulerPickerView ruler, TextView tvValue, TextView tvSubtitle) {
        // Pitch changes are more disruptive musically — wider ticks so each
        // semitone takes a deliberate horizontal drag.
        ruler.setTickSpacingDp(28f);
        ruler.setStep(1);
        ruler.setRange(-12, 12);
        ruler.setNeutralValue(0);                    // stronger haptic at unison
        int semi = pitchSemitonesFromIndex(selectedPitch);
        ruler.setValue(semi);
        ruler.setLabelFormatter(this::formatPitchDisplay);
        tvValue.setText(formatPitchDisplay(semi));
        applyPitchTint(tvSubtitle, semi != 0);

        ruler.setOnValueChangeListener((value, fromUser) -> {
            tvValue.setText(formatPitchDisplay(value));
            if (!fromUser) return;
            selectedPitch = pitchIndexFromSemitones(value);
            updateDB("pitchIndex", selectedPitch);
            if (playbackService != null && engine != null) {
                for (String s : playbackService.getStemOrder()) {
                    if (!StemSpec.isPitched(s)) continue;
                    Integer t = playbackService.getStemTrack(s);
                    if (t != null) engine.setPitch(t, value);
                }
            }
            applyPitchTint(tvSubtitle, value != 0);
            applyDisplayShiftToAdapter();
        });
    }

    private CharSequence formatPitchDisplay(int semitones) {
        return semitones == 0
                ? getString(R.string.pitch_display_zero)
                : getString(R.string.pitch_display_signed, semitones);
    }

    private void applyPitchTint(TextView tvSubtitle, boolean active) {
        int color = active ? accent() : Color.rgb(255, 255, 255);
        if (b_pitch != null) b_pitch.setIconTint(ColorStateList.valueOf(color));
        if (tvSubtitle != null) tvSubtitle.setTextColor(color);
    }

    private int pitchSemitonesFromIndex(int idx) {
        if (idx <= 0 || idx > numberPickerData.PITCH_VALUES.length) return 0;
        try { return (int) Float.parseFloat(numberPickerData.PITCH_VALUES[idx - 1]); }
        catch (Exception e) { return 0; }
    }

    private int pitchIndexFromSemitones(int semitones) {
        for (int i = 0; i < numberPickerData.PITCH_VALUES.length; i++) {
            try {
                if ((int) Float.parseFloat(numberPickerData.PITCH_VALUES[i]) == semitones) {
                    return i + 1;
                }
            } catch (Exception ignored) {}
        }
        return numberPickerData.CENTRAL_PITCH_INDEX;
    }

    public void showOptionsBottomDialog(String stemType){
        final StemSetting setting = StemSettingsCodec.getOrDefault(stemSettings, stemType);

        BottomSheetDialog optionsBottomSheet = new BottomSheetDialog(this, R.style.Theme_Fision_TransparentBottomSheetDialog);

        View bottomSheetView = getLayoutInflater().inflate(R.layout.player_modal_sheet_dialog, null);
        TextView title = bottomSheetView.findViewById(R.id.Title);
        ImageButton restorePan = bottomSheetView.findViewById(R.id.ib_restorePan);
        ImageButton exportStem = bottomSheetView.findViewById(R.id.ib_exportStem);
        if (exportStem != null) {
            exportStem.setOnClickListener(v -> {
                optionsBottomSheet.dismiss();
                showExportStemFormatChoice(stemType);
            });
        }
        boolean hasV2 = playbackService != null && playbackService.hasStem("vocals2");
        int titleRes = StemSpec.titleResource(stemType, hasV2);
        title.setText(titleRes != 0
                ? getString(titleRes)
                : stemType.substring(0, 1).toUpperCase() + stemType.substring(1));
        Slider pan = bottomSheetView.findViewById(R.id.s_pan);
        pan.setLabelFormatter(value -> (value > 0 ? (int)value : (int)-value) + "%");

        pan.setValue(setting.pan);
        if (pan.getValue() == 0.0f) restorePan.setVisibility(View.INVISIBLE);

        pan.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) { restoreVolume(stemType); return; }
            if (restorePan.getVisibility()==View.INVISIBLE){
                restorePan.setVisibility(View.VISIBLE);
                Animation fi = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fade_in);
                restorePan.startAnimation(fi);
            }
            setting.pan = value;
            persistStemSettings();
            applyStemVolumeToEngine(stemType);
            if ((int)value == 0) restorePan.performClick();
        });

        restorePan.setOnClickListener(view -> {
            if (restorePan.getVisibility() != View.VISIBLE) return;
            Animation fo = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fade_out);
            restorePan.startAnimation(fo);
            restorePan.setVisibility(View.INVISIBLE);
            ObjectAnimator sb_animator = ObjectAnimator.ofFloat(pan, "value", pan.getValue(), 0f);
            sb_animator.setDuration(150);
            sb_animator.start();
            setting.pan = 0f;
            persistStemSettings();
            applyStemVolumeToEngine(stemType);
        });

        optionsBottomSheet.setContentView(bottomSheetView);
        showWithPlayerAccent(optionsBottomSheet);
    }

    /** Re-applies volume + pan to the engine for a given stem. */
    private void applyStemVolumeToEngine(String stem) {
        Integer trackId = playbackService != null ? playbackService.getStemTrack(stem) : null;
        if (trackId == null || engine == null) return;
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        float vol = s.volume * 0.01f;
        float volL = vol, volR = vol;
        if (s.pan > 0) {
            volL = vol * (100f - s.pan) * 0.01f;
        } else if (s.pan < 0) {
            volR = vol * (100f - Math.abs(s.pan)) * 0.01f;
        }
        engine.setVolume(trackId, volL, volR);
    }

    public void restoreVolume(String stemType){
        if ("metronome".equals(stemType)) {
            if (engine != null && metronomeTrack >= 0)
                engine.setVolume(metronomeTrack, metronomeVol*0.01f, metronomeVol*0.01f);
            return;
        }
        applyStemVolumeToEngine(stemType);
    }

    /** Updates the "BPM" readout using the last {@link #LOCAL_BPM_WINDOW}
     *  beats around {@code beatIdx}. Averages the interval between them and
     *  converts to beats-per-minute. Safe to call from the timer thread's
     *  runOnUiThread block: only touches the TextView and reads beatsList. */
    private void updateLocalBpmDisplay(int beatIdx) {
        if (tv_bpm == null || beatsList == null) return;
        if (beatIdx < 1 || beatsList.size() < 2) return;
        int endIdx = Math.min(beatsList.size() - 1, beatIdx);
        int startIdx = Math.max(0, endIdx - LOCAL_BPM_WINDOW);
        int intervals = endIdx - startIdx;
        if (intervals < 1) return;
        float span = beatsList.get(endIdx) - beatsList.get(startIdx);
        if (span <= 0f) return;
        float avgInterval = span / intervals;
        int bpm = Math.round(60f / avgInterval);
        tv_bpm.setText("BPM: " + bpm);
    }

    // Carousel hidden: no smooth scroll (on a hidden recycler it keeps
    // animating every frame). The target is left as the layout manager's
    // pending position instead, so the first layout once shown already
    // places the current beat in the active slot (offset 0 = start padding).
    private void scrollChordsTo(int idx) {
        if (rvChords == null) return;
        if (rvChords.isShown()) {
            rvChords.smoothScrollToPosition(idx);
        } else if (rvChords.getLayoutManager() instanceof LinearLayoutManager) {
            ((LinearLayoutManager) rvChords.getLayoutManager())
                    .scrollToPositionWithOffset(Math.max(0, idx), 0);
        }
    }

    public void relocateChords(int pos, List<ChordsFile> chordList ){
        relocating = true;
        // Position the visualizer at the beat matching pos (ms)
        int idx = -1;
        float tSec = pos / 1000f;
        for (int i = 0; i < beatCells.size(); i++) {
            if (beatCells.get(i).time <= tSec) idx = i;
            else break;
        }
        int show = Math.max(0, idx);
        currentBeatIdx = idx;
        scrollChordsTo(show);
        // Cancel the timer's pending callback so it does not overwrite the seek highlight
        if (pendingTileHighlight != null && rvChords != null) {
            rvChords.removeCallbacks(pendingTileHighlight);
        }
        if (beatAdapter != null) beatAdapter.setHighlight(idx);
        if (isChordDiagramShown && chordDiagrams != null) chordDiagrams.onBeat(Math.max(0, idx));
        // Immediate BPM refresh so the readout follows a seek without waiting
        // for the next beat transition.
        updateLocalBpmDisplay(idx);
        relocating = false;
    }

    public void manualMoveProgressBar(int newPosMillis){
        if (engine == null) return;
        engine.seekTo(newPosMillis);
        // Only refresh UI if paused; if playing, the Timer already does it
        if (paused) DefineNewProgress(newPosMillis);
    }

    // Seek bookkeeping for the timer's beat iterator: ticks computed before the
    // latest seek are dropped (seekGeneration), and right after it the heard
    // position still lags the target by the output latency, so for
    // SEEK_SETTLE_MS the iterator uses the target as a floor instead of
    // stepping back one beat and forward again.
    private static final long SEEK_SETTLE_MS = 700L;
    private volatile int seekGeneration = 0;
    private volatile int seekTargetMs = -1;
    private volatile long seekAtMs = 0L;

    /** Single seek path (progress bar, chord carousel, chord diagrams): moves
     *  the engine and re-syncs chords, highlight, half metronome phase,
     *  downbeat clicks, progress bar and clock at once. */
    private void seekToMs(int newPosMillis) {
        if (engine == null) return;
        if (songDuration > 0) newPosMillis = Math.max(0, Math.min(songDuration - 1, newPosMillis));
        stopChordsBrowsing();                 // the carousel follows the seek
        seekGeneration++;
        seekTargetMs = newPosMillis;
        seekAtMs = android.os.SystemClock.uptimeMillis();
        engine.seekTo(newPosMillis);
        if (paused) DefineNewProgress(newPosMillis);
        if (songDuration > 0) {
            sb_progressBar.setProgress((int) ((long) newPosMillis * 10000L / songDuration));
        }
        tv_currentPos.setText(getTime(newPosMillis));
        relocateChords(newPosMillis, chordList);
        // Keep the half metronome phase after seek (engine.seekTo has not yet
        // updated the published position; the target is passed explicitly)
        applyMetronomeHalfPhase(newPosMillis);
        cancelPendingDownbeats();
    }

    // Chord carousel browsing: dragging moves it freely without seeking; the
    // magnet then pulls it back to the current beat. Close (<= MAGNET_NEAR
    // cells) it returns at once; farther it waits longer, so there is time to
    // read or tap a cell.
    private static final int MAGNET_NEAR = 2;
    private static final long MAGNET_BASE_MS = 1500L, MAGNET_PER_CELL_MS = 100L,
            MAGNET_MAX_MS = 4000L;
    private boolean browsingChords = false;
    private final Runnable chordsMagnet = () -> {
        browsingChords = false;
        if (rvChords != null && !beatCells.isEmpty()) {
            rvChords.smoothScrollToPosition(Math.max(0, currentBeatIdx));
        }
    };

    /** Schedules the return to the current beat for a release [distance]
     *  cells away from it. */
    private void scheduleChordsMagnet(int distance) {
        if (rvChords == null) return;
        rvChords.removeCallbacks(chordsMagnet);
        long delay = distance <= MAGNET_NEAR ? 0L
                : Math.min(MAGNET_MAX_MS, MAGNET_BASE_MS + MAGNET_PER_CELL_MS * distance);
        rvChords.postDelayed(chordsMagnet, delay);
    }

    /** Ends carousel browsing at once (seek, song change): it follows the
     *  song again from the next scroll. */
    private void stopChordsBrowsing() {
        browsingChords = false;
        if (rvChords != null) rvChords.removeCallbacks(chordsMagnet);
    }

    /** Jumps the song to the start of beat cell [beatIdx]. */
    private void seekToBeat(int beatIdx) {
        if (beatIdx < 0 || beatIdx >= beatCells.size()) return;
        seekToMs(Math.round(beatCells.get(beatIdx).time * 1000f));
    }

    // === Title marquee ===
    // A running marquee redraws every vsync, so it scrolls a few passes and
    // rests; any touch on the player starts it again once it has stopped.
    private static final int TITLE_MARQUEE_PASSES = 3;
    private long titleMarqueeBusyUntil = 0L;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                && android.os.SystemClock.uptimeMillis() > titleMarqueeBusyUntil) {
            restartTitleMarquee();
        }
        return super.dispatchTouchEvent(ev);
    }

    private void restartTitleMarquee() {
        if (tv_SongTitle == null) return;
        tv_SongTitle.setSelected(false);
        tv_SongTitle.setSelected(true);
        tv_SongTitle.post(() -> titleMarqueeBusyUntil =
                android.os.SystemClock.uptimeMillis() + estimateTitleMarqueeMs());
    }

    /** Duration of the passes, mirroring the framework marquee: 1.2 s start
     *  delay, 30 dp/s, each pass scrolls text width + a third of the view
     *  width, 1.2 s rest between passes. Errs long, so a touch never restarts
     *  a marquee still running (the text would jump back). 0 = text fits. */
    private long estimateTitleMarqueeMs() {
        int viewW = tv_SongTitle.getWidth() - tv_SongTitle.getTotalPaddingLeft()
                - tv_SongTitle.getTotalPaddingRight();
        CharSequence text = tv_SongTitle.getText();
        if (viewW <= 0 || text == null) return 0L;
        float textW = tv_SongTitle.getPaint().measureText(text, 0, text.length());
        if (textW <= viewW) return 0L;
        float pxPerSec = 30f * getResources().getDisplayMetrics().density;
        long passMs = (long) ((textW + viewW / 3f) / pxPerSec * 1000f) + 1200L;
        return 1200L + TITLE_MARQUEE_PASSES * passMs + 500L;
    }

    protected void onStop() {
        super.onStop();
        // Nothing on screen to update. The engine keeps its independent click
        // queue, so the click keeps sounding in sync with the song.
        uiStarted = false;
        cancelProgressTimer();
    }

    protected void onRestart() {
        super.onRestart();
    }

    @Override
    protected void onDestroy() {
        if (loadingCurtain != null) { loadingCurtain.dismissNow(); loadingCurtain = null; }
        android.view.Choreographer.getInstance().removeFrameCallback(sceneStepCallback);
        sceneBuildSteps.clear();
        dismissSceneSpinner();
        if (chordModePill != null) chordModePill.dismissNow();
        DestroyPlayers();
        releaseLyrics();
        if (uiAccentAnim != null) uiAccentAnim.cancel();
        com.jotapeveloper.fision.design.AccentColorManager.clearRuntimeAccent(this);
        if (audio3DScene != null) {
            // Re-assert the crash filter right before teardown: a display
            // event can land after the renderer is disposed, and a debugger
            // may have replaced the default uncaught handler meanwhile.
            FisionApplication.installCrashFilter();
            audio3DScene.release();
            audio3DScene = null;
        }
        danceActive = false;
        danceHandler.removeCallbacks(danceTick);
        if (serviceBound) {
            if (playbackService != null) {
                playbackService.removeListener(playbackListener);
                playbackService.clearSkipRequestHandler(skipRequestHandler);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    /** Bluetooth / notification skip requests: keep the Activity as the SSoT of
     *  the UI by running the same in-place song switch as the on-screen prev/next
     *  buttons. Returns true so the service does not also recreate the engine. */
    private final PlaybackService.SkipRequestHandler skipRequestHandler =
            delta -> {
                if (isDestroyed) return false;
                // Still loading the song behind the curtain: swallow the skip
                // (a service-side load would supersede the pending one).
                if (engine == null) return true;
                runOnUiThread(() -> {
                    if (delta < 0) handlePreviousRequest();
                    else            openAdjacentSong(delta);
                });
                return true;
            };

    // Configuration

    private void setAppLocale(String languageCode) {
        Locale locale = new Locale(languageCode);
        Locale.setDefault(locale);

        Configuration config = new Configuration();
        config.setLocale(locale);

        getResources().updateConfiguration(config, getBaseContext().getResources().getDisplayMetrics());
    }

    private void updateDB(String OperationType, int value) {
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());

        Executors.newSingleThreadExecutor().execute(() -> {
            switch (OperationType) {
                case "metronome":
                    if (value==1){
                        db.DAO_db_SongParameters().updateMetronomeEnabled(SongId, true);
                    }
                    if (value==0){
                        db.DAO_db_SongParameters().updateMetronomeEnabled(SongId, false);
                    }
                    break;

                case "selectedMetronome":
                    db.DAO_db_SongParameters().updateSelectedMetronome(SongId, value);
                    break;
                case "metronomeVolume":
                    db.DAO_db_SongParameters().updateMetronomeVolume(SongId, value);
                    break;

                case "chords":
                    if(value==1) {
                        db.DAO_db_SongParameters().updateChordsEnabled(SongId, true);
                    }
                    if(value==0) {
                        db.DAO_db_SongParameters().updateChordsEnabled(SongId, false);
                    }
                    break;

                case "pitchIndex":
                    db.DAO_db_SongParameters().updatePitchIndex(SongId, value);
                    break;

                case "tempoIndex":
                    db.DAO_db_SongParameters().updateTempoIndex(SongId, value);
                    break;

                case "tempoMultMilli":
                    db.DAO_db_SongParameters().updateTempoMultMilli(SongId, value);
                    break;

                case "chordsBasic":
                    db.DAO_db_SongParameters().updateChordsBasicMode(SongId, value == 1);
                    break;

                case "metronomeHalfVariant":
                    db.DAO_db_SongParameters().updateMetronomeHalfVariant(SongId, value);
                    break;

                case "downbeatEnabled":
                    db.DAO_db_SongParameters().updateDownbeatSoundEnabled(SongId, value == 1);
                    break;

                case "downbeatType":
                    db.DAO_db_SongParameters().updateDownbeatSoundType(SongId, value);
                    break;

                case "audio3DEnabled":
                    db.DAO_db_SongParameters().updateAudio3DEnabled(SongId, value == 1);
                    break;
            }
        });
    }

    // Overload for floats. v3 keeps only metronomePan (stem pans live in the JSON).
    private void updateDB(String OperationType, float value) {
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            if ("metronomePan".equals(OperationType)) {
                db.DAO_db_SongParameters().updateMetronomePan(SongId, value);
            }
        });
    }

    /** Shared single-threaded executor for Room writes. Reused across every
     *  call so a per-frame stream (dance, live drag) does not spawn one
     *  thread per invocation and OOM the process. */
    private static final java.util.concurrent.ExecutorService PERSIST_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "stem-persist");
                t.setDaemon(true);
                return t;
            });

    /** Serializa stemSettings y persiste el JSON en Room (background). */
    private void persistStemSettings() {
        final String json = StemSettingsCodec.encode(stemSettings);
        final int songId = SongId;
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        PERSIST_EXECUTOR.execute(() ->
                db.DAO_db_SongParameters().updateStemSettings(songId, json));
    }

    /** Reads the saved volume for a stem (default 75). Used by the adapter on bind. */
    public int getStemVolume(String stem) {
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        return s.volume;
    }

    /** True during the initial slider animation loop (used to
     *  suppress the icon flash when the slider crosses value=0). */
    private boolean stemInitialAnimating(String stem) {
        return stemsAdapter != null && stemsAdapter.isAnimateInitial();
    }

    // === StemListAdapter callbacks ===

    /**
     * Volume slider moved. Applied to the engine + persisted only on real interaction.
     * Programmatic changes (init animations / mute toggle) already apply the volume
     * by hand: the animation is purely visual.
     */
    public void onStemVolumeChanged(String stem, float value, boolean fromUser, android.widget.ImageView icon) {
        int newVol = (int) value;
        // During the initial animation the slider ramps from 0 to saved: DO NOT change
        // the icon alpha with those pulses (otherwise a black flash appears when it
        // crosses value=0). The alpha is set once by the adapter bind based
        // on the saved value.
        if (icon != null && (fromUser || !stemInitialAnimating(stem))) {
            icon.setAlpha(newVol == 0 ? StemListAdapter.ALPHA_DISABLED
                                      : StemListAdapter.ALPHA_ACTIVE);
        }
        if (!fromUser) return;

        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        s.volume = newVol;
        applyStemVolumeToEngine(stem);
        if (newVol > 0) stemLastVol.put(stem, newVol);
        persistStemSettings();
    }

    /** Click on the restore button: animates the slider to the default value (75). */
    public void onStemRestoreVolume(String stem, Slider slider, android.widget.ImageView icon) {
        final int defaultVol = 75;
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        if (s.volume == defaultVol) return;
        animateSliderTo(slider, defaultVol);
        s.volume = defaultVol;
        stemLastVol.put(stem, defaultVol);
        if (icon != null) icon.setAlpha(StemListAdapter.ALPHA_ACTIVE);
        applyStemVolumeToEngine(stem);
        persistStemSettings();
    }

    /** Stem currently soloed (null if none) + snapshot of previous volumes before
     *  entering solo, so they can be restored on un-solo. */
    private String soloedStem = null;
    private java.util.Map<String, Integer> preSoloVolumes = null;

    /** Click on the stem icon: shows contextual menu (Solo / Mute) anchored
     *  to the icon itself. Replaces the previous direct toggle. */
    public void showStemActionMenu(View anchor, String stem, Slider slider,
                                   android.widget.ImageView icon) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View menu = inflater.inflate(R.layout.stem_context_menu, null);
        TextView tvSolo = menu.findViewById(R.id.tv_stemMenuSolo);
        TextView tvMute = menu.findViewById(R.id.tv_stemMenuMute);

        // Highlight Solo in colorPrimary if this stem is soloed (visual toggle)
        if (stem.equals(soloedStem)) {
            tvSolo.setTextColor(com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this));
        }
        // Same for Mute: if the stem is muted, highlight Mute in green
        StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, stem);
        if (ss.volume == 0) {
            tvMute.setTextColor(com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this));
        }

        android.widget.PopupWindow popup = new android.widget.PopupWindow(menu,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                /*focusable=*/ true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        popup.setElevation(8f * getResources().getDisplayMetrics().density);
        popup.setOutsideTouchable(true);

        tvSolo.setOnClickListener(v -> {
            popup.dismiss();
            toggleStemSolo(stem);
        });
        tvMute.setOnClickListener(v -> {
            popup.dismiss();
            onStemMuteToggle(stem, slider, icon);
        });

        popup.showAsDropDown(anchor, 0, 0);
    }

    /** Solo toggle: mutes every other stem and leaves this one playing. Pressing
     *  Solo again on the same stem restores the volumes that were active before the first solo. */
    private void toggleStemSolo(String stem) {
        java.util.List<String> order = playbackService != null
                ? playbackService.getStemOrder() : java.util.Collections.emptyList();
        if (stem.equals(soloedStem)) {
            // Des-solo: restaurar snapshot
            if (preSoloVolumes != null) {
                for (String s : order) {
                    Integer v = preSoloVolumes.get(s);
                    if (v == null) continue;
                    setStemVolumeAnimated(s, v, order);
                }
                preSoloVolumes = null;
            }
            soloedStem = null;
        } else {
            // Snapshot only the first time — when switching from one solo to another, keep
            // the actual pre-first-solo state.
            if (preSoloVolumes == null) {
                preSoloVolumes = new java.util.HashMap<>();
                for (String s : order) {
                    StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, s);
                    preSoloVolumes.put(s, ss.volume);
                }
            }
            for (String s : order) {
                StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, s);
                int target;
                if (s.equals(stem)) {
                    if (ss.volume == 0) {
                        Integer prev = stemLastVol.get(s);
                        target = (prev != null && prev > 0) ? prev : 75;
                    } else {
                        target = ss.volume;
                    }
                } else {
                    target = 0;
                }
                setStemVolumeAnimated(s, target, order);
            }
            soloedStem = stem;
        }
        persistStemSettings();
    }

    /** Applies volume to a stem: updates model + engine + animates the matching
     *  ViewHolder slider + icon alpha. */
    private void setStemVolumeAnimated(String stem, int target, java.util.List<String> order) {
        StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, stem);
        ss.volume = target;
        applyStemVolumeToEngine(stem);
        if (rvStems == null || stemsAdapter == null) return;
        int idx = stemsAdapter.adapterPositionForStem(stem);
        if (idx == RecyclerView.NO_POSITION) return;
        RecyclerView.ViewHolder vh = rvStems.findViewHolderForAdapterPosition(idx);
        if (!(vh instanceof StemListAdapter.ViewHolder)) return;
        StemListAdapter.ViewHolder svh = (StemListAdapter.ViewHolder) vh;
        animateSliderTo(svh.sb_stemVol, target);
        svh.iv_stemIcon.setAlpha(target == 0
                ? StemListAdapter.ALPHA_DISABLED : StemListAdapter.ALPHA_ACTIVE);
    }

    /** Click on the stem icon: mute if playing, restore if muted. */
    public void onStemMuteToggle(String stem, Slider slider, android.widget.ImageView icon) {
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        if (s.volume > 0) {
            stemLastVol.put(stem, s.volume);
            animateSliderTo(slider, 0);
            s.volume = 0;
            if (icon != null) icon.setAlpha(StemListAdapter.ALPHA_DISABLED);
        } else {
            Integer prev = stemLastVol.get(stem);
            int restoreTo = (prev != null && prev > 0) ? prev : 75;
            animateSliderTo(slider, restoreTo);
            s.volume = restoreTo;
            if (icon != null) icon.setAlpha(StemListAdapter.ALPHA_ACTIVE);
        }
        applyStemVolumeToEngine(stem);
        persistStemSettings();
    }

    private void animateSliderTo(Slider slider, int target) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(slider, "value", slider.getValue(), target);
        anim.setDuration(300);
        anim.start();
    }

    // ================================================================
    // === 3D MODE (ITD/ILD) — view and icon drag =====================
    // ================================================================
    /** 3D overlay root (visibility toggle). */
    private View     view3DRoot;
    /** Alternate 3D scene root (Filament / SceneView). Chosen at runtime
     *  based on {@link com.jotapeveloper.fision.audio3d.PerformancePrefs}. */
    private View     view3DSceneRoot;
    /** Controller for the Filament scene when the perf pref enables it. */
    private com.jotapeveloper.fision.audio3d.Audio3DSceneController audio3DScene;
    /** True when the scene sprites no longer match the loaded song. Re-entering
     *  the scene for the same song reuses the existing nodes (no texture
     *  re-upload on the UI thread). */
    private boolean sceneSpritesStale = true;
    /** Container where the stem icons are placed absolutely. */
    private android.widget.FrameLayout fl3DStemContainer;
    /** Central head. */
    private android.widget.ImageView   iv3DHead;
    /** 3D effect on/off switch. */
    private com.google.android.material.switchmaterial.SwitchMaterial sw3DEnabled;
    /** Vertical elevation slider (affects the selected stem). */
    private VerticalSlider sb3DElevation;
    /** Label showing the elevation value of the selected stem. */
    private TextView tv3DElevation;
    /** Scene-mode vertical slider: distance of the selected stem from the head. */
    private VerticalSlider sb3DDistanceScene;
    /** Scene-mode tooltip showing the distance value in metres. */
    private TextView tv3DDistanceScene;
    /** Black overlay over stems+head: "lights off" effect when 3D=off. */
    private View v3DDarkOverlay;
    /** Stems dance button (play → stop while dancing). */
    private com.google.android.material.button.MaterialButton b3DDance;
    /** Scene-mode counterpart of b3DDance (different layout id). */
    private com.google.android.material.button.MaterialButton b3DDanceScene;
    /** 3D reset button: hidden until any position/elev/mute/omni is modified. */
    private com.google.android.material.button.MaterialButton b3DReset;
    /** Container of the inline dance sliders (visible only while dancing). */
    private View danceInlineSlidersContainer;
    private com.google.android.material.slider.Slider danceInlineSlSpeed;
    private com.google.android.material.slider.Slider danceInlineSlDistance;

    // === Dance mode (stem orbit) ===
    private boolean       danceActive = false;
    private final java.util.Set<String> danceStems = new java.util.HashSet<>();
    private final java.util.Set<String> danceSineStems = new java.util.HashSet<>();  // per-stem up/down wave
    /** Swinging stems start at a random point of their wave (scene mode). */
    private boolean danceRandomSwing = false;
    private float         danceSpeed    = 0.15f;  // laps/second per stem (max 0.4 for finer resolution)
    // Scene mode: metres [0.3..3.0] (same range as the vertical distance
    // slider). Flat mode: radius multiplier [0.4..1.2]. Default 1.0 is
    // valid in both ranges.
    private float         danceDistance = 1.0f;
    // Sine-wave oscillation speed per stem, in cycles per second (scene
    // mode only). The bottom-row slider edits the currently selected stem.
    private final java.util.HashMap<String, Float> danceOscSpeeds = new java.util.HashMap<>();
    private final java.util.Map<String, Float> danceAngle = new java.util.HashMap<>();
    private final java.util.Map<String, Float> danceRadialFactor = new java.util.HashMap<>();
    private final java.util.Map<String, Float> danceSpeedFactor  = new java.util.HashMap<>();
    private long          danceLastNs = 0L;
    private String        danceGrabbed = null;   // stem currently being grabbed
    private final android.os.Handler danceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable danceTick = new Runnable() {
        @Override public void run() {
            tickDance();
            if (danceActive) danceHandler.postDelayed(this, 16);
        }
    };
    /** Selected stem (last one touched). The elevation slider applies to this one. */
    private String selected3DStem = null;
    /** ImageView icon per stem for the 3D overlay. */
    private final java.util.Map<String, android.widget.ImageView> stem3DIcons = new java.util.HashMap<>();
    /** Normalized positions [-1..1] x, [-1..1] y per stem (head center = 0,0). */
    private final java.util.Map<String, float[]> stem3DPositions = new java.util.HashMap<>();
    /** Global state of the 3D effect for this song. */
    private boolean is3DEnabled = false;
    /** True while the 3D view is visible (even if the effect itself is off). */
    private boolean is3DViewShown = false;

    /** `b_3d` button in the toolbar. Reference stored so that
     *  its tint can be updated when the effect toggles on/off. */
    private com.google.android.material.button.MaterialButton b3d;

    // ---------------------------------------------------------------------
    //   Lyrics overlay (opt-in Whisper transcription).
    // ---------------------------------------------------------------------
    private enum LyricsUiState { PICKER, SKELETON, ERROR, CONTENT }

    private com.google.android.material.button.MaterialButton bLyrics;
    private View lyricsRoot;
    private com.google.android.material.card.MaterialCardView cardLyricsLanguage;
    private android.widget.ImageView ivLyricsFlag;
    private android.widget.TextView tvLyricsCardSubtitle;
    private com.google.android.material.button.MaterialButton bLyricsConfirm;
    private com.google.android.material.button.MaterialButton bLyricsPickerCancel;
    private android.widget.LinearLayout llLyricsSkeleton;
    private android.widget.TextView tvLyricsStatus;
    private LyricsSkeletonView lyricsSkeletonView;
    private com.google.android.material.button.MaterialButton bLyricsCancelJob;
    private androidx.recyclerview.widget.RecyclerView rvLyrics;
    private android.widget.ImageButton bLyricsOptions;

    private LyricsManager mLyricsMgr;
    private LyricsLinesAdapter mLyricsLinesAdapter;
    private Lyrics mLoadedLyrics;
    private LyricsLanguage mSelectedLyricsLanguage = LyricsLanguage.LANGUAGES[0]; // Auto
    private LyricsUiState mLyricsUiState = LyricsUiState.PICKER;
    private boolean isLyricsViewShown = false;

    // Chord diagrams view (piano / guitar / ukulele), opened from the pill of
    // the chords button. While open the chords button shows the chord grid and closes it.
    private boolean isChordDiagramShown = false;
    private View chordDiagramRoot;
    private com.jotapeveloper.fision.chords.ChordDiagramController chordDiagrams;
    private com.jotapeveloper.fision.chords.ChordModePill chordModePill;
    private android.graphics.drawable.Drawable chordIcon;
    // While opening the lyrics overlay, applyChordsOverlayForLyrics() is a
    // no-op so the transition owns the chord fade. Otherwise setLyricsUiState
    // (invoked from refreshLyricsViewForCurrentSong before transitionIntoLyrics
    // runs) would snap-hide the chord row before the fade had a chance to run.
    private boolean mLyricsOpeningTransition = false;
    private boolean lyricsReprocessMode = false;
    private int mLyricsActiveIdx = -1;
    private static final String PREF_LYRICS_KARAOKE_MODE = "lyrics_karaoke_mode";
    private boolean mLyricsKaraokeMode = false;
    // Whisper marks the word onset at the leading consonant; visually the
    // syllable is perceived slightly later. Delay the karaoke highlight by
    // this amount so the coloured word matches what the singer just uttered.
    private static final int KARAOKE_HIGHLIGHT_OFFSET_MS = 220;

    /** True when the performance preference selects the Filament scene
     *  variant of the 3D Audio view (vs the flat 2D one). Falls back
     *  to the flat variant when the SceneView layout is not present. */
    private boolean isAudio3DScene() {
        return com.jotapeveloper.fision.audio3d.PerformancePrefs
                .isAudio3DSceneEnabled(this)
                && view3DSceneRoot != null;
    }

    /** Currently active 3D root — scene or flat — for show/hide/animate. */
    private View active3DRoot() {
        return isAudio3DScene() ? view3DSceneRoot : view3DRoot;
    }

    /** Lazy init of the 3D view references. Idempotent. */
    private void ensure3DRefs() {
        if (view3DRoot == null) {
            view3DRoot        = findViewById(R.id.view3D_root);
            fl3DStemContainer = findViewById(R.id.fl_3d_stemContainer);
            iv3DHead          = findViewById(R.id.iv_3d_head);
            sw3DEnabled       = findViewById(R.id.sw_3d_enabled);
            sb3DElevation     = findViewById(R.id.sb_3d_elevation);
            tv3DElevation     = findViewById(R.id.tv_3d_elevation);
            v3DDarkOverlay    = findViewById(R.id.v_3d_darkOverlay);
            // Initial state of the overlay: on if is3DEnabled, off otherwise
            if (v3DDarkOverlay != null) v3DDarkOverlay.setAlpha(is3DEnabled ? 0f : 0.72f);
            if (sw3DEnabled != null) {
                sw3DEnabled.setChecked(is3DEnabled);
                sw3DEnabled.setOnCheckedChangeListener((btn, checked) -> {
                    apply3DEnabled(checked);
                    // Keep the scene-mode twin switch in sync if present.
                    com.google.android.material.switchmaterial.SwitchMaterial swScene =
                            findViewById(R.id.sw_3d_enabledScene);
                    if (swScene != null && swScene.isChecked() != checked) {
                        swScene.setOnCheckedChangeListener(null);
                        swScene.setChecked(checked);
                        wireSceneEnabledSwitch(swScene);
                    }
                });
            }
            if (sb3DElevation != null) {
                sb3DElevation.setRange(-30f, 70f);
                sb3DElevation.setStep(1f);
                sb3DElevation.setValue(0f);
                sb3DElevation.setOnChangeListener((sl, val, fromUser) -> {
                    if (!fromUser || selected3DStem == null) return;
                    StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, selected3DStem);
                    s.elevationDeg = val;
                    persistStemSettings();
                    apply3DPositionForStem(selected3DStem);
                    showElevationTooltip((int) val);
                    // Elevation changed → show reset button
                    showResetButton();
                });
            }
            // Info button: explanatory dialog
            View bInfo = findViewById(R.id.b_3d_info);
            if (bInfo != null) bInfo.setOnClickListener(v -> show3DInfoDialog());
            // Reset button: returns every stem to the default position (frontal semicircle),
            // clears elevation and omnipresent. Hidden by default (alpha=0, gone); fades in
            // when any position changes and fades out after the action completes.
            b3DReset = findViewById(R.id.b_3d_reset);
            if (b3DReset != null) {
                b3DReset.setAlpha(0f);
                b3DReset.setVisibility(View.GONE);
                b3DReset.setOnClickListener(v -> {
                    reset3DPositions();
                    hideResetButton();
                });
            }
            // Dance button: opens setup dialog or stops the dance if active.
            b3DDance = findViewById(R.id.b_3d_dance);
            if (b3DDance != null) b3DDance.setOnClickListener(v -> {
                if (isAudio3DScene() && audio3DScene != null && audio3DScene.isDanceActive()) {
                    audio3DScene.stopDance();
                    if (b3DDanceScene != null) b3DDanceScene.setIconResource(R.raw.ic_play);
                    setSceneDanceSlidersVisible(false);
                } else if (danceActive) {
                    stopDance();
                } else {
                    showStemsDanceDialog();
                }
            });
            // Inline dance sliders (speed + distance) — visible only while the dance is active
            View danceSlidersContainer = findViewById(R.id.ll_3d_danceSliders);
            com.google.android.material.slider.Slider slSpeedInline = findViewById(R.id.sl_3d_danceSpeed);
            com.google.android.material.slider.Slider slDistInline  = findViewById(R.id.sl_3d_danceDistance);
            if (slSpeedInline != null) {
                slSpeedInline.setValue(danceSpeed);
                slSpeedInline.addOnChangeListener((s, val, fromUser) -> {
                    if (fromUser) danceSpeed = val;
                });
            }
            if (slDistInline != null) {
                slDistInline.setValue(danceDistance);
                slDistInline.addOnChangeListener((s, val, fromUser) -> {
                    if (fromUser) danceDistance = val;
                });
            }
            // Initial control state: greyed out when 3D audio starts off.
            setSceneControlsEnabled(is3DEnabled);
            danceInlineSlidersContainer = danceSlidersContainer;
            danceInlineSlSpeed          = slSpeedInline;
            danceInlineSlDistance       = slDistInline;
        }
    }

    // === Scene build ===
    // The Filament scene is kept out of the view tree until first needed: a
    // SurfaceView costs work on every frame of the player even while hidden,
    // and the engine is heavy. Building it blocks the main thread, so it is
    // split into short steps run one per frame (sceneBuildSteps): between
    // steps the UI and the playback controls stay responsive. The chrome
    // (header, sliders, buttons, fades) is inflated first and shown at once
    // with its controls disabled, over an embedded spinner panel.
    private static final int SCENE_NONE = 0, SCENE_BUILDING = 1, SCENE_READY = 2;
    private int sceneBuildState = SCENE_NONE;
    private final java.util.ArrayDeque<Runnable> sceneBuildSteps = new java.util.ArrayDeque<>();
    private boolean sceneStepScheduled = false;
    private final android.view.Choreographer.FrameCallback sceneStepCallback = t -> runNextSceneStep();
    /** Embedded spinner shown in the scene area while it builds. */
    @androidx.annotation.Nullable private com.jotapeveloper.fision.brand.FisionLoadingSurface sceneSpinner;
    private static final float SCENE_SPINNER_DP = 48f;
    private static final float SCENE_SPINNER_FADE_MS = 200f;

    private boolean isSceneReady() { return sceneBuildState == SCENE_READY; }

    /** Makes the scene fully built right now (synchronous). For callers
     *  already behind a cover, e.g. a song stored with 3D on opening under
     *  the loading curtain. Finishes a staged build in progress. */
    private void ensureSceneInflated() {
        inflateSceneChrome();
        if (view3DSceneRoot == null) return;
        if (sceneBuildState == SCENE_NONE) queueSceneBuild();
        drainSceneBuild();
    }

    /** Queues the build steps: engine (SceneView inflation) + controller,
     *  the controller's own setup steps, then the song data. */
    private void queueSceneBuild() {
        sceneBuildState = SCENE_BUILDING;
        sceneBuildSteps.clear();
        sceneBuildSteps.add(() -> {
            android.view.ViewStub svStub = findViewById(R.id.stub_3d_sceneView);
            if (svStub != null) svStub.inflate();
            io.github.sceneview.SceneView sv = findViewById(R.id.v_3d_scene);
            if (sv == null) return;
            audio3DScene = new com.jotapeveloper.fision.audio3d
                    .Audio3DSceneController(this, sv);
            // The controller's construction steps run next, in order.
            java.util.List<Runnable> steps = audio3DScene.setupSteps();
            for (int i = steps.size() - 1; i >= 0; i--) sceneBuildSteps.addFirst(steps.get(i));
        });
        sceneBuildSteps.add(() -> {
            if (audio3DScene == null) return;
            maybeInstallSceneTuningPanel();
            // If the song has 3D audio stored as OFF the scene opens
            // inert from the start (no orbit, no sprite drag).
            audio3DScene.setInteractionEnabled(is3DEnabled);
            // Push the current song's card to the scene now that the
            // controller exists.
            SongMetadata sm =
                    playbackService != null ? playbackService.getCurrentMetadata() : null;
            pushSongCardToScene(
                    sm != null ? sm.displayTitle : null,
                    sm != null ? sm.artist : null);
        });
        sceneBuildSteps.add(this::ensureSceneSprites);
        sceneBuildSteps.add(this::onSceneBuilt);
    }

    /** Runs the remaining build steps one per frame. */
    private void scheduleSceneSteps() {
        if (sceneStepScheduled || sceneBuildSteps.isEmpty()) return;
        sceneStepScheduled = true;
        android.view.Choreographer.getInstance().postFrameCallback(sceneStepCallback);
    }

    private void runNextSceneStep() {
        sceneStepScheduled = false;
        if (isDestroyed) { sceneBuildSteps.clear(); return; }
        Runnable step = sceneBuildSteps.pollFirst();
        if (step != null) step.run();
        scheduleSceneSteps();
    }

    /** Runs every remaining build step now. */
    private void drainSceneBuild() {
        if (sceneStepScheduled) {
            android.view.Choreographer.getInstance().removeFrameCallback(sceneStepCallback);
            sceneStepScheduled = false;
        }
        Runnable step;
        while (!isDestroyed && (step = sceneBuildSteps.pollFirst()) != null) step.run();
    }

    /** Last build step: controls on, and the spinner lifts once the scene
     *  is warm if it is on screen. */
    private void onSceneBuilt() {
        sceneBuildState = SCENE_READY;
        setSceneControlsEnabled(is3DEnabled);
        if (is3DViewShown && audio3DScene != null) {
            audio3DScene.resume();
            audio3DScene.setLightIntensity(is3DEnabled ? 1f : 0.20f);
            audio3DScene.runWhenWarm(this::hideSceneSpinner);
            if (view3DSceneRoot != null) {
                view3DSceneRoot.postDelayed(this::hideSceneSpinner, SCENE_REVEAL_FALLBACK_MS);
            }
        } else {
            hideSceneSpinner();
        }
    }

    /** Embedded spinner over the scene area (under the controls). Starts the
     *  queued build steps once it has faded in. */
    private void showSceneSpinner() {
        android.widget.FrameLayout slot = findViewById(R.id.fl_3d_sceneLoading);
        if (slot == null) { scheduleSceneSteps(); return; }
        if (sceneSpinner != null) { scheduleSceneSteps(); return; }
        float d = getResources().getDisplayMetrics().density;
        sceneSpinner = new com.jotapeveloper.fision.brand.FisionLoadingSurface(
                this, SCENE_SPINNER_DP * d, SCENE_SPINNER_FADE_MS,
                this::scheduleSceneSteps, true);
        slot.addView(sceneSpinner, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Quick fade of the spinner panel, then it is removed. */
    private void hideSceneSpinner() {
        final com.jotapeveloper.fision.brand.FisionLoadingSurface sp = sceneSpinner;
        if (sp == null) return;
        sceneSpinner = null;
        sp.finish(null, () -> {
            if (sp.getParent() instanceof ViewGroup) ((ViewGroup) sp.getParent()).removeView(sp);
        }, false);
    }

    /** Inflates the scene chrome (no engine yet) and wires its controls,
     *  disabled until the build completes. */
    private void inflateSceneChrome() {
        if (view3DSceneRoot != null) return;
        if (!com.jotapeveloper.fision.audio3d.PerformancePrefs.isAudio3DSceneEnabled(this)) return;
        android.view.ViewStub stub = findViewById(R.id.stub_3d_scene);
        if (stub == null) return;
        view3DSceneRoot = stub.inflate();

        View infoScene = findViewById(R.id.b_3d_infoScene);
        if (infoScene != null) {
            infoScene.setOnClickListener(v -> {
                if (audio3DScene != null) audio3DScene.logCameraPose();
                show3DInfoDialog();
            });
        }
        // Backdrop views repaint the nebula over the opaque SceneView surface.
        for (int id : new int[] { R.id.v_3d_sceneCover, R.id.v_3d_sceneFadeTop, R.id.v_3d_sceneFadeBottom }) {
            NebulaBackdropView backdrop = findViewById(id);
            if (backdrop != null) backdrop.setSource(nebulaView);
        }
        // Scene header switch, kept in sync with the flat one.
        wireSceneEnabledSwitch(findViewById(R.id.sw_3d_enabledScene));
        // Vertical slider slot repurposed as stem-to-listener distance
        // (range 0.3..3.0 metres, neutral 1.5).
        sb3DDistanceScene = findViewById(R.id.sb_3d_elevationScene);
        tv3DDistanceScene = findViewById(R.id.tv_3d_elevationScene);
        if (sb3DDistanceScene != null) {
            sb3DDistanceScene.setRange(0.3f, 3.0f);
            sb3DDistanceScene.setStep(0.05f);
            sb3DDistanceScene.setValue(1.5f);
            sb3DDistanceScene.setOnChangeListener((sl, val, fromUser) -> {
                if (!fromUser || selected3DStem == null) return;
                StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, selected3DStem);
                s.distance3D = val;
                persistStemSettings();
                if (audio3DScene != null) audio3DScene.updateStemDistance(selected3DStem, val);
                showDistanceTooltip(val);
                showResetButton();
            });
        }
        b3DDanceScene = findViewById(R.id.b_3d_danceScene);
        if (b3DDanceScene != null) b3DDanceScene.setOnClickListener(v -> {
            if (isAudio3DScene() && audio3DScene != null && audio3DScene.isDanceActive()) {
                audio3DScene.stopDance();
                b3DDanceScene.setIconResource(R.raw.ic_play);
                setSceneDanceSlidersVisible(false);
            } else {
                showStemsDanceDialog();
            }
        });
        // Reset: glides every sprite back to the default frontal semicircle.
        com.google.android.material.button.MaterialButton bResetScene =
                findViewById(R.id.b_3d_resetScene);
        if (bResetScene != null) bResetScene.setOnClickListener(v -> reset3DPositions());
        // Inline dance sliders of the scene (speed + distance).
        View danceSlidersContainerScene = findViewById(R.id.ll_3d_danceSlidersScene);
        com.google.android.material.slider.Slider slSpeedInlineScene =
                findViewById(R.id.sl_3d_danceSpeedScene);
        com.google.android.material.slider.Slider slDistInlineScene =
                findViewById(R.id.sl_3d_danceDistanceScene);
        if (slSpeedInlineScene != null) {
            slSpeedInlineScene.setValue(danceSpeed);
            slSpeedInlineScene.addOnChangeListener((s, val, fromUser) -> {
                if (!fromUser) return;
                danceSpeed = val;
                if (audio3DScene != null) audio3DScene.setDanceSpeed(val);
            });
        }
        if (slDistInlineScene != null) {
            slDistInlineScene.setValue(danceDistance);
            slDistInlineScene.addOnChangeListener((s, val, fromUser) -> {
                if (!fromUser) return;
                danceDistance = val;
                if (audio3DScene != null) audio3DScene.setDanceDistance(val);
            });
        }
        // Wave speed slider: per-stem sine-wave oscillation speed for the
        // selected stem (stems marked with the wave in the dance dialog).
        com.google.android.material.slider.Slider slOscInlineScene =
                findViewById(R.id.sl_3d_danceOscScene);
        if (slOscInlineScene != null) {
            slOscInlineScene.setValue(0.5f);
            slOscInlineScene.addOnChangeListener((s, val, fromUser) -> {
                if (!fromUser) return;
                if (selected3DStem == null) return;
                danceOscSpeeds.put(selected3DStem, val);
                if (audio3DScene != null) audio3DScene.setStemOscSpeed(selected3DStem, val);
            });
        }
        danceInlineSlidersContainer = danceSlidersContainerScene;
        danceInlineSlSpeed          = slSpeedInlineScene;
        danceInlineSlDistance       = slDistInlineScene;
        setSceneControlsEnabled(is3DEnabled);
        refreshWaveSliderVisibility();
        // Fresh views carry the theme accent: bring them to the song's.
        recolorToPlayerAccent(view3DSceneRoot);
    }

    /** Updates the tint of the `b_3d` icon: green if the effect is active. */
    private void refreshB3dTint() {
        if (b3d == null) return;
        int tint = is3DEnabled
                ? com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this)
                : Color.rgb(255, 255, 255);
        b3d.setIconTint(ColorStateList.valueOf(tint));
    }

    /** Toggles between the normal view (stems + chords) and the 3D view with crossfade. */
    private void toggle3DView() {
        ensure3DRefs();
        // From the chord diagrams: close them (button back to its default
        // icon) and open the 3D view once they are out.
        if (isChordDiagramShown) {
            closeChordDiagrams(this::toggle3DView);
            return;
        }
        // Never overlap with the lyrics overlay.
        if (isLyricsViewShown) return;
        // A song load is behind the curtain: ignore the toggle.
        if (loadingCurtain != null) return;
        // First opening of the scene: the chrome appears at once and the
        // engine builds step by step behind it (see transitionIntoScene).
        if (!is3DViewShown) {
            inflateSceneChrome();
            if (view3DSceneRoot != null && sceneBuildState == SCENE_NONE) queueSceneBuild();
        }
        is3DViewShown = !is3DViewShown;
        View chordsContainer = findViewById(R.id.chords_container);
        if (is3DViewShown) transitionInto3D(chordsContainer);
        else               transitionOutOf3D(chordsContainer);
    }


    /** Shows the 3D view without animation. Used when loading a song whose state
     *  `is3DEnabled=true`: goes straight to ITD mode without showing the stems view. */
    private void show3DViewNoAnim() {
        ensure3DRefs();
        if (is3DViewShown) return;
        is3DViewShown = true;
        View chordsContainer = findViewById(R.id.chords_container);
        View stemsStack = rvStems != null ? (View) rvStems.getParent().getParent() : null;
        if (stemsStack != null) stemsStack.setVisibility(View.GONE);
        if (chordsContainer != null) chordsContainer.setVisibility(View.GONE);
        View root = active3DRoot();
        root.setAlpha(1f);
        root.setScaleX(1f);
        root.setScaleY(1f);
        root.setVisibility(View.VISIBLE);
        if (nebulaView != null) nebulaView.setOrbitBlobsVisible(false, false);
        if (!isAudio3DScene()) {
            build3DIcons();
            // Icons appear faded after the layout pass (they start with alpha=0 in build).
            fl3DStemContainer.postDelayed(this::animateStemIconsIn, 100L);
            // Evaluate whether previous modifications exist → show reset button with fade in
            fl3DStemContainer.postDelayed(() -> { if (has3DModifications()) showResetButton(); }, 400L);
        } else {
            // Song opened with the 3D view stored as active: everything is
            // still loading, so the scene stays covered until it is warm and
            // then fades in (no pop of a half-initialised scene).
            resetSceneTransitionViews();
            showSceneCovered();
            scheduleSceneReveal(SCENE_REVEAL_ON_OPEN_MS);
        }
    }

    /** Resting state of the scene transition helpers: cover hidden, chrome
     *  fully visible, no pending entry/reveal. */
    private void resetSceneTransitionViews() {
        cancelPendingSceneEntry();
        applyToolbarCompactForScene(false);
        View cover  = findViewById(R.id.v_3d_sceneCover);
        View chrome = findViewById(R.id.fl_3d_sceneChrome);
        if (cover != null) {
            cover.animate().cancel();
            cover.setAlpha(0f);
            cover.setVisibility(View.GONE);
        }
        if (chrome != null) {
            chrome.animate().cancel();
            chrome.setAlpha(1f);
            chrome.setScaleX(1f);
            chrome.setScaleY(1f);
        }
    }

    /** Symmetric to show3DViewNoAnim. Closes the ITD overlay instantly and
     *  restores the stems + chords stack. Used when the new song has 3D off but
     *  the previous one had the vista open. */
    private void hide3DViewNoAnim() {
        ensure3DRefs();
        if (!is3DViewShown) return;
        is3DViewShown = false;
        if (danceActive) stopDance();
        dismissSceneSpinner();
        View root = active3DRoot();
        if (root != null) {
            root.animate().cancel();
            root.setVisibility(View.GONE);
        }
        if (isAudio3DScene()) resetSceneTransitionViews();
        if (nebulaView != null) nebulaView.setOrbitBlobsVisible(true, false);
        if (audio3DScene != null) audio3DScene.pause();
        View stemsStack = rvStems != null ? (View) rvStems.getParent().getParent() : null;
        if (stemsStack != null) {
            stemsStack.animate().cancel();
            stemsStack.setAlpha(1f);
            stemsStack.setTranslationY(0f);
            stemsStack.setVisibility(View.VISIBLE);
        }
        View chordsContainer = findViewById(R.id.chords_container);
        if (chordsContainer != null) {
            chordsContainer.setVisibility(chordsActive ? View.VISIBLE : View.GONE);
        }
        if (b3DReset != null) {
            b3DReset.animate().cancel();
            b3DReset.setAlpha(0f);
            b3DReset.setVisibility(View.GONE);
        }
        selected3DStem = null;
    }

    /** Reconciles the 3D view, engine and switch state with the value freshly
     *  read from the current song's Room row. Called from updateUI on Activity
     *  open, song switch and end-of-song autoplay. Covers the four transitions
     *  between the previous and current is3DEnabled flags without duplicating
     *  logic across callers. */
    private void refresh3DForCurrentSong() {
        ensure3DRefs();
        boolean want = is3DEnabled;
        // A song stored with 3D on opens straight into the 3D view.
        if (want) ensureSceneInflated();
        // New song → new stem catalog and poses; scene sprites must be rebuilt.
        sceneSpritesStale = true;

        // Silent sync of both switches (flat header + scene header) to avoid
        // re-triggering apply3DEnabled below. The scene one used to keep the
        // previous song's state: ON over a song stored with 3D off.
        if (sw3DEnabled != null && sw3DEnabled.isChecked() != want) {
            sw3DEnabled.setOnCheckedChangeListener(null);
            sw3DEnabled.setChecked(want);
            sw3DEnabled.setOnCheckedChangeListener((btn, checked) -> apply3DEnabled(checked));
        }
        wireSceneEnabledSwitch(findViewById(R.id.sw_3d_enabledScene));

        if (want) {
            apply3DEnabled(true);
            if (is3DViewShown) {
                // Vista already open from previous song — rebuild icons for the
                // new stem catalog and re-evaluate reset button visibility.
                if (danceActive) stopDance();
                selected3DStem = null;
                if (isAudio3DScene()) ensureSceneSprites();
                build3DIcons();
                fl3DStemContainer.postDelayed(this::animateStemIconsIn, 100L);
                fl3DStemContainer.postDelayed(() -> {
                    if (has3DModifications()) showResetButton();
                    else hideResetButton();
                }, 400L);
            } else {
                show3DViewNoAnim();
            }
        } else {
            // Song stored with 3D off: close the vista (behind the loading
            // curtain on a song change, so nothing pops).
            if (is3DViewShown) hide3DViewNoAnim();
            // The scene goes inert with 3D off (no orbit / sprite drag, greyed
            // per-stem controls) — apply3DEnabled is not called on this path.
            if (audio3DScene != null) audio3DScene.setInteractionEnabled(false);
            setSceneControlsEnabled(false);
            // Sliders back to normal (previous song may have locked them).
            if (stemsAdapter != null) stemsAdapter.setEditingLocked(false);
            // Lights-off overlay reflects the off state. Flat and scene
            // modes have different alpha caps (see animate3DLightsOverlay).
            if (v3DDarkOverlay != null) v3DDarkOverlay.setAlpha(0.72f);
            View sceneOverlay = findViewById(R.id.v_3d_darkOverlayScene);
            if (sceneOverlay != null) sceneOverlay.setAlpha(0.35f);
            if (audio3DScene != null) audio3DScene.setLightIntensity(0.20f);
            refreshB3dTint();
        }
    }

    /** Crossfade + slide + zoom toward the 3D view. The ITD view covers chords and stems
     *  at the same time (shared central FrameLayout parent) — no ceiling jump when hiding
     *  chords. The whole stack (chords + stems) does fade+slide as a block so the exit
     *  is visible; then the ITD entry starts. */
    private void transitionInto3D(View chordsContainer) {
        final long OUT_DUR = 200L;
        final long IN_DUR  = 300L;
        float density = getResources().getDisplayMetrics().density;
        float slidePx = 18f * density;

        View stemsStack = rvStems != null ? (View) rvStems.getParent().getParent() : null;
        if (stemsStack != null) {
            stemsStack.animate().cancel();
            stemsStack.setAlpha(1f);
            stemsStack.setTranslationY(0f);
            stemsStack.animate().alpha(0f).translationY(slidePx)
                    .withLayer()
                    .setDuration(OUT_DUR)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        stemsStack.setVisibility(View.GONE);
                        stemsStack.setTranslationY(0f);
                    }).start();
        }

        if (nebulaView != null) nebulaView.setOrbitBlobsVisible(false, true);
        if (isAudio3DScene()) {
            transitionIntoScene(OUT_DUR, IN_DUR);
            return;
        }

        // 3D mode entry: overlaps the end of the fade out to avoid a black gap.
        View root = active3DRoot();
        root.setAlpha(0f);
        root.setScaleX(0.94f);
        root.setScaleY(0.94f);
        root.setVisibility(View.VISIBLE);
        root.animate().cancel();
        root.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .withLayer()
                .setStartDelay(OUT_DUR - 80L)
                .setDuration(IN_DUR)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();

        // Head with bounce overshoot — builds icons once measurements are ready.
        build3DIcons();
        if (iv3DHead != null) {
            iv3DHead.setScaleX(0.75f);
            iv3DHead.setScaleY(0.75f);
            iv3DHead.animate().cancel();
            iv3DHead.animate().scaleX(1f).scaleY(1f)
                    .setStartDelay(OUT_DUR).setDuration(360L)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                    .start();
        }
        // Stem icons: cascaded fade in, after the stems fade out.
        fl3DStemContainer.postDelayed(this::animateStemIconsIn, OUT_DUR + 60L);
        // Evaluate whether previous modifications exist → show reset button with fade in
        fl3DStemContainer.postDelayed(() -> { if (has3DModifications()) showResetButton(); }, OUT_DUR + IN_DUR + 100L);
    }

    /** Reveal fade when the song opens straight into the scene. */
    private static final long SCENE_REVEAL_ON_OPEN_MS = 450L;
    /** Upper bound on the wait for a warm scene (render stall safety net). */
    private static final long SCENE_REVEAL_FALLBACK_MS = 2000L;

    /** Pending "show covered" step, posted at the end of the stems fade out. */
    private Runnable sceneEnterRunnable;
    /** Reveal armed and not yet run. The token invalidates stale callbacks
     *  (warm callback + fallback both target the same reveal). */
    private boolean sceneRevealPending = false;
    private int sceneRevealToken = 0;

    /** Scene entry. The SceneView surface ignores alpha/scale of its parents,
     *  so the reveal is sequential: once the stems stack has faded out, the
     *  root appears under an opaque backdrop cover (identical to what is on
     *  screen at that moment); when the scene is warm the cover fades out
     *  while the chrome zooms in. Surface creation and sprite build happen
     *  behind the cover. */
    private void transitionIntoScene(long outDur, long inDur) {
        View root = view3DSceneRoot;
        if (root == null) return;
        cancelPendingSceneEntry();
        sceneEnterRunnable = () -> {
            sceneEnterRunnable = null;
            if (isSceneReady()) {
                showSceneCovered();
                scheduleSceneReveal(inDur);
            } else {
                showSceneLoading(inDur);
            }
        };
        root.postDelayed(sceneEnterRunnable, outDur);
    }

    /** Entry while the scene is still building: the chrome zooms in at once
     *  (controls disabled) over the embedded spinner panel, and the build
     *  steps run behind it. onSceneBuilt lifts the spinner. */
    private void showSceneLoading(long inDur) {
        View root   = view3DSceneRoot;
        View cover  = findViewById(R.id.v_3d_sceneCover);
        View chrome = findViewById(R.id.fl_3d_sceneChrome);
        if (root == null || chrome == null) return;
        root.animate().cancel();
        root.setAlpha(1f);
        root.setScaleX(1f);
        root.setScaleY(1f);
        if (cover != null) {
            cover.animate().cancel();
            cover.setAlpha(0f);
            cover.setVisibility(View.GONE);
        }
        applyToolbarCompactForScene(true);
        chrome.animate().cancel();
        chrome.setAlpha(0f);
        chrome.setScaleX(0.94f);
        chrome.setScaleY(0.94f);
        root.setVisibility(View.VISIBLE);
        chrome.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .withLayer()
                .setDuration(inDur)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        setSceneControlsEnabled(is3DEnabled);
        showSceneSpinner();
    }

    /** Removes the spinner panel at once (scene closed while building). */
    private void dismissSceneSpinner() {
        final com.jotapeveloper.fision.brand.FisionLoadingSurface sp = sceneSpinner;
        sceneSpinner = null;
        if (sp != null && sp.getParent() instanceof ViewGroup) {
            ((ViewGroup) sp.getParent()).removeView(sp);
        }
    }

    /** Makes the scene root visible under the opaque cover, chrome hidden,
     *  and brings the scene up to date (sprites, frame loop, lights). */
    private void showSceneCovered() {
        View root   = view3DSceneRoot;
        View cover  = findViewById(R.id.v_3d_sceneCover);
        View chrome = findViewById(R.id.fl_3d_sceneChrome);
        if (root == null || cover == null || chrome == null) return;
        root.animate().cancel();
        root.setAlpha(1f);
        root.setScaleX(1f);
        root.setScaleY(1f);
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
        applyToolbarCompactForScene(true);
        chrome.animate().cancel();
        chrome.setAlpha(0f);
        chrome.setScaleX(0.94f);
        chrome.setScaleY(0.94f);
        root.setVisibility(View.VISIBLE);
        ensureSceneSprites();
        if (audio3DScene != null) {
            audio3DScene.resume();
            // Sync illumination with the current 3D on/off state so the
            // scene arrives already dimmed when the effect is off.
            audio3DScene.setLightIntensity(is3DEnabled ? 1f : 0.20f);
        }
    }

    /** Fades the cover out and the chrome in once the scene is warm (or
     *  after the fallback delay, whichever comes first). */
    private void scheduleSceneReveal(long dur) {
        View root = view3DSceneRoot;
        if (root == null) return;
        final int token = ++sceneRevealToken;
        sceneRevealPending = true;
        Runnable reveal = () -> {
            if (!sceneRevealPending || token != sceneRevealToken) return;
            sceneRevealPending = false;
            View cover  = findViewById(R.id.v_3d_sceneCover);
            View chrome = findViewById(R.id.fl_3d_sceneChrome);
            if (cover == null || chrome == null) return;
            cover.animate().alpha(0f)
                    .setDuration(dur)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> cover.setVisibility(View.GONE))
                    .start();
            chrome.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .withLayer()
                    .setDuration(dur)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        };
        if (audio3DScene != null) audio3DScene.runWhenWarm(reveal);
        root.postDelayed(reveal, audio3DScene != null ? SCENE_REVEAL_FALLBACK_MS : 0L);
    }

    /** Drops any queued entry step or armed reveal. */
    private void cancelPendingSceneEntry() {
        if (sceneEnterRunnable != null && view3DSceneRoot != null) {
            view3DSceneRoot.removeCallbacks(sceneEnterRunnable);
        }
        sceneEnterRunnable = null;
        sceneRevealPending = false;
        sceneRevealToken++;
        if (audio3DScene != null) audio3DScene.cancelWarmWaiters();
    }

    /** Scene exit: the backdrop cover fades in over the surface while the
     *  chrome zooms out; the root is hidden once fully covered. */
    private void transitionOutOfScene(long outDur) {
        View root   = view3DSceneRoot;
        View cover  = findViewById(R.id.v_3d_sceneCover);
        View chrome = findViewById(R.id.fl_3d_sceneChrome);
        if (root == null || cover == null || chrome == null) return;
        if (sceneEnterRunnable != null || sceneRevealPending || sceneSpinner != null) {
            // Exit before the reveal ran: the scene was never uncovered (the
            // build, if still running, goes on and the next entry resumes it).
            dismissSceneSpinner();
            root.setVisibility(View.GONE);
            resetSceneTransitionViews();
            if (audio3DScene != null) audio3DScene.pause();
            return;
        }
        cover.animate().cancel();
        if (cover.getVisibility() != View.VISIBLE) {
            cover.setAlpha(0f);
            cover.setVisibility(View.VISIBLE);
        }
        cover.animate().alpha(1f)
                .setDuration(outDur)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    root.setVisibility(View.GONE);
                    resetSceneTransitionViews();
                    if (audio3DScene != null) audio3DScene.pause();
                }).start();
        chrome.animate().cancel();
        chrome.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                .withLayer()
                .setDuration(outDur)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .start();
    }

    /** Reversa hacia el modo stems. Respeta chordsActive: si estaba off, no muestra
     *  the chords_container when returning — it used to force VISIBLE and reappear wrongly. */
    private void transitionOutOf3D(View chordsContainer) {
        final long OUT_DUR = 200L;
        final long IN_DUR  = 260L;
        float density = getResources().getDisplayMetrics().density;
        float slidePx = 18f * density;

        if (nebulaView != null) nebulaView.setOrbitBlobsVisible(true, true);
        // Scene: the opaque surface hides the stems stack until the root is
        // gone, so the stems enter after the cover completes (no overlap).
        final boolean scene = isAudio3DScene();
        if (scene) {
            transitionOutOfScene(OUT_DUR);
        } else {
            View root = active3DRoot();
            root.animate().cancel();
            root.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                    .withLayer()
                    .setDuration(OUT_DUR)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        root.setVisibility(View.GONE);
                        if (audio3DScene != null) audio3DScene.pause();
                    }).start();
        }

        // Restore the stems stack as a block with fade + slide from below.
        View stemsStack = rvStems != null ? (View) rvStems.getParent().getParent() : null;
        if (stemsStack != null) {
            stemsStack.setAlpha(0f);
            stemsStack.setTranslationY(slidePx);
            stemsStack.setVisibility(View.VISIBLE);
            stemsStack.animate().cancel();
            stemsStack.animate().alpha(1f).translationY(0f)
                    .withLayer()
                    .setStartDelay(scene ? OUT_DUR : OUT_DUR - 80L)
                    .setDuration(IN_DUR)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        // Respect chordsActive: if it was hidden, do not show it again.
        if (chordsContainer != null) {
            chordsContainer.setVisibility(chordsActive ? View.VISIBLE : View.GONE);
        }
    }

    /** Animates the stem icons with a cascaded fade+scale in after the transition.
     *  Each icon lands on its real alpha (0.35 if muted, 1 if active) and on the
     *  scale matching its distance (near=big, far=small). */
    private void animateStemIconsIn() {
        int i = 0;
        for (java.util.Map.Entry<String, android.widget.ImageView> e : stem3DIcons.entrySet()) {
            String stem = e.getKey();
            android.widget.ImageView iv = e.getValue();
            float alphaTarget = is3DStemMuted(stem)
                    ? StemListAdapter.ALPHA_DISABLED : StemListAdapter.ALPHA_ACTIVE;
            float[] pos = stem3DPositions.get(stem);
            float distance = pos != null
                    ? (float) Math.sqrt(pos[0]*pos[0] + pos[1]*pos[1])
                    : 1f;
            float d = Math.max(0.5f, distance);
            float scaleTarget = Math.max(0.55f, Math.min(1.35f, 1f / d));
            iv.setAlpha(0f);
            iv.setScaleX(0.6f);
            iv.setScaleY(0.6f);
            iv.setTag(R.id.view3D_root, Boolean.TRUE);   // "animating" flag so scale is not clobbered
            iv.animate().cancel();
            iv.animate().alpha(alphaTarget).scaleX(scaleTarget).scaleY(scaleTarget)
                    .setStartDelay(30L * i)
                    .setDuration(300L)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                    .withEndAction(() -> iv.setTag(R.id.view3D_root, null))
                    .start();
            i++;
        }
    }

    /** Builds (or refreshes) the stem icons in the 3D container. Each icon starts at
     *  its saved position, or on a default frontal semicircle. */
    /** Attaches the same on-check listener to the scene-mode enable switch
     *  as the flat one, and keeps its checked state in sync. Safe to pass
     *  null (no-op) so callers don't have to guard. */
    private void wireSceneEnabledSwitch(com.google.android.material.switchmaterial.SwitchMaterial sw) {
        if (sw == null) return;
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(is3DEnabled);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            apply3DEnabled(checked);
            if (sw3DEnabled != null && sw3DEnabled.isChecked() != checked) {
                sw3DEnabled.setOnCheckedChangeListener(null);
                sw3DEnabled.setChecked(checked);
                sw3DEnabled.setOnCheckedChangeListener((b2, c2) -> apply3DEnabled(c2));
            }
        });
    }

    /** Engine azimuth (+π/2 = right ear) of a stem in scene mode: the saved
     *  pose (pos3DX/Y, engine convention) or the frontal semicircle slot
     *  {@code i} of {@code n} when nothing is saved. */
    private static float sceneEngineAzimuth(StemSetting ss, int i, int n) {
        if (!Float.isNaN(ss.pos3DX) && !Float.isNaN(ss.pos3DY)
                && (ss.pos3DX != 0f || ss.pos3DY != 0f)) {
            return (float) Math.atan2(ss.pos3DX, -ss.pos3DY);
        }
        float t = (n > 1) ? (float) i / (n - 1) : 0.5f;
        return (float) (-Math.PI / 2 + Math.PI * t);
    }

    /** Saved horizontal radius of a stem in scene mode (1.5 m default). */
    private static float sceneHorizontalDistance(StemSetting ss) {
        return Float.isNaN(ss.distance3D) ? 1.5f : ss.distance3D;
    }

    /** Sends the saved scene pose of a stem to the engine. The StemSetting is
     *  the source of truth (drag, distance slider and dance keep it current);
     *  the engine takes spherical coordinates (true 3D distance). */
    private void pushScenePoseToEngine(int trackId, StemSetting ss, int i, int n) {
        if (engine == null) return;
        float elev = (float) Math.toRadians(ss.elevationDeg);
        engine.setTrack3DPosition(trackId, sceneEngineAzimuth(ss, i, n),
                sphericalDist(sceneHorizontalDistance(ss), elev), ss.elevationDeg);
    }

    // ---------------- Scene look tuning (debuggable builds only) ----------------

    /** True while the debug tuning panel is open (playback controls hidden, back disabled). */
    private boolean sceneTuningOpen = false;
    /** Re-reads every tuning slider from the scene (after a pattern swap). */
    private final java.util.List<Runnable> tuneRefreshers = new java.util.ArrayList<>();

    private interface TuneGet { float get(); }
    private interface TuneSet { void set(float v); }

    /** Debug-only overlay on the 3D scene: sliders bound to the scene's live
     *  {@code Tuning} (room textures, TV backlight, lamps) plus a copy
     *  button that puts the values, ready to hard-code, on the clipboard and
     *  in logcat. Not created in release builds. */
    private void maybeInstallSceneTuningPanel() {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        if (audio3DScene == null) return;
        android.widget.FrameLayout chrome = findViewById(R.id.fl_3d_sceneChrome);
        if (chrome == null) return;
        final com.jotapeveloper.fision.audio3d.Audio3DSceneController.Tuning t = audio3DScene.getTuning();
        final float d = getResources().getDisplayMetrics().density;

        // Toggle pill under the header.
        TextView toggle = new TextView(this);
        toggle.setText("DBG");
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        toggle.setPadding((int) (10 * d), (int) (4 * d), (int) (10 * d), (int) (4 * d));
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(12 * d);
        pill.setColor(0xCC202326);
        pill.setStroke((int) d, 0x66FFFFFF);
        toggle.setBackground(pill);
        android.widget.FrameLayout.LayoutParams tlp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.START);
        tlp.topMargin = (int) (56 * d);
        tlp.setMarginStart((int) (14 * d));
        chrome.addView(toggle, tlp);

        // Panel: scrollable list of rows that takes the place of the playback
        // controls (same slot, same height), so the scene stays fully free
        // for orbiting while tuning. Only the pill swaps them back.
        final View bottomControls = findViewById(R.id.bottomLayout);
        if (bottomControls == null || !(bottomControls.getParent() instanceof LinearLayout)) return;
        LinearLayout bottomParent = (LinearLayout) bottomControls.getParent();
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVisibility(View.GONE);
        scroll.setBackgroundColor(0xFF101214);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
        scroll.addView(list);
        bottomParent.addView(scroll, bottomParent.indexOfChild(bottomControls) + 1,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        bottomControls.getLayoutParams().height));
        toggle.setOnClickListener(v -> {
            sceneTuningOpen = !sceneTuningOpen;
            scroll.setVisibility(sceneTuningOpen ? View.VISIBLE : View.GONE);
            bottomControls.setVisibility(sceneTuningOpen ? View.GONE : View.VISIBLE);
        });

        com.google.android.material.button.MaterialButton patterns =
                new com.google.android.material.button.MaterialButton(this);
        patterns.setText("Patrones pared / suelo…");
        patterns.setAllCaps(false);
        patterns.setBackgroundTintList(ColorStateList.valueOf(0xFF2A2E31));
        patterns.setOnClickListener(v -> openScenePatternDialog());
        list.addView(patterns);

        LinearLayout sec = addTuneSection(list, "Paredes");
        addTuneRow(sec, "Gris base", 0, 160, 0, t::getWallGrey, v -> t.setWallGrey(Math.round(v)));
        addTuneRow(sec, "Acento", 0f, 1f, 2, t::getWallAccent, t::setWallAccent);
        addTuneRow(sec, "Contraste", 0f, 3f, 2, t::getWallContrast, t::setWallContrast);
        addTuneRow(sec, "Granularidad (m)", 0.1f, 3f, 2, t::getWallTileM, t::setWallTileM);
        addTuneRow(sec, "Rotación (°)", 0f, 180f, 0, t::getWallRotationDeg, t::setWallRotationDeg);
        addTuneRow(sec, "Desenfoque (px)", 0f, 6f, 0, () -> t.getWallBlurPx(), v -> t.setWallBlurPx(Math.round(v)));
        addTuneRow(sec, "Nitidez", 0f, 3f, 2, t::getWallSharpen, t::setWallSharpen);
        sec = addTuneSection(list, "Techo");
        addTuneRow(sec, "Oscurecer", 0f, 1f, 2, t::getCeilingDarken, t::setCeilingDarken);
        sec = addTuneSection(list, "Suelo");
        addTuneRow(sec, "Tono base", 0, 120, 0, t::getFloorBase, v -> t.setFloorBase(Math.round(v)));
        addTuneRow(sec, "Acento", 0f, 1f, 2, t::getFloorAccent, t::setFloorAccent);
        addTuneRow(sec, "Contraste", 0f, 4f, 2, t::getFloorContrast, t::setFloorContrast);
        addTuneRow(sec, "Granularidad (m)", 0.2f, 4f, 2, t::getFloorTileM, t::setFloorTileM);
        addTuneRow(sec, "Rotación (°)", 0f, 180f, 0, t::getFloorRotationDeg, t::setFloorRotationDeg);
        addTuneRow(sec, "Desenfoque (px)", 0f, 6f, 0, () -> t.getFloorBlurPx(), v -> t.setFloorBlurPx(Math.round(v)));
        addTuneRow(sec, "Nitidez", 0f, 3f, 2, t::getFloorSharpen, t::setFloorSharpen);
        sec = addTuneSection(list, "Halo de la tele");
        addTuneRow(sec, "Tamaño (m)", 2.2f, 6f, 2, t::getBacklightSize, t::setBacklightSize);
        addTuneRow(sec, "Intensidad", 0f, 1f, 2, t::getBacklightAlpha, t::setBacklightAlpha);
        addTuneRow(sec, "Caída", 0.05f, 1f, 2, t::getBacklightFalloff, t::setBacklightFalloff);
        addTuneRow(sec, "Desenfoque (px)", 0f, 24f, 0, t::getBacklightBlur, v -> t.setBacklightBlur(Math.round(v)));
        sec = addTuneSection(list, "Lámparas");
        addTuneRow(sec, "Lúmenes", 5000f, 3000000f, 0, t::getLampLumens, t::setLampLumens);
        addTuneRow(sec, "Ambiente (IBL)", 0f, 1f, 2, t::getAmbientFactor, t::setAmbientFactor);
        sec = addTuneSection(list, "Cámara");
        addTuneRow(sec, "Focal (mm)", 12f, 50f, 1, t::getFocalMm, t::setFocalMm);

        com.google.android.material.button.MaterialButton resetCam =
                new com.google.android.material.button.MaterialButton(this);
        resetCam.setText("Pose inicial");
        resetCam.setAllCaps(false);
        resetCam.setBackgroundTintList(ColorStateList.valueOf(0xFF2A2E31));
        resetCam.setOnClickListener(v -> {
            if (audio3DScene != null) audio3DScene.resetCameraPose();
        });
        sec.addView(resetCam);

        // Scene closed by the app (e.g. next song with 3D off) while the panel
        // is open: the pill is gone, so the controls are restored here. Posts
        // on a detached view (activity gone) never run, which ends the loop.
        Runnable panelGuard = new Runnable() {
            @Override public void run() {
                if (audio3DScene == null) return;
                if (sceneTuningOpen && !chrome.isShown()) {
                    sceneTuningOpen = false;
                    scroll.setVisibility(View.GONE);
                    if (bottomControls != null) bottomControls.setVisibility(View.VISIBLE);
                }
                chrome.postDelayed(this, 200L);
            }
        };
        chrome.post(panelGuard);

        com.google.android.material.button.MaterialButton copy =
                new com.google.android.material.button.MaterialButton(this);
        copy.setText("Copiar valores");
        copy.setAllCaps(false);
        copy.setBackgroundTintList(ColorStateList.valueOf(accent()));
        copy.setOnClickListener(v -> {
            String values = audio3DScene != null ? audio3DScene.debugDescribe() : t.describe();
            Log.d("Audio3DTuning", values);
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Audio3D tuning", values));
                Toast.makeText(this, "Valores copiados", Toast.LENGTH_SHORT).show();
            } catch (Throwable e) {
                Toast.makeText(this, "Valores en logcat (Audio3DTuning)", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = (int) (10 * d);
        list.addView(copy, clp);
    }

    private void addTuneHeader(LinearLayout list, String title) {
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(accent());
        h.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        float d = getResources().getDisplayMetrics().density;
        h.setPadding(0, (int) (10 * d), 0, (int) (2 * d));
        list.addView(h);
    }

    /** Collapsible panel section: tappable header (title + chevron) over a
     *  body that holds the section rows. Starts collapsed. Returns the body. */
    private LinearLayout addTuneSection(LinearLayout list, String title) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(0, (int) (10 * d), 0, (int) (6 * d));
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(accent());
        h.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.ImageView chevron = new android.widget.ImageView(this);
        chevron.setImageResource(R.drawable.ic_collapsed);
        chevron.setImageTintList(ColorStateList.valueOf(0xFFE0E3E5));
        header.addView(chevron, new LinearLayout.LayoutParams((int) (20 * d), (int) (20 * d)));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        header.setOnClickListener(v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.setImageResource(open ? R.drawable.ic_expanded : R.drawable.ic_collapsed);
        });
        list.addView(header);
        list.addView(body);
        return body;
    }

    /** One labeled slider row bound to a tuning getter/setter; every change
     *  is applied to the live scene. [decimals] formats the value label. */
    private void addTuneRow(LinearLayout list, String label, float from, float to, int decimals,
                            TuneGet get, TuneSet set) {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFE0E3E5);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        String fmt = "%s  %." + decimals + "f";
        com.google.android.material.slider.Slider sl = new com.google.android.material.slider.Slider(this);
        sl.setValueFrom(from);
        sl.setValueTo(to);
        float start = Math.max(from, Math.min(to, get.get()));
        sl.setValue(start);
        tv.setText(String.format(java.util.Locale.US, fmt, label, start));
        sl.setTrackActiveTintList(ColorStateList.valueOf(accent()));
        sl.setThumbTintList(ColorStateList.valueOf(
                com.jotapeveloper.fision.design.AccentColorManager.resolveAccentSecondary(this)));
        sl.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE);
        sl.addOnChangeListener((s, v, fromUser) -> {
            tv.setText(String.format(java.util.Locale.US, fmt, label, v));
            if (!fromUser) return;
            set.set(v);
            if (audio3DScene != null) audio3DScene.applyTuning();
        });
        tuneRefreshers.add(() -> sl.setValue(Math.max(from, Math.min(to, get.get()))));
        list.addView(tv);
        list.addView(sl);
    }

    /** Debug picker for the wall/ceiling and floor patterns: a thumbnail
     *  grid per surface; tapping selects, Confirm applies to the scene. */
    private void openScenePatternDialog() {
        if (audio3DScene == null) return;
        final com.jotapeveloper.fision.audio3d.Audio3DSceneController.Tuning t = audio3DScene.getTuning();
        final com.jotapeveloper.fision.audio3d.Audio3DSceneController.SurfacePattern[] sel =
                { t.getWallPattern(), t.getFloorPattern(), t.getPanelPattern(), t.getTrapPattern() };
        final float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.delete_dialogbg);
        root.setPadding((int) (20 * d), (int) (18 * d), (int) (20 * d), (int) (14 * d));
        TextView title = new TextView(this);
        title.setText("Patrones de la sala");
        title.setTextColor(Color.WHITE);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f);
        root.addView(title);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        addTuneHeader(content, "Pared y techo");
        addPatternGrid(content, com.jotapeveloper.fision.audio3d.Audio3DSceneController.Surface.WALL, sel, 0);
        addTuneHeader(content, "Suelo");
        addPatternGrid(content, com.jotapeveloper.fision.audio3d.Audio3DSceneController.Surface.FLOOR, sel, 1);
        addTuneHeader(content, "Paneles");
        addPatternGrid(content, com.jotapeveloper.fision.audio3d.Audio3DSceneController.Surface.PANEL, sel, 2);
        addTuneHeader(content, "Pilares de las esquinas");
        addPatternGrid(content, com.jotapeveloper.fision.audio3d.Audio3DSceneController.Surface.TRAP, sel, 3);
        // Grid list capped at 60% of the screen; taller content scrolls.
        content.measure(View.MeasureSpec.makeMeasureSpec(
                getResources().getDisplayMetrics().widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED);
        int maxListH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(content.getMeasuredHeight(), maxListH)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.END);
        com.google.android.material.button.MaterialButton bCancel =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        bCancel.setText(android.R.string.cancel);
        bCancel.setAllCaps(false);
        com.google.android.material.button.MaterialButton bConfirm =
                new com.google.android.material.button.MaterialButton(this);
        bConfirm.setText("Confirm");
        bConfirm.setAllCaps(false);
        bConfirm.setBackgroundTintList(ColorStateList.valueOf(accent()));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMarginStart((int) (10 * d));
        buttons.addView(bCancel);
        buttons.addView(bConfirm, blp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int) (10 * d);
        root.addView(buttons, rowLp);

        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(
                this, R.style.Theme_Fision_BlurDialog)
                .setView(root).create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        applyActivityBlur();
        dlg.setOnDismissListener(x -> removeActivityBlur());
        bCancel.setOnClickListener(x -> dlg.dismiss());
        bConfirm.setOnClickListener(x -> {
            if (audio3DScene != null) {
                audio3DScene.setSurfacePatterns(sel[0], sel[1], sel[2], sel[3]);
                for (Runnable r : tuneRefreshers) r.run();
            }
            dlg.dismiss();
        });
        showWithPlayerAccent(dlg);
    }

    /** Thumbnail grid of the patterns offered for one surface; the tapped
     *  cell becomes {@code sel[slot]} and gets the accent outline. */
    private void addPatternGrid(LinearLayout parent,
                                com.jotapeveloper.fision.audio3d.Audio3DSceneController.Surface surface,
                                com.jotapeveloper.fision.audio3d.Audio3DSceneController.SurfacePattern[] sel,
                                int slot) {
        final float d = getResources().getDisplayMetrics().density;
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(3);
        final java.util.List<View> cells = new java.util.ArrayList<>();
        final java.util.List<com.jotapeveloper.fision.audio3d.Audio3DSceneController.SurfacePattern> owners =
                new java.util.ArrayList<>();
        final Runnable paint = () -> {
            for (int i = 0; i < cells.size(); i++) {
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(10 * d);
                boolean on = owners.get(i) == sel[slot];
                bg.setStroke((int) ((on ? 2.5f : 1f) * d), on ? accent() : 0x33FFFFFF);
                cells.get(i).setBackground(bg);
            }
        };
        for (com.jotapeveloper.fision.audio3d.Audio3DSceneController.SurfacePattern p :
                com.jotapeveloper.fision.audio3d.Audio3DSceneController.SurfacePattern.values()) {
            boolean floor = surface == com.jotapeveloper.fision.audio3d.Audio3DSceneController.Surface.FLOOR;
            if (floor ? !p.getOnFloor() : !p.getOnWall()) continue;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cell.setPadding((int) (5 * d), (int) (5 * d), (int) (5 * d), (int) (5 * d));
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 7 * d);
                }
            });
            iv.setImageBitmap(audio3DScene.patternPreview(p, surface));
            cell.addView(iv, new LinearLayout.LayoutParams((int) (78 * d), (int) (78 * d)));
            TextView label = new TextView(this);
            label.setText(p.getLabel());
            label.setTextColor(0xFFE0E3E5);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
            label.setGravity(android.view.Gravity.CENTER);
            label.setMaxWidth((int) (78 * d));
            cell.addView(label);
            cell.setOnClickListener(v -> {
                sel[slot] = p;
                paint.run();
            });
            android.widget.GridLayout.LayoutParams glp = new android.widget.GridLayout.LayoutParams();
            glp.setMargins((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
            grid.addView(cell, glp);
            cells.add(cell);
            owners.add(p);
        }
        paint.run();
        parent.addView(grid);
    }

    /** Builds the scene sprites only when the song changed since the last build. */
    private void ensureSceneSprites() {
        if (sceneSpritesStale) build3DSceneSprites();
    }

    /** Feeds the Filament scene with the current stem set. Icons decoded
     *  from res/raw, spherical pose taken from the persisted StemSetting
     *  when present, otherwise the frontal semicircle default. */
    private void build3DSceneSprites() {
        if (audio3DScene == null || playbackService == null) return;
        sceneSpritesStale = false;
        // Half-resolution decode (500px source → 250px): sharp at on-screen
        // sprite size with a quarter of the texture upload cost.
        android.graphics.BitmapFactory.Options iconOpts = new android.graphics.BitmapFactory.Options();
        iconOpts.inSampleSize = 2;
        java.util.List<String> order = playbackService.getStemOrder();
        boolean hasV2 = playbackService.hasStem("vocals2");
        java.util.List<com.jotapeveloper.fision.audio3d.Audio3DSceneController.StemPose> list =
                new java.util.ArrayList<>(order.size());
        int N = order.size();
        for (int i = 0; i < N; i++) {
            String stem = order.get(i);
            int resId = StemSpec.iconResource(stem, hasV2);
            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeResource(getResources(), resId, iconOpts);
            if (bmp == null) continue;
            StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, stem);
            // The scene camera looks at the head from behind, so the sprite
            // azimuth is the sign-flipped engine azimuth (same mapping as the
            // drag listener and the reset).
            float sceneAz = -sceneEngineAzimuth(ss, i, N);
            float elev = (float) Math.toRadians(ss.elevationDeg);
            float dist = sceneHorizontalDistance(ss);
            list.add(new com.jotapeveloper.fision.audio3d.Audio3DSceneController.StemPose(
                    stem, bmp, sceneAz, heightMFromPose(dist, elev), dist));
            // Push the saved pose to the audio engine so headphones hear the
            // same position the sprite is drawn at.
            if (engine != null) {
                Integer trackId = playbackService.getStemTrack(stem);
                if (trackId != null) {
                    pushScenePoseToEngine(trackId, ss, i, N);
                    engine.setTrack3DMuted(trackId, ss.mute3D);
                    engine.setTrack3DOmnipresent(trackId, ss.omnipresent);
                }
            }
        }
        audio3DScene.setStems(list);
        // The stems refresh runs on every song load with 3D enabled — the
        // wall screen must catch up here regardless of PrepareControls'
        // timing (which may fire before audio3DScene exists).
        SongMetadata sm3d = playbackService != null ? playbackService.getCurrentMetadata() : null;
        pushSongCardToScene(
                sm3d != null ? sm3d.displayTitle : null,
                sm3d != null ? sm3d.artist : null);
        audio3DScene.setDragListener((id, azRad, elevRad, dist, isFinal) -> {
            StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, id);
            // Scene camera looks at the head from behind (default pose is
            // az=180°). A world +X sprite is on the user's LEFT from that
            // POV, so flip the sign before handing the azimuth to the
            // engine (which treats +π/2 as right ear).
            float engineAz = -azRad;
            ss.pos3DX = (float) Math.sin(engineAz);
            ss.pos3DY = -(float) Math.cos(engineAz);
            ss.elevationDeg = (float) Math.toDegrees(elevRad);
            // dist is the horizontal radius (XY plane); the engine needs the
            // true spherical distance for attenuation and HRTF.
            ss.distance3D   = dist;
            stem3DPositions.put(id, new float[]{ss.pos3DX, ss.pos3DY});
            if (playbackService != null && engine != null) {
                Integer trackId = playbackService.getStemTrack(id);
                if (trackId != null) {
                    engine.setTrack3DPosition(trackId, engineAz, sphericalDist(dist, elevRad), ss.elevationDeg);
                }
            }
            // Only hit Room on settle events (drag release, dance stop) so
            // 60 fps dance ticks don't flood the DB thread and blow up the
            // process.
            if (isFinal) persistStemSettings();
        });
        audio3DScene.setTapListener((id, event) -> {
            switch (event) {
                case TAP:
                    selected3DStem = id;
                    audio3DScene.setStemSelected(id);
                    syncSceneDistanceSliderForSelected();
                    syncSceneWaveSliderForSelected();
                    break;
                case LONG_PRESS:
                    toggle3DStemMute(id);
                    boolean muted = is3DStemMuted(id);
                    audio3DScene.setStemMuted(id, muted);
                    break;
                case DOUBLE_TAP:
                    toggle3DStemOmnipresentScene(id);
                    break;
            }
        });
        // Reflect the persisted mute state on first build.
        java.util.List<String> orderNow = playbackService != null
                ? playbackService.getStemOrder() : java.util.Collections.emptyList();
        for (String stem : orderNow) {
            StemSetting ss0 = StemSettingsCodec.getOrDefault(stemSettings, stem);
            if (ss0.mute3D) audio3DScene.setStemMuted(stem, true);
            if (ss0.omnipresent) audio3DScene.setStemOmnipresent(stem, true);
        }
    }

    private void build3DIcons() {
        if (fl3DStemContainer == null || playbackService == null) return;
        fl3DStemContainer.removeAllViews();
        stem3DIcons.clear();
        java.util.List<String> order = playbackService.getStemOrder();
        boolean hasV2 = playbackService.hasStem("vocals2");
        float density = getResources().getDisplayMetrics().density;
        int iconSizePx = (int) (44f * density);

        for (int i = 0; i < order.size(); i++) {
            final String stem = order.get(i);
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(StemSpec.iconResource(stem, hasV2));
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    iconSizePx, iconSizePx);
            iv.setLayoutParams(lp);
            // Icons start invisible (alpha=0). animateStemIconsIn reveals them.
            iv.setAlpha(0f);
            // Restore omnipresent tint if saved (persistent, not overridden
            // by the selection — the selection uses a background ring, not colorFilter).
            StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, stem);
            applyOmnipresentTint(iv, ss.omnipresent);
            fl3DStemContainer.addView(iv);
            stem3DIcons.put(stem, iv);
            attach3DDragListener(iv, stem);
        }
        // Place in positions (default = frontal semicircle) once the container
        // has its size measured (post-draw).
        fl3DStemContainer.post(this::place3DIconsAtSavedOrDefault);
    }

    private void place3DIconsAtSavedOrDefault() {
        if (fl3DStemContainer == null || iv3DHead == null) return;
        int w = fl3DStemContainer.getWidth();
        int h = fl3DStemContainer.getHeight();
        if (w <= 0 || h <= 0) return;
        float[] headC = get3DHeadCenter(w, h);
        int cx = (int) headC[0];
        int cy = (int) headC[1];
        // Default semicircle radius = ~0.35 of the width
        float radius = w * 0.35f;
        java.util.List<String> order = playbackService != null
                ? playbackService.getStemOrder() : java.util.Collections.emptyList();
        int N = order.size();
        for (int i = 0; i < N; i++) {
            String stem = order.get(i);
            android.widget.ImageView iv = stem3DIcons.get(stem);
            if (iv == null) continue;
            float[] pos = stem3DPositions.get(stem);
            float nx, ny;
            if (pos != null) {
                nx = pos[0]; ny = pos[1];
            } else {
                // Spread across the frontal semicircle: azimuth from -pi/2 to +pi/2
                float t = (N > 1) ? (float) i / (N - 1) : 0.5f;
                float ang = (float) (-Math.PI / 2 + Math.PI * t);
                nx = (float) Math.sin(ang);
                ny = -(float) Math.cos(ang);   // negative = up on screen = "front"
                stem3DPositions.put(stem, new float[]{ nx, ny });
            }
            int px = cx + (int)(nx * radius) - iv.getWidth()/2;
            int py = cy + (int)(ny * radius) - iv.getHeight()/2;
            // Clamp Y against the header bottom so it does not overlap the title
            float minY = get3DHeaderBottom();
            if (py < minY) {
                py = (int) minY;
                ny = (py + iv.getHeight()/2f - cy) / radius;
                stem3DPositions.put(stem, new float[]{ nx, ny });
            }
            iv.setX(px);
            iv.setY(py);
            // Apply to the engine with this first placement (also refreshes visual scale)
            apply3DPositionToEngine(stem, nx, ny);
        }
    }

    /** Convierte la posicion normalizada del icono a azimuth/distance y la aplica al motor.
     *  Also refreshes the visual scale of the icon to give a "distance" feedback — without
     *  this, moving the icon up feels like elevation rather than moving away. */
    private void apply3DPositionToEngine(String stem, float nx, float ny) {
        if (playbackService == null) return;
        Integer trackId = playbackService.getStemTrack(stem);
        if (trackId == null || engine == null) return;
        // azimuth: 0 = front (up on screen). atan2(x, -y).
        float azimuth  = (float) Math.atan2(nx, -ny);
        // distance: magnitude of (nx,ny). On the default semicircle = 1.0.
        float distance = (float) Math.sqrt(nx*nx + ny*ny);
        if (distance < 0.1f) distance = 0.1f;
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        engine.setTrack3DPosition(trackId, azimuth, distance, s.elevationDeg);
        engine.setTrack3DOmnipresent(trackId, s.omnipresent);
        // Visual feedback: scale inversely proportional to the distance.
        android.widget.ImageView iv = stem3DIcons.get(stem);
        if (iv != null && !isIconAnimating(iv)) applyIconVisualForDistance(iv, distance);
    }

    /** Re-applies the current 3D position of the stem to the engine (without recomputing from pixels).
     *  Used by the elevation slider and the omnipresent toggle. */
    private void apply3DPositionForStem(String stem) {
        float[] pos = stem3DPositions.get(stem);
        if (pos == null) return;
        apply3DPositionToEngine(stem, pos[0], pos[1]);
    }

    /** True if the icon is mid fade/scale animator (entry or long press). */
    private boolean isIconAnimating(View v) {
        return v.getTag(R.id.view3D_root) != null;
    }

    /** Icon scale based on normalized distance: near=big, far=small.
     *  Range [0.55, 1.35] with a distance floor to keep it from exploding when it is glued
     *  to the center. Soft alpha with distance to reinforce the "far away" feeling. */
    private void applyIconVisualForDistance(android.widget.ImageView iv, float distance) {
        float d = Math.max(0.5f, distance);
        float scale = Math.max(0.55f, Math.min(1.35f, 1f / d));
        iv.setScaleX(scale);
        iv.setScaleY(scale);
    }

    /** Applies the 3D effect on/off to ALL engine stems. */
    private void apply3DEnabled(boolean enabled) {
        is3DEnabled = enabled;
        refreshB3dTint();
        updateDB("audio3DEnabled", enabled ? 1 : 0);
        animate3DLightsOverlay(enabled);
        // Scene mode: dim the Filament lights instead of relying on the
        // dark overlay (which reads as a flat black band on the 3D scene).
        if (isAudio3DScene() && audio3DScene != null) {
            audio3DScene.animateLights(enabled, 260L);
            // With 3D audio off the per-stem controls have no effect —
            // grey them out (vertical slider + dance + reset).
            setSceneControlsEnabled(enabled);
            // And the scene itself goes inert: no orbit/zoom, no sprite
            // dragging.
            audio3DScene.setInteractionEnabled(enabled);
        }
        // Normal sliders visually disabled when 3D is on (F5)
        if (stemsAdapter != null) {
            stemsAdapter.setEditingLocked(enabled);
            stemsAdapter.notifyDataSetChanged();
        }
        if (playbackService == null || engine == null) return;
        java.util.List<String> order = playbackService.getStemOrder();
        final boolean scene = isAudio3DScene();
        for (int i = 0; i < order.size(); i++) {
            String stem = order.get(i);
            Integer trackId = playbackService.getStemTrack(stem);
            if (trackId == null) continue;
            StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
            if (enabled) {
                // Pose first, then enable: the stem starts rendering 3D
                // already at the position its sprite/icon is drawn at.
                if (scene) {
                    pushScenePoseToEngine(trackId, s, i, order.size());
                } else {
                    float[] pos = stem3DPositions.get(stem);
                    if (pos != null) apply3DPositionToEngine(stem, pos[0], pos[1]);
                }
                engine.setTrack3DOmnipresent(trackId, s.omnipresent);
            }
            engine.setTrack3DEnabled(trackId, enabled);
            if (enabled) {
                // Push the saved 3D mute flag so muted stems stay silent as soon
                // as the pipeline turns on (before the user interacts).
                engine.setTrack3DMuted(trackId, s.mute3D);
            } else {
                // On disable, re-apply the normal volume/pan (saved in Room)
                applyStemVolumeToEngine(stem);
            }
        }
    }

    /** Enables or greys out the scene-mode per-stem controls when the 3D
     *  audio switch is off. */
    private void setSceneControlsEnabled(boolean enabled) {
        // Everything of the scene stays disabled while it builds; the 3D
        // switch only then (it does not depend on the 3D state).
        boolean ready = isSceneReady();
        enabled = enabled && ready;
        View swScene = findViewById(R.id.sw_3d_enabledScene);
        if (swScene != null) {
            swScene.setEnabled(ready);
            swScene.setAlpha(ready ? 1f : 0.35f);
        }
        float alpha = enabled ? 1f : 0.35f;
        if (sb3DDistanceScene != null) {
            sb3DDistanceScene.setEnabled(enabled);
            sb3DDistanceScene.setAlpha(alpha);
        }
        com.google.android.material.slider.Slider slWave =
                findViewById(R.id.sl_3d_danceOscScene);
        if (slWave != null) {
            slWave.setEnabled(enabled);
            slWave.setAlpha(alpha);
        }
        View waveGroup = findViewById(R.id.ll_3d_waveSpeedScene);
        if (waveGroup != null) {
            waveGroup.setAlpha(alpha);
        }
        if (b3DDanceScene != null) {
            b3DDanceScene.setEnabled(enabled);
            b3DDanceScene.setAlpha(alpha);
        }
        com.google.android.material.button.MaterialButton bResetScene =
                findViewById(R.id.b_3d_resetScene);
        if (bResetScene != null) {
            bResetScene.setEnabled(enabled);
            bResetScene.setAlpha(alpha);
        }
    }

    /** Drag + long press (mute) + double tap (omnipresent) on the icon. */
    private void attach3DDragListener(android.widget.ImageView iv, String stem) {
        final float[] downOffset = {0f, 0f};
        final float[] downRaw    = {0f, 0f};
        final boolean[] dragged  = { false };
        final boolean[] longFired = { false };
        final long[] lastTapMs   = { 0L };
        final int touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        final long LONG_PRESS_MS = 450L;
        final long DOUBLE_TAP_MS = 280L;
        final Runnable[] longPressPending = { null };

        iv.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downOffset[0] = ev.getX();
                    downOffset[1] = ev.getY();
                    downRaw[0] = ev.getRawX();
                    downRaw[1] = ev.getRawY();
                    dragged[0] = false;
                    longFired[0] = false;
                    v.setAlpha(v.getAlpha() * 0.85f);
                    longPressPending[0] = () -> {
                        if (dragged[0] || longFired[0]) return;
                        longFired[0] = true;
                        toggle3DStemMute(stem, v);
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    };
                    v.postDelayed(longPressPending[0], LONG_PRESS_MS);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dxRaw = ev.getRawX() - downRaw[0];
                    float dyRaw = ev.getRawY() - downRaw[1];
                    if (!dragged[0] && Math.hypot(dxRaw, dyRaw) > touchSlop) {
                        dragged[0] = true;
                        if (longPressPending[0] != null) v.removeCallbacks(longPressPending[0]);
                        // If it is dancing, "grabbing" pauses this stem from the dance.
                        onDanceGrab(stem);
                    }
                    if (!dragged[0]) return true;

                    int cw = fl3DStemContainer.getWidth();
                    int ch = fl3DStemContainer.getHeight();
                    float newX = v.getX() + (ev.getX() - downOffset[0]);
                    float newY = v.getY() + (ev.getY() - downOffset[1]);
                    if (newX < 0) newX = 0;
                    // Minimum Y = bottom edge of the header (title + info + switch),
                    // so the icons do not overlap the labels.
                    float minY = get3DHeaderBottom();
                    if (newY < minY) newY = minY;
                    if (newX > cw - v.getWidth())  newX = cw - v.getWidth();
                    // Max Y = top of the bottom row (sliders + buttons) if active,
                    // so the icon does not overlap the controls.
                    float maxYPx = ch - v.getHeight();
                    View bottomRow = findViewById(R.id.ll_3d_bottomRow);
                    if (bottomRow != null && bottomRow.getHeight() > 0) {
                        float d = getResources().getDisplayMetrics().density;
                        float limit = bottomRow.getTop() - 8f * d - v.getHeight();
                        if (limit < maxYPx) maxYPx = limit;
                    }
                    if (newY > maxYPx) newY = maxYPx;

                    // Elliptical hitbox: do not let the icon cross the head volume.
                    // Bigger than the visible head to leave room and avoid "getting inside".
                    float[] headC = get3DHeadCenter(cw, ch);
                    float iconCX = newX + v.getWidth()/2f;
                    float iconCY = newY + v.getHeight()/2f;
                    float rx = iv3DHead.getWidth()  * 0.60f;
                    float ry = iv3DHead.getHeight() * 0.55f;
                    float dx = iconCX - headC[0];
                    float dy = iconCY - headC[1];
                    float inside = (dx*dx)/(rx*rx) + (dy*dy)/(ry*ry);
                    if (inside < 1f) {
                        float k = 1f / (float) Math.sqrt(inside + 1e-6f);
                        dx *= k; dy *= k;
                        iconCX = headC[0] + dx;
                        iconCY = headC[1] + dy;
                        newX = iconCX - v.getWidth()/2f;
                        newY = iconCY - v.getHeight()/2f;
                    }
                    v.setX(newX);
                    v.setY(newY);

                    float radius = cw * 0.35f;
                    float nx = (iconCX - headC[0]) / radius;
                    float ny = (iconCY - headC[1]) / radius;
                    stem3DPositions.put(stem, new float[]{ nx, ny });
                    if (is3DEnabled) apply3DPositionToEngine(stem, nx, ny);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (longPressPending[0] != null) v.removeCallbacks(longPressPending[0]);
                    // Restore alpha to the real stem state
                    v.setAlpha(iconAlphaFor(stem));
                    if (dragged[0]) {
                        // If dancing, release re-attaches on the fly with
                        // the new radius (adjusts the effective distance slider).
                        onDanceRelease(stem);
                        float[] p = stem3DPositions.get(stem);
                        if (p != null && !danceStems.contains(stem)) {
                            StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, stem);
                            ss.pos3DX = p[0];
                            ss.pos3DY = p[1];
                            persistStemSettings();
                        }
                        // Position changed → show reset button
                        showResetButton();
                    } else if (!longFired[0]) {
                        // Single tap = select; double tap = toggle omnipresent
                        long now = System.currentTimeMillis();
                        if (now - lastTapMs[0] < DOUBLE_TAP_MS) {
                            toggle3DStemOmnipresent(stem);
                            lastTapMs[0] = 0L;
                        } else {
                            selectStem3D(stem);
                            lastTapMs[0] = now;
                        }
                    }
                    return true;
            }
            return false;
        });
    }

    /** Y of the header bottom edge (title + info + switch) in fl_3d_stemContainer
     *  coordinates. Used as the top limit to avoid overlaps. */
    private float get3DHeaderBottom() {
        View header = view3DRoot != null ? view3DRoot.findViewById(R.id.ll_3d_header) : null;
        if (header == null) return 0f;
        return header.getBottom() + 4f * getResources().getDisplayMetrics().density;
    }

    /** Coordinates of the head's visual center inside the container. */
    private float[] get3DHeadCenter(int cw, int ch) {
        if (iv3DHead == null) return new float[]{ cw / 2f, ch - 80 };
        float cx = iv3DHead.getX() + iv3DHead.getWidth() / 2f;
        float cy = iv3DHead.getY() + iv3DHead.getHeight() / 2f;
        return new float[]{ cx, cy };
    }

    /** Marks the stem as selected: the elevation slider now drives this one.
     *  The selection uses a RING around the icon (not colorFilter) — this way it does
     *  not clobber the green tint of omnipresent mode, which also uses colorFilter.
     *  If the stem is in omnipresent mode the slider stays disabled (elevation
     *  does not apply when the engine forces az/elev/dist to 0/0/1). */
    private void selectStem3D(String stem) {
        selected3DStem = stem;
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        if (sb3DElevation != null) {
            sb3DElevation.setValue(Math.max(-30f, Math.min(70f, s.elevationDeg)));
            refreshElevationSliderEnabled(s.omnipresent);
        }
        // Selection ring: only on the current icon, null on the rest.
        for (java.util.Map.Entry<String, android.widget.ImageView> e : stem3DIcons.entrySet()) {
            android.widget.ImageView iv = e.getValue();
            iv.setBackgroundResource(e.getKey().equals(stem)
                    ? R.drawable.ring_stem_selected : 0);
        }
    }

    /** Visually enables/disables the elevation slider. Used when the stem
     *  seleccionado entra/sale del modo omnipresente. */
    private void refreshElevationSliderEnabled(boolean stemOmnipresent) {
        if (sb3DElevation == null) return;
        sb3DElevation.setEnabled(!stemOmnipresent);
        sb3DElevation.setAlpha(stemOmnipresent ? 0.35f : 1f);
    }

    private final Runnable hideElevationTooltip = () -> {
        if (tv3DElevation != null) tv3DElevation.animate().alpha(0f)
                .setDuration(150).withEndAction(() -> tv3DElevation.setVisibility(View.GONE))
                .start();
    };

    /** Shows the tooltip with the elevation value pinned to the slider thumb.
     *  Hidden 400ms after the last change. */
    private void showElevationTooltip(int val) {
        if (tv3DElevation == null || sb3DElevation == null) return;
        tv3DElevation.setText(val + "°");
        tv3DElevation.removeCallbacks(hideElevationTooltip);
        tv3DElevation.animate().cancel();
        tv3DElevation.setAlpha(1f);
        tv3DElevation.setVisibility(View.VISIBLE);
        // Position to the left of the slider, vertically aligned with the thumb.
        tv3DElevation.post(() -> {
            float sliderTop = sb3DElevation.getY();
            float sliderLeft = sb3DElevation.getX();
            float thumbY = sb3DElevation.getThumbCenterY();
            float density = getResources().getDisplayMetrics().density;
            tv3DElevation.setX(sliderLeft - tv3DElevation.getWidth() - 6f * density);
            tv3DElevation.setY(sliderTop + thumbY - tv3DElevation.getHeight() / 2f);
        });
        tv3DElevation.postDelayed(hideElevationTooltip, 700);
    }

    private final Runnable hideDistanceTooltip = () -> {
        if (tv3DDistanceScene != null) tv3DDistanceScene.animate().alpha(0f)
                .setDuration(150).withEndAction(() -> tv3DDistanceScene.setVisibility(View.GONE))
                .start();
    };

    /** Refreshes the scene distance slider with the persisted value of the
     *  currently selected stem (or the scene default when none saved).
     *  Silent update — the change listener does not fire because we call
     *  setValue with fromUser=false paths (the slider only propagates
     *  through touch). */
    private void syncSceneDistanceSliderForSelected() {
        if (sb3DDistanceScene == null || selected3DStem == null) return;
        StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, selected3DStem);
        Float cur = audio3DScene != null ? audio3DScene.getStemDistance(selected3DStem) : null;
        float val = cur != null ? cur
                : (Float.isNaN(ss.distance3D) ? 1.5f : ss.distance3D);
        sb3DDistanceScene.setValue(Math.max(0.3f, Math.min(3.0f, val)));
    }

    /** Re-syncs the wave speed slider thumb to the selected stem's stored
     *  speed whenever a stem is selected, so the control never shows a
     *  stale position. */
    private void syncSceneWaveSliderForSelected() {
        com.google.android.material.slider.Slider sl = findViewById(R.id.sl_3d_danceOscScene);
        if (sl == null || !sl.isEnabled() || selected3DStem == null) return;
        float val = danceOscSpeeds.getOrDefault(selected3DStem, 0.5f);
        if (Math.abs(sl.getValue() - val) > 0.001f) {
            sl.setValue(val);
        }
    }

    /** The wave speed slider only makes sense when at least one stem has
     *  the sine-wave oscillation enabled in the dance dialog. */
    /** Swing speed slider: only while the scene dance runs with at least one
     *  swinging stem — outside the dance it has nothing to act on. */
    private void refreshWaveSliderVisibility() {
        View waveGroup = findViewById(R.id.ll_3d_waveSpeedScene);
        if (waveGroup != null) {
            boolean dancing = audio3DScene != null && audio3DScene.isDanceActive();
            waveGroup.setVisibility(dancing && !danceSineStems.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /** Scene-mode counterpart of {@link #showElevationTooltip}. Distance is
     *  rendered as "1.20 m" so the value reads as a physical quantity. */
    private void showDistanceTooltip(float val) {
        if (tv3DDistanceScene == null || sb3DDistanceScene == null) return;
        tv3DDistanceScene.setText(String.format(java.util.Locale.US, "%.2f m", val));
        tv3DDistanceScene.removeCallbacks(hideDistanceTooltip);
        tv3DDistanceScene.animate().cancel();
        tv3DDistanceScene.setAlpha(1f);
        tv3DDistanceScene.setVisibility(View.VISIBLE);
        tv3DDistanceScene.post(() -> {
            float sliderTop = sb3DDistanceScene.getY();
            float sliderLeft = sb3DDistanceScene.getX();
            float thumbY = sb3DDistanceScene.getThumbCenterY();
            float density = getResources().getDisplayMetrics().density;
            tv3DDistanceScene.setX(sliderLeft - tv3DDistanceScene.getWidth() - 6f * density);
            tv3DDistanceScene.setY(sliderTop + thumbY - tv3DDistanceScene.getHeight() / 2f);
        });
        tv3DDistanceScene.postDelayed(hideDistanceTooltip, 700);
    }

    /** True if any stem has its 3D config modified from the default
     *  (position outside the frontal semicircle, elevation != 0, mute or omnipresent). */
    private boolean has3DModifications() {
        if (playbackService == null) return false;
        java.util.List<String> order = playbackService.getStemOrder();
        int N = order.size();
        for (int i = 0; i < N; i++) {
            String stem = order.get(i);
            StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
            if (s.omnipresent) return true;
            if (s.mute3D) return true;
            if (Math.abs(s.elevationDeg) > 0.5f) return true;
            if (!Float.isNaN(s.distance3D) && Math.abs(s.distance3D - 1.5f) > 0.03f) return true;
            if (!Float.isNaN(s.pos3DX) && !Float.isNaN(s.pos3DY)) {
                float t = (N > 1) ? (float) i / (N - 1) : 0.5f;
                float ang = (float) (-Math.PI / 2 + Math.PI * t);
                float defX = (float) Math.sin(ang);
                float defY = -(float) Math.cos(ang);
                if (Math.abs(s.pos3DX - defX) > 0.02f || Math.abs(s.pos3DY - defY) > 0.02f) return true;
            }
        }
        return false;
    }

    /** Shows the reset button with fade in. Must be called when the user modifies
     *  any position/elev/mute/omni of the 3D system. */
    private void showResetButton() {
        if (b3DReset == null) return;
        if (b3DReset.getVisibility() == View.VISIBLE && b3DReset.getAlpha() >= 0.99f) return;
        b3DReset.animate().cancel();
        b3DReset.setVisibility(View.VISIBLE);
        b3DReset.animate().alpha(1f).setDuration(220).start();
    }

    /** Hides the reset button with fade out. Once done, sets it to GONE so
     *  evitar consumir taps fantasma. */
    private void hideResetButton() {
        if (b3DReset == null) return;
        b3DReset.animate().cancel();
        b3DReset.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> b3DReset.setVisibility(View.GONE))
                .start();
    }

    /** Animates every icon to its default position (frontal semicircle) and clears
     *  elevation + omnipresent + selection. Total duration <= 1.5s with cascade. */
    private void reset3DPositions() {
        if (isAudio3DScene()) { reset3DPositionsScene(); return; }
        if (fl3DStemContainer == null || playbackService == null) return;
        java.util.List<String> order = playbackService.getStemOrder();
        int N = order.size();
        int cw = fl3DStemContainer.getWidth();
        int ch = fl3DStemContainer.getHeight();
        if (cw <= 0 || ch <= 0) return;
        float[] headC = get3DHeadCenter(cw, ch);
        float radius = cw * 0.35f;

        for (int i = 0; i < N; i++) {
            final String stem = order.get(i);
            final android.widget.ImageView iv = stem3DIcons.get(stem);
            if (iv == null) continue;
            // Default: frontal semicircle
            float t = (N > 1) ? (float) i / (N - 1) : 0.5f;
            float ang = (float) (-Math.PI / 2 + Math.PI * t);
            final float nxT = (float) Math.sin(ang);
            final float nyT = -(float) Math.cos(ang);

            final float x0 = iv.getX();
            final float y0 = iv.getY();
            final float xT = headC[0] + nxT * radius - iv.getWidth()/2f;
            final float yT = headC[1] + nyT * radius - iv.getHeight()/2f;

            // Clear stem visual flags
            iv.setBackgroundResource(0);
            StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, stem);
            ss.elevationDeg = 0f;
            ss.omnipresent  = false;
            ss.mute3D       = false;
            ss.pos3DX = nxT;
            ss.pos3DY = nyT;
            applyOmnipresentTint(iv, false);
            iv.setAlpha(StemListAdapter.ALPHA_ACTIVE);
            Integer trackId = playbackService != null ? playbackService.getStemTrack(stem) : null;
            if (trackId != null && engine != null) engine.setTrack3DMuted(trackId, false);

            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(700L);
            va.setStartDelay(60L * i);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            va.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                float x = x0 + (xT - x0) * f;
                float y = y0 + (yT - y0) * f;
                iv.setX(x);
                iv.setY(y);
                float nx = (x + iv.getWidth()/2f  - headC[0]) / radius;
                float ny = (y + iv.getHeight()/2f - headC[1]) / radius;
                stem3DPositions.put(stem, new float[]{ nx, ny });
                if (is3DEnabled) apply3DPositionToEngine(stem, nx, ny);
            });
            va.start();
        }
        selected3DStem = null;
        persistStemSettings();
    }

    /** Scene-mode counterpart of {@link #reset3DPositions}. Restores the
     *  frontal semicircle default in the Filament scene, clears elevation,
     *  omnipresent, mute3D and distance3D for every stem, then pushes the
     *  new poses to the controller with a cascaded animator so the sprites
     *  glide back rather than teleport. */
    private void reset3DPositionsScene() {
        if (audio3DScene == null || playbackService == null) return;
        java.util.List<String> order = playbackService.getStemOrder();
        int N = order.size();
        final float DEF_DISTANCE = 1.5f;
        for (int i = 0; i < N; i++) {
            final String stem = order.get(i);
            float t = (N > 1) ? (float) i / (N - 1) : 0.5f;
            // Frontal semicircle in engine coordinates (-π/2 = left ear).
            float engineAz = (float) (-Math.PI / 2 + Math.PI * t);
            // Scene camera looks at the head from behind → flip sign to
            // land the sprite in front of the listener.
            final float sceneAz = -engineAz;

            StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, stem);
            ss.pos3DX = (float) Math.sin(engineAz);
            ss.pos3DY = -(float) Math.cos(engineAz);
            ss.elevationDeg = 0f;
            ss.omnipresent  = false;
            ss.mute3D       = false;
            ss.distance3D   = Float.NaN;
            stem3DPositions.put(stem, new float[]{ss.pos3DX, ss.pos3DY});
            Integer trackId = playbackService.getStemTrack(stem);
            if (trackId != null && engine != null) {
                engine.setTrack3DMuted(trackId, false);
                engine.setTrack3DOmnipresent(trackId, false);
                engine.setTrack3DPosition(trackId, engineAz, DEF_DISTANCE, 0f);
            }

            // Animate azimuth + height + distance together from the live
            // pose so the sprite glides home instead of teleporting.
            com.jotapeveloper.fision.audio3d.Audio3DSceneController.StemPose cur =
                    audio3DScene.getStemPose(stem);
            final float fromAz  = cur != null ? cur.azimuthRad : sceneAz;
            final float fromH   = cur != null ? cur.heightM    : 0f;
            final float fromDist = cur != null ? cur.distance  : DEF_DISTANCE;
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(700L);
            va.setStartDelay(60L * i);
            // Spline-style speed: ease in and out.
            va.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            va.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                float az   = fromAz   + (sceneAz      - fromAz)   * f;
                float h    = fromH    + (0f           - fromH)    * f;
                float dist = fromDist + (DEF_DISTANCE - fromDist) * f;
                audio3DScene.updateStemPose(stem, az, h, dist);
            });
            va.start();
            audio3DScene.setStemMuted(stem, false);
            audio3DScene.setStemOmnipresent(stem, false);
        }
        selected3DStem = null;
        audio3DScene.setStemSelected(null);
        if (sb3DDistanceScene != null) sb3DDistanceScene.setValue(1.5f);
        persistStemSettings();
    }

    // ================================================================
    // === STEMS DANCE (animated orbit) ============================
    // ================================================================
    /** Setup dialog: pick stems + speed + distance. */
    /** Linear height (m) above/below head centre for a saved pose pair
     *  (horizontal radius, elevation in radians). */
    private static float heightMFromPose(float rh, float elevRad) {
        return rh * (float) Math.tan(elevRad);
    }

    /** True spherical distance from the listener for the audio engine —
     *  attenuation and HRTF need the 3D radius, not the horizontal one. */
    private static float sphericalDist(float rh, float elevRad) {
        return (float) (rh / Math.max(0.05f, Math.cos(elevRad)));
    }

    /** Scene mode: inline speed + distance sliders in the bottom row, shown
     *  only while the dance runs (the setup dialog carries no sliders). */
    private void setSceneDanceSlidersVisible(boolean visible) {
        View group = findViewById(R.id.ll_3d_danceSlidersScene);
        if (group == null) return;
        if (visible) {
            com.google.android.material.slider.Slider sp = findViewById(R.id.sl_3d_danceSpeedScene);
            com.google.android.material.slider.Slider di = findViewById(R.id.sl_3d_danceDistanceScene);
            if (sp != null) sp.setValue(danceSpeed);
            if (di != null) di.setValue(danceDistance);
        }
        group.setVisibility(visible ? View.VISIBLE : View.GONE);
        refreshWaveSliderVisibility();
    }

    private void showStemsDanceDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dlgView = inflater.inflate(R.layout.stems_dance_setup_dialog, null);
        androidx.recyclerview.widget.RecyclerView rvStems = dlgView.findViewById(R.id.rv_dance_stems);
        com.google.android.material.checkbox.MaterialCheckBox cbRandomSwing =
                dlgView.findViewById(R.id.cb_dance_random_swing);
        com.google.android.material.button.MaterialButton bCancel = dlgView.findViewById(R.id.b_dance_cancel);
        com.google.android.material.button.MaterialButton bStart  = dlgView.findViewById(R.id.b_dance_start);

        // Speed and distance are tuned from the inline sliders of the 3D
        // view. The distance unit depends on the mode: scene uses metres
        // (0.3..3.0, same as the vertical slider), flat the legacy radius
        // multiplier (0.4..1.2) — bring a value from the other mode back in.
        boolean scene = isAudio3DScene();
        if (scene) {
            if (danceDistance < 0.3f || danceDistance > 3.0f) danceDistance = 1.5f;
        } else if (danceDistance < 0.4f || danceDistance > 1.2f) {
            danceDistance = 1.0f;
        }
        // Swing only exists in scene mode.
        cbRandomSwing.setVisibility(scene ? View.VISIBLE : View.GONE);
        cbRandomSwing.setChecked(danceRandomSwing);

        // Tile grid. Selection starts with the previous dance set (or all
        // stems); the swing toggle is per stem and scene-only. Four stems
        // lay out as 2x2, anything else in rows of three.
        java.util.List<String> order = playbackService.getStemOrder();
        boolean hasV2 = playbackService.hasStem("vocals2");
        final java.util.Set<String> selection = new java.util.HashSet<>(danceStems);
        if (selection.isEmpty()) selection.addAll(order);
        final java.util.Set<String> sineSel = new java.util.HashSet<>(danceSineStems);
        DanceStemTileAdapter adapter = new DanceStemTileAdapter(order, selection, sineSel, hasV2, scene,
                () -> bStart.setEnabled(!selection.isEmpty()));
        int n = order.size();
        int span = (n == 4 || n <= 2) ? 2 : 3;
        rvStems.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, span));
        // No item animator: the default one interpolates bounds on rebind
        // and made tiles visibly resize when toggling a selection.
        rvStems.setItemAnimator(null);
        rvStems.setAdapter(adapter);
        bStart.setEnabled(!selection.isEmpty());

        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(
                this, R.style.Theme_Fision_BlurDialog)
                .setView(dlgView)
                .create();
        android.view.Window win = dlg.getWindow();
        if (win != null) win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());
        bCancel.setOnClickListener(v -> dlg.dismiss());
        bStart.setOnClickListener(v -> {
            if (adapter.getSelected().isEmpty()) { dlg.dismiss(); return; }
            danceStems.clear();
            danceStems.addAll(adapter.getSelected());
            danceSineStems.clear();
            danceSineStems.addAll(adapter.getSine());
            danceRandomSwing = cbRandomSwing.isChecked();
            refreshWaveSliderVisibility();
            dlg.dismiss();
            if (isAudio3DScene() && audio3DScene != null) {
                audio3DScene.startDance(new java.util.ArrayList<>(danceStems),
                        new java.util.HashSet<>(danceSineStems), danceSpeed, danceDistance,
                        danceRandomSwing);
                if (b3DDanceScene != null) b3DDanceScene.setIconResource(R.drawable.ic_stop);
                setSceneDanceSlidersVisible(true);
            } else {
                startDance();
            }
        });
        showWithPlayerAccent(dlg);
    }

    /** Starts the dance: snapshots the current angle of each selected stem and
     *  postDelayed of the tick. Switches the button icon to stop. */
    private void startDance() {
        if (fl3DStemContainer == null) return;
        int cw = fl3DStemContainer.getWidth();
        int ch = fl3DStemContainer.getHeight();
        if (cw <= 0 || ch <= 0) return;
        float[] headC = get3DHeadCenter(cw, ch);
        float radius = cw * 0.35f;
        danceAngle.clear();
        danceRadialFactor.clear();
        danceSpeedFactor.clear();
        java.util.Random rnd = new java.util.Random();
        for (String stem : danceStems) {
            android.widget.ImageView iv = stem3DIcons.get(stem);
            if (iv == null) continue;
            float cx = iv.getX() + iv.getWidth()/2f;
            float cy = iv.getY() + iv.getHeight()/2f;
            float dx = cx - headC[0];
            float dy = cy - headC[1];
            danceAngle.put(stem, (float) Math.atan2(dy, dx));
            // Base radial factor = current stem distance over the reference radius (respects
            // where it was before the dance started). It is multiplied by danceDistance.
            float curDist = (float) Math.hypot(dx, dy) / radius;
            if (curDist < 0.3f) curDist = 1f;   // fallback if it was too close to the center
            danceRadialFactor.put(stem, curDist);
            // Each stem gets a slightly different speed so it feels alive
            danceSpeedFactor.put(stem, 0.7f + rnd.nextFloat() * 0.6f);
        }
        danceLastNs = System.nanoTime();
        danceActive = true;
        danceGrabbed = null;
        if (b3DDance != null) b3DDance.setIconResource(R.drawable.ic_stop);
        // Show inline sliders with current values
        if (danceInlineSlidersContainer != null) danceInlineSlidersContainer.setVisibility(View.VISIBLE);
        if (danceInlineSlSpeed    != null) danceInlineSlSpeed.setValue(danceSpeed);
        if (danceInlineSlDistance != null) danceInlineSlDistance.setValue(danceDistance);
        danceHandler.removeCallbacks(danceTick);
        danceHandler.post(danceTick);
    }

    /** Stops the dance and leaves the stems where they end up. */
    private void stopDance() {
        danceActive = false;
        danceGrabbed = null;
        danceHandler.removeCallbacks(danceTick);
        if (b3DDance != null) b3DDance.setIconResource(R.raw.ic_play);
        // INVISIBLE (not GONE) so it reserves its width and the buttons stay to the right
        if (danceInlineSlidersContainer != null) danceInlineSlidersContainer.setVisibility(View.INVISIBLE);
        // Persist final position of each stem that was dancing
        for (String stem : danceStems) {
            float[] p = stem3DPositions.get(stem);
            if (p == null) continue;
            StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, stem);
            ss.pos3DX = p[0];
            ss.pos3DY = p[1];
        }
        persistStemSettings();
    }

    /** One dance tick (~60 fps): advances angles, moves icons, applies to the engine. */
    private void tickDance() {
        if (!danceActive || fl3DStemContainer == null) return;
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - danceLastNs) / 1e9f);
        danceLastNs = now;
        int cw = fl3DStemContainer.getWidth();
        int ch = fl3DStemContainer.getHeight();
        if (cw <= 0 || ch <= 0) return;
        float[] headC = get3DHeadCenter(cw, ch);
        float baseRadius = cw * 0.35f;
        // Maximum radius limit: do not leave the container on the right or cross the header on top
        float minY = get3DHeaderBottom();
        float density = getResources().getDisplayMetrics().density;
        float rMaxX = Math.min(headC[0], cw - headC[0]) - 30f * density;
        // While the dance is active, the bottom row (sliders + buttons) must not be covered by dancing stems.
        float bottomLimit = ch;
        View bottomRow = findViewById(R.id.ll_3d_bottomRow);
        if (bottomRow != null && bottomRow.getHeight() > 0) {
            bottomLimit = bottomRow.getTop() - 8f * density;
        }
        float rMaxY = Math.min(headC[1] - minY, bottomLimit - headC[1]) - 30f * density;
        float rMax = Math.max(50f, Math.min(rMaxX, rMaxY));
        float twoPi = (float) (2 * Math.PI);

        for (String stem : danceStems) {
            android.widget.ImageView iv = stem3DIcons.get(stem);
            if (iv == null) continue;
            if (stem.equals(danceGrabbed)) continue;  // grabbed: do not move
            Float ang = danceAngle.get(stem);
            Float rf  = danceRadialFactor.get(stem);
            Float sf  = danceSpeedFactor.get(stem);
            if (ang == null || rf == null || sf == null) continue;
            ang = (ang + twoPi * danceSpeed * sf * dt) % twoPi;
            danceAngle.put(stem, ang);
            float radius = Math.min(rMax, baseRadius * rf * danceDistance);
            float cx = headC[0] + (float) Math.cos(ang) * radius;
            float cy = headC[1] + (float) Math.sin(ang) * radius;
            float newX = cx - iv.getWidth()/2f;
            float newY = cy - iv.getHeight()/2f;
            iv.setX(newX);
            iv.setY(newY);
            float nx = (cx - headC[0]) / baseRadius;
            float ny = (cy - headC[1]) / baseRadius;
            stem3DPositions.put(stem, new float[]{ nx, ny });
            if (is3DEnabled) apply3DPositionToEngine(stem, nx, ny);
        }
    }

    /** When grabbing a dancing stem: store the new radial factor when released. */
    private void onDanceGrab(String stem) {
        if (!danceActive || !danceStems.contains(stem)) return;
        danceGrabbed = stem;
    }
    private void onDanceRelease(String stem) {
        if (!danceActive || !danceStems.contains(stem)) return;
        if (!stem.equals(danceGrabbed)) return;
        android.widget.ImageView iv = stem3DIcons.get(stem);
        if (iv != null && fl3DStemContainer != null) {
            int cw = fl3DStemContainer.getWidth();
            int ch = fl3DStemContainer.getHeight();
            float[] headC = get3DHeadCenter(cw, ch);
            float baseRadius = cw * 0.35f;
            float cx = iv.getX() + iv.getWidth()/2f;
            float cy = iv.getY() + iv.getHeight()/2f;
            float dx = cx - headC[0];
            float dy = cy - headC[1];
            float ang  = (float) Math.atan2(dy, dx);
            float dist = (float) Math.hypot(dx, dy) / baseRadius;
            danceAngle.put(stem, ang);
            // New radial factor that respects the distance chosen by the user
            if (danceDistance > 0.01f) danceRadialFactor.put(stem, dist / danceDistance);
        }
        danceGrabbed = null;
    }

    /** Explanatory dialog for Audio 3D mode. Follows the global dialog style (blur).
     *  Justified text (justificationMode=inter_word, API 26+). Actions legend
     *  below the body. Single "Close" button. */
    private void show3DInfoDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dlgView = inflater.inflate(R.layout.audio3d_info_dialog, null);
        com.google.android.material.button.MaterialButton bClose =
                dlgView.findViewById(R.id.b_audio3d_info_close);

        // Render HTML (<b>) in body + actions legend. Without this the <b>
        // tags appear as literal text.
        setHtml(dlgView, R.id.tv_audio3d_info_body,    R.string.audio3d_info_body);
        setHtml(dlgView, R.id.tv_audio3d_legend_drag,   R.string.audio3d_legend_drag);
        setHtml(dlgView, R.id.tv_audio3d_legend_tap,    R.string.audio3d_legend_tap);
        setHtml(dlgView, R.id.tv_audio3d_legend_double, R.string.audio3d_legend_double);
        setHtml(dlgView, R.id.tv_audio3d_legend_long,   R.string.audio3d_legend_long);
        setHtml(dlgView, R.id.tv_audio3d_legend_reset,  R.string.audio3d_legend_reset);

        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(
                this, R.style.Theme_Fision_BlurDialog)
                .setView(dlgView)
                .create();
        android.view.Window win = dlg.getWindow();
        if (win != null) win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());
        bClose.setOnClickListener(v -> dlg.dismiss());
        showWithPlayerAccent(dlg);
    }

    /** Sets the HTML string on a dialog TextView, rendering <b> tags. */
    private void setHtml(View root, int viewId, int strRes) {
        TextView tv = root.findViewById(viewId);
        if (tv == null) return;
        tv.setText(androidx.core.text.HtmlCompat.fromHtml(getString(strRes),
                androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT));
    }

    /** Toggles omnipresent mode. Uses colorFilter (persistent green tint).
     *  Independent of the selection ring. If the stem is the selected one,
     *  the elevation slider gets enabled/disabled based on the new state. */
    private void toggle3DStemOmnipresent(String stem) {
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        s.omnipresent = !s.omnipresent;
        persistStemSettings();
        android.widget.ImageView iv = stem3DIcons.get(stem);
        if (iv != null) {
            applyOmnipresentTint(iv, s.omnipresent);
            iv.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        }
        if (stem.equals(selected3DStem)) refreshElevationSliderEnabled(s.omnipresent);
        if (is3DEnabled) apply3DPositionForStem(stem);
        showResetButton();
    }

    /** Scene-mode omnipresent toggle. The flat path cannot be reused here:
     *  it reads the 2D icon map (empty until a drag happens) and would push
     *  a distance-1.0 pose to the engine. This one flips the engine flag
     *  directly, restores the live scene pose when leaving the mode, and
     *  marks the sprite with the accent halo. */
    private void toggle3DStemOmnipresentScene(String stem) {
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        s.omnipresent = !s.omnipresent;
        persistStemSettings();
        Integer trackId = playbackService != null ? playbackService.getStemTrack(stem) : null;
        if (trackId != null && engine != null) {
            engine.setTrack3DOmnipresent(trackId, s.omnipresent);
            if (!s.omnipresent && audio3DScene != null) {
                // Leaving the mode: hand the engine the sprite's real pose
                // (azimuth sign-flipped to engine convention, spherical
                // distance for attenuation/HRTF).
                com.jotapeveloper.fision.audio3d.Audio3DSceneController.StemPose p =
                        audio3DScene.getStemPose(stem);
                if (p != null) {
                    float elevRad = (float) Math.atan2(p.heightM, Math.max(0.1f, p.distance));
                    engine.setTrack3DPosition(trackId, -p.azimuthRad,
                            sphericalDist(p.distance, elevRad), (float) Math.toDegrees(elevRad));
                }
            }
        }
        if (audio3DScene != null) {
            audio3DScene.setStemOmnipresent(stem, s.omnipresent);
            android.view.View sv = findViewById(R.id.v_3d_scene);
            if (sv != null) sv.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        }
        showResetButton();
    }

    /** Applies (or clears) the persistent green tint of omnipresent mode on the icon. */
    private void applyOmnipresentTint(android.widget.ImageView iv, boolean on) {
        if (on) {
            iv.setColorFilter(
                    com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this),
                    android.graphics.PorterDuff.Mode.MULTIPLY);
        } else {
            iv.clearColorFilter();
        }
    }

    private float iconAlphaFor(String stem) {
        return is3DStemMuted(stem) ? StemListAdapter.ALPHA_DISABLED : StemListAdapter.ALPHA_ACTIVE;
    }

    /** "Lights off / lights on" effect when toggling the Audio 3D switch.
     *  Animates the alpha of the black overlay over the stems + head view.
     *  ON  → fade to 0 (200ms decelerate).
     *  OFF → fade to 0.72 (240ms accelerate). */
    private void animate3DLightsOverlay(boolean lightsOn) {
        float target = lightsOn ? 0f : 0.72f;
        long dur = lightsOn ? 220L : 240L;
        android.view.animation.Interpolator interp = lightsOn
                ? new android.view.animation.DecelerateInterpolator()
                : new android.view.animation.AccelerateInterpolator();
        animateOverlay(v3DDarkOverlay, target, dur, interp);
        // Scene mode uses a flat-black overlay + Filament light dim in
        // parallel. Overlay alpha is capped much lower than in flat mode
        // (0.45 vs 0.72) because the light dim also carries some of the
        // effect — going higher would make the scene disappear entirely.
        View sceneOverlay = findViewById(R.id.v_3d_darkOverlayScene);
        if (sceneOverlay != null) {
            float sceneTarget = lightsOn ? 0f : 0.35f;
            animateOverlay(sceneOverlay, sceneTarget, dur, interp);
        }
    }

    private void animateOverlay(View overlay, float target, long dur,
                                android.view.animation.Interpolator interp) {
        if (overlay == null) return;
        overlay.animate().cancel();
        overlay.animate().alpha(target).setDuration(dur).setInterpolator(interp).withLayer().start();
    }

    /** Toggles mute of the stem in 3D mode via the independent m3D flag. Does
     *  not touch the volume slider (the slider stays at whatever value it had
     *  outside 3D). The engine's Track applies the flag inside apply3D /
     *  apply3D_Hrtf; the normal setVolume path is unused here. */
    private void toggle3DStemMute(String stem, View icon) {
        boolean nowMuted = toggle3DStemMute(stem);
        icon.setAlpha(nowMuted ? StemListAdapter.ALPHA_DISABLED : StemListAdapter.ALPHA_ACTIVE);
    }

    /** Core toggle without the flat-mode icon. Returns the new mute state. */
    private boolean toggle3DStemMute(String stem) {
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        s.mute3D = !s.mute3D;
        persistStemSettings();
        Integer trackId = playbackService != null ? playbackService.getStemTrack(stem) : null;
        if (trackId != null && engine != null) {
            engine.setTrack3DMuted(trackId, s.mute3D);
        }
        showResetButton();
        return s.mute3D;
    }

    /** True if the stem is muted inside 3D mode. Decoupled from the slider. */
    private boolean is3DStemMuted(String stem) {
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        return s.mute3D;
    }

    /** Confirmation dialog for resetting stems to default. */
    private void showResetToDefaultDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dlgView = inflater.inflate(R.layout.delete_alert_dialog, null);
        android.widget.ImageView icon = dlgView.findViewById(R.id.iv_alertDialog_ic);
        TextView text = dlgView.findViewById(R.id.tv_alertDialog_text);
        com.google.android.material.button.MaterialButton bNo = dlgView.findViewById(R.id.b_no);
        com.google.android.material.button.MaterialButton bYes = dlgView.findViewById(R.id.b_yes);

        icon.setImageResource(R.drawable.ic_restore_pan);
        icon.setColorFilter(com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this));
        text.setText(R.string.drawer_reset_dialog_body);
        bYes.setText(R.string.drawer_reset_dialog_title);
        bNo.setText(R.string.drawer_reset_dialog_cancel);

        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(
                this, R.style.Theme_Fision_BlurDialog)
                .setView(dlgView)
                .create();
        android.view.Window win = dlg.getWindow();
        if (win != null) win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());
        bNo.setOnClickListener(v -> dlg.dismiss());
        bYes.setOnClickListener(v -> {
            dlg.dismiss();
            if (drawerLayout != null) drawerLayout.closeDrawer(androidx.core.view.GravityCompat.END);
            resetStemsToDefault();
        });
        showWithPlayerAccent(dlg);
    }

    /** Resets volumes (75) and pan (0) on every stem. Animates visible sliders. */
    private void resetStemsToDefault() {
        java.util.List<String> order = playbackService != null
                ? playbackService.getStemOrder() : java.util.Collections.emptyList();
        // Exit solo if it was active
        soloedStem = null;
        preSoloVolumes = null;
        for (String s : order) {
            StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, s);
            ss.volume = 75;
            ss.pan = 0f;
            stemLastVol.put(s, 75);
            applyStemVolumeToEngine(s);
            // Animate the visible slider if the ViewHolder is bound
            if (rvStems != null && stemsAdapter != null) {
                int idx = stemsAdapter.adapterPositionForStem(s);
                if (idx == RecyclerView.NO_POSITION) continue;
                RecyclerView.ViewHolder vh = rvStems.findViewHolderForAdapterPosition(idx);
                if (vh instanceof StemListAdapter.ViewHolder) {
                    StemListAdapter.ViewHolder svh = (StemListAdapter.ViewHolder) vh;
                    animateSliderTo(svh.sb_stemVol, 75);
                    svh.iv_stemIcon.setAlpha(StemListAdapter.ALPHA_ACTIVE);
                }
            }
        }
        persistStemSettings();
    }

    // ==================================================================
    //   LYRICS OVERLAY
    // ==================================================================

    /** Lazy findViewById + one-time listener wiring for the lyrics overlay. */
    private void ensureLyricsRefs() {
        if (lyricsRoot != null) return;
        lyricsRoot            = findViewById(R.id.lyricsRoot);
        cardLyricsLanguage    = findViewById(R.id.card_lyricsLanguage);
        ivLyricsFlag          = findViewById(R.id.iv_lyricsFlag);
        tvLyricsCardSubtitle  = findViewById(R.id.tv_lyricsCardSubtitle);
        bLyricsConfirm        = findViewById(R.id.b_lyricsConfirm);
        bLyricsPickerCancel   = findViewById(R.id.b_lyricsPickerCancel);
        llLyricsSkeleton      = findViewById(R.id.ll_lyricsSkeleton);
        tvLyricsStatus        = findViewById(R.id.tv_lyricsStatus);
        lyricsSkeletonView    = findViewById(R.id.lyricsSkeleton);
        bLyricsCancelJob      = findViewById(R.id.b_lyricsCancelJob);
        rvLyrics              = findViewById(R.id.rv_lyrics);
        bLyricsOptions        = findViewById(R.id.b_lyricsOptions);

        rvLyrics.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        // Removing the item animator prevents the resize (24sp bold ↔ 20sp)
        // from lagging behind the immediate scroll reposition, which looked
        // like the incoming line was "creeping up" from below.
        rvLyrics.setItemAnimator(null);
        mLyricsLinesAdapter = new LyricsLinesAdapter();
        mLyricsLinesAdapter.setLineClickListener(new LyricsLinesAdapter.LineClickListener() {
            @Override public void onLyricsRowClicked(LyricsLinesAdapter.Row row) {
                seekToLyricsRow(row);
            }
            @Override public void onLyricsWordClicked(double startSec) {
                seekToLyricsPosSec(startSec);
            }
        });
        rvLyrics.setAdapter(mLyricsLinesAdapter);

        cardLyricsLanguage.setOnClickListener(v -> openLanguagePickerDialog());
        bLyricsConfirm.setOnClickListener(v -> onLyricsConfirmClicked());
        bLyricsPickerCancel.setOnClickListener(v -> onLyricsPickerCancelClicked());
        bLyricsCancelJob.setOnClickListener(v -> showCancelJobDialog());
        bLyricsOptions.setOnClickListener(v -> LyricsOptionsBottomSheet.show(this,
                mLyricsKaraokeMode,
                Lyrics.hasWordTimestamps(mLoadedLyrics),
                new LyricsOptionsBottomSheet.Callback() {
                    @Override public void onReprocess() { onLyricsReprocessRequested(); }
                    @Override public void onKaraokeToggled(boolean enabled) {
                        setLyricsKaraokeMode(enabled);
                    }
                }));

        if (mLyricsMgr == null) {
            mLyricsMgr = new LyricsManager(this);
        }
        mLyricsMgr.setListener(new LyricsManager.Listener() {
            @Override public void onLyricsStateChanged(LyricsManager.State state, String errorMsg) {
                applyLyricsManagerState(state, errorMsg);
            }
            @Override public void onLyricsLoaded(Lyrics lyrics) {
                mLoadedLyrics = lyrics;
                mLyricsLinesAdapter.setLyrics(lyrics);
                mLyricsLinesAdapter.setKaraokeMode(mLyricsKaraokeMode);
                mLyricsActiveIdx = -1;
                setLyricsUiState(LyricsUiState.CONTENT);
            }
        });
        refreshLyricsCardFlag();
    }

    /** Toggles the lyrics overlay on / off. Wired to b_lyrics. */
    public void ShowLyrics(View v) {
        toggleLyricsView();
    }

    private void toggleLyricsView() {
        ensureLyricsRefs();
        // From the chord diagrams: close them (button back to its default
        // icon) and open the lyrics once they are out.
        if (isChordDiagramShown) {
            closeChordDiagrams(this::toggleLyricsView);
            return;
        }
        // Never overlap with the 3D view.
        if (is3DViewShown) return;
        isLyricsViewShown = !isLyricsViewShown;
        refreshBLyricsTint();
        View chordsContainer = findViewById(R.id.chords_container);
        if (isLyricsViewShown) {
            lyricsReprocessMode = false;
            // Snapshot chord visibility before refresh so the transition can
            // animate from the real starting state. refreshLyricsViewForCurrentSong
            // triggers setLyricsUiState which would otherwise apply the chord
            // overlay synchronously; the flag blocks that so the transition
            // owns the fade.
            boolean chordsWereVisible = chordsContainer != null
                    && chordsContainer.getVisibility() == View.VISIBLE;
            mLyricsOpeningTransition = true;
            refreshLyricsViewForCurrentSong();
            transitionIntoLyrics(chordsContainer, chordsWereVisible);
            mLyricsOpeningTransition = false;
        } else {
            transitionOutOfLyrics(chordsContainer);
        }
    }

    /** Decides which lyrics sub-state to show for the currently loaded song.
     *  Called on overlay open and on automatic song advance so the view stays
     *  in sync with what's on disk / in memory. Safe to invoke when the
     *  overlay is not visible: only mutates the ui state variables. */
    private void refreshLyricsViewForCurrentSong() {
        java.io.File folder = SongFolder;
        if (folder == null) {
            setLyricsUiState(LyricsUiState.PICKER);
            return;
        }
        // An active job wins over any cached / on-disk lyrics: re-opening the
        // overlay while a reprocess or a fresh transcription is in flight must
        // keep showing the skeleton, not silently drop back to the previous
        // content (which read as if the job had been cancelled).
        if (mLyricsMgr != null && mLyricsMgr.isActive()) {
            setLyricsUiState(LyricsUiState.SKELETON);
            return;
        }
        if (mLoadedLyrics != null) {
            setLyricsUiState(LyricsUiState.CONTENT);
            return;
        }
        if (LyricsManager.hasLocal(folder)) {
            setLyricsUiState(LyricsUiState.CONTENT);
            final java.io.File f = folder;
            new Thread(() -> {
                try {
                    Lyrics ly = mLyricsMgr.parseLocal(f);
                    runOnUiThread(() -> {
                        mLoadedLyrics = ly;
                        if (mLyricsLinesAdapter != null) {
                            mLyricsLinesAdapter.setLyrics(ly);
                            mLyricsLinesAdapter.setKaraokeMode(mLyricsKaraokeMode);
                        }
                        mLyricsActiveIdx = -1;
                    });
                } catch (Exception ignored) {
                    runOnUiThread(() -> setLyricsUiState(LyricsUiState.PICKER));
                }
            }, "lyrics-parse").start();
        } else {
            setLyricsUiState(LyricsUiState.PICKER);
        }
    }

    /** Convenience: current accent color resolved against this Activity theme. */
    private int accent() {
        return com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this);
    }

    private void refreshBLyricsTint() {
        if (bLyrics == null) return;
        int tint = isLyricsViewShown
                ? com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(this)
                : androidx.core.content.ContextCompat.getColor(this, R.color.AppWhite);
        bLyrics.setIconTint(android.content.res.ColorStateList.valueOf(tint));
    }

    /** Swaps the toolbar background so its bottom fade hands off cleanly to
     *  the lyrics recycler's DST_OUT fade (no double-fade band). Restores the
     *  default background when leaving the lyrics view. */
    private void applyToolbarBgForLyrics(boolean lyricsVisible) {
        View tb = findViewById(R.id.toolbar);
        if (tb == null) return;
        tb.setBackgroundResource(lyricsVisible
                ? R.drawable.player_toolbar_bg_lyrics
                : R.drawable.player_toolbar_bg);
    }

    /** Compact toolbar while the 3D scene is on screen: the 24dp bottom fade
     *  (and the empty band above it) goes, so the scene gains that height.
     *  Solid black either way. Swapped only while the scene cover is opaque
     *  or the scene is gone, so the relayout is never seen. */
    private void applyToolbarCompactForScene(boolean compact) {
        View tb = findViewById(R.id.toolbar);
        if (tb == null) return;
        float d = getResources().getDisplayMetrics().density;
        int h = (int) ((compact ? 56 : 80) * d);
        ViewGroup.LayoutParams lp = tb.getLayoutParams();
        if (lp.height == h) return;
        lp.height = h;
        tb.setLayoutParams(lp);
        tb.setPadding(tb.getPaddingLeft(), tb.getPaddingTop(), tb.getPaddingRight(),
                compact ? 0 : (int) (24 * d));
        if (compact) tb.setBackgroundColor(Color.BLACK);
        else tb.setBackgroundResource(R.drawable.player_toolbar_bg);
    }

    // ---------------- Chord diagrams ----------------

    /** Chords button. In diagrams mode it closes them; otherwise it
     *  unfolds the pill: carousel toggle below, diagrams on top. */
    private void onChordButton() {
        if (isChordDiagramShown) {
            closeChordDiagrams();
            return;
        }
        if (chordModePill == null) {
            chordModePill = new com.jotapeveloper.fision.chords.ChordModePill(this, b_chord,
                    newDiagramsIcon(),
                    new com.jotapeveloper.fision.chords.ChordModePill.Listener() {
                        @Override public void onCarouselToggle() { ShowChords(b_chord); }
                        @Override public void onDiagramsOpen() { openChordDiagrams(); }
                    });
        }
        chordModePill.show(!is3DViewShown && !isLyricsViewShown);
    }

    /** Chord grid icon of the diagrams entry (own instance, safe to tint). */
    private android.graphics.drawable.Drawable newDiagramsIcon() {
        android.graphics.drawable.Drawable icon = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(this, R.drawable.ic_chord_diagram);
        return icon != null ? icon.mutate() : new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
    }

    /** Typeface of the chord names, same rule as the carousel. */
    private android.graphics.Typeface chordTypeface() {
        try {
            return androidx.core.content.res.ResourcesCompat.getFont(this,
                    latinNotation ? R.font.yugothic_ui_regular : R.font.mus_glyhps);
        } catch (Exception e) {
            return android.graphics.Typeface.DEFAULT;
        }
    }

    /** Swaps the chords button icon with a shrink / grow morph. */
    private void morphChordButtonIcon(android.graphics.drawable.Drawable icon, int tint) {
        final int full = b_chord.getIconSize() > 0 ? b_chord.getIconSize()
                : (int) (24 * getResources().getDisplayMetrics().density);
        android.animation.ValueAnimator out = android.animation.ValueAnimator.ofInt(full, 0);
        out.setDuration(110);
        out.setInterpolator(new android.view.animation.AccelerateInterpolator());
        out.addUpdateListener(a -> b_chord.setIconSize((int) a.getAnimatedValue()));
        out.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                b_chord.setIcon(icon);
                b_chord.setIconTint(ColorStateList.valueOf(tint));
                android.animation.ValueAnimator in = android.animation.ValueAnimator.ofInt(0, full);
                in.setDuration(200);
                in.setInterpolator(new android.view.animation.OvershootInterpolator(2f));
                in.addUpdateListener(b -> b_chord.setIconSize((int) b.getAnimatedValue()));
                in.start();
            }
        });
        out.start();
    }

    /** Opens the chord diagrams: stems out, carousel forced visible (its own
     *  setting is restored on close), diagrams in with the lyrics transition. */
    private void openChordDiagrams() {
        if (isChordDiagramShown || is3DViewShown || isLyricsViewShown) return;
        if (chordDiagramRoot == null) chordDiagramRoot = findViewById(R.id.chordDiagram_root);
        if (chordDiagramRoot == null) return;
        if (chordDiagrams == null) {
            chordDiagrams = new com.jotapeveloper.fision.chords.ChordDiagramController(
                    this, chordDiagramRoot);
            // Swipe on the stage jumps to the next / previous chord change.
            chordDiagrams.setSeekListener(this::seekToBeat);
        }
        chordDiagrams.setNotation(latinNotation, preferFlat, chordTypeface());
        chordDiagrams.setShift(currentDisplayShift());
        chordDiagrams.setCells(beatCells);
        chordDiagrams.onBeat(Math.max(0, currentBeatIdx));
        isChordDiagramShown = true;
        morphChordButtonIcon(newDiagramsIcon(), accent());

        final long OUT_DUR = 200L;
        final long IN_DUR  = 300L;
        float slidePx = 18f * getResources().getDisplayMetrics().density;
        final View chordsContainer = findViewById(R.id.chords_container);
        final boolean revealChords = chordsContainer != null
                && chordsContainer.getVisibility() != View.VISIBLE;
        View stemsBox = rvStems != null ? (View) rvStems.getParent() : null;
        if (stemsBox != null) {
            stemsBox.animate().cancel();
            stemsBox.animate().alpha(0f).translationY(slidePx)
                    .withLayer()
                    .setDuration(OUT_DUR)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        stemsBox.setVisibility(View.GONE);
                        stemsBox.setTranslationY(0f);
                        // Carousel appears once the stems are gone, so the
                        // layout reflow is never visible.
                        if (revealChords) {
                            chordsContainer.setAlpha(0f);
                            chordsContainer.setVisibility(View.VISIBLE);
                            if (rvChords != null) rvChords.scrollToPosition(Math.max(0, currentBeatIdx));
                            chordsContainer.animate().alpha(1f).setDuration(IN_DUR)
                                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                    .start();
                        }
                    }).start();
        }
        chordDiagramRoot.setAlpha(0f);
        chordDiagramRoot.setScaleX(0.94f);
        chordDiagramRoot.setScaleY(0.94f);
        chordDiagramRoot.setVisibility(View.VISIBLE);
        chordDiagramRoot.animate().cancel();
        chordDiagramRoot.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .withLayer()
                .setStartDelay(OUT_DUR - 80L)
                .setDuration(IN_DUR)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** Closes the chord diagrams: back to the stems, carousel back to the
     *  song's own chords setting, button icon back to chords. */
    private void closeChordDiagrams() {
        closeChordDiagrams(null);
    }

    /**
     * Closes the chord diagrams. With [handoff] (opening lyrics or the 3D view
     * from the diagrams) the stems are not brought back, since the next view
     * hides them anyway (no flash); [handoff] runs once the diagrams are out.
     */
    private void closeChordDiagrams(@androidx.annotation.Nullable Runnable handoff) {
        if (!isChordDiagramShown) return;
        isChordDiagramShown = false;
        morphChordButtonIcon(chordIcon, chordsActive ? accent() : Color.WHITE);

        final long OUT_DUR = 200L;
        final long IN_DUR  = 260L;
        float slidePx = 18f * getResources().getDisplayMetrics().density;
        if (chordDiagramRoot != null) {
            chordDiagramRoot.animate().cancel();
            chordDiagramRoot.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                    .withLayer()
                    .setDuration(OUT_DUR)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> chordDiagramRoot.setVisibility(View.GONE))
                    .start();
        }
        final View chordsContainer = findViewById(R.id.chords_container);
        final boolean hideChords = !chordsActive && chordsContainer != null
                && chordsContainer.getVisibility() == View.VISIBLE;
        if (hideChords) {
            chordsContainer.animate().cancel();
            chordsContainer.animate().alpha(0f).setDuration(OUT_DUR)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        chordsContainer.setVisibility(View.GONE);
                        chordsContainer.setAlpha(1f);
                    }).start();
        }
        if (handoff != null) {
            // The next view opens once the diagrams (and the forced carousel)
            // are out; it handles the stems itself.
            // Slightly after OUT_DUR, so the carousel hide has committed its
            // final visibility (the next view reads it).
            new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isDestroyed) handoff.run();
            }, OUT_DUR + 40L);
            return;
        }
        View stemsBox = rvStems != null ? (View) rvStems.getParent() : null;
        if (stemsBox != null) {
            stemsBox.setAlpha(0f);
            stemsBox.setTranslationY(slidePx);
            stemsBox.setVisibility(View.VISIBLE);
            stemsBox.animate().cancel();
            stemsBox.animate().alpha(1f).translationY(0f)
                    .withLayer()
                    // Wait for the carousel to collapse so the stems do not jump.
                    .setStartDelay(hideChords ? OUT_DUR : OUT_DUR - 80L)
                    .setDuration(IN_DUR)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    private void transitionIntoLyrics(View chordsContainer, boolean chordsWereVisible) {
        final long OUT_DUR = 200L;
        final long IN_DUR  = 300L;
        float density = getResources().getDisplayMetrics().density;
        float slidePx = 18f * density;

        applyToolbarBgForLyrics(true);

        // Only hide the stems box, not the whole vertical stack — leaves the
        // chords_container in place so it can float over the lyrics overlay
        // (see the elevation bump below). rvStems.getParent() is the FrameLayout
        // wrapping the stems recycler + loading spinner; its parent is the
        // LinearLayout that also holds chords_container.
        View stemsBox = rvStems != null ? (View) rvStems.getParent() : null;
        if (stemsBox != null) {
            stemsBox.animate().cancel();
            stemsBox.setAlpha(1f);
            stemsBox.setTranslationY(0f);
            stemsBox.animate().alpha(0f).translationY(slidePx)
                    .withLayer()
                    .setDuration(OUT_DUR)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        stemsBox.setVisibility(View.GONE);
                        stemsBox.setTranslationY(0f);
                    }).start();
        }
        // Chord row transition strategies for the opening path:
        //  * destination CONTENT + chord already visible -> activate the
        //    overlay in place (elevation + z-order + padding) without
        //    refading alpha, since the user was already looking at chords.
        //  * destination non-CONTENT + chord visible -> fade the chord out
        //    in parallel with the lyricsRoot fade in.
        //  * chord was hidden -> nothing to animate; the CONTENT re-open
        //    (if any) will fade it in via animateChordsOverlayForLyrics.
        if (chordsActive && chordsWereVisible) {
            if (mLyricsUiState == LyricsUiState.CONTENT) {
                activateChordOverlayInPlace(chordsContainer);
            } else {
                fadeOutChordForLyricsOpen(chordsContainer);
            }
        }
        lyricsRoot.setAlpha(0f);
        lyricsRoot.setScaleX(0.94f);
        lyricsRoot.setScaleY(0.94f);
        lyricsRoot.setVisibility(View.VISIBLE);
        lyricsRoot.animate().cancel();
        lyricsRoot.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .withLayer()
                .setStartDelay(OUT_DUR - 80L)
                .setDuration(IN_DUR)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void transitionOutOfLyrics(View chordsContainer) {
        final long OUT_DUR = 200L;
        final long IN_DUR  = 260L;
        float density = getResources().getDisplayMetrics().density;
        float slidePx = 18f * density;

        applyToolbarBgForLyrics(false);

        // If chords were hidden by the lyrics config screens, restore them
        // with a fade in parallel to the stems slide-in so they don't pop
        // in beside the incoming controls. Snapshot the state now because
        // isLyricsViewShown has already been flipped to false.
        final View chordsView = findViewById(R.id.chords_container);
        final boolean chordsHiddenForConfig = chordsActive
                && chordsView != null
                && chordsView.getVisibility() != View.VISIBLE;

        lyricsRoot.animate().cancel();
        lyricsRoot.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                .withLayer()
                .setDuration(OUT_DUR)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    lyricsRoot.setVisibility(View.GONE);
                    // Reset chord z-order + padding only after the lyrics
                    // overlay has faded out. Doing it up front made the
                    // lyricsRoot briefly cover the chord row before its own
                    // fade animation started, so the chords looked to
                    // disappear behind the lyrics for a frame. Skip when
                    // the chord fade-in is already in flight — apply would
                    // reset alpha to 1 mid-animation.
                    if (!chordsHiddenForConfig) {
                        applyChordsOverlayForLyrics();
                    }
                }).start();

        View stemsBox = rvStems != null ? (View) rvStems.getParent() : null;
        if (stemsBox != null) {
            stemsBox.setAlpha(0f);
            stemsBox.setTranslationY(slidePx);
            stemsBox.setVisibility(View.VISIBLE);
            stemsBox.animate().cancel();
            stemsBox.animate().alpha(1f).translationY(0f)
                    .withLayer()
                    .setStartDelay(OUT_DUR - 80L)
                    .setDuration(IN_DUR)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }

        if (chordsHiddenForConfig) {
            // Apply the final chord layout now (visibility, elevation, padding)
            // and fade alpha in from 0 in sync with the stems slide-in.
            applyChordsOverlayForLyrics();
            chordsView.animate().cancel();
            chordsView.setAlpha(0f);
            chordsView.animate().alpha(1f)
                    .withLayer()
                    .setStartDelay(OUT_DUR - 80L)
                    .setDuration(IN_DUR)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    /** Opening-only: promote the chord row into overlay position (Z + padding
     *  + fade profile) without touching its alpha. Used when the destination
     *  state is CONTENT and the chord row was already visible — no need to
     *  refade in a row the user was already looking at. */
    private void activateChordOverlayInPlace(View chordsContainer) {
        if (chordsContainer == null || rvLyrics == null) return;
        float density = getResources().getDisplayMetrics().density;
        chordsContainer.animate().cancel();
        chordsContainer.setAlpha(1f);
        chordsContainer.setElevation(20f * density);
        chordsContainer.setBackground(null);
        chordsContainer.setVisibility(View.VISIBLE);
        android.view.ViewParent linearParent = chordsContainer.getParent();
        android.view.ViewParent centralFrame = linearParent != null
                ? linearParent.getParent() : null;
        if (centralFrame instanceof android.view.ViewGroup
                && linearParent instanceof View) {
            ((android.view.ViewGroup) centralFrame)
                    .bringChildToFront((View) linearParent);
        }
        int chordsH = (int) (70f * density);
        int solid = chordsH + (int) (8f * density);
        rvLyrics.setPadding(rvLyrics.getPaddingLeft(),
                solid, rvLyrics.getPaddingRight(),
                rvLyrics.getPaddingBottom());
        FadingEdgeRecyclerView ferv = (FadingEdgeRecyclerView) rvLyrics;
        ferv.setFadeLengthsPx(
                solid + (int) (90f * density),
                (int) (110f * density));
        ferv.setTopFadeSolidPx(solid);
    }

    /** Opening-only: fade the chord row out in parallel with the lyricsRoot
     *  fade in when the destination state is picker / skeleton / error.
     *  Leaves the chord in its natural (non-overlay) state — no elevation
     *  or z-order change, since it's going away. */
    private void fadeOutChordForLyricsOpen(View chordsContainer) {
        if (chordsContainer == null) return;
        chordsContainer.animate().cancel();
        chordsContainer.animate().alpha(0f).setDuration(240L)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    chordsContainer.setVisibility(View.GONE);
                    chordsContainer.setAlpha(1f);
                    chordsContainer.setElevation(0f);
                }).start();
    }

    /** Applies (or reverts) the chord-row overlay used while the lyrics view
     *  is showing content. Enables Z-lift + opaque background + top padding
     *  only when: lyrics view is visible, its state is CONTENT, chords are
     *  active. In every other case the chords_container is restored to its
     *  default look (no elevation, no background, natural visibility) and
     *  the recycler padding drops back to the base 24dp. */
    /** Animated variant of {@link #applyChordsOverlayForLyrics()} for the
     *  in-lyrics toggle. Fades the chord row and eases the recycler top
     *  padding + fade profile so switching chords on/off while the lyrics
     *  view is visible reads as a smooth transition rather than a snap. */
    private void animateChordsOverlayForLyrics(boolean turningOn) {
        View chordsContainer = findViewById(R.id.chords_container);
        if (chordsContainer == null || rvLyrics == null) {
            applyChordsOverlayForLyrics();
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        final int chordsH = (int) (70f * density);
        final int padOn   = chordsH + (int) (8f * density);
        final int padOff  = (int) (24f * density);
        final int fadeOnLen  = padOn + (int) (90f * density);
        final int fadeOffLen = (int) (48f * density);
        final int solidOn  = padOn;
        final FadingEdgeRecyclerView ferv = (FadingEdgeRecyclerView) rvLyrics;
        final int fromPad   = ferv.getPaddingTop();
        final int toPad     = turningOn ? padOn : padOff;
        final int toFadeLen = turningOn ? fadeOnLen : fadeOffLen;
        final int toSolid   = turningOn ? solidOn : 0;

        // Fade the chord tiles + set final z-order / background up front so
        // the animator only needs to drive the numeric transitions.
        if (turningOn) {
            chordsContainer.setElevation(20f * density);
            chordsContainer.setBackground(null);
            chordsContainer.setAlpha(0f);
            chordsContainer.setVisibility(View.VISIBLE);
            android.view.ViewParent linearParent = chordsContainer.getParent();
            android.view.ViewParent centralFrame = linearParent != null
                    ? linearParent.getParent() : null;
            if (centralFrame instanceof android.view.ViewGroup
                    && linearParent instanceof View) {
                ((android.view.ViewGroup) centralFrame)
                        .bringChildToFront((View) linearParent);
            }
            chordsContainer.animate().cancel();
            chordsContainer.animate().alpha(1f).setDuration(280L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            chordsContainer.animate().cancel();
            chordsContainer.animate().alpha(0f).setDuration(240L)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        chordsContainer.setVisibility(View.GONE);
                        chordsContainer.setElevation(0f);
                        chordsContainer.setAlpha(1f);
                        android.view.ViewParent grand = lyricsRoot != null
                                ? lyricsRoot.getParent() : null;
                        if (grand instanceof android.view.ViewGroup) {
                            ((android.view.ViewGroup) grand)
                                    .bringChildToFront(lyricsRoot);
                        }
                    }).start();
        }

        // Ease the padding + fade profile together over the same window as
        // the chord fade so text and tiles move in sync. Interpolating the
        // fade profile continuously (rather than snapping at the mid-point)
        // avoids the "text vanishes suddenly" threshold effect.
        final int fromFade  = ferv.getFadeTopPx();
        final int fromSolid = ferv.getTopFadeSolidPx();
        final int bottomFade = (int) (110f * density);
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(320L);
        anim.setInterpolator(turningOn
                ? new android.view.animation.DecelerateInterpolator()
                : new android.view.animation.AccelerateInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            int pad = (int) (fromPad + (toPad - fromPad) * t);
            int fadeLen = (int) (fromFade + (toFadeLen - fromFade) * t);
            int solid   = (int) (fromSolid + (toSolid - fromSolid) * t);
            ferv.setPadding(ferv.getPaddingLeft(), pad,
                    ferv.getPaddingRight(), ferv.getPaddingBottom());
            ferv.setFadeLengthsPx(fadeLen, bottomFade);
            ferv.setTopFadeSolidPx(solid);
        });
        anim.start();
    }

    private void applyChordsOverlayForLyrics() {
        if (mLyricsOpeningTransition) return;
        View chordsContainer = findViewById(R.id.chords_container);
        if (chordsContainer == null) return;
        float density = getResources().getDisplayMetrics().density;
        boolean overlayActive = isLyricsViewShown
                && mLyricsUiState == LyricsUiState.CONTENT
                && chordsActive;
        // The chords_container lives inside the LinearLayout that also holds
        // the stemsBox; the LinearLayout is a sibling of lyricsRoot inside
        // the central FrameLayout. elevation would not cross the LinearLayout
        // boundary, so we reorder z by bringing the LinearLayout in front of
        // lyricsRoot when the overlay is active, and back to normal when it
        // is not.
        android.view.ViewParent linearParent = chordsContainer.getParent();
        android.view.ViewParent centralFrame = linearParent != null
                ? linearParent.getParent() : null;
        if (overlayActive) {
            chordsContainer.setElevation(20f * density);
            // No background on the chord row: the individual tiles hide the
            // lyrics behind them, and the recycler fade below takes care of
            // dissolving the text before it reaches the tiles.
            chordsContainer.setBackground(null);
            chordsContainer.setVisibility(View.VISIBLE);
            if (centralFrame instanceof android.view.ViewGroup
                    && linearParent instanceof View) {
                ((android.view.ViewGroup) centralFrame)
                        .bringChildToFront((View) linearParent);
            }
            int chordsH = (int) (70f * density);
            if (rvLyrics != null) {
                rvLyrics.setPadding(rvLyrics.getPaddingLeft(),
                        chordsH + (int) (8f * density),
                        rvLyrics.getPaddingRight(),
                        rvLyrics.getPaddingBottom());
                // Two-part top fade: fully transparent in the chord band
                // (70dp + 8dp margin) so nothing bleeds through the gaps
                // between tiles, then a smooth transition from transparent
                // to opaque over the next ~90dp. Chord tiles stay opaque on
                // their own — no black backdrop needed.
                FadingEdgeRecyclerView ferv = (FadingEdgeRecyclerView) rvLyrics;
                int solid = (int) ((70f + 8f) * density);
                ferv.setFadeLengthsPx(
                        solid + (int) (90f * density),
                        (int) (110f * density));
                ferv.setTopFadeSolidPx(solid);
            }
        } else {
            chordsContainer.animate().cancel();
            chordsContainer.setAlpha(1f);
            chordsContainer.setElevation(0f);
            chordsContainer.setBackground(null);
            // While the lyrics view is up but not in CONTENT (picker /
            // skeleton / error) the chord row must stay hidden — those
            // screens are configuration, not playback, and letting the
            // chord row show would collide with the picker title.
            boolean hiddenForLyricsConfig = isLyricsViewShown
                    && mLyricsUiState != LyricsUiState.CONTENT;
            chordsContainer.setVisibility(
                    chordsActive && !hiddenForLyricsConfig
                            ? View.VISIBLE : View.GONE);
            if (centralFrame instanceof android.view.ViewGroup
                    && lyricsRoot != null) {
                // Restore the natural z-order: lyricsRoot sits on top so a
                // later re-open works as expected.
                ((android.view.ViewGroup) centralFrame)
                        .bringChildToFront(lyricsRoot);
            }
            if (rvLyrics != null) {
                int defaultTopPad = (int) (24f * density);
                rvLyrics.setPadding(rvLyrics.getPaddingLeft(),
                        defaultTopPad,
                        rvLyrics.getPaddingRight(),
                        rvLyrics.getPaddingBottom());
                // Short top fade so the first lyric line reads high on the
                // screen when no chord row is covering the top — the toolbar
                // already fades to transparent above, no need for extra
                // clearance. The chord-on branch above pushes the fade back
                // down when tiles reappear.
                FadingEdgeRecyclerView ferv = (FadingEdgeRecyclerView) rvLyrics;
                ferv.setTopFadeSolidPx(0);
                ferv.setFadeLengthsPx(
                        (int) (48f * density),
                        (int) (110f * density));
            }
        }
    }

    /** Applies the picker/skeleton/error/content grouping to the overlay. */
    private void setLyricsUiState(LyricsUiState s) {
        LyricsUiState previous = mLyricsUiState;
        mLyricsUiState = s;
        if (lyricsRoot == null) return;
        boolean picker  = s == LyricsUiState.PICKER;
        boolean loading = s == LyricsUiState.SKELETON;
        boolean err     = s == LyricsUiState.ERROR;
        boolean content = s == LyricsUiState.CONTENT;

        // Cross-fade all the visibility changes triggered below whenever the
        // state actually changes and the overlay is already visible. First
        // opening / restoring path skips this so nothing double-animates on
        // top of transitionIntoLyrics.
        boolean animate = previous != s && isLyricsViewShown
                && lyricsRoot.getVisibility() == View.VISIBLE;
        if (animate) {
            androidx.transition.Fade fade = new androidx.transition.Fade();
            fade.setDuration(220L);
            androidx.transition.TransitionManager.beginDelayedTransition(
                    (android.view.ViewGroup) lyricsRoot, fade);
        }

        // Picker widgets.
        int pickerVis = picker ? View.VISIBLE : View.GONE;
        findViewById(R.id.tv_lyricsPickerTitle).setVisibility(pickerVis);
        cardLyricsLanguage.setVisibility(pickerVis);
        bLyricsConfirm.setVisibility(pickerVis);
        bLyricsPickerCancel.setVisibility(picker && lyricsReprocessMode ? View.VISIBLE : View.GONE);

        // Skeleton / error share the same container; only the text changes.
        int skeletonVis = (loading || err) ? View.VISIBLE : View.GONE;
        llLyricsSkeleton.setVisibility(skeletonVis);
        if (loading) {
            tvLyricsStatus.setText(R.string.lyrics_processing);
            lyricsSkeletonView.setVisibility(View.VISIBLE);
        } else if (err) {
            tvLyricsStatus.setText(R.string.lyrics_error);
            lyricsSkeletonView.setVisibility(View.GONE);
        }
        bLyricsCancelJob.setVisibility(loading ? View.VISIBLE : View.GONE);

        // Content widgets.
        rvLyrics.setVisibility(content ? View.VISIBLE : View.GONE);
        bLyricsOptions.setVisibility(content ? View.VISIBLE : View.GONE);

        // Chord overlay depends on the state (only shown when CONTENT and
        // chords are active). Animate the chord fade in/out only when the
        // CONTENT edge is being crossed while the overlay is up; other
        // transitions (picker <-> skeleton, initial opens) go through the
        // instant path so nothing appears twice on screen.
        boolean chordEdgeCrossed = chordsActive && animate
                && (previous == LyricsUiState.CONTENT
                        || s == LyricsUiState.CONTENT);
        if (chordEdgeCrossed) {
            animateChordsOverlayForLyrics(s == LyricsUiState.CONTENT);
        } else {
            applyChordsOverlayForLyrics();
        }
    }

    private void applyLyricsManagerState(LyricsManager.State state, String errorMsg) {
        switch (state) {
            case ENQUEUED:
            case RUNNING:
            case DOWNLOADING:
                setLyricsUiState(LyricsUiState.SKELETON);
                break;
            case ERROR:
                if ("409".equals(errorMsg)) {
                    android.widget.Toast.makeText(this,
                            R.string.lyrics_no_vocals, android.widget.Toast.LENGTH_SHORT).show();
                    setLyricsUiState(LyricsUiState.PICKER);
                } else {
                    setLyricsUiState(LyricsUiState.ERROR);
                }
                break;
            case CANCELLED:
                setLyricsUiState(LyricsUiState.PICKER);
                break;
            case DONE:
                // The follow-up onLyricsLoaded promotes state to CONTENT.
                break;
            default:
                break;
        }
    }

    private void onLyricsConfirmClicked() {
        if (SongFolder == null || playbackService == null) return;
        String hash = SongFolder.getName();
        String lang = mSelectedLyricsLanguage != null ? mSelectedLyricsLanguage.code : null;
        // Reprocess mode wipes the server JSON first so the new POST is not
        // rejected with 409 for "already exists".
        if (lyricsReprocessMode) {
            mLyricsMgr.deleteRemote(hash);
            mLoadedLyrics = null;
            mLyricsLinesAdapter.setLyrics(null);
        }
        lyricsReprocessMode = false;
        mLyricsMgr.startJob(hash, SongFolder, lang);
    }

    private void onLyricsPickerCancelClicked() {
        // Only visible when reprocess is in progress and we still have the previous
        // lyrics loaded. Fall back to the picker if the earlier JSON is missing.
        lyricsReprocessMode = false;
        if (mLoadedLyrics != null) setLyricsUiState(LyricsUiState.CONTENT);
        else                       setLyricsUiState(LyricsUiState.PICKER);
    }

    private void onLyricsReprocessRequested() {
        lyricsReprocessMode = true;
        setLyricsUiState(LyricsUiState.PICKER);
    }

    private void showCancelJobDialog() {
        View v = getLayoutInflater().inflate(R.layout.delete_alert_dialog, null, false);
        ((android.widget.ImageView) v.findViewById(R.id.iv_alertDialog_ic))
                .setImageResource(R.drawable.ic_warning);
        ((android.widget.TextView) v.findViewById(R.id.tv_alertDialog_text))
                .setText(R.string.lyrics_cancel_dialog_title);
        com.google.android.material.button.MaterialButton bNo  = v.findViewById(R.id.b_no);
        com.google.android.material.button.MaterialButton bYes = v.findViewById(R.id.b_yes);
        bNo.setText(R.string.lyrics_dialog_no);
        bYes.setText(R.string.lyrics_dialog_yes);
        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(
                this, R.style.Theme_Fision_BlurDialog)
                .setView(v).create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());
        bNo.setOnClickListener(x -> dlg.dismiss());
        bYes.setOnClickListener(x -> {
            dlg.dismiss();
            mLyricsMgr.cancelJob();
        });
        showWithPlayerAccent(dlg);
    }

    private void openLanguagePickerDialog() {
        View v = getLayoutInflater().inflate(R.layout.lyrics_language_picker_dialog, null, false);
        androidx.recyclerview.widget.RecyclerView rv = v.findViewById(R.id.rv_langPicker);
        rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        String selected = mSelectedLyricsLanguage != null ? mSelectedLyricsLanguage.code : null;
        androidx.appcompat.app.AlertDialog dlg = new androidx.appcompat.app.AlertDialog.Builder(
                this, R.style.Theme_Fision_BlurDialog)
                .setView(v).create();
        LyricsLanguageAdapter adapter = new LyricsLanguageAdapter(selected, lang -> {
            mSelectedLyricsLanguage = lang;
            refreshLyricsCardFlag();
            dlg.dismiss();
        });
        rv.setAdapter(adapter);
        // Preselect the current one visually + scroll into view.
        int currentIdx = 0;
        for (int i = 0; i < LyricsLanguage.LANGUAGES.length; i++) {
            String c = LyricsLanguage.LANGUAGES[i].code;
            if ((c == null && selected == null) || (c != null && c.equals(selected))) {
                currentIdx = i; break;
            }
        }
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        applyActivityBlur();
        dlg.setOnDismissListener(d -> removeActivityBlur());
        showWithPlayerAccent(dlg);
        rv.scrollToPosition(currentIdx);
    }

    private void refreshLyricsCardFlag() {
        if (ivLyricsFlag == null || tvLyricsCardSubtitle == null) return;
        if (mSelectedLyricsLanguage == null) mSelectedLyricsLanguage = LyricsLanguage.LANGUAGES[0];
        ivLyricsFlag.setImageResource(mSelectedLyricsLanguage.flagRes);
        tvLyricsCardSubtitle.setText(mSelectedLyricsLanguage.labelRes);
        // Auto uses the square artcover asset — keep it inside with margin so it
        // doesn't stretch to the 3:2 flag container.
        if (mSelectedLyricsLanguage.isAuto()) {
            int pad = (int) (5 * getResources().getDisplayMetrics().density);
            ivLyricsFlag.setPadding(pad, pad, pad, pad);
            ivLyricsFlag.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        } else {
            ivLyricsFlag.setPadding(0, 0, 0, 0);
            ivLyricsFlag.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        }
    }

    /** Called from the playback timer to move the active line highlight and
     *  scroll it towards the vertical centre of the recycler. Cheap linear
     *  advance keyed off the previous index so the average update is O(1). */
    private void updateLyricsHighlight(int uiPosMs) {
        if (!isLyricsViewShown || mLyricsUiState != LyricsUiState.CONTENT) return;
        if (mLyricsLinesAdapter == null) return;
        java.util.List<LyricsLinesAdapter.Row> rows = mLyricsLinesAdapter.getRows();
        if (rows == null || rows.isEmpty()) return;
        double posSec = uiPosMs / 1000.0;
        int idx = mLyricsActiveIdx;
        // Rewind if seek went backwards.
        while (idx >= 0 && idx < rows.size() && rows.get(idx).start > posSec) idx--;
        // Advance while the next row has already started.
        while (idx + 1 < rows.size() && rows.get(idx + 1).start <= posSec) idx++;
        // Clamp to -1 while before the first row so nothing is highlighted.
        // With word-level timestamps the segment end coincides with the last
        // spoken word, so a strict "end < posSec" would leave the row blank
        // during the short pause before the next line enters. Keep TEXT rows
        // lit until the next row starts (the builder already inserts an
        // INSTRUMENTAL placeholder when the gap exceeds the threshold, so
        // this only affects short natural pauses between lines). The last row
        // falls back to its own end + 2s margin so it eventually clears.
        boolean deactivate = idx < 0;
        if (!deactivate) {
            LyricsLinesAdapter.Row r = rows.get(idx);
            if (r.type == 0 /*VT_TEXT*/) {
                double effectiveEnd = idx + 1 < rows.size()
                        ? rows.get(idx + 1).start
                        : r.end + 2.0;
                if (posSec >= effectiveEnd) deactivate = true;
            } else if (r.type == 1 /*VT_INSTRUMENTAL*/) {
                if (posSec >= r.end) deactivate = true;
            }
        }
        if (deactivate) {
            if (mLyricsActiveIdx != -1) {
                mLyricsActiveIdx = -1;
                mLyricsLinesAdapter.setActiveIndex(-1);
            }
            return;
        }
        // Publish position every tick so karaoke mode can advance word by
        // word inside the active row without waiting for a row change.
        if (mLyricsKaraokeMode) {
            double karaokePosSec = Math.max(0.0,
                    posSec - KARAOKE_HIGHLIGHT_OFFSET_MS / 1000.0);
            mLyricsLinesAdapter.setKaraokePosSec(karaokePosSec);
        }
        if (idx == mLyricsActiveIdx) return;
        mLyricsActiveIdx = idx;
        mLyricsLinesAdapter.setActiveIndex(idx);
        // Smooth-scroll so the new active row settles at the vertical center
        // of the recycler. The item animator is disabled, so the resize is
        // instantaneous while the scroll travel is what gives the motion.
        androidx.recyclerview.widget.LinearLayoutManager lm =
                (androidx.recyclerview.widget.LinearLayoutManager) rvLyrics.getLayoutManager();
        if (lm != null) {
            androidx.recyclerview.widget.LinearSmoothScroller scroller =
                    new androidx.recyclerview.widget.LinearSmoothScroller(this) {
                        @Override protected int getVerticalSnapPreference() {
                            return SNAP_TO_START;
                        }
                        @Override public int calculateDyToMakeVisible(android.view.View view,
                                                                      int snapPreference) {
                            androidx.recyclerview.widget.RecyclerView.LayoutManager mgr = getLayoutManager();
                            if (mgr == null) return 0;
                            int top = mgr.getDecoratedTop(view);
                            int bottom = mgr.getDecoratedBottom(view);
                            int viewCenter = (top + bottom) / 2;
                            int boxCenter = (mgr.getPaddingTop()
                                    + (mgr.getHeight() - mgr.getPaddingBottom())) / 2;
                            return boxCenter - viewCenter;
                        }
                        @Override protected float calculateSpeedPerPixel(
                                android.util.DisplayMetrics dm) {
                            // Much slower than the default 25f/densityDpi so
                            // the travel reads as a gentle glide instead of
                            // a snap. Higher numbers = slower motion.
                            return 140f / dm.densityDpi;
                        }
                    };
            scroller.setTargetPosition(idx);
            lm.startSmoothScroll(scroller);
        }
    }

    /** Handles a tap on any lyrics row: seeks the engine to the row's start
     *  and reapplies the metronome / chords / downbeat state accordingly.
     *  Mirrors the SeekBar path so playback state (playing vs paused) is
     *  preserved without duplicating the reset logic. */
    private void seekToLyricsRow(LyricsLinesAdapter.Row row) {
        if (row == null) return;
        seekToLyricsPosSec(row.start);
    }

    /** Shared seek entrypoint used by both row clicks and per-word clicks
     *  (karaoke mode). Mirrors the SeekBar path so playback state is preserved
     *  without duplicating the resume / pause logic. */
    private void seekToLyricsPosSec(double startSec) {
        if (engine == null) return;
        seekToMs((int) Math.max(0.0, startSec * 1000.0));
    }

    /** Persists the karaoke mode toggle and syncs it with the adapter so the
     *  active line rerenders immediately. If the current lyrics do not carry
     *  per-word timestamps the adapter silently falls back to the plain line
     *  rendering. */
    private void setLyricsKaraokeMode(boolean enabled) {
        mLyricsKaraokeMode = enabled;
        // Persist to the same SharedPreferences the value is read from at
        // onCreate (PreferenceManager default). Using a different backing
        // store on the write path left the flag reverting to false the
        // next time PlayerActivity opened.
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(PREF_LYRICS_KARAOKE_MODE, enabled).apply();
        if (mLyricsLinesAdapter != null) mLyricsLinesAdapter.setKaraokeMode(enabled);
    }

    private void releaseLyrics() {
        if (mLyricsMgr != null) mLyricsMgr.release();
        mLoadedLyrics = null;
        mLyricsActiveIdx = -1;
        if (mLyricsLinesAdapter != null) mLyricsLinesAdapter.setLyrics(null);
    }

    private void resetLyricsForNewSong() {
        // Song change wipes the loaded JSON reference; hasLocal() will re-probe
        // on the next open. Any polling job for the previous hash is cancelled
        // so its DONE callback does not overwrite the new state.
        releaseLyrics();
        if (mLyricsMgr != null) {
            mLyricsMgr.setListener(new LyricsManager.Listener() {
                @Override public void onLyricsStateChanged(LyricsManager.State state, String errorMsg) {
                    applyLyricsManagerState(state, errorMsg);
                }
                @Override public void onLyricsLoaded(Lyrics lyrics) {
                    mLoadedLyrics = lyrics;
                    if (mLyricsLinesAdapter != null) {
                        mLyricsLinesAdapter.setLyrics(lyrics);
                        mLyricsLinesAdapter.setKaraokeMode(mLyricsKaraokeMode);
                    }
                    mLyricsActiveIdx = -1;
                    setLyricsUiState(LyricsUiState.CONTENT);
                }
            });
        }
        mSelectedLyricsLanguage = LyricsLanguage.LANGUAGES[0];
        refreshLyricsCardFlag();
        // If the overlay is still open, refresh in place with the new song's
        // state — pick up a cached lyrics.json if it exists, otherwise land
        // on the picker.
        if (isLyricsViewShown && lyricsRoot != null) {
            refreshLyricsViewForCurrentSong();
        }
    }
}