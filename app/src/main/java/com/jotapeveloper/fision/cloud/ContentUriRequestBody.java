package com.jotapeveloper.fision.cloud;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

/**
 * RequestBody that streams directly from a content:// Uri without copying the
 * whole audio into memory. Suitable for large uploads (>100MB) without OOM.
 */
public class ContentUriRequestBody extends RequestBody {

    private final ContentResolver cr;
    private final Uri uri;
    private final MediaType mediaType;
    private final long contentLength;

    public ContentUriRequestBody(ContentResolver cr, Uri uri, String mime) {
        this.cr = cr;
        this.uri = uri;
        this.mediaType = mime != null ? MediaType.parse(mime) : null;
        this.contentLength = querySize(cr, uri);
    }

    private static long querySize(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) {
                    long v = c.getLong(idx);
                    if (v > 0) return v;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    @Override public MediaType contentType() { return mediaType; }
    @Override public long contentLength() { return contentLength; }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open " + uri);
            try (Source source = Okio.source(in)) {
                sink.writeAll(source);
            }
        }
    }
}
