package com.jotapeveloper.fision;

import android.content.Context;

import androidx.annotation.Nullable;

import com.jotapeveloper.fision.cloud.PlaylistRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Play context of the current session: the library (default) or a playlist.
 * With a playlist, previous/next follow its order (read live, so edits apply
 * at once) over its downloaded songs. Process-wide; starting a song from the
 * library or Android Auto returns to the library order.
 */
public final class PlayQueue {

    @Nullable private static volatile String playlistId;

    private PlayQueue() {}

    public static void playFromPlaylist(String id) { playlistId = id; }

    public static void playFromLibrary() { playlistId = null; }

    /** Downloaded songs of the active playlist in its order, or null when the
     *  context is the library, the playlist is gone or [current] is not in it. */
    @Nullable
    static List<File> foldersFor(Context ctx, File current) {
        String id = playlistId;
        if (id == null || current == null) return null;
        PlaylistRepository.Playlist p = PlaylistRepository.get(ctx).getById(id);
        if (p == null || !p.songs.contains(current.getName())) return null;
        File root = current.getParentFile();
        List<File> out = new ArrayList<>(p.songs.size());
        for (String hash : p.songs) {
            File f = new File(root, hash);
            // Duplicates would make the position ambiguous
            if (f.isDirectory() && !out.contains(f)) out.add(f);
        }
        return out;
    }
}
