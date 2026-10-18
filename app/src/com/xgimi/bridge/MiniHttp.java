package com.xgimi.bridge;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight, zero-dependency HTTP/1.1 server built on standard java.net.ServerSocket.
 */
public abstract class MiniHttp {
    private static final String TAG = "XgimiBridge";

    public enum Method {
        GET, POST, PUT, DELETE, HEAD, OPTIONS, UNKNOWN
    }

    public static final class Response {
        public enum Status {
            OK(200, "OK"),
            BAD_REQUEST(400, "Bad Request"),
            FORBIDDEN(403, "Forbidden"),
            NOT_FOUND(404, "Not Found"),
            METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
            PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
            INTERNAL_ERROR(500, "Internal Server Error"),
            SERVICE_UNAVAILABLE(503, "Service Unavailable");

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
        public final Map<String, String> headers = new HashMap<>();

        public Response(Status status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body != null ? body : new byte[0];
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
        public final Map<String, String> headers;
        public final byte[] body;

        public Request(Method method, String uri, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.body = body != null ? body : new byte[0];
        }

        public Method getMethod() {
            return method;
        }

        public String getUri() {
            return uri;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public byte[] getBody() {
            return body;
        }
    }

    private final int port;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService executor;
    private volatile boolean running = false;
    private final Set<Socket> openSockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());

    private static final ThreadLocal<SimpleDateFormat> RFC1123 = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
            return sdf;
        }
    };

    protected MiniHttp(int port) {
        this.port = port;
    }

    public synchronized void start() throws IOException {
        if (running) return;

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;

        executor = new ThreadPoolExecutor(
            4, 64, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "MiniHttp-Worker-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "MiniHttp-Accept");
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
                openSockets.add(socket);
                try {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                handleConnection(socket);
                            } finally {
                                openSockets.remove(socket);
                                closeQuietly(socket);
                            }
                        }
                    });
                } catch (RejectedExecutionException ree) {
                    openSockets.remove(socket);
                    sendOverloadedError(socket);
                }
            } catch (SocketException se) {
                if (!running) break;
                Log.w(TAG, "MiniHttp accept socket error", se);
            } catch (IOException ie) {
                if (!running) break;
                Log.w(TAG, "MiniHttp accept IO error", ie);
            } catch (Throwable t) {
                if (!running) break;
                Log.e(TAG, "MiniHttp accept unexpected error", t);
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 8192);
            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);

            while (running && !socket.isClosed()) {
                String requestLine = null;
                try {
                    requestLine = readLine(in, lineBuf, 4096);
                } catch (SocketTimeoutException te) {
                    // Normal keep-alive idle timeout
                    break;
                } catch (IOException ie) {
                    sendError(out, Response.Status.BAD_REQUEST, ie.getMessage());
                    break;
                }
                if (requestLine == null) {
                    // Clean EOF from client
                    break;
                }
                // Skip empty lines before request-line per RFC 7230 §3.5
                while (requestLine.isEmpty()) {
                    try {
                        requestLine = readLine(in, lineBuf, 4096);
                    } catch (SocketTimeoutException te) {
                        break;
                    } catch (IOException ie) {
                        sendError(out, Response.Status.BAD_REQUEST, ie.getMessage());
                        break;
                    }
                    if (requestLine == null) break;
                }
                if (requestLine == null || requestLine.isEmpty()) break;

                String[] parts = requestLine.split(" ");
                if (parts.length != 3) {
                    sendError(out, Response.Status.BAD_REQUEST, "Malformed request line");
                    break;
                }

                String methodStr = parts[0];
                String rawUri = parts[1];
                String httpVersion = parts[2];
                if (methodStr.isEmpty() || rawUri.isEmpty() || httpVersion.isEmpty()) {
                    sendError(out, Response.Status.BAD_REQUEST, "Malformed request line");
                    break;
                }
                if (!httpVersion.equals("HTTP/1.1") && !httpVersion.equals("HTTP/1.0")) {
                    sendError(out, Response.Status.BAD_REQUEST, "Unsupported HTTP version: " + httpVersion);
                    break;
                }

                Method method;
                try {
                    method = Method.valueOf(methodStr.toUpperCase(Locale.US));
                } catch (IllegalArgumentException e) {
                    method = Method.UNKNOWN;
                }

                // Strip authority if absolute URI (e.g. http://host:port/path)
                if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
                    int schemeEnd = rawUri.indexOf("://") + 3;
                    int slash = rawUri.indexOf('/', schemeEnd);
                    rawUri = slash >= 0 ? rawUri.substring(slash) : "/";
                }

                // Strip fragment
                int hashIdx = rawUri.indexOf('#');
                if (hashIdx >= 0) rawUri = rawUri.substring(0, hashIdx);

                // Strip query string
                int queryIdx = rawUri.indexOf('?');
                String pathOnly = queryIdx >= 0 ? rawUri.substring(0, queryIdx) : rawUri;

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
                Map<String, String> headers = new HashMap<>();
                int totalHeaderBytes = requestLine.length();
                boolean headerError = false;
                String headerErrorMsg = "Error reading request headers";
                while (true) {
                    String headerLine;
                    try {
                        headerLine = readLine(in, lineBuf, 4096);
                    } catch (SocketTimeoutException te) {
                        headerError = true;
                        headerErrorMsg = "Timeout reading request headers";
                        break;
                    } catch (IOException ie) {
                        sendError(out, Response.Status.BAD_REQUEST, ie.getMessage());
                        return;
                    }
                    if (headerLine == null) {
                        headerError = true;
                        headerErrorMsg = "Unexpected EOF in request headers";
                        break;
                    }
                    if (headerLine.isEmpty()) {
                        break; // End of headers
                    }
                    totalHeaderBytes += headerLine.length();
                    if (totalHeaderBytes > 16384) {
                        sendError(out, Response.Status.BAD_REQUEST, "Request headers too large");
                        return;
                    }
                    int colon = headerLine.indexOf(':');
                    if (colon <= 0 || Character.isWhitespace(headerLine.charAt(0))) {
                        sendError(out, Response.Status.BAD_REQUEST, "Malformed header line");
                        return;
                    }
                    if (headerLine.charAt(colon - 1) == ' ' || headerLine.charAt(colon - 1) == '\t') {
                        sendError(out, Response.Status.BAD_REQUEST, "Whitespace before colon in header name");
                        return;
                    }
                    String k = headerLine.substring(0, colon).trim().toLowerCase(Locale.US);
                    String v = headerLine.substring(colon + 1).trim();
                    headers.put(k, v);
                }
                if (headerError) {
                    sendError(out, Response.Status.BAD_REQUEST, headerErrorMsg);
                    break;
                }

                // RFC 7230 §5.4: Host header is mandatory for HTTP/1.1
                if ("HTTP/1.1".equals(httpVersion)) {
                    String host = headers.get("host");
                    if (host == null || host.trim().isEmpty()) {
                        sendError(out, Response.Status.BAD_REQUEST, "Host header required for HTTP/1.1");
                        break;
                    }
                }

                // Check transfer encoding
                String te = headers.get("transfer-encoding");
                if (te != null && !te.trim().isEmpty()) {
                    sendError(out, Response.Status.BAD_REQUEST, "Transfer-Encoding not supported");
                    break;
                }

                // Read body
                String clStr = headers.get("content-length");
                int contentLength = 0;
                if (clStr != null) {
                    try {
                        contentLength = Integer.parseInt(clStr.trim());
                    } catch (NumberFormatException e) {
                        sendError(out, Response.Status.BAD_REQUEST, "Invalid Content-Length");
                        break;
                    }
                    if (contentLength < 0) {
                        sendError(out, Response.Status.BAD_REQUEST, "Negative Content-Length");
                        break;
                    }
                    if (contentLength > 65536) {
                        sendError(out, Response.Status.PAYLOAD_TOO_LARGE, "Payload too large (> 64KB)");
                        break;
                    }
                }

                byte[] body = new byte[contentLength];
                if (contentLength > 0) {
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int read = in.read(body, totalRead, contentLength - totalRead);
                        if (read < 0) {
                            sendError(out, Response.Status.BAD_REQUEST, "Unexpected EOF in request body");
                            return;
                        }
                        totalRead += read;
                    }
                }

                Request req = new Request(method, decodedUri, headers, body);
                Response res;
                try {
                    res = serve(req);
                } catch (Throwable t) {
                    Log.e(TAG, "Unhandled exception in serve()", t);
                    res = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", "{\"error\":\"Internal Server Error\"}");
                }
                if (res == null) {
                    res = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", "{\"error\":\"Null response\"}");
                }

                boolean isHttp10 = "HTTP/1.0".equalsIgnoreCase(httpVersion);
                String reqConn = headers.get("connection");
                boolean closeRequested = "close".equalsIgnoreCase(reqConn) || (isHttp10 && !"keep-alive".equalsIgnoreCase(reqConn));
                String resConn = res.headers.get("connection");
                boolean resClose = "close".equalsIgnoreCase(resConn);
                boolean shouldClose = closeRequested || resClose;

                // Status line
                out.write(("HTTP/1.1 " + res.status.code + " " + res.status.description + "\r\n").getBytes(StandardCharsets.US_ASCII));

                // Content-Type
                if (res.contentType != null && !res.contentType.isEmpty()) {
                    out.write(("Content-Type: " + res.contentType + "\r\n").getBytes(StandardCharsets.US_ASCII));
                }

                // Content-Length
                byte[] resBody = res.body != null ? res.body : new byte[0];
                out.write(("Content-Length: " + resBody.length + "\r\n").getBytes(StandardCharsets.US_ASCII));

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

                if (method != Method.HEAD) {
                    if (resBody.length > 0) {
                        out.write(resBody);
                    }
                }
                out.flush();

                if (shouldClose) {
                    break;
                }
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException ignored) {
        } catch (Throwable t) {
            Log.w(TAG, "Connection handler error", t);
        }
    }

    private static String readLine(InputStream in, ByteArrayOutputStream lineBuf, int maxLen) throws IOException {
        lineBuf.reset();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = lineBuf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            lineBuf.write(b);
            if (lineBuf.size() > maxLen) {
                throw new IOException("Header line exceeded max length of " + maxLen);
            }
        }
        if (lineBuf.size() == 0) {
            return null; // clean EOF
        }
        throw new IOException("Truncated line at EOF");
    }

    private void sendOverloadedError(Socket socket) {
        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 1024);
            sendError(out, Response.Status.SERVICE_UNAVAILABLE, "Server busy");
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                if (!s.isClosed()) s.close();
            } catch (IOException ignored) {}
        }
    }

    private void sendError(OutputStream out, Response.Status status, String message) {
        try {
            byte[] body = ("{\"error\":\"" + escapeJson(message) + "\"}").getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 " + status.code + " " + status.description + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write("Content-Type: application/json; charset=utf-8\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(("Date: " + RFC1123.get().format(new Date()) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {}
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
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long remaining = totalBytes;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = is.read(buf, 0, toRead);
                if (n < 0) break;
                baos.write(buf, 0, n);
                remaining -= n;
            }
            return new Response(status, mimeType, baos.toByteArray());
        } catch (IOException e) {
            return new Response(Response.Status.INTERNAL_ERROR, "text/plain", ("Error reading stream: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
