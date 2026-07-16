package com.jotapeveloper.fision.cloud;

import java.util.List;
import java.util.Map;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Streaming;

/**
 * Retrofit interface for the self-hosted Fision server.
 * Contract defined in FisionServer/app.py.
 *
 *   POST   /jobs                       multipart file + config_json -> enqueue
 *   POST   /library/{h}/reprocess      reprocess an already-stored input
 *   GET    /jobs/{id}                  job status
 *   DELETE /jobs/{id}                  cancel (queued or running)
 *   GET    /library                    list of available songs
 *   GET    /library/{h}                metadata for one song
 *   GET    /library/{h}/zip            download stems zip (streaming)
 *   DELETE /library/{h}                delete the whole entry
 */
public interface FisionApi {

    /** Lightweight ping without token. Feeds the green/red status icon. */
    @GET("health")
    Call<Map<String, Object>> health();

    /** Server hardware summary. Values are cached server-side at boot; the
     *  client fetches this once after login and stores it locally. */
    @GET("server/info")
    Call<ServerInfoResponse> getServerInfo();

    /** Exchanges username+password for a bearer token. */
    @POST("login")
    Call<LoginResponse> login(@Body LoginRequest body);

    /** Rotates the current password. The old token is revoked on success and
     *  the response carries a fresh one. Bearer required. */
    @POST("auth/change-password")
    Call<LoginResponse> changePassword(@Body ChangePasswordRequest body);

    /** Fetches the current user's avatar image. 404 means "no avatar set" and
     *  the client should paint the initial fallback. Bearer required. */
    @Streaming
    @GET("auth/avatar")
    Call<ResponseBody> getAvatar();

    /** Uploads a new avatar image. Server enforces JPEG/PNG/WebP by magic
     *  bytes and a 2 MB size cap. Client is expected to crop + resize + encode
     *  before calling. */
    @Multipart
    @retrofit2.http.PUT("auth/avatar")
    Call<Map<String, Object>> putAvatar(@Part MultipartBody.Part file);

    @DELETE("auth/avatar")
    Call<Map<String, Object>> deleteAvatar();

    /** Live server queue: running + queued items with a 1-based position. */
    @GET("jobs")
    Call<List<JobStatusResponse>> listActiveJobs();

    @Multipart
    @POST("jobs")
    Call<JobSubmitResponse> submitJob(@Part MultipartBody.Part file,
                                      @Part("config_json") RequestBody configJson);

    @FormUrlEncoded
    @POST("library/{h}/reprocess")
    Call<JobSubmitResponse> reprocess(@Path("h") String hash,
                                      @Field("config_json") String configJson);

    @GET("jobs/{id}")
    Call<JobStatusResponse> getJob(@Path("id") String jobId);

    @DELETE("jobs/{id}")
    Call<Map<String, Object>> cancelJob(@Path("id") String jobId);

    @GET("library")
    Call<List<LibraryEntry>> listLibrary();

    @GET("library/{h}")
    Call<LibraryEntry> getLibraryEntry(@Path("h") String hash);

    @Streaming
    @GET("library/{h}/zip")
    Call<ResponseBody> downloadZip(@Path("h") String hash);

    /** Downloads the artcover.png from the server. Full path (relative to baseURL). */
    @Streaming
    @GET
    Call<ResponseBody> downloadCover(@retrofit2.http.Url String url);

    @DELETE("library/{h}")
    Call<Map<String, Object>> deleteLibrary(@Path("h") String hash);
}
