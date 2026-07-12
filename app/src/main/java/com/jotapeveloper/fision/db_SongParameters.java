package com.jotapeveloper.fision;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "songParameters")
public class db_SongParameters {
    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "Song name")
    public String songName;
    @ColumnInfo(name = "song ID")
    public int songId;

    @ColumnInfo(name = "Bass volume")
    public int bass;

    @ColumnInfo(name = "Vocals volume")
    public int vocals;

    @ColumnInfo(name = "Drums volume")
    public int drums;

    @ColumnInfo(name = "Other volume")
    public int other;

    @ColumnInfo(name = "Instrumental volume")
    public int instrumental;

    @ColumnInfo(name = "Chords view active")
    public boolean chordsActive;

    @ColumnInfo(name = "Metronome enabled")
    public boolean metronomeActive;

    @ColumnInfo(name = "Selected metronome")
    public int selectedMetronome;

    @ColumnInfo(name = "Metronome volume")
    public int metronomeVol;


    @ColumnInfo(name = "Watchdog active")
    public boolean wdActive;

    // === v2 ===
    @ColumnInfo(name = "Bass pan", defaultValue = "0")
    public float bassPan;

    @ColumnInfo(name = "Drums pan", defaultValue = "0")
    public float drumsPan;

    @ColumnInfo(name = "Other pan", defaultValue = "0")
    public float otherPan;

    @ColumnInfo(name = "Vocals pan", defaultValue = "0")
    public float vocalsPan;

    @ColumnInfo(name = "Metronome pan", defaultValue = "0")
    public float metronomePan;

    // Pitch NumberPicker index (CENTRAL_PITCH_INDEX = neutral)
    @ColumnInfo(name = "Pitch index", defaultValue = "0")
    public int pitchIndex;

    // Tempo NumberPicker index (CENTRAL_INDEX = neutral)
    @ColumnInfo(name = "Tempo index", defaultValue = "0")
    public int tempoIndex;

    // === v3: dynamic per-stem settings (JSON {stem:{v,p}}) ===
    @ColumnInfo(name = "Stem settings JSON", defaultValue = "{}")
    public String stemSettingsJson;

    // === v4: per-song chord mode (true=basic/chords_basic.txt, false=advanced/chords.txt) ===
    @ColumnInfo(name = "Chords basic mode", defaultValue = "0")
    public boolean chordsBasicMode;

    // === v5 ===
    // 0.5x metronome variant (0=A, 1=B): B shifts the phase by one beat
    @ColumnInfo(name = "Metronome half variant", defaultValue = "0")
    public int metronomeHalfVariant;

    // Per-song "Sound on downbeat" switch
    @ColumnInfo(name = "Downbeat sound enabled", defaultValue = "0")
    public boolean downbeatSoundEnabled;

    // Downbeat sound type (index; 0 = default)
    @ColumnInfo(name = "Downbeat sound type", defaultValue = "0")
    public int downbeatSoundType;

    // === v6: 3D audio (ITD/ILD) ===
    // When true, the 3D effect is active for this song and the normal vol/pan sliders
    // are ignored. Per-stem 3D positions live inside the stem settings JSON
    // (x/y fields per stem in stemSettingsJson).
    @ColumnInfo(name = "3D enabled", defaultValue = "0")
    public boolean audio3DEnabled;
}
