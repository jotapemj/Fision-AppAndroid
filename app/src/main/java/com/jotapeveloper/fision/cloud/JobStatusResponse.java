package com.jotapeveloper.fision.cloud;

import com.google.gson.annotations.SerializedName;

/**
 * Response of GET /jobs/{id} and items in GET /jobs.
 * `position`: 0 when running, N>=1 when queued (1-based), null when finished.
 * `error_code` is the stable failure reason; `error` is free text (fallback only).
 */
public class JobStatusResponse {
    @SerializedName("job_id")     public String jobId;
    @SerializedName("hash")       public String hash;
    @SerializedName("filename")   public String filename;
    @SerializedName("state")      public String state;
    @SerializedName("position")   public Integer position;
    @SerializedName("error")      public String error;
    @SerializedName("error_code") public String errorCode;
}
