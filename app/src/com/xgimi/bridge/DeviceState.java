package com.xgimi.bridge;

import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Shared device state queries (awake/power state, currently running application).
 */
public final class DeviceState {
    private DeviceState() {}

    public static boolean isAwake(Context ctx) {
        if (ctx == null) return false;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    public static String getRunningApp(Context ctx) {
        if (ctx == null) return null;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String prop = (String) get.invoke(null, "sys.mtk.running.application", "");
            if (prop != null && !prop.trim().isEmpty() && !"null".equalsIgnoreCase(prop.trim())) {
                return prop.trim();
            }
        } catch (Throwable ignore) {}
        try {
            String pkg = Settings.Global.getString(ctx.getContentResolver(), "running_package_name");
            if (pkg != null && !pkg.trim().isEmpty() && !"null".equalsIgnoreCase(pkg.trim())) {
                return pkg.trim();
            }
        } catch (Throwable ignore) {}
        return null;
    }
}
