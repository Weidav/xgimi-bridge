package android.os;

public class Parcel {
    public static Parcel obtain() {
        return new Parcel();
    }
    public void recycle() {}
    public void writeInterfaceToken(String t) {}
    public void writeInt(int v) {}
    public int readInt() { return 0; }
    public void readException() {}
    public String readString() { return ""; }
    public byte[] createByteArray() { return new byte[0]; }
}
