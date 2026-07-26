package com.jotapeveloper.fision.cloud;

import com.google.gson.annotations.SerializedName;

/** Body of POST /library/{hash}/lyrics. A null language asks the server for
 *  auto-detection (Whisper picks the language from vocals.flac). */
public class LyricsRequest {
    @SerializedName("language") public String language;

    public LyricsRequest(String language) {
        this.language = language;
    }
}
