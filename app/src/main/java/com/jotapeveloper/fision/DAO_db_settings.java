package com.jotapeveloper.fision;


import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface DAO_db_settings {

    @Insert
    void insert(db_settings settings);

    @Query("SELECT * FROM settings WHERE id = :id")
    db_settings getSettingsById(int id);

    @Update
    void update(db_settings settings);
    @Delete
    void delete(db_settings settings);

}
