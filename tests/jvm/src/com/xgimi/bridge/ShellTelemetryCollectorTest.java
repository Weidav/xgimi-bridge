package com.xgimi.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.StringReader;

public class ShellTelemetryCollectorTest {
    public static void main(String[] args) throws Exception {
        testParseCecDumpsys();
        testCollectHardwareInfoRunsWithoutCrashing();
        System.out.println("  [PASS] ShellTelemetryCollectorTest");
    }

    private static void testParseCecDumpsys() throws Exception {
        String sample =
                "eARC local device: TV, mEarcStatus: 2\n" +
                "port_id: 1, type: INPUT, address: 0x1000, cec: true, arc: false, mhl: false, earc: false\n" +
                "port_id: 2, type: INPUT, address: 0x2000, cec: true, arc: true, mhl: false, earc: true\n" +
                "CEC: logical_address: 0x04 device_type: 4 cec_version: 5 vendor_id: 12345 display_name: AppleTV power_status: 0 physical_address: 0x1000 port_id: 1\n" +
                "CEC: logical_address: 0x04 device_type: 4 cec_version: 5 vendor_id: 12345 display_name: AppleTV power_status: 0 physical_address: 0x1000 port_id: 1\n" +
                "CEC: logical_address: 0x00 device_type: 0 cec_version: 5 vendor_id: 999 display_name: TV power_status: 0 physical_address: 0x0000 port_id: 0\n";

        BufferedReader br = new BufferedReader(new StringReader(sample));
        JSONObject cec = ShellTelemetryCollector.parseCecDumpsys(br);

        assertEquals("TV", cec.getString("earcLocalRole"), "eARC local role");
        assertEquals(2, cec.getInt("earcDumpsysStatus"), "eARC dumpsys status");

        JSONArray ports = cec.getJSONArray("ports");
        assertEquals(2, ports.length(), "ports count");
        JSONObject p1 = ports.getJSONObject(0);
        assertEquals(1, p1.getInt("portId"), "port 1 id");
        assertEquals("INPUT", p1.getString("type"), "port 1 type");
        assertEquals("0x1000", p1.getString("address"), "port 1 address");
        assertTrue(p1.getBoolean("cec"), "port 1 cec");
        assertFalse(p1.getBoolean("arc"), "port 1 arc");
        assertFalse(p1.getBoolean("earc"), "port 1 earc");

        JSONObject p2 = ports.getJSONObject(1);
        assertEquals(2, p2.getInt("portId"), "port 2 id");
        assertTrue(p2.getBoolean("earc"), "port 2 earc");

        JSONArray devices = cec.getJSONArray("devices");
        // Duplicate AppleTV should be deduplicated by address
        assertEquals(2, devices.length(), "devices count deduplicated");
        JSONObject d0 = devices.getJSONObject(0);
        assertEquals("0x04", d0.getString("logicalAddress"), "dev 0 logical address");
        assertEquals("AppleTV", d0.getString("displayName"), "dev 0 display name");
        assertEquals("0x1000", d0.getString("physicalAddress"), "dev 0 physical address");

        JSONObject d1 = devices.getJSONObject(1);
        assertEquals("0x00", d1.getString("logicalAddress"), "dev 1 logical address");
        assertEquals("TV", d1.getString("displayName"), "dev 1 display name");
    }

    private static void testCollectHardwareInfoRunsWithoutCrashing() throws Exception {
        // In JVM test environment, GMPF services are absent; collectHardwareInfo must return cleanly
        JSONObject info = ShellTelemetryCollector.collectHardwareInfo();
        assertNotNull(info, "info should not be null");
        assertTrue(info.has("_timings"), "info should contain _timings");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("FAILED: " + message + " expected: " + expected + " got: " + actual);
    }

    private static void assertTrue(boolean val, String message) {
        if (!val) throw new AssertionError("FAILED: " + message + " expected true");
    }

    private static void assertFalse(boolean val, String message) {
        if (val) throw new AssertionError("FAILED: " + message + " expected false");
    }

    private static void assertNotNull(Object obj, String message) {
        if (obj == null) throw new AssertionError("FAILED: " + message + " was null");
    }
}
