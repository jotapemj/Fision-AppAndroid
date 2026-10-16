package com.jotapeveloper.fision.cloud;

import android.content.Context;

import androidx.annotation.NonNull;

import com.jotapeveloper.fision.R;

/** User text for a playlist server error, chosen by HTTP status and the
 *  server's stable code. The server detail is only shown for unknown codes. */
public final class PlaylistErrors {

    private PlaylistErrors() {}

    @NonNull
    public static String message(@NonNull Context ctx, @NonNull PlaylistRepository.PlaylistError e) {
        if (e.isNetwork()) return ctx.getString(R.string.playlist_err_network);
        if (e.cover) {
            if (e.http == 413) return ctx.getString(R.string.playlist_err_cover_too_large);
            if (e.http == 400) return ctx.getString(R.string.playlist_err_cover_format);
        }
        if (e.isGone()) return ctx.getString(R.string.playlist_err_gone);
        if (e.code != null) {
            switch (e.code) {
                case "title_length":    return ctx.getString(R.string.playlist_err_title_length,
                        PlaylistRepository.MAX_TITLE_LENGTH);
                case "song_not_found":  return ctx.getString(R.string.playlist_err_song_not_found);
                case "song_duplicate":  return ctx.getString(R.string.playlist_err_song_duplicate);
                case "song_limit":      return ctx.getString(R.string.playlist_err_song_limit,
                        PlaylistRepository.MAX_SONGS);
                case "playlist_limit":  return ctx.getString(R.string.playlist_err_playlist_limit,
                        PlaylistRepository.MAX_PLAYLISTS);
            }
        }
        if (e.detail != null && !e.detail.isEmpty()) return e.detail;
        return ctx.getString(R.string.playlist_err_generic, e.http);
    }
}
