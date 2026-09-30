package com.jotapeveloper.fision.design;

import android.graphics.Bitmap;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * Player color set: UI accent (+ secondary for slider thumbs), the three
 * nebula blob colors and the nebula bottom anchor glow.
 *
 * Contrast rules when derived from artwork:
 * - Accent: the most saturated, well-populated swatch, normalised to a
 *   luminance window that reads on black and still carries white icons.
 * - Blobs: capped saturation and a dark lightness band, so they sit behind
 *   the controls; a blob whose hue is close to the accent is pushed darker
 *   still, so blobs and controls never read as the same element.
 * - Anchor (glow behind the transport controls): the blob furthest in hue
 *   from the accent, or the darkest one when all hues are close.
 */
public final class SongPalette {

    @ColorInt public final int accent;
    @ColorInt public final int accentSecondary;
    public final int[] nebula;
    @ColorInt public final int nebulaAnchor;

    // Relative luminance window of the UI accent (built-in accents span
    // roughly 0.21..0.50): readable on black, white icons still legible.
    private static final double ACCENT_Y_MIN = 0.20;
    private static final double ACCENT_Y_MAX = 0.42;
    private static final float  ACCENT_S_MIN = 0.50f;
    private static final float  ACCENT_S_MAX = 0.90f;
    // Artwork below this saturation has no usable color (greyscale covers).
    private static final float  SOURCE_S_MIN = 0.22f;
    // Secondary (slider thumbs): brighter, slightly hue-shifted companion.
    private static final float  SECONDARY_HUE_SHIFT = 15f;
    private static final double SECONDARY_Y_MIN = 0.45;
    // Blobs.
    private static final float  BLOB_S_MAX = 0.65f;
    private static final float  BLOB_L_MIN = 0.22f;
    private static final float  BLOB_L_MAX = 0.42f;
    private static final float  BLOB_CLOSE_L_MAX = 0.30f;
    private static final float  CLOSE_HUE_DEG = 25f;

    private SongPalette(int accent, int accentSecondary, int[] nebula, int nebulaAnchor) {
        this.accent = accent;
        this.accentSecondary = accentSecondary;
        this.nebula = nebula;
        this.nebulaAnchor = nebulaAnchor;
    }

    /** Palette built from a fixed accent: three tonal variants for the
     *  nebula, the accent itself as anchor. */
    public static SongPalette fromAccent(@ColorInt int primary, @ColorInt int secondary) {
        int lighter = ColorUtils.blendARGB(primary, 0xFFFFFFFF, 0.30f);
        int darker  = ColorUtils.blendARGB(primary, 0xFF000000, 0.40f);
        return new SongPalette(primary, secondary, new int[] { primary, lighter, darker }, primary);
    }

    /** Derives the palette from artwork. Null when the artwork has no
     *  usable color (the caller falls back to the picked accent). Runs the
     *  Palette extraction: call off the UI thread. */
    @Nullable
    public static SongPalette fromBitmap(Bitmap bm) {
        Palette p = Palette.from(bm).maximumColorCount(24).generate();
        Palette.Swatch best = null;
        double bestScore = -1;
        for (Palette.Swatch s : p.getSwatches()) {
            float[] hsl = s.getHsl();
            if (hsl[1] < SOURCE_S_MIN || hsl[2] < 0.12f || hsl[2] > 0.92f) continue;
            double score = s.getPopulation() * (double) (hsl[1] * hsl[1]);
            if (s == p.getVibrantSwatch()) score *= 1.5;
            if (score > bestScore) { bestScore = score; best = s; }
        }
        if (best == null) return null;

        float[] accentHsl = best.getHsl().clone();
        int accent = normalizeAccent(accentHsl);
        int secondary = deriveSecondary(accent);
        float accentHue = hueOf(accent);

        // Blob sources: Palette's named swatches, most colorful first.
        List<Integer> sources = new ArrayList<>();
        addSwatch(sources, p.getVibrantSwatch());
        addSwatch(sources, p.getDarkVibrantSwatch());
        addSwatch(sources, p.getLightVibrantSwatch());
        addSwatch(sources, p.getMutedSwatch());
        addSwatch(sources, p.getDarkMutedSwatch());
        addSwatch(sources, p.getLightMutedSwatch());
        sources.add(best.getRgb());
        int[] blobs = new int[3];
        for (int i = 0; i < 3; i++) {
            int src = sources.get(Math.min(i, sources.size() - 1));
            blobs[i] = toBlob(src, accentHue);
        }

        // Anchor: furthest hue from the accent, else the darkest blob.
        int anchor = blobs[0];
        float bestDist = -1f;
        for (int b : blobs) {
            float d = hueDistance(hueOf(b), accentHue);
            if (d > bestDist) { bestDist = d; anchor = b; }
        }
        if (bestDist < CLOSE_HUE_DEG) {
            anchor = blobs[0];
            for (int b : blobs) {
                if (ColorUtils.calculateLuminance(b) < ColorUtils.calculateLuminance(anchor)) anchor = b;
            }
        }
        return new SongPalette(accent, secondary, blobs, anchor);
    }

    // ---- Per-song cache ---------------------------------------------------
    // Extraction runs off the UI thread, so without a cache the player would
    // paint the picked accent first and then fade to the artwork colors on
    // every entry. Entries are keyed by song folder name and invalidated by
    // the artwork signature (mtime + size) and CACHE_VERSION (bump it when
    // the derivation rules change).
    private static final String CACHE_PREFS = "prefs_song_palette";
    private static final String CACHE_VERSION = "v1";
    private static final String CACHE_NONE = "none";

    /** Result of a cache lookup: [hit] false → not cached; hit with a null
     *  [palette] → artwork known to have no usable color. */
    public static final class Cached {
        public final boolean hit;
        @Nullable public final SongPalette palette;
        Cached(boolean hit, @Nullable SongPalette palette) { this.hit = hit; this.palette = palette; }
    }

    public static Cached loadCached(android.content.Context ctx, String songKey, String signature) {
        String raw = ctx.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE)
                .getString(songKey, null);
        if (raw == null) return new Cached(false, null);
        String[] f = raw.split("\\|");
        if (f.length < 3 || !CACHE_VERSION.equals(f[0]) || !signature.equals(f[1])) {
            return new Cached(false, null);
        }
        if (CACHE_NONE.equals(f[2])) return new Cached(true, null);
        if (f.length != 8) return new Cached(false, null);
        try {
            int[] v = new int[6];
            for (int i = 0; i < 6; i++) v[i] = (int) Long.parseLong(f[i + 2], 16);
            return new Cached(true, new SongPalette(v[0], v[1], new int[] { v[2], v[3], v[4] }, v[5]));
        } catch (NumberFormatException e) {
            return new Cached(false, null);
        }
    }

    /** Stores [palette] (null = artwork without usable color). */
    public static void storeCached(android.content.Context ctx, String songKey, String signature,
                                   @Nullable SongPalette palette) {
        StringBuilder sb = new StringBuilder(CACHE_VERSION).append('|').append(signature).append('|');
        if (palette == null) {
            sb.append(CACHE_NONE);
        } else {
            int[] v = { palette.accent, palette.accentSecondary,
                    palette.nebula[0], palette.nebula[1], palette.nebula[2], palette.nebulaAnchor };
            for (int i = 0; i < v.length; i++) {
                if (i > 0) sb.append('|');
                sb.append(Integer.toHexString(v[i]));
            }
        }
        ctx.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(songKey, sb.toString()).apply();
    }

    private static void addSwatch(List<Integer> out, @Nullable Palette.Swatch s) {
        if (s != null && !out.contains(s.getRgb())) out.add(s.getRgb());
    }

    /** Clamps saturation and moves lightness until the luminance sits in
     *  the accent window. Luminance grows monotonically with lightness at a
     *  fixed hue/saturation, so a bisection finds the boundary. */
    private static int normalizeAccent(float[] hsl) {
        hsl[1] = Math.max(ACCENT_S_MIN, Math.min(ACCENT_S_MAX, hsl[1]));
        int c = ColorUtils.HSLToColor(hsl);
        double y = ColorUtils.calculateLuminance(c);
        if (y >= ACCENT_Y_MIN && y <= ACCENT_Y_MAX) return c;
        double target = y < ACCENT_Y_MIN ? ACCENT_Y_MIN : ACCENT_Y_MAX;
        float lo = 0f, hi = 1f;
        for (int i = 0; i < 20; i++) {
            float mid = (lo + hi) / 2f;
            hsl[2] = mid;
            if (ColorUtils.calculateLuminance(ColorUtils.HSLToColor(hsl)) < target) lo = mid;
            else hi = mid;
        }
        hsl[2] = (lo + hi) / 2f;
        return ColorUtils.HSLToColor(hsl);
    }

    private static int deriveSecondary(int accent) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(accent, hsl);
        hsl[0] = (hsl[0] + SECONDARY_HUE_SHIFT) % 360f;
        hsl[1] = Math.min(1f, hsl[1] + 0.10f);
        hsl[2] = 0.74f;
        while (ColorUtils.calculateLuminance(ColorUtils.HSLToColor(hsl)) < SECONDARY_Y_MIN
                && hsl[2] < 0.88f) {
            hsl[2] += 0.02f;
        }
        return ColorUtils.HSLToColor(hsl);
    }

    private static int toBlob(int src, float accentHue) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(src, hsl);
        hsl[1] = Math.min(hsl[1], BLOB_S_MAX);
        hsl[2] = Math.max(BLOB_L_MIN, Math.min(BLOB_L_MAX, hsl[2]));
        if (hueDistance(hsl[0], accentHue) < CLOSE_HUE_DEG) {
            hsl[2] = Math.min(hsl[2], BLOB_CLOSE_L_MAX);
        }
        return ColorUtils.HSLToColor(hsl);
    }

    private static float hueOf(int color) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        return hsl[0];
    }

    private static float hueDistance(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }
}
