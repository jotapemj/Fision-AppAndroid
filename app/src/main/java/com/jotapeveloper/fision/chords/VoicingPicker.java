package com.jotapeveloper.fision.chords;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Chooses one position of a fretted chord. A fixed zone of the neck takes
 * the easiest position inside it; AUTO takes the one that moves the hand
 * least from the previous chord.
 */
public final class VoicingPicker {

    public enum Zone {
        OPEN(0, 4), LOW(3, 7), HIGH(7, 12), AUTO(0, 12);

        final int from, to;
        Zone(int from, int to) { this.from = from; this.to = to; }
    }

    private VoicingPicker() {}

    @Nullable
    public static ChordShapes.Position pick(@Nullable ChordShapes.Shape shape, Zone zone,
                                            @Nullable ChordShapes.Position previous) {
        if (shape == null || shape.positions.isEmpty()) return null;
        List<ChordShapes.Position> all = shape.positions;
        if (zone == Zone.AUTO) {
            if (previous == null) return pick(shape, Zone.OPEN, null);
            ChordShapes.Position best = null;
            float bestCost = Float.MAX_VALUE;
            for (ChordShapes.Position p : all) {
                float cost = Math.abs(p.handFret - previous.handFret) * 1.2f + p.difficulty * 0.6f;
                if (cost < bestCost) { bestCost = cost; best = p; }
            }
            return best;
        }
        ChordShapes.Position best = null;
        float bestCost = Float.MAX_VALUE;
        for (ChordShapes.Position p : all) {
            if (!inZone(p, zone)) continue;
            float cost = p.difficulty + p.handFret * 0.05f;
            if (cost < bestCost) { bestCost = cost; best = p; }
        }
        if (best != null) return best;
        // Nothing inside the zone: closest position to it.
        for (ChordShapes.Position p : all) {
            float d = p.handFret < zone.from ? zone.from - p.handFret
                    : p.handFret > zone.to ? p.handFret - zone.to : 0;
            float cost = d * 2f + p.difficulty;
            if (cost < bestCost) { bestCost = cost; best = p; }
        }
        return best;
    }

    private static boolean inZone(ChordShapes.Position p, Zone zone) {
        if (zone == Zone.OPEN) return p.baseFret == 1 && p.handFret <= zone.to;
        return p.handFret >= zone.from && p.handFret <= zone.to;
    }
}
