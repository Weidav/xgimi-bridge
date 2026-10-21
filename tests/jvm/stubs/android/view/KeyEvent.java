package android.view;

public class KeyEvent extends InputEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int KEYCODE_POWER = 26;
    public static final int KEYCODE_WAKEUP = 224;
    public static final int KEYCODE_SLEEP = 223;

    public KeyEvent(long downTime, long eventTime, int action, int code, int repeat,
                    int metaState, int deviceId, int scancode, int flags, int source) {}

    public static int getMaxKeyCode() {
        return 315;
    }
}
