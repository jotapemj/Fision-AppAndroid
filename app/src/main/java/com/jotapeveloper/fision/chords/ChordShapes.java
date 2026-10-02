package com.jotapeveloper.fision.chords;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fretted chord shapes for one instrument, read from the chords-db JSON in
 * assets/chords (MIT, David Rubert). Indexed by root pitch class, quality
 * and bass so a {@link ChordSymbol} resolves in O(1).
 */
public final class ChordShapes {

    private static final String TAG = "ChordShapes";

    public enum Instrument {
        GUITAR("chords/guitar.json", 6),
        UKULELE("chords/ukulele.json", 4);

        final String asset;
        public final int strings;
        Instrument(String asset, int strings) { this.asset = asset; this.strings = strings; }
    }

    /** One way of playing a chord. Frets are relative to [baseFret]
     *  (1 = baseFret), 0 = open string, -1 = muted. */
    public static final class Position {
        public final int[] frets;
        public final int[] fingers;
        public final int baseFret;
        public final int[] barres;
        /** Lowest absolute fretted fret (0 when every string is open). */
        public final int handFret;
        /** Rough playing cost: fingers down, barre, stretch, muted strings. */
        public final float difficulty;

        Position(int[] frets, int[] fingers, int baseFret, int[] barres) {
            this.frets = frets;
            this.fingers = fingers;
            this.baseFret = baseFret;
            this.barres = barres;
            int lo = Integer.MAX_VALUE, hi = 0, muted = 0;
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (int i = 0; i < frets.length; i++) {
                if (frets[i] < 0) { muted++; continue; }
                if (frets[i] == 0) continue;
                int abs = baseFret + frets[i] - 1;
                lo = Math.min(lo, abs);
                hi = Math.max(hi, abs);
                if (fingers != null && i < fingers.length && fingers[i] > 0) used.add(fingers[i]);
            }
            handFret = lo == Integer.MAX_VALUE ? 0 : lo;
            int span = lo == Integer.MAX_VALUE ? 0 : hi - lo;
            difficulty = used.size() + (barres.length > 0 ? 1.5f : 0f) + span * 0.5f + muted * 0.3f;
        }
    }

    /** Positions for a chord; [bassShown] is false when only the chord
     *  without its inversion bass exists in the data. */
    public static final class Shape {
        public final List<Position> positions;
        public final boolean bassShown;
        Shape(List<Position> positions, boolean bassShown) {
            this.positions = positions;
            this.bassShown = bassShown;
        }
    }

    public interface LoadCallback { void onLoaded(ChordShapes shapes); }

    private static final Map<Instrument, ChordShapes> CACHE = new HashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public final Instrument instrument;
    private final Map<String, List<Position>> index = new HashMap<>();

    private ChordShapes(Instrument instrument) { this.instrument = instrument; }

    /** Loaded shapes, or null while not loaded yet. */
    @Nullable
    public static synchronized ChordShapes peek(Instrument instrument) {
        return CACHE.get(instrument);
    }

    /** Loads the instrument data off the main thread (once) and delivers it
     *  on the main thread. */
    public static void load(Context ctx, Instrument instrument, LoadCallback cb) {
        ChordShapes ready = peek(instrument);
        Handler main = new Handler(Looper.getMainLooper());
        if (ready != null) { main.post(() -> cb.onLoaded(ready)); return; }
        Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            ChordShapes s = peek(instrument);
            if (s == null) {
                s = new ChordShapes(instrument);
                try (Reader r = new InputStreamReader(app.getAssets().open(instrument.asset),
                        StandardCharsets.UTF_8)) {
                    s.parse(JsonParser.parseReader(r).getAsJsonObject());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load " + instrument.asset, e);
                }
                synchronized (ChordShapes.class) { CACHE.put(instrument, s); }
            }
            final ChordShapes done = s;
            main.post(() -> cb.onLoaded(done));
        });
    }

    private void parse(JsonObject root) {
        JsonObject chords = root.getAsJsonObject("chords");
        for (Map.Entry<String, JsonElement> e : chords.entrySet()) {
            for (JsonElement ce : e.getValue().getAsJsonArray()) {
                JsonObject c = ce.getAsJsonObject();
                int pc = ChordSymbol.pitchClass(c.get("key").getAsString(), new int[1]);
                if (pc < 0) continue;
                String suffix = c.get("suffix").getAsString();
                String quality = suffix;
                int bass = -1;
                int slash = suffix.indexOf('/');
                if (slash >= 0) {
                    String q = suffix.substring(0, slash);
                    quality = q.isEmpty() ? ChordSymbol.MAJOR : q.equals("m") ? ChordSymbol.MINOR : q;
                    bass = ChordSymbol.pitchClass(suffix.substring(slash + 1), new int[1]);
                    if (bass < 0 || bass == pc) bass = -1;
                }
                List<Position> list = new ArrayList<>();
                for (JsonElement pe : c.getAsJsonArray("positions")) {
                    JsonObject p = pe.getAsJsonObject();
                    list.add(new Position(ints(p.getAsJsonArray("frets")),
                            p.has("fingers") ? ints(p.getAsJsonArray("fingers")) : null,
                            p.has("baseFret") ? p.get("baseFret").getAsInt() : 1,
                            p.has("barres") ? ints(p.getAsJsonArray("barres")) : new int[0]));
                }
                if (!list.isEmpty()) index.put(key(pc, quality, bass), Collections.unmodifiableList(list));
            }
        }
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
        return out;
    }

    private static String key(int root, String quality, int bass) {
        return root + "|" + quality + "|" + bass;
    }

    /** Shape for [c]: exact match, else without the inversion bass, else the
     *  plain triad. Null when nothing matches. */
    @Nullable
    public Shape find(@Nullable ChordSymbol c) {
        if (c == null) return null;
        List<Position> p = index.get(key(c.root, c.quality, c.bass));
        if (p != null) return new Shape(p, true);
        p = index.get(key(c.root, c.quality, -1));
        if (p == null) {
            String triad = c.quality.startsWith("m") && !c.quality.startsWith("maj")
                    ? ChordSymbol.MINOR : ChordSymbol.MAJOR;
            p = index.get(key(c.root, triad, -1));
        }
        return p == null ? null : new Shape(p, c.bass < 0);
    }
}
