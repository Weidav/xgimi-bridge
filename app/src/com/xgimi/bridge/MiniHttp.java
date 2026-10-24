package com.xgimi.bridge;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hardened, zero-dependency HTTP/1.1 server built on standard java.net.ServerSocket.
 * Enforces strict RFC 9112 framing, absolute monotonic deadlines, safe arithmetic on chunks,
 * per-peer and global admission limits, and write watchdogs.
 */
public abstract class MiniHttp {
    private static final String TAG = "XgimiBridge";

    public enum Method {
        GET, POST, PUT, DELETE, HEAD, OPTIONS, UNKNOWN
    }

    public static class Limits {
        public int maxRequestLineBytes = 4096;
        public int maxHeaderLineBytes = 4096;
        public int maxHeadersAggregateBytes = 16384;
        public int maxHeaderCount = 64;
        public int maxBodyBytes = 65536;
        public int maxChunkSizeLineBytes = 128;
        public int maxTrailerBytes = 4096;
        public int maxTrailerCount = 16;
        public long headerReadDeadlineMs = 5000;
        public long requestReadDeadlineMs = 10000;
        public long responseWriteDeadlineMs = 5000;
        public long idleKeepAliveMs = 2000;
        public int maxRequestsPerConnection = 100;
        public int maxActiveConnections = 64;
        public int maxActivePerPeer = 8;
        public boolean allowChunked = true;
        public boolean allowTrailers = true;
        public boolean closeAfterResponse = false;

        public static Limits forPort8080() {
            return new Limits();
        }

        public static Limits forPort8081() {
            Limits l = new Limits();
            l.maxRequestLineBytes = 512;
            l.maxHeaderLineBytes = 1024;
            l.maxHeadersAggregateBytes = 4096;
            l.maxHeaderCount = 16;
            l.maxBodyBytes = 0;
            l.maxChunkSizeLineBytes = 0;
            l.maxTrailerBytes = 0;
            l.maxTrailerCount = 0;
            l.headerReadDeadlineMs = 1000;
            l.requestReadDeadlineMs = 1000;
            l.responseWriteDeadlineMs = 1000;
            l.idleKeepAliveMs = 0;
            l.maxRequestsPerConnection = 1;
            l.maxActiveConnections = 8;
            l.maxActivePerPeer = 2;
            l.allowChunked = false;
            l.allowTrailers = false;
            l.closeAfterResponse = true;
            return l;
        }
    }

    public static final class Response {
        public enum Status {
            OK(200, "OK"),
            ACCEPTED(202, "Accepted"),
            BAD_REQUEST(400, "Bad Request"),
            FORBIDDEN(403, "Forbidden"),
            NOT_FOUND(404, "Not Found"),
            METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
            REQUEST_TIMEOUT(408, "Request Timeout"),
            PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
            UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type"),
            INTERNAL_ERROR(500, "Internal Server Error"),
            NOT_IMPLEMENTED(501, "Not Implemented"),
            BAD_GATEWAY(502, "Bad Gateway"),
            SERVICE_UNAVAILABLE(503, "Service Unavailable"),
            GATEWAY_TIMEOUT(504, "Gateway Timeout");

            public final int code;
            public final String description;

            Status(int code, String description) {
                this.code = code;
                this.description = description;
            }

            public int getRequestStatus() {
                return code;
            }

            public String getDescription() {
                return code + " " + description;
            }
        }

        public final Status status;
        public final String contentType;
        public final byte[] body;
        public final InputStream dataStream;
        public final long dataLength;
        public final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        public Response(Status status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body != null ? body : new byte[0];
            this.dataStream = null;
            this.dataLength = this.body.length;
        }

        public Response(Status status, String contentType, InputStream dataStream, long dataLength) {
            this.status = status;
            this.contentType = contentType;
            this.body = null;
            this.dataStream = dataStream;
            this.dataLength = dataLength;
        }

        public void addHeader(String name, String value) {
            headers.put(name, value);
        }

        public Status getStatus() {
            return status;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getBody() {
            return body;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }
    }

    public static final class Request {
        public final Method method;
        public final String uri;
        public final String query;
        public final Map<String, String> parms;
        public final Map<String, String> headers;
        public final byte[] body;
        public final InetAddress remoteAddress;

        public Request(Method method, String uri, String query, Map<String, String> parms,
                       Map<String, String> headers, byte[] body, InetAddress remoteAddress) {
            this.method = method;
            this.uri = uri;
            this.query = query;
            this.parms = parms != null ? parms : Collections.<String, String>emptyMap();
            this.headers = headers != null ? headers : Collections.<String, String>emptyMap();
            this.body = body != null ? body : new byte[0];
            this.remoteAddress = remoteAddress;
        }

        public Method getMethod() {
            return method;
        }

        public String getUri() {
            return uri;
        }

        public String getQuery() {
            return query;
        }

        public Map<String, String> getParms() {
            return parms;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public byte[] getBody() {
            return body;
        }

        public InetAddress getRemoteAddress() {
            return remoteAddress;
        }
    }

    private final int port;
    protected final Limits limits;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ThreadPoolExecutor executor;
    private ScheduledExecutorService watchdogService;
    private volatile boolean running = false;

    private final Set<Socket> openSockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final ConcurrentHashMap<InetAddress, AtomicInteger> peerConnections = new ConcurrentHashMap<>();

    private static final ThreadLocal<SimpleDateFormat> RFC1123 = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
            return sdf;
        }
    };

    protected MiniHttp(int port) {
        this(port, Limits.forPort8080());
    }

    protected MiniHttp(int port, Limits limits) {
        this.port = port;
        this.limits = limits != null ? limits : Limits.forPort8080();
    }

    public synchronized void start() throws IOException {
        if (running) return;

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;

        executor = new ThreadPoolExecutor(
            2, limits.maxActiveConnections, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "MiniHttp-" + port + "-Worker-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );

        watchdogService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MiniHttp-" + port + "-Watchdog");
                t.setDaemon(true);
                return t;
            }
        });

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "MiniHttp-" + port + "-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        for (Socket s : openSockets) {
            closeQuietly(s);
        }
        openSockets.clear();
        peerConnections.clear();
        activeConnections.set(0);

        if (watchdogService != null) {
            watchdogService.shutdownNow();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    public boolean isAlive() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public int getListeningPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                final InetAddress peer = socket.getInetAddress();

                // Admission control: global ceiling
                int currentGlobal = activeConnections.incrementAndGet();
                if (currentGlobal > limits.maxActiveConnections) {
                    activeConnections.decrementAndGet();
                    sendOverloadAndClose(socket, "Global connection limit exceeded");
                    continue;
                }

                // Admission control: per-peer limit
                AtomicInteger peerCounter = peerConnections.computeIfAbsent(peer, new java.util.function.Function<InetAddress, AtomicInteger>() {
                    @Override
                    public AtomicInteger apply(InetAddress k) {
                        return new AtomicInteger(0);
                    }
                });
                int currentPeer = peerCounter.incrementAndGet();
                if (currentPeer > limits.maxActivePerPeer) {
                    peerCounter.decrementAndGet();
                    activeConnections.decrementAndGet();
                    sendOverloadAndClose(socket, "Per-peer connection limit exceeded");
                    continue;
                }

                openSockets.add(socket);
                try {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                handleConnection(socket);
                            } finally {
                                openSockets.remove(socket);
                                AtomicInteger pc = peerConnections.get(peer);
                                if (pc != null) {
                                    if (pc.decrementAndGet() <= 0) {
                                        peerConnections.remove(peer, pc);
                                    }
                                }
                                activeConnections.decrementAndGet();
                                closeQuietly(socket);
                            }
                        }
                    });
                } catch (RejectedExecutionException ree) {
                    openSockets.remove(socket);
                    AtomicInteger pc = peerConnections.get(peer);
                    if (pc != null) {
                        if (pc.decrementAndGet() <= 0) {
                            peerConnections.remove(peer, pc);
                        }
                    }
                    activeConnections.decrementAndGet();
                    sendOverloadAndClose(socket, "Server worker pool saturated");
                }
            } catch (SocketException se) {
                if (!running) break;
                Log.w(TAG, "MiniHttp accept socket error: " + se.getMessage());
            } catch (IOException ie) {
                if (!running) break;
                Log.w(TAG, "MiniHttp accept IO error: " + ie.getMessage());
            } catch (Throwable t) {
                if (!running) break;
                Log.e(TAG, "MiniHttp accept unexpected error", t);
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 8192);
            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);
            int requestCount = 0;

            while (running && !socket.isClosed()) {
                requestCount++;
                if (requestCount > limits.maxRequestsPerConnection) {
                    break;
                }

                long startNano = System.nanoTime();
                long headerDeadlineNano = startNano + TimeUnit.MILLISECONDS.toNanos(limits.headerReadDeadlineMs);
                long requestDeadlineNano = startNano + TimeUnit.MILLISECONDS.toNanos(limits.requestReadDeadlineMs);

                // Read request line
                String requestLine = null;
                try {
                    // Apply keep-alive idle timeout if this is not the first request
                    if (requestCount > 1 && limits.idleKeepAliveMs > 0) {
                        setSocketTimeoutSafe(socket, limits.idleKeepAliveMs);
                    } else {
                        applyDeadlineTimeout(socket, headerDeadlineNano);
                    }
                    requestLine = readLineStrict(in, lineBuf, limits.maxRequestLineBytes, socket, headerDeadlineNano);
                } catch (SocketTimeoutException te) {
                    if (requestCount > 1) {
                        // Clean idle keep-alive timeout
                        break;
                    }
                    sendError(out, Response.Status.REQUEST_TIMEOUT, "request_timeout", "Header read deadline exceeded");
                    break;
                } catch (IOException ie) {
                    sendError(out, Response.Status.BAD_REQUEST, "invalid_request", ie.getMessage());
                    break;
                }

                if (requestLine == null) {
                    // Clean EOF from client
                    break;
                }

                // RFC 9112 §2.2: Skip up to 4 empty lines before request-line
                int emptyCount = 0;
                while (requestLine.isEmpty()) {
                    if (++emptyCount > 4) {
                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Too many leading empty lines");
                        return;
                    }
                    try {
                        applyDeadlineTimeout(socket, headerDeadlineNano);
                        requestLine = readLineStrict(in, lineBuf, limits.maxRequestLineBytes, socket, headerDeadlineNano);
                    } catch (SocketTimeoutException te) {
                        sendError(out, Response.Status.REQUEST_TIMEOUT, "request_timeout", "Header read deadline exceeded");
                        return;
                    } catch (IOException ie) {
                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", ie.getMessage());
                        return;
                    }
                    if (requestLine == null) return;
                }

                String[] parts = requestLine.split(" ");
                if (parts.length != 3) {
                    sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Malformed request line");
                    break;
                }

                String methodStr = parts[0];
                String rawUri = parts[1];
                String httpVersion = parts[2];

                if (!"HTTP/1.1".equals(httpVersion) && !"HTTP/1.0".equals(httpVersion)) {
                    sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Unsupported HTTP version: " + httpVersion);
                    break;
                }

                Method method;
                try {
                    method = Method.valueOf(methodStr.toUpperCase(Locale.US));
                } catch (IllegalArgumentException e) {
                    method = Method.UNKNOWN;
                }
                if (method == Method.UNKNOWN) {
                    sendError(out, Response.Status.NOT_IMPLEMENTED, "not_implemented", "Unsupported HTTP method: " + methodStr);
                    break;
                }

                // Rule 9: Reject absolute-form targets (e.g. http://host/path)
                if (rawUri.startsWith("http://") || rawUri.startsWith("https://") || !rawUri.startsWith("/")) {
                    sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Absolute-form and non-origin-form request targets not supported");
                    break;
                }

                // Strip fragment
                int hashIdx = rawUri.indexOf('#');
                if (hashIdx >= 0) rawUri = rawUri.substring(0, hashIdx);

                // Strip query string
                int queryIdx = rawUri.indexOf('?');
                String pathOnly = queryIdx >= 0 ? rawUri.substring(0, queryIdx) : rawUri;
                String query = queryIdx >= 0 ? rawUri.substring(queryIdx + 1) : null;
                Map<String, String> parms = parseQueryParameters(query);

                // URL decode preserving '+' as literal
                String decodedUri;
                try {
                    decodedUri = URLDecoder.decode(pathOnly.replace("+", "%2B"), "UTF-8");
                } catch (Exception e) {
                    decodedUri = pathOnly;
                }
                if (decodedUri.isEmpty() || !decodedUri.startsWith("/")) {
                    decodedUri = "/" + decodedUri;
                }

                // Read headers
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                Set<String> seenHeaderNames = new HashSet<>();
                int totalHeaderBytes = requestLine.length() + 2; // include CRLF
                int headerCount = 0;
                boolean headerError = false;

                while (true) {
                    applyDeadlineTimeout(socket, headerDeadlineNano);
                    String headerLine;
                    try {
                        headerLine = readLineStrict(in, lineBuf, limits.maxHeaderLineBytes, socket, headerDeadlineNano);
                    } catch (SocketTimeoutException te) {
                        sendError(out, Response.Status.REQUEST_TIMEOUT, "request_timeout", "Header read deadline exceeded");
                        return;
                    } catch (IOException ie) {
                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", ie.getMessage());
                        return;
                    }

                    if (headerLine == null) {
                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Unexpected EOF in request headers");
                        return;
                    }
                    if (headerLine.isEmpty()) {
                        break; // End of headers
                    }

                    headerCount++;
                    if (headerCount > limits.maxHeaderCount) {
                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Header count exceeded limit of " + limits.maxHeaderCount);
                        return;
                    }

                    totalHeaderBytes += headerLine.length() + 2;
                    if (totalHeaderBytes > limits.maxHeadersAggregateBytes) {
                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Aggregate headers exceeded limit of " + limits.maxHeadersAggregateBytes + " bytes");
                        return;
                    }

                    // Strict header framing: reject obsolete folding (starts with space or tab)
                    if (headerLine.charAt(0) == ' ' || headerLine.charAt(0) == '\t') {
                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Obsolete header line folding rejected");
                        return;
                    }

                    int colon = headerLine.indexOf(':');
                    if (colon <= 0) {
                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Malformed header line");
                        return;
                    }
                    if (headerLine.charAt(colon - 1) == ' ' || headerLine.charAt(colon - 1) == '\t') {
                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Whitespace before colon in header name");
                        return;
                    }

                    String headerName = headerLine.substring(0, colon).trim();
                    String headerValue = headerLine.substring(colon + 1).trim();

                    // Reject control characters in header name (< 0x20 or > 0x7E)
                    for (int i = 0; i < headerName.length(); i++) {
                        char c = headerName.charAt(i);
                        if (c < 0x21 || c > 0x7E) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Control character in header name");
                            return;
                        }
                    }

                    // Reject control characters in header value (< 0x20 except 0x09 HTAB)
                    for (int i = 0; i < headerValue.length(); i++) {
                        char c = headerValue.charAt(i);
                        if (c < 0x20 && c != 0x09) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Control character in header value");
                            return;
                        }
                    }

                    String lowerName = headerName.toLowerCase(Locale.US);
                    // Rule 6: Reject duplicate Host, Origin, Content-Length and Transfer-Encoding
                    if (lowerName.equals("host") || lowerName.equals("origin")
                            || lowerName.equals("content-length") || lowerName.equals("transfer-encoding")) {
                        if (seenHeaderNames.contains(lowerName)) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Duplicate " + headerName + " header rejected");
                            return;
                        }
                    }
                    seenHeaderNames.add(lowerName);
                    headers.put(headerName, headerValue);
                }

                // Rule 8: Mandatory single Host header for HTTP/1.1
                String hostHeader = headers.get("host");
                if ("HTTP/1.1".equals(httpVersion)) {
                    if (hostHeader == null || hostHeader.trim().isEmpty()) {
                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Host header required for HTTP/1.1");
                        break;
                    }
                }

                // Check Transfer-Encoding and Content-Length
                String te = headers.get("transfer-encoding");
                String cl = headers.get("content-length");
                boolean isChunked = false;

                if (te != null && cl != null) {
                    sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Request cannot contain both Transfer-Encoding and Content-Length");
                    break;
                }

                if (te != null) {
                    String teTrimmed = te.trim().toLowerCase(Locale.US);
                    if ("chunked".equals(teTrimmed)) {
                        if (!limits.allowChunked) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Chunked transfer encoding rejected on this port");
                            break;
                        }
                        isChunked = true;
                    } else {
                        sendError(out, Response.Status.NOT_IMPLEMENTED, "not_implemented", "Unsupported Transfer-Encoding: " + te);
                        break;
                    }
                }

                // Read request body under total request deadline
                byte[] body;
                if (isChunked) {
                    ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
                    int totalChunkBytes = 0;
                    boolean chunkError = false;

                    while (true) {
                        applyDeadlineTimeout(socket, requestDeadlineNano);
                        String sizeLine;
                        try {
                            sizeLine = readLineStrict(in, lineBuf, limits.maxChunkSizeLineBytes, socket, requestDeadlineNano);
                        } catch (SocketTimeoutException ste) {
                            sendError(out, Response.Status.REQUEST_TIMEOUT, "request_timeout", "Timeout reading chunk size");
                            chunkError = true;
                            break;
                        } catch (IOException ie) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", ie.getMessage());
                            chunkError = true;
                            break;
                        }

                        if (sizeLine == null) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Unexpected EOF in chunked body");
                            chunkError = true;
                            break;
                        }

                        sizeLine = sizeLine.trim();
                        int semi = sizeLine.indexOf(';');
                        String hexSize = semi >= 0 ? sizeLine.substring(0, semi).trim() : sizeLine;

                        // Rule 7: Hexadecimal nonnegative chunk lengths; reject signs (+/-), overflow
                        if (hexSize.isEmpty() || hexSize.startsWith("+") || hexSize.startsWith("-") || hexSize.length() > 8) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Invalid chunk size syntax: " + sizeLine);
                            chunkError = true;
                            break;
                        }

                        int chunkSize;
                        try {
                            long parsed = Long.parseLong(hexSize, 16);
                            if (parsed < 0 || parsed > Integer.MAX_VALUE) {
                                sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Invalid chunk size: " + hexSize);
                                chunkError = true;
                                break;
                            }
                            chunkSize = (int) parsed;
                        } catch (NumberFormatException e) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Invalid chunk size format: " + hexSize);
                            chunkError = true;
                            break;
                        }

                        if (chunkSize == 0) {
                            // Read trailing headers until empty line
                            if (limits.allowTrailers) {
                                int trailerBytes = 0;
                                int trailerCount = 0;
                                while (true) {
                                    applyDeadlineTimeout(socket, requestDeadlineNano);
                                    String trailer;
                                    try {
                                        trailer = readLineStrict(in, lineBuf, limits.maxHeaderLineBytes, socket, requestDeadlineNano);
                                    } catch (SocketTimeoutException ste) {
                                        sendError(out, Response.Status.REQUEST_TIMEOUT, "request_timeout", "Timeout reading trailers");
                                        chunkError = true;
                                        break;
                                    } catch (IOException e) {
                                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", e.getMessage());
                                        chunkError = true;
                                        break;
                                    }
                                    if (trailer == null || trailer.isEmpty()) break;
                                    trailerCount++;
                                    trailerBytes += trailer.length() + 2;
                                    if (trailerCount > limits.maxTrailerCount || trailerBytes > limits.maxTrailerBytes) {
                                        sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Trailer limits exceeded");
                                        chunkError = true;
                                        break;
                                    }
                                }
                            }
                            break; // Done with chunked body
                        }

                        // Rule 1: Validate each chunk length against MAX_BODY_BYTES - totalBytes BEFORE addition and BEFORE allocation!
                        if (chunkSize > (limits.maxBodyBytes - totalChunkBytes)) {
                            sendError(out, Response.Status.PAYLOAD_TOO_LARGE, "limit_exceeded", "Chunked body exceeds limit of " + limits.maxBodyBytes + " bytes");
                            chunkError = true;
                            break;
                        }

                        byte[] chunk = new byte[chunkSize];
                        int nRead = 0;
                        while (nRead < chunkSize) {
                            applyDeadlineTimeout(socket, requestDeadlineNano);
                            int r;
                            try {
                                r = in.read(chunk, nRead, chunkSize - nRead);
                            } catch (SocketTimeoutException ste) {
                                sendError(out, Response.Status.REQUEST_TIMEOUT, "request_timeout", "Timeout reading chunk data");
                                chunkError = true;
                                break;
                            }
                            if (r < 0) {
                                sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Unexpected EOF in chunk data");
                                chunkError = true;
                                break;
                            }
                            nRead += r;
                        }
                        if (chunkError) break;

                        bodyBuf.write(chunk, 0, nRead);
                        totalChunkBytes += chunkSize;

                        // Strict CRLF after chunk data
                        applyDeadlineTimeout(socket, requestDeadlineNano);
                        String crlf;
                        try {
                            crlf = readLineStrict(in, lineBuf, 16, socket, requestDeadlineNano);
                        } catch (SocketTimeoutException ste) {
                            sendError(out, Response.Status.REQUEST_TIMEOUT, "request_timeout", "Timeout reading chunk delimiter");
                            chunkError = true;
                            break;
                        } catch (IOException e) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", e.getMessage());
                            chunkError = true;
                            break;
                        }
                        if (crlf == null || !crlf.isEmpty()) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Malformed chunk ending (missing CRLF)");
                            chunkError = true;
                            break;
                        }
                    }

                    if (chunkError) {
                        break;
                    }
                    body = bodyBuf.toByteArray();
                } else {
                    int contentLength = 0;
                    if (cl != null) {
                        String clTrimmed = cl.trim();
                        if (clTrimmed.startsWith("+") || clTrimmed.startsWith("-") || !clTrimmed.matches("^[0-9]+$")) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Invalid Content-Length syntax: " + cl);
                            break;
                        }
                        try {
                            long parsed = Long.parseLong(clTrimmed);
                            if (parsed < 0 || parsed > Integer.MAX_VALUE) {
                                sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Content-Length overflow: " + cl);
                                break;
                            }
                            contentLength = (int) parsed;
                        } catch (NumberFormatException e) {
                            sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Invalid Content-Length: " + cl);
                            break;
                        }

                        if (contentLength > limits.maxBodyBytes) {
                            sendError(out, Response.Status.PAYLOAD_TOO_LARGE, "limit_exceeded",
                                    "Content-Length (" + contentLength + ") exceeds maximum limit of " + limits.maxBodyBytes + " bytes");
                            break;
                        }
                    }

                    body = new byte[contentLength];
                    if (contentLength > 0) {
                        int totalRead = 0;
                        boolean bodyError = false;
                        while (totalRead < contentLength) {
                            applyDeadlineTimeout(socket, requestDeadlineNano);
                            int read;
                            try {
                                read = in.read(body, totalRead, contentLength - totalRead);
                            } catch (SocketTimeoutException ste) {
                                sendError(out, Response.Status.REQUEST_TIMEOUT, "request_timeout", "Timeout reading request body");
                                bodyError = true;
                                break;
                            }
                            if (read < 0) {
                                sendError(out, Response.Status.BAD_REQUEST, "invalid_request", "Unexpected EOF in request body");
                                bodyError = true;
                                break;
                            }
                            totalRead += read;
                        }
                        if (bodyError) {
                            break;
                        }
                    }
                }

                // Dispatch to handler
                Request req = new Request(method, decodedUri, query, parms, headers, body, socket.getInetAddress());
                Response res;
                try {
                    res = serve(req);
                } catch (Throwable t) {
                    Log.e(TAG, "Unhandled exception in serve()", t);
                    res = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8",
                            "{\"ok\":false,\"code\":\"internal_error\",\"error\":\"Internal Server Error\"}");
                }
                if (res == null) {
                    res = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8",
                            "{\"ok\":false,\"code\":\"internal_error\",\"error\":\"Null response\"}");
                }

                boolean isHttp10 = "HTTP/1.0".equalsIgnoreCase(httpVersion);
                String reqConn = headers.get("connection");
                boolean closeRequested = "close".equalsIgnoreCase(reqConn)
                        || (isHttp10 && !"keep-alive".equalsIgnoreCase(reqConn))
                        || limits.closeAfterResponse
                        || requestCount >= limits.maxRequestsPerConnection;
                String resConn = res.headers.get("connection");
                boolean resClose = "close".equalsIgnoreCase(resConn);
                boolean shouldClose = closeRequested || resClose;

                // Send response under response-write deadline with watchdog
                ScheduledFuture<?> writeWatchdog = null;
                if (watchdogService != null && !watchdogService.isShutdown()) {
                    writeWatchdog = watchdogService.schedule(new Runnable() {
                        @Override
                        public void run() {
                            closeQuietly(socket);
                        }
                    }, limits.responseWriteDeadlineMs, TimeUnit.MILLISECONDS);
                }

                try {
                    setSocketTimeoutSafe(socket, limits.responseWriteDeadlineMs);

                    // Status line
                    out.write(("HTTP/1.1 " + res.status.code + " " + res.status.description + "\r\n").getBytes(StandardCharsets.US_ASCII));

                    // Content-Type
                    if (res.contentType != null && !res.contentType.isEmpty()) {
                        out.write(("Content-Type: " + res.contentType + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    }

                    // Content-Length
                    long resLength = res.dataStream != null ? res.dataLength : (res.body != null ? res.body.length : 0);
                    out.write(("Content-Length: " + resLength + "\r\n").getBytes(StandardCharsets.US_ASCII));

                    // Date
                    out.write(("Date: " + RFC1123.get().format(new Date()) + "\r\n").getBytes(StandardCharsets.US_ASCII));

                    // Connection
                    if (shouldClose) {
                        out.write("Connection: close\r\n".getBytes(StandardCharsets.US_ASCII));
                    } else {
                        out.write("Connection: keep-alive\r\n".getBytes(StandardCharsets.US_ASCII));
                    }

                    // Custom headers
                    for (Map.Entry<String, String> entry : res.headers.entrySet()) {
                        String k = entry.getKey();
                        if ("content-type".equalsIgnoreCase(k) || "content-length".equalsIgnoreCase(k)
                            || "date".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)) {
                            continue;
                        }
                        out.write((k + ": " + entry.getValue() + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    }
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));

                    // Body: HEAD responses do NOT output body
                    if (method != Method.HEAD && resLength > 0) {
                        if (res.dataStream != null) {
                            try (InputStream streamIn = res.dataStream) {
                                byte[] buf = new byte[8192];
                                long remaining = res.dataLength;
                                while (remaining > 0) {
                                    int toRead = (int) Math.min(buf.length, remaining);
                                    int n = streamIn.read(buf, 0, toRead);
                                    if (n < 0) break;
                                    out.write(buf, 0, n);
                                    remaining -= n;
                                }
                            }
                        } else if (res.body != null) {
                            out.write(res.body);
                        }
                    } else if (res.dataStream != null) {
                        try {
                            res.dataStream.close();
                        } catch (IOException ignored) {}
                    }
                    out.flush();
                } finally {
                    if (writeWatchdog != null) {
                        writeWatchdog.cancel(false);
                    }
                }

                if (shouldClose) {
                    break;
                }
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException ignored) {
        } catch (Throwable t) {
            Log.w(TAG, "Connection handler error: " + t.getMessage());
        }
    }

    private static void applyDeadlineTimeout(Socket socket, long deadlineNano) throws SocketTimeoutException {
        long remainingNano = deadlineNano - System.nanoTime();
        if (remainingNano <= 0) {
            throw new SocketTimeoutException("Request deadline exceeded");
        }
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNano);
        if (remainingMs <= 0) remainingMs = 1;
        setSocketTimeoutSafe(socket, remainingMs);
    }

    private static void setSocketTimeoutSafe(Socket socket, long timeoutMs) {
        try {
            int to = (int) Math.min(Integer.MAX_VALUE, Math.max(1, timeoutMs));
            socket.setSoTimeout(to);
        } catch (SocketException ignored) {}
    }

    private static String readLineStrict(InputStream in, ByteArrayOutputStream lineBuf,
                                         int maxLen, Socket socket, long deadlineNano) throws IOException {
        lineBuf.reset();
        int prev = -1;
        while (true) {
            applyDeadlineTimeout(socket, deadlineNano);
            int b = in.read();
            if (b == -1) {
                if (lineBuf.size() == 0) return null; // Clean EOF
                throw new IOException("Premature EOF in line");
            }
            if (b == '\n') {
                if (prev != '\r') {
                    throw new IOException("Strict CRLF violation: bare LF without preceding CR");
                }
                byte[] bytes = lineBuf.toByteArray();
                int len = bytes.length - 1; // remove trailing \r
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            lineBuf.write(b);
            prev = b;
            if (lineBuf.size() > maxLen + 2) {
                throw new IOException("Line exceeded max length limit of " + maxLen);
            }
        }
    }

    private static Map<String, String> parseQueryParameters(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> parms = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key;
            String val = "";
            if (eq >= 0) {
                key = pair.substring(0, eq);
                val = pair.substring(eq + 1);
            } else {
                key = pair;
            }
            try {
                key = URLDecoder.decode(key.replace("+", "%2B"), "UTF-8");
                val = URLDecoder.decode(val.replace("+", "%2B"), "UTF-8");
            } catch (Exception ignored) {}
            parms.put(key, val);
        }
        return parms;
    }

    private void sendOverloadAndClose(Socket socket, String reason) {
        try {
            setSocketTimeoutSafe(socket, 1000);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 1024);
            sendError(out, Response.Status.SERVICE_UNAVAILABLE, "busy", reason);
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(socket);
        }
    }

    private void sendError(OutputStream out, Response.Status status, String code, String message) {
        try {
            String json = "{\"ok\":false,\"code\":\"" + escapeJson(code) + "\",\"error\":\"" + escapeJson(message) + "\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 " + status.code + " " + status.description + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write("Content-Type: application/json; charset=utf-8\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(("Date: " + RFC1123.get().format(new Date()) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {}
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                if (!s.isClosed()) s.close();
            } catch (IOException ignored) {}
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c < 32) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    protected abstract Response serve(Request req);

    public static Response newFixedLengthResponse(Response.Status status, String mimeType, String message) {
        byte[] b = (message != null) ? message.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return new Response(status, mimeType, b);
    }

    public static Response newFixedLengthResponse(Response.Status status, String mimeType, byte[] data) {
        return new Response(status, mimeType, data);
    }

    public static Response newFixedLengthResponse(Response.Status status, String mimeType, InputStream is, long totalBytes) {
        if (is == null) {
            return new Response(status, mimeType, new byte[0]);
        }
        return new Response(status, mimeType, is, totalBytes);
    }
}
