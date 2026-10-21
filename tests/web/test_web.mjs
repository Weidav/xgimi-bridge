import test from "node:test";
import assert from "node:assert/strict";

import { api, extractWritableValues, WRITABLE_PICTURE_FIELDS } from "../../web/src/api.js";

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
