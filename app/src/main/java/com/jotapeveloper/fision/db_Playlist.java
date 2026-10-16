package com.jotapeveloper.fision;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Offline copy of a server playlist (the server is the master). */
@Entity(tableName = "playlists")
public class db_Playlist {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id = "";

    @ColumnInfo(name = "title")
    public String title;

    /** Position in the server list (last change first). */
    @ColumnInfo(name = "listIndex")
    public int listIndex;

    @ColumnInfo(name = "hasCover")
    public boolean hasCover;

    @ColumnInfo(name = "createdAt")
    public String createdAt;

    @ColumnInfo(name = "updatedAt")
    public String updatedAt;

    /** Song hashes in play order, as a JSON array. */
    @ColumnInfo(name = "songsJson")
    public String songsJson;
}
