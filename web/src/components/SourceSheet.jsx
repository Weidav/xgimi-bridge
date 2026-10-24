import { useState, useEffect } from "react";
import { formatAppName } from "../api.js";

export function inputLabelForPort(inputs, port, fallback) {
  if (!Array.isArray(inputs)) return fallback;
  for (const i of inputs) {
    if (i.hdmiPortId !== port) continue;
    const name = (i.displayName || i.label || "").trim();
    if (name && !/^hdmi\s*\d+$/i.test(name)) return name;
  }
  return fallback;
}

export function SourceSheet({ inputs, picture, runningApp: initialRunningApp, busy, onPick, onClose }) {
  const [runningApp, setRunningApp] = useState(initialRunningApp);
  const current = picture?.currentInputSource;

  useEffect(() => {
    setRunningApp(initialRunningApp);
  }, [initialRunningApp]);

  const appName = formatAppName(runningApp);
  function activeFor(src) {
    return current === src ? " input-btn-active" : "";
  }

  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="source-sheet" role="dialog" aria-modal="true" aria-label="Choose source">
        <h3 className="modal-title">Source</h3>
        <div className="input-rows">
          <button className={"input-btn input-btn-row" + activeFor("E_INPUT_SOURCE_STORAGE")} onClick={() => onPick("STORAGE")} disabled={busy}>
            <span>{appName || "Android TV"}</span>
            {appName && <small>Android TV</small>}
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
