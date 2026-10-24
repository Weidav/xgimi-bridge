package com.xgimi.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DeviceCommandsTest {
    public static void runAll() throws Exception {
        testTryLockBusy();
        testPresetOrderingAndStopOnFailure();
        testPowerActionPending();
        testConcurrentPowerActions();
        testResetPicture();
        System.out.println("  [PASS] DeviceCommandsTest");
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void testTryLockBusy() throws Exception {
        AidlClient aidl = new AidlClient();
        LocalAdbClient adb = new LocalAdbClient(null);
        DeviceCommands cmd = new DeviceCommands(aidl, adb);

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread blocker = new Thread(() -> {
            try {
                java.lang.reflect.Method tryAcquire = DeviceCommands.class.getDeclaredMethod("tryAcquireLock");
                tryAcquire.setAccessible(true);
                boolean locked = (boolean) tryAcquire.invoke(cmd);
                if (locked) {
                    holding.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    java.lang.reflect.Method releaseLock = DeviceCommands.class.getDeclaredMethod("releaseLock");
                    releaseLock.setAccessible(true);
                    releaseLock.invoke(cmd);
                }
            } catch (Exception ignored) {}
        });
        blocker.start();

        assertTrue(holding.await(1, TimeUnit.SECONDS), "Blocker should acquire lock");
        try {
            // Attempt command while locked
            DeviceCommands.CommandResult busy = cmd.setPictureSetting("brightness", 75);
            assertTrue(busy.httpStatus == 503, "Should return 503 busy when lock is held");
            assertTrue("busy".equals(busy.body.optString("code")), "Error code should be busy");
        } finally {
            release.countDown();
            blocker.join(1000);
        }
    }

    private static void testPresetOrderingAndStopOnFailure() throws Exception {
        AidlClient aidl = new AidlClient();
        LocalAdbClient adb = new LocalAdbClient(null);
        DeviceCommands cmd = new DeviceCommands(aidl, adb);

        Map<String, Integer> values = new HashMap<>();
        values.put("wbRed", 60);
        values.put("pictureMode", 3);
        values.put("brightness", 80);

        DeviceCommands.CommandResult okRes = cmd.applyPresetValues("custom_test", values);
        assertTrue(okRes.httpStatus == 200, "Should succeed with 200");
        assertTrue(okRes.body.optBoolean("ok"), "ok should be true");

        // Verify ordering: pictureMode must be called before wbRed
        int picModeIdx = -1;
        int wbRedIdx = -1;
        for (int i = 0; i < aidl.calls.size(); i++) {
            String call = aidl.calls.get(i);
            if (call.startsWith("setInt:" + AidlClient.TX_SET_PICTURE_MODE)) picModeIdx = i;
            if (call.startsWith("setInt:" + AidlClient.TX_SET_WB_RED_GAIN)) wbRedIdx = i;
        }
        assertTrue(picModeIdx != -1 && wbRedIdx != -1 && picModeIdx < wbRedIdx,
                "pictureMode must be applied before wbRed");

        // Now test stop-on-first-fail
        aidl.calls.clear();
        aidl.setterReturnValue = false; // Simulate hardware failure
        DeviceCommands.CommandResult failRes = cmd.applyPresetValues("custom_test", values);
        assertTrue(failRes.httpStatus == 502, "Should return 502 on hardware failure");
        assertFalse(failRes.body.optBoolean("ok"), "ok should be false");
        assertTrue(failRes.body.has("failed"), "Response must indicate failed field");
        assertTrue(failRes.body.has("applied"), "Response must list applied fields");
    }

    private static void testPowerActionPending() throws Exception {
        AidlClient aidl = new AidlClient();
        LocalAdbClient adb = new LocalAdbClient(null);
        DeviceCommands cmd = new DeviceCommands(aidl, adb);

        // Turn on when currently off -> returns 202 accepted with pending: true
        DeviceCommands.CommandResult res = cmd.handlePowerAction("on", false);
        assertTrue(res.httpStatus == 202, "handlePowerAction should return 202 Accepted for pending transition");
        assertTrue(res.body.optBoolean("pending"), "pending should be true");
        assertTrue(res.body.optBoolean("accepted"), "accepted should be true");
    }

    private static void testConcurrentPowerActions() throws Exception {
        AidlClient aidl = new AidlClient();
        LocalAdbClient adb = new LocalAdbClient(null);
        DeviceCommands cmd = new DeviceCommands(aidl, adb);

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread blocker = new Thread(() -> {
            try {
                java.lang.reflect.Method tryAcquire = DeviceCommands.class.getDeclaredMethod("tryAcquireLock");
                tryAcquire.setAccessible(true);
                boolean locked = (boolean) tryAcquire.invoke(cmd);
                if (locked) {
                    holding.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    java.lang.reflect.Method releaseLock = DeviceCommands.class.getDeclaredMethod("releaseLock");
                    releaseLock.setAccessible(true);
                    releaseLock.invoke(cmd);
                }
            } catch (Exception ignored) {}
        });
        blocker.start();

        assertTrue(holding.await(1, TimeUnit.SECONDS), "Blocker should acquire lock");
        try {
            DeviceCommands.CommandResult res = cmd.handlePowerAction("toggle", true);
            assertTrue(res.httpStatus == 503, "Concurrent power action should return 503 busy");
            assertTrue("busy".equals(res.body.optString("code")), "Error code should be busy");
        } finally {
            release.countDown();
            blocker.join(1000);
        }
    }

    private static void testResetPicture() throws Exception {
        AidlClient aidl = new AidlClient();
        LocalAdbClient adb = new LocalAdbClient(null);
        DeviceCommands cmd = new DeviceCommands(aidl, adb);

        DeviceCommands.CommandResult res = cmd.resetPicture(1, false);
        assertTrue(res.httpStatus == 200, "reset should return 200 OK");
        assertTrue(res.body.optBoolean("ok"), "ok should be true");
        assertTrue("reset".equals(res.body.optString("action")), "action should be reset");
        assertFalse(res.body.has("value"), "reset must not return irrelevant sentinel value");

        // When hardware setter fails, resetPicture must return 502 with device_write_failed
        aidl.setterReturnValue = false;
        DeviceCommands.CommandResult failRes = cmd.resetPicture(1, false);
        assertTrue(failRes.httpStatus == 502, "reset failure must return 502 Bad Gateway");
        assertFalse(failRes.body.optBoolean("ok"), "ok should be false on reset failure");
        assertTrue("device_write_failed".equals(failRes.body.optString("code")), "code should be device_write_failed");
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError(msg);
    }
}
