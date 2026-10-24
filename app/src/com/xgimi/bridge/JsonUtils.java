package com.xgimi.bridge;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collection;
import java.util.Map;

/**
 * Shared JSON serialization and sanitization utilities.
 */
public final class JsonUtils {
    private JsonUtils() {}

    /**
     * Converts a Map to a JSONObject, recursively handling nested Maps and Collections.
     */
    public static JSONObject mapToJson(Map<?, ?> map) {
        if (map == null) return new JSONObject();
        JSONObject obj = new JSONObject();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = entry.getKey().toString();
            Object val = entry.getValue();
            if (val instanceof Map) {
                putSafe(obj, key, mapToJson((Map<?, ?>) val));
            } else if (val instanceof Collection) {
                putSafe(obj, key, collectionToJson((Collection<?>) val));
            } else {
                putSafe(obj, key, val);
            }
        }
        return obj;
    }

    public static JSONArray collectionToJson(Collection<?> col) {
        if (col == null) return new JSONArray();
        JSONArray arr = new JSONArray();
        for (Object item : col) {
            if (item instanceof Map) {
                arr.put(mapToJson((Map<?, ?>) item));
            } else if (item instanceof Collection) {
                arr.put(collectionToJson((Collection<?>) item));
            } else if (item != null) {
                arr.put(item);
            }
        }
        return arr;
    }

    /**
     * Null-safe, type-sanitizing JSONObject field putter.
     * Trims strings and strips null terminators; rounds floats and doubles; discards NaN/Infinity.
     */
    public static void putSafe(JSONObject o, String key, Object val) {
        if (o == null || key == null || val == null) return;
        try {
            if (val instanceof String) {
                String s = ((String) val).replace("\u0000", "").trim();
                if (s.isEmpty()) return;
                o.put(key, s);
            } else if (val instanceof Float) {
                Float f = (Float) val;
                if (Float.isNaN(f) || Float.isInfinite(f)) return;
                o.put(key, Math.round(f * 100.0) / 100.0);
            } else if (val instanceof Double) {
                Double d = (Double) val;
                if (Double.isNaN(d) || Double.isInfinite(d)) return;
                o.put(key, Math.round(d * 1000.0) / 1000.0);
            } else {
                o.put(key, val);
            }
        } catch (Throwable ignore) {}
    }
}
