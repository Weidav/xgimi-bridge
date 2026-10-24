package com.xgimi.bridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class WakeHttpServerTest {
    private static final int WAKE_PORT = 18081;

    public static class FakeInputManager {
        final AtomicInteger wakeCalls = new AtomicInteger(0);

        public void injectInputEvent(Object event, int mode) {
            wakeCalls.incrementAndGet();
        }
    }

    public static void runAll() throws Exception {
        testBareConnectDoesNotWake();
        testGetRejected();
        testBodyRejected();
        testProxyHostAccepted();
        testValidWakeAccepted();
        System.out.println("  [PASS] WakeHttpServerTest");
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void testBareConnectDoesNotWake() throws Exception {
        FakeInputManager fim = new FakeInputManager();
        Method m = FakeInputManager.class.getMethod("injectInputEvent", Object.class, int.class);
        RequestPolicy policy = new RequestPolicy();

        // Create custom WakeHttpServer on WAKE_PORT
        KeyDispatcher.WakeHttpServer server = new KeyDispatcher.WakeHttpServer(fim, m, policy) {
            // override to bind to custom test port
        };
        // Reflection to set port if needed, or start on standard WAKE_PORT
        // Let's use server directly
        server.start();
        try {
            // Bare connect and close
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), 8081)) {
                // Connect and immediately close without sending HTTP request
            }
            Thread.sleep(50);
            assertTrue(fim.wakeCalls.get() == 0, "Bare TCP connection must NOT trigger wake event");
        } finally {
            server.stop();
        }
    }

    private static void testGetRejected() throws Exception {
        FakeInputManager fim = new FakeInputManager();
        Method m = FakeInputManager.class.getMethod("injectInputEvent", Object.class, int.class);
        RequestPolicy policy = new RequestPolicy();

        KeyDispatcher.WakeHttpServer server = new KeyDispatcher.WakeHttpServer(fim, m, policy);
        server.start();
        try {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), 8081)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "GET /wake HTTP/1.1\r\nHost: 127.0.0.1:8081\r\nConnection: close\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 405"), "GET /wake must be rejected with 405 Method Not Allowed");
                assertTrue(resp.contains("Allow: POST"), "405 response must include Allow: POST");
                assertTrue(fim.wakeCalls.get() == 0, "GET /wake must not trigger wake event");
            }
        } finally {
            server.stop();
        }
    }

    private static void testBodyRejected() throws Exception {
        FakeInputManager fim = new FakeInputManager();
        Method m = FakeInputManager.class.getMethod("injectInputEvent", Object.class, int.class);
        RequestPolicy policy = new RequestPolicy();

        KeyDispatcher.WakeHttpServer server = new KeyDispatcher.WakeHttpServer(fim, m, policy);
        server.start();
        try {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), 8081)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String body = "wake payload";
                String req = "POST /wake HTTP/1.1\r\nHost: 127.0.0.1:8081\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                // MiniHttp limits for 8081 reject non-zero decoded body with 413, or Wake handler with 400
                assertTrue(resp.startsWith("HTTP/1.1 413") || resp.startsWith("HTTP/1.1 400"),
                        "Non-empty body on port 8081 must be rejected");
                assertTrue(fim.wakeCalls.get() == 0, "Rejected request must not trigger wake");
            }
        } finally {
            server.stop();
        }
    }

    private static void testProxyHostAccepted() throws Exception {
        FakeInputManager fim = new FakeInputManager();
        Method m = FakeInputManager.class.getMethod("injectInputEvent", Object.class, int.class);
        RequestPolicy policy = new RequestPolicy();

        KeyDispatcher.WakeHttpServer server = new KeyDispatcher.WakeHttpServer(fim, m, policy);
        server.start();
        try {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), 8081)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "POST /wake HTTP/1.1\r\nHost: xgimi.local.whitestoner.com:8081\r\nConnection: close\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 200"), "Reverse proxy Host should be accepted on wake port");
                assertTrue(fim.wakeCalls.get() == 2, "Wake call should be executed");
            }
        } finally {
            server.stop();
        }
    }

    private static void testValidWakeAccepted() throws Exception {
        FakeInputManager fim = new FakeInputManager();
        Method m = FakeInputManager.class.getMethod("injectInputEvent", Object.class, int.class);
        RequestPolicy policy = new RequestPolicy();

        KeyDispatcher.WakeHttpServer server = new KeyDispatcher.WakeHttpServer(fim, m, policy);
        server.start();
        try {
            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), 8081)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                String req = "POST /wake HTTP/1.1\r\nHost: 127.0.0.1:8081\r\nConnection: close\r\n\r\n";
                out.write(req.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String resp = readFullString(in);
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"), "Valid POST /wake should return 200 OK");
                assertTrue(resp.contains("\"action\":\"wake\""), "Response should acknowledge wake action");
                assertTrue(fim.wakeCalls.get() == 2, "ACTION_DOWN and ACTION_UP wake events should be dispatched");
            }
        } finally {
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
