package com.jotapeveloper.fision;

import com.jotapeveloper.fision.cloud.CloudItem;

/**
 * Model of an item in the main song list.
 * The `title` field always matches the song folder name (key for Room and to
 * open the song). Optional metadata (displayTitle, artist, coverPath) is
 * populated by MainActivity from info.txt.
 *
 * For songs still processing or available only on the server, `cloud` carries
 * the corresponding state; in that case `title` may be the hash (future target
 * folder) or null when no local folder exists yet.
 */
public class SongListData {

    private String title;        // folder name (can be a hash)
    private String subtitle;     // secondary text (legacy: "4 stems")

    private String displayTitle; // optional: from info.txt or from the server
    private String artist;       // optional: from info.txt
    private String coverPath;    // optional: artcover.png

    /** Cloud state: null when the song is 100% local with no server counterpart. */
    public CloudItem cloud;

    /** Local mode placeholder of the demo song, not downloaded yet ({@link DemoSong}). */
    public boolean demo;

    public SongListData(String title, String subtitle) {
        this.title = title;
        this.subtitle = subtitle;
    }

    public String getTitle()    { return title; }
    public String getSubtitle() { return subtitle; }
    public void setTitle(String title)       { this.title = title; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }

    // Optional metadata
    public String getDisplayTitle()    { return displayTitle; }
    public String getArtist()          { return artist; }
    public String getCoverPath()       { return coverPath; }
    public void setDisplayTitle(String s) { this.displayTitle = s; }
    public void setArtist(String s)       { this.artist = s; }
    public void setCoverPath(String s)    { this.coverPath = s; }

    /** True when the song is downloaded locally (or is a legacy entry without server counterpart). */
    public boolean hasLocalFolder() {
        if (demo) return false;
        if (cloud == null) return true;            // legacy local-only
        return cloud.isLocal();
    }
}
