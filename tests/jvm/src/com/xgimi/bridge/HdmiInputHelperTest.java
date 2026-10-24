package com.xgimi.bridge;

import android.media.tv.TvInputInfo;

public class HdmiInputHelperTest {
    public static void runAll() {
        testNullSafety();
        testHdmiInfoExtraction();
        System.out.println("  [PASS] HdmiInputHelperTest");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void testNullSafety() {
        assertTrue(HdmiInputHelper.getHdmiDeviceInfo(null) == null, "null info returns null device info");
        assertTrue(HdmiInputHelper.extractHdmiInfo(null) == null, "null info returns null hdmi info");
        assertTrue(HdmiInputHelper.getHdmiPortId(null) == null, "null info returns null port id");
    }

    private static void testHdmiInfoExtraction() {
        TvInputInfo info = new TvInputInfo();
        // TvInputInfo stub doesn't declare getHdmiDeviceInfo, so it returns null safely without exception
        assertTrue(HdmiInputHelper.getHdmiDeviceInfo(info) == null, "Stub without method returns null");
        assertTrue(HdmiInputHelper.extractHdmiInfo(info) == null, "Stub without method extracts null");
        assertTrue(HdmiInputHelper.getHdmiPortId(info) == null, "Stub without method returns null port");
    }
}
