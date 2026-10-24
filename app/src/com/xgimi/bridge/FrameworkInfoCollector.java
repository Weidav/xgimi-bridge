package com.xgimi.bridge;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * Collects standard Android TV framework telemetry:
 * DisplayManager, AudioManager, Settings (Global/System/Secure), SystemProperties, and Resources (RAM/Storage/Uptime).
 */
public class FrameworkInfoCollector {

    public static JSONObject collectFrameworkDisplay(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (d != null) {
                    JsonUtils.putSafe(o, "name", d.getName());
                    JsonUtils.putSafe(o, "state", d.getState());
                    JsonUtils.putSafe(o, "refreshRate", Math.round(d.getRefreshRate() * 100.0) / 100.0);
                    Display.Mode mode = d.getMode();
                    if (mode != null) {
                        JsonUtils.putSafe(o, "activeMode", mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight()
                                + " @ " + ProjectorFormatters.fmtDouble(mode.getRefreshRate()) + " Hz");
                        JsonUtils.putSafe(o, "width", mode.getPhysicalWidth());
                        JsonUtils.putSafe(o, "height", mode.getPhysicalHeight());
                    }
                    Display.Mode[] modes = d.getSupportedModes();
                    if (modes != null) {
                        JSONArray arr = new JSONArray();
                        for (Display.Mode m : modes) {
                            arr.put(m.getPhysicalWidth() + "x" + m.getPhysicalHeight()
                                    + " @ " + ProjectorFormatters.fmtDouble(m.getRefreshRate()) + " Hz");
                        }
                        JsonUtils.putSafe(o, "supportedModes", arr);
                    }
                    Display.Mode activeMode = mode != null ? mode : (modes != null && modes.length > 0 ? modes[0] : null);
                    if (activeMode != null) {
                        int[] types = activeMode.getSupportedHdrTypes();
                        if (types != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int t : types) {
                                if (sb.length() > 0) sb.append(", ");
                                if (t == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) sb.append("Dolby Vision");
                                else if (t == Display.HdrCapabilities.HDR_TYPE_HDR10) sb.append("HDR10");
                                else if (t == Display.HdrCapabilities.HDR_TYPE_HLG) sb.append("HLG");
                                else if (t == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) sb.append("HDR10+");
                                else sb.append("Type ").append(t);
                            }
                            JsonUtils.putSafe(o, "supportedHdrTypes", sb.toString());
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 24) {
                        Display.HdrCapabilities hdr = d.getHdrCapabilities();
                        if (hdr != null) {
                            JsonUtils.putSafe(o, "hdrMaxLuminance", Math.round(hdr.getDesiredMaxLuminance() * 10.0) / 10.0);
                            JsonUtils.putSafe(o, "hdrMaxAvgLuminance", Math.round(hdr.getDesiredMaxAverageLuminance() * 10.0) / 10.0);
                            JsonUtils.putSafe(o, "hdrMinLuminance", Math.round(hdr.getDesiredMinLuminance() * 1000.0) / 1000.0);
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 26) {
                        JsonUtils.putSafe(o, "isHdrCapable", d.isHdr());
                        JsonUtils.putSafe(o, "isWideColorGamut", d.isWideColorGamut());
                    }
                    if (Build.VERSION.SDK_INT >= 30) {
                        JsonUtils.putSafe(o, "minimalPostProcessingSupported", d.isMinimalPostProcessingSupported());
                    }
                }
            }
        } catch (Throwable ignore) {}
        return o;
    }

    public static JSONObject collectFrameworkAudio(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                JsonUtils.putSafe(o, "musicVolume", am.getStreamVolume(AudioManager.STREAM_MUSIC));
                JsonUtils.putSafe(o, "musicMaxVolume", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                if (Build.VERSION.SDK_INT >= 23) {
                    JsonUtils.putSafe(o, "musicMuted", am.isStreamMute(AudioManager.STREAM_MUSIC));
                    AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                    JSONArray arr = new JSONArray();
                    String activeRoute = null;
                    if (devs != null) {
                        for (AudioDeviceInfo dev : devs) {
                            int t = dev.getType();
                            String typeName = audioDeviceTypeToString(t);
                            CharSequence prod = dev.getProductName();
                            String entry = typeName + (prod != null && prod.length() > 0 ? " (" + prod + ")" : "");
                            arr.put(entry);
                            if (t == 29 /* TYPE_HDMI_EARC */ || t == 10 /* TYPE_HDMI_ARC */
                                    || t == 8 /* TYPE_BLUETOOTH_A2DP */ || t == 6 /* TYPE_LINE_DIGITAL */) {
                                activeRoute = typeName;
                            } else if (activeRoute == null && t == 2 /* TYPE_BUILTIN_SPEAKER */) {
                                activeRoute = typeName;
                            }
                        }
                    }
                    JsonUtils.putSafe(o, "outputDevices", arr);
                    JsonUtils.putSafe(o, "activeOutputRoute", activeRoute);
                }
            }
        } catch (Throwable ignore) {}
        return o;
    }

    private static String audioDeviceTypeToString(int type) {
        switch (type) {
            case 2: return "Built-in Speaker";
            case 3: return "Wired Headphones";
            case 4: return "Wired Headset";
            case 5: return "Analog Line Out";
            case 6: return "Digital Optical / SPDIF";
            case 7: return "Bluetooth SCO";
            case 8: return "Bluetooth A2DP";
            case 9: return "HDMI Out";
            case 10: return "HDMI ARC";
            case 11: return "USB Audio Device";
            case 12: return "USB Accessory";
            case 13: return "Dock";
            case 22: return "USB Headset";
            case 26: return "BLE Headset";
            case 27: return "BLE Speaker";
            case 29: return "HDMI eARC";
            default: return "Audio Device (" + type + ")";
        }
    }

    public static JSONObject collectSettings(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            ContentResolver cr = ctx.getContentResolver();
            String[] globalIntKeys = {
                    "gm_projector_brightness",
                    "gm_projector_color_temperature",
                    "gm_color_enhancing_filter",
                    "gm_color_space",
                    "picture_advanced_performance_mode",
                    "game_picture_mode",
                    "hdmi_auto_switch",
                    "nrdp_external_surround_sound_enabled",
                    "low_power_standby_enabled",
                    "hdmi_control_enabled",
                    "hdmi_system_audio_control_enabled"
            };
            for (String k : globalIntKeys) {
                try {
                    int v = Settings.Global.getInt(cr, k);
                    o.put("global." + k, v);
                } catch (Throwable ignore) {}
            }
            String[] globalStrKeys = {
                    "device_name",
                    "picture_list_hdr",
                    "game_picture_mode_params",
                    "nrdp_audio_platform_capabilities",
                    "user_disabled_hdr_formats",
                    "TvSourcesStatus",
                    "running_package_name",
                    "pre_package_name",
                    "HDR_Mode",
                    "pq_db_write_status"
            };
            for (String k : globalStrKeys) {
                try {
                    String v = Settings.Global.getString(cr, k);
                    if (v != null) o.put("global." + k, v);
                } catch (Throwable ignore) {}
            }

            String[] systemIntKeys = {
                    "screen_brightness",
                    "smart_turbo_light_enabled",
                    "spdif_mode",
                    "volume_music_speaker",
                    "volume_music_hdmi_arc",
                    "volume_music_hdmi_earc",
                    "volume_music_headphone",
                    "AudioService_DeviceVolumeBehavior_hdmi_arc",
                    "AudioService_DeviceVolumeBehavior_hdmi_earc"
            };
            for (String k : systemIntKeys) {
                try {
                    int v = Settings.System.getInt(cr, k);
                    o.put("system." + k, v);
                } catch (Throwable ignore) {}
            }

            String[] secureStrKeys = {
                    "tv_input_hdmi_edid_version",
                    "default_input_method"
            };
            for (String k : secureStrKeys) {
                try {
                    String v = Settings.Secure.getString(cr, k);
                    if (v != null) o.put("secure." + k, v);
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        return o;
    }

    public static JSONObject collectSysProps(AidlClient aidl) {
        JSONObject o = new JSONObject();
        String[] keys = {
                "ro.product.model",
                "ro.product.odm.model",
                "ro.boot.xgimi.modelname",
                "ro.vendor.xgimi.product.name",
                "ro.soc.manufacturer",
                "ro.soc.model",
                "ro.board.platform",
                "ro.vendor.mtk.serialno",
                "ro.vendor.mtk.pkg_version",
                "ro.system.build.id",
                "persist.vendor.xgimi.atvrc.version",
                "persist.vendor.xgimi.system.hgig.status",
                "persist.vendor.xgimi.system.wow",
                "vendor.xgimi.wow_type",
                "vendor.xgimi.wow_value",
                "vendor.display-size",
                "vendor.mtk.osd_size",
                "vendor.xgimi.ledOn",
                "vendor.xgimi.hdmi.playing.state",
                "vendor.xgimi.system.audio.digital_output_format",
                "sys.mtk.running.application",
                "sys.xgimi.pqmode.isperf",
                "vendor.mtk.playback.running",
                "vendor.mtk.key_repeat_delay_during_playback"
        };
        Method sysPropGet = null;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sysPropGet = sp.getMethod("get", String.class, String.class);
        } catch (Throwable ignore) {}

        for (String k : keys) {
            String val = null;
            if (sysPropGet != null) {
                try {
                    val = (String) sysPropGet.invoke(null, k, "");
                } catch (Throwable ignore) {}
            }
            if ((val == null || val.isEmpty()) && aidl != null) {
                val = aidl.getSystemPropertyViaAidl(k);
            }
            if (val != null && !val.isEmpty()) {
                JsonUtils.putSafe(o, k, val);
            }
        }
        return o;
    }

    public static JSONObject collectSystemResources(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            long upSec = SystemClock.elapsedRealtime() / 1000L;
            long days = upSec / 86400;
            long hrs = (upSec % 86400) / 3600;
            long mins = (upSec % 3600) / 60;
            long secs = upSec % 60;
            String upStr = (days > 0 ? days + "d " : "") + hrs + "h " + mins + "m " + secs + "s";
            JsonUtils.putSafe(o, "uptimeSeconds", upSec);
            JsonUtils.putSafe(o, "uptimeFormatted", upStr);

            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long totalMb = mi.totalMem / (1024 * 1024);
                long availMb = mi.availMem / (1024 * 1024);
                long usedMb = totalMb - availMb;
                int pct = totalMb > 0 ? (int) ((usedMb * 100) / totalMb) : 0;
                JsonUtils.putSafe(o, "ramTotalMb", totalMb);
                JsonUtils.putSafe(o, "ramUsedMb", usedMb);
                JsonUtils.putSafe(o, "ramAvailMb", availMb);
                JsonUtils.putSafe(o, "ramFormatted", usedMb + " MB / " + totalMb + " MB (" + pct + "%)");
            }

            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long totalBytes = stat.getTotalBytes();
            long availBytes = stat.getAvailableBytes();
            long usedBytes = totalBytes - availBytes;
            double totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0);
            double usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0);
            int sPct = totalBytes > 0 ? (int) ((usedBytes * 100) / totalBytes) : 0;
            JsonUtils.putSafe(o, "storageTotalGb", Math.round(totalGb * 10.0) / 10.0);
            JsonUtils.putSafe(o, "storageUsedGb", Math.round(usedGb * 10.0) / 10.0);
            JsonUtils.putSafe(o, "storageFormatted", ProjectorFormatters.fmtDouble(usedGb) + " GB / " + ProjectorFormatters.fmtDouble(totalGb) + " GB (" + sPct + "%)");

            JsonUtils.putSafe(o, "interactiveAwake", DeviceState.isAwake(ctx));
        } catch (Throwable ignore) {}
        return o;
    }
}
