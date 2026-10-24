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
        testPictureModeResolution();
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

    private static void testPictureModeResolution() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);

        // Case 1: AIDL standard (8) with stale MTK "ImaxEnhanced" -> AIDL wins: "Standard (8)"
        JSONObject aidl1 = new JSONObject();
        aidl1.put("pictureMode", 8);
        JSONObject mtk1 = new JSONObject();
        JSONObject s1 = new JSONObject();
        s1.put("Picture_Mode", "ImaxEnhanced");
        mtk1.put("settings", s1);
        ProjectorCategoryBuilder.CategorizedResult res1 = ProjectorCategoryBuilder.build(
                ctx, aidl1, new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), mtk1);
        assertEquals("Standard (8)", res1.summary.optString("pictureMode"), "Summary pictureMode: aidl standard");
        assertEquals("Standard (8)", res1.categories.getJSONObject("brightness_picture").optString("pictureMode"), "Category pictureMode: aidl standard");
        assertEquals(false, res1.categories.getJSONObject("display").optBoolean("imaxEnhancedPlaying"), "Stale MTK ImaxEnhanced must not set imaxEnhancedPlaying when AIDL mode 8 is active");
        assertEquals(false, res1.categories.getJSONObject("display").optBoolean("filmmakerModePlaying"), "Standard mode 8 must not set filmmakerModePlaying");

        // Case 2: AIDL movie (1) with active MTK "DolbyVisionBright" -> MTK DV wins: "Dolby Vision Bright"
        JSONObject aidl2 = new JSONObject();
        aidl2.put("pictureMode", 1);
        JSONObject mtk2 = new JSONObject();
        JSONObject s2 = new JSONObject();
        s2.put("Picture_Mode", "DolbyVisionBright");
        mtk2.put("settings", s2);
        ProjectorCategoryBuilder.CategorizedResult res2 = ProjectorCategoryBuilder.build(
                ctx, aidl2, new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), mtk2);
        assertEquals("Dolby Vision Bright", res2.summary.optString("pictureMode"), "Summary pictureMode: DV override");
        assertEquals("Dolby Vision Bright", res2.categories.getJSONObject("brightness_picture").optString("pictureMode"), "Category pictureMode: DV override");

        // Case 3: AIDL missing (-1) with MTK "User" -> mapped to "ISF Day"
        JSONObject aidl3 = new JSONObject();
        JSONObject mtk3 = new JSONObject();
        JSONObject s3 = new JSONObject();
        s3.put("Picture_Mode", "User");
        mtk3.put("settings", s3);
        ProjectorCategoryBuilder.CategorizedResult res3 = ProjectorCategoryBuilder.build(
                ctx, aidl3, new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), mtk3);
        assertEquals("ISF Day", res3.summary.optString("pictureMode"), "Summary pictureMode: MTK User to ISF Day");
        assertEquals("ISF Day", res3.categories.getJSONObject("brightness_picture").optString("pictureMode"), "Category pictureMode: MTK User to ISF Day");

        // Case 4: AIDL missing (-1) with MTK "Energy Saving" -> mapped to "ISF Night"
        JSONObject aidl4 = new JSONObject();
        JSONObject mtk4 = new JSONObject();
        JSONObject s4 = new JSONObject();
        s4.put("Picture_Mode", "Energy Saving");
        mtk4.put("settings", s4);
        ProjectorCategoryBuilder.CategorizedResult res4 = ProjectorCategoryBuilder.build(
                ctx, aidl4, new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), mtk4);
        assertEquals("ISF Night", res4.summary.optString("pictureMode"), "Summary pictureMode: MTK Energy Saving to ISF Night");
        assertEquals("ISF Night", res4.categories.getJSONObject("brightness_picture").optString("pictureMode"), "Category pictureMode: MTK Energy Saving to ISF Night");

        // Case 5: AIDL Filmmaker Mode (27) marks filmmakerModePlaying true even if MTK register has stale ImaxEnhanced
        JSONObject aidl5 = new JSONObject();
        aidl5.put("pictureMode", 27);
        JSONObject mtk5 = new JSONObject();
        JSONObject s5 = new JSONObject();
        s5.put("Picture_Mode", "ImaxEnhanced");
        mtk5.put("settings", s5);
        ProjectorCategoryBuilder.CategorizedResult res5 = ProjectorCategoryBuilder.build(
                ctx, aidl5, new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), mtk5);
        assertEquals("Filmmaker Mode (27)", res5.summary.optString("pictureMode"), "Summary pictureMode: Filmmaker 27");
        assertEquals(true, res5.categories.getJSONObject("display").optBoolean("filmmakerModePlaying"), "Filmmaker 27 marks filmmakerModePlaying true");
        assertEquals(false, res5.categories.getJSONObject("display").optBoolean("imaxEnhancedPlaying"), "Stale MTK string does not mark imaxEnhancedPlaying");

        // Case 6: AIDL missing (-1) with MTK hardware enum aliases ("PC" -> "Office", "Bright" -> "Vivid")
        JSONObject aidl6 = new JSONObject();
        JSONObject mtk6 = new JSONObject();
        JSONObject s6 = new JSONObject();
        s6.put("Picture_Mode", "PC");
        mtk6.put("settings", s6);
        ProjectorCategoryBuilder.CategorizedResult res6 = ProjectorCategoryBuilder.build(
                ctx, aidl6, new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), mtk6);
        assertEquals("Office", res6.summary.optString("pictureMode"), "MTK PC maps to Office");

        s6.put("Picture_Mode", "Bright");
        ProjectorCategoryBuilder.CategorizedResult res7 = ProjectorCategoryBuilder.build(
                ctx, aidl6, new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), mtk6);
        assertEquals("Vivid", res7.summary.optString("pictureMode"), "MTK Bright maps to Vivid");

        // Case 7: User switched to Filmmaker Mode via OSD -> AIDL pictureMode has stale 9 (Sports),
        // but MTK Color_Temperature is "FMMSDR" and Film_Mode is "On".
        JSONObject aidl8 = new JSONObject();
        aidl8.put("pictureMode", 9);
        JSONObject mtk8 = new JSONObject();
        JSONObject s8 = new JSONObject();
        s8.put("Color_Temperature", "FMMSDR");
        s8.put("Film_Mode", "On");
        s8.put("Picture_Mode", "ImaxEnhanced");
        mtk8.put("settings", s8);
        ProjectorCategoryBuilder.CategorizedResult res8 = ProjectorCategoryBuilder.build(
                ctx, aidl8, new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), mtk8);
        assertEquals("Filmmaker Mode (27)", res8.summary.optString("pictureMode"), "FMMSDR overrides stale AIDL 9 in summary");
        assertEquals("Filmmaker Mode (27)", res8.categories.getJSONObject("brightness_picture").optString("pictureMode"), "FMMSDR overrides stale AIDL 9 in brightness_picture");
        assertEquals(true, res8.categories.getJSONObject("display").optBoolean("filmmakerModePlaying"), "FMMSDR marks filmmakerModePlaying true");
        assertEquals("Filmmaker SDR / Warm (FMMSDR)", res8.categories.getJSONObject("brightness_picture").optString("colorTemperatureMode"), "Color temp formatted correctly");

        // Case 8: HDR Filmmaker Mode -> AIDL pictureMode 1 (Movie), Color_Temperature "FMMHDR"
        JSONObject aidl9 = new JSONObject();
        aidl9.put("pictureMode", 1);
        JSONObject mtk9 = new JSONObject();
        JSONObject s9 = new JSONObject();
        s9.put("Color_Temperature", "FMMHDR");
        mtk9.put("settings", s9);
        ProjectorCategoryBuilder.CategorizedResult res9 = ProjectorCategoryBuilder.build(
                ctx, aidl9, new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), mtk9);
        assertEquals("Filmmaker Mode (27)", res9.summary.optString("pictureMode"), "FMMHDR overrides stale AIDL 1 in summary");
        assertEquals(true, res9.categories.getJSONObject("display").optBoolean("filmmakerModePlaying"), "FMMHDR marks filmmakerModePlaying true");
        assertEquals("Filmmaker HDR / Warm (FMMHDR)", res9.categories.getJSONObject("brightness_picture").optString("colorTemperatureMode"), "Color temp formatted correctly");

        // Case 9: Dolby Vision Filmmaker mode
        JSONObject aidl10 = new JSONObject();
        aidl10.put("pictureMode", 1);
        JSONObject mtk10 = new JSONObject();
        JSONObject s10 = new JSONObject();
        s10.put("Picture_Mode", "DolbyVisionFilmmaker");
        mtk10.put("settings", s10);
        ProjectorCategoryBuilder.CategorizedResult res10 = ProjectorCategoryBuilder.build(
                ctx, aidl10, new JSONObject(), new JSONObject(),
                new JSONObject(), new JSONObject(), new JSONObject(), new JSONObject(), mtk10);
        assertEquals("Dolby Vision Filmmaker", res10.summary.optString("pictureMode"), "Dolby Vision Filmmaker in summary");
        assertEquals(true, res10.categories.getJSONObject("display").optBoolean("filmmakerModePlaying"), "Dolby Vision Filmmaker marks filmmakerModePlaying true");
    }
}
