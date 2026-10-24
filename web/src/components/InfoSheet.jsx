import { useState, useEffect, useCallback } from "react";
import { api } from "../api.js";
import { INFO_SECTIONS, formatInfoValue } from "../constants/infoSections.js";

export function InfoSheet({ onClose }) {
  const [info, setInfo] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [live, setLive] = useState(false);
  const [filter, setFilter] = useState("");
  const [copyStatus, setCopyStatus] = useState(null);

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
    const t = setInterval(() => fetchInfo(true), 10000);
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
    const text = JSON.stringify(info.raw || info, null, 2);
    let ok = false;
    if (navigator?.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        ok = true;
      } catch (_) {
        ok = false;
      }
    }
    if (!ok) {
      try {
        const ta = document.createElement("textarea");
        ta.value = text;
        ta.setAttribute("readonly", "");
        ta.style.position = "absolute";
        ta.style.left = "-9999px";
        document.body.appendChild(ta);
        ta.select();
        ok = document.execCommand("copy");
        document.body.removeChild(ta);
      } catch (_) {
        ok = false;
      }
    }
    if (ok) {
      setCopyStatus("copied");
      setTimeout(() => setCopyStatus(null), 1800);
    } else {
      setCopyStatus("failed");
      setTimeout(() => setCopyStatus(null), 3000);
    }
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
              title="Auto-refresh every 10 seconds"
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
                        {copyStatus === "copied" ? "Copied" : copyStatus === "failed" ? "Copy failed" : "Copy JSON"}
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
