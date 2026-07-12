package com.jotapeveloper.fision;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for downbeats.txt.
 *
 * Current format (beat_this): one line per downbeat, containing the instant
 * (in seconds) of the "1" of the bar.
 *
 * Legacy format (madmom DBNDownBeatTracker, kept for retro-compatibility):
 * two columns per line "time position" where position is 1..N. Only lines
 * with position == 1 are kept.
 *
 * In both cases {@link #times} ends up as the ordered list of downbeat
 * instants. The beats-per-bar count is estimated by
 * {@code PlayerActivity.buildBeatCells} by counting how many beats fall
 * between consecutive downbeats.
 */
public class Downbeats {

    /** Instants (seconds) of the "1" of the bar, in order. */
    public final List<Float> times = new ArrayList<>();

    /** Reads downbeats.txt from the folder. Returns null when the file does not exist or cannot be read. */
    public static Downbeats read(File songFolder) {
        File f = new File(songFolder, "downbeats.txt");
        if (!f.exists()) return null;
        Downbeats d = new Downbeats();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("[\\s,;]+");
                try {
                    float t = Float.parseFloat(parts[0]);
                    if (parts.length >= 2) {
                        // Legacy: only lines with position == 1 are accepted.
                        try {
                            int p = Integer.parseInt(parts[1]);
                            if (p != 1) continue;
                        } catch (NumberFormatException ignored) {}
                    }
                    d.times.add(t);
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            return null;
        }
        return d;
    }
}
