package com.xgimi.bridge;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Shared utility for executing Android Binder Parcel transactions with automatic obtain/recycle lifecycle.
 */
public final class BinderTransact {
    private BinderTransact() {}

    public interface Writer {
        void write(Parcel p);
    }

    public static IBinder getSubBinder(IBinder binder, String interfaceToken, int code) throws RemoteException {
        if (binder == null) throw new RemoteException("Binder is null");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(interfaceToken);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static void transactVoid(IBinder binder, String interfaceToken, int code, Writer writer) throws RemoteException {
        if (binder == null) throw new RemoteException("Binder is null");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(interfaceToken);
            if (writer != null) {
                writer.write(data);
            }
            binder.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static int transactInt(IBinder binder, String interfaceToken, int code, Writer writer) throws RemoteException {
        if (binder == null) throw new RemoteException("Binder is null");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(interfaceToken);
            if (writer != null) {
                writer.write(data);
            }
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static String transactString(IBinder binder, String interfaceToken, int code, Writer writer) throws RemoteException {
        if (binder == null) throw new RemoteException("Binder is null");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(interfaceToken);
            if (writer != null) {
                writer.write(data);
            }
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static Integer queryInt(IBinder binder, String interfaceToken, int code, Writer writer) {
        if (binder == null) return null;
        try {
            return transactInt(binder, interfaceToken, code, writer);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Integer queryInt(IBinder binder, String interfaceToken, int code) {
        return queryInt(binder, interfaceToken, code, null);
    }

    public static Boolean queryBool(IBinder binder, String interfaceToken, int code, Writer writer) {
        Integer val = queryInt(binder, interfaceToken, code, writer);
        return val == null ? null : (val != 0);
    }

    public static Boolean queryBool(IBinder binder, String interfaceToken, int code) {
        return queryBool(binder, interfaceToken, code, null);
    }

    public static String queryString(IBinder binder, String interfaceToken, int code, Writer writer) {
        if (binder == null) return null;
        try {
            return transactString(binder, interfaceToken, code, writer);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String queryString(IBinder binder, String interfaceToken, int code) {
        return queryString(binder, interfaceToken, code, null);
    }
}
