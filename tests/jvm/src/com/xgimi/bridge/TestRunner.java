package com.xgimi.bridge;

public class TestRunner {
    public static void main(String[] args) {
        System.out.println("=== Starting JVM Regression Test Suite ===");
        int failures = 0;

        failures += run("SettingRulesTest", SettingRulesTest::runAll);
        failures += run("RequestPolicyTest", RequestPolicyTest::runAll);
        failures += run("PresetStoreTest", PresetStoreTest::runAll);
        failures += run("DeviceCommandsTest", DeviceCommandsTest::runAll);
        failures += run("MiniHttpTest", MiniHttpTest::runAll);
        failures += run("WakeHttpServerTest", WakeHttpServerTest::runAll);
        failures += run("HttpServerTest", HttpServerTest::runAll);
        failures += run("IoUtilsTest", IoUtilsTest::runAll);

        if (failures == 0) {
            System.out.println("=== ALL JVM REGRESSION TESTS PASSED ===");
            System.exit(0);
        } else {
            System.err.println("=== " + failures + " TEST SUITE(S) FAILED ===");
            System.exit(1);
        }
    }

    private static int run(String name, TestMethod m) {
        try {
            System.out.println("[RUN] " + name);
            m.run();
            return 0;
        } catch (Throwable t) {
            System.err.println("[FAIL] " + name + ": " + t.getMessage());
            t.printStackTrace(System.err);
            return 1;
        }
    }

    @FunctionalInterface
    interface TestMethod {
        void run() throws Throwable;
    }
}
