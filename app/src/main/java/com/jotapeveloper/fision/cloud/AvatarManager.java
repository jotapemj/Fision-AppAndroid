package com.jotapeveloper.fision.cloud;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Owns the local avatar cache and the server sync. Local file lives in
 * filesDir/avatar_<user>.jpg (always JPEG regardless of the server's original
 * extension, because the client crops and re-encodes before upload).
 *
 * The manager exposes a listener bus so the toolbar and the account dialog
 * update whenever the avatar is downloaded, replaced or wiped.
 */
public class AvatarManager {

    private static final String TAG = "AvatarManager";
    private static final String FILE_PREFIX = "avatar_";
    private static final String FILE_SUFFIX = ".jpg";

    public interface Listener { void onAvatarChanged(String username); }
    public interface UploadCallback {
        default void onSuccess() {}
        default void onFailure(int httpCode, String reason) {}
    }

    private static AvatarManager INSTANCE;
    public static synchronized AvatarManager get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new AvatarManager(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    // Users for which fetchAvatar has already been attempted in this process.
    // Prevents refreshToolbarAvatar's safety net from spamming the server when
    // the answer was 404 (no avatar). Cleared on clearLocal so a re-login
    // triggers a fresh attempt.
    private final java.util.HashSet<String> attemptedUsers = new java.util.HashSet<>();

    private AvatarManager(Context ctx) { this.appCtx = ctx; }

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void notifyChanged(String user) {
        main.post(() -> {
            for (Listener l : listeners) {
                try { l.onAvatarChanged(user); }
                catch (Throwable t) { Log.w(TAG, "listener threw", t); }
            }
        });
    }

    /** Local cache file for the given username. Never null; may or may not exist. */
    public File getLocalFile(String username) {
        String safe = username == null ? "" : username.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new File(appCtx.getFilesDir(), FILE_PREFIX + safe + FILE_SUFFIX);
    }

    public boolean hasLocalAvatar(String username) {
        if (username == null || username.isEmpty()) return false;
        File f = getLocalFile(username);
        return f.exists() && f.length() > 0;
    }

    /** Clears the local cache for the given user (or all cached avatars when
     *  username is null). Used at logout / switch account. Also resets the
     *  "already attempted" mark so ensureFetched will try again next time. */
    public void clearLocal(String username) {
        if (username == null || username.isEmpty()) {
            File[] files = appCtx.getFilesDir().listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith(FILE_PREFIX) && f.getName().endsWith(FILE_SUFFIX)) {
                        f.delete();
                    }
                }
            }
            synchronized (attemptedUsers) { attemptedUsers.clear(); }
            notifyChanged(null);
            return;
        }
        File f = getLocalFile(username);
        if (f.exists()) f.delete();
        synchronized (attemptedUsers) { attemptedUsers.remove(username); }
        notifyChanged(username);
    }

    /** Kicks a fetchAvatar off unless one has already been attempted for this
     *  user in the current process or the file is already cached. Safety net
     *  for the race where the post-login fetch completes before MainActivity
     *  re-registers its listener, so the notification is dropped. Called from
     *  refreshToolbarAvatar on every UI refresh. */
    public void ensureFetched(String username) {
        if (username == null || username.isEmpty()) return;
        if (hasLocalAvatar(username)) return;
        synchronized (attemptedUsers) {
            if (attemptedUsers.contains(username)) return;
            attemptedUsers.add(username);
        }
        fetchAvatar(username);
    }

    /** Pulls the current user's avatar from the server into the local cache.
     *  404 is silently treated as "no avatar set" (cache stays empty).
     *  Retrofit delivers the callback on the main thread by default, so the
     *  actual body read + file write are moved to a background thread to
     *  avoid NetworkOnMainThreadException / StrictMode disk violations. */
    public void fetchAvatar(String username) {
        if (username == null || username.isEmpty()) return;
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        api.getAvatar().enqueue(new Callback<ResponseBody>() {
            @Override public void onResponse(Call<ResponseBody> c, Response<ResponseBody> r) {
                final int code = r.code();
                final ResponseBody body = r.body();
                new Thread(() -> {
                    try {
                        if (code == 404) {
                            File f = getLocalFile(username);
                            if (f.exists()) { f.delete(); notifyChanged(username); }
                            return;
                        }
                        if (code < 200 || code >= 300 || body == null) return;
                        try {
                            writeStreamToFile(body.byteStream(), getLocalFile(username));
                            notifyChanged(username);
                        } catch (IOException e) {
                            Log.w(TAG, "fetchAvatar write failed", e);
                        }
                    } finally {
                        if (body != null) body.close();
                    }
                }, "avatar-fetch").start();
            }
            @Override public void onFailure(Call<ResponseBody> c, Throwable t) {
                Log.w(TAG, "fetchAvatar network failure", t);
            }
        });
    }

    /** Uploads the given JPEG file as the current user's avatar. Also updates
     *  the local cache with the same bytes so the UI reflects the change
     *  immediately without waiting for a re-download. */
    public void uploadAvatar(String username, File jpeg, UploadCallback cb) {
        if (username == null || username.isEmpty()) { cb.onFailure(0, "no_user"); return; }
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) { cb.onFailure(0, "no_api"); return; }
        MediaType mt = MediaType.parse("image/jpeg");
        RequestBody body = RequestBody.create(jpeg, mt);
        MultipartBody.Part part = MultipartBody.Part.createFormData(
                "file", "avatar.jpg", body);
        api.putAvatar(part).enqueue(new retrofit2.Callback<java.util.Map<String, Object>>() {
            @Override public void onResponse(Call<java.util.Map<String, Object>> c,
                                             Response<java.util.Map<String, Object>> r) {
                if (!r.isSuccessful()) {
                    int code = r.code();
                    main.post(() -> cb.onFailure(code, code == 413 ? "too_large"
                            : code == 400 ? "bad_format" : "server_error"));
                    return;
                }
                // Mirror the uploaded bytes into the local cache off the main
                // thread (StrictMode disk-read/write policy is on).
                new Thread(() -> {
                    try {
                        copyFile(jpeg, getLocalFile(username));
                        notifyChanged(username);
                    } catch (IOException e) {
                        Log.w(TAG, "uploadAvatar cache write failed", e);
                    }
                    main.post(cb::onSuccess);
                }, "avatar-upload-cache").start();
            }
            @Override public void onFailure(Call<java.util.Map<String, Object>> c, Throwable t) {
                Log.w(TAG, "uploadAvatar network failure", t);
                main.post(() -> cb.onFailure(0, "network_error"));
            }
        });
    }

    public void deleteAvatar(String username, UploadCallback cb) {
        if (username == null || username.isEmpty()) { cb.onFailure(0, "no_user"); return; }
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) { cb.onFailure(0, "no_api"); return; }
        api.deleteAvatar().enqueue(new retrofit2.Callback<java.util.Map<String, Object>>() {
            @Override public void onResponse(Call<java.util.Map<String, Object>> c,
                                             Response<java.util.Map<String, Object>> r) {
                if (!r.isSuccessful()) {
                    int code = r.code();
                    main.post(() -> cb.onFailure(code, "server_error"));
                    return;
                }
                File f = getLocalFile(username);
                if (f.exists()) f.delete();
                notifyChanged(username);
                main.post(cb::onSuccess);
            }
            @Override public void onFailure(Call<java.util.Map<String, Object>> c, Throwable t) {
                Log.w(TAG, "deleteAvatar network failure", t);
                main.post(() -> cb.onFailure(0, "network_error"));
            }
        });
    }

    private static void writeStreamToFile(InputStream in, File out) throws IOException {
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        if (out.exists()) out.delete();
        tmp.renameTo(out);
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(src)) {
            writeStreamToFile(fis, dst);
        }
    }
}
