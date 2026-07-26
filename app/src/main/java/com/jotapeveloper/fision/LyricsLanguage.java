package com.jotapeveloper.fision;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

/**
 * Catalogue of languages exposed on the lyrics picker card. The first entry
 * is always the auto-detect option, whose "code" is null (Whisper decides
 * from vocals.flac). India-related codes (hi/pa/ta) share the same flag.
 */
public final class LyricsLanguage {

    public final String code;              // ISO 639-1, or null for auto-detect
    @StringRes public final int labelRes;
    @DrawableRes public final int flagRes;

    private LyricsLanguage(String code, int labelRes, int flagRes) {
        this.code = code;
        this.labelRes = labelRes;
        this.flagRes = flagRes;
    }

    public boolean isAuto() { return code == null; }

    public static final LyricsLanguage[] LANGUAGES = new LyricsLanguage[] {
            new LyricsLanguage(null, R.string.lyrics_lang_auto, R.drawable.ic_flag_auto),
            new LyricsLanguage("en", R.string.lyrics_lang_en,   R.drawable.flag_gb),
            new LyricsLanguage("es", R.string.lyrics_lang_es,   R.drawable.flag_es),
            new LyricsLanguage("pt", R.string.lyrics_lang_pt,   R.drawable.flag_br),
            new LyricsLanguage("ja", R.string.lyrics_lang_ja,   R.drawable.flag_jp),
            new LyricsLanguage("ko", R.string.lyrics_lang_ko,   R.drawable.flag_kr),
            new LyricsLanguage("hi", R.string.lyrics_lang_hi,   R.drawable.flag_in),
            new LyricsLanguage("fr", R.string.lyrics_lang_fr,   R.drawable.flag_fr),
            new LyricsLanguage("zh", R.string.lyrics_lang_zh,   R.drawable.flag_cn),
            new LyricsLanguage("de", R.string.lyrics_lang_de,   R.drawable.flag_de),
            new LyricsLanguage("it", R.string.lyrics_lang_it,   R.drawable.flag_it),
            new LyricsLanguage("ar", R.string.lyrics_lang_ar,   R.drawable.flag_sa),
            new LyricsLanguage("ru", R.string.lyrics_lang_ru,   R.drawable.flag_ru),
            new LyricsLanguage("tr", R.string.lyrics_lang_tr,   R.drawable.flag_tr),
            new LyricsLanguage("id", R.string.lyrics_lang_id,   R.drawable.flag_id),
            new LyricsLanguage("pa", R.string.lyrics_lang_pa,   R.drawable.flag_in),
            new LyricsLanguage("sv", R.string.lyrics_lang_sv,   R.drawable.flag_se),
            new LyricsLanguage("nl", R.string.lyrics_lang_nl,   R.drawable.flag_nl),
            new LyricsLanguage("vi", R.string.lyrics_lang_vi,   R.drawable.flag_vn),
            new LyricsLanguage("tl", R.string.lyrics_lang_tl,   R.drawable.flag_ph),
            new LyricsLanguage("el", R.string.lyrics_lang_el,   R.drawable.flag_gr),
            new LyricsLanguage("th", R.string.lyrics_lang_th,   R.drawable.flag_th),
            new LyricsLanguage("fa", R.string.lyrics_lang_fa,   R.drawable.flag_ir),
            new LyricsLanguage("he", R.string.lyrics_lang_he,   R.drawable.flag_il),
            new LyricsLanguage("ta", R.string.lyrics_lang_ta,   R.drawable.flag_in),
            new LyricsLanguage("ca", R.string.lyrics_lang_ca,   R.drawable.flag_ca),
            new LyricsLanguage("uk", R.string.lyrics_lang_uk,   R.drawable.flag_ua),
            new LyricsLanguage("pl", R.string.lyrics_lang_pl,   R.drawable.flag_pl),
    };
}
