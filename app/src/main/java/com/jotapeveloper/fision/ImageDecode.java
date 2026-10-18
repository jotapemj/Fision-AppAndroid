package com.jotapeveloper.fision;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

/**
 * Bitmap decoding bounded by the size it is shown at. Covers and avatars come
 * from the server or other apps; decoding them at full resolution wastes
 * memory and an oversized image could exhaust the heap.
 */
public final class ImageDecode {

    private ImageDecode() {}

    /** Decodes [path] with a power-of-two subsampling that keeps the longer
     *  side at or above [maxSidePx] only as far as needed. Null when the file
     *  is missing or not an image. */
    @Nullable
    public static Bitmap file(@Nullable String path, int maxSidePx) {
        if (path == null) return null;
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, probe);
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(probe.outWidth, probe.outHeight, maxSidePx);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** Largest power of two that keeps the longer side >= [maxSidePx]. */
    static int sampleSize(int w, int h, int maxSidePx) {
        int longer = Math.max(w, h);
        int sample = 1;
        while (longer / (sample * 2) >= maxSidePx) sample *= 2;
        return sample;
    }
}
