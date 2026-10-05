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
 * handler untouched. On the main thread (where the display listener runs)
 * the drop happens in a guard around the message loop, so the app keeps
 * running instead of being left with a dead main thread.
 *
 * The upstream null-guard fix only landed in Filament 1.72.0, which is binary
 * incompatible with every released SceneView (Utils.init() became @JvmStatic),
 * so the guard lives here instead of in the library.
 */
public class FisionApplication extends Application {

    private static final String TAG = "FisionApplication";

    /** Notified when the app enters (any activity started) or leaves (none
     *  started) the foreground. */
    public interface ForegroundListener { void onAppForegroundChanged(boolean foreground); }

    private static int startedActivities = 0;
    private static ForegroundListener foregroundListener;

    /** True while at least one activity of the app is started (visible). */
    public static boolean isInForeground() { return startedActivities > 0; }

    public static void setForegroundListener(ForegroundListener l) { foregroundListener = l; }

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashFilter();
        installMainLoopGuard();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(android.app.Activity a) {
                if (++startedActivities == 1 && foregroundListener != null) {
                    foregroundListener.onAppForegroundChanged(true);
                }
            }
            @Override public void onActivityStopped(android.app.Activity a) {
                startedActivities = Math.max(0, startedActivities - 1);
                if (startedActivities == 0 && foregroundListener != null) {
                    foregroundListener.onAppForegroundChanged(false);
                }
            }
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityResumed(android.app.Activity a) {}
            @Override public void onActivityPaused(android.app.Activity a) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(android.app.Activity a) {}
        });
    }

    /** (Re)installs the process-wide crash filter. Called from onCreate and
     *  re-asserted at SceneView teardown: a debugger attached via Android
     *  Studio can replace the default uncaught-exception handler, letting a
     *  second DisplayHelper race event reach the system handler. */
    public static void installCrashFilter() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // Only for background threads: on the main thread the exception
            // has already ended the message loop, and dropping it here would
            // leave a frozen process. The main thread is covered by
            // installMainLoopGuard instead.
            if (thread != android.os.Looper.getMainLooper().getThread()
                    && isFilamentDisplayHelperNpe(throwable)) {
                Log.w(TAG, "Dropped known Filament DisplayHelper race (filament#9352)", throwable);
                return;
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    /** Runs the main message loop nested inside a guard: when the known
     *  DisplayHelper NPE escapes a message (the display listener runs on the
     *  main looper), it is logged and the loop resumes. Any other throwable
     *  is rethrown and crashes the app as usual. */
    private static void installMainLoopGuard() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            while (true) {
                try {
                    android.os.Looper.loop();
                    return;
                } catch (Throwable t) {
                    if (!isFilamentDisplayHelperNpe(t)) {
                        if (t instanceof RuntimeException) throw (RuntimeException) t;
                        if (t instanceof Error) throw (Error) t;
                        throw new RuntimeException(t);
                    }
                    Log.w(TAG, "Dropped known Filament DisplayHelper race (filament#9352)", t);
                }
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
