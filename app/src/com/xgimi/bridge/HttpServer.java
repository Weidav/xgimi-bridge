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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HttpServer extends MiniHttp {
    private static final String TAG = "XgimiBridge";

    @FunctionalInterface
    private interface RouteHandler {
        Response handle(Request req) throws Exception;
    }

    @FunctionalInterface
    private interface PrefixRouteHandler {
        Response handle(Request req, String subPath) throws Exception;
    }

    private static class PrefixRoute {
        final String prefix;
        final PrefixRouteHandler handler;
        PrefixRoute(String prefix, PrefixRouteHandler handler) {
            this.prefix = prefix;
            this.handler = handler;
        }
    }

    private final Context ctx;
    private final AidlClient aidl;
    private final PresetStore presets;
    private final LocalAdbClient adb;
    private final DeviceCommands deviceCommands;
    private final RequestPolicy requestPolicy;
    private final InputService inputService;
    private final Map<String, RouteHandler> exactRoutes = new HashMap<>();
    private final List<PrefixRoute> prefixRoutes = new ArrayList<>();

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
        this.inputService = new InputService(ctx);
        initRoutes();
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

    private void initRoutes() {
        exactRoutes.put("/api/state", new RouteHandler() {
            @Override public Response handle(Request req) { return handleState(req); }
        });
        exactRoutes.put("/api/inputs", new RouteHandler() {
            @Override public Response handle(Request req) { return handleInputs(req); }
        });
        exactRoutes.put("/api/enums", new RouteHandler() {
            @Override public Response handle(Request req) { return handleEnums(req); }
        });
        exactRoutes.put("/api/picture", new RouteHandler() {
            @Override public Response handle(Request req) { return handlePictureSnapshot(req); }
        });
        exactRoutes.put("/api/info", new RouteHandler() {
            @Override public Response handle(Request req) { return handleInfo(req); }
        });
        exactRoutes.put("/api/input", new RouteHandler() {
            @Override public Response handle(Request req) { return handleInputSwitch(req); }
        });
        exactRoutes.put("/api/adb/status", new RouteHandler() {
            @Override public Response handle(Request req) { return handleAdbStatus(req); }
        });
        exactRoutes.put("/api/adb/authorize", new RouteHandler() {
            @Override public Response handle(Request req) { return handleAdbAuthorize(req); }
        });
        exactRoutes.put("/api/adb/text", new RouteHandler() {
            @Override public Response handle(Request req) { return handleAdbText(req); }
        });

        prefixRoutes.add(new PrefixRoute("/api/picture/", new PrefixRouteHandler() {
            @Override public Response handle(Request req, String subPath) { return handlePictureKnobRoute(req, subPath); }
        }));
        prefixRoutes.add(new PrefixRoute("/api/power/", new PrefixRouteHandler() {
            @Override public Response handle(Request req, String subPath) { return handlePowerRoute(req, subPath); }
        }));
        prefixRoutes.add(new PrefixRoute("/api/presets", new PrefixRouteHandler() {
            @Override public Response handle(Request req, String subPath) { return handlePresetsRoute(req, subPath); }
        }));
        prefixRoutes.add(new PrefixRoute("/api/adb/keyevent/", new PrefixRouteHandler() {
            @Override public Response handle(Request req, String subPath) { return handleAdbKeyeventRoute(req, subPath); }
        }));
    }

    private Response handleApi(Request req, String uri) throws Exception {
        RouteHandler exact = exactRoutes.get(uri);
        if (exact != null) {
            return exact.handle(req);
        }
        for (PrefixRoute pr : prefixRoutes) {
            if (pr.prefix.endsWith("/")) {
                if (uri.startsWith(pr.prefix)) {
                    String sub = uri.substring(pr.prefix.length());
                    return pr.handler.handle(req, sub);
                }
            } else {
                if (uri.equals(pr.prefix) || uri.startsWith(pr.prefix + "/")) {
                    String sub = uri.length() > pr.prefix.length() ? uri.substring(pr.prefix.length() + 1) : "";
                    return pr.handler.handle(req, sub);
                }
            }
        }
        return json(Response.Status.NOT_FOUND, errJson("not_found", "Unknown API endpoint: " + uri));
    }

    private Response handleState(Request req) {
        if (req.method != Method.GET && req.method != Method.HEAD) {
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

    private Response handleInputs(Request req) {
        if (req.method != Method.GET && req.method != Method.HEAD) {
            return methodNotAllowed("GET, HEAD");
        }
        try {
            JSONArray arr = inputService.getInputs();
            return json(Response.Status.OK, arr.toString());
        } catch (Throwable t) {
            Log.e(TAG, "Inputs enumeration failed", t);
            return json(Response.Status.INTERNAL_ERROR, errJson("internal_error", t.getMessage()));
        }
    }

    private Response handleEnums(Request req) {
        if (req.method != Method.GET && req.method != Method.HEAD) {
            return methodNotAllowed("GET, HEAD");
        }
        return json(Response.Status.OK, SettingRules.getEnumsJson().toString());
    }

    private Response handlePictureSnapshot(Request req) {
        if (req.method != Method.GET && req.method != Method.HEAD) {
            return methodNotAllowed("GET, HEAD");
        }
        Map<String, Object> snap = aidl.snapshot();
        snap.put("awake", isAwake());
        snap.put("runningApp", getRunningApp(ctx));
        snap.put("model", android.os.Build.MODEL);
        snap.put("osVersion", "Android " + android.os.Build.VERSION.RELEASE);
        return json(Response.Status.OK, JsonUtils.mapToJson(snap).toString());
    }

    private Response handleInfo(Request req) {
        if (req.method != Method.GET && req.method != Method.HEAD) {
            return methodNotAllowed("GET, HEAD");
        }
        JSONObject info = ProjectorInfoCollector.collectAll(ctx, aidl, adb);
        return json(Response.Status.OK, info.toString());
    }

    private Response handlePictureKnobRoute(Request req, String knob) {
        if (knob.isEmpty() || knob.contains("/")) {
            return json(Response.Status.NOT_FOUND, errJson("not_found", "Invalid knob path"));
        }
        if (req.method != Method.POST) {
            return methodNotAllowed("POST");
        }
        return handlePictureSet(req, knob);
    }

    private Response handlePowerRoute(Request req, String action) {
        if (action.isEmpty() || action.contains("/")) {
            return json(Response.Status.NOT_FOUND, errJson("not_found", "Invalid power action path"));
        }
        if (req.method != Method.POST) {
            return methodNotAllowed("POST");
        }
        return handlePower(action);
    }

    private Response handlePresetsRoute(Request req, String subPath) {
        return handlePresets(req, req.uri);
    }

    private Response handleAdbStatus(Request req) {
        if (req.method != Method.GET && req.method != Method.HEAD) {
            return methodNotAllowed("GET, HEAD");
        }
        boolean ok = adb.isAuthorized();
        return json(Response.Status.OK, "{\"authorized\":" + ok + "}");
    }

    private Response handleAdbAuthorize(Request req) {
        if (req.method != Method.POST) {
            return methodNotAllowed("POST");
        }
        try {
            String r = adb.authorize();
            return json(Response.Status.OK, "{\"ok\":true,\"state\":\"" + r + "\"}");
        } catch (Throwable t) {
            Log.e(TAG, "ADB authorize failed", t);
            return json(Response.Status.INTERNAL_ERROR, errJson("internal_error", "adb: " + t.getMessage()));
        }
    }

    private Response handleAdbText(Request req) {
        if (req.method != Method.POST) {
            return methodNotAllowed("POST");
        }
        Response ctCheck = checkJsonContentType(req);
        if (ctCheck != null) return ctCheck;

        JSONObject body;
        try {
            SettingRules.preflightJsonObject(req.body);
            body = new JSONObject(new String(req.body, StandardCharsets.UTF_8));
            String text = SettingRules.validateText(body.optString("text", null));
            String out = adb.injectText(text);
            return json(Response.Status.OK, "{\"ok\":true,\"out\":" + JSONObject.quote(out != null ? out : "") + "}");
        } catch (IllegalArgumentException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", e.getMessage()));
        } catch (JSONException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", "Invalid JSON: " + e.getMessage()));
        } catch (Throwable t) {
            Log.e(TAG, "ADB injectText failed", t);
            return json(Response.Status.INTERNAL_ERROR, errJson("internal_error", "adb: " + t.getMessage()));
        }
    }

    private Response handleAdbKeyeventRoute(Request req, String codeStr) {
        if (req.method != Method.POST) {
            return methodNotAllowed("POST");
        }
        if (codeStr.contains("/")) {
            return json(Response.Status.NOT_FOUND, errJson("not_found", "Invalid adb key route"));
        }
        try {
            int code = Integer.parseInt(codeStr);
            SettingRules.validateKeyCode(code);
            String out = adb.injectKey(code);
            return json(Response.Status.OK, "{\"ok\":true,\"key\":" + code + ",\"out\":" + JSONObject.quote(out != null ? out : "") + "}");
        } catch (IllegalArgumentException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", "Invalid keycode: " + codeStr));
        } catch (Throwable t) {
            Log.e(TAG, "ADB injectKey failed", t);
            return json(Response.Status.INTERNAL_ERROR, errJson("internal_error", "adb: " + t.getMessage()));
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
        if (req.method != Method.POST) {
            return methodNotAllowed("POST");
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

        String to;
        try {
            to = SettingRules.validateInputTo(body.optString("to", null));
        } catch (IllegalArgumentException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", e.getMessage()));
        }

        try {
            if ("STORAGE".equals(to) || "HOME".equals(to) || "APPS".equals(to)) {
                inputService.switchToStorage();
                return json(Response.Status.OK, "{\"ok\":true,\"to\":\"STORAGE\"}");
            }

            int port = Integer.parseInt(to.substring(4));
            inputService.switchToHdmi(port);
            return json(Response.Status.OK, "{\"ok\":true,\"to\":\"" + to + "\"}");
        } catch (IllegalArgumentException e) {
            return json(Response.Status.BAD_REQUEST, errJson("invalid_request", e.getMessage()));
        } catch (Throwable t) {
            Log.e(TAG, "Input switch failed", t);
            return json(Response.Status.INTERNAL_ERROR, errJson("internal_error", t.getMessage()));
        }
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

    private boolean isAwake() {
        return DeviceState.isAwake(ctx);
    }

    static String getRunningApp(Context ctx) {
        return DeviceState.getRunningApp(ctx);
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
        try {
            InputStream is = am.open(assetPath);
            long length = is.available();
            Response r = newFixedLengthResponse(Response.Status.OK, mimeFor(path), is, length);
            if (path.startsWith("assets/")) {
                r.addHeader("Cache-Control", "public, max-age=31536000, immutable");
            } else if (path.equals("index.html") || path.isEmpty()) {
                r.addHeader("Cache-Control", "no-cache, must-revalidate");
            } else if (path.endsWith(".webmanifest") || path.endsWith(".png") || path.endsWith(".svg") || path.endsWith(".woff2")) {
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
        if (path.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }
}
