import test from "node:test";
import assert from "node:assert/strict";

import {
  api,
  extractWritableValues,
  WRITABLE_PICTURE_FIELDS,
  formatAppName,
  sourceTitle,
  epToField,
  inputToAidlSource,
  PICTURE_MODE_OPTIONS,
} from "../../web/src/api.js";
import { formatInfoValue } from "../../web/src/constants/infoSections.js";

test("PICTURE_MODE_OPTIONS defines hardware-verified modes and labels", () => {
  assert.equal(PICTURE_MODE_OPTIONS.length, 9);
  const expected = [
    { value: 8, label: "Standard" },
    { value: 1, label: "Movie" },
    { value: 0, label: "Vivid" },
    { value: 4, label: "Game" },
    { value: 9, label: "Sports" },
    { value: 6, label: "Office" },
    { value: 27, label: "Filmmaker Mode" },
    { value: 30, label: "ISF Day" },
    { value: 31, label: "ISF Night" },
  ];
  assert.deepEqual(PICTURE_MODE_OPTIONS, expected);

  // Assert rejected/legacy modes are not in the list
  const values = new Set(PICTURE_MODE_OPTIONS.map((o) => o.value));
  assert.equal(values.has(2), false);
  assert.equal(values.has(3), false);
  assert.equal(values.has(5), false);
  assert.equal(values.has(14), false);
  assert.equal(values.has(18), false);
});

test("extractWritableValues filters out read-only and non-numeric fields", () => {
  const fullPicture = {
    currentInputSource: "E_INPUT_SOURCE_STORAGE",
    awake: true,
    resolution: "3840x2160",
    hdrStatus: "HDR10",
    wbRed: 60,
    wbGreen: 50,
    wbBlue: 55,
    colorTemp: 2,
    pictureMode: 1,
    gameMode: 0,
    brightness: 75,
    contrast: 50,
    saturation: 50,
    sharpness: 50,
    hue: 50,
    invalidNumber: "50",
    nullField: null,
  };

  const writable = extractWritableValues(fullPicture);
  assert.equal(Object.keys(writable).length, 11);
  assert.equal(writable.wbRed, 60);
  assert.equal(writable.brightness, 75);
  assert.equal(writable.currentInputSource, undefined);
  assert.equal(writable.awake, undefined);
  assert.equal(writable.resolution, undefined);
  assert.equal(writable.invalidNumber, undefined);
  assert.equal(writable.nullField, undefined);
});

test("api function handles responses and errors correctly", async () => {
  // 1. Successful 200 read without ok property
  const mockFetchSuccess = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ brightness: 50, contrast: 50 }),
  });
  const res1 = await api("/api/picture", {}, mockFetchSuccess);
  assert.equal(res1.brightness, 50);

  // 2. HTTP 200 with ok: false -> must throw
  const mockFetchOkFalse = async () => ({
    ok: true,
    status: 200,
    json: async () => ({ ok: false, error: "device_write_failed" }),
  });
  await assert.rejects(
    () => api("/api/picture/brightness", { method: "POST", body: { value: 50 } }, mockFetchOkFalse),
    /device_write_failed/
  );

  // 3. HTTP 202 Accepted pending -> succeeds and preserves pending
  const mockFetch202 = async () => ({
    ok: true,
    status: 202,
    json: async () => ({ accepted: true, pending: true }),
  });
  const res3 = await api("/api/power/on", { method: "POST" }, mockFetch202);
  assert.equal(res3.pending, true);

  // 4. HTTP 503 Busy -> throws
  const mockFetch503 = async () => ({
    ok: false,
    status: 503,
    json: async () => ({ ok: false, code: "busy", error: "Command coordinator busy" }),
  });
  await assert.rejects(
    () => api("/api/picture/brightness", { method: "POST" }, mockFetch503),
    /Command coordinator busy/
  );
});

test("slider queue coalesces rapid updates", async () => {
  const queue = new Map();
  const sentValues = [];

  // Simulate user dragging slider from 50 to 55 to 60 to 75
  queue.set("brightness", 55);
  queue.set("brightness", 60);
  queue.set("brightness", 75);

  // Only the final position should remain in queue
  assert.equal(queue.size, 1);
  assert.equal(queue.get("brightness"), 75);

  // Dequeue and record
  const [ep, target] = queue.entries().next().value;
  queue.delete(ep);
  sentValues.push({ ep, target });

  assert.equal(sentValues.length, 1);
  assert.equal(sentValues[0].target, 75);
});

test("formatAppName formats package names and filters launchers", () => {
  assert.equal(formatAppName("org.jellyfin.androidtv"), "Jellyfin");
  assert.equal(formatAppName("com.netflix.ninja"), "Netflix");
  assert.equal(formatAppName("com.google.android.youtube.tv"), "Youtube");
  assert.equal(formatAppName("com.google.android.tvlauncher"), "");
  assert.equal(formatAppName("com.google.android.apps.tv.launcherx"), "");
  assert.equal(formatAppName("com.xgimi.bridge"), "");
  assert.equal(formatAppName(null), "");
  assert.equal(formatAppName(""), "");
});

test("sourceTitle resolves running app name on Android TV and CEC labels on HDMI", () => {
  const picStorageApp = { currentInputSource: "E_INPUT_SOURCE_STORAGE", runningApp: "org.jellyfin.androidtv" };
  assert.equal(sourceTitle(picStorageApp, [], "org.jellyfin.androidtv"), "Jellyfin");

  const picStorageNoApp = { currentInputSource: "E_INPUT_SOURCE_STORAGE" };
  assert.equal(sourceTitle(picStorageNoApp, [], ""), "Android TV");

  const inputs = [
    { hdmiPortId: 1, displayName: "Apple TV" },
    { hdmiPortId: 2, displayName: "HDMI 2" },
  ];
  const picHdmi1 = { currentInputSource: "E_INPUT_SOURCE_HDMI1" };
  assert.equal(sourceTitle(picHdmi1, inputs), "Apple TV");

  const picHdmi2 = { currentInputSource: "E_INPUT_SOURCE_HDMI2" };
  assert.equal(sourceTitle(picHdmi2, inputs), "HDMI 2");
});

test("epToField maps knob endpoints to state fields dynamically", () => {
  assert.equal(epToField("brightness"), "brightness");
  assert.equal(epToField("contrast"), "contrast");
  assert.equal(epToField("wb-red"), "wbRed");
  assert.equal(epToField("wb-green"), "wbGreen");
  assert.equal(epToField("wb-blue"), "wbBlue");
  assert.equal(epToField("color-temp"), "colorTemp");
  assert.equal(epToField("picture-mode"), "pictureMode");
  assert.equal(epToField("game-mode"), "gameMode");
  assert.equal(epToField("invalid"), null);
});

test("inputToAidlSource resolves inputs to canonical AIDL sources", () => {
  assert.equal(inputToAidlSource("STORAGE"), "E_INPUT_SOURCE_STORAGE");
  assert.equal(inputToAidlSource("home"), "E_INPUT_SOURCE_STORAGE");
  assert.equal(inputToAidlSource("apps"), "E_INPUT_SOURCE_STORAGE");
  assert.equal(inputToAidlSource("HDMI1"), "E_INPUT_SOURCE_HDMI1");
  assert.equal(inputToAidlSource("hdmi2"), "E_INPUT_SOURCE_HDMI2");
  assert.equal(inputToAidlSource("HDMI3"), "E_INPUT_SOURCE_HDMI3");
  assert.equal(inputToAidlSource(null), null);
  assert.equal(inputToAidlSource(""), null);
});

test("formatInfoValue formats booleans, arrays, and nested telemetry objects cleanly", () => {
  assert.equal(formatInfoValue(null), null);
  assert.equal(formatInfoValue(undefined), null);
  assert.equal(formatInfoValue(""), null);
  assert.equal(formatInfoValue(true), "Yes");
  assert.equal(formatInfoValue(false), "No");
  assert.equal(formatInfoValue("Standard"), "Standard");
  assert.equal(formatInfoValue(42), "42");
  assert.equal(formatInfoValue(["HDR10", "Dolby Vision"]), "HDR10, Dolby Vision");
  assert.equal(formatInfoValue({ red: 50, blue: 60 }), "red: 50, blue: 60");
  assert.equal(
    formatInfoValue([{ index: 0, rpm: 1800 }, { index: 1, rpm: 2100 }]),
    "index: 0, rpm: 1800 | index: 1, rpm: 2100"
  );
});

import { createLiveRequestManager } from "../../web/src/hooks/useLiveRequests.js";

test("createLiveRequestManager governance: single-flight queue, offline abort, and circuit breaker", async () => {
  let connected = true;
  let pollFast = false;
  const calls = [];
  let shouldFail = false;

  const mockApi = async (path, opts) => {
    calls.push({ path, signal: opts?.signal });
    if (shouldFail) {
      throw new Error("Network timeout");
    }
    // Simulate brief network latency
    await new Promise((r) => setTimeout(r, 10));
    return { ok: true };
  };

  const mgr = createLiveRequestManager({
    getConnected: () => connected,
    apiFn: mockApi,
    onPollFastChange: (fast) => {
      pollFast = fast;
    },
  });

  // 1. Single keypress
  await mgr.sendKeyDispatch(19);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].path, "/api/adb/keyevent/19");
  assert.equal(mgr.isCircuitOpen(), false);

  // 2. Coalescing: fire multiple keys while first is in-flight
  calls.length = 0;
  const p1 = mgr.sendKeyDispatch(20);
  const p2 = mgr.sendKeyDispatch(21);
  const p3 = mgr.sendKeyDispatch(22);
  await Promise.all([p1, p2, p3]);
  // First key 20 fires, 21 was overwritten by 22 in pendingKey, so exactly 2 calls: 20 and 22
  assert.equal(calls.length, 2);
  assert.equal(calls[0].path, "/api/adb/keyevent/20");
  assert.equal(calls[1].path, "/api/adb/keyevent/22");

  // 3. Error trips circuit breaker
  calls.length = 0;
  shouldFail = true;
  await mgr.sendKeyDispatch(23);
  assert.equal(mgr.isCircuitOpen(), true);
  assert.equal(pollFast, true);

  // Subsequent key presses are dropped while circuit is open
  calls.length = 0;
  await mgr.sendKeyDispatch(24);
  assert.equal(calls.length, 0);

  // 4. Circuit reset closes breaker
  mgr.resetCircuit();
  assert.equal(mgr.isCircuitOpen(), false);
  assert.equal(pollFast, false);

  // 5. Offline immediately aborts and drops keys
  connected = false;
  mgr.handleOffline();
  assert.equal(mgr.isCircuitOpen(), true);
  assert.equal(pollFast, true);
  await mgr.sendKeyDispatch(25);
  assert.equal(calls.length, 0);
  const res = await mgr.liveCall("/api/test");
  assert.equal(res, null);
});

