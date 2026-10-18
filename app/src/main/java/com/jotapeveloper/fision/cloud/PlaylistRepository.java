package com.jotapeveloper.fision.cloud;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.jotapeveloper.fision.AppDatabase;
import com.jotapeveloper.fision.DAO_db_Playlist;
import com.jotapeveloper.fision.db_Playlist;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * User playlists. The server is the master; Room keeps an offline copy for
 * playback without connection and for Android Auto. Covers are cached in
 * filesDir/playlist_covers, one file per playlist version.
 *
 * Sync (GET /playlists) runs when the app opens and when the connection comes
 * back: changed playlists are fetched again, missing ones are removed.
 * Creating, editing and deleting need the server.
 */
public final class PlaylistRepository {

    private static final String TAG = "PlaylistRepository";

    /** Server limits. */
    public static final int MAX_TITLE_LENGTH = 100;
    public static final int MAX_SONGS = 2000;
    public static final int MAX_PLAYLISTS = 200;

    /** Cover bytes accepted by the server. */
    public static final int MAX_COVER_BYTES = 2 * 1024 * 1024;

    public static final class Playlist {
        @NonNull public final String id;
        public final String title;
        public final boolean hasCover;
        public final String createdAt;
        public final String updatedAt;
        /** Song hashes in play order. */
        @NonNull public final List<String> songs;

        Playlist(@NonNull String id, String title, boolean hasCover, String createdAt,
                 String updatedAt, @Nullable List<String> songs) {
            this.id = id;
            this.title = title;
            this.hasCover = hasCover;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.songs = songs != null
                    ? Collections.unmodifiableList(new ArrayList<>(songs))
                    : Collections.emptyList();
        }

        /** Unsaved playlist used to preview the mosaic while editing. */
        public static Playlist preview(@NonNull List<String> songs) {
            return new Playlist("preview", null, false, null, null, songs);
        }

        static Playlist from(PlaylistDto d) {
            return new Playlist(d.id, d.title, d.hasCover, d.createdAt, d.updatedAt, d.songs);
        }
    }

    /** Why a server call failed. UI text comes from {@link PlaylistErrors}. */
    public static final class PlaylistError {
        /** HTTP status, 0 on a network failure. */
        public final int http;
        /** Stable server code (title_length, song_not_found, ...), may be null. */
        @Nullable public final String code;
        /** Server text, may be null. */
        @Nullable public final String detail;
        /** The failed call was the cover step. */
        public final boolean cover;
        /** Cover step only: the playlist itself was saved under this id. */
        @Nullable public final String savedId;

        PlaylistError(int http, @Nullable String code, @Nullable String detail, boolean cover) {
            this(http, code, detail, cover, null);
        }

        PlaylistError(int http, @Nullable String code, @Nullable String detail, boolean cover,
                      @Nullable String savedId) {
            this.http = http;
            this.code = code;
            this.detail = detail;
            this.cover = cover;
            this.savedId = savedId;
        }

        public boolean isNetwork() { return http == 0; }
        /** The playlist was deleted from another device. */
        public boolean isGone()    { return http == 404 && !cover; }
    }

    public interface Listener {
        void onPlaylistsChanged();
    }

    public interface Result {
        /** [playlist] is null after a delete. */
        void onSuccess(@Nullable Playlist playlist);
        void onError(@NonNull PlaylistError error);
    }

    /** What to do with the cover when saving. */
    public static final class CoverEdit {
        public static final CoverEdit KEEP = new CoverEdit(null, false);
        public static final CoverEdit REMOVE = new CoverEdit(null, true);
        @Nullable final byte[] jpeg;
        final boolean remove;

        private CoverEdit(@Nullable byte[] jpeg, boolean remove) {
            this.jpeg = jpeg;
            this.remove = remove;
        }

        /** New square cover, already encoded. */
        public static CoverEdit set(@NonNull byte[] jpeg) { return new CoverEdit(jpeg, false); }
    }

    private static PlaylistRepository INSTANCE;

    public static synchronized PlaylistRepository get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new PlaylistRepository(ctx.getApplicationContext());
        return INSTANCE;
    }

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Gson gson = new Gson();

    /** Current list in server order. Replaced as a whole, read on any thread. */
    private volatile List<Playlist> playlists = Collections.emptyList();
    /** The offline copy (or a sync) has filled {@link #playlists}. */
    private volatile boolean loaded = false;
    private boolean syncing = false;
    private boolean syncAgain = false;

    private PlaylistRepository(Context ctx) {
        this.appCtx = ctx;
        io.execute(this::loadFromRoom);
    }

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) {
                try { l.onPlaylistsChanged(); }
                catch (Throwable t) { Log.w(TAG, "listener threw", t); }
            }
        });
    }

    @NonNull
    public List<Playlist> getAll() { return playlists; }

    @Nullable
    public Playlist getById(@Nullable String id) {
        if (id == null) return null;
        for (Playlist p : playlists) if (p.id.equals(id)) return p;
        return null;
    }

    /** Creating, editing and deleting need a session and a reachable server. */
    public boolean canEdit() {
        return AuthManager.get(appCtx).isLoggedIn() && CloudRepository.get(appCtx).isReachable();
    }

    // ---------------------------------------------------------------
    // Offline copy
    // ---------------------------------------------------------------
    private DAO_db_Playlist dao() {
        return AppDatabase.getInstance(appCtx).DAO_db_Playlist();
    }

    private void loadFromRoom() {
        List<Playlist> out = new ArrayList<>();
        try {
            for (db_Playlist row : dao().getAll()) {
                if (!isValidId(row.id)) continue;
                List<String> songs = null;
                try {
                    songs = gson.fromJson(row.songsJson, new TypeToken<List<String>>() {}.getType());
                } catch (Throwable ignored) { }
                out.add(new Playlist(row.id, row.title, row.hasCover, row.createdAt,
                        row.updatedAt, songs));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Room read failed", t);
        }
        // A sync that finished first already holds fresher data.
        if (!loaded) {
            playlists = Collections.unmodifiableList(out);
            loaded = true;
            notifyChanged();
        }
    }

    public boolean isLoaded() { return loaded; }

    /** Writes [list] to Room on the io thread. */
    private void persist(List<Playlist> list) {
        List<db_Playlist> rows = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Playlist p = list.get(i);
            db_Playlist row = new db_Playlist();
            row.id = p.id;
            row.title = p.title;
            row.listIndex = i;
            row.hasCover = p.hasCover;
            row.createdAt = p.createdAt;
            row.updatedAt = p.updatedAt;
            row.songsJson = gson.toJson(p.songs);
            rows.add(row);
        }
        io.execute(() -> {
            try { dao().replaceAll(rows); }
            catch (Throwable t) { Log.w(TAG, "Room write failed", t); }
        });
    }

    /** Sets the list, persists it and tells the listeners. Main thread. */
    private void commit(List<Playlist> list) {
        playlists = Collections.unmodifiableList(new ArrayList<>(list));
        loaded = true;
        persist(playlists);
        cleanCoverCache(playlists);
        notifyChanged();
    }

    /** Puts [p] first (last change first, as the server orders it). */
    private void putFirst(Playlist p) {
        List<Playlist> list = new ArrayList<>(playlists.size() + 1);
        list.add(p);
        for (Playlist o : playlists) if (!o.id.equals(p.id)) list.add(o);
        commit(list);
    }

    private void removeLocal(String id) {
        List<Playlist> list = new ArrayList<>(playlists);
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) { list.remove(i); removed = true; }
        }
        if (removed) commit(list);
    }

    // ---------------------------------------------------------------
    // Sync
    // ---------------------------------------------------------------
    /** GET /playlists, then GET /playlists/{id} for each changed one. */
    public void sync() {
        main.post(() -> {
            if (!AuthManager.get(appCtx).isLoggedIn()) return;
            if (syncing) { syncAgain = true; return; }
            FisionApi api = ApiClient.get(appCtx).api();
            if (api == null) return;
            syncing = true;
            api.listPlaylists().enqueue(new Callback<List<PlaylistDto>>() {
                @Override public void onResponse(Call<List<PlaylistDto>> c,
                                                 Response<List<PlaylistDto>> r) {
                    if (!r.isSuccessful() || r.body() == null) { endSync(); return; }
                    mergeList(api, r.body());
                }
                @Override public void onFailure(Call<List<PlaylistDto>> c, Throwable t) {
                    endSync();
                }
            });
        });
    }

    private void endSync() {
        syncing = false;
        if (syncAgain) {
            syncAgain = false;
            sync();
        }
    }

    private void mergeList(FisionApi api, List<PlaylistDto> remote) {
        Map<String, Playlist> known = new HashMap<>();
        for (Playlist p : playlists) known.put(p.id, p);

        // Unchanged playlists keep their songs; changed ones are fetched again.
        Playlist[] result = new Playlist[remote.size()];
        List<Integer> toFetch = new ArrayList<>();
        for (int i = 0; i < remote.size(); i++) {
            PlaylistDto d = remote.get(i);
            if (d == null || !isValidId(d.id)) continue;
            Playlist old = known.get(d.id);
            boolean same = old != null && old.updatedAt != null
                    && old.updatedAt.equals(d.updatedAt);
            if (same) {
                result[i] = new Playlist(d.id, d.title, d.hasCover, d.createdAt,
                        d.updatedAt, old.songs);
            } else {
                toFetch.add(i);
            }
        }
        if (toFetch.isEmpty()) {
            finishMerge(result);
            return;
        }
        final int[] pending = { toFetch.size() };
        for (int i : toFetch) {
            final int idx = i;
            PlaylistDto d = remote.get(i);
            api.getPlaylist(d.id).enqueue(new Callback<PlaylistDto>() {
                @Override public void onResponse(Call<PlaylistDto> c, Response<PlaylistDto> r) {
                    if (r.isSuccessful() && r.body() != null) {
                        result[idx] = Playlist.from(r.body());
                    } else if (r.code() != 404) {
                        // Keep the old copy (or the summary) until the next sync.
                        Playlist old = known.get(d.id);
                        result[idx] = new Playlist(d.id, d.title, d.hasCover, d.createdAt,
                                old != null ? old.updatedAt : null,
                                old != null ? old.songs : null);
                    }
                    if (--pending[0] == 0) finishMerge(result);
                }
                @Override public void onFailure(Call<PlaylistDto> c, Throwable t) {
                    Playlist old = known.get(d.id);
                    result[idx] = new Playlist(d.id, d.title, d.hasCover, d.createdAt,
                            old != null ? old.updatedAt : null,
                            old != null ? old.songs : null);
                    if (--pending[0] == 0) finishMerge(result);
                }
            });
        }
    }

    private void finishMerge(Playlist[] result) {
        List<Playlist> list = new ArrayList<>(result.length);
        for (Playlist p : result) if (p != null) list.add(p);
        commit(list);
        for (Playlist p : list) if (p.hasCover) ensureCover(p);
        endSync();
    }

    // ---------------------------------------------------------------
    // Edits
    // ---------------------------------------------------------------
    public void create(@NonNull String title, @NonNull List<String> songs,
                       @NonNull CoverEdit cover, @NonNull Result cb) {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) { cb.onError(new PlaylistError(0, null, null, false)); return; }
        api.createPlaylist(new PlaylistSaveRequest(title, songs))
                .enqueue(saveCallback(api, cover, cb));
    }

    public void update(@NonNull String id, @NonNull String title, @NonNull List<String> songs,
                       @NonNull CoverEdit cover, @NonNull Result cb) {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) { cb.onError(new PlaylistError(0, null, null, false)); return; }
        api.updatePlaylist(id, new PlaylistSaveRequest(title, songs))
                .enqueue(saveCallback(api, cover, cb));
    }

    public void delete(@NonNull String id, @NonNull Result cb) {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) { cb.onError(new PlaylistError(0, null, null, false)); return; }
        api.deletePlaylist(id).enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c,
                                             Response<Map<String, Object>> r) {
                // 404: already gone, which is what was asked.
                if (r.isSuccessful() || r.code() == 404) {
                    removeLocal(id);
                    cb.onSuccess(null);
                } else {
                    cb.onError(parseError(r, false));
                }
            }
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                cb.onError(new PlaylistError(0, null, null, false));
            }
        });
    }

    /** Reloads one playlist (on opening it). A 404 removes it locally. */
    public void refresh(@NonNull String id, @Nullable Result cb) {
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        api.getPlaylist(id).enqueue(new Callback<PlaylistDto>() {
            @Override public void onResponse(Call<PlaylistDto> c, Response<PlaylistDto> r) {
                if (r.isSuccessful() && r.body() != null) {
                    Playlist p = Playlist.from(r.body());
                    replaceInPlace(p);
                    if (p.hasCover) ensureCover(p);
                    if (cb != null) cb.onSuccess(p);
                } else {
                    PlaylistError e = parseError(r, false);
                    if (e.isGone()) removeLocal(id);
                    if (cb != null) cb.onError(e);
                }
            }
            @Override public void onFailure(Call<PlaylistDto> c, Throwable t) {
                if (cb != null) cb.onError(new PlaylistError(0, null, null, false));
            }
        });
    }

    private void replaceInPlace(Playlist p) {
        List<Playlist> list = new ArrayList<>(playlists);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(p.id)) { list.set(i, p); found = true; }
        }
        if (!found) list.add(0, p);
        commit(list);
    }

    private Callback<PlaylistDto> saveCallback(FisionApi api, CoverEdit cover, Result cb) {
        return new Callback<PlaylistDto>() {
            @Override public void onResponse(Call<PlaylistDto> c, Response<PlaylistDto> r) {
                if (!r.isSuccessful() || r.body() == null) {
                    PlaylistError e = parseError(r, false);
                    if (e.isGone()) {
                        String id = idFromUrl(c);
                        if (id != null) removeLocal(id);
                    }
                    cb.onError(e);
                    return;
                }
                Playlist saved = Playlist.from(r.body());
                putFirst(saved);
                applyCover(api, saved, cover, cb);
            }
            @Override public void onFailure(Call<PlaylistDto> c, Throwable t) {
                cb.onError(new PlaylistError(0, null, null, false));
            }
        };
    }

    @Nullable
    private static String idFromUrl(Call<?> c) {
        List<String> seg = c.request().url().pathSegments();
        return seg.isEmpty() ? null : seg.get(seg.size() - 1);
    }

    /** Uploads or removes the cover after the playlist itself was saved,
     *  then reads the playlist again for its new updated_at. */
    private void applyCover(FisionApi api, Playlist saved, CoverEdit cover, Result cb) {
        if (cover.jpeg == null && !(cover.remove && saved.hasCover)) {
            cb.onSuccess(saved);
            return;
        }
        Callback<Map<String, Object>> after = new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c,
                                             Response<Map<String, Object>> r) {
                if (!r.isSuccessful()) {
                    PlaylistError e = parseError(r, true);
                    cb.onError(new PlaylistError(e.http, e.code, e.detail, true, saved.id));
                    return;
                }
                refreshAfterCover(api, saved, cover.jpeg, cb);
            }
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                cb.onError(new PlaylistError(0, null, null, true, saved.id));
            }
        };
        if (cover.jpeg != null) {
            RequestBody body = RequestBody.create(cover.jpeg, MediaType.parse("image/jpeg"));
            MultipartBody.Part part = MultipartBody.Part.createFormData("file", "cover.jpg", body);
            api.putPlaylistCover(saved.id, part).enqueue(after);
        } else {
            api.deletePlaylistCover(saved.id).enqueue(after);
        }
    }

    private void refreshAfterCover(FisionApi api, Playlist saved, @Nullable byte[] jpeg,
                                   Result cb) {
        api.getPlaylist(saved.id).enqueue(new Callback<PlaylistDto>() {
            @Override public void onResponse(Call<PlaylistDto> c, Response<PlaylistDto> r) {
                Playlist p = r.isSuccessful() && r.body() != null
                        ? Playlist.from(r.body())
                        : new Playlist(saved.id, saved.title, jpeg != null, saved.createdAt,
                                saved.updatedAt, saved.songs);
                if (jpeg != null && p.hasCover) {
                    // The uploaded bytes are the cover: no need to download them back.
                    io.execute(() -> {
                        writeCover(p, jpeg);
                        main.post(() -> { putFirst(p); cb.onSuccess(p); });
                    });
                } else {
                    putFirst(p);
                    cb.onSuccess(p);
                }
            }
            @Override public void onFailure(Call<PlaylistDto> c, Throwable t) {
                Playlist p = new Playlist(saved.id, saved.title, jpeg != null, saved.createdAt,
                        saved.updatedAt, saved.songs);
                putFirst(p);
                cb.onSuccess(p);
            }
        });
    }

    private PlaylistError parseError(Response<?> r, boolean cover) {
        String code = null, detail = null;
        try (ResponseBody eb = r.errorBody()) {
            if (eb != null) {
                JsonElement el = JsonParser.parseString(eb.string());
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    if (o.has("code") && o.get("code").isJsonPrimitive()) {
                        code = o.get("code").getAsString();
                    }
                    if (o.has("detail") && o.get("detail").isJsonPrimitive()) {
                        detail = o.get("detail").getAsString();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return new PlaylistError(r.code(), code, detail, cover);
    }

    // ---------------------------------------------------------------
    // Covers
    // ---------------------------------------------------------------
    private File coverDir() {
        File d = new File(appCtx.getFilesDir(), "playlist_covers");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Cached server cover of [p], or null when it has none or it is not
     *  downloaded yet (the UI then shows the mosaic). */
    @Nullable
    public File getCoverFile(@NonNull Playlist p) {
        if (!p.hasCover) return null;
        File f = coverFileFor(p);
        return f.exists() ? f : null;
    }

    private File coverFileFor(Playlist p) {
        String v = p.updatedAt != null ? Integer.toHexString(p.updatedAt.hashCode()) : "0";
        // The id names a file: anything but the server format is hashed.
        String id = isValidId(p.id) ? p.id : "x" + Integer.toHexString(String.valueOf(p.id).hashCode());
        return new File(coverDir(), id + "_" + v + ".img");
    }

    private static final java.util.regex.Pattern PLAYLIST_ID =
            java.util.regex.Pattern.compile("^[0-9a-f]{12}$");

    /** Server playlist ids are 12 hex characters. */
    static boolean isValidId(String id) {
        return id != null && PLAYLIST_ID.matcher(id).matches();
    }

    private void writeCover(Playlist p, byte[] bytes) {
        File target = coverFileFor(p);
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        } catch (Throwable t) {
            Log.w(TAG, "cover write failed", t);
            target.delete();
        }
    }

    private void ensureCover(Playlist p) {
        if (coverFileFor(p).exists()) return;
        FisionApi api = ApiClient.get(appCtx).api();
        if (api == null) return;
        api.getPlaylistCover(p.id, p.updatedAt).enqueue(new Callback<ResponseBody>() {
            @Override public void onResponse(Call<ResponseBody> c, Response<ResponseBody> r) {
                if (!r.isSuccessful() || r.body() == null) return;
                ResponseBody body = r.body();
                // Reading the body off the main thread (StrictMode, socket drain).
                io.execute(() -> {
                    File target = coverFileFor(p);
                    File tmp = new File(target.getPath() + ".part");
                    try (InputStream in = body.byteStream();
                         OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } catch (Throwable t) {
                        tmp.delete();
                        return;
                    } finally {
                        body.close();
                    }
                    if (!isDecodable(tmp) || !tmp.renameTo(target)) {
                        tmp.delete();
                        return;
                    }
                    notifyChanged();
                });
            }
            @Override public void onFailure(Call<ResponseBody> c, Throwable t) { }
        });
    }

    private static boolean isDecodable(File f) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        return o.outWidth > 0;
    }

    /** Deletes cached covers of removed playlists and of older versions. */
    private void cleanCoverCache(List<Playlist> list) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Playlist p : list) if (p.hasCover) keep.add(coverFileFor(p).getName());
        io.execute(() -> {
            File[] files = coverDir().listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.getName().endsWith(".part")) continue;
                if (!keep.contains(f.getName())) f.delete();
            }
        });
    }

    /** Session ended: drop the offline copy and the covers. */
    public void clear() {
        main.post(() -> {
            playlists = Collections.emptyList();
            io.execute(() -> {
                try { dao().deleteAll(); } catch (Throwable ignored) { }
                File[] files = coverDir().listFiles();
                if (files != null) for (File f : files) f.delete();
            });
            notifyChanged();
        });
    }
}
