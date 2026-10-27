package com.jotapeveloper.fision.cloud;

import android.content.Context;

import com.jotapeveloper.fision.SongStorage;

import java.io.File;

/**
 * Entry point for song downloads and the location of the downloaded songs.
 * The download itself (fetch, unzip, progress and notifications) runs in
 * {@link FisionDownloadService}, so it survives the app going to background.
 */
public class DownloadManagerLite {

    private static DownloadManagerLite INSTANCE;
    public static synchronized DownloadManagerLite get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new DownloadManagerLite(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final Context appCtx;

    private DownloadManagerLite(Context ctx) {
        this.appCtx = ctx;
    }

    public synchronized void start(String hash) {
        if (hash == null || hash.isEmpty()) return;
        FisionDownloadService.enqueue(appCtx, hash);
    }

    /** Song folders of the active account. */
    public static File songsDir(Context ctx) {
        return SongStorage.root(ctx);
    }
}
