package com.jotapeveloper.fision.brand;

import android.graphics.Path;

import androidx.core.graphics.PathParser;

/**
 * "FISION" wordmark as artwork: letter outlines in 1000-unit space, y down,
 * cap top at 0 and baseline at {@link #CAP}. Same shapes as the toolbar
 * drawable ic_fision_wordmark; no font file is involved.
 */
final class FisionWordmark {

    /** Cap height in path units. */
    static final float CAP = 727f;

    private static final String[][] GLYPHS = {
            {"F", "705", "M189 727V127H680V0H63V727ZM650 426V315H282V426Z"},
            {"I", "267", "M197 727V0H70V727Z"},
            {"S", "880", "M608 727C760 727 834 636 834 514C834 373 755 304 608 304H275C219 304 184 271 184 216C184 161 218 127 276 127H798V0H276C131 0 52 87 52 204C52 324 137 411 274 411H608C676 411 714 443 714 505C714 562 676 600 608 600H63V727Z"},
            {"O", "968", "M572 727C779 727 928 567 928 356C928 145 782 0 572 0H395C192 0 42 142 42 356C42 567 192 727 395 727ZM395 600C264 600 169 499 169 362C169 224 265 127 395 127H572C706 127 801 225 801 362C801 499 706 600 572 600Z"},
            {"N", "873", "M692 709C710 727 729 736 750 736C787 736 813 708 813 660V-6H692V526L179 17C160 -2 144 -9 125 -9C85 -9 60 20 60 67V727H181V195Z"},
    };

    private FisionWordmark() {}

    /** Outline of [ch] (one of F, I, S, O, N), or an empty path. */
    static Path glyph(char ch) {
        for (String[] g : GLYPHS) {
            if (g[0].charAt(0) == ch) {
                Path p = PathParser.createPathFromPathData(g[2]);
                return p != null ? p : new Path();
            }
        }
        return new Path();
    }

    /** Advance width of [ch] in path units. */
    static float advance(char ch) {
        for (String[] g : GLYPHS) if (g[0].charAt(0) == ch) return Float.parseFloat(g[1]);
        return 0f;
    }
}
