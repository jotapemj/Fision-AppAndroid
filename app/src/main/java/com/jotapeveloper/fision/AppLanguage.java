package com.jotapeveloper.fision;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import java.util.Locale;

/**
 * UI language through the per-app locale API: on Android 13+ it is the
 * system's per-app language setting; below, AppCompat stores it and applies it
 * to every activity. An empty tag follows the system language.
 */
public final class AppLanguage {

    /** Same order as R.array.lang_entries. */
    public static final String[] TAGS = {
            "", "en", "es", "ca", "uk", "pt-BR", "fr", "de", "it", "ru", "zh-Hans"};

    private static final String LEGACY_PREF = "language";

    private AppLanguage() {}

    /** Index in TAGS of the active language (0 = system). */
    public static int currentIndex() {
        LocaleListCompat list = AppCompatDelegate.getApplicationLocales();
        if (list.isEmpty()) return 0;
        Locale loc = list.get(0);
        if (loc == null) return 0;
        String tag = loc.toLanguageTag();
        for (int i = 1; i < TAGS.length; i++) {
            if (TAGS[i].equalsIgnoreCase(tag)) return i;
        }
        // Tag with extra subtags (e.g. zh-Hans-CN, pt-BR-x): match the base.
        for (int i = 1; i < TAGS.length; i++) {
            Locale t = Locale.forLanguageTag(TAGS[i]);
            if (t.getLanguage().equals(loc.getLanguage())) return i;
        }
        return 0;
    }

    /** Applies [tag] ("" = system). AppCompat recreates the open activities. */
    public static void set(String tag) {
        AppCompatDelegate.setApplicationLocales(tag.isEmpty()
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag));
    }

    /** One-time move of the old "language" preference to the per-app locale.
     *  Call after Activity.onCreate. */
    public static void migrateLegacy(Context ctx) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
        if (!sp.contains(LEGACY_PREF)) return;
        String old = sp.getString(LEGACY_PREF, "");
        sp.edit().remove(LEGACY_PREF).apply();
        if (!AppCompatDelegate.getApplicationLocales().isEmpty()) return;
        for (int i = 1; i < TAGS.length; i++) {
            if (TAGS[i].equalsIgnoreCase(old)) { set(TAGS[i]); return; }
        }
    }
}
