package com.xgimi.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

public class ProjectorCategoryBuilderTest {

    public static void runAll() throws Exception {
        testDefaultEmptyInputs();
        testBrightnessModeEdgeCases();
        testDisplayCategory();
        testHdrResolution();
        testCecTopologySummary();
        System.out.println("  [PASS] ProjectorCategoryBuilderTest");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static void assertEquals(Object exp, Object act, String msg) {
        if (exp == null && act == null) return;
        if (exp != null && exp.equals(act)) return;
        throw new AssertionError("FAILED: " + msg + " expected=" + exp + " actual=" + act);
    }

    private static void testDefaultEmptyInputs() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);

        ProjectorCategoryBuilder.CategorizedResult res = ProjectorCategoryBuilder.build(
                ctx, new JSONObject(), new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), null);

        JSONObject summary = res.summary;
        JSONObject cats = res.categories;

        assertTrue(cats.has("display"), "categories has display");
        assertTrue(cats.has("brightness_picture"), "categories has brightness_picture");
        assertTrue(cats.has("audio"), "categories has audio");
        assertTrue(cats.has("optics_sensors"), "categories has optics_sensors");
        assertTrue(cats.has("thermal_power"), "categories has thermal_power");
        assertTrue(cats.has("hdmi_cec"), "categories has hdmi_cec");
        assertTrue(cats.has("system"), "categories has system");

        // Critical regression test: summary.brightnessMode must NEVER be the literal string "null"
        Object bm = summary.opt("brightnessMode");
        assertTrue(bm == null || !bm.equals("null"), "summary.brightnessMode must not be the literal string 'null'");
    }

    private static void testBrightnessModeEdgeCases() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);

        // Case A: dlpLumensMode = 0 (Standard) and dlpLumensLevel = 8 -> "Standard (0) • Lvl 8/10"
        JSONObject gmpfA = new JSONObject();
        JSONObject dispA = new JSONObject();
        dispA.put("dlpLumensMode", 0);
        dispA.put("dlpLumensLevel", 8);
        gmpfA.put("display", dispA);

        ProjectorCategoryBuilder.CategorizedResult resA = ProjectorCategoryBuilder.build(
                ctx, new JSONObject(), gmpfA, new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), null);

        assertEquals("Standard (0) • Lvl 8/10", resA.summary.optString("brightnessMode"), "Standard mode with level");

        // Case B: No luminance mode, only dlpLumensLevel = 5 -> "Level 5/10" (NOT "null • Lvl 5/10")
        JSONObject gmpfB = new JSONObject();
        JSONObject dispB = new JSONObject();
        dispB.put("dlpLumensLevel", 5);
        gmpfB.put("display", dispB);

        ProjectorCategoryBuilder.CategorizedResult resB = ProjectorCategoryBuilder.build(
                ctx, new JSONObject(), gmpfB, new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), null);

        assertEquals("Level 5/10", resB.summary.optString("brightnessMode"), "Only level without mode must not contain 'null'");

        // Case C: No mode, no level -> null / omitted (NOT "null")
        JSONObject gmpfC = new JSONObject();
        ProjectorCategoryBuilder.CategorizedResult resC = ProjectorCategoryBuilder.build(
                ctx, new JSONObject(), gmpfC, new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), null);

        Object bmC = resC.summary.opt("brightnessMode");
        assertTrue(bmC == null || !bmC.equals("null"), "No mode and no level must not produce string 'null'");
    }

    private static void testDisplayCategory() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);

        JSONObject gmpf = new JSONObject();
        JSONObject tv = new JSONObject();
        JSONObject hdmi = new JSONObject();
        hdmi.put("width", 3840);
        hdmi.put("height", 2160);
        hdmi.put("frameRate", 60000);
        hdmi.put("interlace", false);
        hdmi.put("isHdmiMode", true);
        tv.put("hdmiInfo", hdmi);
        gmpf.put("tv", tv);

        ProjectorCategoryBuilder.CategorizedResult res = ProjectorCategoryBuilder.build(
                ctx, new JSONObject(), gmpf, new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), null);

        JSONObject catDisp = res.categories.getJSONObject("display");
        assertEquals("3840x2160p @ 60 Hz", catDisp.getString("hdmiSignalTiming"), "Signal timing formatted");
        assertEquals("HDMI", catDisp.getString("hdmiMode"), "HDMI mode");
        assertEquals("3840x2160p @ 60 Hz", res.summary.getString("resolution"), "Summary resolution");
    }

    private static void testHdrResolution() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);

        JSONObject settings = new JSONObject();
        settings.put("global.TvSourcesStatus", "v:DOLBYVISION|s:0");

        ProjectorCategoryBuilder.CategorizedResult res = ProjectorCategoryBuilder.build(
                ctx, new JSONObject(), new JSONObject(), new JSONObject(),
                new JSONObject(), settings, new JSONObject(), new JSONObject(), null);

        assertEquals("Dolby Vision", res.summary.getString("hdrStatus"), "HDR status from tvSourcesStatus");
        JSONObject catDisp = res.categories.getJSONObject("display");
        assertEquals("Dolby Vision", catDisp.getString("hdrStatus"), "Category hdrStatus");
    }

    private static void testCecTopologySummary() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);

        JSONObject gmpf = new JSONObject();
        JSONObject cec = new JSONObject();
        JSONArray devices = new JSONArray();

        JSONObject d1 = new JSONObject();
        d1.put("displayName", "Apple TV");
        d1.put("portId", 1);
        d1.put("physicalAddress", "0x1000");
        d1.put("powerStatus", 0);
        devices.put(d1);

        cec.put("devices", devices);
        gmpf.put("cecDumpsys", cec);

        ProjectorCategoryBuilder.CategorizedResult res = ProjectorCategoryBuilder.build(
                ctx, new JSONObject(), gmpf, new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), null);

        JSONObject catCec = res.categories.getJSONObject("hdmi_cec");
        String summary = catCec.getString("connectedCecSummary");
        assertTrue(summary.contains("Apple TV (Port 1, 0x1000, On)"), "CEC summary format");
    }
}
