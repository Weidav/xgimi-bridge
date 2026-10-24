package com.xgimi.bridge;

import org.json.JSONObject;

public class ProjectorFormattersTest {

    public static void runAll() throws Exception {
        testFormatInputSource();
        testResolveHdrStatus();
        testResolvePictureMode();
        testFormatLuminanceMode();
        testFormatEnumLookups();
        testFormatAudio();
        testFmtDouble();
        System.out.println("  [PASS] ProjectorFormattersTest");
    }

    private static void testFormatInputSource() {
        assertEquals("Android TV / Home (Storage)", ProjectorFormatters.formatInputSource("E_INPUT_SOURCE_STORAGE", -1), "storage");
        assertEquals("HDMI 1 (eARC)", ProjectorFormatters.formatInputSource("E_INPUT_SOURCE_HDMI1", -1), "hdmi1 aidl");
        assertEquals("HDMI 2", ProjectorFormatters.formatInputSource("E_INPUT_SOURCE_HDMI2", -1), "hdmi2 aidl");
        assertEquals("HDMI 1", ProjectorFormatters.formatInputSource("", 23), "hdmi1 gmpf");
        assertEquals("Android TV / Home", ProjectorFormatters.formatInputSource("", 34), "storage gmpf");
        assertEquals("Source (99)", ProjectorFormatters.formatInputSource("", 99), "fallback gmpf");
    }

    private static void testResolveHdrStatus() throws Exception {
        // Framework TvSourcesStatus priority
        assertEquals("Dolby Vision",
                ProjectorFormatters.resolveHdrStatus(0, false, null, "v:DOLBYVISION|s:0", -1, null, false),
                "dv from tvSourcesStatus");
        assertEquals("HDR10+",
                ProjectorFormatters.resolveHdrStatus(0, false, null, "v:HDR10PLUS|s:0", -1, null, false),
                "hdr10plus from tvSourcesStatus");
        assertEquals("HDR10",
                ProjectorFormatters.resolveHdrStatus(0, false, null, "v:HDR10|s:0", -1, null, false),
                "hdr10 from tvSourcesStatus");
        assertEquals("SDR (Standard Dynamic Range)",
                ProjectorFormatters.resolveHdrStatus(0, false, null, "v:SDR|s:0", -1, null, false),
                "sdr from tvSourcesStatus");

        // MTK PQ fallback
        assertEquals("Dolby Vision",
                ProjectorFormatters.resolveHdrStatus(0, false, null, null, 2, null, false),
                "dv from mtk");
        assertEquals("HDR10",
                ProjectorFormatters.resolveHdrStatus(0, false, null, null, 1, null, false),
                "hdr10 from mtk");

        // GmTv fallback
        JSONObject gTv = new JSONObject();
        gTv.put("isImaxVideoPlaying", true);
        assertEquals("IMAX Enhanced",
                ProjectorFormatters.resolveHdrStatus(0, true, gTv, null, -1, null, false),
                "imax from gTv");

        assertEquals("SDR (Standard Dynamic Range)",
                ProjectorFormatters.resolveHdrStatus(0, false, null, null, -1, null, false),
                "default sdr");
    }

    private static void testResolvePictureMode() {
        // 1. Dolby Vision MTK strings override valid AIDL picture modes
        assertEquals("Dolby Vision Dark", ProjectorFormatters.resolvePictureMode(1, "DolbyVisionDark"), "mtk dv dark");
        assertEquals("Dolby Vision Bright", ProjectorFormatters.resolvePictureMode(8, "DolbyVisionBright"), "mtk dv bright");
        assertEquals("Dolby Vision Game", ProjectorFormatters.resolvePictureMode(4, "DolbyVisionGame"), "mtk dv game");

        // 2. Stale or non-DV MTK strings do NOT override valid AIDL picture modes
        assertEquals("Movie (1)", ProjectorFormatters.resolvePictureMode(1, "FilmMaker"), "aidl movie not overridden by mtk filmmaker");
        assertEquals("Movie (1)", ProjectorFormatters.resolvePictureMode(1, "ImaxEnhanced"), "aidl movie not overridden by mtk imax");
        assertEquals("Standard (8)", ProjectorFormatters.resolvePictureMode(8, "User"), "aidl standard not overridden by mtk user");
        assertEquals("Vivid (0)", ProjectorFormatters.resolvePictureMode(0, "Movie"), "aidl vivid not overridden by mtk movie");

        // 3. Hardware-verified AIDL modes
        assertEquals("Vivid (0)", ProjectorFormatters.resolvePictureMode(0, null), "aidl vivid");
        assertEquals("Movie (1)", ProjectorFormatters.resolvePictureMode(1, null), "aidl movie");
        assertEquals("User / Custom (3)", ProjectorFormatters.resolvePictureMode(3, null), "aidl custom");
        assertEquals("Game (4)", ProjectorFormatters.resolvePictureMode(4, null), "aidl game");
        assertEquals("Office (6)", ProjectorFormatters.resolvePictureMode(6, null), "aidl office");
        assertEquals("Standard (8)", ProjectorFormatters.resolvePictureMode(8, null), "aidl standard");
        assertEquals("Sports (9)", ProjectorFormatters.resolvePictureMode(9, null), "aidl sports");
        assertEquals("Filmmaker Mode (27)", ProjectorFormatters.resolvePictureMode(27, null), "aidl filmmaker");
        assertEquals("ISF Day (30)", ProjectorFormatters.resolvePictureMode(30, null), "aidl isf day");
        assertEquals("ISF Night (31)", ProjectorFormatters.resolvePictureMode(31, null), "aidl isf night");
        assertEquals("Mode 99", ProjectorFormatters.resolvePictureMode(99, null), "aidl unmapped mode");

        // 4. Fallback to MTK when aidlPicMode < 0
        assertEquals("Filmmaker Mode", ProjectorFormatters.resolvePictureMode(-1, "FilmMaker"), "mtk filmmaker fallback");
        assertEquals("IMAX Enhanced", ProjectorFormatters.resolvePictureMode(-1, "ImaxEnhanced"), "mtk imax fallback");
        assertEquals("ISF Day", ProjectorFormatters.resolvePictureMode(-1, "User"), "mtk user mapped to isf day");
        assertEquals("ISF Night", ProjectorFormatters.resolvePictureMode(-1, "Energy Saving"), "mtk energy saving mapped to isf night");
        assertEquals("ISF Night", ProjectorFormatters.resolvePictureMode(-1, "EnergySaving"), "mtk energysaving mapped to isf night");
        assertEquals(null, ProjectorFormatters.resolvePictureMode(-1, null), "both null/negative");
        assertEquals(null, ProjectorFormatters.resolvePictureMode(-1, ""), "empty mtk mode fallback");
    }

    private static void testFormatLuminanceMode() {
        assertEquals("Standard (0)", ProjectorFormatters.formatLuminanceMode(0, -1), "dlp standard");
        assertEquals("Performance / High Power (1)", ProjectorFormatters.formatLuminanceMode(1, -1), "dlp perf");
        assertEquals("Eco (2)", ProjectorFormatters.formatLuminanceMode(2, -1), "dlp eco");
        assertEquals("Standard (Level 5/10)", ProjectorFormatters.formatLuminanceMode(-1, 5), "gm slider");
    }

    private static void testFormatEnumLookups() {
        assertEquals("Off (0)", ProjectorFormatters.formatGameMode(0), "game mode off");
        assertEquals("Boost Ultra Low Latency (2)", ProjectorFormatters.formatGameMode(2), "game mode boost");
        assertEquals("Off (0)", ProjectorFormatters.formatMemcLevel(0), "memc off");
        assertEquals("Real Cinema 24p (4)", ProjectorFormatters.formatMemcLevel(4), "memc real cinema");
        assertEquals("Auto (0)", ProjectorFormatters.formatColorSpace(0), "color space auto");
        assertEquals("DCI-P3 (2)", ProjectorFormatters.formatColorSpace(2), "color space dci-p3");
        assertEquals("Full (0–255) (1)", ProjectorFormatters.formatHdmiRange(1), "hdmi range full");
        assertEquals("Auto / 16:9 (0)", ProjectorFormatters.formatAspectRatio(0), "aspect ratio 0");
        assertEquals("HDMI 2.1 / Enhanced (2)", ProjectorFormatters.formatEdidVersion(2), "edid 2.1");
        assertEquals("Side-by-Side (3) (3D-to-2D Active)", ProjectorFormatters.format3dMode(3, true), "3d mode");
    }

    private static void testFormatAudio() {
        assertEquals("HDMI ARC / eARC (2)", ProjectorFormatters.formatAudioOutputDevice(2, -1, null), "gmpf out");
        assertEquals("Custom Route", ProjectorFormatters.formatAudioOutputDevice(2, -1, "Custom Route"), "fw route override");
        assertEquals("Dolby Digital (AC-3)", ProjectorFormatters.formatDigitalAudioFormat("dd", -1, -1), "prop dd");
        assertEquals("Pass-Through (Bitstream)", ProjectorFormatters.formatDigitalAudioFormat("passthrough", -1, -1), "prop passthrough");
        assertEquals("Pass-Through (2)", ProjectorFormatters.formatArcType(2), "arc type passthrough");
        assertEquals("eARC Connected (Full Bandwidth) (3)", ProjectorFormatters.formatEarcStatus(3), "earc connected");
    }

    private static void testFmtDouble() {
        assertEquals("60", ProjectorFormatters.fmtDouble(60.0), "integer double");
        assertEquals("59.94", ProjectorFormatters.fmtDouble(59.94), "fractional double");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("FAILED: " + message + " expected: " + expected + " got: " + actual);
    }
}
