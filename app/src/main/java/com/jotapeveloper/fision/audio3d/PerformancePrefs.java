package com.jotapeveloper.fision.audio3d;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Performance preferences. Currently only carries the Audio 3D scene
 * toggle (true = real Filament scene, false = flat 2D fallback for
 * modest devices). More perf knobs land here as they're needed.
 */
public class PerformancePrefs {

    private static final String PREFS = "prefs_performance";
    private static final String KEY_AUDIO3D_SCENE = "audio3d_scene_enabled";

    private PerformancePrefs() {}

    public static boolean isAudio3DSceneEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUDIO3D_SCENE, true);
    }

    public static void setAudio3DSceneEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_AUDIO3D_SCENE, enabled).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
