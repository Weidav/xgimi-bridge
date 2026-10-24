import { useState, useRef, useEffect } from "react";
import { formatAppName, sourceTitle } from "../api.js";
import {
  PowerDotIcon,
  AssistantMicIcon,
  FocusIcon,
  KeyboardIcon,
  SourceIcon,
  BackIcon,
  SettingsIcon,
  HomeIcon,
  MinusIcon,
  MuteIcon,
  PlusIcon,
} from "./Icons.jsx";

// Android KeyEvent codes used by the virtual remote.
// SETTINGS = 176 (KEYCODE_SETTINGS) opens the Google TV Settings dashboard.
// FOCUS = 140 (KEYCODE_F10) triggers XgimiGlobalKeyBroadcast.gotoAF on Android 14.
export const KEY = {
  DPAD_UP: 19, DPAD_DOWN: 20, DPAD_LEFT: 21, DPAD_RIGHT: 22, DPAD_CENTER: 23,
  BACK: 4, HOME: 3,
  VOLUME_UP: 24, VOLUME_DOWN: 25, VOLUME_MUTE: 164,
  SETTINGS: 176, ASSIST: 219, FOCUS: 140,
};

/**
 * Hook returning pointer handlers for a key that fires once on press, then repeats while held.
 * Mimics the physical remote's auto-repeat: 380 ms initial delay, then ~12 events/sec.
 */
export function useHoldable(send, opts = {}) {
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

export function HoldButton({ keyCode, sendKey, repeat = true, className = "rc-btn", children, ariaLabel }) {
  const { pressed, handlers } = useHoldable(() => sendKey(keyCode), { repeat });
  return (
    <button className={className + (pressed ? " is-pressed" : "")} {...handlers} aria-label={ariaLabel}>
      {children}
    </button>
  );
}

export function RemoteCard({
  sendKey,
  onSendText,
  onOpenPower,
  onOpenSource,
  busy,
  connected,
  picture,
  inputs,
  state,
}) {
  const runningApp = state?.runningApp || picture?.runningApp;
  const sourceName = connected
    ? (picture ? sourceTitle(picture, inputs, runningApp) : (state?.ready ? (formatAppName(runningApp) || "Connected") : "Connecting..."))
    : "Offline";

  return (
    <section className="rc-wrap" aria-label="Virtual Remote">
      <div className="rc-shell">
        <div className="rc-face">
          {/* Virtual OLED Display */}
          <button
            type="button"
            className={"rc-display" + (connected ? "" : " is-offline")}
            onClick={onOpenSource}
            disabled={!connected || busy}
            title={connected ? "Change input source" : "Projector unreachable"}
            aria-label={connected ? `Current input: ${sourceName}. Tap to change source.` : "Projector unreachable"}
          >
            <div className="rc-display-screen">
              <span className={"rc-display-dot" + (connected ? "" : " is-offline")} />
              <span className="rc-display-title">{sourceName}</span>
              {connected && busy ? (
                <span className="rc-display-busy">SENDING</span>
              ) : (
                <span className="rc-display-spacer" aria-hidden="true" />
              )}
            </div>
          </button>

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
            <HoldButton keyCode={KEY.DPAD_UP} sendKey={sendKey} className="rc-dpad-up" ariaLabel="Up" />
            <HoldButton keyCode={KEY.DPAD_LEFT} sendKey={sendKey} className="rc-dpad-left" ariaLabel="Left" />
            <HoldButton keyCode={KEY.DPAD_CENTER} sendKey={sendKey} repeat={false} className="rc-dpad-center" ariaLabel="OK" />
            <HoldButton keyCode={KEY.DPAD_RIGHT} sendKey={sendKey} className="rc-dpad-right" ariaLabel="Right" />
            <HoldButton keyCode={KEY.DPAD_DOWN} sendKey={sendKey} className="rc-dpad-down" ariaLabel="Down" />
          </div>

          {/* Row below D-pad: Back (left) + Settings (center) + Home (right) */}
          <div className="rc-row rc-mid-row">
            <HoldButton keyCode={KEY.BACK} sendKey={sendKey} repeat={false} className="rc-btn" ariaLabel="Back"><BackIcon /></HoldButton>
            <HoldButton keyCode={KEY.SETTINGS} sendKey={sendKey} repeat={false} className="rc-btn" ariaLabel="Settings"><SettingsIcon /></HoldButton>
            <HoldButton keyCode={KEY.HOME} sendKey={sendKey} repeat={false} className="rc-btn" ariaLabel="Home"><HomeIcon /></HoldButton>
          </div>

          {/* Bottom row: Volume down (left) + Mute (center) + Volume up (right) */}
          <div className="rc-row rc-vol-row">
            <HoldButton keyCode={KEY.VOLUME_DOWN} sendKey={sendKey} className="rc-btn" ariaLabel="Volume down"><MinusIcon /></HoldButton>
            <HoldButton keyCode={KEY.VOLUME_MUTE} sendKey={sendKey} repeat={false} className="rc-btn" ariaLabel="Mute"><MuteIcon /></HoldButton>
            <HoldButton keyCode={KEY.VOLUME_UP} sendKey={sendKey} className="rc-btn" ariaLabel="Volume up"><PlusIcon /></HoldButton>
          </div>
        </div>
      </div>
    </section>
  );
}
