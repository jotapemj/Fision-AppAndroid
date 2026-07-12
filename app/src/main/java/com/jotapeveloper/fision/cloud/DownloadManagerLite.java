package com.jotapeveloper.fision.cloud;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Lightweight downloader (no WorkManager). Fetches the zip into cache,
 * unzips it into Songs/<hash>/, reports progress to CloudRepository and
 * emits a dismissible notification on completion. Internal queue: avoids
 * launching two downloads for the same hash, but different hashes run in
 * parallel (the server serves them statically).
 */
public class DownloadManagerLite {

    private static final String TAG = "DownloadManagerLite";
    private static final String CHANNEL_ID = "fision_cloud";
    private static final String CHANNEL_NAME = "Fision · Server";

    private static DownloadManagerLite INSTANCE;
    public static synchronized DownloadManagerLite get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new DownloadManagerLite(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final Context appCtx;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Set<String> inFlight = new HashSet<>();

    private DownloadManagerLite(Context ctx) {
        this.appCtx = ctx;
        ensureChannel();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Fision server notifications");
        nm.createNotificationChannel(ch);
    }

    public synchronized void start(String hash) {
        if (hash == null || hash.isEmpty()) return;
        // Delegates on the ForegroundService: survives the app moving to background
        // without being killed by the system, and shows a progress bar notification.
        FisionDownloadService.enqueue(appCtx, hash);
    }

    private void runDownload(String hash) {
        CloudRepository repo = CloudRepository.get(appCtx);
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) {
            done(hash);
            repo.onDownloadFailed(hash, "Server not configured");
            return;
        }
        repo.onDownloadStarted(hash);

        File outDir = songsDir(appCtx);
        File hashDir = new File(outDir, hash);
        File tmpZip  = new File(appCtx.getCacheDir(), "dl_" + hash + ".zip");

        try {
            Call<ResponseBody> call = api.downloadZip(hash);
            Response<ResponseBody> resp = call.execute();
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code());
            }
            long total = resp.body().contentLength();
            try (InputStream in = resp.body().byteStream();
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tmpZip))) {
                byte[] buf = new byte[64 * 1024];
                long downloaded = 0;
                int n;
                long lastNotify = 0;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    long now = System.nanoTime();
                    // UI progress throttle: max 10 updates/s
                    if (now - lastNotify > 100_000_000L) {
                        repo.onDownloadProgress(hash, downloaded, total);
                        lastNotify = now;
                    }
                }
                repo.onDownloadProgress(hash, downloaded, total);
            }

            if (hashDir.exists()) deleteRecursive(hashDir);
            if (!hashDir.mkdirs()) throw new IOException("Could not create " + hashDir);

            unzip(tmpZip, hashDir);
            tmpZip.delete();

            CloudItem snap = repo.getByHash(hash);
            String displayName = snap != null ? snap.displayName : hash;
            repo.onLocalCommit(hash, hash);
            notifyDone(hash, displayName);
        } catch (Throwable t) {
            Log.w(TAG, "Download failed " + hash, t);
            try { if (tmpZip.exists()) tmpZip.delete(); } catch (Throwable ignored) {}
            repo.onDownloadFailed(hash, t.getMessage());
        } finally {
            done(hash);
        }
    }

    private synchronized void done(String hash) { inFlight.remove(hash); }

    private static void unzip(File zip, File outDir) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name.contains("..")) continue;            // zip-slip defense
                File dst = new File(outDir, name);
                if (e.isDirectory()) {
                    dst.mkdirs();
                    continue;
                }
                File parent = dst.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
                    int n;
                    while ((n = zin.read(buf)) != -1) out.write(buf, 0, n);
                }
                zin.closeEntry();
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    /** Songs/ inside the app's external storage: same directory the app already uses. */
    public static File songsDir(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        File songs = new File(ext, "Songs");
        if (!songs.exists()) songs.mkdirs();
        return songs;
    }

    private void notifyDone(String hash, String displayName) {
        NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(appCtx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(displayName != null ? displayName : "Song ready")
                .setContentText("Ready to play")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        nm.notify(("dl_" + hash).hashCode(), b.build());
    }
}
