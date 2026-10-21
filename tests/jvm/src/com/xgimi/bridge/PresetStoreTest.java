package com.xgimi.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class PresetStoreTest {
    public static void runAll() throws Exception {
        testInitializeAndReadOnlyLoad();
        testCreateAndApplyValues();
        testBuiltinReadOnlyProtection();
        testQuotas();
        testCorruptStorePreservation();
        testRecoveryFileRestoration();
        testEmptyFileRejection();
        testSyncEmptyValuesRejected();
        testCustomPresetsQuota100();
        System.out.println("  [PASS] PresetStoreTest");
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void testInitializeAndReadOnlyLoad() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "ps_init_" + System.nanoTime());
        dir.mkdirs();
        try {
            PresetStore store = new PresetStore(new Context(dir), new AidlClient());
            store.initialize();

            File presetFile = new File(dir, "presets.json");
            assertTrue(presetFile.exists(), "presets.json should be created after initialize");
            long mtime1 = presetFile.lastModified();

            // Ordinary load() should be strictly read-only
            Thread.sleep(50);
            JSONArray loaded = store.load();
            assertTrue(loaded != null && loaded.toString().contains("Balanced"), "Default preset Balanced should be loaded");
            long mtime2 = presetFile.lastModified();
            assertTrue(mtime1 == mtime2, "load() must not modify file timestamp");
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void testCreateAndApplyValues() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "ps_create_" + System.nanoTime());
        dir.mkdirs();
        try {
            PresetStore store = new PresetStore(new Context(dir), new AidlClient());
            store.initialize();

            Map<String, Object> values = new HashMap<>();
            values.put("wbRed", 60);
            values.put("wbGreen", 55);
            values.put("wbBlue", 45);
            values.put("brightness", 70);

            JSONObject reqBody = new JSONObject();
            reqBody.put("name", "My Test Preset");
            reqBody.put("values", new JSONObject(values));

            JSONObject created = store.create(reqBody);
            assertTrue(created != null, "Preset should be created");
            String id = created.getString("id");
            assertTrue(id != null && !id.isEmpty(), "Generated id should be present");

            JSONObject loaded = store.getPreset(id);
            assertTrue(loaded != null, "Preset should be retrievable by id");
            assertTrue("My Test Preset".equals(loaded.getString("name")), "Name should match");

            Map<String, Integer> extracted = store.validateAndExtractValues(loaded.getJSONObject("values"));
            assertTrue(extracted.get("wbRed") == 60, "wbRed should be 60");
            assertTrue(extracted.get("brightness") == 70, "brightness should be 70");
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void testBuiltinReadOnlyProtection() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "ps_builtin_" + System.nanoTime());
        dir.mkdirs();
        try {
            PresetStore store = new PresetStore(new Context(dir), new AidlClient());
            store.initialize();

            // Try to delete a builtin preset
            assertThrows(() -> store.delete("balanced"));

            // Try to sync a builtin preset
            Map<String, Object> values = new HashMap<>();
            values.put("brightness", 80);
            JSONObject syncBody = new JSONObject();
            syncBody.put("values", new JSONObject(values));
            assertThrows(() -> store.syncFromCurrent("balanced", syncBody));
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void testQuotas() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "ps_quota_" + System.nanoTime());
        dir.mkdirs();
        try {
            PresetStore store = new PresetStore(new Context(dir), new AidlClient());
            store.initialize();

            // Name length > 80 code points should be rejected
            StringBuilder longName = new StringBuilder();
            for (int i = 0; i < 85; i++) longName.append("a");
            JSONObject longReq = new JSONObject();
            longReq.put("name", longName.toString());
            longReq.put("values", new JSONObject());
            assertThrows(() -> store.create(longReq));

            // Blank name rejected
            JSONObject blankReq = new JSONObject();
            blankReq.put("name", "   ");
            blankReq.put("values", new JSONObject());
            assertThrows(() -> store.create(blankReq));
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void testCorruptStorePreservation() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "ps_corrupt_" + System.nanoTime());
        dir.mkdirs();
        try {
            File presetFile = new File(dir, "presets.json");
            String corruptContent = "CORRUPT JSON NOT AN ARRAY {{{";
            try (FileOutputStream fos = new FileOutputStream(presetFile)) {
                fos.write(corruptContent.getBytes(StandardCharsets.UTF_8));
            }

            PresetStore store = new PresetStore(new Context(dir), new AidlClient());
            // Calling load() or initialize() must not wipe the file!
            assertThrows(() -> store.load());

            // Check that the corrupt content is preserved
            byte[] bytes = java.nio.file.Files.readAllBytes(presetFile.toPath());
            String preserved = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(corruptContent.equals(preserved), "Corrupt file bytes must be preserved");
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void testRecoveryFileRestoration() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "ps_recov_" + System.nanoTime());
        dir.mkdirs();
        try {
            // Write custom preset into presets.json.recovery (with no presets.json)
            File recovFile = new File(dir, "presets.json.recovery");
            JSONArray initial = new JSONArray();
            JSONObject custom = new JSONObject();
            custom.put("id", "custom_recov");
            custom.put("name", "Recovered Preset");
            JSONObject vals = new JSONObject();
            vals.put("wbRed", 77);
            custom.put("values", vals);
            initial.put(custom);

            try (FileOutputStream fos = new FileOutputStream(recovFile)) {
                fos.write(initial.toString().getBytes(StandardCharsets.UTF_8));
            }

            PresetStore store = new PresetStore(new Context(dir), new AidlClient());
            store.initialize();

            // Store should have been restored from recovery file!
            JSONArray loaded = store.load();
            assertTrue(loaded.toString().contains("custom_recov"), "Restored store must contain custom_recov preset");
            assertTrue(loaded.toString().contains("Balanced"), "Restored store must also have Balanced built-in ensured");
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void testEmptyFileRejection() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "ps_empty_" + System.nanoTime());
        dir.mkdirs();
        try {
            File presetFile = new File(dir, "presets.json");
            presetFile.createNewFile(); // 0 bytes

            PresetStore store = new PresetStore(new Context(dir), new AidlClient());
            // An existing 0-byte file must be treated as corrupt, NOT empty list
            assertThrows(() -> store.load());
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void testSyncEmptyValuesRejected() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "ps_sync_empty_" + System.nanoTime());
        dir.mkdirs();
        try {
            PresetStore store = new PresetStore(new Context(dir), new AidlClient());
            store.initialize();

            JSONObject req = new JSONObject();
            req.put("name", "Custom1");
            JSONObject vals = new JSONObject();
            vals.put("brightness", 50);
            req.put("values", vals);
            JSONObject created = store.create(req);
            String id = created.getString("id");

            // Empty values object must be rejected, not silently change meaning to capture
            JSONObject syncBody = new JSONObject();
            syncBody.put("values", new JSONObject());
            assertThrows(() -> store.syncFromCurrent(id, syncBody));
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void testCustomPresetsQuota100() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "ps_quota100_" + System.nanoTime());
        dir.mkdirs();
        try {
            PresetStore store = new PresetStore(new Context(dir), new AidlClient());
            store.initialize();

            JSONObject vals = new JSONObject();
            vals.put("brightness", 50);

            for (int i = 0; i < 100; i++) {
                JSONObject req = new JSONObject();
                req.put("name", "Preset " + i);
                req.put("values", vals);
                store.create(req);
            }

            // 101st custom preset must be rejected
            JSONObject req101 = new JSONObject();
            req101.put("name", "Preset 101");
            req101.put("values", vals);
            assertThrows(() -> store.create(req101));
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void assertThrows(RunnableWithException r) {
        try {
            r.run();
            throw new AssertionError("Expected exception was not thrown");
        } catch (Throwable expected) {
            // Success
        }
    }

    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Throwable;
    }
}
