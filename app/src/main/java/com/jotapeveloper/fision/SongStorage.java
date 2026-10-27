package com.jotapeveloper.fision;

import android.content.Context;

import androidx.annotation.NonNull;

import com.jotapeveloper.fision.cloud.ServerPrefs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Location of the song library. Each server account (URL + username) has its
 * own folder and local mode another one, so a session only ever lists the
 * songs downloaded or imported under it:
 * <pre>
 * Library/local/&lt;folder&gt;/   local mode
 * Library/acc_&lt;id&gt;/&lt;hash&gt;/  one server account
 * </pre>
 * The account stays active while its session is expired; logout and local
 * mode switch to the local library. Folders of other accounts are kept.
 */
public final class SongStorage {

    private SongStorage() {}

    private static final String LIBRARY = "Library";
    public static final String LOCAL = "local";

    /** Key of the active library: {@link #LOCAL} or "acc_" + hash of URL and user. */
    @NonNull
    public static String activeKey(@NonNull Context ctx) {
        ServerPrefs sp = ServerPrefs.get(ctx);
        if (sp.isLocalMode() || sp.getUsername().isEmpty()) return LOCAL;
        String k = sp.getAccountKey();
        if (k.isEmpty()) {
            // Session stored before keys existed: derive it once.
            k = keyFor(sp.getUrl(), sp.getUsername());
            sp.setAccountKey(k);
        }
        return k;
    }

    /** Stable key of a server account. */
    @NonNull
    public static String keyFor(String url, String user) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        String id = u + "\n" + (user == null ? "" : user.trim().toLowerCase(Locale.ROOT));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("acc_");
            for (int i = 0; i < 12; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Song folders of the active account (created when missing). */
    @NonNull
    public static File root(@NonNull Context ctx) {
        File dir = new File(new File(ctx.getExternalFilesDir(null), LIBRARY), activeKey(ctx));
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** True when [path] is a song folder of the active account. */
    public static boolean isActive(@NonNull Context ctx, String path) {
        if (path == null) return false;
        File parent = new File(path).getParentFile();
        return parent != null && parent.equals(root(ctx));
    }
}
