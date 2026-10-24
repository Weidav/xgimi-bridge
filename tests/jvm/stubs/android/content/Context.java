package android.content;

import java.io.File;

public class Context {
    public static final String TV_INPUT_SERVICE = "tv";
    public static final String POWER_SERVICE = "power";
    public static final String ACTIVITY_SERVICE = "activity";
    public static final String WIFI_SERVICE = "wifi";

    private final File files;

    public Context(File f) {
        this.files = f;
    }

    public File getFilesDir() {
        return files;
    }

    private android.media.tv.TvInputManager tvInputManager;

    public void setTvInputManager(android.media.tv.TvInputManager tim) {
        this.tvInputManager = tim;
    }

    public Object getSystemService(String name) {
        if (POWER_SERVICE.equals(name)) {
            return new android.os.PowerManager();
        }
        if (TV_INPUT_SERVICE.equals(name)) {
            return tvInputManager;
        }
        return null;
    }

    public Object getContentResolver() {
        return null;
    }

    public void startActivity(Intent i) {}

    public android.content.res.AssetManager getAssets() {
        return new android.content.res.AssetManager();
    }

    public String getPackageCodePath() {
        return "/data/app/com.xgimi.bridge.apk";
    }

    public Context getApplicationContext() {
        return this;
    }
}