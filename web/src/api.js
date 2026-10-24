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
