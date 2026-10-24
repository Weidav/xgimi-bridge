package com.xgimi.bridge;

import android.content.Context;
import org.json.JSONArray;

public class InputServiceTest {
    public static void runAll() throws Exception {
        java.io.File tmp = new java.io.File(System.getProperty("java.io.tmpdir"));
        Context ctx = new Context(tmp);
        InputService service = new InputService(ctx);

        JSONArray inputs = service.getInputs();
        if (inputs == null) {
            throw new AssertionError("getInputs should not return null");
        }

        service.switchToStorage();
        service.switchToHdmi(1);
        service.findHdmiPassthroughInputId(1);

        System.out.println("  [PASS] InputServiceTest");
    }
}
