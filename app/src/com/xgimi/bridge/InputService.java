package com.xgimi.bridge;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Service encapsulating TV input discovery and HDMI / storage switching logic.
 */
public class InputService {
    private final Context ctx;

    public InputService(Context ctx) {
        this.ctx = ctx;
    }

    public JSONArray getInputs() {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        JSONArray arr = new JSONArray();
        if (tim != null) {
            List<TvInputInfo> list = tim.getTvInputList();
            if (list != null) {
                for (TvInputInfo info : list) {
                    if (info == null) continue;
                    JSONObject o = new JSONObject();
                    try {
                        o.put("id", info.getId());
                        o.put("type", info.getType());
                        CharSequence label = info.loadLabel(ctx);
                        o.put("label", label != null ? label.toString() : null);
                        CharSequence custom = info.loadCustomLabel(ctx);
                        o.put("customLabel", custom != null ? custom.toString() : null);
                        HdmiInputHelper.HdmiInfo hdmi = HdmiInputHelper.extractHdmiInfo(info);
                        if (hdmi != null) {
                            if (hdmi.displayName != null) o.put("displayName", hdmi.displayName);
                            if (hdmi.portId != null) o.put("hdmiPortId", hdmi.portId.intValue());
                        }
                    } catch (JSONException ignored) {}
                    arr.put(o);
                }
            }
        }
        return arr;
    }

    public void switchToStorage() {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses("com.google.android.tv.inputplayer");
            }
        } catch (Throwable ignored) {}
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(home);
    }

    public void switchToHdmi(int port) {
        String inputId = findHdmiPassthroughInputId(port);
        if (inputId == null) {
            throw new IllegalArgumentException("HDMI port " + port + " not found");
        }
        Intent it = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId));
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(it);
    }

    public String findHdmiPassthroughInputId(int port) {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        List<TvInputInfo> list = tim.getTvInputList();
        if (list == null) return null;
        String fallback = null;
        for (TvInputInfo info : list) {
            if (info == null) continue;
            Integer pid = HdmiInputHelper.getHdmiPortId(info);
            if (pid != null && pid.intValue() == port) {
                if (info.getParentId() == null) return info.getId();
                if (fallback == null) fallback = info.getId();
            }
        }
        return fallback;
    }
}
