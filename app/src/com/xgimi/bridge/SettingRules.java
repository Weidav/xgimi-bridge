package com.xgimi.bridge;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Common validation rules, allowed setting names and ranges for direct writes and presets.
 */
public class SettingRules {
    public static final Set<Integer> ALLOWED_PICTURE_MODES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(1, 3, 4, 6, 9)));

    public static final Set<Integer> ALLOWED_COLOR_TEMPS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(0, 1, 2, 3, 4)));

    public static final Set<Integer> ALLOWED_GAME_MODES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(0, 1, 2)));

    public static final Set<String> ALLOWED_INPUTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("STORAGE", "HOME", "APPS", "HDMI1", "HDMI2", "HDMI3")));

    public static final Set<String> PRESET_WRITABLE_FIELDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "wbRed", "wbGreen", "wbBlue",
                    "colorTemp", "pictureMode", "gameMode",
                    "brightness", "contrast", "saturation", "sharpness", "hue")));

    public static JSONObject getEnumsJson() {
        JSONObject obj = new JSONObject();
        try {
            org.json.JSONArray picModes = new org.json.JSONArray();
            picModes.put(new JSONObject().put("value", 1).put("label", "Movie"));
            picModes.put(new JSONObject().put("value", 9).put("label", "Football"));
            picModes.put(new JSONObject().put("value", 6).put("label", "Office"));
            picModes.put(new JSONObject().put("value", 4).put("label", "Game"));
            picModes.put(new JSONObject().put("value", 3).put("label", "User"));
            obj.put("pictureModes", picModes);

            org.json.JSONArray colTemps = new org.json.JSONArray();
            colTemps.put(new JSONObject().put("value", 0).put("label", "System"));
            colTemps.put(new JSONObject().put("value", 1).put("label", "Warm"));
            colTemps.put(new JSONObject().put("value", 2).put("label", "Standard"));
            colTemps.put(new JSONObject().put("value", 3).put("label", "Cold"));
            colTemps.put(new JSONObject().put("value", 4).put("label", "User"));
            obj.put("colorTemps", colTemps);

            org.json.JSONArray gameModes = new org.json.JSONArray();
            gameModes.put(new JSONObject().put("value", 0).put("label", "Off"));
            gameModes.put(new JSONObject().put("value", 1).put("label", "Standard"));
            gameModes.put(new JSONObject().put("value", 2).put("label", "Boost"));
            obj.put("gameModes", gameModes);

            org.json.JSONArray knobs = new org.json.JSONArray();
            for (String field : PRESET_WRITABLE_FIELDS) {
                knobs.put(new JSONObject().put("key", field).put("min", 0).put("max", 100));
            }
            obj.put("knobs", knobs);
            obj.put("inputs", new org.json.JSONArray(ALLOWED_INPUTS));
        } catch (JSONException ignored) {}
        return obj;
    }

    private static final Map<String, String> CANONICAL_KNOB_MAP = new HashMap<>();
    static {
        CANONICAL_KNOB_MAP.put("wb-red", "wbRed");
        CANONICAL_KNOB_MAP.put("wbred", "wbRed");
        CANONICAL_KNOB_MAP.put("wbRed", "wbRed");

        CANONICAL_KNOB_MAP.put("wb-green", "wbGreen");
        CANONICAL_KNOB_MAP.put("wbgreen", "wbGreen");
        CANONICAL_KNOB_MAP.put("wbGreen", "wbGreen");

        CANONICAL_KNOB_MAP.put("wb-blue", "wbBlue");
        CANONICAL_KNOB_MAP.put("wbblue", "wbBlue");
        CANONICAL_KNOB_MAP.put("wbBlue", "wbBlue");

        CANONICAL_KNOB_MAP.put("color-temp", "colorTemp");
        CANONICAL_KNOB_MAP.put("colortemp", "colorTemp");
        CANONICAL_KNOB_MAP.put("colorTemp", "colorTemp");

        CANONICAL_KNOB_MAP.put("picture-mode", "pictureMode");
        CANONICAL_KNOB_MAP.put("picturemode", "pictureMode");
        CANONICAL_KNOB_MAP.put("pictureMode", "pictureMode");

        CANONICAL_KNOB_MAP.put("game-mode", "gameMode");
        CANONICAL_KNOB_MAP.put("gamemode", "gameMode");
        CANONICAL_KNOB_MAP.put("gameMode", "gameMode");

        CANONICAL_KNOB_MAP.put("brightness", "brightness");
        CANONICAL_KNOB_MAP.put("contrast", "contrast");
        CANONICAL_KNOB_MAP.put("saturation", "saturation");
        CANONICAL_KNOB_MAP.put("sharpness", "sharpness");
        CANONICAL_KNOB_MAP.put("hue", "hue");
    }

    public static String canonicalField(String name) {
        if (name == null) return null;
        return CANONICAL_KNOB_MAP.get(name);
    }

    public static boolean isWritableField(String name) {
        String canonical = canonicalField(name);
        return canonical != null && PRESET_WRITABLE_FIELDS.contains(canonical);
    }

    /**
     * Validates that raw is a finite, mathematically integral number within [min, max].
     * Returns the integer value, or throws IllegalArgumentException.
     */
    public static int validateIntegerRange(Object raw, int min, int max, String fieldName) {
        if (raw == null || raw == JSONObject.NULL) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        if (raw instanceof Boolean || raw instanceof String || raw instanceof Character) {
            throw new IllegalArgumentException(fieldName + " must be a number");
        }
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(fieldName + " must be a number");
        }

        Number num = (Number) raw;
        double d = num.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException(fieldName + " must be a finite number");
        }

        if (Math.floor(d) != d) {
            throw new IllegalArgumentException(fieldName + " must be an integer (fractional values not allowed)");
        }

        if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(fieldName + " integer overflow");
        }

        int val = (int) d;
        if (val < min || val > max) {
            throw new IllegalArgumentException(fieldName + " out of range [" + min + ".." + max + "]: " + val);
        }
        return val;
    }

    public static int validateSettingValue(String field, Object raw) {
        String canonical = canonicalField(field);
        if (canonical == null) {
            throw new IllegalArgumentException("Unknown setting: " + field);
        }

        switch (canonical) {
            case "wbRed":
            case "wbGreen":
            case "wbBlue":
            case "brightness":
            case "contrast":
            case "saturation":
            case "sharpness":
            case "hue":
                return validateIntegerRange(raw, 0, 100, canonical);

            case "pictureMode": {
                int mode = validateIntegerRange(raw, Integer.MIN_VALUE, Integer.MAX_VALUE, canonical);
                if (!ALLOWED_PICTURE_MODES.contains(mode)) {
                    throw new IllegalArgumentException("Invalid pictureMode: " + mode);
                }
                return mode;
            }

            case "colorTemp": {
                int temp = validateIntegerRange(raw, Integer.MIN_VALUE, Integer.MAX_VALUE, canonical);
                if (!ALLOWED_COLOR_TEMPS.contains(temp)) {
                    throw new IllegalArgumentException("Invalid colorTemp: " + temp);
                }
                return temp;
            }

            case "gameMode": {
                int gm = validateIntegerRange(raw, Integer.MIN_VALUE, Integer.MAX_VALUE, canonical);
                if (!ALLOWED_GAME_MODES.contains(gm)) {
                    throw new IllegalArgumentException("Invalid gameMode: " + gm);
                }
                return gm;
            }

            default:
                throw new IllegalArgumentException("Unknown setting: " + field);
        }
    }

    public static String validateInputTo(String rawTo) {
        if (rawTo == null || rawTo.trim().isEmpty()) {
            throw new IllegalArgumentException("missing to");
        }
        String to = rawTo.trim().toUpperCase(Locale.US);
        if (!ALLOWED_INPUTS.contains(to)) {
            throw new IllegalArgumentException("invalid to: " + to);
        }
        return to;
    }

    public static int validateKeyCode(int code) {
        if (code < 0 || code > 1000) {
            throw new IllegalArgumentException("invalid keycode: " + code);
        }
        return code;
    }

    public static String validateText(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("missing text");
        }
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) {
            throw new IllegalArgumentException("text exceeds max bytes (4096)");
        }
        int codePointCount = raw.codePointCount(0, raw.length());
        if (codePointCount > 1024) {
            throw new IllegalArgumentException("text exceeds max code points (1024)");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\0') {
                throw new IllegalArgumentException("text contains NUL character");
            }
        }
        return raw;
    }

    /**
     * Preflights JSON bytes for strict UTF-8, nesting depth <= 16, string escape correctness,
     * complete object framing, and rejection of trailing non-whitespace data.
     */
    public static void preflightJsonObject(byte[] body) throws JSONException {
        if (body == null || body.length == 0) {
            throw new JSONException("Empty body");
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars;
        try {
            chars = decoder.decode(ByteBuffer.wrap(body));
        } catch (CharacterCodingException e) {
            throw new JSONException("Invalid UTF-8 sequence in JSON body: " + e.getMessage());
        }

        String s = chars.toString().trim();
        if (!s.startsWith("{") || !s.endsWith("}")) {
            throw new JSONException("Body must be a JSON object");
        }

        int depth = 0;
        int maxDepth = 16;
        boolean inString = false;
        boolean escape = false;
        boolean started = false;
        boolean closed = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                if (closed) {
                    throw new JSONException("Trailing data after JSON object");
                }
                inString = true;
                continue;
            }

            if (c == '{' || c == '[') {
                if (closed) {
                    throw new JSONException("Trailing data after JSON object");
                }
                started = true;
                depth++;
                if (depth > maxDepth) {
                    throw new JSONException("JSON nesting depth exceeded limit of " + maxDepth);
                }
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    throw new JSONException("Unbalanced JSON braces");
                }
                if (depth == 0) {
                    closed = true;
                }
            } else if (closed) {
                if (!Character.isWhitespace(c)) {
                    throw new JSONException("Trailing non-whitespace characters after JSON object");
                }
            }
        }

        if (inString || depth != 0 || !started || !closed) {
            throw new JSONException("Unbalanced or incomplete JSON");
        }
    }
}
