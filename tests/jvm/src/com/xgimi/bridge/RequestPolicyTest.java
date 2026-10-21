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

        // Loopback is always allowed
        assertTrue(policy.isHostAllowed("localhost:8080", 8080), "localhost:8080 should be allowed");
        assertTrue(policy.isHostAllowed("127.0.0.1:8080", 8080), "127.0.0.1:8080 should be allowed");
        assertTrue(policy.isHostAllowed("127.0.0.1", 8080), "127.0.0.1 without port should be allowed");
        assertTrue(policy.isHostAllowed("[::1]:8080", 8080), "[::1]:8080 should be allowed");

        // Wrong port
        assertFalse(policy.isHostAllowed("127.0.0.1:9999", 8080), "Wrong port should be rejected");

        // Disallowed external hostnames
        assertFalse(policy.isHostAllowed("evil.com:8080", 8080), "evil.com should be rejected");
        assertFalse(policy.isHostAllowed("attacker.org", 8080), "attacker.org should be rejected");
        assertFalse(policy.isHostAllowed("10.99.99.99:8080", 8080), "Unknown foreign IP should be rejected");
    }

    private static void testAliasConfiguration() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "rp_test_" + System.nanoTime());
        tempDir.mkdirs();
        try {
            RequestPolicy policy = new RequestPolicy(tempDir);
            policy.setAliases(Arrays.asList("xgimi.local", "projector.home.", "MY-PROJECTOR"));

            assertTrue(policy.isHostAllowed("xgimi.local:8080", 8080), "Configured alias should be allowed");
            assertTrue(policy.isHostAllowed("XGIMI.LOCAL:8080", 8080), "Alias should be case-insensitive");
            assertTrue(policy.isHostAllowed("projector.home:8080", 8080), "Trailing dot normalization should work");
            assertTrue(policy.isHostAllowed("my-projector:8080", 8080), "my-projector should be allowed");

            assertFalse(policy.isHostAllowed("other.local:8080", 8080), "Unconfigured alias rejected");

            // Wildcards, paths, schemes should be rejected during configuration
            policy.setAliases(Arrays.asList("*.local", "http://bad", "bad/path"));
            assertFalse(policy.isHostAllowed("*.local:8080", 8080), "Wildcard alias should not be accepted");
        } finally {
            for (File f : tempDir.listFiles()) f.delete();
            tempDir.delete();
        }
    }

    private static void testOriginValidation() {
        RequestPolicy policy = new RequestPolicy();

        // Absent Origin is allowed (e.g. Home Assistant, curl)
        assertTrue(policy.isOriginAllowed(null, 8080, false), "Absent origin should be allowed");
        assertTrue(policy.isOriginAllowed("", 8080, false), "Empty origin should be allowed");

        // Valid loopback origin
        assertTrue(policy.isOriginAllowed("http://127.0.0.1:8080", 8080, false), "Local origin allowed");
        assertTrue(policy.isOriginAllowed("http://localhost:8080", 8080, false), "Localhost origin allowed");

        // Disallowed / attacker origin
        assertFalse(policy.isOriginAllowed("http://evil.attacker.com", 8080, false), "Evil origin rejected");
        assertFalse(policy.isOriginAllowed("null", 8080, false), "null origin rejected");
        assertFalse(policy.isOriginAllowed("javascript:alert(1)", 8080, false), "Malformed origin rejected");

        // Wake port allowing 8080 UI origin
        assertTrue(policy.isOriginAllowed("http://127.0.0.1:8080", 8081, true), "Port 8080 origin allowed on wake port 8081");
        assertFalse(policy.isOriginAllowed("http://evil.com:8080", 8081, true), "Evil origin still rejected on wake port");
    }
}
