package com.xgimi.bridge;

import android.content.Context;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class HttpServerTest {
    private static final int SERVER_PORT = 18082;

    public static void runAll() throws Exception {
        testExactRoutesAndMethodChecking();
        testContentTypeEnforcement();
        testSettingValidationAndProtection();
        System.out.println("  [PASS] HttpServerTest");
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void testExactRoutesAndMethodChecking() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "http_test_" + System.nanoTime());
        dir.mkdirs();
        try {
            Context ctx = new Context(dir);
            AidlClient aidl = new AidlClient();
            PresetStore presets = new PresetStore(ctx, aidl);
            presets.initialize();

            HttpServer server = new HttpServer(ctx, SERVER_PORT, aidl, presets);
            server.start();
            try {
                // 1. GET /api/picture -> 200 OK
                String resp1 = sendRequest("GET /api/picture HTTP/1.1\r\nHost: 127.0.0.1:18082\r\nConnection: close\r\n\r\n");
                assertTrue(resp1.startsWith("HTTP/1.1 200 OK"), "GET /api/picture should succeed");
                assertTrue(resp1.contains("\"brightness\""), "Should contain picture snapshot");

                // 2. Unknown route -> 404
                String resp2 = sendRequest("GET /api/unknown-endpoint HTTP/1.1\r\nHost: 127.0.0.1:18082\r\nConnection: close\r\n\r\n");
                assertTrue(resp2.startsWith("HTTP/1.1 404"), "Unknown route should return 404");

                // 3. Extra suffix route /api/input-extra -> 404
                String resp3 = sendRequest("POST /api/input-extra HTTP/1.1\r\nHost: 127.0.0.1:18082\r\nConnection: close\r\n\r\n");
                assertTrue(resp3.startsWith("HTTP/1.1 404"), "Invalid route alias should return 404");

                // 4. Method not allowed (e.g. DELETE /api/picture) -> 405
                String resp4 = sendRequest("DELETE /api/picture HTTP/1.1\r\nHost: 127.0.0.1:18082\r\nConnection: close\r\n\r\n");
                assertTrue(resp4.startsWith("HTTP/1.1 405"), "Wrong method should return 405");
                assertTrue(resp4.contains("Allow: GET, HEAD"), "405 should specify Allow: GET, HEAD");
            } finally {
                server.stop();
            }
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void testContentTypeEnforcement() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "http_ct_test_" + System.nanoTime());
        dir.mkdirs();
        try {
            Context ctx = new Context(dir);
            AidlClient aidl = new AidlClient();
            PresetStore presets = new PresetStore(ctx, aidl);
            presets.initialize();

            HttpServer server = new HttpServer(ctx, SERVER_PORT, aidl, presets);
            server.start();
            try {
                // Non-empty JSON endpoint with text/plain Content-Type -> 415
                String body = "{\"value\":50}";
                String req = "POST /api/picture/brightness HTTP/1.1\r\nHost: 127.0.0.1:18082\r\nContent-Type: text/plain\r\nContent-Length: "
                        + body.length() + "\r\nConnection: close\r\n\r\n" + body;
                String resp = sendRequest(req);
                assertTrue(resp.startsWith("HTTP/1.1 415"), "Incompatible Content-Type must return 415 Unsupported Media Type");
            } finally {
                server.stop();
            }
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void testSettingValidationAndProtection() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "http_val_test_" + System.nanoTime());
        dir.mkdirs();
        try {
            Context ctx = new Context(dir);
            AidlClient aidl = new AidlClient();
            PresetStore presets = new PresetStore(ctx, aidl);
            presets.initialize();

            HttpServer server = new HttpServer(ctx, SERVER_PORT, aidl, presets);
            server.start();
            try {
                // 1. Out of range brightness (150) -> 400
                String body = "{\"value\":150}";
                String req = "POST /api/picture/brightness HTTP/1.1\r\nHost: 127.0.0.1:18082\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.length() + "\r\nConnection: close\r\n\r\n" + body;
                String resp = sendRequest(req);
                assertTrue(resp.startsWith("HTTP/1.1 400"), "Out of range value must return 400 Bad Request");

                // 2. Valid brightness (75) -> 200
                String validBody = "{\"value\":75}";
                String validReq = "POST /api/picture/brightness HTTP/1.1\r\nHost: 127.0.0.1:18082\r\nContent-Type: application/json\r\nContent-Length: "
                        + validBody.length() + "\r\nConnection: close\r\n\r\n" + validBody;
                String validResp = sendRequest(validReq);
                assertTrue(validResp.startsWith("HTTP/1.1 200 OK"), "Valid setting write should return 200 OK");
                assertTrue(validResp.contains("\"ok\":true"), "Response should have ok:true");

                // 3. Delete built-in preset -> 403 Forbidden
                String delReq = "DELETE /api/presets/balanced HTTP/1.1\r\nHost: 127.0.0.1:18082\r\nConnection: close\r\n\r\n";
                String delResp = sendRequest(delReq);
                assertTrue(delResp.startsWith("HTTP/1.1 403"), "Deleting built-in preset must return 403 Forbidden");
                assertTrue(delResp.contains("builtin_read_only"), "Error code should be builtin_read_only");
            } finally {
                server.stop();
            }
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static String sendRequest(String rawRequest) throws Exception {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), SERVER_PORT)) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(rawRequest.getBytes(StandardCharsets.US_ASCII));
            out.flush();

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
}
