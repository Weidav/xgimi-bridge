package com.xgimi.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AidlClient {
    public static final int TX_GET_COLOR_TEMP = 0;
    public static final int TX_GET_GAME_MODE_OPTION = 1;
    public static final int TX_GET_PICTURE_MODE = 2;
    public static final int TX_GET_WB_BLUE_GAIN = 3;
    public static final int TX_GET_WB_GREEN_GAIN = 4;
    public static final int TX_GET_WB_RED_GAIN = 5;
    public static final int TX_SET_COLOR_TEMP = 6;
    public static final int TX_SET_GAME_MODE_OPTION = 7;
    public static final int TX_SET_PICTURE_ITEM = 8;
    public static final int TX_SET_PICTURE_MODE = 9;
    public static final int TX_SET_WB_BLUE_GAIN = 10;
    public static final int TX_SET_WB_GREEN_GAIN = 11;
    public static final int TX_SET_WB_RED_GAIN = 12;

    public int lastValue;
    public int reboots;
    public boolean setterReturnValue = true;
    public int getIntReturnValue = 0;
    public int writeCallCount = 0;
    public final List<String> calls = new ArrayList<>();
    public final Map<String, Object> mockSnapshot = new HashMap<>();

    public AidlClient() {}
    public AidlClient(android.content.Context ctx) {}

    public void connect() {}
    public void disconnect() {}

    public boolean isReady() { return true; }

    public Map<String, Object> snapshot() {
        if (!mockSnapshot.isEmpty()) return new HashMap<>(mockSnapshot);
        Map<String, Object> snap = new HashMap<>();
        snap.put("brightness", 50);
        snap.put("contrast", 50);
        snap.put("saturation", 50);
        snap.put("sharpness", 50);
        snap.put("hue", 50);
        snap.put("wbRed", 50);
        snap.put("wbGreen", 50);
        snap.put("wbBlue", 50);
        snap.put("colorTemp", 2);
        snap.put("pictureMode", 1);
        snap.put("gameMode", 0);
        return snap;
    }

    public boolean setInt(int code, int value) throws android.os.RemoteException {
        writeCallCount++;
        lastValue = value;
        calls.add("setInt:" + code + "=" + value);
        return setterReturnValue;
    }

    public boolean setIntInt(int code, int item, int value) throws android.os.RemoteException {
        writeCallCount++;
        lastValue = value;
        calls.add("setIntInt:" + code + ":" + item + "=" + value);
        return setterReturnValue;
    }

    public boolean setWbRedGain(int val) throws android.os.RemoteException { return setInt(TX_SET_WB_RED_GAIN, val); }
    public boolean setWbGreenGain(int val) throws android.os.RemoteException { return setInt(TX_SET_WB_GREEN_GAIN, val); }
    public boolean setWbBlueGain(int val) throws android.os.RemoteException { return setInt(TX_SET_WB_BLUE_GAIN, val); }
    public boolean setWhiteBalance(int r, int g, int b) throws android.os.RemoteException {
        return setWbRedGain(r) && setWbGreenGain(g) && setWbBlueGain(b);
    }
    public boolean setColorTemp(int val) throws android.os.RemoteException { return setInt(TX_SET_COLOR_TEMP, val); }
    public boolean setPictureMode(int val) throws android.os.RemoteException { return setInt(TX_SET_PICTURE_MODE, val); }
    public boolean setGameModeOption(int val) throws android.os.RemoteException { return setInt(TX_SET_GAME_MODE_OPTION, val); }
    public boolean setPictureItem(int item, int val) throws android.os.RemoteException { return setIntInt(TX_SET_PICTURE_ITEM, item, val); }

    public int getInt(int code) throws android.os.RemoteException {
        return getIntReturnValue != 0 ? getIntReturnValue : lastValue;
    }

    public void resetPictureMode(int m, boolean f) throws android.os.RemoteException {
        writeCallCount++;
        calls.add("resetPictureMode:" + m + ":" + f);
    }

    public void setLedOn(boolean b) {
        writeCallCount++;
        calls.add("setLedOn:" + b);
    }

    public void setSystemProperty(String p, String v) {
        writeCallCount++;
        reboots++;
        calls.add("setSystemProperty:" + p + "=" + v);
    }
}