package com.xgimi.bridge;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Durable, quota-bounded storage for picture presets.
 * Uses AtomicFile for crash-safe writes, preserves corrupt stores without truncation,
 * enforces storage quotas and protects built-in presets.
 */
public class PresetStore {
    private static final String TAG = "XgimiBridge";
    public static final String FILE = "presets.json";
    public static final String RECOVERY_FILE = "presets.json.recovery";
    public static final String BALANCED_ID = "balanced";

    public static final int MAX_CUSTOM_PRESETS = 100;
    public static final int MAX_NAME_CODE_POINTS = 80;
    public static final int MAX_NAME_BYTES = 320;
    public static final int MAX_ID_CHARS = 96;
    public static final int MAX_STORE_BYTES = 262144; // 256 KB

    private final File path;
    private final File recoveryPath;
    private final AtomicFile atomicFile;
    private final AidlClient aidl;
    private final Object storageLock = new Object();

    public static class PresetStorageException extends Exception {
        public final int statusCode;
        public final String errorCode;

        public PresetStorageException(int statusCode, String errorCode, String message) {
            super(message);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }

        public PresetStorageException(int statusCode, String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }
    }

    public static class BuiltinReadOnlyException extends RuntimeException {
        public BuiltinReadOnlyException(String msg) {
            super(msg);
        }
    }

    public PresetStore(Context ctx, AidlClient aidl) {
        File filesDir = ctx.getFilesDir();
        this.path = new File(filesDir, FILE);
        this.recoveryPath = new File(filesDir, RECOVERY_FILE);
        this.atomicFile = new AtomicFile(this.path);
        this.aidl = aidl;
    }

    public static JSONObject balancedValues() {
        JSONObject v = new JSONObject();
        try {
            v.put("wbRed", 60);
            v.put("wbGreen", 50);
            v.put("wbBlue", 55);
            v.put("brightness", 50);
            v.put("contrast", 50);
            v.put("saturation", 50);
            v.put("sharpness", 50);
            v.put("hue", 50);
        } catch (JSONException ignored) {}
        return v;
    }

    /**
     * Explicit initialization and built-in migration at service startup.
     * Missing stores get canonical defaults; existing stores are preserved and validated.
     */
    public void initialize() throws PresetStorageException {
        synchronized (storageLock) {
            File backup = new File(path.getPath() + ".bak");
            boolean exists = path.exists() || backup.exists();
            if (!exists) {
                // Check if recovery file exists before declaring store absent
                if (recoveryPath.exists() && recoveryPath.length() > 0) {
                    try (FileInputStream in = new FileInputStream(recoveryPath);
                         FileOutputStream out = new FileOutputStream(path)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                        }
                        Log.i(TAG, "Restored preset store from recovery copy at " + recoveryPath.getAbsolutePath());
                    } catch (IOException e) {
                        Log.w(TAG, "Failed restoring store from recovery copy: " + e.getMessage());
                    }
                }
            }

            exists = path.exists() || backup.exists();
            if (!exists) {
                // Store is missing -> create default Balanced preset
                JSONArray initial = new JSONArray();
                long now = System.currentTimeMillis();
                try {
                    JSONObject p = new JSONObject();
                    p.put("id", BALANCED_ID);
                    p.put("name", "Balanced");
                    p.put("values", balancedValues());
                    p.put("builtin", true);
                    p.put("createdAt", now);
                    p.put("updatedAt", now);
                    initial.put(p);
                    saveInternal(initial);
                    Log.i(TAG, "Initialized default presets with Balanced");
                } catch (JSONException | IOException e) {
                    throw new PresetStorageException(500, "preset_store_unavailable",
                            "Failed to initialize preset store: " + e.getMessage(), e);
                }
                return;
            }

            // Store exists: load and validate
            JSONArray arr = loadInternal();
            boolean changed = false;
            JSONObject balanced = null;
            int legacyIdx = -1;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                String id = p.optString("id", "");
                String name = p.optString("name", "");
                if (BALANCED_ID.equals(id)) {
                    balanced = p;
                } else if ("vivid".equalsIgnoreCase(id) || "vivid".equalsIgnoreCase(name)) {
                    legacyIdx = i;
                }
            }

            if (balanced == null && legacyIdx >= 0) {
                preserveRecoveryCopy();
                JSONObject p = arr.optJSONObject(legacyIdx);
                try {
                    p.put("id", BALANCED_ID);
                    p.put("name", "Balanced");
                    p.put("builtin", true);
                    p.put("values", balancedValues());
                    p.put("updatedAt", System.currentTimeMillis());
                } catch (JSONException ignored) {}
                changed = true;
                Log.i(TAG, "Migrated legacy Vivid preset to Balanced");
            } else if (balanced == null) {
                preserveRecoveryCopy();
                long now = System.currentTimeMillis();
                try {
                    JSONObject p = new JSONObject();
                    p.put("id", BALANCED_ID);
                    p.put("name", "Balanced");
                    p.put("values", balancedValues());
                    p.put("builtin", true);
                    p.put("createdAt", now);
                    p.put("updatedAt", now);
                    arr.put(p);
                } catch (JSONException ignored) {}
                changed = true;
                Log.i(TAG, "Added missing Balanced built-in preset");
            } else {
                if (!balanced.optBoolean("builtin", false)) {
                    preserveRecoveryCopy();
                    try {
                        balanced.put("builtin", true);
                    } catch (JSONException ignored) {}
                    changed = true;
                }
            }

            if (changed) {
                try {
                    saveInternal(arr);
                } catch (IOException e) {
                    throw new PresetStorageException(500, "preset_store_unavailable",
                            "Failed to save migrated presets: " + e.getMessage(), e);
                }
            }
        }
    }

    private void preserveRecoveryCopy() {
        if (!recoveryPath.exists() && path.exists()) {
            try (FileInputStream in = new FileInputStream(path);
                 FileOutputStream out = new FileOutputStream(recoveryPath)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                Log.i(TAG, "Preserved recovery copy of presets at " + recoveryPath.getAbsolutePath());
            } catch (IOException e) {
                Log.w(TAG, "Failed to preserve recovery copy of presets: " + e.getMessage());
            }
        }
    }

    /**
     * Read-only load. Preserves file bytes and modification times.
     */
    public JSONArray load() throws PresetStorageException {
        synchronized (storageLock) {
            return loadInternal();
        }
    }

    private JSONArray loadInternal() throws PresetStorageException {
        File backup = new File(path.getPath() + ".bak");
        if (!path.exists() && !backup.exists()) {
            return new JSONArray();
        }

        long length = path.exists() ? path.length() : backup.length();
        if (length > MAX_STORE_BYTES) {
            throw new PresetStorageException(413, "limit_exceeded",
                    "Preset store file exceeds maximum quota of " + MAX_STORE_BYTES + " bytes (" + length + " bytes)");
        }

        byte[] raw;
        try {
            raw = atomicFile.readFully();
        } catch (IOException e) {
            throw new PresetStorageException(500, "preset_store_unavailable",
                    "Failed to read preset store: " + e.getMessage(), e);
        }

        if (raw.length > MAX_STORE_BYTES) {
            throw new PresetStorageException(413, "limit_exceeded",
                    "Preset store file exceeds maximum quota of " + MAX_STORE_BYTES + " bytes (" + raw.length + " bytes)");
        }

        String str = new String(raw, StandardCharsets.UTF_8).trim();
        if (str.isEmpty()) {
            throw new PresetStorageException(500, "preset_store_unavailable",
                    "Preset store file is empty or corrupt");
        }

        try {
            return new JSONArray(str);
        } catch (JSONException e) {
            throw new PresetStorageException(500, "preset_store_unavailable",
                    "Preset store is corrupt: " + e.getMessage(), e);
        }
    }

    private void saveInternal(JSONArray arr) throws IOException, PresetStorageException {
        String json = arr.toString();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STORE_BYTES) {
            throw new PresetStorageException(413, "limit_exceeded",
                    "Serialized preset store size (" + bytes.length + " bytes) exceeds maximum quota of " + MAX_STORE_BYTES + " bytes");
        }

        FileOutputStream out = null;
        try {
            out = atomicFile.startWrite();
            out.write(bytes);
            atomicFile.finishWrite(out);
        } catch (IOException e) {
            if (out != null) {
                atomicFile.failWrite(out);
            }
            throw e;
        }
    }

    public JSONObject create(JSONObject body) throws PresetStorageException, IOException {
        if (body == null) {
            throw new IllegalArgumentException("Missing request body");
        }

        String rawName = body.optString("name", "").trim();
        validatePresetName(rawName);

        JSONObject valuesIn = body.optJSONObject("values");
        Map<String, Integer> validatedValues;
        if (valuesIn == null || !body.has("values")) {
            // Omitted values: capture current
            validatedValues = captureCurrentWritable();
        } else {
            if (valuesIn.length() == 0) {
                throw new IllegalArgumentException("Values object cannot be empty when provided");
            }
            validatedValues = validateAndExtractValues(valuesIn);
        }

        synchronized (storageLock) {
            JSONArray arr = loadInternal();
            int customCount = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null && !p.optBoolean("builtin", false) && !BALANCED_ID.equals(p.optString("id"))) {
                    customCount++;
                }
            }

            if (customCount >= MAX_CUSTOM_PRESETS) {
                throw new PresetStorageException(413, "limit_exceeded",
                        "Custom presets quota exceeded (max " + MAX_CUSTOM_PRESETS + ")");
            }

            String id = generateUniqueId(rawName, arr);
            long now = System.currentTimeMillis();
            JSONObject p = new JSONObject();
            try {
                p.put("id", id);
                p.put("name", rawName);
                p.put("values", new JSONObject(validatedValues));
                p.put("createdAt", now);
                p.put("updatedAt", now);
            } catch (JSONException ignored) {}

            arr.put(p);
            saveInternal(arr);
            return p;
        }
    }

    public JSONObject update(String id, JSONObject body) throws PresetStorageException, IOException {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Missing preset id");
        }
        if (body == null) {
            throw new IllegalArgumentException("Missing request body");
        }

        String newName = null;
        if (body.has("name")) {
            newName = body.optString("name", "").trim();
            validatePresetName(newName);
        }

        Map<String, Integer> newValues = null;
        if (body.has("values")) {
            JSONObject valuesIn = body.optJSONObject("values");
            if (valuesIn == null || valuesIn.length() == 0) {
                throw new IllegalArgumentException("Values object cannot be null or empty when specified");
            }
            newValues = validateAndExtractValues(valuesIn);
        }

        synchronized (storageLock) {
            JSONArray arr = loadInternal();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null && id.equals(p.optString("id"))) {
                    if (p.optBoolean("builtin", false) || BALANCED_ID.equals(id)) {
                        throw new BuiltinReadOnlyException("Built-in preset is read-only and cannot be modified");
                    }
                    try {
                        if (newName != null) p.put("name", newName);
                        if (newValues != null) p.put("values", new JSONObject(newValues));
                        p.put("updatedAt", System.currentTimeMillis());
                    } catch (JSONException ignored) {}
                    saveInternal(arr);
                    return p;
                }
            }
        }
        return null;
    }

    public JSONObject syncFromCurrent(String id, JSONObject body) throws PresetStorageException, IOException {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Missing preset id");
        }

        Map<String, Integer> valuesToSync;
        if (body != null && body.has("values")) {
            JSONObject valuesIn = body.optJSONObject("values");
            if (valuesIn == null || valuesIn.length() == 0) {
                throw new IllegalArgumentException("Values object cannot be null or empty when specified");
            }
            valuesToSync = validateAndExtractValues(valuesIn);
        } else {
            valuesToSync = captureCurrentWritable();
        }

        synchronized (storageLock) {
            JSONArray arr = loadInternal();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null && id.equals(p.optString("id"))) {
                    if (p.optBoolean("builtin", false) || BALANCED_ID.equals(id)) {
                        throw new BuiltinReadOnlyException("Built-in preset is read-only and cannot be synced");
                    }
                    try {
                        p.put("values", new JSONObject(valuesToSync));
                        p.put("updatedAt", System.currentTimeMillis());
                    } catch (JSONException ignored) {}
                    saveInternal(arr);
                    return p;
                }
            }
        }
        return null;
    }

    public boolean delete(String id) throws PresetStorageException, IOException {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Missing preset id");
        }
        synchronized (storageLock) {
            JSONArray arr = loadInternal();
            JSONArray out = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                if (id.equals(p.optString("id"))) {
                    if (p.optBoolean("builtin", false) || BALANCED_ID.equals(id)) {
                        throw new BuiltinReadOnlyException("Cannot delete built-in preset");
                    }
                    removed = true;
                    continue;
                }
                out.put(p);
            }
            if (removed) {
                saveInternal(out);
            }
            return removed;
        }
    }

    public JSONObject getPreset(String id) throws PresetStorageException {
        if (id == null || id.isEmpty()) return null;
        synchronized (storageLock) {
            JSONArray arr = loadInternal();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null && id.equals(p.optString("id"))) {
                    return p;
                }
            }
        }
        return null;
    }

    public Map<String, Integer> validateAndExtractValues(JSONObject valuesObj) {
        if (valuesObj == null) {
            throw new IllegalArgumentException("values cannot be null");
        }
        Map<String, Integer> result = new HashMap<>();
        Iterator<String> keys = valuesObj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (valuesObj.isNull(key)) {
                throw new IllegalArgumentException("Explicit null is invalid for preset value: " + key);
            }
            String canonical = SettingRules.canonicalField(key);
            if (canonical == null || !SettingRules.PRESET_WRITABLE_FIELDS.contains(canonical)) {
                throw new IllegalArgumentException("Unknown or non-writable setting field: " + key);
            }
            Object raw = valuesObj.opt(key);
            int validInt = SettingRules.validateSettingValue(canonical, raw);
            result.put(canonical, validInt);
        }
        return result;
    }

    private Map<String, Integer> captureCurrentWritable() {
        Map<String, Integer> map = new HashMap<>();
        if (aidl != null) {
            for (Map.Entry<String, Object> e : aidl.snapshot().entrySet()) {
                String canonical = SettingRules.canonicalField(e.getKey());
                if (canonical != null && SettingRules.PRESET_WRITABLE_FIELDS.contains(canonical)
                        && e.getValue() instanceof Number) {
                    try {
                        int v = SettingRules.validateSettingValue(canonical, e.getValue());
                        map.put(canonical, v);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
        return map;
    }

    private static void validatePresetName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Preset name cannot be empty");
        }
        int codePoints = name.codePointCount(0, name.length());
        if (codePoints > MAX_NAME_CODE_POINTS) {
            throw new IllegalArgumentException("Preset name exceeds max length of " + MAX_NAME_CODE_POINTS + " code points");
        }
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("Preset name exceeds max UTF-8 bytes of " + MAX_NAME_BYTES);
        }
    }

    private static String generateUniqueId(String name, JSONArray current) {
        String base = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "preset";
        if (base.length() > MAX_ID_CHARS - 10) {
            base = base.substring(0, MAX_ID_CHARS - 10);
        }

        String candidate = base;
        int n = 2;
        while (true) {
            boolean clash = false;
            for (int i = 0; i < current.length(); i++) {
                JSONObject p = current.optJSONObject(i);
                if (p != null && candidate.equals(p.optString("id"))) {
                    clash = true;
                    break;
                }
            }
            if (!clash) return candidate;
            candidate = base + "-" + n++;
            if (candidate.length() > MAX_ID_CHARS) {
                candidate = candidate.substring(0, MAX_ID_CHARS);
            }
        }
    }
}
