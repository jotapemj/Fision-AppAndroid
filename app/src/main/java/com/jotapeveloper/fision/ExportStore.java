package com.jotapeveloper.fision;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * Destination of the exports: {@code Music/Fision/<song>/} in shared storage.
 *
 * <p>Each file is rendered into a private temporary file first and published
 * only when complete. Android 10+ publishes through MediaStore (no storage
 * permission; the file is pending, so invisible to other apps, until it is
 * fully written). Android 8-9 writes the public folder directly, which needs
 * WRITE_EXTERNAL_STORAGE, and then asks the media scanner to index it.
 */
final class ExportStore {

    private ExportStore() {}

    static final String FOLDER = "Fision";

    /** Temporary file for one export; deleted by {@link #publish} or {@link #discard}. */
    @NonNull
    static File tempFile(@NonNull Context ctx, @NonNull String ext) {
        File base = ctx.getExternalCacheDir() != null ? ctx.getExternalCacheDir() : ctx.getCacheDir();
        File dir = new File(base, "export");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, UUID.randomUUID() + ext);
    }

    /** Removes leftovers of exports interrupted before publishing. */
    static void cleanTemp(@NonNull Context ctx) {
        for (File base : new File[]{ctx.getExternalCacheDir(), ctx.getCacheDir()}) {
            if (base == null) continue;
            File[] kids = new File(base, "export").listFiles();
            if (kids != null) for (File k : kids) k.delete();
        }
    }

    static void discard(File temp) {
        if (temp != null && temp.exists()) temp.delete();
    }

    /** True when exporting needs the legacy storage permission (Android 8-9). */
    static boolean needsWritePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    /**
     * Moves the finished [temp] file to {@code Music/Fision/<songFolder>/<fileName>}.
     * The temporary file is always deleted.
     */
    static void publish(@NonNull Context ctx, @NonNull File temp,
                        @NonNull String songFolder, @NonNull String fileName) throws IOException {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishMediaStore(ctx, temp, songFolder, fileName);
            else publishLegacy(ctx, temp, songFolder, fileName);
        } finally {
            discard(temp);
        }
    }

    private static void publishMediaStore(Context ctx, File temp, String songFolder, String fileName)
            throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(fileName));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/" + FOLDER + "/" + songFolder);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = cr.insert(collection, v);
        if (item == null) throw new IOException("MediaStore insert failed");
        boolean ok = false;
        try {
            try (InputStream in = new FileInputStream(temp);
                 OutputStream out = cr.openOutputStream(item, "w")) {
                if (out == null) throw new IOException("MediaStore stream failed");
                copy(in, out);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, done, null, null);
            ok = true;
        } finally {
            if (!ok) cr.delete(item, null, null);
        }
    }

    @SuppressWarnings("deprecation")
    private static void publishLegacy(Context ctx, File temp, String songFolder, String fileName)
            throws IOException {
        File music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        File dir = new File(new File(music, FOLDER), songFolder);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File out = new File(dir, fileName);
        boolean ok = false;
        try (InputStream in = new FileInputStream(temp);
             OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
            ok = true;
        } finally {
            if (!ok) out.delete();
        }
        MediaScannerConnection.scanFile(ctx, new String[]{out.getAbsolutePath()}, null, null);
    }

    private static String mimeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (m != null) return m;
        switch (ext) {
            case "wav": return "audio/x-wav";
            case "aac": return "audio/aac";
            case "m4a": return "audio/mp4";
            default:    return "audio/*";
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
