/**
 * Client transport wrapper and picture setting validation utilities.
 */

export const KNOB_DEFINITIONS = [
  { key: "brightness", ep: "brightness", label: "Brightness", accent: "#f5f5f7" },
  { key: "contrast", ep: "contrast", label: "Contrast", accent: "#f5f5f7" },
  { key: "saturation", ep: "saturation", label: "Saturation", accent: "#f5f5f7" },
  { key: "sharpness", ep: "sharpness", label: "Sharpness", accent: "#f5f5f7" },
  { key: "hue", ep: "hue", label: "Hue", accent: "#f5f5f7" },
  { key: "wbRed", ep: "wb-red", label: "Red gain", accent: "#ff453a" },
  { key: "wbGreen", ep: "wb-green", label: "Green gain", accent: "#30d158" },
  { key: "wbBlue", ep: "wb-blue", label: "Blue gain", accent: "#0a84ff" },
];

const EP_TO_FIELD_MAP = Object.fromEntries(KNOB_DEFINITIONS.map((k) => [k.ep, k.key]));
EP_TO_FIELD_MAP["color-temp"] = "colorTemp";
EP_TO_FIELD_MAP["picture-mode"] = "pictureMode";
EP_TO_FIELD_MAP["game-mode"] = "gameMode";

export const WRITABLE_PICTURE_FIELDS = [
  ...KNOB_DEFINITIONS.map((k) => k.key),
  "colorTemp", "pictureMode", "gameMode"
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
  return EP_TO_FIELD_MAP[ep] || null;
}

export function inputToAidlSource(to) {
  if (!to || typeof to !== "string") return null;
  const upper = to.trim().toUpperCase();
  if (upper === "STORAGE" || upper === "HOME" || upper === "APPS") {
    return "E_INPUT_SOURCE_STORAGE";
  }
  if (upper.startsWith("HDMI")) {
    return `E_INPUT_SOURCE_${upper}`;
  }
  return null;
}

export function formatAppName(pkg) {
  if (!pkg || typeof pkg !== "string") return "";
  const trimmed = pkg.trim();
  if (/launcher|katniss|systemui|xgimi\.bridge/i.test(trimmed)) return "";
  const lastDot = trimmed.lastIndexOf(".");
  if (lastDot === -1) {
    return trimmed.charAt(0).toUpperCase() + trimmed.slice(1);
  }
  const beforeLastDot = trimmed.substring(0, lastDot);
  const prevDot = beforeLastDot.lastIndexOf(".");
  const name = prevDot === -1 ? beforeLastDot : beforeLastDot.substring(prevDot + 1);
  if (!name) return "";
  return name.charAt(0).toUpperCase() + name.slice(1);
}

export function sourceTitle(picture, inputs, runningApp) {
  if (!picture) return "";
  const src = picture.currentInputSource;
  if (src === "E_INPUT_SOURCE_STORAGE") {
    const app = formatAppName(runningApp || picture.runningApp);
    return app || "Android TV";
  }
  if (typeof src === "string" && src.startsWith("E_INPUT_SOURCE_HDMI")) {
    const port = Number(src.replace("E_INPUT_SOURCE_HDMI", ""));
    if (Array.isArray(inputs)) {
      for (const i of inputs) {
        if (i.hdmiPortId === port) {
          const name = (i.displayName || i.label || "").trim();
          if (name && !/^hdmi\s*\d+$/i.test(name)) return name;
        }
      }
    }
    return `HDMI ${port}`;
  }
  return src || "";
}
