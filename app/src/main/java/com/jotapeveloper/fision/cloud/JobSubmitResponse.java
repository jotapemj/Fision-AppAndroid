package com.jotapeveloper.fision.cloud;

import com.google.gson.annotations.SerializedName;

/** Response of POST /jobs: hash always present; job_id only when it was not a cache hit. */
public class JobSubmitResponse {
    @SerializedName("hash")    public String hash;
    @SerializedName("job_id")  public String jobId;
    @SerializedName("estado")  public String estado;
    @SerializedName("cached")  public boolean cached;
}
