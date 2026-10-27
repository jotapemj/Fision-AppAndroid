package com.jotapeveloper.fision.onboarding;

import android.content.Context;
import android.content.SharedPreferences;

/** First-run state of the guided tours (one flag per screen). */
public final class Onboarding {

    public static final String LIBRARY = "library";
    public static final String PLAYER  = "player";

    private static final String PREFS = "prefs_onboarding";

    private Onboarding() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isDone(Context ctx, String tour) {
        if (debugAlwaysShow(ctx)) return false;
        return prefs(ctx).getBoolean("done_" + tour, false);
    }

    public static void markDone(Context ctx, String tour) {
        // While tuning (debug switch on) nothing is stored: the tours show on every visit.
        if (debugAlwaysShow(ctx)) return;
        prefs(ctx).edit().putBoolean("done_" + tour, true).apply();
    }

    // ---------------------------------------------------------------- debug

    private static final String DEBUG_PREFS = "prefs_debug";
    private static final String KEY_ALWAYS = "onboarding_always";

    public static boolean isDebugBuild(Context ctx) {
        return (ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /**
     * Debug builds only: welcome on every app start and tours on every visit
     * (default), or the release behaviour. Kept apart from the tour flags so
     * {@link #resetAll} does not touch it.
     */
    public static boolean debugAlwaysShow(Context ctx) {
        return isDebugBuild(ctx) && ctx.getApplicationContext()
                .getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALWAYS, true);
    }

    public static void setDebugAlwaysShow(Context ctx, boolean on) {
        ctx.getApplicationContext().getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ALWAYS, on).apply();
    }

    /** Every tour shows again on its next chance. */
    public static void resetAll(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }
}
