package com.jotapeveloper.fision.cloud;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Application-wide foreground tracker. Counts started Activities to decide
 *  whether the app is in the foreground and dismisses pending "song ready"
 *  notifications the moment the user returns to any app screen. */
public class AppForegroundState {

    public interface Listener { void onEnteredForeground(); }

    private static AppForegroundState INSTANCE;

    public static synchronized AppForegroundState get(Context ctx) {
        if (INSTANCE == null) {
            INSTANCE = new AppForegroundState(ctx.getApplicationContext());
        }
        return INSTANCE;
    }

    private final Context appCtx;
    private int startedCount = 0;
    private final Set<Integer> pendingDismissIds = new HashSet<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private AppForegroundState(Context ctx) {
        this.appCtx = ctx;
        if (ctx instanceof Application) {
            ((Application) ctx).registerActivityLifecycleCallbacks(new Cbs());
        } else if (ctx.getApplicationContext() instanceof Application) {
            ((Application) ctx.getApplicationContext())
                    .registerActivityLifecycleCallbacks(new Cbs());
        }
    }

    public synchronized boolean isInForeground() { return startedCount > 0; }

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** Registers a notification id that should be dismissed the next time the
     *  app comes back to the foreground. Called by the download service when
     *  it posts a "song ready" notification while the user is elsewhere. */
    public synchronized void trackReadyNotif(int notifId) {
        pendingDismissIds.add(notifId);
    }

    private synchronized void drainAndDismiss() {
        if (pendingDismissIds.isEmpty()) return;
        NotificationManager nm = (NotificationManager)
                appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            for (Integer id : pendingDismissIds) {
                try { nm.cancel(id); } catch (Throwable ignored) {}
            }
        }
        pendingDismissIds.clear();
    }

    private class Cbs implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityStarted(@NonNull Activity a) {
            boolean wasBackground;
            synchronized (AppForegroundState.this) {
                wasBackground = startedCount == 0;
                startedCount++;
            }
            if (wasBackground) {
                drainAndDismiss();
                for (Listener l : listeners) {
                    try { l.onEnteredForeground(); } catch (Throwable ignored) {}
                }
            }
        }
        @Override public void onActivityStopped(@NonNull Activity a) {
            synchronized (AppForegroundState.this) {
                if (startedCount > 0) startedCount--;
            }
        }
        @Override public void onActivityCreated(@NonNull Activity a, Bundle b) {}
        @Override public void onActivityResumed(@NonNull Activity a) {}
        @Override public void onActivityPaused(@NonNull Activity a) {}
        @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
        @Override public void onActivityDestroyed(@NonNull Activity a) {}
    }
}
