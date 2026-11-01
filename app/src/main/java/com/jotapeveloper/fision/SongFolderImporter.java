package com.jotapeveloper.fision;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.WorkerThread;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Local mode: imports a song folder picked with the system folder picker.
 *
 * Only the files the player reads are taken (known stems, metronomes, chord,
 * beat and downbeat charts, metadata, cover and cached lyrics), each under a
 * size limit. Everything is copied to a private staging folder first and
 * checked there (every audio file fully decoded, every text file parsed); the
 * song appears in the library only when all of it is valid, with a single
 * rename. Anything else in the picked folder is left behind.
 */
public final class SongFolderImporter {

    private SongFolderImporter() {}

    public static final class Result {
        public final boolean ok;
        /** Folder name of the imported song (ok), or the offending file (error). */
        @Nullable public final String name;
        @StringRes public final int error;

        private Result(boolean ok, @Nullable String name, int error) {
            this.ok = ok; this.name = name; this.error = error;
        }
        static Result ok(String folder)                 { return new Result(true, folder, 0); }
        static Result fail(@StringRes int e, @Nullable String file) { return new Result(false, file, e); }
    }

    private static final long MB = 1024L * 1024L;
    private static final long MAX_AUDIO   = 600 * MB;
    private static final long MAX_TOTAL   = 2500 * MB;
    private static final long MAX_CHART   = MB;
    private static final long MAX_META    = 16 * 1024;
    private static final long MAX_COVER   = 15 * MB;
    private static final long MAX_LYRICS  = 8 * MB;
    private static final int  MIN_AUDIO_S = 5;
    /** Allowed length difference between the audio files of one song. */
    private static final double MAX_LENGTH_SPREAD_S = 2.0;

    private static final String[] METRONOMES = {"metronome", "metronome_half", "metronome_double"};
    private static final String[] TEXT_FILES = {"chords.txt", "chords_basic.txt", "beats.txt",
            "downbeats.txt", "metadata.txt", "lyrics.json", "artcover.png"};
    private static final Pattern CHORD = Pattern.compile("[A-Za-z0-9#;:/+()\\-.]{1,24}");

    /** Imports the folder at [treeUri] into Songs/. Blocking: call off the main thread. */
    @WorkerThread
    @NonNull
    public static Result importTree(@NonNull Context ctx, @NonNull Uri treeUri) {
        DocumentFile tree = DocumentFile.fromTreeUri(ctx, treeUri);
        if (tree == null || !tree.isDirectory()) return Result.fail(R.string.import_folder_err_read, null);

        // 1) Pick the allowed files by name; two versions of one file are ambiguous.
        Map<String, DocumentFile> picked = new LinkedHashMap<>();
        for (DocumentFile f : tree.listFiles()) {
            if (!f.isFile() || f.getName() == null) continue;
            String canonical = canonicalName(f.getName());
            if (canonical == null) continue;
            String key = bareName(canonical);
            for (String k : picked.keySet()) {
                if (bareName(k).equals(key)) return Result.fail(R.string.import_folder_err_duplicate, f.getName());
            }
            picked.put(canonical, f);
        }

        // 2) The set the player needs.
        Result missing = checkRequired(picked.keySet());
        if (missing != null) return missing;

        // 3) Declared sizes and free space (the copy enforces the limits again).
        long total = 0;
        for (Map.Entry<String, DocumentFile> e : picked.entrySet()) {
            long len = e.getValue().length();
            if (len <= 0 || len > limitFor(e.getKey())) return Result.fail(R.string.import_folder_err_size, e.getKey());
            total += len;
        }
        File songsRoot = SongCatalog.songsRoot(ctx);
        if (!songsRoot.isDirectory() && !songsRoot.mkdirs()) return Result.fail(R.string.import_folder_err_io, null);
        if (total > MAX_TOTAL || songsRoot.getUsableSpace() < total + 64 * MB) {
            return Result.fail(R.string.import_folder_err_space, null);
        }

        String folderName = safeFolderName(tree.getName());
        File dest = new File(songsRoot, folderName);
        if (dest.exists()) return Result.fail(R.string.import_folder_err_exists, folderName);

        // 4) Copy to staging (same volume as Songs/, outside the library) and check there.
        File staging = new File(stagingRoot(ctx), UUID.randomUUID().toString());
        try {
            if (!staging.mkdirs()) return Result.fail(R.string.import_folder_err_io, null);
            for (Map.Entry<String, DocumentFile> e : picked.entrySet()) {
                File out = new File(staging, e.getKey());
                if (!copyLimited(ctx, e.getValue().getUri(), out, limitFor(e.getKey()))) {
                    return Result.fail(R.string.import_folder_err_size, e.getKey());
                }
            }
            Result bad = validate(staging);
            if (bad != null) return bad;
            if (dest.exists()) return Result.fail(R.string.import_folder_err_exists, folderName);
            if (!staging.renameTo(dest)) return Result.fail(R.string.import_folder_err_io, null);
            return Result.ok(folderName);
        } catch (IOException e) {
            return Result.fail(R.string.import_folder_err_read, null);
        } finally {
            deleteTree(staging);
        }
    }

    /** Download progress of {@link #importZip}. */
    public interface ZipProgress {
        /** Bytes of the zip read so far. */
        void onBytes(long read);
        boolean isCancelled();
    }

    /**
     * Imports a song from a zip read as a stream (nothing but the picked files
     * is written): the entries directly under [innerDir] go through the same
     * name filter, limits and checks as a picked folder. The whole stream must
     * hash to [sha256] (hex) before the song is added as [folderName].
     * Cancelled: a failure with error 0. Blocking: call off the main thread.
     */
    @WorkerThread
    @NonNull
    public static Result importZip(@NonNull Context ctx, @NonNull InputStream raw,
                                   @NonNull String innerDir, @NonNull String folderName,
                                   @NonNull String sha256, long expectedBytes,
                                   @NonNull ZipProgress progress) {
        File songsRoot = SongCatalog.songsRoot(ctx);
        if (!songsRoot.isDirectory() && !songsRoot.mkdirs()) return Result.fail(R.string.import_folder_err_io, null);
        if (songsRoot.getUsableSpace() < expectedBytes + 64 * MB) {
            return Result.fail(R.string.import_folder_err_space, null);
        }
        File dest = new File(songsRoot, folderName);
        if (dest.exists()) return Result.fail(R.string.import_folder_err_exists, folderName);

        File staging = new File(stagingRoot(ctx), UUID.randomUUID().toString());
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            return Result.fail(R.string.import_folder_err_io, null);
        }
        long[] read = {0};
        InputStream counting = new java.io.FilterInputStream(raw) {
            @Override public int read() throws IOException {
                int b = super.read();
                if (b >= 0) progress.onBytes(++read[0]);
                return b;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (progress.isCancelled()) throw new java.io.InterruptedIOException("cancelled");
                int n = super.read(b, off, len);
                if (n > 0) { read[0] += n; progress.onBytes(read[0]); }
                return n;
            }
        };
        java.security.DigestInputStream digest = new java.security.DigestInputStream(counting, md);
        try {
            if (!staging.mkdirs()) return Result.fail(R.string.import_folder_err_io, null);
            java.util.Set<String> taken = new java.util.HashSet<>();
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(digest);
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String path = e.getName();
                if (e.isDirectory() || !path.startsWith(innerDir)) continue;
                String rest = path.substring(innerDir.length());
                if (rest.isEmpty() || rest.contains("/") || rest.contains("\\")) continue;
                String canonical = canonicalName(rest);
                if (canonical == null) continue;
                for (String k : taken) {
                    if (bareName(k).equals(bareName(canonical))) return Result.fail(R.string.import_folder_err_duplicate, rest);
                }
                taken.add(canonical);
                if (!copyLimited(zin, new File(staging, canonical), limitFor(canonical))) {
                    return Result.fail(R.string.import_folder_err_size, canonical);
                }
            }
            // The digest covers the whole file, central directory included.
            byte[] buf = new byte[64 * 1024];
            while (digest.read(buf) != -1) { /* drain */ }
            if (!toHex(md.digest()).equalsIgnoreCase(sha256)) {
                return Result.fail(R.string.import_folder_err_invalid, "zip");
            }
            Result missing = checkRequired(taken);
            if (missing != null) return missing;
            Result bad = validate(staging);
            if (bad != null) return bad;
            if (dest.exists()) return Result.fail(R.string.import_folder_err_exists, folderName);
            if (!staging.renameTo(dest)) return Result.fail(R.string.import_folder_err_io, null);
            return Result.ok(folderName);
        } catch (java.io.InterruptedIOException e) {
            return Result.fail(0, null);
        } catch (IOException e) {
            return Result.fail(R.string.import_folder_err_read, null);
        } finally {
            deleteTree(staging);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /** Failure for the first file the player needs that is missing, or null. */
    @Nullable
    private static Result checkRequired(java.util.Set<String> names) {
        boolean vocals = hasAudio(names, "vocals");
        boolean backing = hasAudio(names, "other") || hasAudio(names, "instrumental");
        if (!vocals || !backing) return Result.fail(R.string.import_folder_err_stems, null);
        for (String m : METRONOMES) {
            if (!hasAudio(names, m)) return Result.fail(R.string.import_folder_err_missing, m + ".flac");
        }
        if (!names.contains("beats.txt"))  return Result.fail(R.string.import_folder_err_missing, "beats.txt");
        if (!names.contains("chords.txt")) return Result.fail(R.string.import_folder_err_missing, "chords.txt");
        return null;
    }

    /** Removes staging leftovers of an interrupted import. */
    public static void cleanStaging(@NonNull Context ctx) {
        File root = stagingRoot(ctx);
        File[] kids = root.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
    }

    // ------------------------------------------------------------------ names

    private static File stagingRoot(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), ".import");
    }

    /** Lower-case name when the file is one the player reads, null otherwise. */
    @Nullable
    private static String canonicalName(String raw) {
        String n = raw.trim().toLowerCase(Locale.ROOT);
        for (String t : TEXT_FILES) if (n.equals(t)) return n;
        String ext = n.endsWith(".flac") ? ".flac" : n.endsWith(".wav") ? ".wav" : null;
        if (ext == null) return null;
        String bare = n.substring(0, n.length() - ext.length());
        for (String m : METRONOMES) if (bare.equals(m)) return n;
        return StemSpec.isKnown(bare) ? n : null;
    }

    private static String bareName(String canonical) {
        int dot = canonical.lastIndexOf('.');
        return dot > 0 ? canonical.substring(0, dot) : canonical;
    }

    private static boolean isAudio(String canonical) {
        return canonical.endsWith(".wav") || canonical.endsWith(".flac");
    }

    private static boolean hasAudio(java.util.Set<String> names, String bare) {
        return names.contains(bare + ".flac") || names.contains(bare + ".wav");
    }

    private static long limitFor(String canonical) {
        if (isAudio(canonical)) return MAX_AUDIO;
        switch (canonical) {
            case "metadata.txt": return MAX_META;
            case "artcover.png": return MAX_COVER;
            case "lyrics.json":  return MAX_LYRICS;
            default:             return MAX_CHART;
        }
    }

    /** Folder name from the picked folder: no path or reserved characters, no leading dots. */
    private static String safeFolderName(@Nullable String raw) {
        String n = raw == null ? "" : raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1).trim();
        if (n.length() > 80) n = n.substring(0, 80).trim();
        return n.isEmpty() ? "Song" : n;
    }

    // ------------------------------------------------------------------ copy

    /** Copies at most [limit] bytes; false when the source is larger. */
    private static boolean copyLimited(Context ctx, Uri src, File dst, long limit) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(src)) {
            if (in == null) throw new IOException("no stream");
            return copyLimited(in, dst, limit);
        }
    }

    /** Same from an open stream, which is left open (a zip entry). */
    private static boolean copyLimited(InputStream in, File dst, long limit) throws IOException {
        try (OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[256 * 1024];
            long copied = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                copied += n;
                if (copied > limit) return false;
                out.write(buf, 0, n);
            }
            return copied > 0;
        }
    }

    // ------------------------------------------------------------------ checks

    /** Failure for the first invalid file in [dir], or null when all are valid. */
    @Nullable
    private static Result validate(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return Result.fail(R.string.import_folder_err_io, null);
        double minLen = Double.MAX_VALUE, maxLen = 0;
        for (File f : files) {
            String n = f.getName();
            if (!isAudio(n)) continue;
            double len = audioSeconds(f);
            if (len == TOO_BIG) return Result.fail(R.string.import_folder_err_size, n);
            if (len < MIN_AUDIO_S) return Result.fail(R.string.import_folder_err_invalid, n);
            if (n.startsWith("metronome")) continue;   // generated clicks may end earlier
            minLen = Math.min(minLen, len);
            maxLen = Math.max(maxLen, len);
        }
        if (maxLen - minLen > MAX_LENGTH_SPREAD_S) {   // stems of different songs or cut
            return Result.fail(R.string.import_folder_err_lengths, null);
        }
        for (File f : files) {
            String n = f.getName();
            boolean ok;
            switch (n) {
                case "beats.txt":        ok = validTimes(f, maxLen, 8, false); break;
                case "downbeats.txt":    ok = validTimes(f, maxLen, 1, true); break;
                case "chords.txt":
                case "chords_basic.txt": ok = validChords(f, maxLen); break;
                case "metadata.txt":     ok = validText(f); break;
                case "artcover.png":     ok = validCover(f); break;
                case "lyrics.json":      ok = validLyrics(f); break;
                default:                 ok = true;   // audio, checked above
            }
            if (!ok) return Result.fail(R.string.import_folder_err_invalid, n);
        }
        return null;
    }

    /** Returned by {@link #audioSeconds} when the decoded file does not fit in memory. */
    private static final double TOO_BIG = -2;

    /**
     * Full decode with the export decoder; -1 when the file is not playable,
     * {@link #TOO_BIG} when it is too long to check.
     */
    private static double audioSeconds(File f) {
        try {
            WavIO.WavData d = AudioIO.read(f);
            if (d == null || d.sampleRate < 8000 || d.sampleRate > 192000
                    || d.channels < 1 || d.channels > 2 || d.frames <= 0) return -1;
            double s = d.frames / (double) d.sampleRate;
            d.samples = null;
            return s;
        } catch (OutOfMemoryError e) {
            return TOO_BIG;
        } catch (Throwable t) {   // decoder errors
            return -1;
        }
    }

    /**
     * One time per line (seconds, ascending), optionally followed by a bar
     * position. A first value larger than the second is the BPM header the
     * player drops, so it is not counted as a time.
     */
    private static boolean validTimes(File f, double songLen, int minCount, boolean allowPosition) {
        java.util.List<Float> times = new java.util.ArrayList<>();
        try (BufferedReader r = utf8Reader(f)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("[\\s,;]+");
                if (p.length > (allowPosition ? 2 : 1)) return false;
                float t = Float.parseFloat(p[0]);
                if (!Float.isFinite(t) || t < 0f) return false;
                if (p.length == 2) Integer.parseInt(p[1]);
                times.add(t);
            }
        } catch (IOException | NumberFormatException e) {
            return false;
        }
        if (times.size() >= 2 && times.get(0) > times.get(1)) times.remove(0);
        for (int i = 0; i < times.size(); i++) {
            if (times.get(i) > songLen + 5) return false;
            if (i > 0 && times.get(i) < times.get(i - 1)) return false;
        }
        return times.size() >= minCount;
    }

    /** "CHORD TIME" per line, times ascending. */
    private static boolean validChords(File f, double songLen) {
        int count = 0;
        float prev = 0f;
        try (BufferedReader r = utf8Reader(f)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+");
                if (p.length != 2 || !CHORD.matcher(p[0]).matches()) return false;
                float t = Float.parseFloat(p[1]);
                if (!Float.isFinite(t) || t < 0f || t > songLen + 5 || t < prev) return false;
                prev = t;
                count++;
            }
        } catch (IOException | NumberFormatException e) {
            return false;
        }
        return count > 0;
    }

    /** Strict UTF-8, no control characters other than line breaks and tabs. */
    private static boolean validText(File f) {
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') return false;
            }
            return true;
        } catch (CharacterCodingException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** PNG or JPEG content (covers taken from audio tags keep the .png name
     *  with JPEG data; the player decodes either). */
    private static boolean validCover(File f) {
        byte[] sig = new byte[8];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(sig) != 8) return false;
        } catch (IOException e) {
            return false;
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        boolean isPng = true;
        for (int i = 0; i < 8; i++) if (sig[i] != png[i]) { isPng = false; break; }
        boolean isJpeg = sig[0] == (byte) 0xFF && sig[1] == (byte) 0xD8 && sig[2] == (byte) 0xFF;
        if (!isPng && !isJpeg) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return o.outWidth > 0 && o.outHeight > 0 && o.outWidth <= 8192 && o.outHeight <= 8192;
    }

    private static boolean validLyrics(File f) {
        try (BufferedReader r = utf8Reader(f)) {
            Lyrics l = Lyrics.sanitize(new com.google.gson.Gson().fromJson(r, Lyrics.class));
            return l != null && l.segments != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static BufferedReader utf8Reader(File f) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)));
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
