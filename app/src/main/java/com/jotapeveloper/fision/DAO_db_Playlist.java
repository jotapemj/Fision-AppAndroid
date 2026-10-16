package com.jotapeveloper.fision;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public abstract class DAO_db_Playlist {

    @Query("SELECT * FROM playlists ORDER BY listIndex ASC")
    public abstract List<db_Playlist> getAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertAll(List<db_Playlist> rows);

    @Query("DELETE FROM playlists")
    public abstract void deleteAll();

    @Query("DELETE FROM playlists WHERE id = :id")
    public abstract void deleteById(String id);

    /** Replaces the whole offline copy in one transaction. */
    @Transaction
    public void replaceAll(List<db_Playlist> rows) {
        deleteAll();
        upsertAll(rows);
    }
}
