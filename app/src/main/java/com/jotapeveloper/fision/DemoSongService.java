package com.jotapeveloper.fision;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Foreground service that downloads the demo song and imports it while the
 * zip streams in (only the song files reach the disk). A notification shows
 * the progress and offers cancelling.
 */
public class DemoSongService extends Service {

    private static final String TAG = "DemoSongService";
    /** Same channel as the server downloads. */
    private static final String CHANNEL_ID = "fision_cloud";
    private static final int NOTIF_ID = 91207;
    private static final String ACTION_CANCEL = "com.jotapeveloper.fision.DEMO_CANCEL";

    private volatile boolean cancelled;
    private Thread worker;
    private int lastPct = -1;

    @Nullable @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        Notification n = buildNotification(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            if (worker == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
            return START_NOT_STICKY;
        }
        if (worker == null) {
            worker = new Thread(this::run, "DemoSongDownload");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void run() {
        SongFolderImporter.Result r;
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        try (Response resp = client.newCall(new Request.Builder().url(DemoSong.URL).build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new IOException("HTTP " + resp.code());
            long total = resp.body().contentLength() > 0 ? resp.body().contentLength() : DemoSong.ZIP_BYTES;
            try (InputStream in = resp.body().byteStream()) {
                r = SongFolderImporter.importZip(getApplicationContext(), in, DemoSong.ZIP_DIR,
                        DemoSong.FOLDER, DemoSong.SHA256, DemoSong.ZIP_BYTES,
                        new SongFolderImporter.ZipProgress() {
                            @Override public void onBytes(long read) { onProgress(read, total); }
                            @Override public boolean isCancelled() { return cancelled; }
                        });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Demo download failed", t);
            r = null;
        }
        if (r == null) DemoSong.finished(false, cancelled ? 0 : R.string.demo_download_failed, null);
        else DemoSong.finished(r.ok, r.error, r.name);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void onProgress(long read, long total) {
        int pct = (int) Math.min(99, read * 100L / total);
        if (pct == lastPct) return;
        lastPct = pct;
        DemoSong.setProgress(pct);
        NotificationManager nm = getSystemService(NotificationManager.class);
        try { if (nm != null) nm.notify(NOTIF_ID, buildNotification(pct)); }
        catch (SecurityException ignored) { /* notifications not allowed */ }
    }

    private Notification buildNotification(int pct) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, NOTIF_ID, open, piFlags);
        PendingIntent cancelPi = PendingIntent.getService(this, NOTIF_ID,
                new Intent(this, DemoSongService.class).setAction(ACTION_CANCEL), piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(DemoSong.TITLE)
                .setContentText(getString(R.string.dl_notif_progress_single, pct))
                .setContentIntent(openPi)
                .addAction(0, getString(android.R.string.cancel), cancelPi)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(100, pct, pct <= 0)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_downloads_desc));
        nm.createNotificationChannel(ch);
    }
}
