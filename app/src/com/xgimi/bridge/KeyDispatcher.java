package com.xgimi.bridge;

import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Long-lived input-event injector & shell-uid wake listener.
 * Started once per bridge lifetime by spawning {@code app_process} over an ADB shell stream
 * (so it runs as shell uid, which holds INJECT_EVENTS).
 * Shell telemetry collection is delegated to {@link ShellTelemetryCollector}.
 */
public class KeyDispatcher {
    private static final int WAKE_PORT = 8081;
    private static final Object LOCK = new Object();
    private static final int INJECT_MODE_ASYNC = 0;

    public static class WakeHttpServer extends MiniHttp {
        private final Object im;
        private final java.lang.reflect.Method injectInputEvent;
        private final RequestPolicy policy;

        public WakeHttpServer(Object im, java.lang.reflect.Method injectInputEvent, RequestPolicy policy) {
            super(WAKE_PORT, Limits.forPort8081());
            this.im = im;
            this.injectInputEvent = injectInputEvent;
            this.policy = policy;
        }

        @Override
        protected Response serve(Request req) {
            String host = req.headers.get("host");
            if (!policy.isHostAllowed(host, WAKE_PORT)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json; charset=utf-8",
                        "{\"ok\":false,\"code\":\"request_forbidden\",\"error\":\"Host forbidden\"}");
            }

            String origin = req.headers.get("origin");
            if (!policy.isOriginAllowed(origin, WAKE_PORT, true)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json; charset=utf-8",
                        "{\"ok\":false,\"code\":\"request_forbidden\",\"error\":\"Origin forbidden\"}");
            }

            if (!"/wake".equals(req.uri)) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8",
                        "{\"ok\":false,\"code\":\"not_found\",\"error\":\"Not found\"}");
            }

            if (req.method != MiniHttp.Method.POST) {
                Response r = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "application/json; charset=utf-8",
                        "{\"ok\":false,\"code\":\"method_not_allowed\",\"error\":\"Method not allowed\"}");
                r.addHeader("Allow", "POST");
                return r;
            }

            if (req.body != null && req.body.length > 0) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8",
                        "{\"ok\":false,\"code\":\"invalid_request\",\"error\":\"Wake endpoint expects empty body\"}");
            }

            try {
                dispatchKey(im, injectInputEvent, KeyEvent.KEYCODE_WAKEUP);
                return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                        "{\"ok\":true,\"action\":\"wake\"}");
            } catch (Throwable t) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8",
                        "{\"ok\":false,\"code\":\"internal_error\",\"error\":\"Wake injection failed: " + t.getMessage() + "\"}");
            }
        }
    }

    public static void main(String[] args) {
        WakeHttpServer wakeServer = null;
        try {
            Class<?> imClass = Class.forName("android.hardware.input.InputManagerGlobal");
            Method getInstance = imClass.getMethod("getInstance");
            Object im = getInstance.invoke(null);
            Method injectInputEvent = imClass.getMethod(
                    "injectInputEvent",
                    Class.forName("android.view.InputEvent"),
                    int.class);

            RequestPolicy policy = new RequestPolicy();
            wakeServer = new WakeHttpServer(im, injectInputEvent, policy);
            wakeServer.start();

            System.out.println("READY");
            System.out.flush();

            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    if (line.startsWith("ALIASES ")) {
                        String aliasStr = line.substring(8).trim();
                        java.util.List<String> raw = new java.util.ArrayList<>();
                        for (String a : aliasStr.split(",")) {
                            raw.add(a.trim());
                        }
                        policy.setAliases(raw);
                        System.out.println("OK ALIASES");
                        System.out.flush();
                        continue;
                    }
                    if (line.equals("INFO")) {
                        JSONObject info = ShellTelemetryCollector.collectHardwareInfo();
                        System.out.println("OK INFO " + info.toString());
                        System.out.flush();
                        continue;
                    }
                    if (line.startsWith("TEXT ")) {
                        byte[] raw = Base64.decode(line.substring(5).trim(), Base64.DEFAULT);
                        String text = new String(raw, "UTF-8");
                        SettingRules.validateText(text);
                        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMapSource());
                        KeyEvent[] events = kcm.getEvents(text.toCharArray());
                        if (events == null) {
                            System.out.println("ERR UnsupportedCharacters: cannot map text to key events");
                        } else {
                            synchronized (LOCK) {
                                for (KeyEvent ev : events) {
                                    injectInputEvent.invoke(im, ev, INJECT_MODE_ASYNC);
                                }
                            }
                            System.out.println("OK TEXT");
                        }
                        System.out.flush();
                        continue;
                    }
                    int code = Integer.parseInt(line);
                    SettingRules.validateKeyCode(code);
                    dispatchKey(im, injectInputEvent, code);
                    System.out.println("OK " + code);
                } catch (Exception e) {
                    System.out.println("ERR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                System.out.flush();
            }
        } catch (Throwable t) {
            System.out.println("FATAL " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        } finally {
            if (wakeServer != null) {
                wakeServer.stop();
            }
        }
    }

    private static void dispatchKey(Object im, Method injectInputEvent, int code) throws Exception {
        synchronized (LOCK) {
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0,
                    0, KeyCharacterMapSource(), 0, 0,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0,
                    0, KeyCharacterMapSource(), 0, 0,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            injectInputEvent.invoke(im, down, INJECT_MODE_ASYNC);
            injectInputEvent.invoke(im, up, INJECT_MODE_ASYNC);
        }
    }

    private static int KeyCharacterMapSource() {
        return android.view.KeyCharacterMap.VIRTUAL_KEYBOARD;
    }
}
