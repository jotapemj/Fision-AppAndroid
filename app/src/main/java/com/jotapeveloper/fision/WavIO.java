package com.jotapeveloper.fision;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Minimal WAV reader/writer. Mirror of the C++ parser {@code Track::loadWavToFloat}:
 * supports PCM16/24/32, IEEE float32 and {@code WAVE_FORMAT_EXTENSIBLE} (0xFFFE)
 * by reading the SubFormat GUID.
 *
 * <p>The reader returns interleaved float samples [-1,1] together with sample rate
 * and channel count. The writer emits stereo PCM16 WAV (universal format for
 * importing into any DAW / player).
 */
public final class WavIO {

    public static final class WavData {
        // samples is not final so it can be nulled out after consumption (GC ASAP on large exports).
        public float[]   samples;       // interleaved L,R,L,R,...
        public final int sampleRate;
        public final int channels;
        public final long frames;       // cached at construction (stays valid after null-out)
        public WavData(float[] samples, int sampleRate, int channels) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.frames = samples.length / Math.max(1, channels);
        }
        public long framesCount() { return frames; }
    }

    private WavIO() {}

    public static WavData read(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             FileChannel ch = fis.getChannel()) {

            ByteBuffer buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            if (ch.read(buf) != 12) throw new IOException("WAV header trunc");
            buf.flip();
            int riff = buf.getInt();
            buf.getInt(); // size
            int wave = buf.getInt();
            if (riff != 0x46464952 /* 'RIFF' */ || wave != 0x45564157 /* 'WAVE' */) {
                throw new IOException("Not a WAV");
            }

            int fmt = -1, channels = 0, sampleRate = 0, bitsPerSample = 0;
            long dataOff = -1, dataLen = -1;

            ByteBuffer chunkHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            while (ch.read(chunkHdr) == 8) {
                chunkHdr.flip();
                int id = chunkHdr.getInt();
                int sz = chunkHdr.getInt();
                chunkHdr.clear();
                long pos = ch.position();
                if (id == 0x20746d66 /* 'fmt ' */) {
                    ByteBuffer fmtBuf = ByteBuffer.allocate(Math.max(16, sz)).order(ByteOrder.LITTLE_ENDIAN);
                    if (ch.read(fmtBuf, pos) < 16) throw new IOException("fmt chunk trunc");
                    fmtBuf.flip();
                    fmt           = fmtBuf.getShort() & 0xFFFF;
                    channels      = fmtBuf.getShort() & 0xFFFF;
                    sampleRate    = fmtBuf.getInt();
                    fmtBuf.getInt();   // byteRate
                    fmtBuf.getShort(); // blockAlign
                    bitsPerSample = fmtBuf.getShort() & 0xFFFF;
                    if (fmt == 0xFFFE && sz >= 18 + 22) {
                        // cbSize (2) + Samples (2) + ChannelMask (4) = 8 bytes before the GUID
                        fmtBuf.getShort(); // cbSize
                        fmtBuf.position(fmtBuf.position() + 6); // skip Samples + Mask
                        int guidFmt = fmtBuf.getShort() & 0xFFFF;
                        if (guidFmt == 1 || guidFmt == 3) fmt = guidFmt;
                    }
                } else if (id == 0x61746164 /* 'data' */) {
                    dataOff = pos;
                    dataLen = sz & 0xFFFFFFFFL;
                }
                ch.position(pos + sz + (sz & 1L));
                if (dataOff > 0 && fmt > 0) break;
            }
            if (fmt <= 0 || dataOff < 0 || dataLen <= 0) {
                throw new IOException("Malformed WAV");
            }

            int bytesPerSample = bitsPerSample / 8;
            int totalSamples   = (int) (dataLen / bytesPerSample);
            float[] out = new float[totalSamples];

            // Read in 64 KB chunks
            int chunkSamples = 16384;
            int chunkBytes   = chunkSamples * bytesPerSample;
            ByteBuffer dbuf  = ByteBuffer.allocate(chunkBytes).order(ByteOrder.LITTLE_ENDIAN);
            long remaining   = dataLen;
            long readPos     = dataOff;
            int outIdx       = 0;
            while (remaining > 0) {
                dbuf.clear();
                int toRead = (int) Math.min(chunkBytes, remaining);
                dbuf.limit(toRead);
                int r = ch.read(dbuf, readPos);
                if (r <= 0) break;
                readPos   += r;
                remaining -= r;
                dbuf.flip();
                int samplesInChunk = r / bytesPerSample;
                for (int i = 0; i < samplesInChunk; i++) {
                    switch (fmt) {
                        case 1:
                            if (bitsPerSample == 16) {
                                out[outIdx++] = dbuf.getShort() / 32768f;
                            } else if (bitsPerSample == 24) {
                                int b0 = dbuf.get() & 0xFF;
                                int b1 = dbuf.get() & 0xFF;
                                int b2 = dbuf.get();
                                int v  = (b2 << 16) | (b1 << 8) | b0;
                                out[outIdx++] = v / 8388608f;
                            } else if (bitsPerSample == 32) {
                                out[outIdx++] = dbuf.getInt() / 2147483648f;
                            } else {
                                throw new IOException("Unsupported PCM bit depth: " + bitsPerSample);
                            }
                            break;
                        case 3:
                            out[outIdx++] = dbuf.getFloat();
                            break;
                        default:
                            throw new IOException("Unsupported format: " + fmt);
                    }
                }
            }

            return new WavData(out, sampleRate, channels);
        }
    }

    /** Writes interleaved samples as PCM16 WAV. */
    public static void writePcm16(File out, float[] samples, int sampleRate, int channels) throws IOException {
        out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 16)) {

            int byteRate     = sampleRate * channels * 2;
            int dataLen      = samples.length * 2;
            int riffLen      = 36 + dataLen;

            ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            hdr.putInt(0x46464952);     // 'RIFF'
            hdr.putInt(riffLen);
            hdr.putInt(0x45564157);     // 'WAVE'
            hdr.putInt(0x20746d66);     // 'fmt '
            hdr.putInt(16);             // fmt chunk size
            hdr.putShort((short) 1);    // PCM
            hdr.putShort((short) channels);
            hdr.putInt(sampleRate);
            hdr.putInt(byteRate);
            hdr.putShort((short) (channels * 2)); // blockAlign
            hdr.putShort((short) 16);   // bits
            hdr.putInt(0x61746164);     // 'data'
            hdr.putInt(dataLen);
            bos.write(hdr.array());

            // Chunked writes
            int chunkSamples = 8192;
            ByteBuffer dbuf  = ByteBuffer.allocate(chunkSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
            int i = 0;
            while (i < samples.length) {
                int n = Math.min(chunkSamples, samples.length - i);
                dbuf.clear();
                for (int k = 0; k < n; k++) {
                    float v = samples[i + k];
                    if (v >  1f) v =  1f;
                    if (v < -1f) v = -1f;
                    dbuf.putShort((short) Math.round(v * 32767f));
                }
                bos.write(dbuf.array(), 0, n * 2);
                i += n;
            }
        }
    }

    /** Quick byte-for-byte copy (used when exporting to WAV without transcoding). */
    public static void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (InputStream is = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = is.read(buf)) > 0) fos.write(buf, 0, r);
        }
    }
}
