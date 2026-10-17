package com.xgimi.bridge;

import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Long-lived input-event injector. Started once per bridge lifetime by spawning
 * {@code app_process} over an ADB shell stream (so it runs as shell uid, which holds the
 * signature-protected INJECT_EVENTS permission). After ~200 ms of JVM startup it sits in a
 * read-line loop on stdin and dispatches a KEY_DOWN/KEY_UP pair per integer keycode it reads,
 * or a sequence of key events for Base64-encoded text.
 *
 * Replaces {@code input keyevent N} which would re-pay the same JVM cold start every call.
 *
 * Invocation pattern (from the bridge over ADB shell):
 *   CLASSPATH=<bridge.apk> exec app_process / com.xgimi.bridge.KeyDispatcher
 *
 * Wire format:
 *   stdin:  "<keycode>\n"       (e.g. "20\n" for DPAD_DOWN)
 *           "TEXT <base64>\n"   (e.g. "TEXT SGVsbG8=\n")
 *   stdout: "OK <keycode>\n" or "OK TEXT\n" on success, "ERR <message>\n" on failure
 */
public class KeyDispatcher {
    private static final int WAKE_PORT = 8081;
    private static final Object LOCK = new Object();
    private static final int INJECT_MODE_ASYNC = 0;

    public static void main(String[] args) {
        try {
            // android.hardware.input.InputManagerGlobal + injectInputEvent are hidden APIs.
            // Shell uid bypasses hidden-API restrictions, so reflection works.
            Class<?> imClass = Class.forName("android.hardware.input.InputManagerGlobal");
            Method getInstance = imClass.getMethod("getInstance");
            Object im = getInstance.invoke(null);
            Method injectInputEvent = imClass.getMethod(
                    "injectInputEvent",
                    Class.forName("android.view.InputEvent"),
                    int.class);

            startWakeServer(im, injectInputEvent);

            System.out.println("READY");
            System.out.flush();

            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    if (line.startsWith("TEXT ")) {
                        byte[] raw = Base64.decode(line.substring(5).trim(), Base64.DEFAULT);
                        String text = new String(raw, "UTF-8");
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

    private static void startWakeServer(final Object im, final Method injectInputEvent) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.ServerSocket ss = null;
                try {
                    ss = new java.net.ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new java.net.InetSocketAddress(WAKE_PORT));
                    while (true) {
                        final java.net.Socket s = ss.accept();
                        try {
                            s.setSoTimeout(2000);
                            dispatchKey(im, injectInputEvent, KeyEvent.KEYCODE_WAKEUP);
                            java.io.OutputStream out = s.getOutputStream();
                            String body = "{\"ok\":true,\"action\":\"wake\"}";
                            String resp = "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: application/json\r\n"
                                    + "Content-Length: " + body.length() + "\r\n"
                                    + "Connection: close\r\n\r\n"
                                    + body;
                            out.write(resp.getBytes("UTF-8"));
                            out.flush();
                        } catch (Throwable ignore) {
                        } finally {
                            try { s.close(); } catch (Throwable ignore) {}
                        }
                    }
                } catch (Throwable ignore) {
                } finally {
                    if (ss != null) try { ss.close(); } catch (Throwable ignore) {}
                }
            }
        }, "KeyDispatcher-Wake");
        t.setDaemon(true);
        t.start();
    }

    /** {@link KeyCharacterMap}'s "virtual" device id — use the same value {@code input} does. */
    private static int KeyCharacterMapSource() {
        return android.view.KeyCharacterMap.VIRTUAL_KEYBOARD;
    }
}
