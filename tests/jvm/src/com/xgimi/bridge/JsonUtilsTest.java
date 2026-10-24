package com.xgimi.bridge;

import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

public class JsonUtilsTest {
    public static void runAll() throws Exception {
        testPutSafe();
        testMapToJson();
        System.out.println("  [PASS] JsonUtilsTest");
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void testPutSafe() throws Exception {
        JSONObject o = new JSONObject();
        JsonUtils.putSafe(o, "nullKey", null);
        assertTrue(!o.has("nullKey"), "null value must be ignored");

        JsonUtils.putSafe(o, "str", "  hello\u0000world  ");
        assertTrue("helloworld".equals(o.getString("str")), "string should be trimmed and null characters removed");

        JsonUtils.putSafe(o, "nan", Float.NaN);
        assertTrue(!o.has("nan"), "NaN float must be ignored");

        JsonUtils.putSafe(o, "inf", Double.POSITIVE_INFINITY);
        assertTrue(!o.has("inf"), "Infinity double must be ignored");

        JsonUtils.putSafe(o, "flt", 12.3456f);
        assertTrue(Math.abs(o.getDouble("flt") - 12.35) < 0.001, "float should be rounded to 2 decimal places");

        JsonUtils.putSafe(o, "dbl", 12.345678d);
        assertTrue(Math.abs(o.getDouble("dbl") - 12.346) < 0.0001, "double should be rounded to 3 decimal places");
    }

    private static void testMapToJson() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("key1", "val1");
        Map<String, Object> inner = new HashMap<>();
        inner.put("innerKey", 42);
        map.put("nested", inner);

        JSONObject json = JsonUtils.mapToJson(map);
        assertTrue("val1".equals(json.getString("key1")), "key1 must match");
        assertTrue(json.getJSONObject("nested").getInt("innerKey") == 42, "nested value must match");
    }
}
