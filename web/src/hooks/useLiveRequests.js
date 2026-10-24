import { useState, useEffect, useRef, useCallback } from "react";
import { api } from "../api.js";

/**
 * Creates an isolated manager for live-request governance and single-flight key events.
 * Extracted from App.jsx to provide a testable finite-state machine.
 */
export function createLiveRequestManager({
  getConnected = () => true,
  setErr = () => {},
  apiFn = api,
  onPollFastChange = () => {},
} = {}) {
  let liveAbort = new AbortController();
  let inFlight = false;
  let pendingKey = null;
  let circuitOpen = false;

  async function liveCall(path, opts = {}, deadlineMs = 1500) {
    if (!getConnected()) return null;
    const perCall = new AbortController();
    const timer = setTimeout(() => perCall.abort(), deadlineMs);
    const onShared = () => perCall.abort();

    liveAbort.signal.addEventListener("abort", onShared, { once: true });
    try {
      return await apiFn(path, { ...opts, signal: perCall.signal });
    } catch (e) {
      if (e?.name === "AbortError") return null;
      throw e;
    } finally {
      clearTimeout(timer);
      liveAbort.signal.removeEventListener("abort", onShared);
    }
  }

  async function sendKeyDispatch(keyCode) {
    if (!getConnected() || circuitOpen) return;
    if (inFlight) {
      // Hot path during rapid or held presses: replace whatever's pending with the most recent tap.
      pendingKey = keyCode;
      return;
    }
    inFlight = true;
    let current = keyCode;
    try {
      while (current != null) {
        let ok = false;
        try {
          const r = await liveCall(`/api/adb/keyevent/${current}`, { method: "POST" }, 1500);
          ok = r != null; // liveCall returns null on abort
        } catch (e) {
          setErr(String(e));
        }

        if (!ok) {
          // Trip breaker: drop pending and stop firing until polling confirms bridge reachability.
          circuitOpen = true;
          pendingKey = null;
          onPollFastChange(true);
          return;
        }

        if (!getConnected()) {
          pendingKey = null;
          return;
        }

        current = pendingKey;
        pendingKey = null;
      }
    } finally {
      inFlight = false;
    }
  }

  function handleOffline() {
    liveAbort.abort();
    liveAbort = new AbortController();
    pendingKey = null;
    circuitOpen = true;
    onPollFastChange(true);
  }

  function resetCircuit() {
    circuitOpen = false;
    onPollFastChange(false);
  }

  return {
    liveCall,
    sendKeyDispatch,
    handleOffline,
    resetCircuit,
    isCircuitOpen: () => circuitOpen,
    isInFlight: () => inFlight,
    getPendingKey: () => pendingKey,
  };
}

/**
 * Custom React hook for live request governance and iOS Safari parked fetch mitigation.
 */
export function useLiveRequests({ connected, setErr }) {
  const [pollFast, setPollFast] = useState(false);
  const connectedRef = useRef(connected);
  connectedRef.current = connected;

  const setErrRef = useRef(setErr);
  setErrRef.current = setErr;

  const managerRef = useRef(null);
  if (!managerRef.current) {
    managerRef.current = createLiveRequestManager({
      getConnected: () => connectedRef.current,
      setErr: (err) => setErrRef.current?.(err),
      onPollFastChange: (fast) => setPollFast(fast),
    });
  }

  useEffect(() => {
    if (!connected) {
      managerRef.current.handleOffline();
    }
  }, [connected]);

  const liveCall = useCallback((path, opts, deadlineMs) => {
    return managerRef.current.liveCall(path, opts, deadlineMs);
  }, []);

  const sendKeyDispatch = useCallback((keyCode) => {
    return managerRef.current.sendKeyDispatch(keyCode);
  }, []);

  const resetCircuit = useCallback(() => {
    managerRef.current.resetCircuit();
  }, []);

  return {
    liveCall,
    sendKeyDispatch,
    pollFast,
    resetCircuit,
  };
}
