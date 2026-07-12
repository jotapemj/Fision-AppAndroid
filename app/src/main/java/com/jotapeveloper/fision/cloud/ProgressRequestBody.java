package com.jotapeveloper.fision.cloud;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

/**
 * Wraps a RequestBody and reports written bytes to the Listener. Lets the
 * consumer draw real upload progress. Callback throttling happens in the
 * consumer (UI); this wrapper simply reports raw counts each chunk.
 */
public class ProgressRequestBody extends RequestBody {

    public interface Listener {
        void onProgress(long sent, long total);
    }

    private final RequestBody delegate;
    private final Listener listener;

    public ProgressRequestBody(RequestBody delegate, Listener listener) {
        this.delegate = delegate;
        this.listener = listener;
    }

    @Override public MediaType contentType() { return delegate.contentType(); }

    @Override public long contentLength() {
        try { return delegate.contentLength(); } catch (IOException e) { return -1; }
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        CountingSink counter = new CountingSink(sink, contentLength(), listener);
        BufferedSink buffered = Okio.buffer(counter);
        delegate.writeTo(buffered);
        buffered.flush();
    }

    private static class CountingSink extends ForwardingSink {
        private final long total;
        private final Listener listener;
        private long sent = 0L;

        CountingSink(Sink delegate, long total, Listener listener) {
            super(delegate);
            this.total = total;
            this.listener = listener;
        }

        @Override
        public void write(Buffer source, long byteCount) throws IOException {
            super.write(source, byteCount);
            sent += byteCount;
            if (listener != null) listener.onProgress(sent, total);
        }
    }
}
