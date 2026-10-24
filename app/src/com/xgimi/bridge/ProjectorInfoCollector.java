package com.xgimi.bridge;

import android.content.Context;

import org.json.JSONObject;

import java.util.Map;

/**
 * Aggregates projector telemetry from:
 *   1. Standard Android TV APIs via {@link FrameworkInfoCollector}
 *   2. com.xgimi.xgimiservice AIDL via {@link AidlClient}
 *   3. xgimi.hardware.gmpf.* HAL services & dumpsys hdmi_control via shell-uid {@link KeyDispatcher}
 * Telemetry categories and summary status are built by {@link ProjectorCategoryBuilder}.
 */
public class ProjectorInfoCollector {

    public static JSONObject collectAll(Context ctx, AidlClient aidl, LocalAdbClient adb) {
        long startMs = System.currentTimeMillis();
        JSONObject res = new JSONObject();

        // 1. Collect AIDL snapshot & extended getters
        JSONObject aidlJson = new JSONObject();
        try {
            Map<String, Object> aidlMap = aidl.collectAidlInfo();
            if (aidlMap != null) {
                aidlJson = JsonUtils.mapToJson(aidlMap);
            }
        } catch (Throwable ignore) {}

        // 2. Collect GMPF + CEC info via shell KeyDispatcher
        JSONObject gmpfJson = new JSONObject();
        String gmpfError = null;
        try {
            String rawHw = adb.queryHardwareInfo();
            if (rawHw != null && !rawHw.isEmpty()) {
                gmpfJson = new JSONObject(rawHw);
            }
        } catch (Throwable t) {
            gmpfError = t.getMessage();
        }

        // 3. Collect Android Framework, Settings & SystemProperties
        JSONObject fwDisplay = FrameworkInfoCollector.collectFrameworkDisplay(ctx);
        JSONObject fwAudio = FrameworkInfoCollector.collectFrameworkAudio(ctx);
        JSONObject settingsJson = FrameworkInfoCollector.collectSettings(ctx);
        JSONObject sysPropsJson = FrameworkInfoCollector.collectSysProps(aidl);
        JSONObject sysResJson = FrameworkInfoCollector.collectSystemResources(ctx);

        // MTK PQ report
        JSONObject gMtkPq = gmpfJson.optJSONObject("mtkPq");
        if (gMtkPq == null) {
            gMtkPq = MtkPqClient.queryPqInfo();
        }

        try {
            ProjectorCategoryBuilder.CategorizedResult categorized = ProjectorCategoryBuilder.build(
                    ctx, aidlJson, gmpfJson, fwDisplay, fwAudio,
                    settingsJson, sysPropsJson, sysResJson, gMtkPq);

            res.put("ok", true);
            res.put("ts", System.currentTimeMillis());
            res.put("collectionMs", System.currentTimeMillis() - startMs);
            if (gmpfError != null) res.put("gmpfWarning", gmpfError);
            res.put("summary", categorized.summary);
            res.put("categories", categorized.categories);

            // Include raw telemetry for full transparency / debugging
            JSONObject raw = new JSONObject();
            raw.put("aidl", aidlJson);
            raw.put("gmpf", gmpfJson);
            JSONObject mtkSettings = gMtkPq != null ? gMtkPq.optJSONObject("settings") : null;
            raw.put("mtkPq", mtkSettings != null ? mtkSettings : (gMtkPq != null ? gMtkPq : JSONObject.NULL));
            String tvSourcesStatus = settingsJson.optString("global.TvSourcesStatus", null);
            if (tvSourcesStatus != null) raw.put("tvSourcesStatus", tvSourcesStatus);
            raw.put("frameworkDisplay", fwDisplay);
            raw.put("frameworkAudio", fwAudio);
            raw.put("settings", settingsJson);
            raw.put("systemProperties", sysPropsJson);
            raw.put("systemResources", sysResJson);
            res.put("raw", raw);
        } catch (Throwable t) {
            try { res.put("error", t.getMessage()); } catch (Throwable ignore) {}
        }

        return res;
    }
}
