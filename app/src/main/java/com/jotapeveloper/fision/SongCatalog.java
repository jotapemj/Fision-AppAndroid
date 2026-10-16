package com.jotapeveloper.fision;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jotapeveloper.fision.cloud.CloudItem;
import com.jotapeveloper.fision.cloud.CloudRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Display data of a song by hash, from its local folder or, when it is not
 *  downloaded, from the cloud catalog. Shared by the playlist screens. */
public final class SongCatalog {

    private SongCatalog() {}

    public static final class Song {
        @NonNull public final String hash;
        @NonNull public final String title;
        @Nullable public final String artist;
        @Nullable public final String coverPath;
        /** Downloaded: its folder exists under Songs/. */
        public final boolean local;
        @Nullable public final CloudItem cloud;

        Song(@NonNull String hash, @NonNull String title, @Nullable String artist,
             @Nullable String coverPath, boolean local, @Nullable CloudItem cloud) {
            this.hash = hash;
            this.title = title;
            this.artist = artist;
            this.coverPath = coverPath;
            this.local = local;
            this.cloud = cloud;
        }
    }

    public static File songsRoot(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), "Songs");
    }

    @NonNull
    public static Song resolve(@NonNull Context ctx, @NonNull String hash) {
        File folder = new File(songsRoot(ctx), hash);
        boolean local = folder.isDirectory();
        CloudItem ci = CloudRepository.get(ctx).getByHash(hash);
        String title = null, artist = null, cover = null;
        if (local) {
            SongMetadata meta = SongMetadata.read(folder);
            title = meta.displayTitle;
            artist = meta.artist;
            cover = meta.coverPath;
        }
        if (ci != null) {
            if ((title == null || title.isEmpty() || title.equals(hash))
                    && ci.displayName != null && !ci.displayName.isEmpty()) {
                title = ci.displayName;
            }
            if ((artist == null || artist.isEmpty()) && ci.artist != null && !ci.artist.isEmpty()) {
                artist = ci.artist;
            }
            if ((cover == null || !new File(cover).exists())
                    && ci.coverPath != null && new File(ci.coverPath).exists()) {
                cover = ci.coverPath;
            }
        }
        if (cover != null && !new File(cover).exists()) cover = null;
        if (title == null || title.isEmpty()) title = hash;
        return new Song(hash, title, artist, cover, local, ci);
    }

    /** True when the server holds the finished song, so it can go in a playlist. */
    public static boolean isInCloud(@Nullable CloudItem ci) {
        if (ci == null) return false;
        return ci.state == CloudItem.State.LOCAL_AND_CLOUD
                || ci.state == CloudItem.State.CLOUD_ONLY
                || ci.state == CloudItem.State.DOWNLOADING;
    }

    /** Songs that can be added to a playlist, in library order. */
    @NonNull
    public static List<Song> cloudSongs(@NonNull Context ctx) {
        List<CloudItem> snap = CloudRepository.get(ctx).snapshot();
        File root = songsRoot(ctx);
        List<CloudItem> items = new ArrayList<>();
        for (CloudItem ci : snap) if (isInCloud(ci)) items.add(ci);
        int order = SongOrder.sortOrder(ctx);
        long[] ts = new long[items.size()];
        Integer[] idx = new Integer[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ts[i] = SongOrder.timestamp(ctx, items.get(i), new File(root, items.get(i).hash));
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> SongOrder.compare(ts[a], items.get(a).hash,
                ts[b], items.get(b).hash, order));
        List<Song> out = new ArrayList<>(items.size());
        for (Integer i : idx) out.add(resolve(ctx, items.get(i).hash));
        return Collections.unmodifiableList(out);
    }
}
