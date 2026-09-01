package com.jotapeveloper.fision;

import android.app.Application;
import android.util.Log;

/**
 * Process-wide default uncaught-exception handler. Swallows a single known
 * harmless race inside Filament's DisplayHelper (google/filament#9352): a
 * display-change event can be dispatched after the SceneView renderer has
 * already been disposed, producing an NPE in DisplayHelper.updateDisplayInfo.
 * The event only refreshes the display refresh-rate of a dead renderer, so
 * dropping it is safe. Every other exception is delegated to the previous
 * handler untouched.
 *
 * The upstream null-guard fix only landed in Filament 1.72.0, which is binary
 * incompatible with every released SceneView (Utils.init() became @JvmStatic),
 * so the guard lives here instead of in the library.
 */
public class FisionApplication extends Application {

    private static final String TAG = "FisionApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (isFilamentDisplayHelperNpe(throwable)) {
                Log.w(TAG, "Dropped known Filament DisplayHelper race (filament#9352)", throwable);
                return;
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static boolean isFilamentDisplayHelperNpe(Throwable t) {
        if (!(t instanceof NullPointerException)) return false;
        for (StackTraceElement e : t.getStackTrace()) {
            if ("com.google.android.filament.android.DisplayHelper".equals(e.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
