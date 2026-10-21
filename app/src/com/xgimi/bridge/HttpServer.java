package com.xgimi.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HttpServer extends MiniHttp {
    private static final String TAG = "XgimiBridge";

    private final Context ctx;
    private final AidlClient aidl;
    private final PresetStore presets;
    private final LocalAdbClient adb;
    private final DeviceCommands deviceCommands;
    private final RequestPolicy requestPolicy;

    public HttpServer(Context ctx, int port, AidlClient aidl, PresetStore presets) {
        this(ctx, port, aidl, presets, new LocalAdbClient(ctx),
                new RequestPolicy(ctx != null ? ctx.getFilesDir() : null));
        this.adb.warmUpAsync();
    }

    public HttpServer(Context ctx, int port, AidlClient aidl, PresetStore presets,
                      LocalAdbClient adb, RequestPolicy policy) {
        super(port, Limits.forPort8080());
        this.ctx = ctx;
        this.aidl = aidl;
        this.presets = presets;
        this.adb = adb;
        this.deviceCommands = new DeviceCommands(aidl, adb);
        this.requestPolicy = policy != null ? policy : new RequestPolicy(ctx != null ? ctx.getFilesDir() : null);
    }

    public RequestPolicy getRequestPolicy() {
        return requestPolicy;
    }

    @Override
    public void stop() {
        super.stop();
        if (adb != null) {
            adb.close();
        }
    }

    @Override
    protected Response serve(Request req) {
        String uri = req.uri;
        int listeningPort = getListeningPort();

        // Validate Host header for all requests (assets + API)
        String host = req.headers.get("host");
        if (!requestPolicy.isHostAllowed(host, listeningPort)) {
            return json(Response.Status.FORBIDDEN, errJson("request_forbidden", "Host forbidden: " + host));
        }

        // Validate Origin header for mutation methods
        if (req.method == Method.POST || req.method == Method.PUT || req.method == Method.DELETE) {
            String origin = req.headers.get("origin");
            if (!requestPolicy.isOriginAllowed(origin, listeningPort, false)) {
                return json(Response.Status.FORBIDDEN, errJson("request_forbidden", "Origin forbidden: " + origin));
            }
        }

        try {
            if (uri.startsWith("/api/")) {
                return handleApi(req, uri);
            } else {
                return serveAsset(req, uri);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Handler exception on " + uri, t);
            return json(Response.Status.INTERNAL_ERROR, errJson("internal_error", t.getMessage()));
        }
    }

    private Response handleApi(Request req, String uri) throws IOException {
        Method m = req.method;

        // /api/state
        if (uri.equals("/api/state")) {
            if (m != Method.GET && m != Method.HEAD) {
                return methodNotAllowed("GET, HEAD");
            }
            JSONObject o = new JSONObject();
            try {
                o.put("ready", aidl.isReady())
                 .put("awake", isAwake())
                 .put("port", getListeningPort())
                 .put("runningApp", getRunningApp(ctx))
                 .put("ts", System.currentTimeMillis());
            } catch (JSONException ignored) {}
            return json(Response.Status.OK, o.toString());
        }

        // /api/inputs
        if (uri.equals("/api/inputs")) {
            if (m != Method.GET && m != Method.HEAD) {
                return methodNotAllowed("GET, HEAD");
            }
            return handleInputsEnum();
        }

        // /api/picture
        if (uri.equals("/api/picture")) {
            if (m != Method.GET && m != Method.HEAD) {
                return methodNotAllowed("GET, HEAD");
            }
            Map<String, Object> snap = aidl.snapshot();
            snap.put("awake", isAwake());
            snap.put("runningApp", getRunningApp(ctx));
            for (Map.Entry<String, Object> e : snap.entrySet()) {
                if (e.getValue() instanceof Map) {
                    e.setValue(new JSONObject((Map<?, ?>) e.getValue()));
                }
            }
            return json(Response.Status.OK, new JSONObject(snap).toString());
        }

        // /api/info
        if (uri.equals("/api/info")) {
            if (m != Method.GET && m != Method.HEAD) {
                return methodNotAllowed("GET, HEAD");
            }
            JSONObject info = ProjectorInfoCollector.collectAll(ctx, aidl, adb);
            return json(Response.Status.OK, info.toString());
        }

        // /api/picture/*
        if (uri.startsWith("/api/picture/")) {
            String knob = uri.substring("/api/picture/".length());
            if (knob.isEmpty() || knob.contains("/")) {
                return json(Response.Status.NOT_FOUND, errJson("not_found", "Invalid knob path"));
            }
            if (m != Method.POST) {
                return methodNotAllowed("POST");
            }
            return handlePictureSet(req, knob);
        }

        // /api/input
        if (uri.equals("/api/input")) {
            if (m != Method.POST) {
                return methodNotAllowed("POST");
            }
            return handleInputSwitch(req);
        }

        // /api/power/*
        if (uri.startsWith("/api/power/")) {
            String action = uri.substring("/api/power/".length());
            if (action.isEmpty() || action.contains("/")) {
                return json(Response.Status.NOT_FOUND, errJson("not_found", "Invalid power action path"));
            }
            if (m != Method.POST) {
                return methodNotAllowed("POST");
            }
            return handlePower(action);
        }

        // /api/presets or /api/presets/*
        if (uri.equals("/api/presets") || uri.startsWith("/api/presets/")) {
            return handlePresets(req, uri);
        }

        // /api/adb/*
        if (uri.startsWith("/api/adb/")) {
            return handleAdb(req, uri, m);
        }

        return json(Response.Status.NOT_FOUND, errJson("not_found", "Unknown API endpoint: " + uri));
    }

    private Response handleInputsEnum() {
        try {
            android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager)
                    ctx.getSystemService(Context.TV_INPUT_SERVICE);
            JSONArray arr = new JSONArray();
            if (tim != null) {
                for (android.media.tv.TvInputInfo info : tim.getTvInputList()) {
                    JSONObject o = new JSONObject();
                    try {
                        o.put("id", info.getId());
                        o.put("type", info.getType());
                        CharSequence label = info.loadLabel(ctx);
                        o.put("label", label != null ? label.toString() : null);
                        CharSequence custom = info.loadCustomLabel(ctx);
                        o.put("customLabel", custom != null ? custom.toString() : null);
                        try {
                            java.lang.reflect.Method m1 = info.getClass().getMethod("getHdmiDeviceInfo");
                            Object hdi = m1.invoke(info);
                            if (hdi != null) {
                                java.lang.reflect.Method getDisplayName = hdi.getClass().getMethod("getDisplayName");
                                Object dn = getDisplayName.invoke(hdi);
                                if (dn != null) o.put("displayName", dn.toString());
                                java.lang.reflect.Method getPortId = hdi.getClass().getMethod("getPortId");
                                Object pid = getPortId.invoke(hdi);
                                if (pid != null) o.put("hdmiPortId", ((Integer) pid).intValue());
                            }
                        } catch (Throwable ignored) {}
                    } catch (JSONException ignored) {}
                    arr.put(o);
                }
            }
            return json(Response.Status.OK, arr.toString());
        } catch (Throwable t) {
            Log.e(TAG, "Inputs enumeration failed", t);
            return json(Response.Status.INTERNAL_ERROR, errJson("internal_error", t.getMessage()));
        }
    }

    private Response handlePictureSet(Request req, String knob) {
        if ("reset".equals(knob)) {
            int mode = 0;
            boolean force = false;
            if (req.body != null && req.body.length > 0) {
                Response ctCheck = checkJsonContentType(req);
                if (ctCheck != null) return ctCheck;
                try {
                    SettingRules.preflightJsonObject(req.body);
                    JSONObject body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
                    if (body.has("mode")) {
                        Object rawMode = body.opt("mode");
                        mode = SettingRules.validateIntegerRange(rawMode, Integer.MIN_VALUE, Integer.MAX_VALUE, "mode");
                        if (mode != 0 && !SettingRules.ALLOWED_PICTURE_MODES.contains(mode)) {
                            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", "Invalid reset mode: " + mode));
                        }
                    }
                    if (body.has("force")) {
                        force = body.optBoolean("force", false);
                    }
                } catch (IllegalArgumentException e) {
                    return json(Response.Status.BAD_REQUEST, errJson("invalid_request", e.getMessage()));
                } catch (JSONException e) {
                    return json(Response.Status.BAD_REQUEST, errJson("invalid_request", "Invalid JSON: " + e.getMessage()));
                }
            }
            DeviceCommands.CommandResult cr = deviceCommands.resetPicture(mode, force);
            return toResponse(cr);
        }

        // Direct picture knob
        String canonical = SettingRules.canonicalField(knob);
        if (canonical == null || !SettingRules.isWritableField(canonical)) {
            return json(Response.Status.NOT_FOUND, errJson("not_found", "Unknown knob: " + knob));
        }

        Response ctCheck = checkJsonContentType(req);
        if (ctCheck != null) return ctCheck;

        JSONObject body;
        try {
            SettingRules.preflightJsonObject(req.body);
            body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", "Invalid JSON: " + e.getMessage()));
        }

        if (!body.has("value") || body.isNull("value")) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", "missing value"));
        }

        int validValue;
        try {
            validValue = SettingRules.validateSettingValue(canonical, body.opt("value"));
        } catch (IllegalArgumentException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", e.getMessage()));
        }

        DeviceCommands.CommandResult cr = deviceCommands.setPictureSetting(canonical, validValue);
        return toResponse(cr);
    }

    private Response handleInputSwitch(Request req) {
        Response ctCheck = checkJsonContentType(req);
        if (ctCheck != null) return ctCheck;

        JSONObject body;
        try {
            SettingRules.preflightJsonObject(req.body);
            body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", "Invalid JSON: " + e.getMessage()));
        }

        String to;
        try {
            to = SettingRules.validateInputTo(body.optString("to", null));
        } catch (IllegalArgumentException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", e.getMessage()));
        }

        try {
            if ("STORAGE".equals(to) || "HOME".equals(to) || "APPS".equals(to)) {
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        am.killBackgroundProcesses("com.google.android.tv.inputplayer");
                    }
                } catch (Throwable ignored) {}
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(home);
                return json(Response.Status.OK, "{\"ok\":true,\"to\":\"STORAGE\"}");
            }

            int port = Integer.parseInt(to.substring(4));
            String inputId = findHdmiPassthroughInputId(port);
            Intent it = new Intent(Intent.ACTION_VIEW,
                    android.media.tv.TvContract.buildChannelUriForPassthroughInput(inputId));
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(it);
            return json(Response.Status.OK, "{\"ok\":true,\"to\":\"" + to + "\"}");
        } catch (Throwable t) {
            Log.e(TAG, "Input switch failed", t);
            return json(Response.Status.INTERNAL_ERROR, errJson("internal_error", t.getMessage()));
        }
    }

    private String findHdmiPassthroughInputId(int port) {
        android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager)
                ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        String fallback = null;
        for (android.media.tv.TvInputInfo info : tim.getTvInputList()) {
            try {
                java.lang.reflect.Method m1 = info.getClass().getMethod("getHdmiDeviceInfo");
                Object hdi = m1.invoke(info);
                if (hdi != null) {
                    java.lang.reflect.Method getPortId = hdi.getClass().getMethod("getPortId");
                    Object pid = getPortId.invoke(hdi);
                    if (pid instanceof Integer && ((Integer) pid).intValue() == port) {
                        if (info.getParentId() == null) return info.getId();
                        if (fallback == null) fallback = info.getId();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return fallback;
    }

    private Response handlePower(String action) {
        DeviceCommands.CommandResult cr = deviceCommands.handlePowerAction(action, isAwake());
        return toResponse(cr);
    }

    private Response handlePresets(Request req, String uri) {
        Method m = req.method;
        try {
            if (uri.equals("/api/presets")) {
                if (m == Method.GET || m == Method.HEAD) {
                    JSONArray list = presets.load();
                    return json(Response.Status.OK, list.toString());
                }
                if (m == Method.POST) {
                    Response ctCheck = checkJsonContentType(req);
                    if (ctCheck != null) return ctCheck;

                    SettingRules.preflightJsonObject(req.body);
                    JSONObject body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
                    JSONObject created = presets.create(body);
                    return json(Response.Status.OK, created.toString());
                }
                return methodNotAllowed("GET, HEAD, POST");
            }

            // Path is /api/presets/...
            String rest = uri.substring("/api/presets/".length());
            String[] segments = rest.split("/");
            if (segments.length == 0 || segments[0].isEmpty()) {
                return json(Response.Status.NOT_FOUND, errJson("not_found", "Preset id missing"));
            }

            String id = segments[0];

            if (segments.length == 1) {
                // /api/presets/:id
                if (m == Method.PUT) {
                    Response ctCheck = checkJsonContentType(req);
                    if (ctCheck != null) return ctCheck;

                    SettingRules.preflightJsonObject(req.body);
                    JSONObject body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
                    JSONObject updated = presets.update(id, body);
                    if (updated == null) {
                        return json(Response.Status.NOT_FOUND, errJson("not_found", "Preset not found: " + id));
                    }
                    return json(Response.Status.OK, updated.toString());
                }
                if (m == Method.DELETE) {
                    boolean ok = presets.delete(id);
                    if (!ok) {
                        return json(Response.Status.NOT_FOUND, errJson("not_found", "Preset not found: " + id));
                    }
                    JSONObject r = new JSONObject();
                    r.put("ok", true);
                    return json(Response.Status.OK, r.toString());
                }
                return methodNotAllowed("PUT, DELETE");
            }

            if (segments.length == 2) {
                String sub = segments[1];
                if ("sync".equals(sub)) {
                    if (m != Method.POST) return methodNotAllowed("POST");
                    JSONObject body = null;
                    if (req.body != null && req.body.length > 0) {
                        Response ctCheck = checkJsonContentType(req);
                        if (ctCheck != null) return ctCheck;
                        SettingRules.preflightJsonObject(req.body);
                        body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
                    }
                    JSONObject synced = presets.syncFromCurrent(id, body);
                    if (synced == null) {
                        return json(Response.Status.NOT_FOUND, errJson("not_found", "Preset not found: " + id));
                    }
                    return json(Response.Status.OK, synced.toString());
                }
                if ("apply".equals(sub)) {
                    if (m != Method.POST) return methodNotAllowed("POST");
                    JSONObject preset = presets.getPreset(id);
                    if (preset == null) {
                        return json(Response.Status.NOT_FOUND, errJson("not_found", "Preset not found: " + id));
                    }
                    JSONObject valuesObj = preset.optJSONObject("values");
                    Map<String, Integer> validated = presets.validateAndExtractValues(valuesObj);
                    DeviceCommands.CommandResult cr = deviceCommands.applyPresetValues(id, validated);
                    return toResponse(cr);
                }
            }

            return json(Response.Status.NOT_FOUND, errJson("not_found", "Unknown preset route: " + uri));
        } catch (PresetStore.BuiltinReadOnlyException be) {
            return json(Response.Status.FORBIDDEN, errJson("builtin_read_only", be.getMessage()));
        } catch (PresetStore.PresetStorageException se) {
            return json(statusFromCode(se.statusCode), errJson(se.errorCode, se.getMessage()));
        } catch (IllegalArgumentException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", e.getMessage()));
        } catch (JSONException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", "Invalid JSON: " + e.getMessage()));
        } catch (IOException e) {
            return json(Response.Status.INTERNAL_ERROR, errJson("internal_error", e.getMessage()));
        }
    }

    private Response handleAdb(Request req, String uri, Method m) {
        try {
            if (uri.equals("/api/adb/status")) {
                if (m != Method.GET && m != Method.HEAD) return methodNotAllowed("GET, HEAD");
                boolean ok = adb.isAuthorized();
                return json(Response.Status.OK, "{\"authorized\":" + ok + "}");
            }
            if (uri.equals("/api/adb/authorize")) {
                if (m != Method.POST) return methodNotAllowed("POST");
                String r = adb.authorize();
                return json(Response.Status.OK, "{\"ok\":true,\"state\":\"" + r + "\"}");
            }
            if (uri.startsWith("/api/adb/keyevent/")) {
                if (m != Method.POST) return methodNotAllowed("POST");
                String codeStr = uri.substring("/api/adb/keyevent/".length());
                if (codeStr.contains("/")) {
                    return json(Response.Status.NOT_FOUND, errJson("not_found", "Invalid adb key route"));
                }
                int code;
                try {
                    code = Integer.parseInt(codeStr);
                    SettingRules.validateKeyCode(code);
                } catch (IllegalArgumentException e) {
                    return json(Response.Status.BAD_REQUEST, errJson("invalid_request", "Invalid keycode: " + codeStr));
                }
                String out = adb.injectKey(code);
                return json(Response.Status.OK, "{\"ok\":true,\"key\":" + code + ",\"out\":" + jsonString(out) + "}");
            }
            if (uri.equals("/api/adb/text")) {
                if (m != Method.POST) return methodNotAllowed("POST");
                Response ctCheck = checkJsonContentType(req);
                if (ctCheck != null) return ctCheck;

                SettingRules.preflightJsonObject(req.body);
                JSONObject body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
                String text;
                try {
                    text = SettingRules.validateText(body.optString("text", null));
                } catch (IllegalArgumentException e) {
                    return json(Response.Status.BAD_REQUEST, errJson("invalid_request", e.getMessage()));
                }
                String out = adb.injectText(text);
                return json(Response.Status.OK, "{\"ok\":true,\"out\":" + jsonString(out) + "}");
            }
        } catch (JSONException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", "Invalid JSON: " + e.getMessage()));
        } catch (Throwable t) {
            Log.e(TAG, "ADB endpoint " + uri + " failed", t);
            return json(Response.Status.INTERNAL_ERROR, errJson("internal_error", "adb: " + t.getMessage()));
        }
        return json(Response.Status.NOT_FOUND, errJson("not_found", "No such adb endpoint: " + uri));
    }

    private static Response checkJsonContentType(Request req) {
        String ct = req.headers.get("content-type");
        if (ct == null) {
            return jsonResponse(Response.Status.UNSUPPORTED_MEDIA_TYPE,
                    errJson("unsupported_media_type", "Content-Type must be application/json"));
        }
        String cleanCt = ct.split(";")[0].trim().toLowerCase(Locale.US);
        if (!cleanCt.equals("application/json")) {
            return jsonResponse(Response.Status.UNSUPPORTED_MEDIA_TYPE,
                    errJson("unsupported_media_type", "Content-Type must be application/json"));
        }
        return null;
    }

    private static Response toResponse(DeviceCommands.CommandResult cr) {
        return jsonResponse(statusFromCode(cr.httpStatus), cr.body.toString());
    }

    private static Response.Status statusFromCode(int code) {
        for (Response.Status s : Response.Status.values()) {
            if (s.code == code) return s;
        }
        return Response.Status.INTERNAL_ERROR;
    }

    private static Response methodNotAllowed(String allow) {
        Response r = jsonResponse(Response.Status.METHOD_NOT_ALLOWED,
                errJson("method_not_allowed", "Method not allowed. Allowed methods: " + allow));
        r.addHeader("Allow", allow);
        return r;
    }

    private static Response jsonResponse(Response.Status st, String body) {
        return newFixedLengthResponse(st, "application/json; charset=utf-8", body);
    }

    private Response json(Response.Status st, String body) {
        return jsonResponse(st, body);
    }

    private static String errJson(String code, String msg) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", false);
            obj.put("code", code != null ? code : "error");
            obj.put("error", msg != null ? msg : "");
        } catch (JSONException ignored) {}
        return obj.toString();
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c < 32) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    private boolean isAwake() {
        android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    static String getRunningApp(Context ctx) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String prop = (String) get.invoke(null, "sys.mtk.running.application", "");
            if (prop != null && !prop.trim().isEmpty() && !"null".equalsIgnoreCase(prop.trim())) {
                return prop.trim();
            }
        } catch (Throwable ignored) {}
        try {
            String pkg = Settings.Global.getString(ctx.getContentResolver(), "running_package_name");
            if (pkg != null && !pkg.trim().isEmpty() && !"null".equalsIgnoreCase(pkg.trim())) {
                return pkg.trim();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Response serveAsset(Request req, String uri) {
        if (req.method != Method.GET && req.method != Method.HEAD) {
            return methodNotAllowed("GET, HEAD");
        }
        String path = uri.equals("/") ? "/index.html" : uri;
        if (path.startsWith("/")) path = path.substring(1);
        if (path.contains("..") || path.contains("//") || path.indexOf('\0') >= 0) {
            return json(Response.Status.NOT_FOUND, errJson("not_found", "Asset not found"));
        }
        String assetPath = "web/" + path;
        AssetManager am = ctx.getAssets();
        try (InputStream is = am.open(assetPath)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            byte[] data = out.toByteArray();
            Response r = newFixedLengthResponse(Response.Status.OK, mimeFor(path), data);
            if (path.startsWith("assets/")) {
                r.addHeader("Cache-Control", "public, max-age=31536000, immutable");
            } else if (path.equals("index.html") || path.isEmpty()) {
                r.addHeader("Cache-Control", "no-cache, must-revalidate");
            } else if (path.endsWith(".webmanifest") || path.endsWith(".png") || path.endsWith(".svg")) {
                r.addHeader("Cache-Control", "public, max-age=86400");
            }
            return r;
        } catch (IOException e) {
            return json(Response.Status.NOT_FOUND, errJson("not_found", "Asset not found: " + path));
        }
    }

    private String mimeFor(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json") || path.endsWith(".webmanifest")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
