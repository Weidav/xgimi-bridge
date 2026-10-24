package com.xgimi.bridge;

import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serialized mutation coordinator for hardware writes, preset application, reset and power actions.
 */
public class DeviceCommands {
    private static final String TAG = "XgimiBridge";

    private final AidlClient aidl;
    private final LocalAdbClient adb;
    private final ReentrantLock deviceLock = new ReentrantLock();

    public static class CommandResult {
        public final int httpStatus;
        public final JSONObject body;

        public CommandResult(int httpStatus, JSONObject body) {
            this.httpStatus = httpStatus;
            this.body = body;
        }
    }

    public DeviceCommands(AidlClient aidl, LocalAdbClient adb) {
        this.aidl = aidl;
        this.adb = adb;
    }

    private boolean tryAcquireLock() {
        try {
            return deviceLock.tryLock(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void releaseLock() {
        if (deviceLock.isHeldByCurrentThread()) {
            deviceLock.unlock();
        }
    }

    public CommandResult setPictureSetting(String knob, int value) {
        if (!tryAcquireLock()) {
            return busyResult();
        }
        try {
            String canonical = SettingRules.canonicalField(knob);
            if (canonical == null) {
                return errorResult(404, "not_found", "Unknown knob: " + knob);
            }

            boolean ok = applyAidlSetting(aidl, canonical, value);

            if (!ok) {
                return errorResult(502, "device_write_failed", "Hardware setter failed for " + knob);
            }

            JSONObject res = new JSONObject();
            res.put("ok", true);
            res.put("knob", knob);
            res.put("value", value);
            return new CommandResult(200, res);
        } catch (RemoteException re) {
            Log.e(TAG, "AIDL remote error", re);
            return errorResult(502, "device_write_failed", "Remote exception: " + re.getMessage());
        } catch (Throwable t) {
            Log.e(TAG, "Hardware write error", t);
            return errorResult(500, "internal_error", t.getMessage());
        } finally {
            releaseLock();
        }
    }

    public CommandResult resetPicture(int mode, boolean force) {
        if (!tryAcquireLock()) {
            return busyResult();
        }
        try {
            boolean ok1 = aidl.setWbRedGain(50);
            Thread.sleep(60);
            boolean ok2 = aidl.setWbGreenGain(50);
            Thread.sleep(60);
            boolean ok3 = aidl.setWbBlueGain(50);
            Thread.sleep(60);
            aidl.resetPictureMode(mode, force);

            if (!ok1 || !ok2 || !ok3) {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("code", "device_write_failed");
                err.put("error", "Hardware setter failed during reset");
                err.put("action", "reset");
                err.put("mode", mode);
                return new CommandResult(502, err);
            }

            JSONObject res = new JSONObject();
            res.put("ok", true);
            res.put("action", "reset");
            res.put("mode", mode);
            return new CommandResult(200, res);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return errorResult(500, "internal_error", "Operation interrupted");
        } catch (RemoteException re) {
            return errorResult(502, "device_write_failed", "Remote exception: " + re.getMessage());
        } catch (Throwable t) {
            return errorResult(500, "internal_error", t.getMessage());
        } finally {
            releaseLock();
        }
    }

    public CommandResult applyPresetValues(String presetId, Map<String, Integer> validatedValues) {
        if (!tryAcquireLock()) {
            return busyResult();
        }
        try {
            List<String> applied = new ArrayList<>();
            // Order: mode-before-white-balance
            String[] order = new String[]{
                    "pictureMode", "colorTemp", "wbRed", "wbGreen", "wbBlue",
                    "gameMode", "brightness", "contrast", "saturation", "sharpness", "hue"
            };

            for (String field : order) {
                if (!validatedValues.containsKey(field)) continue;
                int target = validatedValues.get(field);
                boolean ok = applyAidlSetting(aidl, field, target);
                sleepQuietly(field.equals("brightness") || field.equals("contrast") ||
                             field.equals("saturation") || field.equals("sharpness") ||
                             field.equals("hue") ? 150 : 60);

                if (!ok) {
                    JSONObject err = new JSONObject();
                    err.put("ok", false);
                    err.put("code", "device_write_failed");
                    err.put("error", "Hardware setter failed for field " + field);
                    err.put("failed", field);
                    err.put("applied", new JSONArray(applied));
                    return new CommandResult(502, err);
                }
                applied.add(field);
            }

            Map<String, Object> snap = aidl.snapshot();
            JSONObject reply = new JSONObject();
            reply.put("ok", true);
            reply.put("id", presetId);
            reply.put("applied", new JSONArray(applied));
            reply.put("after", new JSONObject(snap));
            return new CommandResult(200, reply);
        } catch (Throwable t) {
            Log.e(TAG, "applyPresetValues error", t);
            return errorResult(500, "internal_error", t.getMessage());
        } finally {
            releaseLock();
        }
    }

    public CommandResult handlePowerAction(String action, boolean currentInteractive) {
        if (!tryAcquireLock()) {
            return busyResult();
        }
        try {
            switch (action) {
                case "on": {
                    if (!currentInteractive) {
                        adb.injectKey(26);
                    }
                    JSONObject res = new JSONObject();
                    res.put("ok", true);
                    res.put("accepted", true);
                    res.put("pending", true);
                    res.put("action", "on");
                    return new CommandResult(202, res);
                }
                case "off": {
                    if (currentInteractive) {
                        adb.injectKey(26);
                    }
                    JSONObject res = new JSONObject();
                    res.put("ok", true);
                    res.put("accepted", true);
                    res.put("pending", true);
                    res.put("action", "off");
                    return new CommandResult(202, res);
                }
                case "toggle":
                case "standby": {
                    adb.injectKey(26);
                    JSONObject res = new JSONObject();
                    res.put("ok", true);
                    res.put("accepted", true);
                    res.put("pending", true);
                    res.put("action", action);
                    return new CommandResult(202, res);
                }
                case "led-off": {
                    aidl.setLedOn(false);
                    JSONObject res = new JSONObject();
                    res.put("ok", true);
                    res.put("action", "led-off");
                    return new CommandResult(200, res);
                }
                case "led-on": {
                    aidl.setLedOn(true);
                    JSONObject res = new JSONObject();
                    res.put("ok", true);
                    res.put("action", "led-on");
                    return new CommandResult(200, res);
                }
                case "restart": {
                    aidl.setSystemProperty("sys.powerctl", "reboot");
                    JSONObject res = new JSONObject();
                    res.put("ok", true);
                    res.put("action", "restart");
                    return new CommandResult(200, res);
                }
                default:
                    return errorResult(404, "not_found", "unknown power action: " + action);
            }
        } catch (Throwable t) {
            Log.e(TAG, "handlePowerAction error", t);
            return errorResult(500, "internal_error", t.getMessage());
        } finally {
            releaseLock();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static CommandResult busyResult() {
        return errorResult(503, "busy", "Mutation lock busy (another operation is in progress)");
    }

    public static CommandResult errorResult(int status, String code, String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", false);
            obj.put("code", code);
            obj.put("error", message != null ? message : "");
        } catch (JSONException ignored) {}
        return new CommandResult(status, obj);
    }

    public static boolean applyAidlSetting(AidlClient aidl, String field, int value) throws RemoteException {
        switch (field) {
            case "wbRed":
                return aidl.setWbRedGain(value);
            case "wbGreen":
                return aidl.setWbGreenGain(value);
            case "wbBlue":
                return aidl.setWbBlueGain(value);
            case "colorTemp":
                return aidl.setColorTemp(value);
            case "pictureMode":
                return aidl.setPictureMode(value);
            case "gameMode":
                return aidl.setGameModeOption(value);
            case "brightness":
                return aidl.setPictureItem(0, value);
            case "contrast":
                return aidl.setPictureItem(1, value);
            case "saturation":
                return aidl.setPictureItem(2, value);
            case "sharpness":
                return aidl.setPictureItem(3, value);
            case "hue":
                return aidl.setPictureItem(4, value);
            default:
                return false;
        }
    }
}
