package com.xgimi.bridge;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class InputServiceTest {

    public static class FakeHdmiDeviceInfo {
        private final int portId;
        private final String displayName;

        public FakeHdmiDeviceInfo(int portId, String displayName) {
            this.portId = portId;
            this.displayName = displayName;
        }

        public int getPortId() {
            return portId;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static void runAll() throws Exception {
        testEmptyInputs();
        testHdmiDiscoveryAndSwitching();
        testPortNotFoundThrows();
        testParentPreference();
        testStorageSwitch();
        System.out.println("  [PASS] InputServiceTest");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static void assertEquals(Object exp, Object act, String msg) {
        if (exp == null && act == null) return;
        if (exp != null && exp.equals(act)) return;
        throw new AssertionError("FAILED: " + msg + " expected=" + exp + " actual=" + act);
    }

    private static void testEmptyInputs() {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);
        InputService service = new InputService(ctx);

        JSONArray inputs = service.getInputs();
        assertTrue(inputs != null && inputs.length() == 0, "Empty inputs when TV service is null");
        assertTrue(service.findHdmiPassthroughInputId(1) == null, "No port when TV service is null");
    }

    private static void testHdmiDiscoveryAndSwitching() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);

        List<TvInputInfo> list = new ArrayList<>();
        list.add(new TvInputInfo("com.xgimi.tv.hdmi1", null, 1007, new FakeHdmiDeviceInfo(1, "PlayStation 5")));
        list.add(new TvInputInfo("com.xgimi.tv.hdmi2", null, 1007, new FakeHdmiDeviceInfo(2, "Apple TV")));

        ctx.setTvInputManager(new TvInputManager(list));
        InputService service = new InputService(ctx);

        JSONArray arr = service.getInputs();
        assertEquals(2, arr.length(), "Two inputs enumerated");
        JSONObject o1 = arr.getJSONObject(0);
        assertEquals("com.xgimi.tv.hdmi1", o1.getString("id"), "HDMI 1 id");
        assertEquals(1, o1.getInt("hdmiPortId"), "HDMI 1 port");
        assertEquals("PlayStation 5", o1.getString("displayName"), "HDMI 1 displayName");

        String id1 = service.findHdmiPassthroughInputId(1);
        assertEquals("com.xgimi.tv.hdmi1", id1, "found port 1");

        String id2 = service.findHdmiPassthroughInputId(2);
        assertEquals("com.xgimi.tv.hdmi2", id2, "found port 2");

        // Switching to valid HDMI port should not throw
        service.switchToHdmi(1);
        service.switchToHdmi(2);
    }

    private static void testPortNotFoundThrows() {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);

        List<TvInputInfo> list = new ArrayList<>();
        list.add(new TvInputInfo("com.xgimi.tv.hdmi1", null, 1007, new FakeHdmiDeviceInfo(1, "Console")));
        ctx.setTvInputManager(new TvInputManager(list));
        InputService service = new InputService(ctx);

        boolean threw = false;
        try {
            service.switchToHdmi(3);
        } catch (IllegalArgumentException e) {
            threw = true;
            assertTrue(e.getMessage().contains("HDMI port 3 not found"), "Error message specifies missing port");
        }
        assertTrue(threw, "switchToHdmi(3) must throw IllegalArgumentException when port does not exist");
    }

    private static void testParentPreference() {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);

        List<TvInputInfo> list = new ArrayList<>();
        // Two inputs for port 1: one with parent (child), one root (parentId == null)
        list.add(new TvInputInfo("com.xgimi.tv.hdmi1.child", "com.xgimi.tv.hdmi1", 1007, new FakeHdmiDeviceInfo(1, "Sub")));
        list.add(new TvInputInfo("com.xgimi.tv.hdmi1.root", null, 1007, new FakeHdmiDeviceInfo(1, "Root")));
        ctx.setTvInputManager(new TvInputManager(list));
        InputService service = new InputService(ctx);

        String id = service.findHdmiPassthroughInputId(1);
        assertEquals("com.xgimi.tv.hdmi1.root", id, "Should prefer parentId == null (root input)");
    }

    private static void testStorageSwitch() {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);
        InputService service = new InputService(ctx);
        // Should execute smoothly without crashing
        service.switchToStorage();
    }
}
