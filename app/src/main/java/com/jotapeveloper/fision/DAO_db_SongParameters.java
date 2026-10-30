package com.jotapeveloper.fision;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface DAO_db_SongParameters {

    @Insert
    void insert(db_SongParameters SongParameters);

    @Query("SELECT * FROM songParameters WHERE `song ID` = :elemento_id")
    List<db_SongParameters> getParametersByElementId(int elemento_id);

    @Query("SELECT * FROM songParameters")
    List<db_SongParameters> getAllParameters();

    @Query("SELECT `song ID` FROM songParameters WHERE `Song name` = :songName LIMIT 1")
    int getSongIdByName(String songName);

    @Query("UPDATE songParameters SET 'Bass volume' = :newBassVol WHERE `Song ID` = :songId")
    void updateBassVolume(int songId, int newBassVol);

    @Query("UPDATE songParameters SET 'Drums volume' = :newDrumsVol WHERE `Song ID` = :songId")
    void updateDrumsVolume(int songId, int newDrumsVol);

    @Query("UPDATE songParameters SET 'Vocals volume' = :newVocalsVol WHERE `Song ID` = :songId")
    void updateVocalsVolume(int songId, int newVocalsVol);

    @Query("UPDATE songParameters SET 'Other volume' = :newOtherVol WHERE `Song ID` = :songId")
    void updateOtherVolume(int songId, int newOtherVol);

    @Query("UPDATE songParameters SET 'Instrumental volume' = :newInstrumentalVol WHERE `Song ID` = :songId")
    void updateInstrumentalVolume(int songId, int newInstrumentalVol);

    @Query("UPDATE songParameters SET `Metronome enabled` = :newValue WHERE `Song ID` = :songId")
    void updateMetronomeEnabled(int songId, boolean newValue);

    @Query("UPDATE songParameters SET `Selected metronome` = :newSelectedMetronome WHERE `Song ID` = :songId")
    void updateSelectedMetronome(int songId, int newSelectedMetronome);

    @Query("UPDATE songParameters SET `Metronome volume` = :newMetronomeVol WHERE `Song ID` = :songId")
    void updateMetronomeVolume(int songId, int newMetronomeVol);

    @Query("UPDATE songParameters SET `Chords view active` = :newValue WHERE `Song ID` = :songId")
    void updateChordsEnabled(int songId, boolean newValue);

    // === v2: pan, pitch and tempo ===
    @Query("UPDATE songParameters SET `Bass pan` = :pan WHERE `Song ID` = :songId")
    void updateBassPan(int songId, float pan);

    @Query("UPDATE songParameters SET `Drums pan` = :pan WHERE `Song ID` = :songId")
    void updateDrumsPan(int songId, float pan);

    @Query("UPDATE songParameters SET `Other pan` = :pan WHERE `Song ID` = :songId")
    void updateOtherPan(int songId, float pan);

    @Query("UPDATE songParameters SET `Vocals pan` = :pan WHERE `Song ID` = :songId")
    void updateVocalsPan(int songId, float pan);

    @Query("UPDATE songParameters SET `Metronome pan` = :pan WHERE `Song ID` = :songId")
    void updateMetronomePan(int songId, float pan);

    @Query("UPDATE songParameters SET `Pitch index` = :idx WHERE `Song ID` = :songId")
    void updatePitchIndex(int songId, int idx);

    @Query("UPDATE songParameters SET `Tempo index` = :idx WHERE `Song ID` = :songId")
    void updateTempoIndex(int songId, int idx);

    // === v3 ===
    @Query("UPDATE songParameters SET `Stem settings JSON` = :json WHERE `Song ID` = :songId")
    void updateStemSettings(int songId, String json);

    // === v4 ===
    @Query("UPDATE songParameters SET `Chords basic mode` = :basic WHERE `Song ID` = :songId")
    void updateChordsBasicMode(int songId, boolean basic);

    @Query("UPDATE songParameters SET `Chords on lyrics` = :on WHERE `Song ID` = :songId")
    void updateChordsOnLyrics(int songId, boolean on);

    // === v5 ===
    @Query("UPDATE songParameters SET `Metronome half variant` = :variant WHERE `Song ID` = :songId")
    void updateMetronomeHalfVariant(int songId, int variant);

    @Query("UPDATE songParameters SET `Downbeat sound enabled` = :enabled WHERE `Song ID` = :songId")
    void updateDownbeatSoundEnabled(int songId, boolean enabled);

    @Query("UPDATE songParameters SET `Downbeat sound type` = :type WHERE `Song ID` = :songId")
    void updateDownbeatSoundType(int songId, int type);

    // === v6: per-song 3D audio on/off ===
    @Query("UPDATE songParameters SET `3D enabled` = :enabled WHERE `Song ID` = :songId")
    void updateAudio3DEnabled(int songId, boolean enabled);

    // === v7: tempo multiplier persisted as mult * 1000 (int) ===
    @Query("UPDATE songParameters SET `Tempo mult milli` = :milli WHERE `Song ID` = :songId")
    void updateTempoMultMilli(int songId, int milli);


    @Update
    void update(db_SongParameters SongParameters);

    @Delete
    void delete(db_SongParameters SongParameters);

    /** Deletes the song entry by folder name. */
    @Query("DELETE FROM songParameters WHERE `Song name` = :songName")
    void deleteBySongName(String songName);

}
