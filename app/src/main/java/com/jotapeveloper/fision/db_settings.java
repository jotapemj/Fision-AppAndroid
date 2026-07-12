package com.jotapeveloper.fision;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "settings")
public class db_settings {
    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "language")
    public String language = "en";
}
