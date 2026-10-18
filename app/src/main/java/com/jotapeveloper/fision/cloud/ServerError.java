package com.jotapeveloper.fision.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * Error answer of the server: HTTP status plus the stable {@code code} and
 * {@code job_id} fields when present ({"detail", "code", "job_id"}). The
 * error body can be read once, so it is parsed here a single time.
 */
public final class ServerError {

    public static final String AUDIO_EMPTY     = "audio_empty";
    public static final String AUDIO_TOO_SHORT = "audio_too_short";
    public static final String JOB_LIMIT       = "job_limit";
    public static final String JOB_RUNNING     = "job_running";

    /** Shortest audio the server processes. */
    public static final int MIN_AUDIO_SEC = 15;

    public final int http;
    @Nullable public final String code;
    @Nullable public final String jobId;

    private ServerError(int http, @Nullable String code, @Nullable String jobId) {
        this.http = http;
        this.code = code;
        this.jobId = jobId;
    }

    public static ServerError of(Response<?> r) {
        String code = null, jobId = null;
        try (ResponseBody eb = r.errorBody()) {
            if (eb != null) {
                JsonElement el = JsonParser.parseString(eb.string());
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    code = string(o, "code");
                    jobId = string(o, "job_id");
                }
            }
        } catch (Throwable ignored) {
            // Not JSON (proxy page, empty body): status only.
        }
        return new ServerError(r.code(), code, jobId);
    }

    public boolean is(String c) { return c.equals(code); }

    /** Too many live jobs of the user (429 job_limit, or any 429 on a job call). */
    public boolean isJobLimit() { return http == 429; }

    @Nullable
    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
