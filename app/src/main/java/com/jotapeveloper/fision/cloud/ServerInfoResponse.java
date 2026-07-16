package com.jotapeveloper.fision.cloud;

import com.google.gson.annotations.SerializedName;

/**
 * Response of GET /server/info. Cached client-side after login. Fields are
 * fixed for the life of the server process (they are computed once at boot).
 */
public class ServerInfoResponse {
    @SerializedName("gpu")            public boolean gpu;
    @SerializedName("gpu_name")       public String  gpuName;
    @SerializedName("vram_total_mb")  public int     vramTotalMb;
    @SerializedName("driver")         public String  driver;
}
