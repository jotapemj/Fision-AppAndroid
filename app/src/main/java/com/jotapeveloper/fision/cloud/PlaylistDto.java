package com.jotapeveloper.fision.cloud;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Playlist as the server sends it. GET /playlists leaves {@link #songs}
 *  null; GET /playlists/{id}, POST and PUT include it in play order. */
public class PlaylistDto {
    @SerializedName("id")         public String id;
    @SerializedName("title")      public String title;
    @SerializedName("count")      public int count;
    @SerializedName("has_cover")  public boolean hasCover;
    @SerializedName("created_at") public String createdAt;
    @SerializedName("updated_at") public String updatedAt;
    @SerializedName("songs")      public List<String> songs;
}
