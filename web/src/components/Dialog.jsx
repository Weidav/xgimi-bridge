import { useState, useRef, useEffect } from "react";

export function Dialog({ spec, onClose }) {
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

  function cancel() {
    onClose(spec.kind === "prompt" ? null : false);
  }

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
