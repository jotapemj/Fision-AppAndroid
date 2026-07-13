package com.jotapeveloper.fision.cloud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.jotapeveloper.fision.MainActivity;
import com.jotapeveloper.fision.PlayerActivity;
import com.jotapeveloper.fision.R;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Foreground service that orchestrates server ZIP downloads. Started whenever a
 * new hash arrives; shows a notification with aggregate progress and stays alive
 * until the queue is empty. Survives the app moving to background without being
 * killed by the system.
 *
 * Notification: fision_cloud channel, progress bar with the % of the "active"
 * hash (the most recent). When N downloads are queued the text includes "(N/M)".
 */
public class FisionDownloadService extends Service {

    private static final String TAG = "FisionDlService";
    private static final String CHANNEL_ID = "fision_cloud";
    private static final String CHANNEL_NAME = "Fision · Server";
    private static final int    NOTIF_ID    = 91201;
    public  static final String EXTRA_HASH  = "hash";

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Set<String>  queued  = new HashSet<>();
    private final Map<String, Progress> progressByHash = new HashMap<>();
    private String activeHash = null;      // shown in the main notification
    private boolean isForeground = false;

    private static class Progress {
        String displayName;
        int pct;      // 0..100
        boolean done; // finished (ok or fail) -> counted in the aggregate text
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        if (!isForeground) {
            startInForeground();
            isForeground = true;
        }
        if (intent != null) {
            String hash = intent.getStringExtra(EXTRA_HASH);
            if (hash != null && !hash.isEmpty()) enqueue(hash);
        }
        return START_NOT_STICKY;
    }

    private synchronized void enqueue(String hash) {
        if (queued.contains(hash)) return;
        queued.add(hash);
        Progress p = new Progress();
        CloudItem it = CloudRepository.get(this).getByHash(hash);
        p.displayName = it != null && it.displayName != null ? it.displayName : hash;
        progressByHash.put(hash, p);
        activeHash = hash;
        refreshNotification();
        pool.execute(() -> runDownload(hash));
    }

    private void runDownload(String hash) {
        CloudRepository repo = CloudRepository.get(this);
        FisionApi api = ApiClient.get(this).api();
        if (api == null) {
            markDone(hash);
            repo.onDownloadFailed(hash, "Server not configured");
            return;
        }
        repo.onDownloadStarted(hash);

        File outDir  = DownloadManagerLite.songsDir(this);
        File hashDir = new File(outDir, hash);
        File tmpZip  = new File(getCacheDir(), "dl_" + hash + ".zip");

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
                    // progress throttle: max 10 updates/s
                    if (now - lastNotify > 100_000_000L) {
                        int pct = total > 0 ? (int) Math.min(100, (downloaded * 100L) / total) : 0;
                        onPct(hash, pct);
                        repo.onDownloadProgress(hash, downloaded, total);
                        lastNotify = now;
                    }
                }
                repo.onDownloadProgress(hash, downloaded, total);
                onPct(hash, 100);
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
            markDone(hash);
        }
    }

    private synchronized void onPct(String hash, int pct) {
        Progress p = progressByHash.get(hash);
        if (p != null) p.pct = pct;
        activeHash = hash;
        refreshNotification();
    }

    private synchronized void markDone(String hash) {
        queued.remove(hash);
        Progress p = progressByHash.get(hash);
        if (p != null) p.done = true;
        // When nothing is pending, leave foreground and stop.
        if (queued.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            isForeground = false;
            stopSelf();
        } else {
            // Move the active hash to the next pending one
            activeHash = queued.iterator().next();
            refreshNotification();
        }
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        try { nm.notify(NOTIF_ID, buildNotification()); }
        catch (SecurityException ignored) { /* POST_NOTIFICATIONS permission missing */ }
    }

    private Notification buildNotification() {
        Progress p = activeHash != null ? progressByHash.get(activeHash) : null;
        int pct = p != null ? p.pct : 0;
        String title = p != null && p.displayName != null ? p.displayName : "Downloading";
        int totalCount = progressByHash.size();
        int doneCount = 0;
        for (Progress q : progressByHash.values()) if (q.done) doneCount++;
        String text;
        if (totalCount > 1) {
            text = getString(R.string.dl_notif_progress_multi, doneCount + 1, totalCount, pct);
        } else {
            text = getString(R.string.dl_notif_progress_single, pct);
        }

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp, flags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(100, pct, pct <= 0);
        return b.build();
    }

    private void startInForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Fision server downloads");
        nm.createNotificationChannel(ch);
    }

    private static void unzip(File zip, File outDir) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name.contains("..")) continue;
                File dst = new File(outDir, name);
                if (e.isDirectory()) { dst.mkdirs(); continue; }
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

    private void notifyDone(String hash, String displayName) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return;

        // Tap opens PlayerActivity with the freshly downloaded song. autoCancel
        // dismisses the notification on tap.
        File hashDir = new File(DownloadManagerLite.songsDir(this), hash);
        Intent open = new Intent(this, PlayerActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("SongPathString", hashDir.getAbsolutePath());
        open.putExtra("SongName", hash);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, ("dl_" + hash).hashCode(), open, flags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(displayName != null ? displayName : "Song ready")
                .setContentText(getString(R.string.dl_notif_ready))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try { nm.notify(("dl_" + hash).hashCode(), b.build()); }
        catch (SecurityException ignored) {}
    }

    /** Entry point from DownloadManagerLite.start(). */
    public static void enqueue(Context ctx, String hash) {
        Intent i = new Intent(ctx, FisionDownloadService.class);
        i.putExtra(EXTRA_HASH, hash);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}
