package com.jotapeveloper.fision.cloud;

import com.google.gson.annotations.SerializedName;

/**
 * Response of GET /jobs/{id} and items in GET /jobs.
 * `posicion`: 0 when running, N>=1 when queued (1-based), null when finished.
 */
public class JobStatusResponse {
    @SerializedName("job_id")   public String jobId;
    @SerializedName("hash")     public String hash;
    @SerializedName("nombre")   public String nombre;
    @SerializedName("estado")   public String estado;
    @SerializedName("posicion") public Integer posicion;
    @SerializedName("error")    public String error;
}
