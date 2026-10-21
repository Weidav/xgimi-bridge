package com.xgimi.bridge;

import java.util.ArrayList;
import java.util.List;

public class LocalAdbClient {
    public final List<String> injectedCommands = new ArrayList<>();
    public boolean authorized = true;

    public LocalAdbClient(android.content.Context c) {}

    public void warmUpAsync() {}

    public void close() {}

    public boolean isAuthorized() {
        return authorized;
    }

    public String authorize() {
        return "authorized";
    }

    public String injectKey(int n) {
        injectedCommands.add("key:" + n);
        return "OK " + n;
    }

    public String injectText(String s) {
        injectedCommands.add("text:" + s);
        return "OK TEXT";
    }

    public String queryHardwareInfo() {
        return "{}";
    }
}