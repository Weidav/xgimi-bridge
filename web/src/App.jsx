import { useEffect, useState, useCallback, useRef } from "react";

const KNOBS = [
  { key: "wbRed",   ep: "wb-red",   label: "Red gain",   accent: "#ff453a" },
  { key: "wbGreen", ep: "wb-green", label: "Green gain", accent: "#30d158" },
  { key: "wbBlue",  ep: "wb-blue",  label: "Blue gain",  accent: "#0a84ff" },
];

const PICTURE_ITEMS = [
  { key: "brightness", ep: "brightness", label: "Brightness", accent: "#f5f5f7" },
  { key: "contrast",   ep: "contrast",   label: "Contrast",   accent: "#f5f5f7" },
  { key: "saturation", ep: "saturation", label: "Saturation", accent: "#f5f5f7" },
  { key: "sharpness",  ep: "sharpness",  label: "Sharpness",  accent: "#f5f5f7" },
  { key: "hue",        ep: "hue",        label: "Hue",        accent: "#f5f5f7" },
];

const PICTURE_MODE_OPTIONS = [
  { value: 1, label: "Movie" },
  { value: 9, label: "Football" },
  { value: 6, label: "Office" },
  { value: 4, label: "Game" },
  { value: 3, label: "User" },
];
const COLOR_TEMP_OPTIONS = [
  { value: 0, label: "System" },
  { value: 1, label: "Warm" },
  { value: 2, label: "Standard" },
  { value: 3, label: "Cold" },
  { value: 4, label: "User" },
];
const GAME_MODE_OPTIONS = [
  { value: 0, label: "Off" },
  { value: 1, label: "Standard" },
  { value: 2, label: "Boost" },
];

async function api(path, opts = {}) {
  const r = await fetch(path, {
    method: opts.method || "GET",
    headers: opts.body ? { "Content-Type": "application/json" } : {},
    body: opts.body ? JSON.stringify(opts.body) : undefined,
    signal: opts.signal,
  });
  const data = await r.json().catch(() => null);
  if (!r.ok) {
    throw new Error((data && data.error) || `HTTP ${r.status}`);
  }
  return data;
}

function sourceTitle(picture, inputs) {
  if (!picture) return "";
  const src = picture.currentInputSource;
  if (src === "E_INPUT_SOURCE_STORAGE") return "Android TV";
  if (typeof src === "string" && src.startsWith("E_INPUT_SOURCE_HDMI")) {
    const port = Number(src.replace("E_INPUT_SOURCE_HDMI", ""));
    return inputLabelForPort(inputs, port, `HDMI ${port}`);
  }
  return src || "";
}

function PinIcon() {
  return (
    <svg className="pin" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 17v5" />
      <path d="M9 10.76V6h-1a2 2 0 0 1 0-4h8a2 2 0 0 1 0 4h-1v4.76a2 2 0 0 0 1.11 1.79l1.78.9A2 2 0 0 1 19 15.24V16a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-.76a2 2 0 0 1 1.11-1.79l1.78-.9A2 2 0 0 0 9 10.76Z" />
    </svg>
  );
}

function inputLabelForPort(inputs, port, fallback) {
  if (!Array.isArray(inputs)) return fallback;
  for (const i of inputs) {
    if (i.hdmiPortId !== port) continue;
    const name = (i.displayName || i.label || "").trim();
    if (name && !/^hdmi\s*\d+$/i.test(name)) return name;
  }
  return fallback;
}

function epToField(ep) {
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

function rgbDot(v) {
  const r = clamp(v?.wbRed), g = clamp(v?.wbGreen), b = clamp(v?.wbBlue);
  return `rgb(${rrr(r)}, ${rrr(g)}, ${rrr(b)})`;
}
function clamp(n) { if (typeof n !== "number") return 50; return Math.max(0, Math.min(100, n)); }
function rrr(n) { return Math.round(80 + (n / 100) * 175); }

// Android KeyEvent codes used by the virtual remote.
// SETTINGS = 176 (KEYCODE_SETTINGS) opens the Google TV Settings dashboard.
// FOCUS = 140 (KEYCODE_F10) triggers XgimiGlobalKeyBroadcast.gotoAF on Android 14.
const KEY = {
  DPAD_UP: 19, DPAD_DOWN: 20, DPAD_LEFT: 21, DPAD_RIGHT: 22, DPAD_CENTER: 23,
  BACK: 4, HOME: 3,
  VOLUME_UP: 24, VOLUME_DOWN: 25, VOLUME_MUTE: 164,
  SETTINGS: 176, ASSIST: 219, FOCUS: 140,
};

export default function App() {
  const [picture, setPicture] = useState(null);
  const [inputs, setInputs] = useState([]);
  const [state, setState] = useState(null);
  const [presets, setPresets] = useState([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(null);
  const [adbAuthorized, setAdbAuthorized] = useState(true); // optimistic — banner only flashes if check fails
  const [authorizing, setAuthorizing] = useState(false);
  const [connected, setConnected] = useState(true);
  const connectedRef = useRef(connected);
  connectedRef.current = connected;
  const [sourceOpen, setSourceOpen] = useState(false);
  const [powerOpen, setPowerOpen] = useState(false);
  const [infoOpen, setInfoOpen] = useState(false);
  const [dialog, setDialog] = useState(null);
  const dialogResolverRef = useRef(null);
  /**
   * Live-only request governance. Two layers:
   *
   *   1. {@link liveAbortRef}: a shared AbortController that the offline-transition effect
   *      flips to abort everything in flight. Lets us *try* to cancel a parked iOS
   *      request — sometimes it actually goes through, but we don't depend on it.
   *
   *   2. {@link inFlightRef} + {@link pendingKeyRef}: a single-flight semaphore for keypresses.
   *      iOS doesn't fail fetch() fast on a dropped radio — it parks the request in the OS
   *      network queue and *replays* the whole queue when the link returns, flooding the
   *      bridge with stale taps. AbortController in JS land doesn't reach into that OS
   *      queue. So we cap the stack: at most ONE keypress can be in flight at a time;
   *      while one is pending, additional taps just overwrite a single "next" slot. The
   *      caller's hold loop can fire as fast as it wants — the network sees one request,
   *      then one more, never a queued backlog.
   */
  const liveAbortRef = useRef(new AbortController());
  const inFlightRef = useRef(false);
  const pendingKeyRef = useRef(null);
  /**
   * Circuit breaker. AbortController in JS only rejects the Promise — on iOS Safari it
   * doesn't always cancel the underlying request that's already been handed to the OS
   * network queue. So if the projector goes offline and we keep firing keyevents, every
   * timeout cycle adds one more parked request to iOS's queue, and they all replay when
   * the radio comes back. Trip this flag the moment ONE keyevent fails — refuse to fire
   * any more keyevents until the polling refresh confirms the bridge is reachable again.
   */
  const circuitOpenRef = useRef(false);
  const [pollFast, setPollFast] = useState(false);

  /**
   * Helper for one-shot control calls (source switch, power) that should only run when
   * the bridge is known-reachable. Skips when offline, gives the request a tight per-call
   * deadline, and silently swallows aborts. Use {@link sendKeyDispatch} for keypresses
   * since those need the single-flight semaphore.
   */
  function liveCall(path, opts = {}, deadlineMs = 1500) {
    if (!connectedRef.current) return Promise.resolve(null);
    const perCall = new AbortController();
    const timer = setTimeout(() => perCall.abort(), deadlineMs);
    const onShared = () => perCall.abort();
    liveAbortRef.current.signal.addEventListener("abort", onShared, { once: true });
    return api(path, { ...opts, signal: perCall.signal })
      .catch((e) => {
        if (e?.name === "AbortError") return null;
        throw e;
      })
      .finally(() => {
        clearTimeout(timer);
        liveAbortRef.current.signal.removeEventListener("abort", onShared);
      });
  }

  /**
   * Single-flight keyevent dispatcher with a circuit-breaker around iOS's parked-fetch
   * queue. First call fires; concurrent calls just overwrite a single "pending" slot. The
   * settled call's loop picks up whatever's pending and fires that once. If any fetch
   * fails (timeout/abort/error), the circuit opens — no more keyevents leave the device
   * until the polling refresh confirms the bridge is reachable again, at which point
   * pollFast also kicks in to bring the recovery latency from 5 s down to ~1 s.
   */
  async function sendKeyDispatch(keyCode) {
    if (!connectedRef.current || circuitOpenRef.current) return;
    if (inFlightRef.current) {
      // Hot path during a held press: replace whatever's pending with the most recent tap.
      pendingKeyRef.current = keyCode;
      return;
    }
    inFlightRef.current = true;
    let current = keyCode;
    try {
      while (current != null) {
        let ok = false;
        try {
          const r = await liveCall(`/api/adb/keyevent/${current}`, { method: "POST" }, 1500);
          ok = r != null;  // liveCall returns null on abort
        } catch (e) {
          setErr(String(e));
        }
        if (!ok) {
          // Trip the breaker. Drop pending; stop firing until polling closes the circuit.
          circuitOpenRef.current = true;
          pendingKeyRef.current = null;
          setPollFast(true);
          return;
        }
        if (!connectedRef.current) {
          pendingKeyRef.current = null;
          return;
        }
        current = pendingKeyRef.current;
        pendingKeyRef.current = null;
      }
    } finally {
      inFlightRef.current = false;
    }
  }

  function openDialog(spec) {
    return new Promise((resolve) => {
      dialogResolverRef.current = resolve;
      setDialog(spec);
    });
  }
  function closeDialog(value) {
    const resolve = dialogResolverRef.current;
    dialogResolverRef.current = null;
    setDialog(null);
    if (resolve) resolve(value);
  }
  const askPrompt = (spec) => openDialog({ kind: "prompt", ...spec });
  const askConfirm = (spec) => openDialog({ kind: "confirm", ...spec });

  // Polling refresh excludes /api/picture: the AIDL getter for picture items returns the
  // slot-0 default rather than what we last wrote, so polling would clobber the slider state.
  // Picture state is updated optimistically from successful writes / preset applies / input switches.
  const refresh = useCallback(async () => {
    try {
      const [s, ps, ins, adb] = await Promise.all([
        api("/api/state"),
        api("/api/presets"),
        api("/api/inputs").catch(() => []),
        api("/api/adb/status").catch(() => null),
      ]);
      setState(s);
      if (Array.isArray(ps)) setPresets(ps);
      setInputs(Array.isArray(ins) ? ins : []);
      if (adb) setAdbAuthorized(!!adb.authorized);
      setConnected(true);
      // Successful round-trip means the bridge is reachable — close the keyevent circuit
      // breaker and step polling back down to the leisurely cadence.
      circuitOpenRef.current = false;
      setPollFast(false);
    } catch (e) {
      // Bridge unreachable — likely the projector is asleep / off-network. Don't surface
      // the raw network error as a red banner every 5 s; the disconnected indicator says
      // it more cleanly.
      setConnected(false);
    }
  }, []);

  async function authorizeAdb() {
    setAuthorizing(true);
    try {
      await api("/api/adb/authorize", { method: "POST" });
      setAdbAuthorized(true);
    } catch (e) {
      setErr(String(e));
    } finally {
      setAuthorizing(false);
    }
  }

  useEffect(() => {
    refresh();
    api("/api/picture").then(setPicture).catch((e) => setErr(String(e)));
    // Tight polling (1 s) when the keyevent circuit is open so we close it within ~1 s
    // of reachability returning instead of waiting up to 5 s. Drops back to 5 s during
    // healthy operation.
    const t = setInterval(refresh, pollFast ? 1000 : 5000);
    return () => clearInterval(t);
  }, [refresh, pollFast]);

  // Errors from a failed action (sendKey, switchInput, etc.) usually surface during a
  // brief outage. Clear them automatically the next time the bridge comes back online so
  // the user doesn't have to dismiss a stale red card by hand.
  useEffect(() => {
    if (connected) setErr(null);
  }, [connected]);

  // The moment we detect the bridge is unreachable, abort every parked live request so
  // they can't replay against the bridge when the radio comes back, drop any held-key
  // tap that was queued in the single-flight slot, trip the keyevent circuit so no new
  // taps fire, and switch polling to the fast 1 s cadence so we close the circuit ~1 s
  // after the link returns rather than waiting up to 5 s.
  useEffect(() => {
    if (!connected) {
      liveAbortRef.current.abort();
      liveAbortRef.current = new AbortController();
      pendingKeyRef.current = null;
      circuitOpenRef.current = true;
      setPollFast(true);
    }
  }, [connected]);

  // While the user is mid-authorize, poll the status endpoint every 1s so we flip the UI
  // off "Waiting…" within ~1s of them tapping Allow on the TV — instead of waiting for the
  // long-running /api/adb/authorize POST to return.
  useEffect(() => {
    if (!authorizing) return;
    let cancelled = false;
    const t = setInterval(async () => {
      try {
        const r = await api("/api/adb/status");
        if (!cancelled && r && r.authorized) {
          setAdbAuthorized(true);
          setAuthorizing(false);
        }
      } catch (_) {}
    }, 1000);
    return () => { cancelled = true; clearInterval(t); };
  }, [authorizing]);

  async function setKnob(ep, value) {
    setBusy(true);
    try {
      await api(`/api/picture/${ep}`, { method: "POST", body: { value } });
      // Optimistic: trust what we wrote, skip the lying getter.
      const fieldKey = epToField(ep);
      if (fieldKey) setPicture((prev) => prev ? { ...prev, [fieldKey]: value } : prev);
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function reset() {
    setBusy(true);
    try {
      await api("/api/picture/reset", { method: "POST", body: { mode: picture?.pictureMode ?? 0, force: false } });
      setPicture(await api("/api/picture"));
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function saveCurrent() {
    const name = await askPrompt({ title: "Save preset", placeholder: "Preset name", okLabel: "Save" });
    if (!name || !name.trim()) return;
    setBusy(true);
    try {
      await api("/api/presets", { method: "POST", body: { name: name.trim(), values: picture } });
      setPresets(await api("/api/presets"));
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function applyPreset(id) {
    const preset = presets.find((x) => x.id === id);
    setBusy(true);
    try {
      await api(`/api/presets/${id}/apply`, { method: "POST" });
      // Optimistic update from preset values (getter is unreliable for picture items).
      if (preset?.values) setPicture((prev) => prev ? { ...prev, ...preset.values } : prev);
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function syncPreset(id) {
    setBusy(true);
    try {
      await api(`/api/presets/${id}/sync`, { method: "POST", body: { values: picture } });
      setPresets(await api("/api/presets"));
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function deletePreset(id, name) {
    const ok = await askConfirm({ title: "Delete preset", message: `Delete "${name}"?`, okLabel: "Delete", danger: true });
    if (!ok) return;
    setBusy(true);
    try {
      await api(`/api/presets/${id}`, { method: "DELETE" });
      setPresets(await api("/api/presets"));
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function switchInput(to) {
    setSourceOpen(false);
    if (!connected) return;
    setBusy(true);
    try {
      const r = await liveCall("/api/input", { method: "POST", body: { to } }, 3000);
      if (r === null) { setBusy(false); return; }
      // The AIDL `currentInputSource` lags (HDMI switch updates it; the Apps path just fires a
      // launcher intent and xgimiservice doesn't notice). Optimistically reflect the user's pick.
      const aidlSource = to === "STORAGE" ? "E_INPUT_SOURCE_STORAGE"
                       : to === "HDMI1"   ? "E_INPUT_SOURCE_HDMI1"
                       : to === "HDMI2"   ? "E_INPUT_SOURCE_HDMI2"
                       : to === "HDMI3"   ? "E_INPUT_SOURCE_HDMI3"
                       : null;
      // Then re-fetch picture (per-source picture-mode/colorTemp may differ); merge so the
      // optimistic source isn't clobbered if AIDL hasn't caught up yet.
      try {
        const p = await api("/api/picture");
        setPicture(aidlSource ? { ...p, currentInputSource: aidlSource } : p);
      } catch (_) {
        if (aidlSource) setPicture((prev) => prev ? { ...prev, currentInputSource: aidlSource } : prev);
      }
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  // KEYCODE_POWER (26). Going through the keyevent path toggles display
  // standby the same way the physical remote's power button does — single press off when
  // on, single press wakes when off.
  async function pressPower() {
    try { await liveCall(`/api/adb/keyevent/26`, { method: "POST" }, 3000); }
    catch (e) { setErr(String(e)); }
  }

  async function handlePowerAction(action) {
    setPowerOpen(false);
    if (action === "standby") {
      await pressPower();
      return;
    }
    if (action === "restart") {
      const ok = await askConfirm({
        title: "Restart projector",
        message: "Reboot the projector now?",
        okLabel: "Restart",
        danger: true,
      });
      if (!ok) return;
    }
    setBusy(true);
    try {
      await liveCall(`/api/power/${action}`, { method: "POST" }, 3000);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function sendKey(keyCode) {
    sendKeyDispatch(keyCode);
  }

  async function sendText() {
    const text = await askPrompt({ title: "Send text", placeholder: "Type text to enter on TV…", okLabel: "Send" });
    if (!text) return;
    setBusy(true);
    try {
      await liveCall("/api/adb/text", { method: "POST", body: { text } }, 5000);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  const onStorage = picture?.currentInputSource === "E_INPUT_SOURCE_STORAGE";

  return (
    <div className="screen">
      <header className="hdr">
        <div className="hdr-row">
          <div>
            <h1>Xgimi Remote</h1>
            <p className={"subtitle" + (connected ? "" : " subtitle-offline")}>
              {connected
                ? (picture ? sourceTitle(picture, inputs) : (state?.ready ? "Connected" : "Connecting..."))
                : "Disconnected — projector unreachable"}
              {connected && busy && " · sending"}
            </p>
          </div>
          <div className="hdr-actions">
            <button
              className="hdr-btn hdr-info"
              onClick={() => setInfoOpen(true)}
              aria-label="Projector info"
              title="Stats for Nerds / Projector Info"
            >
              <InfoIcon />
            </button>
            {!adbAuthorized && (
              <>
                <button className="hdr-btn hdr-src" onClick={() => setSourceOpen(true)} aria-label="Choose source" disabled={busy}>
                  <SourceIcon />
                </button>
                <button className="hdr-btn hdr-power" onClick={() => setPowerOpen(true)} aria-label="Power" disabled={busy}>
                  <PowerIcon />
                </button>
              </>
            )}
          </div>
        </div>
      </header>

      {err && (
        <section className="card">
          <h2>Error</h2>
          <pre className="err">{err}</pre>
          <button className="btn" onClick={() => setErr(null)}>Dismiss</button>
        </section>
      )}

      {adbAuthorized ? (
        <RemoteCard
          sendKey={sendKey}
          onSendText={sendText}
          onOpenPower={() => setPowerOpen(true)}
          onOpenSource={() => setSourceOpen(true)}
          busy={busy}
        />
      ) : (
        <section className="card adb-card">
          <h2>Remote needs access</h2>
          <p className="muted" style={{ marginBottom: 14 }}>
            Tap <b>Authorize</b>. A one-time prompt appears on the projector — accept it with the
            physical remote.
          </p>
          <button className="btn adb-btn" onClick={authorizeAdb} disabled={authorizing}>
            {authorizing ? "Waiting for Allow on TV…" : "Authorize"}
          </button>
        </section>
      )}

      <section className="card display-settings">
        <h2>Display settings</h2>

        <details className="sub-card">
          <summary>
            <h3>Presets</h3>
            <span className="sub-actions">
              <button className="icon-btn icon-accent" title="Save current as new preset" onClick={(e) => { e.preventDefault(); saveCurrent(); }} disabled={busy || !picture}>+</button>
            </span>
          </summary>
          {presets.length === 0 ? (
            <p className="muted">No presets yet. Tune sliders below, then save.</p>
          ) : (
            <ul className="presets">
              {presets.map((p) => (
                <li key={p.id} className="preset">
                  <div className="preset-row">
                    <button className="preset-name" onClick={() => applyPreset(p.id)} disabled={busy}>
                      <span className="dot" style={{ background: rgbDot(p.values) }} />
                      <span>{p.name}</span>
                    </button>
                    <div className="preset-actions">
                      {p.builtin ? (
                        <span className="badge" title="Built-in (read-only)"><PinIcon /></span>
                      ) : (
                        <>
                          <button className="icon-btn" title="Update from current values" onClick={() => syncPreset(p.id)} disabled={busy}>↻</button>
                          <button className="icon-btn icon-danger" title="Delete" onClick={() => deletePreset(p.id, p.name)} disabled={busy}>×</button>
                        </>
                      )}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </details>

        <details className="sub-card">
          <summary>
            <h3>White balance</h3>
            <span className="sub-actions">
              <button className="icon-btn" title="Reset picture mode" onClick={(e) => { e.preventDefault(); reset(); }} disabled={busy || onStorage}>↻</button>
            </span>
          </summary>
          {!picture && <p className="muted">loading…</p>}
          {picture && onStorage && <p className="muted">Not applicable on Apps. Switch to HDMI to adjust.</p>}
          {picture && !onStorage && (
            <>
              <OptionRow label="Color temp" options={COLOR_TEMP_OPTIONS}
                value={picture.colorTemp} disabled={busy}
                onChange={(v) => setKnob("color-temp", v)} />
              {KNOBS.map((k) => (
                <Slider key={k.key} label={k.label} accent={k.accent}
                  value={picture[k.key] ?? 50}
                  onChange={(v) => setKnob(k.ep, v)} />
              ))}
            </>
          )}
        </details>

        <details className="sub-card">
          <summary><h3>Picture</h3></summary>
          {!picture && <p className="muted">loading…</p>}
          {picture && onStorage && <p className="muted">Not applicable on Apps. Switch to HDMI to adjust.</p>}
          {picture && !onStorage && (
            <>
              <OptionRow label="Mode" options={PICTURE_MODE_OPTIONS}
                value={picture.pictureMode} disabled={busy}
                onChange={(v) => setKnob("picture-mode", v)} />
              <OptionRow label="Game mode" options={GAME_MODE_OPTIONS}
                value={picture.gameMode} disabled={busy}
                onChange={(v) => setKnob("game-mode", v)} />
              {PICTURE_ITEMS.map((k) => (
                <Slider key={k.key} label={k.label} accent={k.accent}
                  value={picture[k.key] ?? 50}
                  onChange={(v) => setKnob(k.ep, v)} />
              ))}
            </>
          )}
        </details>

        <button
          type="button"
          className="sub-card sub-card-btn"
          onClick={() => setInfoOpen(true)}
        >
          <span className="sub-card-btn-left">
            <InfoIcon />
            <span>Stats for nerds</span>
          </span>
          <span className="sub-card-btn-meta">Signal · Audio · Optics · Thermal ›</span>
        </button>
      </section>

      {sourceOpen && (
        <SourceSheet
          inputs={inputs}
          picture={picture}
          busy={busy}
          onPick={switchInput}
          onClose={() => setSourceOpen(false)}
        />
      )}

      {powerOpen && <PowerSheet busy={busy} onAction={handlePowerAction} onClose={() => setPowerOpen(false)} />}

      {infoOpen && <InfoSheet onClose={() => setInfoOpen(false)} />}

      {dialog && <Dialog spec={dialog} onClose={closeDialog} />}
    </div>
  );
}

function SourceSheet({ inputs, picture, busy, onPick, onClose }) {
  const current = picture?.currentInputSource;
  function activeFor(src) { return current === src ? " input-btn-active" : ""; }
  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="source-sheet" role="dialog" aria-modal="true" aria-label="Choose source">
        <h3 className="modal-title">Source</h3>
        <div className="input-rows">
          <button className={"input-btn input-btn-row" + activeFor("E_INPUT_SOURCE_STORAGE")} onClick={() => onPick("STORAGE")} disabled={busy}>
            <span>Apps</span><small>Android TV</small>
          </button>
          {[1, 2].map((port) => {
            const cec = inputLabelForPort(inputs, port, "");
            return (
              <button key={port} className={"input-btn input-btn-row" + activeFor(`E_INPUT_SOURCE_HDMI${port}`)} onClick={() => onPick(`HDMI${port}`)} disabled={busy}>
                <span>{cec || `HDMI ${port}`}</span>
                {cec && <small>HDMI {port}</small>}
              </button>
            );
          })}
        </div>
        <div className="modal-actions">
          <button className="modal-btn" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}

function PowerSheet({ busy, onAction, onClose }) {
  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="power-sheet" role="dialog" aria-modal="true" aria-label="Power options">
        <h3 className="modal-title">Power</h3>
        <div className="power-list">
          <button className="power-item" onClick={() => onAction("standby")} disabled={busy}>
            <span className="power-item-label">Standby / Wake</span>
            <span className="power-item-sub">Toggle display sleep (same as physical power button)</span>
          </button>
          <button className="power-item" onClick={() => onAction("led-off")} disabled={busy}>
            <span className="power-item-label">Lamp off</span>
            <span className="power-item-sub">Turn projector LED off while Android keeps running</span>
          </button>
          <button className="power-item" onClick={() => onAction("led-on")} disabled={busy}>
            <span className="power-item-label">Lamp on</span>
            <span className="power-item-sub">Turn projector LED back on</span>
          </button>
          <button className="power-item" onClick={() => onAction("restart")} disabled={busy}>
            <span className="power-item-label" style={{ color: "var(--danger)" }}>Restart</span>
            <span className="power-item-sub">Reboot the projector</span>
          </button>
        </div>
        <div className="modal-actions">
          <button className="modal-btn" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}

function SourceIcon() {
  // Rounded rectangle open on the left edge with a right-pointing arrow entering it,
  // matching the input-source glyph on the physical Xgimi remote.
  return (
    <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M7 7.5V6a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-1.5" />
      <line x1="4" y1="12" x2="14.5" y2="12" />
      <polyline points="11 8.5 14.5 12 11 15.5" />
    </svg>
  );
}

function PowerIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M18.36 6.64a9 9 0 1 1-12.72 0" />
      <line x1="12" y1="2" x2="12" y2="12" />
    </svg>
  );
}

/**
 * Hook returning pointer handlers for a key that fires once on press, then repeats while held.
 * Mimics the physical remote's auto-repeat: 380 ms initial delay, then ~12 events/sec.
 *
 * Also exposes `pressed` state so the caller can apply a CSS class for visual feedback —
 * Mobile Safari swallows :active when pointerdown handlers call preventDefault, so we don't
 * rely on it.
 */
function useHoldable(send, opts = {}) {
  const repeat = opts.repeat !== false;
  const initialDelay = opts.initialDelay ?? 380;
  const interval = opts.interval ?? 80;
  const timerRef = useRef(null);
  const pressedRef = useRef(false);
  const [pressed, setPressed] = useState(false);

  function stop() {
    pressedRef.current = false;
    setPressed(false);
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }

  useEffect(() => stop, []);

  function onPointerDown(e) {
    if (pressedRef.current) return;
    pressedRef.current = true;
    setPressed(true);
    e.preventDefault();
    e.currentTarget.setPointerCapture?.(e.pointerId);
    send();
    if (repeat) {
      timerRef.current = setTimeout(() => {
        timerRef.current = setInterval(send, interval);
      }, initialDelay);
    }
  }

  function onPointerMove(e) {
    if (!pressedRef.current) return;
    const rect = e.currentTarget.getBoundingClientRect();
    if (e.clientX < rect.left || e.clientX > rect.right || e.clientY < rect.top || e.clientY > rect.bottom) {
      stop();
    }
  }

  // We MUST remove the timer on any pointer leave / cancel / up / lost capture / drag-out —
  // otherwise dragging the finger off the button keeps firing.
  return {
    pressed,
    handlers: {
      onPointerDown,
      onPointerMove,
      onPointerUp: stop,
      onPointerCancel: stop,
      onPointerLeave: stop,
      onLostPointerCapture: stop,
    },
  };
}

function HoldButton({ keyCode, sendKey, repeat = true, className = "rc-btn", children, ariaLabel }) {
  const { pressed, handlers } = useHoldable(() => sendKey(keyCode), { repeat });
  return (
    <button className={className + (pressed ? " is-pressed" : "")} {...handlers} aria-label={ariaLabel}>
      {children}
    </button>
  );
}

function RemoteCard({ sendKey, onSendText, onOpenPower, onOpenSource, busy }) {
  return (
    <section className="card rc-card">
      <h2>Remote</h2>
      <div className="rc-shell">
        <div className="rc-face">
          {/* Top row: Power (left) + Google Assistant (right) */}
          <div className="rc-row rc-power-row">
            <button className="rc-btn rc-btn-power" onClick={onOpenPower} disabled={busy} aria-label="Power">
              <PowerDotIcon />
            </button>
            <span className="rc-spacer" aria-hidden="true" />
            <HoldButton keyCode={KEY.ASSIST} sendKey={sendKey} repeat={false} className="rc-btn rc-btn-assist" ariaLabel="Assistant">
              <AssistantMicIcon />
            </HoldButton>
          </div>

          {/* Row above D-pad: Auto-focus (left) + Keyboard (center) + Source (right) */}
          <div className="rc-row rc-top-row">
            <HoldButton keyCode={KEY.FOCUS} sendKey={sendKey} repeat={false} className="rc-btn" ariaLabel="Auto-focus">
              <FocusIcon />
            </HoldButton>
            <button className="rc-btn" onClick={onSendText} disabled={busy} aria-label="Send text">
              <KeyboardIcon />
            </button>
            <button className="rc-btn" onClick={onOpenSource} disabled={busy} aria-label="Choose source">
              <SourceIcon />
            </button>
          </div>

          {/* D-pad ring — bare spun-metal circle with bare concave center OK button */}
          <div className="rc-dpad">
            <HoldButton keyCode={KEY.DPAD_UP}     sendKey={sendKey} className="rc-dpad-up"     ariaLabel="Up" />
            <HoldButton keyCode={KEY.DPAD_LEFT}   sendKey={sendKey} className="rc-dpad-left"   ariaLabel="Left" />
            <HoldButton keyCode={KEY.DPAD_CENTER} sendKey={sendKey} repeat={false} className="rc-dpad-center" ariaLabel="OK" />
            <HoldButton keyCode={KEY.DPAD_RIGHT}  sendKey={sendKey} className="rc-dpad-right"  ariaLabel="Right" />
            <HoldButton keyCode={KEY.DPAD_DOWN}   sendKey={sendKey} className="rc-dpad-down"   ariaLabel="Down" />
          </div>

          {/* Row below D-pad: Back (left) + Settings (center) + Home (right) */}
          <div className="rc-row rc-mid-row">
            <HoldButton keyCode={KEY.BACK}     sendKey={sendKey} repeat={false} className="rc-btn" ariaLabel="Back"><BackIcon /></HoldButton>
            <HoldButton keyCode={KEY.SETTINGS} sendKey={sendKey} repeat={false} className="rc-btn" ariaLabel="Settings"><SettingsIcon /></HoldButton>
            <HoldButton keyCode={KEY.HOME}     sendKey={sendKey} repeat={false} className="rc-btn" ariaLabel="Home"><HomeIcon /></HoldButton>
          </div>

          {/* Bottom row: Volume down (left) + Mute (center) + Volume up (right) */}
          <div className="rc-row rc-vol-row">
            <HoldButton keyCode={KEY.VOLUME_DOWN} sendKey={sendKey} className="rc-btn" ariaLabel="Volume down"><MinusIcon /></HoldButton>
            <HoldButton keyCode={KEY.VOLUME_MUTE} sendKey={sendKey} repeat={false} className="rc-btn" ariaLabel="Mute"><MuteIcon /></HoldButton>
            <HoldButton keyCode={KEY.VOLUME_UP}   sendKey={sendKey} className="rc-btn" ariaLabel="Volume up"><PlusIcon /></HoldButton>
          </div>
        </div>
      </div>
    </section>
  );
}

function PowerDotIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="3.6" fill="#d94038" />
    </svg>
  );
}

function AssistantMicIcon() {
  return (
    <svg width="21" height="21" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="9" y="3" width="6" height="11" rx="3" fill="#4285f4" />
      <path d="M5.5 10.5a6.5 6.5 0 0 0 1.9 4.6" stroke="#ea4335" strokeWidth="2.2" strokeLinecap="round" />
      <path d="M7.4 15.1A6.5 6.5 0 0 0 18.5 10.5" stroke="#fbbc05" strokeWidth="2.2" strokeLinecap="round" />
      <line x1="12" y1="17" x2="12" y2="21" stroke="#34a853" strokeWidth="2.2" strokeLinecap="round" />
    </svg>
  );
}

function KeyboardIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="2" y="5" width="20" height="14" rx="2" />
      <line x1="6" y1="9" x2="6" y2="9" />
      <line x1="10" y1="9" x2="10" y2="9" />
      <line x1="14" y1="9" x2="14" y2="9" />
      <line x1="18" y1="9" x2="18" y2="9" />
      <line x1="6" y1="13" x2="6" y2="13" />
      <line x1="18" y1="13" x2="18" y2="13" />
      <line x1="8" y1="15" x2="16" y2="15" />
    </svg>
  );
}

function SettingsIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  );
}

function FocusIcon() {
  // Viewfinder: four L-shaped corner brackets with a center crosshair, matching the
  // autofocus glyph on the physical Xgimi remote.
  return (
    <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M5 9V5h4" />
      <path d="M15 5h4v4" />
      <path d="M19 15v4h-4" />
      <path d="M9 19H5v-4" />
      <line x1="12" y1="9.5" x2="12" y2="14.5" />
      <line x1="9.5" y1="12" x2="14.5" y2="12" />
    </svg>
  );
}

function BackIcon() {
  // Straight left arrow matching the physical Xgimi remote's Back glyph.
  return (
    <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <line x1="19" y1="12" x2="5" y2="12" />
      <polyline points="11.5 5.5 5 12 11.5 18.5" />
    </svg>
  );
}

function HomeIcon() {
  // Solid pentagon house silhouette with the upper-left roof cutout matching the physical remote.
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
      <path
        fill="currentColor"
        d="M12 4.2L4.2 10.7a1 1 0 0 0-.36.77V19a1.5 1.5 0 0 0 1.5 1.5h13.32A1.5 1.5 0 0 0 20.16 19v-7.53a1 1 0 0 0-.36-.77L12 4.2zm-1.15 3.95a.75.75 0 0 1 .96 1.15l-4.5 3.75a.75.75 0 1 1-.96-1.15l4.5-3.75z"
        fillRule="evenodd"
      />
    </svg>
  );
}

function MuteIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M11 5L6 9H2v6h4l5 4V5z" />
      <line x1="23" y1="9" x2="17" y2="15" />
      <line x1="17" y1="9" x2="23" y2="15" />
    </svg>
  );
}

function MinusIcon() {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true"><line x1="6" y1="12" x2="18" y2="12"/></svg>;
}
function PlusIcon() {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true"><line x1="6" y1="12" x2="18" y2="12"/><line x1="12" y1="6" x2="12" y2="18"/></svg>;
}


function Dialog({ spec, onClose }) {
  const [value, setValue] = useState(spec.defaultValue ?? "");
  const inputRef = useRef(null);
  useEffect(() => {
    if (spec.kind === "prompt") {
      const t = setTimeout(() => inputRef.current?.focus(), 30);
      return () => clearTimeout(t);
    }
  }, [spec.kind]);

  function submit() {
    if (spec.kind === "prompt") onClose(value);
    else onClose(true);
  }
  function cancel() { onClose(spec.kind === "prompt" ? null : false); }

  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) cancel(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={spec.title}>
        {spec.title && <h3 className="modal-title">{spec.title}</h3>}
        {spec.message && <p className="modal-message">{spec.message}</p>}
        {spec.kind === "prompt" && (
          <input
            ref={inputRef}
            className="modal-input"
            type="text"
            value={value}
            placeholder={spec.placeholder || ""}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") submit();
              if (e.key === "Escape") cancel();
            }}
          />
        )}
        <div className="modal-actions">
          <button className="modal-btn" onClick={cancel}>Cancel</button>
          <button className={`modal-btn modal-btn-primary${spec.danger ? " modal-btn-danger" : ""}`} onClick={submit}>
            {spec.okLabel || "OK"}
          </button>
        </div>
      </div>
    </div>
  );
}

function Slider({ label, accent, value, onChange }) {
  const [local, setLocal] = useState(value);
  const committedRef = useRef(value);
  useEffect(() => {
    setLocal(value);
    committedRef.current = value;
  }, [value]);

  function commit(next) {
    if (next !== committedRef.current) {
      committedRef.current = next;
      onChange(next);
    }
  }

  return (
    <div className="slider">
      <div className="slider-row">
        <label>{label}</label>
        <span className="slider-value" style={{ color: accent }}>{local}</span>
      </div>
      <input type="range" min="0" max="100"
        value={local}
        onChange={(e) => setLocal(Number(e.target.value))}
        onMouseUp={(e) => commit(Number(e.target.value))}
        onTouchEnd={(e) => commit(Number(e.target.value))}
        onTouchCancel={(e) => commit(Number(e.target.value))}
        onKeyUp={(e) => commit(Number(e.target.value))}
        style={{ accentColor: accent }} />
    </div>
  );
}

function OptionRow({ label, options, value, onChange, disabled }) {
  return (
    <div className="opt-group">
      <div className="slider-row">
        <label>{label}</label>
      </div>
      <div className="opt-pills">
        {options.map((o) => (
          <button
            key={o.value}
            type="button"
            className={"opt-pill" + (value === o.value ? " opt-pill-active" : "")}
            disabled={disabled}
            onClick={() => onChange(o.value)}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}

function InfoIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.1" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="9.5" />
      <line x1="12" y1="16.5" x2="12" y2="11.5" />
      <circle cx="12" cy="8" r="0.8" fill="currentColor" />
    </svg>
  );
}

const INFO_SECTIONS = [
  {
    id: "display",
    title: "Signal & Display",
    defaultOpen: true,
    fields: [
      ["activeInputSource", "Active Input Source"],
      ["runningApp", "Foreground Package"],
      ["mediaCodecPlayback", "Android TV MediaCodec Playback"],
      ["mediaCodecPipeline", "MediaCodec Pipeline Status"],
      ["hdmiPlayingState", "HDMI Signal State"],
      ["hdmiSignalTiming", "HDMI Input Timing"],
      ["hdmiMode", "HDMI / DVI Protocol"],
      ["videoTiming", "Decoded Video Timing"],
      ["androidDisplayMode", "Android Display Mode"],
      ["dlpOutputTiming", "DLP Output Timing"],
      ["dlpOutputFps", "DLP Refresh Rate"],
      ["dlpXprPixelShift", "4K XPR Pixel Shift"],
      ["panelOutputResolution", "Panel Output Resolution"],
      ["osdUiRenderResolution", "OSD UI Render Size"],
      ["hdrStatus", "HDR / Dynamic Range"],
      ["hdrSupportEnabled", "HDR Support Enabled"],
      ["hdrSettingMode", "HDR Mode Setting"],
      ["dolbyVisionPicMode", "Dolby Vision Mode"],
      ["filmmakerModePlaying", "Filmmaker Mode Active"],
      ["hdrVividPlaying", "HDR Vivid Active"],
      ["imaxEnhancedPlaying", "IMAX Enhanced Active"],
      ["supportedHdrFormats", "Supported HDR Formats"],
      ["panelHdrLuminanceRange", "HDR Luminance Metadata"],
      ["gameLowLatencyMode", "Game Mode / Latency"],
      ["allmEnabled", "ALLM Auto Low Latency"],
      ["allmActive", "ALLM Signal Active"],
      ["vrrState", "VRR Variable Refresh"],
      ["amdFreeSync", "AMD FreeSync"],
      ["hgigMode", "HGiG Tone Mapping"],
      ["memcMotionCompensation", "MEMC Motion Compensation"],
      ["colorSpace", "Color Space / Gamut"],
      ["hdmiColorRange", "HDMI Quantization Range"],
      ["aspectRatio", "Aspect Ratio"],
      ["edidVersion", "HDMI EDID Version"],
      ["mode3d", "3D Display Mode"],
      ["supportedDisplayModes", "Supported Display Modes"],
    ],
  },
  {
    id: "brightness_picture",
    title: "Picture & Brightness",
    defaultOpen: true,
    fields: [
      ["luminancePowerMode", "Brightness Power Mode"],
      ["brightnessLevel", "Brightness Level"],
      ["lightSourcePower", "Light Source (Laser/LED)"],
      ["dualLightMode", "Dual Light Engine Mode"],
      ["lightSourceRgbLevels", "RGB Light Source Drive"],
      ["smartTurboLight", "Smart Turbo Light"],
      ["advancedPerformanceMode", "Advanced Performance Mode"],
      ["pqPerformanceActive", "PQ Performance State"],
      ["androidBacklightValue", "Android Backlight Index"],
      ["pictureMode", "Picture Preset Mode"],
      ["pqBrightness", "PQ Brightness"],
      ["pqContrast", "PQ Contrast"],
      ["pqSaturation", "PQ Saturation"],
      ["pqSharpness", "PQ Sharpness"],
      ["pqHue", "PQ Hue"],
      ["memcDeblur", "MEMC De-blur (MJC)"],
      ["memcDejudder", "MEMC De-judder (MJC)"],
      ["colorTemperatureMode", "Color Temperature Mode"],
      ["whiteBalanceGains", "White Balance Gains (R/G/B)"],
      ["dynamicBlackLevel", "Dynamic Black / Laser Dimming"],
      ["dynamicContrastEnable", "Dynamic Contrast"],
      ["contrastEnhanceLevel", "Local Contrast Enhancement"],
      ["colorEnhance", "Color Enhancement"],
      ["colorEnhancingFilter", "Color Enhancing Filter"],
      ["rainbowEffectReduction", "Rainbow Effect Reduction"],
      ["noiseReduction", "Noise Reduction"],
      ["gammaLevel", "Gamma Curve Index"],
      ["globalDimming", "Global Dimming (PQ)"],
      ["darkDetail", "Dark Detail Enhancement"],
      ["lowBlueLight", "Low Blue Light Filter"],
      ["aiPqEnabled", "AI Picture Quality"],
      ["aisrSuperResolution", "AISR Super Resolution"],
    ],
  },
  {
    id: "audio",
    title: "Audio & Pass-Through",
    defaultOpen: true,
    fields: [
      ["activeOutputDevice", "Active Audio Output"],
      ["digitalOutputPassThroughFormat", "Digital Output / Pass-Through"],
      ["arcOutputFormat", "HDMI ARC / eARC Format"],
      ["spdifOutputFormat", "Optical SPDIF Format"],
      ["earcEnabled", "eARC Support Enabled"],
      ["earcHandshakeStatus", "eARC Handshake State"],
      ["externalSurroundSoundEnabled", "External Surround Sound"],
      ["soundMode", "Sound Effect Mode"],
      ["soundProcessorType", "Audio DSP Processor"],
      ["dolbySurround", "Dolby Surround Upmixer"],
      ["dolbyVirtualSurround", "Dolby Virtual Surround"],
      ["dolbyDialogEnhancer", "Dolby Dialog Enhancer"],
      ["dolbyVolumeLeveler", "Dolby Volume Leveler"],
      ["dtsEffectsEnabled", "DTS Studio Sound"],
      ["dtsTruBass", "DTS TruBass"],
      ["dtsTruSurround", "DTS TruSurround"],
      ["dtsTruVolume", "DTS TruVolume"],
      ["dtsDialogClarity", "DTS Dialog Clarity"],
      ["dtsDrc", "DTS Dynamic Range Control"],
      ["syncImageSoundEffects", "Sync Image & Sound Effects"],
      ["volumeBalanceEnabled", "Auto Volume Balance"],
      ["audioDelayMs", "Audio Output Delay"],
      ["videoDelayMs", "Video Lip-Sync Delay"],
      ["musicVolumeCurrent", "System Media Volume"],
      ["musicMuted", "Audio Muted"],
      ["speakerVolumeSetting", "Internal Speaker Volume"],
      ["hdmiArcVolumeSetting", "HDMI ARC Volume"],
      ["hdmiEarcVolumeSetting", "HDMI eARC Volume"],
      ["amplifierFirmwareVersion", "Amplifier MCU Firmware"],
      ["androidOutputDevices", "Detected Audio Routes"],
    ],
  },
  {
    id: "optics_sensors",
    title: "Optics, Geometry & Sensors",
    defaultOpen: false,
    fields: [
      ["projectionPlacementMode", "Projection Mount Mode"],
      ["keystoneMode", "Keystone Correction Mode"],
      ["opticalZoomStep", "Optical Zoom Motor Step"],
      ["digitalZoomStep", "Digital Zoom Step"],
      ["zoomStep", "Zoom Step"],
      ["focusMotorCurrentStep", "Focus Motor Position"],
      ["focusMotorSpeedPps", "Focus Motor Speed"],
      ["zoomMotorSpeedPps", "Zoom Motor Speed"],
      ["tofDistanceToWall", "ToF Laser Distance to Wall"],
      ["ambientLightSensor", "Ambient Light Sensor"],
      ["ambientLightAdaptation", "Wall Brightness Adaptation"],
      ["eyeProtectionHumanDetect", "Eye Protection Sensor"],
      ["radarDetectionEnabled", "ToF / Radar Presence Sensor"],
      ["autoFocusOnPowerOn", "Auto-Focus on Startup"],
      ["thermalFocusAutoCompensate", "Thermal Focus Creep Comp"],
      ["autoScreenAlignment", "Auto Screen Alignment"],
      ["smartObstacleAvoidance", "Smart Obstacle Avoidance"],
      ["autoWallColorAdapt", "Wall Color Adaptation"],
      ["imuPitchRollAngles", "IMU Pitch / Roll Angles"],
      ["standVerticalAngle", "Gimbal Stand Vertical Angle"],
      ["gimbalPanTiltRoll", "Gimbal Pan / Tilt / Roll"],
      ["lensShiftPositionInfo", "Lens Shift Position"],
      ["lensShiftStep", "Lens Shift Step"],
      ["motionTriggeredAutoKeystone", "Motion Auto-Keystone"],
      ["motionTriggeredAutoFocus", "Motion Auto-Focus"],
      ["autoCeilingReverse", "Auto Ceiling Flip"],
    ],
  },
  {
    id: "thermal_power",
    title: "Thermal, Cooling & Power",
    defaultOpen: false,
    fields: [
      ["systemTemperature", "Primary System Temp"],
      ["overheatWarning", "Overheat Alert"],
      ["humidity", "Internal Humidity"],
      ["fanControlMode", "Fan Control Mode"],
      ["standFanMode", "Stand Fan Active"],
      ["coolingPumpRpm", "Liquid Cooling Pump"],
      ["tecPeltierSpeed", "TEC Peltier Cooler"],
      ["powerSupplyVoltage", "DC Input Voltage"],
      ["adapterPowered", "External AC Adapter"],
      ["powerMode", "Power State Index"],
      ["standbyStage", "Standby Stage"],
      ["ecoState", "Energy Saving / Eco Mode"],
      ["autoEcoMode", "Auto Eco Mode"],
      ["highAltitudeMode", "High Altitude Fan Mode"],
      ["silentMode", "Silent Cooling Mode"],
      ["lowPowerStandbyEnabled", "Low-Power Standby"],
      ["wakeOnLanWow", "Wake-on-Wireless (WoW)"],
      ["machineUsageTime", "Total Projector Hours"],
      ["lightSourceUsageInfo", "Light Source Telemetry"],
    ],
  },
  {
    id: "hdmi_cec",
    title: "HDMI & CEC Topology",
    defaultOpen: false,
    fields: [
      ["hdmiPort1LinkConnected", "HDMI 1 Cable / 5V Link"],
      ["hdmiPort2LinkConnected", "HDMI 2 Cable / 5V Link"],
      ["hdmiAutoSwitch", "HDMI Auto Input Switch"],
      ["cecControlEnabled", "HDMI-CEC Enabled"],
      ["cecAutoWakeupEnabled", "CEC Auto TV Wakeup"],
      ["cecAutoDeviceOffEnabled", "CEC Auto Device Standby"],
      ["earcLocalRole", "HDMI Audio System Role"],
      ["earcHandshakeState", "eARC Link Status"],
      ["connectedCecSummary", "CEC Bus Summary"],
    ],
  },
  {
    id: "system",
    title: "System & Firmware",
    defaultOpen: false,
    fields: [
      ["deviceName", "Projector Name"],
      ["deviceModel", "Hardware Model / ODM"],
      ["xgimiProductCode", "XGIMI Product ID"],
      ["socPlatform", "SoC & Board Platform"],
      ["serialNumber", "Serial Number"],
      ["gmuiVersion", "XGIMI OS / GMUI Version"],
      ["productFirmwareVersion", "OTA Firmware Build"],
      ["androidVersion", "Android TV OS"],
      ["securityPatch", "Android Security Patch"],
      ["buildId", "System Build ID"],
      ["dlpFirmwareVersion", "TI DLP Controller FW"],
      ["whiteBalanceHalVersion", "White Balance HAL"],
      ["bluetoothRemoteFirmware", "Bluetooth Remote FW"],
      ["xgimiApiVersion", "XGIMI API Level"],
      ["systemSubVersions", "Subsystem Versions"],
      ["uptime", "System Uptime"],
      ["memoryUsage", "RAM Utilization"],
      ["storageUsage", "Internal Storage (/data)"],
    ],
  },
];

function formatInfoValue(val) {
  if (val === null || val === undefined || val === "") return null;
  if (typeof val === "boolean") return val ? "Yes" : "No";
  if (Array.isArray(val)) {
    if (val.length === 0) return null;
    if (typeof val[0] === "string" || typeof val[0] === "number") {
      return val.join(", ");
    }
    return JSON.stringify(val);
  }
  if (typeof val === "object") return JSON.stringify(val);
  return String(val);
}

function InfoSheet({ onClose }) {
  const [info, setInfo] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [live, setLive] = useState(false);
  const [filter, setFilter] = useState("");
  const [copied, setCopied] = useState(false);

  const fetchInfo = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    try {
      const data = await api("/api/info");
      setInfo(data);
      setError(null);
    } catch (e) {
      setError(String(e));
    } finally {
      if (!quiet) setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchInfo(false);
  }, [fetchInfo]);

  useEffect(() => {
    if (!live) return;
    const t = setInterval(() => fetchInfo(true), 3000);
    return () => clearInterval(t);
  }, [live, fetchInfo]);

  useEffect(() => {
    function onKey(e) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function copyJson() {
    if (!info) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(info, null, 2));
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch (_) {}
  }

  const summary = info?.summary || {};
  const categories = info?.categories || {};
  const q = filter.trim().toLowerCase();

  return (
    <div
      className="modal-backdrop info-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="info-sheet" role="dialog" aria-modal="true" aria-label="Stats for nerds">
        <div className="info-hdr">
          <div>
            <h3 className="info-title">Stats for nerds</h3>
            <p className="info-subtitle">
              {summary.deviceName || "XGIMI Projector"}
              {info?.collectionMs != null ? ` · polled in ${info.collectionMs} ms` : ""}
            </p>
          </div>
          <div className="info-hdr-actions">
            <button
              type="button"
              className={"info-pill-btn" + (live ? " info-pill-live" : "")}
              onClick={() => setLive((v) => !v)}
              title="Auto-refresh every 3 seconds"
            >
              <span className="info-live-dot" />
              {live ? "Live" : "Auto"}
            </button>
            <button
              type="button"
              className="icon-btn"
              onClick={() => fetchInfo(false)}
              disabled={loading}
              title="Refresh now"
              aria-label="Refresh telemetry"
            >
              ↻
            </button>
            <button
              type="button"
              className="icon-btn"
              onClick={onClose}
              title="Close"
              aria-label="Close"
            >
              ×
            </button>
          </div>
        </div>

        <div className="info-body">
          {error && (
            <div className="info-err">
              <span>{error}</span>
              <button className="modal-btn" onClick={() => fetchInfo(false)}>Retry</button>
            </div>
          )}

          {loading && !info && (
            <p className="muted" style={{ padding: "18px 4px", textAlign: "center" }}>
              Querying projector hardware & HAL services…
            </p>
          )}

          {info && (
            <>
              {/* Top Quick Summary Grid */}
              <div className="info-summary-grid">
                <div className="info-tile">
                  <span className="info-tile-label">Input & Signal</span>
                  <span className="info-tile-val">{summary.inputSource || "—"}</span>
                  <span className="info-tile-sub">{summary.resolution || "—"}</span>
                </div>
                <div className="info-tile">
                  <span className="info-tile-label">Dynamic Range</span>
                  <span className="info-tile-val">{summary.hdrStatus || "SDR"}</span>
                  <span className="info-tile-sub">Picture: {summary.pictureMode || "—"}</span>
                </div>
                <div className="info-tile">
                  <span className="info-tile-label">Light Source</span>
                  <span className="info-tile-val">{summary.brightnessMode || "—"}</span>
                  <span className="info-tile-sub">
                    Lamp: {summary.ledOn === false ? "Off" : "On"}
                    {summary.systemTempC != null ? ` · ${summary.systemTempC}°C` : ""}
                  </span>
                </div>
                <div className="info-tile">
                  <span className="info-tile-label">Audio Route</span>
                  <span className="info-tile-val">{summary.audioOutput || "—"}</span>
                  <span className="info-tile-sub">{summary.digitalAudioFormat || "—"}</span>
                </div>
              </div>

              {/* Filter Input */}
              <div className="info-search-wrap">
                <input
                  type="search"
                  className="info-search"
                  placeholder="Filter settings, sensors, HDMI, HDR, audio…"
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                />
                {filter && (
                  <button
                    type="button"
                    className="info-search-clear"
                    onClick={() => setFilter("")}
                    aria-label="Clear filter"
                  >
                    ×
                  </button>
                )}
              </div>

              {/* Categorized Submenus */}
              <div className="info-sections">
                {INFO_SECTIONS.map((sec) => {
                  const catObj = categories[sec.id] || {};
                  const rows = [];
                  const seenKeys = new Set();

                  for (const [key, label] of sec.fields) {
                    seenKeys.add(key);
                    const formatted = formatInfoValue(catObj[key]);
                    if (formatted === null) continue;
                    if (
                      q &&
                      !label.toLowerCase().includes(q) &&
                      !key.toLowerCase().includes(q) &&
                      !formatted.toLowerCase().includes(q) &&
                      !sec.title.toLowerCase().includes(q)
                    ) {
                      continue;
                    }
                    rows.push({ key, label, value: formatted, raw: catObj[key] });
                  }

                  // Check if special structured arrays match filter
                  const sensors = sec.id === "thermal_power" && Array.isArray(catObj.temperatureSensors)
                    ? catObj.temperatureSensors.filter((s) =>
                        !q ||
                        String(s.name).toLowerCase().includes(q) ||
                        "temperature sensors".includes(q) ||
                        sec.title.toLowerCase().includes(q)
                      )
                    : [];
                  const fans = sec.id === "thermal_power" && Array.isArray(catObj.fans)
                    ? catObj.fans.filter((f) =>
                        !q ||
                        `fan ${f.index + 1}`.includes(q) ||
                        "fan speeds".includes(q) ||
                        sec.title.toLowerCase().includes(q)
                      )
                    : [];
                  const cecDevices = sec.id === "hdmi_cec" && Array.isArray(catObj.cecDevices)
                    ? catObj.cecDevices.filter((d) =>
                        !q ||
                        String(d.displayName || "").toLowerCase().includes(q) ||
                        String(d.physicalAddress || "").toLowerCase().includes(q) ||
                        "cec".includes(q)
                      )
                    : [];
                  const rgbRaw = sec.id === "brightness_picture" ? catObj.rgbLevelsRaw : null;

                  if (rows.length === 0 && sensors.length === 0 && fans.length === 0 && cecDevices.length === 0) {
                    return null;
                  }

                  return (
                    <details
                      key={sec.id}
                      className="sub-card info-sub-card"
                      open={q ? true : sec.defaultOpen}
                    >
                      <summary>
                        <h3>{sec.title}</h3>
                        <span className="info-count-badge">
                          {rows.length + (sensors.length ? 1 : 0) + (fans.length ? 1 : 0)}
                        </span>
                      </summary>

                      <div className="info-section-content">
                        {/* Special RGB laser/LED drive bars in Picture & Brightness */}
                        {rgbRaw && !q && (() => {
                          const maxScale = Math.max(rgbRaw.r || 0, rgbRaw.g || 0, rgbRaw.b || 0) <= 10 ? 10 : (Math.max(rgbRaw.r || 0, rgbRaw.g || 0, rgbRaw.b || 0) <= 100 ? 100 : 1023);
                          return (
                            <div className="info-rgb-bars">
                              {[
                                { k: "r", label: "Red Drive", color: "#ff453a", val: rgbRaw.r },
                                { k: "g", label: "Green Drive", color: "#32d74b", val: rgbRaw.g },
                                { k: "b", label: "Blue Drive", color: "#0a84ff", val: rgbRaw.b },
                              ].map((ch) => (
                                <div key={ch.k} className="info-rgb-item">
                                  <div className="info-rgb-hdr">
                                    <span>{ch.label}</span>
                                    <span>{ch.val}/{maxScale}</span>
                                  </div>
                                  <div className="info-rgb-track">
                                    <div
                                      className="info-rgb-fill"
                                      style={{
                                        width: `${Math.min(100, Math.max(0, ((Number(ch.val) || 0) / maxScale) * 100))}%`,
                                        background: ch.color,
                                      }}
                                    />
                                  </div>
                                </div>
                              ))}
                            </div>
                          );
                        })()}

                        {/* Special Temperature Sensor Chips in Thermal */}
                        {sensors.length > 0 && (
                          <div className="info-chip-group">
                            <div className="info-chip-group-title">Hardware Temperature Sensors</div>
                            <div className="info-chip-grid">
                              {sensors.map((s) => {
                                const temp = Number(s.tempC) || 0;
                                const hot = temp >= 65 ? " info-chip-warn" : "";
                                return (
                                  <div key={s.index} className={"info-chip" + hot}>
                                    <span className="info-chip-name">{s.name}</span>
                                    <span className="info-chip-val">{s.tempC}°C</span>
                                  </div>
                                );
                              })}
                            </div>
                          </div>
                        )}

                        {/* Special Fan Speed Chips in Thermal */}
                        {fans.length > 0 && (
                          <div className="info-chip-group">
                            <div className="info-chip-group-title">Cooling Fans</div>
                            <div className="info-chip-grid">
                              {fans.map((f) => {
                                const v = Number(f.rpm) || 0;
                                return (
                                  <div key={f.index} className="info-chip">
                                    <span className="info-chip-name">Fan {f.index + 1}</span>
                                    <span className="info-chip-val">
                                      {v <= 100 ? `${v}% duty` : `${v} RPM`}
                                    </span>
                                  </div>
                                );
                              })}
                            </div>
                          </div>
                        )}

                        {/* Special Connected CEC Devices Cards in HDMI & CEC */}
                        {cecDevices.length > 0 && (
                          <div className="info-chip-group">
                            <div className="info-chip-group-title">Discovered HDMI-CEC Topology</div>
                            <div className="info-cec-list">
                              {cecDevices.map((d, idx) => (
                                <div key={idx} className="info-cec-card">
                                  <div className="info-cec-main">
                                    <span className="info-cec-name">{d.displayName || "CEC Device"}</span>
                                    <span className={"info-cec-pwr" + (d.powerStatus === 0 ? " is-on" : "")}>
                                      {d.powerStatus === 0 ? "Active / On" : "Standby"}
                                    </span>
                                  </div>
                                  <div className="info-cec-meta">
                                    <span>HDMI Port {d.portId}</span>
                                    <span>Addr {d.physicalAddress}</span>
                                    <span>Logical #{d.logicalAddress}</span>
                                    {d.vendorId ? <span>Vendor 0x{Number(d.vendorId).toString(16)}</span> : null}
                                  </div>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}

                        {/* Key-Value Telemetry Rows */}
                        <div className="info-kv-list">
                          {rows.map((r) => (
                            <div key={r.key} className="info-kv-row">
                              <span className="info-kv-label">{r.label}</span>
                              <span className="info-kv-val">{r.value}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    </details>
                  );
                })}

                {/* Raw Telemetry Inspector */}
                <details className="sub-card info-sub-card">
                  <summary>
                    <h3>Raw HAL & Android Telemetry (JSON)</h3>
                    <span className="sub-actions">
                      <button
                        type="button"
                        className="info-copy-btn"
                        onClick={(e) => {
                          e.preventDefault();
                          copyJson();
                        }}
                      >
                        {copied ? "Copied" : "Copy JSON"}
                      </button>
                    </span>
                  </summary>
                  <div className="info-section-content">
                    <pre className="info-raw-pre">{JSON.stringify(info.raw || info, null, 2)}</pre>
                  </div>
                </details>
              </div>
            </>
          )}
        </div>

        <div className="info-footer">
          <button type="button" className="modal-btn" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

