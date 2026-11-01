package com.jotapeveloper.fision;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.jotapeveloper.fision.cloud.AuthManager;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Demo song for local mode: "Airplane Mode" by Josh Woodward (CC BY 4.0),
 * attached to a Fision-Server release already split and analysed.
 *
 * Until it is in the library, local mode lists a dark placeholder row pinned
 * on top; tapping it offers the download ({@link DemoSongService}, which
 * imports it with the same checks as a picked folder). Deleting the imported
 * song brings the placeholder back; deleting the placeholder hides it for
 * good, until the download is asked for again from Settings.
 */
public final class DemoSong {

    private DemoSong() {}

    static final String URL =
            "https://github.com/jotapemj/Fision-Server/releases/download/v2.1.0/fision-demo.zip";
    /** SHA-256 of the release asset; any other file is rejected. */
    static final String SHA256 = "32ab164fb9506d884bc6016dd3b10d5d7c539f5e933d40cf338ae2689d8e5bb3";
    static final long ZIP_BYTES = 172_043_070L;
    /** Song folder inside the zip, also the folder name in the library. */
    static final String FOLDER = "3aee40e900130b16cbafd93bfdba1da543a81011f8d54b286ddc72ee042a1ff3";
    static final String ZIP_DIR = FOLDER + "/stems/";

    /** Key of the placeholder row (never a real folder name: starts with a dot). */
    public static final String PLACEHOLDER_KEY = ".demo";
    public static final String TITLE  = "Airplane Mode";
    public static final String ARTIST = "Josh Woodward";

    private static final String PREFS = "prefs_demo";
    private static final String KEY_DISMISSED = "placeholder_dismissed";

    public interface Listener {
        /** Download progress 0..100, or -1 when no download runs. */
        void onDemoProgress(int pct);
        /** Download ended: imported, or failed with an import error (0 = cancelled). */
        void onDemoFinished(boolean ok, int error, @Nullable String file);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile int pct = -1;

    public static void addListener(Listener l)    { listeners.addIfAbsent(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    /** 0..100 while downloading, -1 otherwise. */
    public static int progress() { return pct; }

    public static boolean isDownloading() { return pct >= 0; }

    public static boolean isInLibrary(Context ctx) {
        return new File(SongCatalog.songsRoot(ctx), FOLDER).isDirectory();
    }

    /** Local mode, not in the library and not dismissed (a running download always shows). */
    public static boolean placeholderShown(Context ctx) {
        if (!AuthManager.get(ctx).isLocalMode()) return false;
        if (isDownloading()) return true;
        return !isInLibrary(ctx) && !prefs(ctx).getBoolean(KEY_DISMISSED, false);
    }

    /** Settings offers the download in local mode while the song is not in the library. */
    public static boolean downloadOffered(Context ctx) {
        return AuthManager.get(ctx).isLocalMode() && !isInLibrary(ctx);
    }

    public static void dismissPlaceholder(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_DISMISSED, true).apply();
    }

    /** Starts the download and import; the placeholder shows its progress. */
    public static void start(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_DISMISSED, false).apply();
        if (isDownloading()) return;
        setProgress(0);
        Intent i = new Intent(ctx, DemoSongService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    static void setProgress(int p) {
        pct = p;
        main.post(() -> { for (Listener l : listeners) l.onDemoProgress(p); });
    }

    static void finished(boolean ok, int error, @Nullable String file) {
        pct = -1;
        main.post(() -> { for (Listener l : listeners) l.onDemoFinished(ok, error, file); });
    }

    @MainThread
    public static SongListData placeholder() {
        SongListData s = new SongListData(PLACEHOLDER_KEY, "");
        s.setDisplayTitle(TITLE);
        s.setArtist(ARTIST);
        s.demo = true;
        return s;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
