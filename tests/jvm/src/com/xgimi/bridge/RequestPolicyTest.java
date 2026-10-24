package com.xgimi.bridge;

import java.io.File;
import java.util.Arrays;

public class RequestPolicyTest {
    public static void runAll() throws Exception {
        testHostValidation();
        testAliasConfiguration();
        testOriginValidation();
        System.out.println("  [PASS] RequestPolicyTest");
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) throw new AssertionError(msg);
    }

    private static void testHostValidation() {
        RequestPolicy policy = new RequestPolicy();

        // All hosts are allowed for isolated VLAN / reverse proxy deployments
        assertTrue(policy.isHostAllowed("localhost:8080", 8080), "localhost:8080 allowed");
        assertTrue(policy.isHostAllowed("127.0.0.1:8080", 8080), "127.0.0.1:8080 allowed");
        assertTrue(policy.isHostAllowed("xgimi.local.whitestoner.com", 8080), "Traefik reverse proxy host allowed");
        assertTrue(policy.isHostAllowed("xgimi.local.whitestoner.com:443", 8080), "Traefik HTTPS port 443 host allowed");
        assertTrue(policy.isHostAllowed("192.168.11.11:8080", 8080), "LAN IP host allowed");
    }

    private static void testAliasConfiguration() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "rp_test_" + System.nanoTime());
        tempDir.mkdirs();
        try {
            RequestPolicy policy = new RequestPolicy(tempDir);
            policy.setAliases(Arrays.asList("xgimi.local", "projector.home.", "MY-PROJECTOR"));

            assertTrue(policy.isHostAllowed("xgimi.local:8080", 8080), "Configured alias allowed");
            assertTrue(policy.isHostAllowed("XGIMI.LOCAL:8080", 8080), "Alias case-insensitive allowed");
            assertTrue(policy.isHostAllowed("projector.home:8080", 8080), "Trailing dot normalization allowed");
            assertTrue(policy.isHostAllowed("my-projector:8080", 8080), "my-projector allowed");

            // Alias configuration APIs still preserve alias list
            assertTrue(policy.getAliases().contains("xgimi.local"), "Aliases preserved in store");
        } finally {
            for (File f : tempDir.listFiles()) f.delete();
            tempDir.delete();
        }
    }

    private static void testOriginValidation() {
        RequestPolicy policy = new RequestPolicy();

        // All origins are allowed for isolated VLAN / reverse proxy deployments
        assertTrue(policy.isOriginAllowed(null, 8080, false), "Absent origin allowed");
        assertTrue(policy.isOriginAllowed("", 8080, false), "Empty origin allowed");
        assertTrue(policy.isOriginAllowed("http://127.0.0.1:8080", 8080, false), "Local origin allowed");
        assertTrue(policy.isOriginAllowed("https://xgimi.local.whitestoner.com", 8080, false), "Traefik HTTPS origin allowed");
        assertTrue(policy.isOriginAllowed("https://xgimi.local.whitestoner.com:443", 8080, false), "Traefik HTTPS 443 origin allowed");
        assertTrue(policy.isOriginAllowed("http://192.168.11.11:8080", 8081, true), "Wake port origin allowed");
    }
}
