package com.jotapeveloper.fision;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Preset-based avatar editor. Loads PNGs from assets/avatars/, groups them by
 * instrument/gender, applies subject tint and background colour, previews the
 * composite in a circle, and returns a 512×512 JPEG on apply.
 *
 * File naming convention (assets/avatars/):
 *   avatar_<instrument>_<male|female>.png
 *   avatar_base_<male|female>.png
 * Files with no gender suffix (e.g. avatar_trumpet.png) are treated as male
 * so a stray asset never becomes unreachable.
 */
public class AvatarStudioDialog {

    public interface Callback {
        /** Returns the 512×512 JPEG bytes composed by the studio. */
        void onAvatarComposed(byte[] jpegBytes);
        /** User asked for the classic device-gallery path. */
        void onPickFromDevice();
        /** Studio closed by Apply, Cancel or back (not by the gallery path). */
        default void onClosed() {}
    }

    // Subject tint palette. First entry (BASE) means "no tint": preserves the
    // raw PNG colour so the studio can always return to the neutral state.
    private static final int BASE_GREY = 0xFFCFCFCF;
    private static final int[] SUBJECT_COLORS = new int[] {
            BASE_GREY,
            0xFFFFFFFF, 0xFFFFD54F, 0xFFFF8A65, 0xFFE57373, 0xFFF48FB1,
            0xFFB39DDB, 0xFF64B5F6, 0xFF27B397, 0xFFAED581, 0xFF8D6E63,
            0xFF212121
    };
    private static final int[] BG_COLORS = new int[] {
            0xFFFFF8E1, 0xFFFFF176, 0xFFFFB74D, 0xFFF8BBD0, 0xFFCE93D8,
            0xFF90CAF9, 0xFF80DEEA, 0xFF27B397, 0xFFC5E1A5, 0xFFD7CCC8,
            0xFF616161, 0xFF0A0A0A
    };

    private static final String ASSETS_DIR = "avatars";
    private static final int OUTPUT_SIZE = 512;

    // SharedPreferences keys: restore the last studio session on reopen.
    private static final String PREFS = "avatar_studio";
    private static final String KEY_INSTRUMENT   = "last_instrument";
    private static final String KEY_FEMALE       = "last_female";
    private static final String KEY_SUBJECT_COL  = "last_subject_color";
    private static final String KEY_BG_COL       = "last_bg_color";

    /** One instrument entry, paired with the assets available for it. */
    private static class Entry {
        final String instrument;   // "base", "vocals", "piano", ...
        String male;               // asset filename or null
        String female;             // asset filename or null
        Entry(String i) { this.instrument = i; }
    }

    private final Activity host;
    private final Callback callback;
    private final List<Entry> entries = new ArrayList<>();
    private int entryIdx = 0;
    private boolean female = false;
    private int subjectColor = BASE_GREY;
    private int bgColor = BG_COLORS[0];

    private ImageView ivBg;
    private ImageView ivImage;
    private TextView tvInstrument;

    public AvatarStudioDialog(Activity host, Callback cb) {
        this.host = host;
        this.callback = cb;
        loadEntries();
        restoreLastConfig();
    }

    /** Restores the previous studio session so reopening the dialog does not
     *  reset the user's choice. Falls back to defaults for missing keys. */
    private void restoreLastConfig() {
        android.content.SharedPreferences sp =
                host.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String lastInstrument = sp.getString(KEY_INSTRUMENT, null);
        if (lastInstrument != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (lastInstrument.equals(entries.get(i).instrument)) {
                    entryIdx = i;
                    break;
                }
            }
        }
        female       = sp.getBoolean(KEY_FEMALE, false);
        subjectColor = sp.getInt(KEY_SUBJECT_COL, BASE_GREY);
        bgColor      = sp.getInt(KEY_BG_COL,     BG_COLORS[0]);
    }

    private void persistLastConfig() {
        if (entries.isEmpty()) return;
        host.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_INSTRUMENT,  entries.get(entryIdx).instrument)
                .putBoolean(KEY_FEMALE,     female)
                .putInt(KEY_SUBJECT_COL,    subjectColor)
                .putInt(KEY_BG_COL,         bgColor)
                .apply();
    }

    /** Enumerates PNGs in assets/avatars/, groups them by instrument. */
    private void loadEntries() {
        AssetManager am = host.getAssets();
        String[] names;
        try { names = am.list(ASSETS_DIR); }
        catch (IOException e) { names = null; }
        if (names == null || names.length == 0) return;

        // Preserve directory order so JP can control the sequence by naming.
        java.util.Map<String, Entry> byInstrument = new java.util.LinkedHashMap<>();
        for (String n : names) {
            if (!n.toLowerCase().endsWith(".png")) continue;
            String base = n.substring(0, n.length() - 4);
            if (!base.startsWith("avatar_")) continue;
            String rest = base.substring("avatar_".length());
            String instrument;
            String gender = "male"; // default for files without a suffix
            if (rest.endsWith("_male")) {
                instrument = rest.substring(0, rest.length() - "_male".length());
            } else if (rest.endsWith("_female")) {
                instrument = rest.substring(0, rest.length() - "_female".length());
                gender = "female";
            } else {
                instrument = rest;
            }
            Entry e = byInstrument.get(instrument);
            if (e == null) { e = new Entry(instrument); byInstrument.put(instrument, e); }
            if ("female".equals(gender)) e.female = n; else e.male = n;
        }
        entries.addAll(byInstrument.values());
    }

    public void show() {
        if (entries.isEmpty()) {
            // No presets available → fall through to the classic picker.
            if (callback != null) callback.onPickFromDevice();
            return;
        }

        View v = host.getLayoutInflater().inflate(R.layout.avatar_studio_dialog, null);
        ivBg          = v.findViewById(R.id.iv_avatarStudioBg);
        ivImage       = v.findViewById(R.id.iv_avatarStudioImage);
        tvInstrument  = v.findViewById(R.id.tv_avatarStudioInstrument);
        MaterialButton bPrev = v.findViewById(R.id.b_avatarStudioPrev);
        MaterialButton bNext = v.findViewById(R.id.b_avatarStudioNext);
        MaterialButton bMale   = v.findViewById(R.id.b_avatarStudioMale);
        MaterialButton bFemale = v.findViewById(R.id.b_avatarStudioFemale);
        MaterialButtonToggleGroup tgGender = v.findViewById(R.id.tg_avatarStudioGender);
        RecyclerView rvSubject = v.findViewById(R.id.rv_avatarStudioSubjectColors);
        RecyclerView rvBg      = v.findViewById(R.id.rv_avatarStudioBgColors);
        MaterialButton bFromDevice = v.findViewById(R.id.b_avatarStudioFromDevice);
        MaterialButton bCancel = v.findViewById(R.id.b_avatarStudioCancel);
        MaterialButton bApply  = v.findViewById(R.id.b_avatarStudioApply);

        AlertDialog dlg = new AlertDialog.Builder(host, R.style.Theme_Fision_BlurDialog)
                .setView(v)
                .setCancelable(true)
                .create();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // Palettes: seeded with the restored colours
        int subjInitial = indexOfColor(SUBJECT_COLORS, subjectColor);
        int bgInitial   = indexOfColor(BG_COLORS,      bgColor);
        rvSubject.setLayoutManager(new LinearLayoutManager(host,
                LinearLayoutManager.HORIZONTAL, false));
        AvatarColorAdapter subjAdapter = new AvatarColorAdapter(
                SUBJECT_COLORS, subjInitial, c -> {
            subjectColor = c; renderPreview();
        });
        rvSubject.setAdapter(subjAdapter);
        rvSubject.scrollToPosition(Math.max(0, subjInitial));

        rvBg.setLayoutManager(new LinearLayoutManager(host,
                LinearLayoutManager.HORIZONTAL, false));
        AvatarColorAdapter bgAdapter = new AvatarColorAdapter(
                BG_COLORS, bgInitial, c -> {
            bgColor = c; renderPreview();
        });
        rvBg.setAdapter(bgAdapter);
        rvBg.scrollToPosition(Math.max(0, bgInitial));

        // Gender toggle reflects the restored value
        tgGender.check(female ? R.id.b_avatarStudioFemale : R.id.b_avatarStudioMale);
        tgGender.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            female = (checkedId == R.id.b_avatarStudioFemale);
            renderPreview();
        });

        bPrev.setOnClickListener(x -> {
            entryIdx = (entryIdx - 1 + entries.size()) % entries.size();
            renderPreview();
        });
        bNext.setOnClickListener(x -> {
            entryIdx = (entryIdx + 1) % entries.size();
            renderPreview();
        });

        final boolean[] toDevice = {false};
        bFromDevice.setOnClickListener(x -> {
            toDevice[0] = true;
            dlg.dismiss();
            if (callback != null) callback.onPickFromDevice();
        });

        bCancel.setOnClickListener(x -> dlg.dismiss());
        bApply.setOnClickListener(x -> {
            byte[] jpeg = composeJpeg();
            persistLastConfig();
            if (jpeg != null && callback != null) callback.onAvatarComposed(jpeg);
            dlg.dismiss();
        });

        applyActivityBlur();
        dlg.setOnDismissListener(d -> {
            removeActivityBlur();
            if (!toDevice[0] && callback != null) callback.onClosed();
        });
        // Apply the blur again once the window is attached: dismissing the
        // account dialog (which owns its own blur) fires removeActivityBlur
        // asynchronously and would strip the studio blur one frame after it
        // was set. Re-applying on show guarantees the effect stays visible.
        dlg.setOnShowListener(d -> applyActivityBlur());
        dlg.show();

        renderPreview();
    }

    /** Paints the circular preview with the current selection. */
    private void renderPreview() {
        Entry e = entries.get(entryIdx);
        // Update instrument label
        tvInstrument.setText(instrumentLabel(e.instrument));
        // Background circle
        ivBg.setImageDrawable(circleDrawable(bgColor));
        // Foreground avatar with tint
        Bitmap src = loadEntryBitmap(e);
        if (src != null) {
            ivImage.setImageBitmap(src);
            ivImage.setColorFilter(makeTintFilter(subjectColor));
        } else {
            ivImage.setImageDrawable(null);
            ivImage.clearColorFilter();
        }
    }

    private static int indexOfColor(int[] palette, int color) {
        for (int i = 0; i < palette.length; i++) if (palette[i] == color) return i;
        return 0;
    }

    private String instrumentLabel(String instrument) {
        int resId = host.getResources().getIdentifier(
                "avatar_instrument_" + instrument, "string", host.getPackageName());
        if (resId != 0) return host.getString(resId);
        // Fallback: capitalise the asset name
        if (instrument.isEmpty()) return "";
        return Character.toUpperCase(instrument.charAt(0)) + instrument.substring(1);
    }

    private Bitmap loadEntryBitmap(Entry e) {
        String name = female && e.female != null ? e.female
                    : e.male   != null ? e.male
                    : e.female;
        if (name == null) return null;
        try (InputStream is = host.getAssets().open(ASSETS_DIR + "/" + name)) {
            return BitmapFactory.decodeStream(is);
        } catch (IOException ex) {
            return null;
        }
    }

    /** Returns a ColorFilter that maps neutral grey (#CFCFCF) to the target
     *  colour. Preserves alpha, so the transparent PNG borders stay clean.
     *  When the target is BASE_GREY the filter is null (no tint). */
    private android.graphics.ColorFilter makeTintFilter(int color) {
        if (color == BASE_GREY) return null;
        float r = Color.red(color)   / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color)  / 255f;
        // Each output channel = input * ratio. Since the base is grey and
        // roughly monochromatic, scaling each channel independently produces
        // the requested hue while preserving anti-aliasing.
        ColorMatrix m = new ColorMatrix(new float[] {
                r, 0, 0, 0, 0,
                0, g, 0, 0, 0,
                0, 0, b, 0, 0,
                0, 0, 0, 1, 0
        });
        return new ColorMatrixColorFilter(m);
    }

    /** Solid-colour circle for the background of the preview. */
    private android.graphics.drawable.Drawable circleDrawable(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** Composes the final 512×512 JPEG: coloured circle background + tinted
     *  avatar centred inside. Ready to feed AvatarManager.uploadAvatar. */
    private byte[] composeJpeg() {
        Bitmap out = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Background circle
        p.setColor(bgColor);
        c.drawCircle(OUTPUT_SIZE / 2f, OUTPUT_SIZE / 2f, OUTPUT_SIZE / 2f, p);
        // Foreground avatar
        Entry e = entries.get(entryIdx);
        Bitmap src = loadEntryBitmap(e);
        if (src != null) {
            Paint pImg = new Paint(Paint.ANTI_ALIAS_FLAG);
            pImg.setFilterBitmap(true);
            android.graphics.ColorFilter tint = makeTintFilter(subjectColor);
            if (tint != null) pImg.setColorFilter(tint);
            RectF dst = new RectF(0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
            c.drawBitmap(src, null, dst, pImg);
        }
        // Encode to JPEG (~40-80KB)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 88, baos);
        return baos.toByteArray();
    }

    private void applyActivityBlur() {
        BlurDialogs.applyBlur(host);
    }

    private void removeActivityBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            host.getWindow().getDecorView().setRenderEffect(null);
        }
    }
}
