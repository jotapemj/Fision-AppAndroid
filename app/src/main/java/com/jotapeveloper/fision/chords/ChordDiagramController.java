package com.jotapeveloper.fision.chords;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.jotapeveloper.fision.BeatCell;
import com.jotapeveloper.fision.ChordFormatter;
import com.jotapeveloper.fision.FadingEdgeRecyclerView;
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
    private static final String KEY_VIEW = "chord_diagram_view";   // 0 diagrams, 1 grid

    // Grid: the current row sits this far down the view; a drag browses it
    // freely and a magnet brings it back (near: at once; far: after a wait,
    // like the carousel).
    private static final float GRID_FOCUS = 0.30f;
    private static final long MAGNET_BASE_MS = 1500L, MAGNET_PER_CELL_MS = 100L,
            MAGNET_MAX_MS = 4000L;
    private static final long PANE_OUT_MS = 160L, PANE_IN_MS = 240L;
    private static final int GRID_GLIDE_MS = 650;
    private static final android.view.animation.Interpolator GRID_INTERPOLATOR =
            new androidx.interpolator.view.animation.FastOutSlowInInterpolator();

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

    private final SlidingChipSelector viewSel;
    private final View diagramPane;
    private final RecyclerView grid;
    private final ChordGridAdapter gridAdapter;
    private final GridLayoutManager gridLm;
    private boolean gridMode;
    private boolean gridBrowsing;
    private int gridRow = -1;
    private final Runnable gridMagnet = () -> {
        gridBrowsing = false;
        gridRow = -1;
        followGrid(true);
    };

    /** Seek request from a swipe on the stage or a tap on the grid: start of
     *  beat cell [beatIdx]. */
    public interface SeekListener { void seekToBeat(int beatIdx); }

    /** Diagrams / grid switch (the grid replaces the chord carousel). */
    public interface ViewModeListener { void onGridModeChanged(boolean grid); }

    @Nullable private SeekListener seekListener;
    @Nullable private ViewModeListener viewModeListener;

    public void setSeekListener(@Nullable SeekListener l) { seekListener = l; }

    public void setViewModeListener(@Nullable ViewModeListener l) { viewModeListener = l; }

    public boolean isGridMode() { return gridMode; }

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

        viewSel = root.findViewById(R.id.sel_chordView);
        diagramPane = root.findViewById(R.id.ll_chordDiagramPane);
        grid = root.findViewById(R.id.rv_chordGrid);
        gridAdapter = new ChordGridAdapter(ctx);
        gridAdapter.setOnCellClickListener(cell -> {
            if (seekListener != null) seekListener.seekToBeat(cell);
        });
        gridLm = new GridLayoutManager(ctx, gridAdapter.getColumns());
        grid.setLayoutManager(gridLm);
        grid.setAdapter(gridAdapter);
        grid.setItemAnimator(null);
        float density = ctx.getResources().getDisplayMetrics().density;
        grid.addItemDecoration(new ChordGridAdapter.BarDecoration(gridAdapter, density));
        if (grid instanceof FadingEdgeRecyclerView) {
            // Short at the top, so the rows right under the selector stay readable.
            ((FadingEdgeRecyclerView) grid).setFadeLengthsPx(Math.round(24f * density),
                    Math.round(48f * density));
        }
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    gridBrowsing = true;
                    grid.removeCallbacks(gridMagnet);
                } else if (state == RecyclerView.SCROLL_STATE_IDLE && gridBrowsing) {
                    scheduleGridMagnet();
                }
            }
        });
        viewSel.setOptions(ctx.getString(R.string.chord_view_diagrams), ctx.getString(R.string.chord_view_grid));
        gridMode = prefs.getInt(KEY_VIEW, 0) == 1;
        viewSel.setSelected(gridMode ? 1 : 0, false);
        diagramPane.setVisibility(gridMode ? View.INVISIBLE : View.VISIBLE);
        grid.setVisibility(gridMode ? View.VISIBLE : View.GONE);
        viewSel.setOnSelectedListener(i -> setGridMode(i == 1));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Switches between the diagrams and the grid with a crossfade. */
    private void setGridMode(boolean on) {
        if (gridMode == on) return;
        gridMode = on;
        prefs.edit().putInt(KEY_VIEW, on ? 1 : 0).apply();
        View out = on ? diagramPane : grid;
        View in = on ? grid : diagramPane;
        float slide = 14f * ctx.getResources().getDisplayMetrics().density;
        out.animate().cancel();
        out.animate().alpha(0f).translationY(slide).setDuration(PANE_OUT_MS)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    out.setVisibility(out == grid ? View.GONE : View.INVISIBLE);
                    out.setAlpha(1f);
                    out.setTranslationY(0f);
                }).start();
        if (on) {
            stopBrowsing();
            gridRow = -1;
            in.setVisibility(View.VISIBLE);
            // Laid out at the current row before it shows.
            grid.post(() -> followGrid(false));
        } else {
            refresh();
            in.setVisibility(View.VISIBLE);
        }
        in.animate().cancel();
        in.setAlpha(0f);
        in.setTranslationY(slide);
        in.animate().alpha(1f).translationY(0f).setStartDelay(PANE_OUT_MS - 40L)
                .setDuration(PANE_IN_MS).setInterpolator(new DecelerateInterpolator()).start();
        if (viewModeListener != null) viewModeListener.onGridModeChanged(on);
    }

    /** Before the view shows: the grid lands on the current row, not browsing. */
    public void prepareShow() {
        stopBrowsing();
        gridRow = -1;
        if (gridMode) grid.post(() -> followGrid(false));
    }

    /** Ends a grid browse at once (seek, song change). */
    public void stopBrowsing() {
        gridBrowsing = false;
        grid.removeCallbacks(gridMagnet);
    }

    /** Keeps the row of the current beat at the focus line of the grid. */
    private void followGrid(boolean animate) {
        if (!gridMode || gridBrowsing || gridAdapter.getItemCount() == 0) return;
        int cols = gridAdapter.getColumns();
        int row = gridAdapter.slotOf(Math.max(0, lastBeat)) / cols;
        if (animate && row == gridRow) return;
        gridRow = row;
        int focus = Math.round(grid.getHeight() * GRID_FOCUS);
        if (!animate || grid.getHeight() == 0) {
            gridLm.scrollToPositionWithOffset(row * cols, focus);
            return;
        }
        View target = gridLm.findViewByPosition(row * cols);
        if (target != null) {
            // Fixed length and curve, like the lyrics glide.
            int dy = gridLm.getDecoratedTop(target) - (grid.getPaddingTop() + focus);
            if (dy != 0) grid.smoothScrollBy(0, dy, GRID_INTERPOLATOR, GRID_GLIDE_MS);
            return;
        }
        LinearSmoothScroller s = new LinearSmoothScroller(ctx) {
            @Override public int calculateDyToMakeVisible(View view, int snapPreference) {
                RecyclerView.LayoutManager lm = getLayoutManager();
                if (lm == null) return 0;
                return grid.getPaddingTop() + focus - lm.getDecoratedTop(view);
            }
            @Override protected float calculateSpeedPerPixel(android.util.DisplayMetrics dm) {
                return 60f / dm.densityDpi;
            }
        };
        s.setTargetPosition(row * cols);
        gridLm.startSmoothScroll(s);
    }

    /** Released after a drag: back to the current row, at once when it is
     *  near, after a wait when far (time to read or tap a tile). */
    private void scheduleGridMagnet() {
        grid.removeCallbacks(gridMagnet);
        int cols = gridAdapter.getColumns();
        int first = gridLm.findFirstVisibleItemPosition();
        int last = gridLm.findLastVisibleItemPosition();
        int cur = gridAdapter.slotOf(Math.max(0, lastBeat));
        int rows = 0;
        if (first != RecyclerView.NO_POSITION && (cur < first || cur > last)) {
            int center = (first + last) / 2;
            rows = Math.abs(center / cols - cur / cols);
        }
        long delay = rows <= 1 ? 0L
                : Math.min(MAGNET_MAX_MS, MAGNET_BASE_MS + MAGNET_PER_CELL_MS * rows * cols);
        grid.postDelayed(gridMagnet, delay);
    }

    /** Notation of chord and note names, and the typeface of the carousel. */
    public void setNotation(boolean latin, boolean preferFlat, Typeface typeface) {
        this.latin = latin;
        this.preferFlat = preferFlat;
        this.typeface = typeface;
        stage.setTypeface(typeface);
        gridAdapter.setFormat(latin, preferFlat, shift, typeface);
        refresh();
    }

    @Nullable private Typeface typeface;

    /** Display transposition in semitones (Transpose with pitch). */
    public void setShift(int semitones) {
        if (shift == semitones) return;
        shift = semitones;
        gridAdapter.setFormat(latin, preferFlat, shift, typeface);
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
        if (gridAdapter.setCells(cells)) gridLm.setSpanCount(gridAdapter.getColumns());
        gridRow = -1;
        if (lastBeat >= 0) onBeat(lastBeat);
    }

    /** Beat highlighted by the carousel; advances the stage on a chord change
     *  and moves the grid highlight. */
    public void onBeat(int beatIdx) {
        lastBeat = beatIdx;
        gridAdapter.setHighlight(beatIdx);
        followGrid(true);
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

    /** True when a chord change starts exactly at [beatIdx] (a transition
     *  will animate then). */
    public boolean changesAt(int beatIdx) {
        for (int i = 0; i < changes.size(); i++) {
            int b = changes.get(i).beat;
            if (b == beatIdx) return true;
            if (b > beatIdx) return false;
        }
        return false;
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
