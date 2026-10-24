package com.xgimi.bridge;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MiniHttpTest {
    private static final int TEST_PORT = 18080;

    private static class EchoServer extends MiniHttp {
        final AtomicInteger requestCount = new AtomicInteger(0);
        final List<String> receivedBodies = new ArrayList<>();

        EchoServer(int port, Limits limits) {
            super(port, limits);
        }

        @Override
        protected Response serve(Request req) {
            requestCount.incrementAndGet();
            String b = req.body != null ? new String(req.body, StandardCharsets.UTF_8) : "";
            synchronized (receivedBodies) {
                receivedBodies.add(b);
            }
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK " + b.length());
        }
    }

    public static void runAll() throws Exception {
        testNormalRequest();
        testStreamingResponse();
        testBodyLimits();
        testChunkArithmeticAndLimits();
        testDeadlines();
        testFramingRejections();
        testPerPeerAdmissionLimit();
        System.out.println("  [PASS] MiniHttpTest");
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void testNormalRequest() throws Exception {
        EchoServer server = new EchoServer(TEST_PORT, MiniHttp.Limits.forPort8080());
        server.start();
        try {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "GET /test HTTP/1.1\r\nHost: 127.0.0.1:18080\r\nConnection: close\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"), "Normal request should succeed with 200 OK");
                assertTrue(resp.contains("OK 0"), "Body length should be 0");
            }
        } finally {
            server.stop();
        }
    }

    private static void testStreamingResponse() throws Exception {
        MiniHttp server = new MiniHttp(TEST_PORT, MiniHttp.Limits.forPort8080()) {
            @Override
            protected Response serve(Request req) {
                byte[] sample = "STREAMED_DATA_PAYLOAD_12345".getBytes(StandardCharsets.UTF_8);
                ByteArrayInputStream bais = new ByteArrayInputStream(sample);
                Response r = newFixedLengthResponse(Response.Status.OK, "text/plain", bais, sample.length);
                r.addHeader("Cache-Control", "public, max-age=86400");
                return r;
            }
        };
        server.start();
        try {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "GET /stream HTTP/1.1\r\nHost: 127.0.0.1:18080\r\nConnection: close\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"), "Streaming request should succeed with 200 OK");
                assertTrue(resp.contains("Content-Length: 27\r\n"), "Should have correct Content-Length");
                assertTrue(resp.contains("Cache-Control: public, max-age=86400"), "Should preserve custom headers");
                assertTrue(resp.endsWith("STREAMED_DATA_PAYLOAD_12345"), "Streamed body should match payload");
            }
        } finally {
            server.stop();
        }
    }

    private static void testBodyLimits() throws Exception {
        EchoServer server = new EchoServer(TEST_PORT, MiniHttp.Limits.forPort8080());
        server.start();
        try {
            // 1. Exactly 65536 bytes
            int maxBody = 65536;
            byte[] body64k = new byte[maxBody];
            java.util.Arrays.fill(body64k, (byte) 'A');

            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String header = "POST /data HTTP/1.1\r\nHost: 127.0.0.1:18080\r\nContent-Length: " + maxBody + "\r\nConnection: close\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.US_ASCII));
                out.write(body64k);
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"), "65536 bytes body should be accepted");
            }

            // 2. 65537 bytes (one byte over) -> must be rejected with 413
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String header = "POST /data HTTP/1.1\r\nHost: 127.0.0.1:18080\r\nContent-Length: " + (maxBody + 1) + "\r\nConnection: close\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 413"), "65537 bytes body must be rejected with 413 Payload Too Large");
            }
        } finally {
            server.stop();
        }
    }

    private static void testChunkArithmeticAndLimits() throws Exception {
        EchoServer server = new EchoServer(TEST_PORT, MiniHttp.Limits.forPort8080());
        server.start();
        try {
            // Valid chunked request
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "POST /chunked HTTP/1.1\r\nHost: 127.0.0.1:18080\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"), "Valid chunked request should succeed");
                assertTrue(resp.contains("OK 11"), "Total body length should be 11");
            }

            // Chunk length exceeding limit in single chunk
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                // 0x10001 = 65537 bytes
                String req = "POST /chunked HTTP/1.1\r\nHost: 127.0.0.1:18080\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        + "10001\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 413"), "Oversized chunk must be rejected with 413");
            }

            // Negative / signed chunk length -> 400
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "POST /chunked HTTP/1.1\r\nHost: 127.0.0.1:18080\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        + "-5\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 400"), "Negative chunk length must be rejected with 400");
            }

            // Cumulative chunk overflow: 32768 bytes + 32769 bytes = 65537 bytes > 65536 bytes limit
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "POST /chunked HTTP/1.1\r\nHost: 127.0.0.1:18080\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        + "8000\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                byte[] chunk1 = new byte[32768];
                out.write(chunk1);
                out.write("\r\n8001\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 413"), "Cumulative chunk overflow must be rejected with 413 Payload Too Large");
            }

            // Malformed chunk delimiter (missing CRLF after chunk data) -> 400
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "POST /chunked HTTP/1.1\r\nHost: 127.0.0.1:18080\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        + "4\r\ntestXX\r\n"; // XX\r\n instead of CRLF
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 400"), "Missing CRLF delimiter must return 400 Bad Request");
            }
        } finally {
            server.stop();
        }
    }

    private static void testDeadlines() throws Exception {
        MiniHttp.Limits tightLimits = new MiniHttp.Limits();
        tightLimits.headerReadDeadlineMs = 200; // 200ms deadline
        EchoServer server = new EchoServer(TEST_PORT, tightLimits);
        server.start();
        try {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                // Send partial request line then stall
                out.write("GET /te".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                Thread.sleep(350); // Exceed header deadline

                // Try to read response
                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 408"), "Header deadline expiry must return 408 Request Timeout");
            }
        } finally {
            server.stop();
        }
    }

    private static void testFramingRejections() throws Exception {
        EchoServer server = new EchoServer(TEST_PORT, MiniHttp.Limits.forPort8080());
        server.start();
        try {
            // Duplicate Host header
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "GET / HTTP/1.1\r\nHost: a.com\r\nHost: b.com\r\nConnection: close\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 400"), "Duplicate Host header must return 400 Bad Request");
            }

            // Both Transfer-Encoding and Content-Length
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 400"), "Both TE and CL must return 400 Bad Request");
            }

            // Absolute URI target (proxying rejected)
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "GET http://proxy.target.com/test HTTP/1.1\r\nHost: proxy.target.com\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 400"), "Absolute URI target must return 400 Bad Request");
            }
        } finally {
            server.stop();
        }
    }

    private static void testPerPeerAdmissionLimit() throws Exception {
        // Port 8081 has per-peer limit of 2 active connections
        MiniHttp.Limits tightLimits = MiniHttp.Limits.forPort8081();
        EchoServer server = new EchoServer(TEST_PORT, tightLimits);
        server.start();
        List<Socket> openSockets = new ArrayList<>();
        try {
            // Open 2 connections (the peer limit)
            for (int i = 0; i < 2; i++) {
                Socket s = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT);
                openSockets.add(s);
            }

            // The 3rd connection from the same peer should exceed per-peer limit
            try (Socket s3 = new Socket(InetAddress.getByName("127.0.0.1"), TEST_PORT)) {
                s3.setSoTimeout(1000);
                InputStream in = s3.getInputStream();
                // When per-peer limit is exceeded, server responds with 503 or closes socket
                int read = in.read();
                // Either EOF (-1) or 503 response
                assertTrue(read == -1 || read == 'H', "Third connection should be rejected or closed");
            }
        } finally {
            for (Socket s : openSockets) {
                try { s.close(); } catch (Exception ignored) {}
            }
            server.stop();
        }
    }

    private static String readFullString(InputStream in) throws Exception {
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
        } catch (Exception ignored) {}
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
}
