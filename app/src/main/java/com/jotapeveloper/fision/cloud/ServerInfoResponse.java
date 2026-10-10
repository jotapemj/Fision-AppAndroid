package com.jotapeveloper.fision.cloud;

import com.google.gson.annotations.SerializedName;

/**
 * Response of GET /server/info. Cached client-side. GPU name, total VRAM and
 * driver are fixed for the life of the server process; the free VRAM is read
 * live on every request.
 */
public class ServerInfoResponse {
    @SerializedName("gpu")            public boolean gpu;
    @SerializedName("gpu_name")       public String  gpuName;
    @SerializedName("vram_total_mb")  public int     vramTotalMb;
    @SerializedName("driver")         public String  driver;
    // Server semver reported since FisionServer 1.1.0. Older servers omit the
    // field and Gson leaves it null; callers must handle that case.
    @SerializedName("version")        public String  version;
    // Since FisionServer 1.4.0: free VRAM right now (each request runs
    // nvidia-smi, ~50 ms: no polling) and the minimum the server needs to run
    // a GPU job (FISION_GPU_MIN_VRAM_MB). Null on older servers.
    @SerializedName("vram_free_mb")   public Integer vramFreeMb;
    @SerializedName("vram_min_mb")    public Integer vramMinMb;
}
