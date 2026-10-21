package com.xgimi.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;

public class BridgeService extends Service {
    private static final String TAG = "XgimiBridge";
    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIF_ID = 1;
    private static final int HTTP_PORT = 8080;

    private HttpServer server;
    private AidlClient aidl;
    private PresetStore presets;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean isDestroyed = false;
    private Thread startRetryThread;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "service onCreate");
        startInForeground();
        acquireLocks();

        aidl = new AidlClient(this);
        aidl.connect();

        presets = new PresetStore(this, aidl);
        try {
            presets.initialize();
        } catch (Throwable t) {
            Log.e(TAG, "PresetStore initialization failed", t);
        }

        server = new HttpServer(this, HTTP_PORT, aidl, presets);
        startServerWithBackoff();
    }

    private void startServerWithBackoff() {
        final int[] backoffsSeconds = {1, 2, 4, 8, 16, 30};
        startRetryThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int attempt = 0;
                while (!isDestroyed) {
                    try {
                        server.start();
                        Log.i(TAG, "HTTP server started on :" + HTTP_PORT);
                        return;
                    } catch (IOException e) {
                        int delaySec = attempt < backoffsSeconds.length ? backoffsSeconds[attempt] : 30;
                        attempt++;
                        Log.w(TAG, "Failed to start HTTP server (attempt " + attempt + "): " + e.getMessage() + ". Retrying in " + delaySec + "s");
                        try {
                            server.stop();
                        } catch (Throwable ignored) {}
                        try {
                            Thread.sleep(delaySec * 1000L);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            }
        }, "BridgeService-ServerStarter");
        startRetryThread.setDaemon(true);
        startRetryThread.start();
    }

    @Override
    public int onStartCommand(Intent i, int f, int s) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service onDestroy");
        isDestroyed = true;
        if (startRetryThread != null) {
            startRetryThread.interrupt();
        }
        if (server != null) {
            server.stop();
        }
        if (aidl != null) {
            aidl.disconnect();
        }
        releaseLocks();
        super.onDestroy();
    }

    /**
     * Hold a partial CPU wake lock and a high-perf Wi-Fi lock for the lifetime of the service.
     * Without these, the projector's standby state suspends the CPU and powers down the radio,
     * killing the HTTP socket until the next boot/wake.
     */
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XgimiBridge:cpu");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "XgimiBridge:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
            Log.i(TAG, "wake+wifi locks acquired");
        } catch (Throwable t) {
            Log.w(TAG, "failed to acquire locks", t);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {}
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Bridge", NotificationManager.IMPORTANCE_LOW);
        if (nm != null) nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Xgimi Bridge")
                .setContentText("HTTP on port " + HTTP_PORT)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }
}
