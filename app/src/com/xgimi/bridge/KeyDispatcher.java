package com.xgimi.bridge;

import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Long-lived input-event injector & shell-uid hardware telemetry helper.
 * Started once per bridge lifetime by spawning {@code app_process} over an ADB shell stream
 * (so it runs as shell uid, which holds INJECT_EVENTS and SELinux binder call rights to
 * {@code aidl_gmpf_xgimi}).
 */
public class KeyDispatcher {
    private static final int WAKE_PORT = 8081;
    private static final Object LOCK = new Object();
    private static final int INJECT_MODE_ASYNC = 0;

    public static void main(String[] args) {
        try {
            Class<?> imClass = Class.forName("android.hardware.input.InputManagerGlobal");
            Method getInstance = imClass.getMethod("getInstance");
            Object im = getInstance.invoke(null);
            Method injectInputEvent = imClass.getMethod(
                    "injectInputEvent",
                    Class.forName("android.view.InputEvent"),
                    int.class);

            startWakeServer(im, injectInputEvent);

            System.out.println("READY");
            System.out.flush();

            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    if (line.equals("INFO")) {
                        JSONObject info = collectHardwareInfo();
                        System.out.println("OK INFO " + info.toString());
                        System.out.flush();
                        continue;
                    }
                    if (line.startsWith("TEXT ")) {
                        byte[] raw = Base64.decode(line.substring(5).trim(), Base64.DEFAULT);
                        String text = new String(raw, "UTF-8");
                        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMapSource());
                        KeyEvent[] events = kcm.getEvents(text.toCharArray());
                        if (events == null) {
                            System.out.println("ERR UnsupportedCharacters: cannot map text to key events");
                        } else {
                            synchronized (LOCK) {
                                for (KeyEvent ev : events) {
                                    injectInputEvent.invoke(im, ev, INJECT_MODE_ASYNC);
                                }
                            }
                            System.out.println("OK TEXT");
                        }
                        System.out.flush();
                        continue;
                    }
                    int code = Integer.parseInt(line);
                    dispatchKey(im, injectInputEvent, code);
                    System.out.println("OK " + code);
                } catch (Exception e) {
                    System.out.println("ERR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                System.out.flush();
            }
        } catch (Throwable t) {
            System.out.println("FATAL " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    private static void dispatchKey(Object im, Method injectInputEvent, int code) throws Exception {
        synchronized (LOCK) {
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0,
                    0, KeyCharacterMapSource(), 0, 0,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0,
                    0, KeyCharacterMapSource(), 0, 0,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            injectInputEvent.invoke(im, down, INJECT_MODE_ASYNC);
            injectInputEvent.invoke(im, up, INJECT_MODE_ASYNC);
        }
    }

    private static void startWakeServer(final Object im, final Method injectInputEvent) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.ServerSocket ss = null;
                try {
                    ss = new java.net.ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new java.net.InetSocketAddress(WAKE_PORT));
                    while (true) {
                        final java.net.Socket s = ss.accept();
                        try {
                            s.setSoTimeout(2000);
                            dispatchKey(im, injectInputEvent, KeyEvent.KEYCODE_WAKEUP);
                            java.io.OutputStream out = s.getOutputStream();
                            String body = "{\"ok\":true,\"action\":\"wake\"}";
                            String resp = "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: application/json\r\n"
                                    + "Content-Length: " + body.length() + "\r\n"
                                    + "Connection: close\r\n\r\n"
                                    + body;
                            out.write(resp.getBytes("UTF-8"));
                            out.flush();
                        } catch (Throwable ignore) {
                        } finally {
                            try { s.close(); } catch (Throwable ignore) {}
                        }
                    }
                } catch (Throwable ignore) {
                } finally {
                    if (ss != null) try { ss.close(); } catch (Throwable ignore) {}
                }
            }
        }, "KeyDispatcher-Wake");
        t.setDaemon(true);
        t.start();
    }

    private static int KeyCharacterMapSource() {
        return android.view.KeyCharacterMap.VIRTUAL_KEYBOARD;
    }

    // ---- GMPF & Shell Telemetry Collection ----

    private static Object getGmpfService(String simpleName) {
        try {
            Class<?> cls = Class.forName("xgimi.hardware.gmpf." + simpleName);
            Method m = cls.getMethod("getInstance");
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object callNoArg(Object target, String method) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method);
            Object res = m.invoke(target);
            if (res instanceof Byte) return ((Byte) res).intValue();
            return res;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object callIntArg(Object target, String method, int arg) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method, int.class);
            Object res = m.invoke(target, arg);
            if (res instanceof Byte) return ((Byte) res).intValue();
            return res;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object callIntIntArg(Object target, String method, int a, int b) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method, int.class, int.class);
            Object res = m.invoke(target, a, b);
            if (res instanceof Byte) return ((Byte) res).intValue();
            return res;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object callByteArg(Object target, String method, int arg) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method, byte.class);
            Object res = m.invoke(target, (byte) arg);
            if (res instanceof Byte) return ((Byte) res).intValue();
            return res;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getFieldVal(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Byte) return ((Byte) v).intValue();
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void putSafe(JSONObject o, String key, Object val) {
        if (val == null) return;
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

    private static final String[] SENSOR_NAMES = {
            "System",
            "Environment / Ambient",
            "DMD / DLP Chip",
            "Color Wheel / Phosphor",
            "Green Laser/LED",
            "Red Laser/LED",
            "Blue Phosphor",
            "System Assist",
            "Blue Laser/LED",
            "CPU / SoC",
            "Drive Board",
            "Red 2 Laser/LED"
    };

    private static JSONObject cachedCec = null;
    private static long cachedCecTs = 0;
    private static Object cachedTof = null;
    private static Object cachedAmbient = null;
    private static long cachedTofTs = 0;

    private static JSONObject collectHardwareInfo() {
        JSONObject root = new JSONObject();
        JSONObject timings = new JSONObject();
        long t0 = System.currentTimeMillis();

        // 1. GmTvManagerService
        Object tv = getGmpfService("GmTvManagerService");
        if (tv != null) {
            JSONObject tvObj = new JSONObject();
            int curSrc = 0;
            Object srcObj = callNoArg(tv, "getCurrentInputSource");
            if (srcObj instanceof Integer) curSrc = (Integer) srcObj;
            putSafe(tvObj, "currentInputSource", srcObj);
            putSafe(tvObj, "signalStatus", callNoArg(tv, "getSignalStatus"));
            putSafe(tvObj, "hdrEnable", callNoArg(tv, "getHdrEnable"));
            putSafe(tvObj, "hdrType", callNoArg(tv, "getHdrType"));
            putSafe(tvObj, "dolbyVisionPicMode", callNoArg(tv, "getDolbyVisionPicMode"));
            putSafe(tvObj, "isFFMPlaying", callNoArg(tv, "isFFMPlaying"));
            putSafe(tvObj, "isHdrVividPlaying", callNoArg(tv, "isHdrVividPlaying"));
            putSafe(tvObj, "isImaxVideoPlaying", callNoArg(tv, "isImaxVideoPlaying"));
            putSafe(tvObj, "imaxState", callIntArg(tv, "getImaxState", curSrc));
            putSafe(tvObj, "imaxType", callNoArg(tv, "getImaxType"));
            putSafe(tvObj, "ffmState", callIntArg(tv, "getFFMState", curSrc));
            putSafe(tvObj, "allmEnable", callNoArg(tv, "getAllmEnableState"));
            putSafe(tvObj, "allmStatus", callIntArg(tv, "getAllmStatus", curSrc));
            putSafe(tvObj, "vrrEnableState", callIntArg(tv, "getVRREnableState", curSrc));
            putSafe(tvObj, "amdFreeSyncState", callNoArg(tv, "getAmdFreeSyncState"));
            putSafe(tvObj, "hgigMode", callNoArg(tv, "getHGiGMode"));
            putSafe(tvObj, "gameModeOption", callNoArg(tv, "getGameModeOption"));
            putSafe(tvObj, "gamePictureMode", callIntArg(tv, "getGamePictureMode", curSrc));
            putSafe(tvObj, "memcLevel", callIntArg(tv, "getMemcLevel", curSrc));
            putSafe(tvObj, "aspectRatio", callNoArg(tv, "getAspectRatio"));
            putSafe(tvObj, "edidVersion", callNoArg(tv, "getEdidVersion"));
            putSafe(tvObj, "hdmiRange", callNoArg(tv, "getHdmiRange"));
            putSafe(tvObj, "hdmi1Connected", callByteArg(tv, "getHdmiConnectStatus", 1));
            putSafe(tvObj, "hdmi2Connected", callByteArg(tv, "getHdmiConnectStatus", 2));
            putSafe(tvObj, "earcEnableState", callNoArg(tv, "getEARCEnableState"));
            putSafe(tvObj, "earcStatus", callNoArg(tv, "getEARCStatus"));
            putSafe(tvObj, "dynamicContrastEnable", callIntArg(tv, "getTvDynamicContrastEnable", curSrc));
            putSafe(tvObj, "gammaLevel", callIntArg(tv, "getTvGammaLevel", curSrc));
            putSafe(tvObj, "nr3d", callIntArg(tv, "get3DNR", curSrc));
            putSafe(tvObj, "aiPqEnable", callIntArg(tv, "getAiPqEnableStatus", curSrc));
            putSafe(tvObj, "aiContrastStatus", callIntArg(tv, "getAIContrastStatus", curSrc));
            putSafe(tvObj, "aiContrastLevel", callIntArg(tv, "getAIContrastLevel", curSrc));
            putSafe(tvObj, "aisrType", callNoArg(tv, "getAisrType"));
            putSafe(tvObj, "aisrLevel", callNoArg(tv, "getAisrLevel"));
            putSafe(tvObj, "cecWakeUpState", callNoArg(tv, "getCecWakeUpState"));
            putSafe(tvObj, "wolWakeUp", callNoArg(tv, "getWolWakeUp"));
            putSafe(tvObj, "wowWakeUp", callNoArg(tv, "getWowWakeUp"));

            // getHdmiInfo() -> stHdmiInfo
            Object hdmiInfo = callNoArg(tv, "getHdmiInfo");
            if (hdmiInfo != null) {
                JSONObject hi = new JSONObject();
                putSafe(hi, "width", getFieldVal(hdmiInfo, "u32Width"));
                putSafe(hi, "height", getFieldVal(hdmiInfo, "u32Height"));
                putSafe(hi, "frameRate", getFieldVal(hdmiInfo, "u32FrameRate"));
                putSafe(hi, "interlace", getFieldVal(hdmiInfo, "bInterlace"));
                putSafe(hi, "isHdmiMode", getFieldVal(hdmiInfo, "bIsHdmiMode"));
                putSafe(tvObj, "hdmiInfo", hi);
            }

            // getVideoInfo(stVideoInfo)
            try {
                Class<?> viCls = Class.forName("xgimi.hardware.gmpf.IGmTvType.stVideoInfo");
                Object viInst = viCls.getDeclaredConstructor().newInstance();
                Method mVi = tv.getClass().getMethod("getVideoInfo", viCls);
                Object ok = mVi.invoke(tv, viInst);
                if (Boolean.TRUE.equals(ok)) {
                    JSONObject vi = new JSONObject();
                    putSafe(vi, "width", getFieldVal(viInst, "width"));
                    putSafe(vi, "height", getFieldVal(viInst, "height"));
                    putSafe(vi, "frameRate", getFieldVal(viInst, "frameRate"));
                    putSafe(vi, "videoType", getFieldVal(viInst, "videoType"));
                    putSafe(vi, "hdmiOrDvi", getFieldVal(viInst, "bHdmiOrDvi"));
                    putSafe(tvObj, "videoInfo", vi);
                }
            } catch (Throwable ignore) {}

            putSafe(root, "tv", tvObj);
        }
        long t1 = System.currentTimeMillis();
        putSafe(timings, "tvMs", t1 - t0);

        // 2. DisplayManagerService
        Object disp = getGmpfService("DisplayManagerService");
        if (disp != null) {
            JSONObject dObj = new JSONObject();
            putSafe(dObj, "dispLumensLevel", callNoArg(disp, "getDispLumensLevel"));
            putSafe(dObj, "dlpLumensLevel", callNoArg(disp, "getDlpLumensLevel"));
            putSafe(dObj, "dlpLumensMode", callNoArg(disp, "getDlpLumensMode"));
            putSafe(dObj, "dualLightMode", callNoArg(disp, "getDualLightMode"));
            putSafe(dObj, "dispOutputFps", callNoArg(disp, "getDispOutputFps"));
            putSafe(dObj, "dispColorSpace", callIntIntArg(disp, "getDispColorSpace", 0, 0));
            putSafe(dObj, "colorEnhance", callNoArg(disp, "getColorEnhance"));
            putSafe(dObj, "colorEnhanceType", callNoArg(disp, "getColorEnhanceType"));
            putSafe(dObj, "dynamicBlackLevel", callNoArg(disp, "getDynamicBlackLevel"));
            putSafe(dObj, "dynamicBlackType", callNoArg(disp, "getDynamicBlackType"));
            putSafe(dObj, "contrastEnhanceLvl", callNoArg(disp, "getDispContrastEnhanceLvl"));
            putSafe(dObj, "rainbowReductionStatus", callNoArg(disp, "getRainbowReductionStatus"));
            putSafe(dObj, "rainbowReductionLevel", callNoArg(disp, "getRainbowReductionLevel"));
            putSafe(dObj, "dlpXprMode", callNoArg(disp, "getDlpXPRMode"));
            putSafe(dObj, "dlpImageVersion", callNoArg(disp, "getDlpImageVersion"));
            putSafe(dObj, "whiteBalanceHalVersion", callNoArg(disp, "getWhiteBalanceHalVersion"));
            putSafe(dObj, "lightUsageInfo", callNoArg(disp, "getLightUsageInfo"));
            putSafe(dObj, "lightUsageCycleInfo", callNoArg(disp, "getLightUsageCycleInfo"));
            putSafe(dObj, "projectorPutMode", callNoArg(disp, "getProjectorPutMode"));
            putSafe(dObj, "currentKeystoneMode", callNoArg(disp, "getCurrentKeystoneMode"));
            putSafe(dObj, "kstAdjustType", callNoArg(disp, "getKstAdjustType"));
            putSafe(dObj, "currentZoomStep", callIntArg(disp, "getCurrentZoomStep", 0));
            putSafe(dObj, "screenZoomFactor", callNoArg(disp, "getScreenZoomfactor"));
            putSafe(dObj, "ambientLightAdaptOn", callNoArg(disp, "getALOnOff"));
            putSafe(dObj, "ambientLightAdaptType", callNoArg(disp, "getALOnType"));
            putSafe(dObj, "humanDetectOn", callNoArg(disp, "getHumanDetectOnOff"));
            putSafe(dObj, "humanDetectMode", callNoArg(disp, "getHumanDetectMode"));
            putSafe(dObj, "humanDetectScreenOn", callNoArg(disp, "getHumanDetectScreenOnOff"));
            putSafe(dObj, "isPlayingHdrVideo", callNoArg(disp, "isPlayingHDRVideo"));
            putSafe(dObj, "isVideoPlaying", callNoArg(disp, "isVideoPlaying"));
            putSafe(dObj, "screenOn", callNoArg(disp, "getScreenOnOff"));
            putSafe(dObj, "current3dFormat", callNoArg(disp, "getCurrent3DFormat"));
            putSafe(dObj, "is3dTo2dEnabled", callNoArg(disp, "is3DTo2DEnabled"));
            putSafe(dObj, "dolbyDefaultMode", callNoArg(disp, "getDolbyDefaultMode"));
            putSafe(dObj, "lensDispMode", callNoArg(disp, "getLensDispMode"));

            // RGB light source component levels
            Object rgbObj = callNoArg(disp, "getDlpLumensComponent_RGB_Level");
            if (rgbObj != null) {
                JSONObject rgb = new JSONObject();
                putSafe(rgb, "r", getFieldVal(rgbObj, "R"));
                putSafe(rgb, "g", getFieldVal(rgbObj, "G"));
                putSafe(rgb, "b", getFieldVal(rgbObj, "B"));
                putSafe(rgb, "c", getFieldVal(rgbObj, "C"));
                putSafe(dObj, "rgbLevels", rgb);
            }

            // OutputTimingInfo
            try {
                Class<?> otiCls = Class.forName("xgimi.hardware.gmpf.IDisplayType.OutputTimingInfo");
                Object otiInst = otiCls.getDeclaredConstructor().newInstance();
                Method mOti = disp.getClass().getMethod("getOutputTimingInfo", otiCls);
                Object ok = mOti.invoke(disp, otiInst);
                if (Boolean.TRUE.equals(ok)) {
                    JSONObject oti = new JSONObject();
                    putSafe(oti, "currHres", getFieldVal(otiInst, "curr_hres"));
                    putSafe(oti, "currVres", getFieldVal(otiInst, "curr_vres"));
                    putSafe(oti, "dispWidth", getFieldVal(otiInst, "disp_width"));
                    putSafe(oti, "dispHeight", getFieldVal(otiInst, "disp_height"));
                    putSafe(oti, "vfreq", getFieldVal(otiInst, "vfreq"));
                    putSafe(oti, "currTimingIdx", getFieldVal(otiInst, "curr_timing_idx"));
                    putSafe(dObj, "outputTiming", oti);
                }
            } catch (Throwable ignore) {}

            // st_ZoomStep
            try {
                Class<?> zsCls = Class.forName("xgimi.hardware.gmpf.IDisplayType.st_ZoomStep");
                Object zsInst = zsCls.getDeclaredConstructor().newInstance();
                Method mZs = disp.getClass().getMethod("getCurrentZoomStep2", zsCls);
                mZs.invoke(disp, zsInst);
                JSONObject zs = new JSONObject();
                putSafe(zs, "digitalStep", getFieldVal(zsInst, "zoomDigtalstep"));
                putSafe(zs, "opticalStep", getFieldVal(zsInst, "zoomOpticalstep"));
                putSafe(dObj, "zoomStepDetail", zs);
            } catch (Throwable ignore) {}

            putSafe(root, "display", dObj);
        }
        long t2 = System.currentTimeMillis();
        putSafe(timings, "displayMs", t2 - t1);

        // 3. GmAudioManagerService
        Object aud = getGmpfService("GmAudioManagerService");
        if (aud != null) {
            JSONObject aObj = new JSONObject();
            putSafe(aObj, "audioOutput", callNoArg(aud, "getAudioOutput"));
            putSafe(aObj, "arcType", callNoArg(aud, "getARCType"));
            putSafe(aObj, "spdifType", callNoArg(aud, "getSpdifType"));
            putSafe(aObj, "soundEffect", callNoArg(aud, "getSoundeffect"));
            putSafe(aObj, "soundProcessType", callNoArg(aud, "getSoundProcessType"));
            putSafe(aObj, "audioDelay", callNoArg(aud, "getAudioDelay"));
            putSafe(aObj, "videoDelay", callNoArg(aud, "getVideoDelay"));
            putSafe(aObj, "dolbySurroundEnable", callNoArg(aud, "getDolbySurroundEnable"));
            putSafe(aObj, "dolbySurroundVirtual", callNoArg(aud, "getDolbySurroundVirtual"));
            putSafe(aObj, "dolbyDialogEnhancer", callNoArg(aud, "getDolbyDialogEnhancer"));
            putSafe(aObj, "dolbyVolumeLeveler", callNoArg(aud, "getDolbyVolumeLeveler"));
            putSafe(aObj, "dolbyAc4De", callNoArg(aud, "getDolbyAc4De"));
            putSafe(aObj, "dtsEffectsEnable", callNoArg(aud, "getDtsEffectsEnable"));
            putSafe(aObj, "dtsTruBassEnable", callNoArg(aud, "getDtsTruBassEnable"));
            putSafe(aObj, "dtsTruSurroundEnable", callNoArg(aud, "getDtsTruSurroundEnable"));
            putSafe(aObj, "dtsTruVolumeEnable", callNoArg(aud, "getDtsTruVolumeEnable"));
            putSafe(aObj, "dtsDialogClarityEnable", callNoArg(aud, "getDtsDialogClarityEnable"));
            putSafe(aObj, "dtsDrc", callNoArg(aud, "getDtsDrc"));
            putSafe(aObj, "gamingSoundEffectMode", callNoArg(aud, "getGamingSoundEffectMode"));
            putSafe(aObj, "syncImageSoundEffects", callNoArg(aud, "getSyncImageSoundEffects"));
            putSafe(aObj, "volumeBalanceEnable", callNoArg(aud, "getVolumeBalanceEnable"));
            putSafe(aObj, "ampVersion", callNoArg(aud, "getAmpVersion"));
            putSafe(root, "audio", aObj);
        }
        long t3 = System.currentTimeMillis();
        putSafe(timings, "audioMs", t3 - t2);

        // 4. TemperatureManagerService
        Object temp = getGmpfService("TemperatureManagerService");
        if (temp != null) {
            JSONObject tObj = new JSONObject();
            putSafe(tObj, "systemTemperature", callNoArg(temp, "getSystemTemperature"));
            putSafe(tObj, "isTempUltraHigh", callNoArg(temp, "isTempUltraHigh"));
            putSafe(tObj, "humidity", callByteArg(temp, "GetHumidityFromSensor", 0));
            putSafe(tObj, "fanMode", callNoArg(temp, "getFanMode"));
            putSafe(tObj, "fanStandMode", callNoArg(temp, "getFanStandMode"));
            putSafe(tObj, "pumpRpm", callByteArg(temp, "getPumpRotationRate", 0));
            putSafe(tObj, "tecSpeed", callByteArg(temp, "getTECSpeed", 0));

            // Probe all 12 standard XGIMI factory sensor indices (0..11)
            JSONArray sensors = new JSONArray();
            for (int i = 0; i < SENSOR_NAMES.length; i++) {
                Object valI = callByteArg(temp, "getTemperatureFromSensor", i);
                if (valI instanceof Number) {
                    int tempVal = ((Number) valI).intValue();
                    if (tempVal > 0 && tempVal < 150) {
                        JSONObject s = new JSONObject();
                        putSafe(s, "index", i);
                        putSafe(s, "name", SENSOR_NAMES[i]);
                        putSafe(s, "tempC", tempVal);
                        sensors.put(s);
                    }
                }
            }
            putSafe(tObj, "sensorCount", sensors.length());
            putSafe(tObj, "sensors", sensors);

            // Probe fan indices 0..3 directly
            JSONArray fans = new JSONArray();
            for (int i = 0; i < 4; i++) {
                Object rpmObj = callByteArg(temp, "getFanRotationRate", i);
                if (rpmObj instanceof Number) {
                    int rpm = ((Number) rpmObj).intValue();
                    if (rpm > 0 && rpm < 30000) {
                        JSONObject f = new JSONObject();
                        putSafe(f, "index", i);
                        putSafe(f, "rpm", rpm);
                        fans.put(f);
                    }
                }
            }
            putSafe(tObj, "fanCount", fans.length());
            putSafe(tObj, "fans", fans);
            putSafe(root, "thermal", tObj);
        }
        long t4 = System.currentTimeMillis();
        putSafe(timings, "thermalMs", t4 - t3);

        // 5. ProjectorFocusManagerService
        Object foc = getGmpfService("ProjectorFocusManagerService");
        if (foc != null) {
            JSONObject fObj = new JSONObject();
            putSafe(fObj, "focusMotorCurrentStep", callNoArg(foc, "getFocusMotorCurrentStep"));
            putSafe(fObj, "focusPps", callNoArg(foc, "getFocusPPS"));
            putSafe(fObj, "zoomPps", callNoArg(foc, "getZoomPPS"));
            long nowFoc = System.currentTimeMillis();
            if (cachedTof == null || (nowFoc - cachedTofTs) > 5000) {
                cachedTof = callNoArg(foc, "getTofDistance");
                cachedAmbient = callNoArg(foc, "getAmbientLight");
                cachedTofTs = nowFoc;
            }
            putSafe(fObj, "tofDistance", cachedTof);
            putSafe(fObj, "ambientLight", cachedAmbient);
            putSafe(fObj, "powerOnAfEnabled", callNoArg(foc, "isPowerOnAFEnabled"));
            putSafe(fObj, "focusAutoCompensate", callNoArg(foc, "isFocusAutoCompensateEnable"));
            putSafe(fObj, "autoScreenAlign", callNoArg(foc, "getProjCurtainAdp"));
            putSafe(fObj, "obstacleAvoidance", callNoArg(foc, "getProjScreenAdp"));
            putSafe(fObj, "turboLightOn", callNoArg(foc, "getTurboLightOnOff"));
            putSafe(fObj, "autoColorAdapt", callNoArg(foc, "getPictureAdjustEnableAutoColor"));
            putSafe(fObj, "shiftProfessionalMode", callNoArg(foc, "getShiftProfessionalMode"));
            putSafe(fObj, "tiltShiftCurPositionInfo", callNoArg(foc, "getTiltShiftCurPositionInfo"));
            putSafe(fObj, "curNimbleOpticalStatus", callNoArg(foc, "getCurNimbleOpticalStatus"));
            putSafe(fObj, "lensType", callNoArg(foc, "getLensType"));
            putSafe(fObj, "cameraNum", callNoArg(foc, "getCameraNum"));
            putSafe(root, "focus", fObj);
        }
        long t5 = System.currentTimeMillis();
        putSafe(timings, "focusMs", t5 - t4);

        // 6. MotionDetectionManagerService
        Object mot = getGmpfService("MotionDetectionManagerService");
        if (mot != null) {
            JSONObject mObj = new JSONObject();
            putSafe(mObj, "accTriggerAutoKeystone", callNoArg(mot, "getAccTriggerAK"));
            putSafe(mObj, "angTriggerAutoFocus", callNoArg(mot, "getAngTriggerAF"));
            putSafe(mObj, "autoReverse", callNoArg(mot, "getAutoReverse"));
            try {
                Class<?> attCls = Class.forName("xgimi.hardware.gmpf.IMotionType.stAttitudeInfo");
                Object attInst = attCls.getDeclaredConstructor().newInstance();
                Method mAtt = mot.getClass().getMethod("getMotionAttitude", attCls);
                Object ok = mAtt.invoke(mot, attInst);
                if (Boolean.TRUE.equals(ok)) {
                    putSafe(mObj, "pitch", getFieldVal(attInst, "pitch"));
                    putSafe(mObj, "roll", getFieldVal(attInst, "roll"));
                }
            } catch (Throwable ignore) {}
            putSafe(root, "motion", mObj);
        }
        long t6 = System.currentTimeMillis();
        putSafe(timings, "motionMs", t6 - t5);

        // 7. SystemManagerService
        Object sys = getGmpfService("SystemManagerService");
        if (sys != null) {
            JSONObject sObj = new JSONObject();
            putSafe(sObj, "deviceName", callNoArg(sys, "getDeviceName"));
            putSafe(sObj, "deviceShowName", callNoArg(sys, "getDeviceShowName"));
            putSafe(sObj, "modelName", callNoArg(sys, "getModelName"));
            putSafe(sObj, "deviceMemorySize", callNoArg(sys, "getDeviceMemorySize"));
            putSafe(sObj, "deviceBuildInSize", callNoArg(sys, "getDeviceBuildInSize"));
            putSafe(sObj, "deviceSystemPartitionSize", callNoArg(sys, "getDeviceSystemPartitionSize"));
            putSafe(sObj, "systemVersionInfo", callNoArg(sys, "getSystemVersionInfo"));
            putSafe(sObj, "machineUsageTime", callIntArg(sys, "getMachineUsageTime", 0));
            putSafe(sObj, "ecoState", callNoArg(sys, "getEcoState"));
            putSafe(sObj, "highAltitudeMode", callNoArg(sys, "getHighAltitudeMode"));
            putSafe(sObj, "silentMode", callNoArg(sys, "getSilentMode"));
            putSafe(sObj, "hdmiAutoSwitch", callNoArg(sys, "getHdmiAutoSwitch"));
            putSafe(sObj, "standVerticalAngle", callNoArg(sys, "getStandVerticalAngle"));
            putSafe(sObj, "wakeupMode2", callNoArg(sys, "getWakeupMode2"));
            putSafe(sObj, "powerOnMusicEnabled", callNoArg(sys, "isPowerOnMusicEnabled"));
            putSafe(sObj, "shiftCurrentStep", callNoArg(sys, "getShiftCurrentStep"));
            putSafe(sObj, "shiftMaxStep", callNoArg(sys, "getShiftMaxStep"));
            putSafe(sObj, "radarDetectOn", callNoArg(sys, "getRadarDetectOnOff"));
            try {
                Class<?> ptCls = Class.forName("xgimi.hardware.gmpf.IDisplayType.PantiltAngInfo_st");
                Object ptInst = ptCls.getDeclaredConstructor().newInstance();
                Method mPt = sys.getClass().getMethod("getPantiltAngle", ptCls);
                Object ok = mPt.invoke(sys, ptInst);
                if (Boolean.TRUE.equals(ok)) {
                    putSafe(sObj, "pantiltHorizontalAng", getFieldVal(ptInst, "horizontalAng"));
                    putSafe(sObj, "pantiltVerticalAng", getFieldVal(ptInst, "verticalAng"));
                    putSafe(sObj, "pantiltRollingAng", getFieldVal(ptInst, "rollingAng"));
                }
            } catch (Throwable ignore) {}
            putSafe(root, "system", sObj);
        }
        long t7 = System.currentTimeMillis();
        putSafe(timings, "systemMs", t7 - t6);

        // 8. PowerManagerService & ConfigManagerService
        Object pwr = getGmpfService("PowerManagerService");
        if (pwr != null) {
            JSONObject pObj = new JSONObject();
            putSafe(pObj, "powerMode", callNoArg(pwr, "getPowerMode"));
            putSafe(pObj, "standbyStage", callNoArg(pwr, "getStandbyStage"));
            putSafe(pObj, "powerSupplyVoltage", callNoArg(pwr, "getPowerSupplyVoltage"));
            putSafe(pObj, "bootReason", callNoArg(pwr, "getBootReason"));
            putSafe(pObj, "isAdapterPowered", callNoArg(pwr, "isAdapterPowered"));
            putSafe(pObj, "autoEcoMode", callNoArg(pwr, "getAutoEcoMode"));
            putSafe(root, "power", pObj);
        }

        Object cfg = getGmpfService("ConfigManagerService");
        if (cfg != null) {
            JSONObject cObj = new JSONObject();
            putSafe(cObj, "gmuiVersion", callNoArg(cfg, "getGmuiVersion"));
            putSafe(cObj, "productName", callNoArg(cfg, "getProductName"));
            putSafe(cObj, "updateOtaName", callNoArg(cfg, "getUpdateOtaName"));
            putSafe(cObj, "xgimiApiVersion", callNoArg(cfg, "getXgimiApiVersion"));
            putSafe(cObj, "socType", callNoArg(cfg, "getSocType"));
            putSafe(root, "config", cObj);
        }
        long t8 = System.currentTimeMillis();
        putSafe(timings, "powerCfgMs", t8 - t7);

        // 9. HDMI CEC topology from dumpsys hdmi_control (cached for 4s)
        long now = System.currentTimeMillis();
        if (cachedCec == null || (now - cachedCecTs) > 4000) {
            cachedCec = parseCecDumpsys();
            cachedCecTs = now;
        }
        putSafe(root, "cecDumpsys", cachedCec);
        long t9 = System.currentTimeMillis();
        putSafe(timings, "cecMs", t9 - t8);

        // 10. MediaTek Hardware Picture Quality (IPq HAL) for internal MediaCodec / Android TV plane
        long t10 = System.currentTimeMillis();
        JSONObject mtkPq = collectMtkPqInfo();
        if (mtkPq != null && mtkPq.length() > 0) {
            putSafe(root, "mtkPq", mtkPq);
        }
        long t11 = System.currentTimeMillis();
        putSafe(timings, "mtkPqMs", t11 - t10);

        putSafe(root, "_timings", timings);

        return root;
    }

    private static JSONObject collectMtkPqInfo() {
        JSONObject res = new JSONObject();
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "vendor.mediatek.hardware.pq.IPq/default");
            if (binder == null || !binder.isBinderAlive()) return res;

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
            } catch (Throwable ignore) {
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
            } catch (Throwable ignore) {
            } finally {
                data59.recycle();
                reply59.recycle();
            }
        } catch (Throwable ignore) {}
        return res;
    }

    private static JSONObject parseCecDumpsys() {
        JSONObject cec = new JSONObject();
        JSONArray devices = new JSONArray();
        JSONArray ports = new JSONArray();
        Set<String> seenAddrs = new LinkedHashSet<>();

        Pattern devPat = Pattern.compile(
                "CEC:\\s+logical_address:\\s*(0x[0-9a-fA-F]+)\\s+device_type:\\s*(\\d+)\\s+cec_version:\\s*(\\d+)\\s+vendor_id:\\s*(\\d+)\\s+display_name:\\s*(.+?)\\s+power_status:\\s*(-?\\d+)\\s+physical_address:\\s*(0x[0-9a-fA-F]+)\\s+port_id:\\s*(-?\\d+)");
        Pattern portPat = Pattern.compile(
                "port_id:\\s*(\\d+),\\s*type:\\s*([A-Z0-9_]+),\\s*address:\\s*(0x[0-9a-fA-F]+),\\s*cec:\\s*(true|false),\\s*arc:\\s*(true|false),\\s*mhl:\\s*(true|false),\\s*earc:\\s*(true|false)");
        Pattern earcPat = Pattern.compile("eARC local device:\\s*([A-Z0-9_]+),\\s*mEarcStatus:\\s*(\\d+)");

        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"dumpsys", "hdmi_control"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                Matcher em = earcPat.matcher(line);
                if (em.find()) {
                    putSafe(cec, "earcLocalRole", em.group(1));
                    putSafe(cec, "earcDumpsysStatus", Integer.parseInt(em.group(2)));
                }
                Matcher pm = portPat.matcher(line);
                if (pm.find()) {
                    JSONObject port = new JSONObject();
                    putSafe(port, "portId", Integer.parseInt(pm.group(1)));
                    putSafe(port, "type", pm.group(2));
                    putSafe(port, "address", pm.group(3));
                    putSafe(port, "cec", Boolean.parseBoolean(pm.group(4)));
                    putSafe(port, "arc", Boolean.parseBoolean(pm.group(5)));
                    putSafe(port, "earc", Boolean.parseBoolean(pm.group(7)));
                    ports.put(port);
                }
                Matcher dm = devPat.matcher(line);
                if (dm.find()) {
                    String logAddr = dm.group(1);
                    String physAddr = dm.group(7);
                    String key = logAddr + "@" + physAddr;
                    if (!seenAddrs.contains(key)) {
                        seenAddrs.add(key);
                        JSONObject d = new JSONObject();
                        putSafe(d, "logicalAddress", logAddr);
                        putSafe(d, "deviceType", Integer.parseInt(dm.group(2)));
                        putSafe(d, "cecVersion", Integer.parseInt(dm.group(3)));
                        putSafe(d, "vendorId", Integer.parseInt(dm.group(4)));
                        putSafe(d, "displayName", dm.group(5).trim());
                        putSafe(d, "powerStatus", Integer.parseInt(dm.group(6)));
                        putSafe(d, "physicalAddress", physAddr);
                        putSafe(d, "portId", Integer.parseInt(dm.group(8)));
                        devices.put(d);
                    }
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignore) {}
        }
        putSafe(cec, "ports", ports);
        putSafe(cec, "devices", devices);
        return cec;
    }
}
