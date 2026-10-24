package com.xgimi.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Raw-Binder client for com.xgimi.xgimiservice. Verified working against IGimiVideo
 * on this projector firmware. No AIDL generation needed.
 */
public class AidlClient {
    private static final String TAG = "XgimiBridge";
    private static final String DESC_SVC = "com.xgimi.aidl.IXgimiService";
    private static final String DESC_VIDEO = "com.xgimi.aidl.IGimiVideo";
    private static final String DESC_AUDIO = "com.xgimi.aidl.IGimiAudio";
    private static final String DESC_COMMON = "com.xgimi.aidl.IGimiCommon";
    private static final String DESC_PROJECTOR = "com.xgimi.aidl.IGimiProjector";
    private static final String BIND_ACTION = "com.xgimi.xgimiservice.services.XgimiServices";
    private static final String BIND_PKG = "com.xgimi.xgimiservice";

    // IXgimiService broker transactions
    static final int TX_GET_GIMI_COMMON = 0x1;
    static final int TX_GET_GIMI_PROJECTOR = 0x4;
    static final int TX_GET_GIMI_VIDEO = 0x8;
    static final int TX_GET_GIMI_AUDIO = 0x9;

    // IGimiCommon transactions
    static final int TX_COMMON_GET_CURRENT_CHIP = 0x6;
    static final int TX_COMMON_GET_STR = 0x8;
    static final int TX_COMMON_GET_CURRENT_PRODUCT = 0x12;
    static final int TX_COMMON_GET_CURRENT_GMUI_VERSION = 0x13;
    static final int TX_COMMON_GET_CURRENT_PRODUCT_VERSION = 0x14;
    static final int TX_COMMON_GET_CURRENT_XGIMI_API_VERSION = 0x15;
    static final int TX_COMMON_SET_SYSTEM_PROPERTIES = 0x16;
    static final int TX_COMMON_GET_SYSTEM_PROPERTIES = 0x17;
    static final int TX_COMMON_READ_SYS_FS = 0x1d;
    static final int TX_COMMON_IS_CEC_ENABLED = 0x23;
    static final int TX_COMMON_IS_CEC_AUTO_WAKEUP = 0x25;
    static final int TX_COMMON_IS_CEC_AUTO_DEVICE_OFF = 0x27;

    // IGimiProjector transactions
    static final int TX_PROJECTOR_IS_LED_ON = 0x1;
    static final int TX_PROJECTOR_SET_LED_ON = 0x2;

    // IGimiVideo
    static final int TX_GET_PICTURE_MODE = 0x1;
    static final int TX_SET_PICTURE_MODE = 0x2;
    static final int TX_GET_COLOR_TEMP = 0x3;
    static final int TX_SET_COLOR_TEMP = 0x4;
    static final int TX_SET_WB_RED_GAIN = 0x5;
    static final int TX_SET_WB_GREEN_GAIN = 0x6;
    static final int TX_SET_WB_BLUE_GAIN = 0x7;
    static final int TX_GET_WB_RED_GAIN = 0x8;
    static final int TX_GET_WB_GREEN_GAIN = 0x9;
    static final int TX_GET_WB_BLUE_GAIN = 0xa;
    static final int TX_GET_VIDEO_ARC_TYPE = 0xc;
    static final int TX_GET_PICTURE_ITEM = 0xd;
    static final int TX_SET_PICTURE_ITEM = 0xe;
    static final int TX_GET_NOISE_REDUCTION = 0xf;
    static final int TX_GET_MFC_LEVEL = 0x12;
    static final int TX_RESET_PICTURE_MODE = 0x13;
    static final int TX_GET_EDID_VERSION = 0x14;
    static final int TX_GET_ASPECT_RATIO = 0x16;
    static final int TX_GET_CURRENT_3D_TYPE = 0x19;
    static final int TX_GET_3D_DISPLAY_FORMAT = 0x1a;
    static final int TX_GET_3D_TO_2D_MODE = 0x1c;
    static final int TX_IS_3D_TO_2D = 0x1d;
    static final int TX_GET_CURRENT_INPUT_SOURCE = 0x1e;
    static final int TX_IS_VIDEO_PLAYING = 0x21;
    static final int TX_GET_HDR_ENABLE = 0x23;
    static final int TX_SET_GAME_MODE_OPTION = 0x24;
    static final int TX_GET_GAME_MODE_OPTION = 0x25;
    static final int TX_SET_GAME_MODE_OPTION_FOR_SOURCE = 0x26;
    static final int TX_GET_GAME_MODE_OPTION_FOR_SOURCE = 0x27;

    // IGimiAudio
    static final int TX_AUDIO_GET_OUT_DEVICE_DELAY = 0x5;
    static final int TX_AUDIO_GET_DATA_FORMAT = 0x8;
    static final int TX_AUDIO_GET_SOUND_MODE = 0xc;
    static final int TX_AUDIO_GET_VOLUME = 0xe;
    static final int TX_AUDIO_GET_OUTPUT_DEVICES = 0x2b;

    private final Context ctx;
    private final AtomicReference<IBinder> svcBinder = new AtomicReference<>();
    private final AtomicReference<IBinder> videoBinder = new AtomicReference<>();
    private final AtomicReference<IBinder> audioBinder = new AtomicReference<>();
    private final AtomicReference<IBinder> commonBinder = new AtomicReference<>();
    private final AtomicReference<IBinder> projectorBinder = new AtomicReference<>();
    private volatile boolean connected = false;

    public AidlClient(Context ctx) { this.ctx = ctx; }

    public void connect() {
        Intent i = new Intent(BIND_ACTION).setPackage(BIND_PKG);
        boolean ok = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "AidlClient.bindService -> " + ok);
    }

    public void disconnect() {
        try {
            ctx.unbindService(conn);
        } catch (Throwable ignored) {}
        connected = false;
        svcBinder.set(null);
        videoBinder.set(null);
        audioBinder.set(null);
        commonBinder.set(null);
        projectorBinder.set(null);
    }

    public boolean isReady() { return connected && videoBinder.get() != null; }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "AidlClient connected: " + name);
            svcBinder.set(binder);
            try {
                IBinder video = getSubBinder(binder, TX_GET_GIMI_VIDEO);
                videoBinder.set(video);
                connected = true;
                Log.i(TAG, "AidlClient: video binder = " + video);
            } catch (Throwable t) {
                Log.e(TAG, "could not get video binder", t);
            }
            try {
                IBinder audio = getSubBinder(binder, TX_GET_GIMI_AUDIO);
                audioBinder.set(audio);
                Log.i(TAG, "AidlClient: audio binder = " + audio);
            } catch (Throwable t) {
                Log.w(TAG, "could not get audio binder", t);
            }
            try {
                IBinder common = getSubBinder(binder, TX_GET_GIMI_COMMON);
                commonBinder.set(common);
                Log.i(TAG, "AidlClient: common binder = " + common);
            } catch (Throwable t) {
                Log.w(TAG, "could not get common binder", t);
            }
            try {
                IBinder projector = getSubBinder(binder, TX_GET_GIMI_PROJECTOR);
                projectorBinder.set(projector);
                Log.i(TAG, "AidlClient: projector binder = " + projector);
            } catch (Throwable t) {
                Log.w(TAG, "could not get projector binder", t);
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "AidlClient disconnected: " + name);
            connected = false;
            svcBinder.set(null);
            videoBinder.set(null);
            audioBinder.set(null);
            commonBinder.set(null);
            projectorBinder.set(null);
        }
    };

    private IBinder getSubBinder(IBinder svc, int code) throws RemoteException {
        return BinderTransact.getSubBinder(svc, DESC_SVC, code);
    }

    /** Turn the projector lamp on/off via IGimiProjector.setLedOn(boolean) — Android keeps running. */
    public void setLedOn(final boolean on) throws RemoteException {
        BinderTransact.transactVoid(projectorBinder.get(), DESC_PROJECTOR, TX_PROJECTOR_SET_LED_ON, new BinderTransact.Writer() {
            @Override
            public void write(Parcel p) {
                p.writeInt(on ? 1 : 0);
            }
        });
    }

    public Boolean isLedOn() {
        return BinderTransact.queryBool(projectorBinder.get(), DESC_PROJECTOR, TX_PROJECTOR_IS_LED_ON);
    }

    /** Set a system property via the system-uid xgimiservice — used for sys.powerctl etc. */
    public void setSystemProperty(final String key, final String value) throws RemoteException {
        BinderTransact.transactVoid(commonBinder.get(), DESC_COMMON, TX_COMMON_SET_SYSTEM_PROPERTIES, new BinderTransact.Writer() {
            @Override
            public void write(Parcel p) {
                p.writeString(key);
                p.writeString(value);
            }
        });
    }

    public String getSystemPropertyViaAidl(final String key) {
        return BinderTransact.queryString(commonBinder.get(), DESC_COMMON, TX_COMMON_GET_SYSTEM_PROPERTIES, new BinderTransact.Writer() {
            @Override
            public void write(Parcel p) {
                p.writeString(key);
            }
        });
    }

    public String readSysFsViaAidl(final String path) {
        return BinderTransact.queryString(commonBinder.get(), DESC_COMMON, TX_COMMON_READ_SYS_FS, new BinderTransact.Writer() {
            @Override
            public void write(Parcel p) {
                p.writeString(path);
            }
        });
    }

    // ---------- video calls ----------

    public int getInt(int code) throws RemoteException {
        return BinderTransact.transactInt(videoBinder.get(), DESC_VIDEO, code, null);
    }

    public String getString(int code) throws RemoteException {
        return BinderTransact.transactString(videoBinder.get(), DESC_VIDEO, code, null);
    }

    public int getIntWithIntArg(int code, final int arg) throws RemoteException {
        return BinderTransact.transactInt(videoBinder.get(), DESC_VIDEO, code, new BinderTransact.Writer() {
            @Override
            public void write(Parcel p) {
                p.writeInt(arg);
            }
        });
    }

    public boolean setInt(int code, final int value) throws RemoteException {
        return BinderTransact.transactInt(videoBinder.get(), DESC_VIDEO, code, new BinderTransact.Writer() {
            @Override
            public void write(Parcel p) {
                p.writeInt(value);
            }
        }) != 0;
    }

    public boolean setIntInt(int code, final int a, final int b) throws RemoteException {
        return BinderTransact.transactInt(videoBinder.get(), DESC_VIDEO, code, new BinderTransact.Writer() {
            @Override
            public void write(Parcel p) {
                p.writeInt(a);
                p.writeInt(b);
            }
        }) != 0;
    }

    public void resetPictureMode(final int mode, final boolean force) throws RemoteException {
        BinderTransact.transactVoid(videoBinder.get(), DESC_VIDEO, TX_RESET_PICTURE_MODE, new BinderTransact.Writer() {
            @Override
            public void write(Parcel p) {
                p.writeInt(mode);
                p.writeInt(force ? 1 : 0);
            }
        });
    }


    /** Snapshot of all interesting picture state. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        try { m.put("pictureMode", getInt(TX_GET_PICTURE_MODE)); } catch (Throwable t) { m.put("pictureMode", null); }
        try { m.put("colorTemp", getInt(TX_GET_COLOR_TEMP)); } catch (Throwable t) { m.put("colorTemp", null); }
        try { m.put("wbRed", getInt(TX_GET_WB_RED_GAIN)); } catch (Throwable t) { m.put("wbRed", null); }
        try { m.put("wbGreen", getInt(TX_GET_WB_GREEN_GAIN)); } catch (Throwable t) { m.put("wbGreen", null); }
        try { m.put("wbBlue", getInt(TX_GET_WB_BLUE_GAIN)); } catch (Throwable t) { m.put("wbBlue", null); }
        try { m.put("gameMode", getInt(TX_GET_GAME_MODE_OPTION)); } catch (Throwable t) { m.put("gameMode", null); }

        // Per-source detail
        String src = null; int srcId = -1;
        try { src = getString(TX_GET_CURRENT_INPUT_SOURCE); } catch (Throwable t) {}
        m.put("currentInputSource", src);
        if (src != null) {
            if (src.equals("E_INPUT_SOURCE_STORAGE")) srcId = 0;
            else if (src.startsWith("E_INPUT_SOURCE_HDMI")) {
                try { srcId = Integer.parseInt(src.substring("E_INPUT_SOURCE_HDMI".length())); } catch (Throwable t) {}
            }
        }
        m.put("currentSourceId", srcId);
        try {
            if (srcId >= 0) m.put("gameModeCurrent", getIntWithIntArg(TX_GET_GAME_MODE_OPTION_FOR_SOURCE, srcId));
            else m.put("gameModeCurrent", null);
        } catch (Throwable t) { m.put("gameModeCurrent", null); }

        // Bonus: dump per-source game mode for sources 0..3 (storage + 3 HDMI)
        java.util.LinkedHashMap<String,Object> per = new java.util.LinkedHashMap<>();
        for (int i = 0; i <= 3; i++) {
            try { per.put(Integer.toString(i), getIntWithIntArg(TX_GET_GAME_MODE_OPTION_FOR_SOURCE, i)); }
            catch (Throwable t) { per.put(Integer.toString(i), null); }
        }
        m.put("gameModePerSource", per);

        // Picture items: 0=brightness, 1=contrast, 2=saturation, 3=sharpness, 4=hue
        try { m.put("brightness", getIntWithIntArg(TX_GET_PICTURE_ITEM, 0)); } catch (Throwable t) { m.put("brightness", null); }
        try { m.put("contrast",   getIntWithIntArg(TX_GET_PICTURE_ITEM, 1)); } catch (Throwable t) { m.put("contrast", null); }
        try { m.put("saturation", getIntWithIntArg(TX_GET_PICTURE_ITEM, 2)); } catch (Throwable t) { m.put("saturation", null); }
        try { m.put("sharpness",  getIntWithIntArg(TX_GET_PICTURE_ITEM, 3)); } catch (Throwable t) { m.put("sharpness", null); }
        try { m.put("hue",        getIntWithIntArg(TX_GET_PICTURE_ITEM, 4)); } catch (Throwable t) { m.put("hue", null); }
        return m;
    }

    /** Extended read-only telemetry from IGimiVideo, IGimiAudio, IGimiProjector, and IGimiCommon. */
    public Map<String, Object> collectAidlInfo() {
        Map<String, Object> m = snapshot();
        IBinder vb = videoBinder.get();
        IBinder ab = audioBinder.get();
        IBinder cb = commonBinder.get();

        // IGimiVideo extras
        m.put("videoArcType", BinderTransact.queryInt(vb, DESC_VIDEO, TX_GET_VIDEO_ARC_TYPE));
        m.put("noiseReduction", BinderTransact.queryInt(vb, DESC_VIDEO, TX_GET_NOISE_REDUCTION));
        m.put("mfcLevel", BinderTransact.queryInt(vb, DESC_VIDEO, TX_GET_MFC_LEVEL));
        m.put("edidVersion", BinderTransact.queryInt(vb, DESC_VIDEO, TX_GET_EDID_VERSION));
        m.put("aspectRatio", BinderTransact.queryInt(vb, DESC_VIDEO, TX_GET_ASPECT_RATIO));
        m.put("current3dType", BinderTransact.queryInt(vb, DESC_VIDEO, TX_GET_CURRENT_3D_TYPE));
        m.put("displayFormat3d", BinderTransact.queryInt(vb, DESC_VIDEO, TX_GET_3D_DISPLAY_FORMAT));
        m.put("mode3dTo2d", BinderTransact.queryInt(vb, DESC_VIDEO, TX_GET_3D_TO_2D_MODE));
        m.put("is3dTo2d", BinderTransact.queryBool(vb, DESC_VIDEO, TX_IS_3D_TO_2D));
        m.put("isVideoPlaying", BinderTransact.queryBool(vb, DESC_VIDEO, TX_IS_VIDEO_PLAYING));
        m.put("hdrEnable", BinderTransact.queryBool(vb, DESC_VIDEO, TX_GET_HDR_ENABLE));

        // IGimiAudio extras
        m.put("audioDataFormat", BinderTransact.queryInt(ab, DESC_AUDIO, TX_AUDIO_GET_DATA_FORMAT));
        m.put("soundMode", BinderTransact.queryInt(ab, DESC_AUDIO, TX_AUDIO_GET_SOUND_MODE));
        m.put("audioOutputDevices", BinderTransact.queryInt(ab, DESC_AUDIO, TX_AUDIO_GET_OUTPUT_DEVICES));
        m.put("audioOutDelay0", BinderTransact.queryInt(ab, DESC_AUDIO, TX_AUDIO_GET_OUT_DEVICE_DELAY, new BinderTransact.Writer() {
            @Override public void write(Parcel p) { p.writeInt(0); }
        }));
        m.put("audioVolume0", BinderTransact.queryInt(ab, DESC_AUDIO, TX_AUDIO_GET_VOLUME, new BinderTransact.Writer() {
            @Override public void write(Parcel p) { p.writeInt(0); }
        }));

        // IGimiProjector extras
        m.put("ledOn", isLedOn());

        // IGimiCommon extras
        m.put("currentChip", BinderTransact.queryString(cb, DESC_COMMON, TX_COMMON_GET_CURRENT_CHIP));
        m.put("strState", BinderTransact.queryInt(cb, DESC_COMMON, TX_COMMON_GET_STR));
        m.put("currentProduct", BinderTransact.queryString(cb, DESC_COMMON, TX_COMMON_GET_CURRENT_PRODUCT));
        m.put("currentGmuiVersion", BinderTransact.queryString(cb, DESC_COMMON, TX_COMMON_GET_CURRENT_GMUI_VERSION));
        m.put("currentProductVersion", BinderTransact.queryString(cb, DESC_COMMON, TX_COMMON_GET_CURRENT_PRODUCT_VERSION));
        m.put("xgimiApiVersion", BinderTransact.queryInt(cb, DESC_COMMON, TX_COMMON_GET_CURRENT_XGIMI_API_VERSION));
        m.put("cecEnabled", BinderTransact.queryBool(cb, DESC_COMMON, TX_COMMON_IS_CEC_ENABLED));
        m.put("cecAutoWakeup", BinderTransact.queryBool(cb, DESC_COMMON, TX_COMMON_IS_CEC_AUTO_WAKEUP));
        m.put("cecAutoDeviceOff", BinderTransact.queryBool(cb, DESC_COMMON, TX_COMMON_IS_CEC_AUTO_DEVICE_OFF));
        return m;
    }
}
