package com.jotapeveloper.fision;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** Reads optional metadata (metadata.txt + artcover.png) from a song folder. */
public class SongMetadata {

    public String displayTitle;
    public String artist;
    public String coverPath;

    public static SongMetadata read(File songFolder) {
        SongMetadata m = new SongMetadata();
        if (songFolder == null || !songFolder.isDirectory()) return m;

        File info = new File(songFolder, "metadata.txt");
        if (info.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(info))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int sep = line.indexOf(':');
                    if (sep < 0) continue;
                    String key = line.substring(0, sep).trim().toLowerCase();
                    String val = line.substring(sep + 1).trim();
                    if ("title".equals(key))  m.displayTitle = val;
                    if ("artist".equals(key)) m.artist = val;
                }
            } catch (IOException ignored) {}
        }

        File cover = new File(songFolder, "artcover.png");
        if (cover.exists()) m.coverPath = cover.getAbsolutePath();

        return m;
    }
}
