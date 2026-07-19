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

    private TextView tv_duration;
    private TextView tv_currentPos;
    private TextView tv_SongTitle;
    private TextView tv_bpm;

    private RecyclerView rvChords;
    private RecyclerView rvStems;

    // Controls

    private MaterialButton b_play;
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
    boolean relocating = false;
    boolean movingChords = false;
    boolean chordsActive = true;
    boolean paused = true;
    private volatile boolean isDestroyed = false;


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
    public int selectedPlaybackSpeed = numberPickerData.CENTRAL_INDEX;
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
            boolean playing = playbackService.isPlaying();
            paused = !playing;
            if (b_play != null) {
                b_play.setIconResource(playing ? R.raw.ic_pause : R.raw.ic_play);
            }
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

        // Load the song in the service when it is not the current one
        try {
            if (SongPath != null && !playbackService.isCurrentSong(SongPath)) {
                boolean ok = playbackService.loadSong(SongPath, SongName, SongId);
                if (!ok) {
                    Toast.makeText(this, "Error opening song. Missing files.", Toast.LENGTH_LONG).show();
                    // Release the service so no phantom notification is left with the
                    // startForegroundService promise dangling
                    playbackService.releaseSongAndStop();
                    finish();

                    
                    return;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


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
                b_play.setIconResource(R.raw.ic_play);
            }
        } else {
            // Already playing: sync UI with state
            paused = false;
            b_play.setIconResource(R.raw.ic_pause);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        readDataBase(SongId);
        setupTimers();
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.new_activity_player);
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

            // Query the database for this song's parameters
            List<db_SongParameters> parametersList = db.DAO_db_SongParameters().getParametersByElementId(songId);

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
                selectedPlaybackSpeed = (parameters.tempoIndex > 0)
                        ? parameters.tempoIndex : numberPickerData.CENTRAL_INDEX;

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
        float tempoFactor = Float.parseFloat(numberPickerData.VALUES[selectedPlaybackSpeed - 1]);
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
            b_pitch.setIconTint(ColorStateList.valueOf(Color.rgb(39, 179, 151)));
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
        if(chordsActive){
            b_chord.setIconTint(ColorStateList.valueOf(Color.rgb(39,179,151)));
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
            b_metronome.setIconTint(ColorStateList.valueOf(Color.rgb(39,179,151)));
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

        // When this song has 3D mode active, apply it to the engine, lock sliders
        // and show the ITD view without animation (open into the previous state).
        if (is3DEnabled) {
            apply3DEnabled(true);
            show3DViewNoAnim();
        } else {
            refreshB3dTint();
        }

        // Refresh progressbar + clock when entering the Activity (useful when arriving
        // from the mini-player with NoAutoplay: the normal timer does not update while
        // paused=true, so the seekbar stayed at 0 until play was pressed).
        if (engine != null && songDuration > 0) {
            int curMs = (int) engine.getCurrentPositionMs();
            int pct = (curMs * 100) / songDuration;
            if (pct < 0) pct = 0; if (pct > 100) pct = 100;
            sb_progressBar.setProgress(pct);
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
        if (noAutoplay) return;
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
        UpdateProgressBar = new Timer();
        UpdateProgressBar.scheduleAtFixedRate(new UpdateProgressBarTask(), 500,100);
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

        rvChords = (RecyclerView) findViewById(R.id.rv_chords);
        beatAdapter = new BeatCellAdapter(this, beatCells, beatsPerBar);
        beatAdapter.setLatinNotation(latinNotation);
        beatAdapter.setPreferFlat(preferFlat);
        beatAdapter.setSemitonesShift(currentDisplayShift());
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
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) movingChords = true;
                if (newState == RecyclerView.SCROLL_STATE_IDLE && movingChords) {
                    View snappedView = helper.findSnapView(recyclerView.getLayoutManager());
                    if (snappedView != null) {
                        int position = recyclerView.getLayoutManager().getPosition(snappedView);
                        if (position >= 0 && position < beatCells.size()) {
                            int newPos = (int) (beatCells.get(position).time * 1000);
                            manualMoveProgressBar(newPos);
                            currentBeatIdx = position;
                        }
                    }
                    movingChords = false;
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
        b_pitch = findViewById(R.id.b_pitch);
        b_back = findViewById(R.id.b_back);
        b3d = findViewById(R.id.b_3d);
        if (b3d != null) b3d.setOnClickListener(v -> toggle3DView());
        b_repeat = findViewById(R.id.b_repeat);
        if (b_repeat != null) b_repeat.setOnClickListener(v -> cycleRepeatMode());

        tv_duration = findViewById(R.id.tv_duration);
        tv_currentPos = findViewById(R.id.tv_currentPos);
        tv_SongTitle = findViewById(R.id.tv_SongTitle);
        tv_SongTitle.setSelected(true);
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
                    b_metronome.setIconTint(ColorStateList.valueOf(Color.rgb(39,179,151)));

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

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (engine == null) return;
                int newPosPercent = sb_progressBar.getProgress();
                int songDuration = (int) engine.getDurationMs();
                int newPosMillis = (newPosPercent * songDuration) / 100;

                engine.seekTo(newPosMillis);

                // If paused, refresh the UI manually (the Timer does not tick)
                if (paused) DefineNewProgress(newPosMillis);

                // Reposition chords
                relocateChords(newPosMillis, chordList);
                // Keep the half metronome phase after seek (engine.seekTo has not yet
                // updated the published position; the target is passed explicitly)
                applyMetronomeHalfPhase(newPosMillis);
                cancelPendingDownbeats();
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
        refreshNebulaPalette();

        SetControlsEnabled(false);
    }

    /** Binds the drawer switch that toggles the nebula background. Persists the
     *  choice as a global preference and fades the view in/out. */
    private void bindNebulaSwitch() {
        com.google.android.material.switchmaterial.SwitchMaterial sw =
                findViewById(R.id.sw_drawer_nebula);
        if (sw == null) return;
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(nebulaEnabled);
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            nebulaEnabled = isChecked;
            PreferenceManager.getDefaultSharedPreferences(PlayerActivity.this)
                    .edit().putBoolean(PREF_NEBULA_ENABLED, isChecked).apply();
            if (nebulaView != null) nebulaView.setNebulaEnabled(isChecked, true);
            if (isChecked) refreshNebulaPalette();
        });
    }

    /** Decodes the current artcover.png in the background and extracts a vibrant
     *  palette for the nebula. Falls back to a neutral blue/teal palette when
     *  the cover is missing or unreadable. */
    private void refreshNebulaPalette() {
        if (nebulaView == null || SongFolder == null) return;
        final File cover = new File(SongFolder, "artcover.png");
        final int primary = androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary);
        final int[] fallback = new int[] { 0xFF2D6BB0, 0xFF3AA3B3, primary };
        if (!cover.exists()) {
            nebulaView.setPalette(fallback);
            return;
        }
        new Thread(() -> {
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
                if (bm == null) {
                    runOnUiThread(() -> nebulaView.setPalette(fallback));
                    return;
                }
                androidx.palette.graphics.Palette p = androidx.palette.graphics.Palette
                        .from(bm).maximumColorCount(24).generate();
                int c1 = p.getVibrantColor(p.getMutedColor(fallback[0]));
                int c2 = p.getLightVibrantColor(p.getLightMutedColor(fallback[1]));
                int c3 = p.getDarkVibrantColor(p.getDarkMutedColor(fallback[2]));
                final int[] out = new int[] { c1, c2, c3 };
                runOnUiThread(() -> nebulaView.setPalette(out));
            } catch (Throwable t) {
                runOnUiThread(() -> nebulaView.setPalette(fallback));
            }
        }, "nebula-palette").start();
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

        int primary = androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary);
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
        dlg.show();
    }

    /** Blurs the activity decorView (Android 12+). Only affects the background. */
    private void applyActivityBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().getDecorView().setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                            40f, 40f, android.graphics.Shader.TileMode.CLAMP));
        }
    }

    private void removeActivityBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().getDecorView().setRenderEffect(null);
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
        wizardDialog.show();
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
        int primary = androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary);
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
        int tintRes;
        switch (mode) {
            case ALL:
                iconRes = R.drawable.ic_repeat;
                tintRes = R.color.colorPrimary;
                break;
            case ONE:
                iconRes = R.drawable.ic_repeat_one;
                tintRes = R.color.colorPrimary;
                break;
            case OFF:
            default:
                iconRes = R.drawable.ic_repeat;
                tintRes = R.color.white;
                break;
        }
        b_repeat.setIconResource(iconRes);
        b_repeat.setIconTint(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, tintRes)));
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

    /** Switches to a song within the current Activity: reloads files, recyclerviews,
     *  state, and triggers autoplay. The saved volumes are applied via
     *  readDataBase → updateUI (synchronous) → autoplayPending → performClick. */
    private void switchToSong(File newFolder) {
        if (playbackService == null || newFolder == null) return;

        // 1) Stop timer to avoid callbacks with mixed state
        if (UpdateProgressBar != null) { UpdateProgressBar.cancel(); UpdateProgressBar = null; }

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

        chordsBasicMode    = false;
        hasBasicChordsFile = new File(SongPath, "chords_basic.txt").exists();

        // 4) Re-read text files
        readChordsFile(SongPath);
        readBeatsFile(SongPath);
        filterGhostChords();

        // 5) Load into the service (releaseInternal frees the previous one)
        boolean ok = playbackService.loadSong(SongPath, SongName, SongId);
        if (!ok) {
            Toast.makeText(this, "Error opening song. Missing files.", Toast.LENGTH_LONG).show();
            return;
        }

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
        b_play.setIconResource(R.raw.ic_play);

        // 8) Pending autoplay: updateUI applies saved volumes before pressing play
        autoplayPending = true;

        // 9) Read DB async → updateUI triggers autoplay with the correct volumes
        readDataBase(SongId);
        setupTimers();

        // 10) Refresh visibility of the "Reprocess" item based on whether the new
        // song exists in CloudRepository (came from the server or is LOCAL_ONLY).
        refreshReprocessDrawerVisibility();

        // 11) Nebula palette follows the new artcover. Reset the handshake
        // so the previous song's colours fade out cleanly and the new palette
        // fades in only when it has actually been extracted.
        if (nebulaView != null) nebulaView.resetPaletteFade();
        refreshNebulaPalette();
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
        int active   = Color.rgb(39, 179, 151);
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
        if (beatAdapter != null) {
            beatAdapter.notifyDataSetChanged();
            // Reposition to the current beat
            if (engine != null) relocateChords((int) engine.getCurrentPositionMs(), chordList);
        }
    }

    @SuppressLint("ResourceType")
    public void Play(View view){
        if (playbackService == null) return;
        if (!paused){
            playbackService.pause();
            cancelPendingDownbeats();
            paused = true;
            b_play.setIconResource(R.raw.ic_play);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            playbackService.play();
            b_play.setIconResource(R.raw.ic_pause);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            paused = false;
        }
    }

    @SuppressLint("ResourceType")
    public void Stop(){
        StopMedia();
        b_play.setIconResource(R.raw.ic_play);
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

    private static final int NOTIFICATION_TICK_THRESHOLD = 10; // 10 * 100ms = ~1s
    private int notificationTickCounter = 0;

    private class UpdateProgressBarTask extends TimerTask {
        @Override
        public void run() {
            if (isDestroyed) return;
            if(!paused) {
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
            long latencyMs = engine.getOutputLatencyMs();
            int PerceivedPos = (int) Math.max(0L, CurrentPos - latencyMs);
            int PosPercentage = songDuration > 0 ? (PerceivedPos * 100) / songDuration : 0;
            sb_progressBar.setProgress(PosPercentage);

            // End of song: delegate to the service so repeat + playlist logic
            // stays in one place. The service returns what happened and the UI
            // updates accordingly (loop → reset counters only; stop → full fade).
            if (songDuration > 0 && CurrentPos >= songDuration - 250) {
                PlaybackService.EndOfSongResult result =
                        playbackService != null
                                ? playbackService.handleSongEnded()
                                : PlaybackService.EndOfSongResult.STOPPED;
                if (result == PlaybackService.EndOfSongResult.STOPPED) {
                    cancelPendingDownbeats();
                    applyMetronomeHalfPhase(0);
                    paused = true;
                    runOnUiThread(() -> {
                        b_play.setIconResource(R.raw.ic_play);
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

            // Notification: the service refreshes it ~1x per second
            notificationTickCounter++;
            if (notificationTickCounter >= NOTIFICATION_TICK_THRESHOLD) {
                notificationTickCounter = 0;
                if (playbackService != null) playbackService.refreshNotificationProgress();
            }



            final int UiPos = PerceivedPos;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {

                    String tvNewCurrentPos = getTime(UiPos);
                    tv_currentPos.setText(tvNewCurrentPos);

                    // Current beat: find the last beat whose time <= UiPos
                    if (!relocating && !movingChords && !beatCells.isEmpty()) {
                        float tSec = UiPos / 1000f;
                        int idx = currentBeatIdx;
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
                            if (nebulaView != null && nebulaEnabled) {
                                int pos = beatCells.get(idx).positionInBar;
                                float intensity;
                                if (beatsPerBar == 3) {
                                    intensity = pos == 1 ? 0.95f : 0.30f;
                                } else {
                                    intensity = (pos % 2 == 0) ? 0.90f : 0.35f;
                                }
                                nebulaView.pulse(intensity);
                            }
                            final int targetIdx = idx;
                            rvChords.smoothScrollToPosition(targetIdx);
                            // Delay the highlight so the tile lights up once it has already
                            // scrolled to the center. Cancel the previous one if still alive.
                            if (pendingTileHighlight != null) {
                                rvChords.removeCallbacks(pendingTileHighlight);
                            }
                            pendingTileHighlight = () -> {
                                if (beatAdapter != null) beatAdapter.setHighlight(targetIdx);
                            };
                            rvChords.postDelayed(pendingTileHighlight, 80);

                            // Refresh the local BPM readout on every beat
                            // transition, so seek and coming back into the
                            // activity always show an up-to-date value.
                            updateLocalBpmDisplay(idx);
                        }
                    }

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

        // "Artist - Title" caption if metadata is available, fallback to folder name
        SongMetadata meta = playbackService != null ? playbackService.getCurrentMetadata() : null;
        String shown;
        if (meta != null && meta.displayTitle != null) {
            shown = (meta.artist != null && !meta.artist.isEmpty())
                    ? (meta.artist + " - " + meta.displayTitle)
                    : meta.displayTitle;
        } else {
            shown = SongName != null ? SongName : "";
        }
        FolderName = shown;
        tv_SongTitle.setText(FolderName);
    }

    public void ShowChords(View view){
        // In ITD view the button should not react: the chords animation does not fit
        // with the 3D overlay and leaves artifacts. Only the flag is persisted.
        if (is3DViewShown) {
            chordsActive = !chordsActive;
            b_chord.setIconTint(ColorStateList.valueOf(chordsActive
                    ? Color.rgb(39,179,151) : Color.rgb(255,255,255)));
            updateDB("chords", chordsActive ? 1 : 0);
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
        androidx.transition.TransitionManager.beginDelayedTransition(parent, set);

        if (chordsActive){
            chordsContainer.setVisibility(View.GONE);
            b_chord.setIconTint(ColorStateList.valueOf(Color.rgb(255,255,255)));
            chordsActive = false;
            updateDB("chords",0);
        } else {
            chordsContainer.setVisibility(View.VISIBLE);
            b_chord.setIconTint(ColorStateList.valueOf(Color.rgb(39,179,151)));
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

        engine.seekTo(newPos);

        // Reposition the chords recycler so it follows the seek (previously it was
        // only repositioned while dragging the SeekBar; the 5s buttons left it offset)
        relocateChords((int) newPos, chordList);
        applyMetronomeHalfPhase(newPos);
        cancelPendingDownbeats();

        // Resume if it was playing, otherwise just update UI
        if (!paused) {
            engine.resume();
        } else {
            DefineNewProgress((int)newPos); // <-- used to refresh UI (bar, time)
        }
    }

    @Override
    public void onBackPressed(){
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

        if (UpdateProgressBar != null) {
            UpdateProgressBar.cancel();
            UpdateProgressBar.purge();
            UpdateProgressBar = null;
        }
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

            int ThumbColor = ContextCompat.getColor(getApplicationContext(), R.color.sliderThumb);
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
        int active = Color.parseColor("#27B397");
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
        NumberPicker np_metronome_speed = metronomeBottomSheet.findViewById(R.id.np_metronome_speed);
        TextView tv_metronome_volume = metronomeBottomSheet.findViewById(R.id.tv_metronome_volume);
        TextView tv_metronome_panning = metronomeBottomSheet.findViewById(R.id.tv_metronome_panning);

        String[] numberPickerData = com.jotapeveloper.fision.numberPickerData.VALUES;
        np_metronome_speed.setMinValue(1);
        np_metronome_speed.setMaxValue(numberPickerData.length);
        np_metronome_speed.setDisplayedValues(numberPickerData);
        np_metronome_speed.setValue(selectedPlaybackSpeed);

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
                    b_metronome.setIconTint(ColorStateList.valueOf(Color.rgb(39,179,151)));
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

        np_metronome_speed.setOnScrollListener(new NumberPicker.OnScrollListener() {
            @Override
            public void onScrollStateChange(NumberPicker picker, int scrollState) {
                if (scrollState == SCROLL_STATE_IDLE) {
                    float factor = Float.parseFloat(numberPickerData[picker.getValue() - 1]);
                    selectedPlaybackSpeed = picker.getValue();
                    updateDB("tempoIndex", selectedPlaybackSpeed);

                    // Tempo applied to ALL stems + metronomes
                    for (String s : playbackService.getStemOrder()) {
                        Integer t = playbackService.getStemTrack(s);
                        if (t != null) engine.setTempo(t, factor);
                    }
                    if (metronomeTrack >= 0)       engine.setTempo(metronomeTrack, factor);
                    if (metronomeDoubleTrack >= 0) engine.setTempo(metronomeDoubleTrack, factor);
                    if (metronomeHalfTrack >= 0)   engine.setTempo(metronomeHalfTrack, factor);

                }
            }
        });

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

        metronomeBottomSheet.show();

    }


    public void showPitchBottomDialog() {
        BottomSheetDialog pitchBottomSheet = new BottomSheetDialog(this, R.style.Theme_Fision_TransparentBottomSheetDialog);
        View bottomSheetView = getLayoutInflater().inflate(R.layout.pitch_modal_sheet_dialog, null);

        pitchBottomSheet.setContentView(bottomSheetView);
        NumberPicker np_pitch = bottomSheetView.findViewById(R.id.np_pitch);
        TextView tv_pitch_semitones = bottomSheetView.findViewById(R.id.tv_pitch_semitones);

        // Display values: -12..+12
        String[] pitchValues = com.jotapeveloper.fision.numberPickerData.PITCH_VALUES;
        np_pitch.setMinValue(1);
        np_pitch.setMaxValue(pitchValues.length);
        np_pitch.setDisplayedValues(pitchValues);
        np_pitch.setValue(selectedPitch);

        // Update tint if pitch != 0
        if (selectedPitch != com.jotapeveloper.fision.numberPickerData.CENTRAL_PITCH_INDEX) {
            b_pitch.setIconTint(ColorStateList.valueOf(Color.rgb(39,179,151)));
            tv_pitch_semitones.setTextColor(Color.rgb(39,179,151));
        } else {
            b_pitch.setIconTint(ColorStateList.valueOf(Color.rgb(255,255,255)));
            tv_pitch_semitones.setTextColor(Color.rgb(255,255,255));
        }

        np_pitch.setOnScrollListener(new NumberPicker.OnScrollListener() {
            @Override
            public void onScrollStateChange(NumberPicker picker, int scrollState) {
                if (scrollState == SCROLL_STATE_IDLE) {
                    selectedPitch = picker.getValue();
                    updateDB("pitchIndex", selectedPitch);

                    // Convert index -> semitone float (array is 1-based in the picker)
                    float semitones = Float.parseFloat(pitchValues[selectedPitch - 1]);

                    // Pitch applied to pitched stems (percussion excluded by design)
                    for (String s : playbackService.getStemOrder()) {
                        if (!StemSpec.isPitched(s)) continue;
                        Integer t = playbackService.getStemTrack(s);
                        if (t != null) engine.setPitch(t, semitones);
                    }

                    // Update UI tint
                    if (selectedPitch != com.jotapeveloper.fision.numberPickerData.CENTRAL_PITCH_INDEX) {
                        b_pitch.setIconTint(ColorStateList.valueOf(Color.rgb(39,179,151)));
                        tv_pitch_semitones.setTextColor(Color.rgb(39,179,151));
                    } else {
                        b_pitch.setIconTint(ColorStateList.valueOf(Color.rgb(255,255,255)));
                        tv_pitch_semitones.setTextColor(Color.rgb(255,255,255));
                    }

                    // If the drawer switch is on, repaint chord names
                    applyDisplayShiftToAdapter();
                }
            }
        });

        pitchBottomSheet.show();
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
        optionsBottomSheet.show();
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
        if (rvChords != null) rvChords.smoothScrollToPosition(show);
        currentBeatIdx = idx;
        // Cancel the timer's pending callback so it does not overwrite the seek highlight
        if (pendingTileHighlight != null && rvChords != null) {
            rvChords.removeCallbacks(pendingTileHighlight);
        }
        if (beatAdapter != null) beatAdapter.setHighlight(idx);
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

    protected void onStop() {
        super.onStop();
        // The engine keeps its independent click queue; no need to clear it when
        // go to background — the click keeps sounding in sync with the song.
    }

    protected void onRestart() {
        super.onRestart();
    }

    @Override
    protected void onDestroy() {
        DestroyPlayers();
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

    /** Serializa stemSettings y persiste el JSON en Room (background). */
    private void persistStemSettings() {
        final String json = StemSettingsCodec.encode(stemSettings);
        final int songId = SongId;
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        Executors.newSingleThreadExecutor().execute(() ->
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
            tvSolo.setTextColor(androidx.core.content.ContextCompat.getColor(
                    this, R.color.colorPrimary));
        }
        // Same for Mute: if the stem is muted, highlight Mute in green
        StemSetting ss = StemSettingsCodec.getOrDefault(stemSettings, stem);
        if (ss.volume == 0) {
            tvMute.setTextColor(androidx.core.content.ContextCompat.getColor(
                    this, R.color.colorPrimary));
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
    /** Black overlay over stems+head: "lights off" effect when 3D=off. */
    private View v3DDarkOverlay;
    /** Stems dance button (play → stop while dancing). */
    private com.google.android.material.button.MaterialButton b3DDance;
    /** 3D reset button: hidden until any position/elev/mute/omni is modified. */
    private com.google.android.material.button.MaterialButton b3DReset;
    /** Container of the inline dance sliders (visible only while dancing). */
    private View danceInlineSlidersContainer;
    private com.google.android.material.slider.Slider danceInlineSlSpeed;
    private com.google.android.material.slider.Slider danceInlineSlDistance;

    // === Dance mode (stem orbit) ===
    private boolean       danceActive = false;
    private final java.util.Set<String> danceStems = new java.util.HashSet<>();
    private float         danceSpeed    = 0.15f;  // laps/second per stem (max 0.4 for finer resolution)
    private float         danceDistance = 1.0f;   // radius multiplier [0.4..1.2]
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
                sw3DEnabled.setOnCheckedChangeListener((btn, checked) -> apply3DEnabled(checked));
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
                if (danceActive) stopDance();
                else showStemsDanceDialog();
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
            danceInlineSlidersContainer = danceSlidersContainer;
            danceInlineSlSpeed = slSpeedInline;
            danceInlineSlDistance = slDistInline;
        }
    }

    /** Updates the tint of the `b_3d` icon: green if the effect is active. */
    private void refreshB3dTint() {
        if (b3d == null) return;
        int tint = is3DEnabled
                ? androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary)
                : Color.rgb(255, 255, 255);
        b3d.setIconTint(ColorStateList.valueOf(tint));
    }

    /** Toggles between the normal view (stems + chords) and the 3D view with crossfade. */
    private void toggle3DView() {
        ensure3DRefs();
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
        view3DRoot.setAlpha(1f);
        view3DRoot.setScaleX(1f);
        view3DRoot.setScaleY(1f);
        view3DRoot.setVisibility(View.VISIBLE);
        build3DIcons();
        // Icons appear faded after the layout pass (they start with alpha=0 in build).
        fl3DStemContainer.postDelayed(this::animateStemIconsIn, 100L);
        // Evaluate whether previous modifications exist → show reset button with fade in
        fl3DStemContainer.postDelayed(() -> { if (has3DModifications()) showResetButton(); }, 400L);
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

        // 3D mode entry: overlaps the end of the fade out to avoid a black gap.
        view3DRoot.setAlpha(0f);
        view3DRoot.setScaleX(0.94f);
        view3DRoot.setScaleY(0.94f);
        view3DRoot.setVisibility(View.VISIBLE);
        view3DRoot.animate().cancel();
        view3DRoot.animate().alpha(1f).scaleX(1f).scaleY(1f)
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

    /** Reversa hacia el modo stems. Respeta chordsActive: si estaba off, no muestra
     *  the chords_container when returning — it used to force VISIBLE and reappear wrongly. */
    private void transitionOutOf3D(View chordsContainer) {
        final long OUT_DUR = 200L;
        final long IN_DUR  = 260L;
        float density = getResources().getDisplayMetrics().density;
        float slidePx = 18f * density;

        view3DRoot.animate().cancel();
        view3DRoot.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                .withLayer()
                .setDuration(OUT_DUR)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> view3DRoot.setVisibility(View.GONE)).start();

        // Restore the stems stack as a block with fade + slide from below.
        View stemsStack = rvStems != null ? (View) rvStems.getParent().getParent() : null;
        if (stemsStack != null) {
            stemsStack.setAlpha(0f);
            stemsStack.setTranslationY(slidePx);
            stemsStack.setVisibility(View.VISIBLE);
            stemsStack.animate().cancel();
            stemsStack.animate().alpha(1f).translationY(0f)
                    .withLayer()
                    .setStartDelay(OUT_DUR - 80L)
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
        // Normal sliders visually disabled when 3D is on (F5)
        if (stemsAdapter != null) {
            stemsAdapter.setEditingLocked(enabled);
            stemsAdapter.notifyDataSetChanged();
        }
        if (playbackService == null || engine == null) return;
        for (String stem : playbackService.getStemOrder()) {
            Integer trackId = playbackService.getStemTrack(stem);
            if (trackId == null) continue;
            engine.setTrack3DEnabled(trackId, enabled);
            if (enabled) {
                float[] pos = stem3DPositions.get(stem);
                if (pos != null) apply3DPositionToEngine(stem, pos[0], pos[1]);
            } else {
                // On disable, re-apply the normal volume/pan (saved in Room)
                applyStemVolumeToEngine(stem);
            }
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
            if (s.volume == 0) return true;
            if (Math.abs(s.elevationDeg) > 0.5f) return true;
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
            ss.pos3DX = nxT;
            ss.pos3DY = nyT;
            applyOmnipresentTint(iv, false);

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

    // ================================================================
    // === STEMS DANCE (animated orbit) ============================
    // ================================================================
    /** Setup dialog: pick stems + speed + distance. */
    private void showStemsDanceDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dlgView = inflater.inflate(R.layout.stems_dance_setup_dialog, null);
        android.widget.LinearLayout gl = dlgView.findViewById(R.id.gl_dance_stems);
        com.google.android.material.slider.Slider slSpeed = dlgView.findViewById(R.id.sl_dance_speed);
        com.google.android.material.slider.Slider slDist  = dlgView.findViewById(R.id.sl_dance_distance);
        TextView tvSpeed = dlgView.findViewById(R.id.tv_dance_speed_value);
        TextView tvDist  = dlgView.findViewById(R.id.tv_dance_distance_value);
        com.google.android.material.button.MaterialButton bCancel = dlgView.findViewById(R.id.b_dance_cancel);
        com.google.android.material.button.MaterialButton bStart  = dlgView.findViewById(R.id.b_dance_start);

        slSpeed.setValue(danceSpeed);
        slDist.setValue(danceDistance);
        tvSpeed.setText(String.format(java.util.Locale.US, "%.2f×", danceSpeed));
        tvDist.setText(String.format(java.util.Locale.US, "%.2f", danceDistance));
        slSpeed.addOnChangeListener((s, v, fromUser) -> tvSpeed.setText(String.format(java.util.Locale.US, "%.2f×", v)));
        slDist.addOnChangeListener((s, v, fromUser) -> tvDist.setText(String.format(java.util.Locale.US, "%.2f", v)));

        // Selection: toggleable icons. Starts with whatever is in danceStems (or all if empty)
        java.util.List<String> order = playbackService.getStemOrder();
        boolean hasV2 = playbackService.hasStem("vocals2");
        final java.util.Set<String> selection = new java.util.HashSet<>(danceStems);
        if (selection.isEmpty()) selection.addAll(order);
        float density = getResources().getDisplayMetrics().density;
        int iconPx = (int) (46f * density);
        int gap    = (int) (8f * density);
        android.widget.LinearLayout row = null;
        int perRow = 5;
        for (int i = 0; i < order.size(); i++) {
            final String stem = order.get(i);
            if (i % perRow == 0) {
                row = new android.widget.LinearLayout(this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = gap;
                row.setLayoutParams(rlp);
                gl.addView(row);
            }
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(StemSpec.iconResource(stem, hasV2));
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundResource(R.drawable.dance_chip_bg);
            int padPx = (int) (6f * density);
            iv.setPadding(padPx, padPx, padPx, padPx);
            iv.setSelected(selection.contains(stem));
            iv.setAlpha(iv.isSelected() ? 1f : 0.65f);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(iconPx, iconPx);
            lp.leftMargin = gap / 2; lp.rightMargin = gap / 2;
            iv.setLayoutParams(lp);
            iv.setOnClickListener(v -> {
                boolean sel = !iv.isSelected();
                iv.setSelected(sel);
                iv.setAlpha(sel ? 1f : 0.65f);
                if (sel) selection.add(stem); else selection.remove(stem);
            });
            row.addView(iv);
        }

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
            if (selection.isEmpty()) { dlg.dismiss(); return; }
            danceStems.clear();
            danceStems.addAll(selection);
            danceSpeed    = slSpeed.getValue();
            danceDistance = slDist.getValue();
            dlg.dismiss();
            startDance();
        });
        dlg.show();
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
        dlg.show();
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

    /** Applies (or clears) the persistent green tint of omnipresent mode on the icon. */
    private void applyOmnipresentTint(android.widget.ImageView iv, boolean on) {
        if (on) {
            iv.setColorFilter(androidx.core.content.ContextCompat.getColor(this,
                    R.color.colorPrimary), android.graphics.PorterDuff.Mode.MULTIPLY);
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
        if (v3DDarkOverlay == null) return;
        v3DDarkOverlay.animate().cancel();
        float target = lightsOn ? 0f : 0.72f;
        long dur = lightsOn ? 220L : 240L;
        v3DDarkOverlay.animate()
                .alpha(target)
                .setDuration(dur)
                .setInterpolator(lightsOn
                        ? new android.view.animation.DecelerateInterpolator()
                        : new android.view.animation.AccelerateInterpolator())
                .withLayer()
                .start();
    }

    /** Toggles mute of the stem in 3D mode. Muted = vol=0 → the track plays silent.
     *  Stores the previous volume so it can be restored with another long press. */
    private void toggle3DStemMute(String stem, View icon) {
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        boolean nowMuted;
        if (s.volume > 0) {
            stemLastVol.put(stem, s.volume);
            s.volume = 0;
            nowMuted = true;
        } else {
            Integer prev = stemLastVol.get(stem);
            s.volume = (prev != null && prev > 0) ? prev : 75;
            nowMuted = false;
        }
        persistStemSettings();
        // Apply to the engine: use setVolume directly (3D mode respects 0 as silence).
        Integer trackId = playbackService != null ? playbackService.getStemTrack(stem) : null;
        if (trackId != null && engine != null) {
            float g = s.volume * 0.01f;
            engine.setVolume(trackId, g, g);
        }
        icon.setAlpha(nowMuted ? StemListAdapter.ALPHA_DISABLED : StemListAdapter.ALPHA_ACTIVE);
        showResetButton();
    }

    /** True if the stem volume is 0 (muted). */
    private boolean is3DStemMuted(String stem) {
        StemSetting s = StemSettingsCodec.getOrDefault(stemSettings, stem);
        return s.volume == 0;
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
        icon.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary));
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
        dlg.show();
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

}