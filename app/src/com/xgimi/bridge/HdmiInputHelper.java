package com.xgimi.bridge;

import android.media.tv.TvInputInfo;

/**
 * Shared reflection helper for querying HDMI device info from android.media.tv.TvInputInfo.
 */
public final class HdmiInputHelper {
    public static final class HdmiInfo {
        public final Integer portId;
        public final String displayName;

        public HdmiInfo(Integer portId, String displayName) {
            this.portId = portId;
            this.displayName = displayName;
        }
    }

    private HdmiInputHelper() {}

    public static Object getHdmiDeviceInfo(TvInputInfo info) {
        if (info == null) return null;
        try {
            java.lang.reflect.Method m = info.getClass().getMethod("getHdmiDeviceInfo");
            return m.invoke(info);
        } catch (Throwable t) {
            return null;
        }
    }

    public static HdmiInfo extractHdmiInfo(TvInputInfo info) {
        Object hdi = getHdmiDeviceInfo(info);
        if (hdi == null) return null;
        Integer portId = null;
        String displayName = null;
        try {
            java.lang.reflect.Method getPortId = hdi.getClass().getMethod("getPortId");
            Object pid = getPortId.invoke(hdi);
            if (pid instanceof Integer) {
                portId = (Integer) pid;
            }
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method getDisplayName = hdi.getClass().getMethod("getDisplayName");
            Object dn = getDisplayName.invoke(hdi);
            if (dn != null) {
                displayName = dn.toString();
            }
        } catch (Throwable ignored) {}
        return new HdmiInfo(portId, displayName);
    }

    public static Integer getHdmiPortId(TvInputInfo info) {
        HdmiInfo hi = extractHdmiInfo(info);
        return hi != null ? hi.portId : null;
    }
}
