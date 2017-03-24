package okhttp3.internal.tcp;

import okhttp3.*;
import okhttp3.internal.*;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.*;
import okhttp3.internal.http1.Http1Codec;
import okhttp3.internal.http2.*;
import okio.*;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.checkOffsetAndCount;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;
import static okhttp3.internal.http2.Header.*;
import static okhttp3.internal.http2.Header.RESPONSE_STATUS;
import static okhttp3.internal.http2.Header.TARGET_SCHEME;

/**
 * Created by a.viter on 16.03.2017.
 */
public class OtTcp1Codec implements HttpCodec {

    private static final int STATE_IDLE = 0; // Idle connections are ready to write request headers.
    private static final int STATE_OPEN_REQUEST_BODY = 1;
    private static final int STATE_WRITING_REQUEST_BODY = 2;
    private static final int STATE_READ_RESPONSE_HEADERS = 3;
    private static final int STATE_OPEN_RESPONSE_BODY = 4;
    private static final int STATE_READING_RESPONSE_BODY = 5;
    private static final int STATE_CLOSED = 6;

    /** The client that configures this stream. May be null for HTTPS proxy tunnels. */
    final OkHttpClient client;
    /** The stream allocation that owns this stream. May be null for HTTPS proxy tunnels. */
    final StreamAllocation streamAllocation;

    final BufferedSource source;
    final BufferedSink sink;
    int state = STATE_IDLE;

    public OtTcp1Codec(OkHttpClient client, StreamAllocation streamAllocation, BufferedSource source,
                      BufferedSink sink) {
        this.client = client;
        this.streamAllocation = streamAllocation;
        this.source = source;
        this.sink = sink;
    }

    @Override public Sink createRequestBody(Request request, long contentLength) {

        if ("chunked".equalsIgnoreCase(request.header("Transfer-Encoding"))) {
            // Stream a request body of unknown length.
            return newChunkedSink();
        }

        if (contentLength != -1) {
            // Stream a request body of a known length.
            return newFixedLengthSink(contentLength);
        }

        throw new IllegalStateException(
                "Cannot stream a request body without chunked encoding or a known content length!");
    }

    @Override public void cancel() {
        RealConnection connection = streamAllocation.connection();
        if (connection != null) connection.cancel();
    }

    /**
     * Prepares the HTTP headers and sends them to the server.
     *
     * <p>For streaming requests with a body, headers must be prepared <strong>before</strong> the
     * output stream has been written to. Otherwise the body would need to be buffered!
     *
     * <p>For non-streaming requests with a body, headers must be prepared <strong>after</strong> the
     * output stream has been written to and closed. This ensures that the {@code Content-Length}
     * header field receives the proper value.
     */
    @Override public void writeRequestHeaders(Request request) throws IOException {
        String requestLine = RequestLine.get(
                request, streamAllocation.connection().route().proxy().type());
        writeRequest(request.headers(), requestLine);
    }

    @Override public ResponseBody openResponseBody(Response response) throws IOException {
        Source source = getTransferStream(response);
        return new RealResponseBody(response.headers(), Okio.buffer(source));
    }

    private Source getTransferStream(Response response) throws IOException {
        /*if (!HttpHeaders.hasBody(response)) {
            return newFixedLengthSource(0);
        }

        if ("chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
            return newChunkedSource(response.request().url());
        }

        long contentLength = HttpHeaders.contentLength(response);
        if (contentLength != -1) {
            return newFixedLengthSource(contentLength);
        }

        // Wrap the input stream from the connection (rather than just returning
        // "socketIn" directly here), so that we can control its use after the
        // reference escapes.
        return newUnknownLengthSource();*/

        // читает 10 символов ut8 по 2 байта
        return newFixedLengthSource(10);
    }

    /** Returns true if this connection is closed. */
    public boolean isClosed() {
        return state == STATE_CLOSED;
    }

    @Override public void flushRequest() throws IOException {
        sink.flush();
    }

    @Override public void finishRequest() throws IOException {
        sink.flush();
    }

    //** Returns bytes of a request header for sending on an HTTP transport. *//*
    public void writeRequest(Headers headers, String requestLine) throws IOException {
        if (state != STATE_IDLE) throw new IllegalStateException("state: " + state);
        sink.writeUtf8(requestLine).writeUtf8("\r\n");
        sink.writeUtf8("\r\n");
        state = STATE_OPEN_REQUEST_BODY;
    }

    @Override public Response.Builder readResponseHeaders(boolean expectContinue) throws IOException {
        if (state != STATE_OPEN_REQUEST_BODY ) {
            throw new IllegalStateException("state: " + state);
        }




            Response.Builder responseBuilder = new Response.Builder()
                    .protocol(Protocol.OT_TCP_1_0)
                    .code(200)
                    .message("see source code of custom okhttp lib: OtTcp1Codec.java");



            state = STATE_OPEN_RESPONSE_BODY;
            return responseBuilder;

    }

   /* *//** Reads headers or trailers. *//*
    public Headers readHeaders() throws IOException {
        Headers.Builder headers = new Headers.Builder();
        // parse the result headers until the first blank line
        for (String line; (line = source.readUtf8LineStrict()).length() != 0; ) {
            Internal.instance.addLenient(headers, line);
        }
        return headers.build();
    }
*/
    public Sink newChunkedSink() {
        if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
        state = STATE_WRITING_REQUEST_BODY;
        return new OtTcp1Codec.ChunkedSink();
    }
    public Sink newFixedLengthSink(long contentLength) {
        if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
        state = STATE_WRITING_REQUEST_BODY;
        return new OtTcp1Codec.FixedLengthSink(contentLength);
    }

    public Source newFixedLengthSource(long length) throws IOException {
        if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
        state = STATE_READING_RESPONSE_BODY;
        return new OtTcp1Codec.FixedLengthSource(length);
    }

    /*public Source newChunkedSource(HttpUrl url) throws IOException {
        if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
        state = STATE_READING_RESPONSE_BODY;
        return new OtTcp1Codec.ChunkedSource(url);
    }

    public Source newUnknownLengthSource() throws IOException {
        if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
        if (streamAllocation == null) throw new IllegalStateException("streamAllocation == null");
        state = STATE_READING_RESPONSE_BODY;
        streamAllocation.noNewStreams();
        return new OtTcp1Codec.UnknownLengthSource();
    }*/

    /**
     * Sets the delegate of {@code timeout} to {@link Timeout#NONE} and resets its underlying timeout
     * to the default configuration. Use this to avoid unexpected sharing of timeouts between pooled
     * connections.
     */
    void detachTimeout(ForwardingTimeout timeout) {
        Timeout oldDelegate = timeout.delegate();
        timeout.setDelegate(Timeout.NONE);
        oldDelegate.clearDeadline();
        oldDelegate.clearTimeout();
    }

    /** An HTTP body with a fixed length known in advance. */
    private final class FixedLengthSink implements Sink {
        private final ForwardingTimeout timeout = new ForwardingTimeout(sink.timeout());
        private boolean closed;
        private long bytesRemaining;

        FixedLengthSink(long bytesRemaining) {
            this.bytesRemaining = bytesRemaining;
        }

        @Override public Timeout timeout() {
            return timeout;
        }

        @Override public void write(Buffer source, long byteCount) throws IOException {
            if (closed) throw new IllegalStateException("closed");
            checkOffsetAndCount(source.size(), 0, byteCount);
            if (byteCount > bytesRemaining) {
                throw new ProtocolException("expected " + bytesRemaining
                        + " bytes but received " + byteCount);
            }
            sink.write(source, byteCount);
            bytesRemaining -= byteCount;
        }

        @Override public void flush() throws IOException {
            if (closed) return; // Don't throw; this stream might have been closed on the caller's behalf.
            sink.flush();
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (bytesRemaining > 0) throw new ProtocolException("unexpected end of stream");
            detachTimeout(timeout);
            state = STATE_READ_RESPONSE_HEADERS;
        }
    }

    /**
     * An HTTP body with alternating chunk sizes and chunk bodies. It is the caller's responsibility
     * to buffer chunks; typically by using a buffered sink with this sink.
     */
    private final class ChunkedSink implements Sink {
        private final ForwardingTimeout timeout = new ForwardingTimeout(sink.timeout());
        private boolean closed;

        ChunkedSink() {
        }

        @Override public Timeout timeout() {
            return timeout;
        }

        @Override public void write(Buffer source, long byteCount) throws IOException {
            if (closed) throw new IllegalStateException("closed");
            if (byteCount == 0) return;

            sink.writeHexadecimalUnsignedLong(byteCount);
            sink.writeUtf8("\r\n");
            sink.write(source, byteCount);
            sink.writeUtf8("\r\n");
        }

        @Override public synchronized void flush() throws IOException {
            if (closed) return; // Don't throw; this stream might have been closed on the caller's behalf.
            sink.flush();
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            sink.writeUtf8("0\r\n\r\n");
            detachTimeout(timeout);
            state = STATE_READ_RESPONSE_HEADERS;
        }
    }

    private abstract class AbstractSource implements Source {
        protected final ForwardingTimeout timeout = new ForwardingTimeout(source.timeout());
        protected boolean closed;

        @Override public Timeout timeout() {
            return timeout;
        }

        /**
         * Closes the cache entry and makes the socket available for reuse. This should be invoked when
         * the end of the body has been reached.
         */
        protected final void endOfInput(boolean reuseConnection) throws IOException {
            if (state == STATE_CLOSED) return;
            if (state != STATE_READING_RESPONSE_BODY) throw new IllegalStateException("state: " + state);

            detachTimeout(timeout);

            state = STATE_CLOSED;
            if (streamAllocation != null) {
                streamAllocation.streamFinished(!reuseConnection, OtTcp1Codec.this);
            }
        }
    }

    /** An HTTP body with a fixed length specified in advance. */
    private class FixedLengthSource extends OtTcp1Codec.AbstractSource {
        private long bytesRemaining;

        public FixedLengthSource(long length) throws IOException {
            bytesRemaining = length;
            if (bytesRemaining == 0) {
                endOfInput(true);
            }
        }

        @Override public long read(Buffer sink, long byteCount) throws IOException {
            if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            if (closed) throw new IllegalStateException("closed");
            if (bytesRemaining == 0) return -1;

            long read = source.read(sink, Math.min(bytesRemaining, byteCount));
            if (read == -1) {
                endOfInput(false); // The server didn't supply the promised content length.
                throw new ProtocolException("unexpected end of stream");
            }

            bytesRemaining -= read;
            if (bytesRemaining == 0) {
                endOfInput(true);
            }
            return read;
        }

        @Override public void close() throws IOException {
            if (closed) return;

            if (bytesRemaining != 0 && !Util.discard(this, DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
                endOfInput(false);
            }

            closed = true;
        }
    }
/*
    *//** An HTTP body with alternating chunk sizes and chunk bodies. *//*
    private class ChunkedSource extends OtTcp1Codec.AbstractSource {
        private static final long NO_CHUNK_YET = -1L;
        private final HttpUrl url;
        private long bytesRemainingInChunk = NO_CHUNK_YET;
        private boolean hasMoreChunks = true;

        ChunkedSource(HttpUrl url) {
            this.url = url;
        }

        @Override public long read(Buffer sink, long byteCount) throws IOException {
            if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            if (closed) throw new IllegalStateException("closed");
            if (!hasMoreChunks) return -1;

            if (bytesRemainingInChunk == 0 || bytesRemainingInChunk == NO_CHUNK_YET) {
                readChunkSize();
                if (!hasMoreChunks) return -1;
            }

            long read = source.read(sink, Math.min(byteCount, bytesRemainingInChunk));
            if (read == -1) {
                endOfInput(false); // The server didn't supply the promised chunk length.
                throw new ProtocolException("unexpected end of stream");
            }
            bytesRemainingInChunk -= read;
            return read;
        }

        private void readChunkSize() throws IOException {
            // Read the suffix of the previous chunk.
            if (bytesRemainingInChunk != NO_CHUNK_YET) {
                source.readUtf8LineStrict();
            }
            try {
                bytesRemainingInChunk = source.readHexadecimalUnsignedLong();
                String extensions = source.readUtf8LineStrict().trim();
                if (bytesRemainingInChunk < 0 || (!extensions.isEmpty() && !extensions.startsWith(";"))) {
                    throw new ProtocolException("expected chunk size and optional extensions but was \""
                            + bytesRemainingInChunk + extensions + "\"");
                }
            } catch (NumberFormatException e) {
                throw new ProtocolException(e.getMessage());
            }
            if (bytesRemainingInChunk == 0L) {
                hasMoreChunks = false;
                //HttpHeaders.receiveHeaders(client.cookieJar(), url, readHeaders());
                endOfInput(true);
            }
        }

        @Override public void close() throws IOException {
            if (closed) return;
            if (hasMoreChunks && !Util.discard(this, DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
                endOfInput(false);
            }
            closed = true;
        }
    }

    *//** An HTTP message body terminated by the end of the underlying stream. *//*
    private class UnknownLengthSource extends OtTcp1Codec.AbstractSource {
        private boolean inputExhausted;

        UnknownLengthSource() {
        }

        @Override public long read(Buffer sink, long byteCount)
                throws IOException {
            if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            if (closed) throw new IllegalStateException("closed");
            if (inputExhausted) return -1;

            long read = source.read(sink, 10);
            if (read == -1) {
                inputExhausted = true;
                endOfInput(true);
                return -1;
            }
            return read;
        }

        @Override public void close() throws IOException {
            if (closed) return;
            if (!inputExhausted) {
                endOfInput(false);
            }
            closed = true;
        }
    }*/
}
