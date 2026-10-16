package com.jotapeveloper.fision;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.jotapeveloper.fision.cloud.CloudItem;
import com.jotapeveloper.fision.cloud.CloudRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Single ordering of the song library, shared by the song list, the Player's
 * previous/next, the service skips and the Android Auto browser.
 * Date of a song: sticky order > server created_at > server updated_at >
 * submission time > local folder lastModified. Direction from the
 * "songs_sort_order" preference (0 = newest first).
 */
public final class SongOrder {

    public static final String PREF_SORT_ORDER = "songs_sort_order";

    private SongOrder() {}

    /** 0 = newest first, 1 = oldest first. */
    public static int sortOrder(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx).getInt(PREF_SORT_ORDER, 0);
    }

    /** Date used to sort a song: cloud timestamps first, then the last known
     *  server date (before the first server answer or offline), then the folder. */
    public static long timestamp(Context ctx, @Nullable CloudItem cloud, @Nullable File folder) {
        if (cloud != null) {
            if (cloud.stickyOrderTs > 0) return cloud.stickyOrderTs;
            long sc = parseIsoTs(cloud.serverCreated);
            if (sc > 0) return sc;
            long su = parseIsoTs(cloud.serverUpdated);
            if (su > 0) return su;
        }
        String hash = cloud != null ? cloud.hash : (folder != null ? folder.getName() : null);
        CloudRepository repo = CloudRepository.get(ctx);
        long cached = repo.getCachedOrderTs(hash);
        if (cached > 0) return cached;
        if (cloud != null && cloud.submittedAt > 0) return cloud.submittedAt;
        if (folder == null || !folder.exists()) return 0L;
        // First sight of a song with no server date: its folder date is frozen,
        // so later writes into the folder (lyrics, covers) never move it.
        return repo.freezeOrderTs(folder.getName(), folder.lastModified());
    }

    /** Compares two songs by date in the configured direction; equal dates
     *  fall back to the hash so the order never depends on listing order. */
    public static int compare(long a, @Nullable String keyA, long b, @Nullable String keyB,
                              int sortOrder) {
        int c = sortOrder == 0 ? Long.compare(b, a) : Long.compare(a, b);
        if (c != 0) return c;
        if (keyA == null) keyA = "";
        if (keyB == null) keyB = "";
        return keyA.compareTo(keyB);
    }

    /** Downloaded songs (folders under [songsRoot]) in list order. */
    public static List<File> sortedSongFolders(Context ctx, File songsRoot) {
        File[] dirs = songsRoot != null ? songsRoot.listFiles(File::isDirectory) : null;
        if (dirs == null) return new ArrayList<>();
        CloudRepository repo = CloudRepository.get(ctx);
        long[] ts = new long[dirs.length];
        Integer[] idx = new Integer[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            ts[i] = timestamp(ctx, repo.getByHash(dirs[i].getName()), dirs[i]);
            idx[i] = i;
        }
        int order = sortOrder(ctx);
        Arrays.sort(idx, (a, b) -> compare(ts[a], dirs[a].getName(), ts[b], dirs[b].getName(), order));
        List<File> out = new ArrayList<>(dirs.length);
        for (Integer i : idx) out.add(dirs[i]);
        return out;
    }

    /** Song [delta] steps from [current], wrapping around: in the active
     *  playlist's order (PlayQueue) or else in list order. Null when [current]
     *  is not in that order or it has a single song. */
    @Nullable
    public static File adjacent(Context ctx, File current, int delta) {
        if (current == null) return null;
        List<File> songs = PlayQueue.foldersFor(ctx, current);
        if (songs == null) songs = sortedSongFolders(ctx, current.getParentFile());
        if (songs.size() < 2) return null;
        int idx = -1;
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).getName().equals(current.getName())) { idx = i; break; }
        }
        if (idx < 0) return null;
        int n = songs.size();
        return songs.get(((idx + delta) % n + n) % n);
    }

    /** Parses a server ISO8601 date to millis. Accepts "2026-06-30T12:34:56",
     *  "2026-06-30 12:34:56", with or without fraction and timezone (read as
     *  UTC). Returns 0 on failure. */
    public static long parseIsoTs(@Nullable String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        String s = iso.replace('T', ' ');
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        int z = s.indexOf('+');
        if (z < 0) z = s.indexOf('Z');
        if (z > 0) s = s.substring(0, z).trim();
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = sdf.parse(s);
            return d != null ? d.getTime() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
