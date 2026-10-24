package android.media.tv;

public class TvInputInfo {
    private final String id;
    private final String parentId;
    private final int type;
    private final Object hdmiDeviceInfo;

    public TvInputInfo() {
        this(null, null, 0, null);
    }

    public TvInputInfo(String id, String parentId, int type, Object hdmiDeviceInfo) {
        this.id = id;
        this.parentId = parentId;
        this.type = type;
        this.hdmiDeviceInfo = hdmiDeviceInfo;
    }

    public String getId() { return id; }
    public String getParentId() { return parentId; }
    public int getType() { return type; }
    public CharSequence loadLabel(android.content.Context c) { return id != null ? id : "HDMI"; }
    public CharSequence loadCustomLabel(android.content.Context c) { return null; }
    public Object getHdmiDeviceInfo() { return hdmiDeviceInfo; }
}