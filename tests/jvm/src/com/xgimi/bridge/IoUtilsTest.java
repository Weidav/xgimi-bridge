package com.xgimi.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

public class IoUtilsTest {
    public static void runAll() throws Exception {
        testReadFullyAndCopy();
        testFileOperations();
        System.out.println("  [PASS] IoUtilsTest");
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }

    private static void testReadFullyAndCopy() throws Exception {
        byte[] expected = "HELLO_WORLD_IO_UTILS_TEST_12345".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(expected);
        byte[] read = IoUtils.readFully(in);
        assertTrue(new String(read, StandardCharsets.UTF_8).equals("HELLO_WORLD_IO_UTILS_TEST_12345"), "readFully must match input");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in = new ByteArrayInputStream(expected);
        long copied = IoUtils.copy(in, out);
        assertTrue(copied == expected.length, "copy length must match");
        assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).equals("HELLO_WORLD_IO_UTILS_TEST_12345"), "copy content must match");
    }

    private static void testFileOperations() throws Exception {
        File temp = File.createTempFile("io_test_", ".dat");
        File copy = File.createTempFile("io_copy_", ".dat");
        try {
            byte[] data = "FILE_CONTENT_TEST_DATA".getBytes(StandardCharsets.UTF_8);
            IoUtils.writeBytes(temp, data);
            byte[] read = IoUtils.readFile(temp);
            assertTrue(new String(read, StandardCharsets.UTF_8).equals("FILE_CONTENT_TEST_DATA"), "readFile must match writeBytes");

            IoUtils.copyFile(temp, copy);
            byte[] copiedRead = IoUtils.readFile(copy);
            assertTrue(new String(copiedRead, StandardCharsets.UTF_8).equals("FILE_CONTENT_TEST_DATA"), "copyFile content must match");
        } finally {
            temp.delete();
            copy.delete();
        }
    }
}
