package com.xgimi.bridge;

import org.json.JSONObject;

public class SettingRulesTest {
    public static void runAll() throws Exception {
        testIntegerRange();
        testPictureMode();
        testColorTemp();
        testGameMode();
        testAllowedInputs();
        testTextValidation();
        testJsonPreflight();
        System.out.println("  [PASS] SettingRulesTest");
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError(msg);
    }

    private static void testIntegerRange() {
        // Valid
        assertTrue(SettingRules.validateIntegerRange(0, 0, 100, "wbRed") == 0, "0 should be valid");
        assertTrue(SettingRules.validateIntegerRange(50, 0, 100, "wbRed") == 50, "50 should be valid");
        assertTrue(SettingRules.validateIntegerRange(100, 0, 100, "wbRed") == 100, "100 should be valid");
        assertTrue(SettingRules.validateIntegerRange(50.0, 0, 100, "wbRed") == 50, "50.0 should normalize to 50");

        // Invalid: out of bounds
        assertThrows(() -> SettingRules.validateIntegerRange(-1, 0, 100, "wbRed"));
        assertThrows(() -> SettingRules.validateIntegerRange(101, 0, 100, "wbRed"));

        // Invalid: non-integral
        assertThrows(() -> SettingRules.validateIntegerRange(50.5, 0, 100, "wbRed"));

        // Invalid: types
        assertThrows(() -> SettingRules.validateIntegerRange("50", 0, 100, "wbRed"));
        assertThrows(() -> SettingRules.validateIntegerRange(true, 0, 100, "wbRed"));
        assertThrows(() -> SettingRules.validateIntegerRange(null, 0, 100, "wbRed"));
        assertThrows(() -> SettingRules.validateIntegerRange(JSONObject.NULL, 0, 100, "wbRed"));
        assertThrows(() -> SettingRules.validateIntegerRange(Double.NaN, 0, 100, "wbRed"));
        assertThrows(() -> SettingRules.validateIntegerRange(Double.POSITIVE_INFINITY, 0, 100, "wbRed"));
    }

    private static void testPictureMode() {
        for (int m : new int[]{1, 3, 4, 6, 9}) {
            assertTrue(SettingRules.ALLOWED_PICTURE_MODES.contains(m), "Mode " + m + " should be allowed");
        }
        assertFalse(SettingRules.ALLOWED_PICTURE_MODES.contains(0), "Mode 0 should not be allowed");
        assertFalse(SettingRules.ALLOWED_PICTURE_MODES.contains(2), "Mode 2 should not be allowed");
        assertFalse(SettingRules.ALLOWED_PICTURE_MODES.contains(5), "Mode 5 should not be allowed");
    }

    private static void testColorTemp() {
        for (int t : new int[]{0, 1, 2, 3, 4}) {
            assertTrue(SettingRules.ALLOWED_COLOR_TEMPS.contains(t), "Color temp " + t + " should be allowed");
        }
        assertFalse(SettingRules.ALLOWED_COLOR_TEMPS.contains(-1), "Temp -1 should not be allowed");
        assertFalse(SettingRules.ALLOWED_COLOR_TEMPS.contains(5), "Temp 5 should not be allowed");
    }

    private static void testGameMode() {
        for (int g : new int[]{0, 1, 2}) {
            assertTrue(SettingRules.ALLOWED_GAME_MODES.contains(g), "Game mode " + g + " should be allowed");
        }
        assertFalse(SettingRules.ALLOWED_GAME_MODES.contains(3), "Game mode 3 should not be allowed");
    }

    private static void testAllowedInputs() {
        for (String in : new String[]{"STORAGE", "HOME", "APPS", "HDMI1", "HDMI2", "HDMI3"}) {
            assertTrue(SettingRules.ALLOWED_INPUTS.contains(in), "Input " + in + " should be allowed");
        }
        assertFalse(SettingRules.ALLOWED_INPUTS.contains("HDMI4"), "HDMI4 should not be allowed");
        assertFalse(SettingRules.ALLOWED_INPUTS.contains("UNKNOWN"), "UNKNOWN should not be allowed");
    }

    private static void testTextValidation() {
        // Valid text
        String valid = SettingRules.validateText("Hello Projector!");
        assertTrue("Hello Projector!".equals(valid), "Valid text should pass");

        // Empty text
        assertThrows(() -> SettingRules.validateText(""));
        assertThrows(() -> SettingRules.validateText(null));

        // NUL character
        assertThrows(() -> SettingRules.validateText("Hello\0World"));

        // Length > 1024 code points
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1025; i++) {
            sb.append('a');
        }
        assertThrows(() -> SettingRules.validateText(sb.toString()));
    }

    private static void testJsonPreflight() throws Exception {
        // Normal JSON object
        SettingRules.preflightJsonObject("{\"key\":\"value\",\"num\":123}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Not an object (e.g. array, number, string)
        assertThrows(() -> SettingRules.preflightJsonObject("[\"a\", \"b\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(() -> SettingRules.preflightJsonObject("123".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(() -> SettingRules.preflightJsonObject("\"hello\"".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // Trailing non-whitespace
        assertThrows(() -> SettingRules.preflightJsonObject("{\"a\":1} extra".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // Multiple concatenated JSON objects
        assertThrows(() -> SettingRules.preflightJsonObject("{\"a\":1} {\"b\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // Invalid UTF-8 sequence
        byte[] invalidUtf8 = new byte[] { '{', '"', 'a', '"', ':', (byte) 0xFF, (byte) 0xFE, '}' };
        assertThrows(() -> SettingRules.preflightJsonObject(invalidUtf8));

        // Nesting depth <= 16 is fine
        StringBuilder deepValid = new StringBuilder();
        for (int i = 0; i < 15; i++) deepValid.append("{\"n\":");
        deepValid.append("1");
        for (int i = 0; i < 15; i++) deepValid.append("}");
        SettingRules.preflightJsonObject(deepValid.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Nesting depth > 16 should be rejected
        StringBuilder deepInvalid = new StringBuilder();
        for (int i = 0; i < 17; i++) deepInvalid.append("{\"n\":");
        deepInvalid.append("1");
        for (int i = 0; i < 17; i++) deepInvalid.append("}");
        assertThrows(() -> SettingRules.preflightJsonObject(deepInvalid.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void assertThrows(RunnableWithException r) {
        try {
            r.run();
            throw new AssertionError("Expected exception was not thrown");
        } catch (Throwable expected) {
            // Success
        }
    }

    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Throwable;
    }
}
