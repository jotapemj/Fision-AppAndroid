package com.jotapeveloper.fision;

import android.content.Context;
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
 *   <li>Output folder: {@code Music/Fision/<ArtistTitle>/} in the shared
 *       Music folder, through {@link ExportStore}: each file is rendered to a
 *       temporary file and published when complete. Each file has a timestamp
 *       to avoid overwrite.</li>
 *   <li>MP3 has no native encoder on Android — the caller must handle that (toast).</li>
 * </ul>
 */
public class ExportManager {

    private static final String TAG = "ExportManager";

    public enum Format { WAV, AAC, M4A }
    public enum VolumeSource { DEFAULT, CURRENT }

    public interface Listener {
        void onProgress(int percent);
        void onDone(int filesWritten);
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

    /** Song folder inside {@code Music/Fision/}. */
    private static String folderFor(String displayName) {
        String n = sanitize(displayName);
        return n.isEmpty() ? "Export" : n;
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
        // null = dry mix (effects left out in the wizard).
        mixFx = stemFx;
        mixFxBpm = bpm;
        _listener = listener;
        worker = new Thread(() -> {
            File outFile = ExportStore.tempFile(ctx, extFor(format));
            try {
                String fileName = sanitize(displayName) + "_mix_" + timestamp() + extFor(format);

                // Mixdown
                MixResult mix = mixStems(songFolder, stemOrder, currentSettings, volSrc,
                        metronomeWavOrNull, metronomeVol, 0, 70);
                if (cancelled.get()) { post(listener, l -> l.onCancelled()); return; }

                // Write in the requested format
                writeOutput(outFile, mix.samples, mix.sampleRate, mix.channels, format, 70, 100, listener);
                if (cancelled.get()) {
                    post(listener, l -> l.onCancelled());
                    return;
                }
                ExportStore.publish(ctx, outFile, folderFor(displayName), fileName);
                post(listener, l -> l.onDone(1));
            } catch (Throwable e) {
                Log.e(TAG, "mix export fail", e);
                final String msg = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? (": " + e.getMessage()) : "");
                post(listener, l -> l.onError(msg));
            } finally {
                ExportStore.discard(outFile);
            }
        }, "ExportMix");
        worker.start();
    }

    /** Exports separate tracks. One file per stem. A stem with active FX in
     *  {@code stemFx} (null = none) is rendered through them, with its tail. */
    public void exportSeparate(File songFolder,
                               String displayName,
                               List<String> stemOrder,
                               Format format,
                               Map<String, StemFx> stemFx,
                               float bpm,
                               Listener listener) {
        cancelled.set(false);
        _listener = listener;
        worker = new Thread(() -> {
            try {
                String stamp = timestamp();
                int n = stemOrder.size();
                int written = 0;
                for (int i = 0; i < n; i++) {
                    if (cancelled.get()) { post(listener, l -> l.onCancelled()); return; }
                    String stem = stemOrder.get(i);
                    File src = AudioIO.findStemFile(songFolder, stem);
                    if (src == null) continue;
                    String fileName = sanitize(displayName) + "_" + stem + "_" + stamp + extFor(format);
                    File outFile = ExportStore.tempFile(ctx, extFor(format));

                    final int base = i * 100 / n;
                    final int top  = (i + 1) * 100 / n;
                    boolean srcIsWav = src.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav");
                    StemFx fx = stemFx != null ? stemFx.get(stem) : null;
                    try {
                        if (fx != null && fx.isActive()) {
                            WavIO.WavData d = AudioIO.read(src);
                            float[] out = renderStemFx(d, fx, bpm);
                            softLimit(out);
                            writeOutput(outFile, out, d.sampleRate, 2, format, base, top, listener);
                        } else if (format == Format.WAV && srcIsWav) {
                            // Bit-for-bit copy only when source and destination are WAV
                            WavIO.copyFile(src, outFile);
                            post(listener, l -> l.onProgress(top));
                        } else {
                            WavIO.WavData d = AudioIO.read(src);
                            writeOutput(outFile, d.samples, d.sampleRate, d.channels, format, base, top, listener);
                        }
                        if (cancelled.get()) { post(listener, l -> l.onCancelled()); return; }
                        ExportStore.publish(ctx, outFile, folderFor(displayName), fileName);
                    } finally {
                        ExportStore.discard(outFile);
                    }
                    written++;
                }
                final int total = written;
                post(listener, l -> l.onDone(total));
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
     *  {@code metronomeWavOrNull}: when != null, mixes the metronome on top of the stem.
     *  {@code fx}: the stem's FX to render (null = dry). */
    public void exportSingleStem(File songFolder,
                                 String displayName,
                                 String stem,
                                 Format format,
                                 File metronomeWavOrNull,
                                 float metronomeVol,
                                 StemFx fx,
                                 float bpm,
                                 Listener listener) {
        cancelled.set(false);
        _listener = listener;
        worker = new Thread(() -> {
            File outFile = null;
            try {
                File src = AudioIO.findStemFile(songFolder, stem);
                if (src == null) {
                    post(listener, l -> l.onError("stem not found: " + stem));
                    return;
                }
                String fileName = sanitize(displayName) + "_" + stem + "_" + timestamp() + extFor(format);
                outFile = ExportStore.tempFile(ctx, extFor(format));

                boolean srcIsWav = src.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav");
                if (fx != null && fx.isActive()) {
                    WavIO.WavData d = AudioIO.read(src);
                    float[] out = renderStemFx(d, fx, bpm);
                    postProgress(50);
                    if (metronomeWavOrNull != null) {
                        addMetronome(out, 2, metronomeWavOrNull, metronomeVol, d.framesCount());
                    }
                    softLimit(out);
                    if (cancelled.get()) { post(listener, l -> l.onCancelled()); return; }
                    writeOutput(outFile, out, d.sampleRate, 2, format, 70, 100, listener);
                } else if (metronomeWavOrNull == null) {
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
                    post(listener, l -> l.onCancelled());
                    return;
                }
                ExportStore.publish(ctx, outFile, folderFor(displayName), fileName);
                post(listener, l -> l.onDone(1));
            } catch (Throwable e) {
                Log.e(TAG, "single export fail", e);
                final String msg = e.getClass().getSimpleName()
                        + (e.getMessage() != null ? (": " + e.getMessage()) : "");
                post(listener, l -> l.onError(msg));
            } finally {
                ExportStore.discard(outFile);
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

        // Room for the echo / reverb tail past the longest stem (trimmed at the end).
        long tailFrames = 0;
        if (mixFx != null && ch == 2) {
            for (StemFx fx : mixFx.values()) {
                if (fx != null && fx.hasTail()) {
                    tailFrames = (long) (StemFxOffline.TAIL_SECONDS * sr);
                    break;
                }
            }
        }
        long totalSamples = (maxFrames + tailFrames) * ch;
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
            if ((d.framesCount() + tailFrames) * ch > out.length) {
                float[] bigger = new float[(int) ((d.framesCount() + tailFrames) * ch)];
                System.arraycopy(out, 0, bigger, 0, out.length);
                out = bigger;
            }
            mixOneStem(d, stem, currentSettings, volSrc, out, ch);
            d.samples = null; // release ASAP
            int pct = progressBase + (s + 1) * (progressTop - progressBase) / presentStems.size();
            postProgress(pct);
        }

        // Optional metronome on top of the mix
        if (metronomeFile != null) addMetronome(out, ch, metronomeFile, metronomeVol, maxFrames);

        out = trimTail(out, maxFrames * ch, ch);
        softLimit(out);

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
        softLimit(out);
        postProgress(progressTop);
        MixResult r = new MixResult();
        r.samples = out; r.sampleRate = sr; r.channels = ch;
        return r;
    }

    /** One stem through its FX at unity volume: stereo, with the FX tail
     *  (silence after the tail trimmed). */
    private float[] renderStemFx(WavIO.WavData d, StemFx fx, float bpm) throws Exception {
        long len = d.framesCount();
        long tail = fx.hasTail() ? (long) (StemFxOffline.TAIL_SECONDS * d.sampleRate) : 0;
        long total = (len + tail) * 2;
        if (total > Integer.MAX_VALUE - 8) throw new java.io.IOException("Stem too long");
        float[] out = new float[(int) total];
        try (StemFxOffline off = new StemFxOffline(d.sampleRate, fx, bpm)) {
            if (!off.mixInto(d.samples, d.channels, (int) len, d.sampleRate, out, 1f, 1f, cancelled::get)) {
                throw new InterruptedException();
            }
        }
        return trimTail(out, len * 2, 2);
    }

    /** Adds the metronome (no pan) to the first {@code maxFrames} of {@code out}. */
    private void addMetronome(float[] out, int ch, File metronomeFile, float vol,
                              long maxFrames) throws Exception {
        if (!metronomeFile.exists()) return;
        if (cancelled.get()) throw new InterruptedException();
        WavIO.WavData m = AudioIO.read(metronomeFile);
        int mch = m.channels;
        int n = (int) Math.min(m.framesCount(), Math.min(maxFrames, out.length / ch));
        for (int f = 0; f < n; f++) {
            if ((f & 0xFFFF) == 0 && cancelled.get()) throw new InterruptedException();
            float l, r;
            if (mch == 1) { l = r = m.samples[f]; }
            else          { l = m.samples[f * mch]; r = m.samples[f * mch + 1]; }
            out[f * ch]     += l * vol;
            out[f * ch + 1] += r * vol;
        }
    }

    /** Below this the FX tail counts as silence (about -80 dBFS). */
    private static final float TAIL_FLOOR = 1e-4f;

    /** Cuts the buffer after the last audible sample, never shorter than
     *  {@code minSamples} (the song itself). */
    private static float[] trimTail(float[] buf, long minSamples, int ch) {
        int last = buf.length - 1;
        while (last >= minSamples && Math.abs(buf[last]) < TAIL_FLOOR) last--;
        int len = (int) Math.max(minSamples, last + 1);
        len = Math.min(buf.length, (len + ch - 1) / ch * ch);   // whole frames
        return len == buf.length ? buf : java.util.Arrays.copyOf(buf, len);
    }

    /** Same soft-knee limiter as the engine output (AudioEngine::onAudioReady):
     *  linear up to 0.7, eased curve up to 1, ceiling 0.95. Keeps the export
     *  sounding like the playback instead of hard clipping. */
    static void softLimit(float[] buf) {
        final float thr = 0.7f, ceil = 0.95f;
        final float span = 1f - thr, delta = ceil - thr;
        for (int i = 0; i < buf.length; i++) {
            float x = buf[i];
            float a = Math.abs(x);
            if (a <= thr) continue;
            if (a >= 1f) { buf[i] = Math.copySign(ceil, x); continue; }
            float over = (a - thr) / span;
            buf[i] = Math.copySign(thr + delta * (1f - (1f - over) * (1f - over)), x);
        }
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
