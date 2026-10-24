export function PowerSheet({ busy, onAction, onClose }) {
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
