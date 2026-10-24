import { useEffect, useState, useCallback, useRef } from "react";
import { api, extractWritableValues, epToField, KNOB_DEFINITIONS, inputToAidlSource } from "./api.js";
import { useLiveRequests } from "./hooks/useLiveRequests.js";
import { PinIcon, InfoIcon } from "./components/Icons.jsx";
import { Slider, OptionRow } from "./components/Controls.jsx";
import { Dialog } from "./components/Dialog.jsx";
import { SourceSheet } from "./components/SourceSheet.jsx";
import { PowerSheet } from "./components/PowerSheet.jsx";
import { RemoteCard } from "./components/Remote.jsx";
import { InfoSheet } from "./components/InfoSheet.jsx";

const KNOBS = KNOB_DEFINITIONS.filter((k) => k.key.startsWith("wb"));
const PICTURE_ITEMS = KNOB_DEFINITIONS.filter((k) => !k.key.startsWith("wb"));

const PICTURE_MODE_OPTIONS = [
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

function rgbDot(v) {
  const r = clamp(v?.wbRed), g = clamp(v?.wbGreen), b = clamp(v?.wbBlue);
  return `rgb(${rrr(r)}, ${rrr(g)}, ${rrr(b)})`;
}
function clamp(n) { if (typeof n !== "number") return 50; return Math.max(0, Math.min(100, n)); }
function rrr(n) { return Math.round(80 + (n / 100) * 175); }

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
  const { liveCall, sendKeyDispatch, pollFast, resetCircuit } = useLiveRequests({ connected, setErr });
  const [sourceOpen, setSourceOpen] = useState(false);
  const [powerOpen, setPowerOpen] = useState(false);
  const [infoOpen, setInfoOpen] = useState(false);
  const [dialog, setDialog] = useState(null);
  const dialogResolverRef = useRef(null);

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
      resetCircuit();
    } catch (e) {
      // Bridge unreachable — likely the projector is asleep / off-network. Don't surface
      // the raw network error as a red banner every 5 s; the disconnected indicator says
      // it more cleanly.
      setConnected(false);
    }
  }, [resetCircuit]);

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
      } catch (_) { }
    }, 1000);
    return () => { cancelled = true; clearInterval(t); };
  }, [authorizing]);

  const knobQueueRef = useRef(new Map());
  const knobInFlightRef = useRef(false);

  const processKnobQueue = useCallback(async () => {
    if (knobInFlightRef.current) return;
    knobInFlightRef.current = true;
    try {
      while (knobQueueRef.current.size > 0) {
        const entry = knobQueueRef.current.entries().next().value;
        if (!entry) break;
        const [ep, targetValue] = entry;
        knobQueueRef.current.delete(ep);

        setBusy(true);
        let retries = 0;
        let success = false;
        while (retries < 3 && !success) {
          try {
            await api(`/api/picture/${ep}`, { method: "POST", body: { value: targetValue } });
            const fieldKey = epToField(ep);
            if (fieldKey) {
              setPicture((prev) => (prev ? { ...prev, [fieldKey]: targetValue } : prev));
            }
            success = true;
          } catch (e) {
            if (knobQueueRef.current.has(ep)) {
              break;
            }
            const isBusy = e.message && (e.message.includes("503") || e.message.toLowerCase().includes("busy"));
            if (isBusy && retries < 2) {
              retries++;
              await new Promise((r) => setTimeout(r, 100 * retries));
            } else {
              setErr(String(e));
              break;
            }
          }
        }
      }
    } finally {
      knobInFlightRef.current = false;
      setBusy(false);
      if (knobQueueRef.current.size > 0) {
        processKnobQueue();
      }
    }
  }, []);

  const setKnob = useCallback((ep, value) => {
    knobQueueRef.current.set(ep, value);
    processKnobQueue();
  }, [processKnobQueue]);

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
      const values = extractWritableValues(picture);
      await api("/api/presets", { method: "POST", body: { name: name.trim(), values } });
      setPresets(await api("/api/presets"));
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function applyPreset(id) {
    const preset = presets.find((x) => x.id === id);
    setBusy(true);
    try {
      const res = await api(`/api/presets/${id}/apply`, { method: "POST" });
      if (res && res.after) {
        setPicture(res.after);
      } else if (preset?.values) {
        setPicture((prev) => prev ? { ...prev, ...preset.values } : prev);
      }
    } catch (e) {
      try {
        const fresh = await api("/api/picture");
        if (fresh) setPicture(fresh);
      } catch (_) {}
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function syncPreset(id) {
    const preset = presets.find((x) => x.id === id);
    if (preset?.builtin) {
      setErr("Built-in presets are read-only");
      return;
    }
    setBusy(true);
    try {
      const values = extractWritableValues(picture);
      await api(`/api/presets/${id}/sync`, { method: "POST", body: { values } });
      setPresets(await api("/api/presets"));
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function deletePreset(id, name) {
    const preset = presets.find((x) => x.id === id);
    if (preset?.builtin) {
      setErr("Built-in presets cannot be deleted");
      return;
    }
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
      const aidlSource = inputToAidlSource(to);
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
      const res = await liveCall(`/api/power/${action}`, { method: "POST" }, 3000);
      if (res && res.pending) {
        setTimeout(() => {
          api("/api/state").then(setState).catch(() => {});
        }, 1500);
      }
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
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
      {err && (
        <section className="card">
          <h2>Error</h2>
          <pre className="err">{err}</pre>
          <button className="btn" onClick={() => setErr(null)}>Dismiss</button>
        </section>
      )}

      {adbAuthorized ? (
        <RemoteCard
          sendKey={sendKeyDispatch}
          onSendText={sendText}
          onOpenPower={() => setPowerOpen(true)}
          onOpenSource={() => setSourceOpen(true)}
          busy={busy}
          connected={connected}
          picture={picture}
          inputs={inputs}
          state={state}
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
          {picture && onStorage && <p className="muted">Not applicable on Android TV. Switch to HDMI to adjust.</p>}
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
          {picture && onStorage && <p className="muted">Not applicable on Android TV. Switch to HDMI to adjust.</p>}
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
          runningApp={state?.runningApp || picture?.runningApp}
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

