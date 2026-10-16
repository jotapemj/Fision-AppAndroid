package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jotapeveloper.fision.cloud.PlaylistRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Playlist artwork: the server cover when there is one, otherwise a 2×2
 * mosaic of the covers of the first four songs (built here; the server does
 * not make it). Decoded off the main thread and kept in a small memory cache.
 */
public final class PlaylistCovers {

    private PlaylistCovers() {}

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(16 * 1024 * 1024) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
            };

    /** Loads the artwork of [p] into [iv] at [sizePx]. Recycled views are
     *  safe: the result is dropped if the view was rebound meanwhile. */
    public static void load(@NonNull ImageView iv, @NonNull PlaylistRepository.Playlist p,
                            int sizePx) {
        Context ctx = iv.getContext().getApplicationContext();
        File serverCover = PlaylistRepository.get(ctx).getCoverFile(p);
        List<String> paths = new ArrayList<>(4);
        if (serverCover == null) {
            for (String hash : p.songs) {
                String c = SongCatalog.resolve(ctx, hash).coverPath;
                paths.add(c);
                if (paths.size() == 4) break;
            }
        }
        String key = sizePx + "|" + (serverCover != null ? serverCover.getPath() : paths.toString());
        iv.setTag(R.id.tag_playlist_cover_key, key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        iv.setImageResource(R.raw.artcover_generic);
        EXEC.execute(() -> {
            Bitmap b = serverCover != null
                    ? decode(serverCover.getPath(), sizePx)
                    : mosaic(ctx, paths, sizePx);
            if (b == null) return;
            CACHE.put(key, b);
            MAIN.post(() -> {
                if (key.equals(iv.getTag(R.id.tag_playlist_cover_key))) iv.setImageBitmap(b);
            });
        });
    }

    /** Loads one song cover (square, [sizePx]) into [iv]; generic art when
     *  [path] is null or unreadable. */
    public static void loadSong(@NonNull ImageView iv, @Nullable String path, int sizePx) {
        String key = sizePx + "|song|" + path;
        iv.setTag(R.id.tag_playlist_cover_key, key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        iv.setImageResource(R.raw.artcover_generic);
        if (path == null) return;
        EXEC.execute(() -> {
            Bitmap b = decode(path, sizePx);
            if (b == null) return;
            CACHE.put(key, b);
            MAIN.post(() -> {
                if (key.equals(iv.getTag(R.id.tag_playlist_cover_key))) iv.setImageBitmap(b);
            });
        });
    }

    /** Square crop of [path] scaled to [size]. */
    @Nullable
    private static Bitmap decode(@Nullable String path, int size) {
        if (path == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(path, o);
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawCropped(new Canvas(out), src, new Rect(0, 0, size, size));
        src.recycle();
        return out;
    }

    private static void drawCropped(Canvas c, Bitmap src, Rect dst) {
        int s = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - s) / 2, y = (src.getHeight() - s) / 2;
        c.drawBitmap(src, new Rect(x, y, x + s, y + s), dst,
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
    }

    /** One cover for 1-3 songs, 2×2 grid for 4+, generic art for none. */
    @Nullable
    private static Bitmap mosaic(Context ctx, List<String> paths, int size) {
        if (paths.size() < 4) {
            String first = paths.isEmpty() ? null : paths.get(0);
            Bitmap b = decode(first, size);
            return b != null ? b : genericArt(ctx, size);
        }
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int h = size / 2;
        Bitmap generic = null;
        for (int i = 0; i < 4; i++) {
            Rect dst = new Rect((i % 2) * h, (i / 2) * h, (i % 2) * h + h, (i / 2) * h + h);
            Bitmap tile = decode(paths.get(i), h);
            if (tile == null) {
                if (generic == null) generic = genericArt(ctx, h);
                tile = generic;
            }
            if (tile != null) drawCropped(c, tile, dst);
            if (tile != null && tile != generic) tile.recycle();
        }
        return out;
    }

    @Nullable
    private static Bitmap genericArt(Context ctx, int size) {
        Bitmap src = BitmapFactory.decodeResource(ctx.getResources(), R.raw.artcover_generic);
        if (src == null) return null;
        Bitmap out = Bitmap.createScaledBitmap(src, size, size, true);
        if (out != src) src.recycle();
        return out;
    }
}
