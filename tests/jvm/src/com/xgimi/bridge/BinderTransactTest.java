package com.xgimi.bridge;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

public class BinderTransactTest {
    public static void runAll() throws Exception {
        testNullSafety();
        testTransactIntAndString();
        testQuerySafety();
        System.out.println("  [PASS] BinderTransactTest");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void testNullSafety() {
        try {
            BinderTransact.transactInt(null, "desc", 1, null);
            assertTrue(false, "Should throw on null binder");
        } catch (RemoteException expected) {}

        try {
            BinderTransact.transactString(null, "desc", 1, null);
            assertTrue(false, "Should throw on null binder");
        } catch (RemoteException expected) {}

        try {
            BinderTransact.transactVoid(null, "desc", 1, null);
            assertTrue(false, "Should throw on null binder");
        } catch (RemoteException expected) {}

        try {
            BinderTransact.getSubBinder(null, "desc", 1);
            assertTrue(false, "Should throw on null binder");
        } catch (RemoteException expected) {}
    }

    private static void testQuerySafety() {
        assertTrue(BinderTransact.queryInt(null, "desc", 1) == null, "queryInt on null binder returns null");
        assertTrue(BinderTransact.queryString(null, "desc", 1) == null, "queryString on null binder returns null");
        assertTrue(BinderTransact.queryBool(null, "desc", 1) == null, "queryBool on null binder returns null");
    }

    private static void testTransactIntAndString() throws Exception {
        IBinder mockBinder = new IBinder() {
            @Override
            public boolean isBinderAlive() { return true; }
            @Override
            public String getInterfaceDescriptor() { return "test"; }
            @Override
            public boolean transact(int code, Parcel data, Parcel reply, int flags) {
                return true;
            }
        };

        int val = BinderTransact.transactInt(mockBinder, "token", 42, new BinderTransact.Writer() {
            @Override
            public void write(Parcel p) {
                p.writeInt(100);
            }
        });
        assertTrue(val == 0, "Default stub returns 0");

        String str = BinderTransact.transactString(mockBinder, "token", 43, null);
        assertTrue(str != null, "Default stub returns non-null string");

        Integer qInt = BinderTransact.queryInt(mockBinder, "token", 44);
        assertTrue(qInt != null && qInt == 0, "queryInt returns 0");

        Boolean qBool = BinderTransact.queryBool(mockBinder, "token", 45);
        assertTrue(qBool != null && !qBool, "queryBool returns false for 0");
    }
}
