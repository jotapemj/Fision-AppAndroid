package com.jotapeveloper.fision;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates exports (current mixdown + separate stems + per-stem). Offline
 * render happens on a dedicated thread; progress/completion callbacks are
 * posted to the main thread.
 *
 * <p>Notes:
 * <ul>
 *   <li>Pitch and tempo are NOT applied to exports in this version (would
 *       require plugging the engine in offline mode, out of scope here).
 *       Volume and pan are applied.</li>
 *   <li>Output folder: {@code Fision/<ArtistTitle>/} at the root of external
 *       storage. Each file has a timestamp to avoid overwrite.</li>
 *   <li>MP3 has no native encoder on Android — the caller must handle that (toast).</li>
 * </ul>
 */
public class ExportManager {

    private static final String TAG = "ExportManager";

    public enum Format { WAV, AAC, M4A }
    public enum VolumeSource { DEFAULT, CURRENT }

    public interface Listener {
        void onProgress(int percent);
        void onDone(File outputFolder, int filesWritten);
        void onCancelled();
        void onError(String message);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Thread worker;

    public ExportManager(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public void cancel() {
        cancelled.set(true);
        if (worker != null) worker.interrupt();
    }

    /** Base output folder: {@code Fision/<ArtistTitle>/}. Created when missing. */
    public static File outputFolderFor(String displayName) {
        File root = new File(Environment.getExternalStorageDirectory(), "Fision");
        File song = new File(root, sanitize(displayName));
        if (!song.exists()) song.mkdirs();
        return song;
    }

    private static String sanitize(String s) {
        if (s == null) return "Export";
        // Characters not allowed in FAT/exFAT
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private static String extFor(Format f) {
        switch (f) {
            case WAV: return ".wav";
            case AAC: return ".aac";
            case M4A: return ".m4a";
        }
        return ".bin";
    }

    /** Exports the current mixdown. */
    public void exportMixdown(File songFolder,
                              String displayName,
                              List<String> stemOrder,
                              Map<String, StemSetting> currentSettings,
                              VolumeSource volSrc,
                              Format format,
                              File metronomeWavOrNull,
                              float metronomeVol,
                              Map<String, StemFx> stemFx,
                              float bpm,
                              Listener listener) {
        cancelled.set(false);
        // FX belong to the current mix only.
        mixFx = volSrc == VolumeSource.CURRENT ? stemFx : null;
        mixFxBpm = bpm;
        _listener = listener;
        worker = new Thread(() -> {
            try {
                File outDir = outputFolderFor(displayName);
                String baseName = sanitize(displayName) + "_mix_" + timestamp();
                File outFile = new File(outDir, baseName + extFor(format));

                // Mixdown
                MixResult mix = mixStems(songFolder, stemOrder, currentSettings, volSrc,
                        metronomeWavOrNull, metronomeVol, 0, 70);
                if (cancelled.get()) { post(listener, l -> l.onCancelled()); return; }

                // Write in the requested format
                writeOutput(outFile, mix.samples, mix.sampleRate, mix.channels, format, 70, 100, listener);
                if (cancelled.get()) {
                    if (outFile.exists()) outFile.delete();
                    post(listener, l -> l.onCancelled());
                    return;
                }
                post(listener, l -> l.onDone(outDir, 1));
            } catch (Throwable e) {
                Log.e(TAG, "mix export fail", e);
                final String msg = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? (": " + e.getMessage()) : "");
                post(listener, l -> l.onError(msg));
            }
        }, "ExportMix");
        worker.start();
    }

    /** Exports separate tracks. One file per stem. */
    public void exportSeparate(File songFolder,
                               String displayName,
                               List<String> stemOrder,
                               Format format,
                               Listener listener) {
        cancelled.set(false);
        _listener = listener;
        worker = new Thread(() -> {
            try {
                File outDir = outputFolderFor(displayName);
                String stamp = timestamp();
                int n = stemOrder.size();
                int written = 0;
                for (int i = 0; i < n; i++) {
                    if (cancelled.get()) { post(listener, l -> l.onCancelled()); return; }
                    String stem = stemOrder.get(i);
                    File src = AudioIO.findStemFile(songFolder, stem);
                    if (src == null) continue;
                    File outFile = new File(outDir,
                            sanitize(displayName) + "_" + stem + "_" + stamp + extFor(format));

                    final int base = i * 100 / n;
                    final int top  = (i + 1) * 100 / n;
                    boolean srcIsWav = src.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav");
                    if (format == Format.WAV && srcIsWav) {
                        // Bit-for-bit copy only when source and destination are WAV
                        WavIO.copyFile(src, outFile);
                        post(listener, l -> l.onProgress(top));
                    } else {
                        WavIO.WavData d = AudioIO.read(src);
                        writeOutput(outFile, d.samples, d.sampleRate, d.channels, format, base, top, listener);
                    }
                    written++;
                }
                final int total = written;
                post(listener, l -> l.onDone(outDir, total));
            } catch (Throwable e) {
                Log.e(TAG, "separate export fail", e);
                final String msg = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? (": " + e.getMessage()) : "");
                post(listener, l -> l.onError(msg));
            }
        }, "ExportSeparate");
        worker.start();
    }

    /** Single-stem export (from the bottom sheet).
     *  {@code metronomeWavOrNull}: when != null, mixes the metronome on top of the stem. */
    public void exportSingleStem(File songFolder,
                                 String displayName,
                                 String stem,
                                 Format format,
                                 File metronomeWavOrNull,
                                 float metronomeVol,
                                 Listener listener) {
        cancelled.set(false);
        _listener = listener;
        worker = new Thread(() -> {
            try {
                File src = AudioIO.findStemFile(songFolder, stem);
                if (src == null) {
                    post(listener, l -> l.onError("stem not found: " + stem));
                    return;
                }
                File outDir = outputFolderFor(displayName);
                File outFile = new File(outDir,
                        sanitize(displayName) + "_" + stem + "_" + timestamp() + extFor(format));

                boolean srcIsWav = src.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav");
                if (metronomeWavOrNull == null) {
                    // Direct copy only when both source and destination are WAV
                    if (format == Format.WAV && srcIsWav) {
                        WavIO.copyFile(src, outFile);
                        post(listener, l -> l.onProgress(100));
                    } else {
                        WavIO.WavData d = AudioIO.read(src);
                        writeOutput(outFile, d.samples, d.sampleRate, d.channels, format, 0, 100, listener);
                    }
                } else {
                    // Stem + metronome mix
                    MixResult mix = mixStemWithMetronome(src, metronomeWavOrNull, metronomeVol, 0, 70);
                    if (cancelled.get()) { post(listener, l -> l.onCancelled()); return; }
                    writeOutput(outFile, mix.samples, mix.sampleRate, mix.channels, format, 70, 100, listener);
                }
                if (cancelled.get()) {
                    if (outFile.exists()) outFile.delete();
                    post(listener, l -> l.onCancelled());
                    return;
                }
                post(listener, l -> l.onDone(outDir, 1));
            } catch (Throwable e) {
                Log.e(TAG, "single export fail", e);
                final String msg = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? (": " + e.getMessage()) : "");
                post(listener, l -> l.onError(msg));
            }
        }, "ExportSingle");
        worker.start();
    }

    /** Mix result: interleaved samples + metadata. */
    private static class MixResult {
        float[] samples; int sampleRate; int channels;
    }

    /** Offline mixdown. Per-stem volume+pan driven by volSrc. When {@code metronomeFile}
     *  != null the metronome is added on top at {@code metronomeVol} (0..1) with no pan.
     *
     *  <p>Loads ONE stem at a time (not all at once) to avoid exhausting the heap — a
     *  4-min song × 5 stems × 48 kHz stereo float = ~450 MB in RAM, guaranteed OOM.
     *  With sequential loading the peak is ~180 MB (1 stem + mix buffer). */
    private MixResult mixStems(File songFolder,
                               List<String> stemOrder,
                               Map<String, StemSetting> currentSettings,
                               VolumeSource volSrc,
                               File metronomeFile,
                               float metronomeVol,
                               int progressBase, int progressTop) throws Exception {
        // First pass: enumerate stems present and obtain sr/ch/frames of the first one
        // (without loading samples) — the mix buffer size must be known before loading
        // any individual stem.
        java.util.List<String> presentStems = new java.util.ArrayList<>();
        for (String stem : stemOrder) {
            if (cancelled.get()) throw new InterruptedException();
            if (AudioIO.hasStem(songFolder, stem)) presentStems.add(stem);
        }
        if (presentStems.isEmpty()) throw new java.io.IOException("No stems to mix");

        // Load the first one to pin the format + size
        File firstFile = AudioIO.findStemFile(songFolder, presentStems.get(0));
        WavIO.WavData first = AudioIO.read(firstFile);
        int sr  = first.sampleRate;
        int ch  = Math.max(2, first.channels);
        long maxFrames = first.framesCount();

        long totalSamples = maxFrames * ch;
        if (totalSamples > Integer.MAX_VALUE - 8) throw new java.io.IOException("Mix too long");
        float[] out = new float[(int) totalSamples];

        // Mix the first stem and release it
        mixOneStem(first, presentStems.get(0), currentSettings, volSrc, out, ch);
        first.samples = null; first = null; // GC ASAP
        postProgress(progressBase + (progressTop - progressBase) / presentStems.size());

        // Load and mix the rest sequentially
        for (int s = 1; s < presentStems.size(); s++) {
            if (cancelled.get()) throw new InterruptedException();
            String stem = presentStems.get(s);
            WavIO.WavData d = AudioIO.read(AudioIO.findStemFile(songFolder, stem));
            maxFrames = Math.max(maxFrames, d.framesCount());
            // When a stem is longer than the current buffer, expand (rare but possible)
            if (d.framesCount() * ch > out.length) {
                float[] bigger = new float[(int) (d.framesCount() * ch)];
                System.arraycopy(out, 0, bigger, 0, out.length);
                out = bigger;
            }
            mixOneStem(d, stem, currentSettings, volSrc, out, ch);
            d.samples = null; // release ASAP
            int pct = progressBase + (s + 1) * (progressTop - progressBase) / presentStems.size();
            postProgress(pct);
        }

        // Optional metronome on top of the mix
        if (metronomeFile != null && metronomeFile.exists()) {
            if (cancelled.get()) throw new InterruptedException();
            WavIO.WavData m = AudioIO.read(metronomeFile);
            int srcCh = m.channels;
            int len   = (int) m.framesCount();
            int n     = Math.min(len, (int) maxFrames);
            for (int f = 0; f < n; f++) {
                if ((f & 0xFFFF) == 0 && cancelled.get()) throw new InterruptedException();
                float l, r;
                if (srcCh == 1) { l = r = m.samples[f]; }
                else            { l = m.samples[f * srcCh]; r = m.samples[f * srcCh + 1]; }
                out[f * ch]     += l * metronomeVol;
                out[f * ch + 1] += r * metronomeVol;
            }
        }

        // Light soft-clamp (no limiter)
        for (int i = 0; i < out.length; i++) {
            if (out[i] >  1f) out[i] =  1f;
            if (out[i] < -1f) out[i] = -1f;
        }

        MixResult r = new MixResult();
        r.samples = out; r.sampleRate = sr; r.channels = ch;
        return r;
    }

    // FX of the mix being exported (null = dry mix).
    private Map<String, StemFx> mixFx;
    private float mixFxBpm;

    /** Mixes one stem into the global `out` buffer applying its volume+pan
     *  and, for the current mix, its FX. */
    private void mixOneStem(WavIO.WavData d, String stem,
                            Map<String, StemSetting> currentSettings,
                            VolumeSource volSrc,
                            float[] out, int ch) throws InterruptedException {
        float volL = 0.75f, volR = 0.75f;
        if (volSrc == VolumeSource.CURRENT && currentSettings != null) {
            StemSetting ss = StemSettingsCodec.getOrDefault(currentSettings, stem);
            float vol = ss.volume * 0.01f;
            volL = vol; volR = vol;
            if (ss.pan > 0)      volL = vol * (100f - ss.pan)         * 0.01f;
            else if (ss.pan < 0) volR = vol * (100f - Math.abs(ss.pan)) * 0.01f;
        }
        int srcCh = d.channels;
        int len   = (int) d.framesCount();
        StemFx fx = mixFx != null ? mixFx.get(stem) : null;
        if (fx != null && fx.isActive() && ch == 2) {
            try (StemFxOffline off = new StemFxOffline(d.sampleRate, fx, mixFxBpm)) {
                if (!off.mixInto(d.samples, srcCh, len, d.sampleRate, out, volL, volR, cancelled::get)) {
                    throw new InterruptedException();
                }
            }
            return;
        }
        for (int f = 0; f < len; f++) {
            if ((f & 0xFFFF) == 0 && cancelled.get()) throw new InterruptedException();
            float l, r;
            if (srcCh == 1) { l = r = d.samples[f]; }
            else            { l = d.samples[f * srcCh]; r = d.samples[f * srcCh + 1]; }
            out[f * ch]     += l * volL;
            out[f * ch + 1] += r * volR;
        }
    }

    /** Mixes a single stem with the metronome (per-stem export with the checkbox on). */
    private MixResult mixStemWithMetronome(File stemFile,
                                           File metronomeFile,
                                           float metronomeVol,
                                           int progressBase, int progressTop) throws Exception {
        WavIO.WavData s = AudioIO.read(stemFile);
        if (cancelled.get()) throw new InterruptedException();
        int sr = s.sampleRate;
        int ch = Math.max(2, s.channels);
        long frames = s.framesCount();
        long total = frames * ch;
        if (total > Integer.MAX_VALUE - 8) throw new java.io.IOException("Stem too long");
        float[] out = new float[(int) total];

        int srcCh = s.channels;
        int len   = (int) frames;
        for (int f = 0; f < len; f++) {
            if ((f & 0xFFFF) == 0 && cancelled.get()) throw new InterruptedException();
            float l, r;
            if (srcCh == 1) { l = r = s.samples[f]; }
            else            { l = s.samples[f * srcCh]; r = s.samples[f * srcCh + 1]; }
            out[f * ch] = l; out[f * ch + 1] = r;
        }
        postProgress(progressBase + (progressTop - progressBase) / 2);

        if (metronomeFile != null && metronomeFile.exists()) {
            WavIO.WavData m = AudioIO.read(metronomeFile);
            int n = Math.min((int) m.framesCount(), (int) frames);
            int mch = m.channels;
            for (int f = 0; f < n; f++) {
                if ((f & 0xFFFF) == 0 && cancelled.get()) throw new InterruptedException();
                float ml, mr;
                if (mch == 1) { ml = mr = m.samples[f]; }
                else          { ml = m.samples[f * mch]; mr = m.samples[f * mch + 1]; }
                out[f * ch]     += ml * metronomeVol;
                out[f * ch + 1] += mr * metronomeVol;
            }
        }
        for (int i = 0; i < out.length; i++) {
            if (out[i] >  1f) out[i] =  1f;
            if (out[i] < -1f) out[i] = -1f;
        }
        postProgress(progressTop);
        MixResult r = new MixResult();
        r.samples = out; r.sampleRate = sr; r.channels = ch;
        return r;
    }

    private void writeOutput(File outFile,
                             float[] samples,
                             int sampleRate,
                             int channels,
                             Format format,
                             int progressBase,
                             int progressTop,
                             Listener listener) throws Exception {
        if (format == Format.WAV) {
            WavIO.writePcm16(outFile, samples, sampleRate, channels);
            postProgress(progressTop);
            return;
        }
        // AAC / M4A → encode with MediaCodec
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float v = samples[i];
            if (v >  1f) v =  1f;
            if (v < -1f) v = -1f;
            pcm[i] = (short) Math.round(v * 32767f);
        }
        boolean adts = (format == Format.AAC);
        AacEncoder.encode(outFile, pcm, sampleRate, channels, AacEncoder.BITRATE_DEFAULT,
                adts,
                pct -> postProgress(progressBase + (progressTop - progressBase) * pct / 100),
                cancelled::get);
    }

    private void postProgress(int pct) {
        final Listener l = _listener;
        if (l != null) main.post(() -> l.onProgress(Math.min(100, Math.max(0, pct))));
    }

    // Live listener of the current export. Set on entry of each exportXxx() before
    // the worker start(), so postProgress() in the worker sees the correct listener.
    private volatile Listener _listener;
    private void post(Listener listener, java.util.function.Consumer<Listener> c) {
        main.post(() -> c.accept(listener));
    }
}
