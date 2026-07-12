package com.jotapeveloper.fision;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * AAC encoder via MediaCodec.
 *
 * <p>MP4 mode (.m4a): MediaMuxer packs the AAC stream in an MP4 container with
 * correct STSC/STSZ. Raw ADTS mode (.aac): a 7-byte ADTS header is prepended to
 * each AccessUnit so any player understands the stream without a container.
 *
 * <p>Sample rate and channel count are pinned to the input (whatever the mix returns).
 */
public class AacEncoder {

    public static final int BITRATE_DEFAULT = 192000;

    /** Encodes interleaved PCM16 to AAC. {@code adts=true} → writes raw .aac;
     *  false → packs into MP4 (.m4a). */
    public static void encode(File outFile,
                              short[] pcm16,
                              int sampleRate,
                              int channels,
                              int bitrate,
                              boolean adts,
                              ProgressSink progress,
                              CancelToken cancel) throws IOException {

        outFile.getParentFile().mkdirs();
        MediaFormat fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        MediaMuxer muxer = null;
        OutputStream adtsOut = null;
        int muxerTrack = -1;
        boolean muxerStarted = false;
        if (adts) {
            adtsOut = new FileOutputStream(outFile);
        } else {
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long presentationUs = 0;
        long totalUs = (long) pcm16.length * 1_000_000L / Math.max(1, sampleRate * channels);
        int pcmIdx = 0;
        boolean inputDone = false;
        boolean outputDone = false;

        try {
            while (!outputDone) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new IOException("Cancelled");
                }
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        if (ib == null) continue;
                        ib.clear();
                        int cap = ib.remaining();
                        int samplesAvail = pcm16.length - pcmIdx;
                        int samplesToCopy = Math.min(samplesAvail, cap / 2);
                        ByteBuffer tmp = ib.order(ByteOrder.LITTLE_ENDIAN);
                        for (int i = 0; i < samplesToCopy; i++) {
                            tmp.putShort(pcm16[pcmIdx + i]);
                        }
                        pcmIdx += samplesToCopy;
                        int flag = 0;
                        if (samplesToCopy == 0) {
                            flag = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                            inputDone = true;
                        }
                        codec.queueInputBuffer(inIdx, 0, samplesToCopy * 2, presentationUs, flag);
                        presentationUs = (long) pcmIdx * 1_000_000L / Math.max(1, sampleRate * channels);
                        if (progress != null && totalUs > 0) {
                            progress.onProgress((int) (presentationUs * 100 / totalUs));
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxer != null) {
                        muxerTrack   = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    }
                } else if (outIdx >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        if (adts) {
                            byte[] hdr = makeAdtsHeader(info.size + 7, sampleRate, channels);
                            adtsOut.write(hdr);
                            byte[] payload = new byte[info.size];
                            ob.get(payload);
                            adtsOut.write(payload);
                        } else if (muxer != null && muxerStarted &&
                                (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            muxer.writeSampleData(muxerTrack, ob, info);
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                try { if (muxerStarted) muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
            if (adtsOut != null) {
                try { adtsOut.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 7-byte ADTS header for AAC LC. */
    private static byte[] makeAdtsHeader(int frameLen, int sampleRate, int channels) {
        int sri = sampleRateIndex(sampleRate);
        int profile = 2; // AAC LC
        byte[] h = new byte[7];
        h[0] = (byte) 0xFF;
        h[1] = (byte) 0xF1; // MPEG-4, no CRC
        h[2] = (byte) (((profile - 1) << 6) | (sri << 2) | ((channels >> 2) & 0x1));
        h[3] = (byte) (((channels & 0x3) << 6) | ((frameLen >> 11) & 0x3));
        h[4] = (byte) ((frameLen >> 3) & 0xFF);
        h[5] = (byte) (((frameLen & 0x7) << 5) | 0x1F);
        h[6] = (byte) 0xFC;
        return h;
    }

    private static int sampleRateIndex(int sr) {
        switch (sr) {
            case 96000: return 0; case 88200: return 1; case 64000: return 2;
            case 48000: return 3; case 44100: return 4; case 32000: return 5;
            case 24000: return 6; case 22050: return 7; case 16000: return 8;
            case 12000: return 9; case 11025: return 10; case 8000: return 11;
            default: return 4; // fallback 44.1k
        }
    }

    public interface ProgressSink   { void onProgress(int percent); }
    public interface CancelToken    { boolean isCancelled(); }
}
