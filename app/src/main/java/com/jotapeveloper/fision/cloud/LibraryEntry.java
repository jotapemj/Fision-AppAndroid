package com.jotapeveloper.fision.cloud;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

/** Entry from the "my cloud" catalog returned by GET /library. */
public class LibraryEntry {
    @SerializedName("hash")        public String hash;
    @SerializedName("filename")    public String filename;    // original upload filename
    @SerializedName("title")       public String title;       // readable title from meta.json
    @SerializedName("artist")      public String artist;      // artist from meta.json
    @SerializedName("cover_url")   public String coverUrl;    // relative path to the artcover PNG
    @SerializedName("created_at")  public String createdAt;   // ISO8601 original upload date
    @SerializedName("updated_at")  public String updatedAt;   // ISO8601 last modification
    @SerializedName("state")       public String state;       // ready | processing | error | cancelled
    @SerializedName("config")      public Map<String, Object> config;
}
