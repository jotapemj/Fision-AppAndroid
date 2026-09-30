package com.jotapeveloper.fision.design;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.StyleRes;

import com.jotapeveloper.fision.R;

import java.util.ArrayList;
import java.util.List;

/** Singleton store for the app-wide accent color pick + the "adopt song
 *  colors" pref. Persists across processes in SharedPreferences and notifies
 *  listeners so live views can react without a full recreate.
 *
 *  A context may also carry a runtime accent (the player adopting the
 *  artwork colors): {@link #resolveAccent(Context)} and
 *  {@link #resolveAccentSecondary(Context)} return it instead of the theme
 *  attribute for that context and any wrapper around it. */
public class AccentColorManager {

    public static final int ACCENT_GREEN  = 0;
    public static final int ACCENT_BLUE   = 1;
    public static final int ACCENT_PURPLE = 2;
    public static final int ACCENT_AMBER  = 3;

    private static final String PREFS = "prefs_design";
    private static final String KEY_ACCENT = "accent_id";
    private static final String KEY_SONG_COLORS = "song_colors_enabled";

    private static AccentColorManager sInstance;

    /** Runtime accent per context: {primary, secondary}. Weak keys so a
     *  destroyed activity never leaks. UI thread only. */
    private static final java.util.WeakHashMap<Context, int[]> sRuntimeAccent =
            new java.util.WeakHashMap<>();

    public static synchronized AccentColorManager get(Context ctx) {
        if (sInstance == null) sInstance = new AccentColorManager(ctx.getApplicationContext());
        return sInstance;
    }

    public interface Listener {
        void onAccentChanged();
    }

    private final SharedPreferences prefs;
    private final List<Listener> listeners = new ArrayList<>();

    private AccentColorManager(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int getAccentId() {
        return prefs.getInt(KEY_ACCENT, ACCENT_GREEN);
    }

    public void setAccentId(int id) {
        if (id < ACCENT_GREEN || id > ACCENT_AMBER) id = ACCENT_GREEN;
        prefs.edit().putInt(KEY_ACCENT, id).apply();
        notifyListeners();
    }

    /** Player adopts the artwork colors (controls, nebula, 3D room). Off:
     *  everything uses the picked accent. */
    public boolean isSongColorsEnabled() {
        return prefs.getBoolean(KEY_SONG_COLORS, false);
    }

    public void setSongColorsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SONG_COLORS, enabled).apply();
        notifyListeners();
    }

    /** Overrides the accent resolved for [ctx] (and wrappers around it). */
    public static void setRuntimeAccent(Context ctx, @ColorInt int primary, @ColorInt int secondary) {
        sRuntimeAccent.put(ctx, new int[] { primary, secondary });
    }

    public static void clearRuntimeAccent(Context ctx) {
        sRuntimeAccent.remove(ctx);
    }

    /** Runtime accent pair for [ctx] or one of its base contexts, else null. */
    private static int[] findRuntimeAccent(Context ctx) {
        if (sRuntimeAccent.isEmpty()) return null;
        Context c = ctx;
        while (c != null) {
            int[] o = sRuntimeAccent.get(c);
            if (o != null) return o;
            if (!(c instanceof android.content.ContextWrapper)) break;
            Context base = ((android.content.ContextWrapper) c).getBaseContext();
            if (base == c) break;
            c = base;
        }
        return null;
    }

    @StyleRes
    public int getOverlayStyle() {
        switch (getAccentId()) {
            case ACCENT_BLUE:   return R.style.ThemeOverlay_Fision_Accent_Blue;
            case ACCENT_PURPLE: return R.style.ThemeOverlay_Fision_Accent_Purple;
            case ACCENT_AMBER:  return R.style.ThemeOverlay_Fision_Accent_Amber;
            case ACCENT_GREEN:
            default:            return R.style.ThemeOverlay_Fision_Accent_Green;
        }
    }

    @ColorInt
    public int getAccentColor(Context ctx) {
        switch (getAccentId()) {
            case ACCENT_BLUE:   return ctx.getColor(R.color.accent_blue);
            case ACCENT_PURPLE: return ctx.getColor(R.color.accent_purple);
            case ACCENT_AMBER:  return ctx.getColor(R.color.accent_amber);
            case ACCENT_GREEN:
            default:            return ctx.getColor(R.color.accent_green);
        }
    }

    @ColorInt
    public int getAccentSecondaryColor(Context ctx) {
        switch (getAccentId()) {
            case ACCENT_BLUE:   return ctx.getColor(R.color.accent_blue_secondary);
            case ACCENT_PURPLE: return ctx.getColor(R.color.accent_purple_secondary);
            case ACCENT_AMBER:  return ctx.getColor(R.color.accent_amber_secondary);
            case ACCENT_GREEN:
            default:            return ctx.getColor(R.color.accent_green_secondary);
        }
    }

    /** Applies the currently selected accent overlay to an Activity's theme.
     *  Must be called from the Activity's onCreate before setContentView so
     *  every widget inflated afterwards inherits the picked colorPrimary.
     *  Also tags the Activity's Window with the accentId that was applied so
     *  {@link #maybeRecreateForAccent(android.app.Activity)} can detect a
     *  stale theme on the next onResume and trigger a recreate. */
    public void applyOverlayTo(android.app.Activity act) {
        int id = getAccentId();
        act.getTheme().applyStyle(getOverlayStyle(), true);
        act.getWindow().getDecorView().setTag(
                R.id.accent_applied_tag, Integer.valueOf(id));
    }

    /** Recreates the Activity when the accent has been changed since the
     *  overlay was applied. Meant to be called from onResume so back-navigation
     *  from Settings picks up the new palette without needing a manual reopen. */
    public void maybeRecreateForAccent(android.app.Activity act) {
        Object tag = act.getWindow().getDecorView().getTag(R.id.accent_applied_tag);
        if (!(tag instanceof Integer)) return;
        if ((Integer) tag != getAccentId()) {
            act.recreate();
        }
    }

    /** Resolves ?attr/colorPrimary against the given context's theme. Prefer
     *  this over ContextCompat.getColor(ctx, R.color.colorPrimary) when the
     *  color must follow the active accent. */
    @ColorInt
    public static int resolveAccent(Context ctx) {
        int[] runtime = findRuntimeAccent(ctx);
        if (runtime != null) return runtime[0];
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorPrimary, tv, true);
        return tv.data;
    }

    /** Resolves ?attr/accentSecondary against the given context's theme. */
    @ColorInt
    public static int resolveAccentSecondary(Context ctx) {
        int[] runtime = findRuntimeAccent(ctx);
        if (runtime != null) return runtime[1];
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(R.attr.accentSecondary, tv, true);
        return tv.data;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) {
            try { l.onAccentChanged(); } catch (Throwable ignored) {}
        }
    }
}
