package com.xgimi.bridge;

import android.os.IBinder;
import android.os.Parcel;
import org.json.JSONObject;
import java.lang.reflect.Method;

/**
 * Shared helper for querying MediaTek Hardware Picture Quality (IPq HAL)
 * via vendor.mediatek.hardware.pq.IPq/default AIDL interface.
 */
public final class MtkPqClient {
    private static final String SERVICE_NAME = "vendor.mediatek.hardware.pq.IPq/default";

    private MtkPqClient() {}

    public static JSONObject queryPqInfo() {
        JSONObject res = new JSONObject();
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, SERVICE_NAME);
            if (binder == null || !binder.isBinderAlive()) return null;

            String desc = binder.getInterfaceDescriptor();

            // Method 54: getGlobalNonAwarePqSetting(1, 1) -> vector<EN_RETURN_VALUE>, vector<String>
            Parcel data54 = Parcel.obtain();
            Parcel reply54 = Parcel.obtain();
            try {
                data54.writeInterfaceToken(desc);
                data54.writeInt(1);
                data54.writeInt(1);
                boolean ok54 = binder.transact(54, data54, reply54, 0);
                if (ok54) {
                    reply54.readException();
                    int retLen = reply54.readInt();
                    for (int i = 0; i < retLen; i++) reply54.readInt();
                    int strLen = reply54.readInt();
                    for (int i = 0; i < strLen; i++) {
                        String s = reply54.readString();
                        if (s != null && s.startsWith("{")) {
                            try {
                                res.put("settings", new JSONObject(s));
                            } catch (Throwable ignore) {}
                        }
                    }
                }
            } finally {
                data54.recycle();
                reply54.recycle();
            }

            // Method 59: getHdrType(0, 1, 1) -> vector<EN_RETURN_VALUE>, vector<EN_PQ_HDR_TYPE>
            Parcel data59 = Parcel.obtain();
            Parcel reply59 = Parcel.obtain();
            try {
                data59.writeInterfaceToken(desc);
                data59.writeInt(0);
                data59.writeInt(1);
                data59.writeInt(1);
                boolean ok59 = binder.transact(59, data59, reply59, 0);
                if (ok59) {
                    reply59.readException();
                    int retLen = reply59.readInt();
                    for (int i = 0; i < retLen; i++) reply59.readInt();
                    int hdrLen = reply59.readInt();
                    if (hdrLen > 0) {
                        res.put("hdrType", reply59.readInt());
                    }
                }
            } finally {
                data59.recycle();
                reply59.recycle();
            }
        } catch (Throwable ignore) {
            return null;
        }
        return res.length() > 0 ? res : null;
    }
}
