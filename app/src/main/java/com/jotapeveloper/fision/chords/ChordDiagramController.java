package com.jotapeveloper.fision.chords;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.View;

import androidx.annotation.Nullable;

import com.jotapeveloper.fision.BeatCell;
import com.jotapeveloper.fision.ChordFormatter;
import com.jotapeveloper.fision.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the chord diagram view: instrument and neck-zone selectors
 * (persisted globally), the chord timeline taken from the beat cells, and
 * the stage showing the current and the next chord. The stage advances on
 * the same beat the chord carousel highlights a new chord.
 */
public final class ChordDiagramController {

    private static final String PREFS = "prefs_songs";
    private static final String KEY_INSTRUMENT = "chord_diagram_instrument";
    private static final String KEY_ZONE = "chord_diagram_zone";

    /** Selector order: Piano, Guitar, Ukulele. */
    private static final int PIANO = 0, GUITAR = 1, UKULELE = 2;

    /** One chord change of the song: beat where it starts + raw symbol. */
    private static final class Change {
        final int beat;
        final String raw;
        @Nullable ChordShapes.Position position;
        Change(int beat, String raw) { this.beat = beat; this.raw = raw; }
    }

    private final Context ctx;
    private final SlidingChipSelector instrumentSel;
    private final SlidingChipSelector zoneSel;
    private final ChordStageView stage;
    private final SharedPreferences prefs;

    private final List<Change> changes = new ArrayList<>();
    private int instrument;
    private VoicingPicker.Zone zone;
    private boolean latin, preferFlat;
    private int shift;
    /** Index in [changes] currently shown as "current" (-1 = before the first). */
    private int shownIdx = Integer.MIN_VALUE;
    private int lastBeat = -1;
    @Nullable private ChordShapes shapes;

    /** Seek request from a swipe on the stage: start of the chord change at
     *  beat cell [beatIdx]. */
    public interface SeekListener { void seekToBeat(int beatIdx); }

    @Nullable private SeekListener seekListener;

    public void setSeekListener(@Nullable SeekListener l) { seekListener = l; }

    /** Swipe: jumps to the start of the next (+1) or previous (-1) chord. */
    private void onSwipe(int direction) {
        if (seekListener == null || changes.isEmpty()) return;
        int cur = shownIdx == Integer.MIN_VALUE ? changeAt(Math.max(0, lastBeat)) : shownIdx;
        int target = Math.max(0, Math.min(changes.size() - 1, cur + direction));
        if (target == cur && cur >= 0) return;
        seekListener.seekToBeat(changes.get(target).beat);
    }

    public ChordDiagramController(Context ctx, View root) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        instrumentSel = root.findViewById(R.id.sel_chordInstrument);
        zoneSel = root.findViewById(R.id.sel_chordZone);
        stage = root.findViewById(R.id.stage_chordDiagram);
        stage.setOnSwipeListener(this::onSwipe);

        instrumentSel.setOptions(ctx.getString(R.string.chord_diagram_piano),
                ctx.getString(R.string.chord_diagram_guitar),
                ctx.getString(R.string.chord_diagram_ukulele));
        zoneSel.setOptions(ctx.getString(R.string.chord_diagram_zone_open), "3–7", "7–12",
                ctx.getString(R.string.chord_diagram_zone_auto));
        instrument = clamp(prefs.getInt(KEY_INSTRUMENT, GUITAR), 0, 2);
        zone = VoicingPicker.Zone.values()[clamp(prefs.getInt(KEY_ZONE, 0), 0, 3)];
        instrumentSel.setSelected(instrument, false);
        zoneSel.setSelected(zone.ordinal(), false);
        instrumentSel.setOnSelectedListener(i -> {
            instrument = i;
            prefs.edit().putInt(KEY_INSTRUMENT, i).apply();
            applyInstrument();
        });
        zoneSel.setOnSelectedListener(i -> {
            zone = VoicingPicker.Zone.values()[i];
            prefs.edit().putInt(KEY_ZONE, i).apply();
            recomputeVoicings();
            refresh();
        });
        applyInstrument();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Notation of chord and note names, and the typeface of the carousel. */
    public void setNotation(boolean latin, boolean preferFlat, Typeface typeface) {
        this.latin = latin;
        this.preferFlat = preferFlat;
        stage.setTypeface(typeface);
        refresh();
    }

    /** Display transposition in semitones (Transpose with pitch). */
    public void setShift(int semitones) {
        if (shift == semitones) return;
        shift = semitones;
        recomputeVoicings();
        refresh();
    }

    /** Rebuilds the chord timeline from the carousel cells. */
    public void setCells(List<BeatCell> cells) {
        changes.clear();
        String last = null;
        for (int i = 0; i < cells.size(); i++) {
            String c = cells.get(i).chord;
            if (c == null || c.equals(last)) continue;
            changes.add(new Change(i, c));
            last = c;
        }
        recomputeVoicings();
        shownIdx = Integer.MIN_VALUE;
        if (lastBeat >= 0) onBeat(lastBeat);
    }

    /** Beat highlighted by the carousel; advances the stage on a chord change. */
    public void onBeat(int beatIdx) {
        lastBeat = beatIdx;
        int idx = changeAt(beatIdx);
        if (idx == shownIdx) return;
        boolean known = shownIdx != Integer.MIN_VALUE;
        boolean stepForward = known && idx == shownIdx + 1;
        boolean stepBack = known && idx == shownIdx - 1;
        shownIdx = idx;
        if (stepForward) stage.advance(card(idx), card(idx + 1));
        else if (stepBack) stage.retreat(card(idx), card(idx + 1));
        else stage.show(card(idx), card(idx + 1));
    }

    /** Redraws the current state in place (seek, open, settings change). */
    public void refresh() {
        int idx = shownIdx == Integer.MIN_VALUE ? changeAt(Math.max(0, lastBeat)) : shownIdx;
        shownIdx = idx;
        stage.show(card(idx), card(idx + 1));
    }

    private int changeAt(int beatIdx) {
        int idx = -1;
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).beat <= beatIdx) idx = i;
            else break;
        }
        return idx;
    }

    private void applyInstrument() {
        zoneSel.animate().cancel();
        boolean fretted = instrument != PIANO;
        zoneSel.animate().alpha(fretted ? 1f : 0f).setDuration(200)
                .withStartAction(() -> { if (fretted) zoneSel.setVisibility(View.VISIBLE); })
                .withEndAction(() -> { if (!fretted) zoneSel.setVisibility(View.INVISIBLE); })
                .start();
        if (!fretted) {
            shapes = null;
            stage.setMode(ChordStageView.Mode.PIANO, 0);
            refresh();
            return;
        }
        ChordShapes.Instrument inst = instrument == GUITAR
                ? ChordShapes.Instrument.GUITAR : ChordShapes.Instrument.UKULELE;
        stage.setMode(ChordStageView.Mode.FRETTED, inst.strings);
        shapes = ChordShapes.peek(inst);
        recomputeVoicings();
        refresh();
        if (shapes == null) {
            ChordShapes.load(ctx, inst, loaded -> {
                if (instrument == PIANO || loaded.instrument != inst) return;
                shapes = loaded;
                recomputeVoicings();
                refresh();
            });
        }
    }

    /** Picks a position per chord change, in song order so AUTO can follow
     *  the hand from one chord to the next. */
    private void recomputeVoicings() {
        ChordShapes.Position prev = null;
        for (Change c : changes) {
            c.position = null;
            if (shapes == null) continue;
            ChordShapes.Shape shape = shapes.find(ChordSymbol.parse(c.raw, shift));
            c.position = VoicingPicker.pick(shape, zone, prev);
            if (c.position != null) prev = c.position;
        }
    }

    @Nullable
    private ChordStageView.CardData card(int idx) {
        if (idx < 0 || idx >= changes.size()) {
            // Before the first chord / after the last: an empty card.
            return idx < 0 && !changes.isEmpty()
                    ? new ChordStageView.CardData("—", null, null, new int[0], new String[0])
                    : null;
        }
        Change c = changes.get(idx);
        ChordSymbol sym = ChordSymbol.parse(c.raw, shift);
        String name = sym == null ? "—" : ChordFormatter.format(c.raw, shift, latin, preferFlat);
        String bassLabel = null;
        if (instrument != PIANO && sym != null && sym.bass >= 0 && shapes != null) {
            ChordShapes.Shape shape = shapes.find(sym);
            if (shape != null && !shape.bassShown) {
                bassLabel = ctx.getString(R.string.chord_diagram_bass,
                        ChordFormatter.noteName(sym.bass, latin, preferFlat));
            }
        }
        int[] keys = PianoVoicing.keys(sym);
        String[] names = new String[keys.length];
        for (int i = 0; i < keys.length; i++) names[i] = ChordFormatter.noteName(keys[i], latin, preferFlat);
        return new ChordStageView.CardData(name, bassLabel,
                instrument == PIANO ? null : c.position, keys, names);
    }
}
