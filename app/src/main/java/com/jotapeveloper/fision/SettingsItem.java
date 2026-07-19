package com.jotapeveloper.fision;

/** Data model for the settings list. Three flavours share the same class
 *  and are disambiguated by the {@link Type} field. */
public class SettingsItem {

    public enum Type {
        HEADER,          // Account card (avatar + name + email + chevron)
        SECTION_TITLE,   // Section title above a group of cards
        CARD             // Standard card (icon tile + title + subtitle + chevron)
    }

    public final Type type;
    public final String id;

    // Card fields
    public int iconRes;
    public CharSequence title;
    public CharSequence subtitle;
    public boolean showChevron = true;
    public boolean externalLink = false;
    public int iconTintOverride = 0; // 0 = use colorPrimary default

    // Header fields
    public String user;
    public String email;

    private SettingsItem(Type t, String id) {
        this.type = t;
        this.id = id;
    }

    public static SettingsItem header(String id, String user, String email) {
        SettingsItem s = new SettingsItem(Type.HEADER, id);
        s.user = user;
        s.email = email;
        return s;
    }

    public static SettingsItem section(String id, CharSequence title) {
        SettingsItem s = new SettingsItem(Type.SECTION_TITLE, id);
        s.title = title;
        return s;
    }

    public static SettingsItem card(String id, int iconRes,
                                    CharSequence title, CharSequence subtitle) {
        SettingsItem s = new SettingsItem(Type.CARD, id);
        s.iconRes = iconRes;
        s.title = title;
        s.subtitle = subtitle;
        return s;
    }
}
