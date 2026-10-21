/**
 * Client transport wrapper and picture setting validation utilities.
 */

export const WRITABLE_PICTURE_FIELDS = [
  "wbRed", "wbGreen", "wbBlue",
  "colorTemp", "pictureMode", "gameMode",
  "brightness", "contrast", "saturation", "sharpness", "hue"
];

export function extractWritableValues(pic) {
  if (!pic || typeof pic !== "object") return {};
  const values = {};
  for (const field of WRITABLE_PICTURE_FIELDS) {
    if (typeof pic[field] === "number") {
      values[field] = pic[field];
    }
  }
  return values;
}

export async function api(path, opts = {}, fetchFn = globalThis.fetch) {
  const r = await fetchFn(path, {
    method: opts.method || "GET",
    headers: opts.body ? { "Content-Type": "application/json" } : {},
    body: opts.body ? JSON.stringify(opts.body) : undefined,
    signal: opts.signal,
  });
  let data = null;
  try {
    data = await r.json();
  } catch (_) {
    data = null;
  }
  if (!r.ok) {
    const msg = (data && (data.error || data.message || data.code)) || `HTTP ${r.status}`;
    throw new Error(msg);
  }
  if (data && typeof data === "object" && data.ok === false) {
    throw new Error(data.error || data.code || "Operation failed");
  }
  return data;
}

export function epToField(ep) {
  switch (ep) {
    case "wb-red": return "wbRed";
    case "wb-green": return "wbGreen";
    case "wb-blue": return "wbBlue";
    case "color-temp": return "colorTemp";
    case "picture-mode": return "pictureMode";
    case "game-mode": return "gameMode";
    case "brightness": return "brightness";
    case "contrast": return "contrast";
    case "saturation": return "saturation";
    case "sharpness": return "sharpness";
    case "hue": return "hue";
    default: return null;
  }
}
