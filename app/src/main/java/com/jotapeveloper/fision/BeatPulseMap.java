package com.jotapeveloper.fision;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Sections of a song where the ambient beat pulse is active, derived from the
 * energy of the drums. Quiet sections do not pulse and the pulse fades in over
 * the beats that lead to a loud one. A song without clear contrast between its
 * sections pulses from start to end, except where the drums are silent.
 *
 * The result is cached in the song folder and stays valid until the drums or
 * the beat grid change.
 */
final class BeatPulseMap {

    private static final String FILE_NAME = "pulse_map.txt";
    private static final int FORMAT = 1;

    private static final int MIN_BEATS = 32;
    private static final int SMOOTH_BEATS = 8;
    private static final int MIN_SECTION_BEATS = 16;
    private static final int LEAD_BEATS = 8;
    private static final double SILENCE_RANGE_DB = 25.0;
    private static final double MIN_CONTRAST_DB = 6.0;
    private static final double NO_DRUMS_DB = -50.0;
    private static final int HISTOGRAM_BINS = 60;
    private static final double MIN_CLUSTER_SHARE = 0.05;

    private final float[] rampStarts;
    private final float[] starts;
    private final float[] ends;

    private BeatPulseMap(float[] rampStarts, float[] starts, float[] ends) {
        this.rampStarts = rampStarts;
        this.starts = starts;
        this.ends = ends;
    }

    /** Pulse gain at [timeSec]: 0 in a quiet section, rising to 1 ahead of a loud one. */
    float gainAt(float timeSec) {
        for (int i = 0; i < starts.length; i++) {
            if (timeSec >= ends[i]) continue;
            if (timeSec < rampStarts[i]) return 0f;
            if (timeSec >= starts[i]) return 1f;
            return (timeSec - rampStarts[i]) / (starts[i] - rampStarts[i]);
        }
        return 0f;
    }

    /** Cached map of the song, measured and stored when missing or outdated. Blocking.
     *  Null when the song cannot be analysed, which leaves the pulse always on. */
    static BeatPulseMap loadOrAnalyze(File folder, float[] beats) {
        if (beats.length < MIN_BEATS) return null;
        List<File> drums = drumFiles(folder);
        if (drums.isEmpty()) return null;
        String signature = signature(drums, beats);
        File cache = new File(folder, FILE_NAME);
        BeatPulseMap cached = read(cache, signature);
        if (cached != null) return cached;

        double[] power = new double[beats.length];
        for (File f : drums) {
            if (!StemEnergyJni.nativeAddBeatPower(f.getAbsolutePath(), beats, power)) return null;
        }
        BeatPulseMap map = fromPower(power, beats);
        map.write(cache, signature);
        return map;
    }

    /** The full drum stem, or its parts when the separation split it. */
    private static List<File> drumFiles(File folder) {
        List<File> out = new ArrayList<>();
        File full = audioFile(folder, "drums");
        if (full != null) {
            out.add(full);
            return out;
        }
        for (String part : StemSpec.DRUM_PARTS_ORDER) {
            File f = audioFile(folder, part);
            if (f != null) out.add(f);
        }
        File perc = audioFile(folder, "perc");
        if (perc != null) out.add(perc);
        return out;
    }

    private static File audioFile(File folder, String stem) {
        File flac = new File(folder, stem + ".flac");
        if (flac.isFile()) return flac;
        File wav = new File(folder, stem + ".wav");
        return wav.isFile() ? wav : null;
    }

    private static String signature(List<File> drums, float[] beats) {
        StringBuilder sb = new StringBuilder().append(beats.length);
        for (File f : drums) sb.append('_').append(f.length()).append('_').append(f.lastModified());
        return sb.toString();
    }

    /** Builds the sections from the drum power of each beat. */
    static BeatPulseMap fromPower(double[] power, float[] beats) {
        double[] level = smoothedDb(power);
        double[] sorted = level.clone();
        Arrays.sort(sorted);
        double top = sorted[(int) (0.95 * (sorted.length - 1))];
        if (top < NO_DRUMS_DB) return alwaysOn(beats);

        double silence = top - SILENCE_RANGE_DB;
        double threshold = contrastThreshold(sorted, silence);
        if (Double.isNaN(threshold)) threshold = silence;

        boolean[] on = new boolean[level.length];
        for (int i = 0; i < on.length; i++) on[i] = level[i] >= threshold;
        absorbShortRuns(on, false);
        absorbShortRuns(on, true);
        return fromSections(on, beats);
    }

    /** Power per beat in dB, averaged over a window centred on the beat. */
    private static double[] smoothedDb(double[] power) {
        int n = power.length;
        double[] db = new double[n];
        for (int i = 0; i < n; i++) db[i] = 10.0 * Math.log10(power[i] + 1e-12);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int k = 0; k < SMOOTH_BEATS; k++) {
                int at = Math.max(0, Math.min(n - 1, i - SMOOTH_BEATS / 2 + k));
                sum += db[at];
            }
            out[i] = sum / SMOOTH_BEATS;
        }
        return out;
    }

    /** Level that best splits the sounding beats in two groups (Otsu), or NaN
     *  when both groups are too close to count as different sections. */
    private static double contrastThreshold(double[] sorted, double silence) {
        int first = 0;
        while (first < sorted.length && sorted[first] < silence) first++;
        int count = sorted.length - first;
        if (count == 0) return Double.NaN;
        double lo = sorted[first];
        double hi = sorted[sorted.length - 1];
        if (hi <= lo) return Double.NaN;

        double width = (hi - lo) / HISTOGRAM_BINS;
        double[] weight = new double[HISTOGRAM_BINS];
        for (int i = first; i < sorted.length; i++) {
            weight[Math.min(HISTOGRAM_BINS - 1, (int) ((sorted[i] - lo) / width))]++;
        }
        double bestScore = -1;
        double bestSplit = Double.NaN;
        double bestContrast = 0;
        for (int split = 1; split < HISTOGRAM_BINS; split++) {
            double w0 = 0, w1 = 0, s0 = 0, s1 = 0;
            for (int b = 0; b < HISTOGRAM_BINS; b++) {
                double centre = lo + (b + 0.5) * width;
                if (b < split) { w0 += weight[b]; s0 += weight[b] * centre; }
                else           { w1 += weight[b]; s1 += weight[b] * centre; }
            }
            if (w0 < MIN_CLUSTER_SHARE * count || w1 < MIN_CLUSTER_SHARE * count) continue;
            double contrast = s1 / w1 - s0 / w0;
            double score = w0 * w1 * contrast * contrast;
            if (score > bestScore) {
                bestScore = score;
                bestSplit = lo + split * width;
                bestContrast = contrast;
            }
        }
        return bestContrast >= MIN_CONTRAST_DB ? bestSplit : Double.NaN;
    }

    /** Flips the runs of [value] shorter than a section into their surroundings. */
    private static void absorbShortRuns(boolean[] on, boolean value) {
        int n = on.length;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n && on[j] == on[i]) j++;
            if (on[i] == value && j - i < MIN_SECTION_BEATS && j - i < n) {
                Arrays.fill(on, i, j, !value);
            }
            i = j;
        }
    }

    private static BeatPulseMap fromSections(boolean[] on, float[] beats) {
        int n = on.length;
        List<float[]> sections = new ArrayList<>();
        int previousEnd = 0;
        int i = 0;
        while (i < n) {
            if (!on[i]) { i++; continue; }
            int j = i;
            while (j < n && on[j]) j++;
            // One extra beat so the first beat of the ramp already pulses.
            int rampIdx = Math.max(previousEnd, i - LEAD_BEATS - 1);
            float end = j < n ? beats[j] : beats[n - 1] + 60f;
            sections.add(new float[]{beats[rampIdx], beats[i], end});
            previousEnd = j;
            i = j;
        }
        if (sections.isEmpty()) return alwaysOn(beats);
        return fromList(sections);
    }

    private static BeatPulseMap alwaysOn(float[] beats) {
        List<float[]> all = new ArrayList<>();
        all.add(new float[]{0f, 0f, beats[beats.length - 1] + 60f});
        return fromList(all);
    }

    private static BeatPulseMap fromList(List<float[]> sections) {
        int n = sections.size();
        float[] rampStarts = new float[n];
        float[] starts = new float[n];
        float[] ends = new float[n];
        for (int i = 0; i < n; i++) {
            rampStarts[i] = sections.get(i)[0];
            starts[i] = sections.get(i)[1];
            ends[i] = sections.get(i)[2];
        }
        return new BeatPulseMap(rampStarts, starts, ends);
    }

    private static BeatPulseMap read(File cache, String signature) {
        if (!cache.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(cache))) {
            if (!(FORMAT + " " + signature).equals(reader.readLine())) return null;
            List<float[]> sections = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 3) return null;
                sections.add(new float[]{Float.parseFloat(parts[0]),
                        Float.parseFloat(parts[1]), Float.parseFloat(parts[2])});
            }
            return sections.isEmpty() ? null : fromList(sections);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** Best effort: without the cache the song is simply measured again. */
    private void write(File cache, String signature) {
        File tmp = new File(cache.getParentFile(), FILE_NAME + ".tmp");
        try (PrintWriter out = new PrintWriter(tmp)) {
            out.println(FORMAT + " " + signature);
            for (int i = 0; i < starts.length; i++) {
                out.println(String.format(Locale.ROOT, "%.3f %.3f %.3f",
                        rampStarts[i], starts[i], ends[i]));
            }
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cache)) tmp.delete();
    }
}
