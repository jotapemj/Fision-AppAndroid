package com.jotapeveloper.fision.cloud;

/**
 * State of a song in the ecosystem (local + server combined).
 * The hash is the identity. A single song may sit in any combination of the
 * three axes: server queue, local download, error.
 */
public class CloudItem {

    public enum State {
        /** Queued on the server, waiting its turn. */
        QUEUED,
        /** Server currently processing it. */
        RUNNING,
        /** Job requested cuda but the GPU is busy with another process. */
        WAITING_GPU,
        /** Server has it ready, zip being downloaded to the phone. */
        DOWNLOADING,
        /** Server has it ready but the phone does not have the stems downloaded. */
        CLOUD_ONLY,
        /** In local AND on the server. Normal happy path. */
        LOCAL_AND_CLOUD,
        /** Local only (songs added manually without going through the server). */
        LOCAL_ONLY,
        /** Failure on the server. */
        ERROR,
        /** Cancelled by the user. */
        CANCELLED
    }

    public final String hash;            // identity
    public String displayName;           // readable name (from the server or the local metadata.txt)
    public State state;
    public String jobId;                 // set when an active server job exists for this song
    public int downloadPct;              // 0..100 while state=DOWNLOADING
    public long downloadedBytes;
    public long totalBytes;
    public String errorMessage;          // populated when state=ERROR
    public String localFolderName;       // real folder name inside Songs/ (may match the hash)
    public String serverUpdated;         // server ISO timestamp used for sorting
    public int queuePosition;            // 1..N when state=QUEUED; 0 unknown or running
    public String coverPath;             // cached cover extracted from the audio before upload (in-flight)
    public String artist;                // artist extracted from the audio before upload
    public long   stickyOrderTs;         // if >0, sort this CLOUD_ONLY as if it were local with this lastModified
    public String coverUrl;              // server-relative path to fetch the artcover
    public String serverCreated;         // ISO8601 original upload date (server created_at)
    public long   submittedAt;           // local time the job was sent; orders in-flight items
    public java.util.Map<String, Object> config;  // current server config (used to prefill the reprocess wizard)

    public CloudItem(String hash) { this.hash = hash; }

    private static final java.util.regex.Pattern SERVER_HASH =
            java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    /** Server hashes are SHA-256 hex. They name folders and files on disk, so
     *  anything else from the network is rejected. */
    public static boolean isServerHash(String h) {
        return h != null && SERVER_HASH.matcher(h).matches();
    }

    public boolean isLocal() {
        return state == State.LOCAL_AND_CLOUD || state == State.LOCAL_ONLY;
    }

    /** Stems on the device, whatever the server state (a failed reprocess
     *  leaves the previous version playable). */
    public boolean hasLocalCopy() {
        return isLocal() || localFolderName != null;
    }

    public boolean isInFlight() {
        return state == State.QUEUED || state == State.RUNNING
                || state == State.WAITING_GPU || state == State.DOWNLOADING;
    }

    /** Still being separated on the server: no place in the library yet. */
    public boolean isProcessing() {
        return state == State.QUEUED || state == State.RUNNING || state == State.WAITING_GPU;
    }
}
