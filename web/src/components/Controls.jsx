import { useState, useRef, useEffect } from "react";

export function Slider({ label, accent, value, onChange }) {
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
      <input
        type="range"
        min="0"
        max="100"
        value={local}
        onChange={(e) => setLocal(Number(e.target.value))}
        onMouseUp={(e) => commit(Number(e.target.value))}
        onTouchEnd={(e) => commit(Number(e.target.value))}
        onTouchCancel={(e) => commit(Number(e.target.value))}
        onKeyUp={(e) => commit(Number(e.target.value))}
        style={{ accentColor: accent }}
      />
    </div>
  );
}

export function OptionRow({ label, options, value, onChange, disabled }) {
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
