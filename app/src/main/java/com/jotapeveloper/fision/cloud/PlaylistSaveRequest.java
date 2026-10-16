package com.jotapeveloper.fision.cloud;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Body of POST /playlists and PUT /playlists/{id}: the whole playlist. */
public class PlaylistSaveRequest {
    @SerializedName("title") public final String title;
    @SerializedName("songs") public final List<String> songs;

    public PlaylistSaveRequest(String title, List<String> songs) {
        this.title = title;
        this.songs = songs;
    }
}
