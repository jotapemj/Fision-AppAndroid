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

    /** Trades the current token for a new one with a full TTL. A 401 means the
     *  session is gone (expired, revoked, password changed). Bearer required. */
    @POST("auth/refresh")
    Call<LoginResponse> refresh();

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

    /** Opt-in lyrics transcription (Whisper). Server 409 means vocals.flac
     *  is not ready yet or another lyrics job is already active for this hash. */
    @POST("library/{h}/lyrics")
    Call<JobSubmitResponse> submitLyrics(@Path("h") String hash,
                                         @Body LyricsRequest body);

    /** Downloads lyrics.json for a hash. 404 when it was never generated. */
    @Streaming
    @GET("library/{h}/lyrics")
    Call<ResponseBody> downloadLyrics(@Path("h") String hash);

    /** Removes the server-side lyrics.json so a new job can be launched
     *  in a different language. */
    @DELETE("library/{h}/lyrics")
    Call<Map<String, Object>> deleteLyrics(@Path("h") String hash);

    // ---- Playlists (server 1.8.0) ----

    /** User playlists without their songs, last change first. */
    @GET("playlists")
    Call<List<PlaylistDto>> listPlaylists();

    /** One playlist with its song hashes in play order. 404 = deleted. */
    @GET("playlists/{id}")
    Call<PlaylistDto> getPlaylist(@Path("id") String id);

    @POST("playlists")
    Call<PlaylistDto> createPlaylist(@Body PlaylistSaveRequest body);

    /** Replaces the whole playlist (title and ordered songs). */
    @retrofit2.http.PUT("playlists/{id}")
    Call<PlaylistDto> updatePlaylist(@Path("id") String id, @Body PlaylistSaveRequest body);

    /** Deletes the playlist and its cover. */
    @DELETE("playlists/{id}")
    Call<Map<String, Object>> deletePlaylist(@Path("id") String id);

    /** Square JPEG/PNG/WebP, 2 MB max. 413 = too large, 400 = bad format. */
    @Multipart
    @retrofit2.http.PUT("playlists/{id}/cover")
    Call<Map<String, Object>> putPlaylistCover(@Path("id") String id,
                                               @Part MultipartBody.Part file);

    /** [version] is the playlist updated_at, so a changed cover is not
     *  served from a cache. */
    @Streaming
    @GET("playlists/{id}/cover")
    Call<ResponseBody> getPlaylistCover(@Path("id") String id,
                                        @retrofit2.http.Query("v") String version);

    @DELETE("playlists/{id}/cover")
    Call<Map<String, Object>> deletePlaylistCover(@Path("id") String id);
}
