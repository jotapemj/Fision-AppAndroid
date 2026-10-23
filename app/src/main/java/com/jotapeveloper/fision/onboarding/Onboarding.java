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
        return prefs(ctx).getBoolean("done_" + tour, false);
    }

    public static void markDone(Context ctx, String tour) {
        // Debug builds never store it: the tours show on every visit while they are tuned.
        if ((ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) return;
        prefs(ctx).edit().putBoolean("done_" + tour, true).apply();
    }

    /** Every tour shows again on its next chance. */
    public static void resetAll(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }
}
