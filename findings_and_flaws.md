# XGIMI Bridge: Codebase Audit, Flaws, and Architectural Findings

This document summarizes findings, architectural flaws, bugs, duplicate code, over-engineering, and single-use micro-functions identified across the **XGIMI Bridge** project (`app/`, `web/`, and `homeassistant/`).

**Scope:** `app/src/`, `app/build.sh`, `app/install.sh`, `app/AndroidManifest.xml`, `web/src/`, `web/index.html`, `web/vite.config.js`, `web/package.json`, `homeassistant/custom_components/`, `homeassistant/pyproject.toml`, and `README.md`. Build outputs (`app/build/`, `app/assets/web/`), `node_modules/`, and `.venv/` are intentionally excluded.

---

## 1. Critical Bugs & Runtime Flaws

### 1.1 Silent `SecurityException` in Input Switch (`killBackgroundProcesses`)
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L347-L349)
- **Flaw**: `handleInputSwitch()` attempts to kill the HDMI playback process:
  ```java
  ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
  am.killBackgroundProcesses("com.google.android.tv.inputplayer");
  ```
  Calling `killBackgroundProcesses` requires the manifest permission `android.permission.KILL_BACKGROUND_PROCESSES`. This permission is **missing** from [`app/AndroidManifest.xml`](file:///workspaces/xgimi-bridge/app/AndroidManifest.xml).
- **Impact**: Android throws a `SecurityException`, which is caught by `catch (Throwable ignore) {}`. The process is never killed, causing the HDMI TvView pipeline to potentially hold background hardware decoders when switching back to Android TV launcher.

### 1.2 Unsynchronized Check in `LocalAdbClient.warmUpAsync()`
- **File**: [`app/src/com/xgimi/bridge/LocalAdbClient.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/LocalAdbClient.java#L93-L114)
- **Flaw**: `warmUpAsync()` tests socket state outside of `dispatcherLock`:
  ```java
  if (dispSock != null && !dispSock.isClosed() && dispLocalId > 0) return;
  ```
  Inside the spawned worker thread, it executes `isAuthorized()`, which establishes a new TCP socket to `127.0.0.1:5555` and carries out a full ADB handshake without locking, while `ensureDispatcher()` locks `dispatcherLock`. If another thread calls `injectKey()` simultaneously, concurrent socket connections and state resets occur.

### 1.3 Presets Inefficient Parsing & Recursive Sanitization Loop
- **File**: [`app/src/com/xgimi/bridge/PresetStore.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/PresetStore.java#L35-L47)
- **Flaw**: `load()` unconditionally calls `ensureBuiltins()`. Inside `ensureBuiltins()`, it reads the file again (`readRaw()`), modifies values, and saves. Furthermore, `create()`, `update()`, `syncFromCurrent()`, `delete()`, `apply()`, and `uniqueIdFor()` all invoke `load()`.
- **Impact**: A single preset operation parses `presets.json` from disk multiple times, sanitizes and verifies built-ins repeatedly, and triggers redundant file I/O.

### 1.4 Blocking Sleep on HTTP Worker Pool Threads
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L313-L317) & [`app/src/com/xgimi/bridge/PresetStore.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/PresetStore.java#L262-L270)
- **Flaw**: `Thread.sleep(60)` and `Thread.sleep(150)` are invoked in retry loops on HTTP request handler threads. When applying a preset with multiple knobs, thread execution blocks synchronously for 1 to 2 seconds, tying up the fixed `MiniHttp` worker threads (`ThreadPoolExecutor` with max 64 threads).

### 1.5 Offline Remote Dependency on External Google Fonts
- **File**: [`web/index.html`](file:///workspaces/xgimi-bridge/web/index.html#L14-L16)
- **Flaw**: The web app loads Google Fonts over the public internet:
  ```html
  <link href="https://fonts.googleapis.com/css2?family=Silkscreen:wght@400;700&display=swap" rel="stylesheet" />
  ```
- **Impact**: The bridge is explicitly designed as an offline local-LAN remote control. If the local network has no active internet connection or during WAN outages, font loading blocks or delays page styling.

### 1.6 Hardcoded Private Developer IP in Home Assistant Component
- **File**: [`homeassistant/custom_components/xgimi_bridge/const.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/const.py#L6)
- **Flaw**: `DEFAULT_HOST = "192.168.3.17"` is hardcoded as default for new installations. It should be empty or localhost, prompting the user for their projector's IP rather than defaulting to the author's local subnet.

### 1.7 Home Assistant options flow crashes on current HA
- **File**: [`homeassistant/custom_components/xgimi_bridge/config_flow.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/config_flow.py#L70-L72)
- **Flaw**:
  ```python
  def __init__(self, config_entry: config_entries.ConfigEntry) -> None:
      self.config_entry = config_entry
  ```
  In current Home Assistant, `OptionsFlow.config_entry` is a **read-only property** (no setter) and is explicitly documented as unavailable during `__init__`. Assigning to it raises `AttributeError: property 'config_entry' of 'XgimiOptionsFlowHandler' object has no setter`, so **Configure** on the device page is broken.
- **Verified**: `OptionsFlow.config_entry.fset` is `None` in the installed HA version.
- **Fix**: delete the `__init__` entirely and use the inherited `self.config_entry` property inside `async_step_init`.

### 1.8 "Copy JSON" silently does nothing on the LAN
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L1378-L1387)
- **Flaw**: `copyJson()` calls `navigator.clipboard.writeText(...)`. The Clipboard API requires a **secure context**; `http://<projector-ip>:8080` is not one, so `navigator.clipboard` is `undefined` and the `catch (_) {}` swallows the failure. The button flips to "Copied" only on success, so it appears dead.
- **Compounded by**: `body { user-select: none; }` in [`web/src/index.css`](file:///workspaces/xgimi-bridge/web/src/index.css#L33) prevents manually selecting the raw telemetry `<pre>`, so there is no fallback path at all.
- **Fix**: fall back to a hidden `<textarea>` + `document.execCommand("copy")`, and set `user-select: text` on `.info-raw-pre`.

### 1.9 `/api/state` reports a hardcoded port
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L87)
- **Flaw**: `.put("port", 8080)` is hardcoded even though `MiniHttp` is constructed with a configurable port and exposes `getListeningPort()`. If the port ever changes, the API lies.

### 1.10 Malformed JSON is silently treated as an empty body
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L454-L466)
- **Flaw**: `readJsonBody()` catches `JSONException`, logs the raw body at INFO, and returns `new JSONObject()`. Callers then report `"missing value"` / `"missing to"` instead of `"invalid JSON"`. It also logs arbitrary user text — including `/api/adb/text` payloads — to logcat.

### 1.11 Preset apply reports success even when writes fail to converge
- **File**: [`app/src/com/xgimi/bridge/PresetStore.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/PresetStore.java#L241-L296)
- **Flaw**: `writeAndVerify()` retries 4×, then logs an error and returns `void`; `applyValues()` ignores the outcome; `apply()` always returns `{"ok": true}`. `writePictureItem()` likewise discards the boolean returned by `setIntInt`. A preset can silently fail to apply while the UI shows success.

### 1.12 `reset` echoes a sentinel value
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L289-L333)
- **Flaw**: `int value = body.optInt("value", Integer.MIN_VALUE)` is included in the reply for every knob, including `reset`, which returns `"value": -2147483648`.

### 1.13 No range validation on picture knobs
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L289-L333)
- **Flaw**: Any int (negative, >100) is forwarded to the AIDL HAL. The web UI clamps to 0–100 and HA `number` entities enforce 0–100, but direct HTTP calls do not.

### 1.14 Power on/off is a check-then-toggle race
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L248-L287)
- **Flaw**: `handlePower()` reads `isAwake()` and then injects `KEYCODE_POWER`, which is a **toggle**. Two concurrent requests, or a state change between read and inject, flip the projector the wrong way. It also returns `"awake": true/false` unconditionally, regardless of the actual result.

### 1.15 `KeyDispatcher` wake listener is unauthenticated and ignores the request
- **File**: [`app/src/com/xgimi/bridge/KeyDispatcher.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/KeyDispatcher.java#L108-L143)
- **Flaw**: `startWakeServer()` binds `0.0.0.0:8081` and wakes the projector on **any** TCP connection without reading the request. The HA coordinator POSTs to `/wake`, but the path and verb are never inspected. Anyone on the LAN can wake the projector. The server socket is never closed and runs on a daemon thread with no lifecycle.

### 1.16 Data races on `LocalAdbClient` dispatcher fields
- **File**: [`app/src/com/xgimi/bridge/LocalAdbClient.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/LocalAdbClient.java#L93-L114)
- **Flaw**: `dispSock`, `dispLocalId`, and `dispRemoteId` are non-volatile and read outside `dispatcherLock` in `warmUpAsync()` and `isAuthorized()`. The unsynchronized fast-path checks can observe stale values and spawn a second dispatcher.

### 1.17 `KeyDispatcher` telemetry caches are unsynchronized statics
- **File**: [`app/src/com/xgimi/bridge/KeyDispatcher.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/KeyDispatcher.java#L258-L263)
- **Flaw**: `cachedCec`, `cachedCecTs`, `cachedTof`, `cachedAmbient`, and `cachedTofTs` are static, non-volatile, and mutated from whichever HTTP worker thread calls `/api/info`. Concurrent requests can race and publish partially-updated caches.

### 1.18 `ProjectorInfoCollector` duplicate settings key
- **File**: [`app/src/com/xgimi/bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java#L775-L812)
- **Flaw**: `collectSettings()` reads `picture_advanced_performance_mode` in both the `globalIntKeys` and `globalStrKeys` loops. The string read overwrites the int under the same `global.` prefix, so `advancedPerformanceMode` (`optInt(...) == 1`) can silently become false when the stored value is a string.

### 1.19 `BridgeService` holds a wake lock forever
- **File**: [`app/src/com/xgimi/bridge/BridgeService.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/BridgeService.java#L66-L86)
- **Flaw**: `acquireLocks()` takes a non-reference-counted `PARTIAL_WAKE_LOCK` and a `WIFI_MODE_FULL_LOW_LATENCY` lock with no timeout, released only in `onDestroy`. This keeps the CPU and Wi-Fi radio at full power permanently — a significant idle-power cost on a projector.

### 1.20 `BridgeService` has no recovery if the HTTP server fails to bind
- **File**: [`app/src/com/xgimi/bridge/BridgeService.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/BridgeService.java#L33-L40)
- **Flaw**: `onCreate()` catches `IOException`, logs it, and leaves the service running with `server == null`. `START_STICKY` does not re-run `onCreate`, so the bridge stays dead until the process is killed.

### 1.21 Exported components with no permission
- **File**: [`app/AndroidManifest.xml`](file:///workspaces/xgimi-bridge/app/AndroidManifest.xml#L18-L27)
- **Flaw**: `BridgeJobService` is declared `android:exported="true"` (JobScheduler does not require it), and `LauncherActivity` is exported with no permission. Any app on the device can trigger the job or start the activity.

### 1.22 Unused / misleading manifest permissions
- **File**: [`app/AndroidManifest.xml`](file:///workspaces/xgimi-bridge/app/AndroidManifest.xml#L6-L14)
- **Flaw**:
  - `ACCESS_NETWORK_STATE` is declared but never used (no `ConnectivityManager`).
  - `SYSTEM_ALERT_WINDOW` is declared and granted by `install.sh`, but no overlay window is ever created; the README attributes the input-switch BAL bypass to it, yet the code never uses it.
  - `KILL_BACKGROUND_PROCESSES` is **missing** despite `killBackgroundProcesses()` being called (see 1.1).

### 1.23 No authentication or CSRF protection beyond a same-origin check
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L64-L79)
- **Flaw**: Any device on the LAN can call every endpoint, including `/api/power/restart`, `/api/adb/text`, and `/api/presets/*`. The only guard is an `Origin`/`Host` comparison for mutating methods, which a non-browser client trivially omits. This is a deliberate LAN-trust model, but it should be documented as such — the README only discusses ADB exposure.

### 1.24 `serveAsset` loads every asset fully into memory per request
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L473-L506)
- **Flaw**: `serveAsset()` copies the asset into a `ByteArrayOutputStream`, then `MiniHttp` writes it. The 262 KB JS bundle is re-read and re-copied on every cold request, and responses are never gzip-compressed. `MiniHttp.newFixedLengthResponse(..., InputStream, long)` exists but is unused.

### 1.25 Route matching is prefix-based and ambiguous
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L146-L150)
- **Flaw**: `uri.startsWith("/api/input")` also matches `/api/inputs` for POST; `uri.startsWith("/api/presets")` matches `/api/presetsfoo`; `uri.startsWith("/api/adb/")` matches any method. A route table with exact/pattern matching would remove the ambiguity.

### 1.26 `MiniHttp` parses query parameters and fragments that nothing consumes
- **File**: [`app/src/com/xgimi/bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java#L124-L150)
- **Flaw**: `Request.query` and `Request.parms` are populated for every request but never read by `HttpServer`. `Request` also has an unused 4-arg constructor and a set of getters that duplicate public fields.

### 1.27 `MiniHttp` supports chunked request bodies that no client sends
- **File**: [`app/src/com/xgimi/bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java#L380-L470)
- **Flaw**: ~90 lines of chunked-transfer decoding (including trailer parsing) are dead weight for a browser/HA client that always sends `Content-Length`. The 64 KB body cap is fine, but the complexity is not justified.

### 1.28 `MiniHttp` error path duplicates the response writer
- **File**: [`app/src/com/xgimi/bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java#L694-L706)
- **Flaw**: `sendError()` re-implements status line, headers, date, and body emission instead of building a `Response` and using the normal writer. It also omits `Allow` on 405 and always closes the connection.

### 1.29 `MiniHttp` `readLine` reads one byte at a time
- **File**: [`app/src/com/xgimi/bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java#L660-L680)
- **Flaw**: `readLine()` calls `in.read()` per byte. It is wrapped in a `BufferedInputStream`, so syscalls are amortized, but the per-byte loop is still the hot path for every header line.

### 1.30 `MiniHttp.stop()` does not await executor termination
- **File**: [`app/src/com/xgimi/bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java#L196-L220)
- **Flaw**: `executor.shutdownNow()` is called but not awaited; in-flight handlers can still touch `openSockets`/`serverSocket` after `stop()` returns. `BridgeService.onDestroy()` then unbinds AIDL while handlers may still be running.

### 1.31 `LauncherActivity` is a full programmatic UI for a one-shot message
- **File**: [`app/src/com/xgimi/bridge/LauncherActivity.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/LauncherActivity.java#L18-L70)
- **Flaw**: ~90 lines build a `LinearLayout`/`TextView` screen and enumerate network interfaces just to show "Service started" and a URL. A `Toast` plus the existing foreground notification would do the same job with far less code.

### 1.32 `build.sh` is fragile
- **File**: [`app/build.sh`](file:///workspaces/xgimi-bridge/app/build.sh)
- **Flaw**:
  - Unquoted `$OUT`, `$PROJ`, `$PLATFORM`, and `find` expansions break on paths with spaces.
  - `-source 1.8 -target 1.8` with JDK 17 emits warnings and does not use `--release`.
  - `zip -r ... assets/` can include stale files if `app/assets/web` was not cleaned (the web build does clean it, but `build.sh` does not verify).
  - The keystore is generated silently with a fixed password; `keytool` output is discarded, so failures are hard to diagnose.

### 1.33 `install.sh` assumes tools and timing
- **File**: [`app/install.sh`](file:///workspaces/xgimi-bridge/app/install.sh)
- **Flaw**: No check that `build/bridge.apk` exists before `adb install`; `pkill` may not exist on all Android builds (the `|| true` hides it); `sleep 2` is a fixed race between `am start` and `cmd jobscheduler run`; `adb devices` parsing assumes the default format.

### 1.34 Version drift across the three deliverables
- Android `versionName="0.2"`, web `0.0.2`, HA `manifest.json` `1.0.0`, HA `pyproject.toml` `0.1.0`. There is no single source of truth and no release automation.

### 1.35 No tests, no CI, no license
- No unit/integration tests for `MiniHttp`, `PresetStore`, `LocalAdbClient`, the HA coordinator, or the React app.
- No `.github/workflows`.
- No `LICENSE` file despite public GitHub releases.

### 1.36 README drift
- **File**: [`README.md`](file:///workspaces/xgimi-bridge/README.md)
- The layout tree omits `homeassistant/`, `web/public/`, and `findings_and_flaws.md`.
- The build one-liner is repeated verbatim in two sections.
- The "Disconnected badge in the header" does not exist; the remote display shows "Offline".
- `POST /api/input` is documented with `HOME`, but the response always says `"to":"STORAGE"`.
- The custom-keycode table duplicates `App.jsx` `KEY` and HA `button.py`; there is no shared source.
- `CAST_WAKE_PORT` (8010) and `REMOTE_WAKE_PORT` (6465) are used by HA but undocumented.

---

## 2. Duplicate Code & Shared Code Opportunities

### 2.1 Complete Duplication of Raw MediaTek IPq Binder IPC (~65 lines)
- **Files**:
  - [`app/src/com/xgimi/bridge/KeyDispatcher.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/KeyDispatcher.java#L658-L721) (`collectMtkPqInfo()`)
  - [`app/src/com/xgimi/bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java#L1037-L1098) (`queryMtkPqFallback()`)
- **Issue**: Both methods execute the exact same AIDL transaction codes (Transaction 54 and Transaction 59) against `vendor.mediatek.hardware.pq.IPq/default`, unpacking byte returns, string vectors, and integer values byte-by-byte into a JSONObject.
- **Recommendation**: Deduplicate into a shared helper method or extract into `AidlClient` / `MtkPqClient`.

### 2.2 Duplicate File Read/Write Operations
- **Files**:
  - `LocalAdbClient.java` (`readFile` / `writeFile`)
  - `PresetStore.java` (`readRaw` / `save`)
  - `HttpServer.java` (`serveAsset` stream-to-byte buffering)
- **Issue**: Every class implements its own `byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) != -1) ...` loop.
- **Recommendation**: Create a centralized static utility (e.g. `IoUtils.readFully(InputStream)` / `IoUtils.writeBytes(File, byte[])`).

### 2.3 Duplicate JSON String Escaping & Error Formatting
- **Files**:
  - [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L202-L214) (`jsonString`) & line 470 (`errJson`)
  - [`app/src/com/xgimi/bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java#L708-L720) (`escapeJson`) & line 697 (`sendError`)
- **Issue**: Identical character-by-character string builders escaping quotes, backslashes, line breaks, and formatting `\u%04x` hex codes.
- **Recommendation**: Keep a single `escapeJson` implementation in `MiniHttp` or reuse `JSONObject.quote()`.

### 2.4 Duplicate Running Application Lookups
- **Files**:
  - [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L221-L237) (`getRunningApp`)
  - [`app/src/com/xgimi/bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java#L88-L89) & lines 850-895
- **Issue**: Both independently perform reflection calls on `android.os.SystemProperties.get("sys.mtk.running.application")` and fallback to `Settings.Global.getString("running_package_name")`.

### 2.5 CSS: split rules, dead selectors, and one real override
- **File**: [`web/src/index.css`](file:///workspaces/xgimi-bridge/web/src/index.css)
- **Correction to an earlier draft**: most of these are *not* duplicate declarations — they are the same selector split across two blocks, which is legal CSS but hurts readability:
  - `.modal` at line 447 (layout) and line 605 (`position: relative`).
  - `.sub-card > summary` at line 363 (layout) and line 407 (`gap: 8px`).
  - `.sub-card > summary > h3` at line 377 (typography) and line 411 (`flex: 1`).
  - `.err` at line 71 (shared with `.state`) and line 79 (color) — intentional grouping.
  - `.rc-dpad-right` at line 894 (shared `inset: 0` group) and line 913 (`clip-path`) — not a duplicate.
- **Real problems**:
  - `.btn` is declared twice (lines 181 and 196) with **conflicting** `background`. The second rule wins, so `background: var(--accent)` in the first block is dead code.
  - `.state` (line 70) is never used in `App.jsx` — dead selector.
  - `--bg` (line 2) is never referenced — dead custom property.
  - `--panel` and `--radius` are each used exactly once; `--panel-blur` twice. Fine, but the token set is inconsistent.
  - No `prefers-reduced-motion` handling despite several transitions.
  - Only one breakpoint (`min-width: 560px`) for a UI that targets phones, tablets, and desktop.

### 2.6 Dead Code in React Icons
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L733-L741)
- **Issue**: `PowerIcon()` is fully declared with SVG paths but is never referenced anywhere in the component tree (`PowerDotIcon()` is used instead).

### 2.7 Home Assistant reverse dictionaries are fine — the real duplication is cross-language
- **File**: [`homeassistant/custom_components/xgimi_bridge/const.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/const.py#L32-L48)
- **Issue**: `PICTURE_MODE_REV`, `COLOR_TEMP_REV`, and `GAME_MODE_REV` are derived from the forward maps, which is the right pattern. The problem is that the *forward* maps are a third copy of the same enum table that also exists in `App.jsx` and `ProjectorInfoCollector.java` (see 2.9).

### 2.8 Two incompatible HDR enum mappings in the same file
- **File**: [`app/src/com/xgimi/bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java#L957-L996) and [line 1105](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java#L1105-L1115)
- **Issue**: `resolveHdrStatus()` maps the MediaTek PQ `hdrType` as `1=HDR10, 2=Dolby Vision, 3=Technicolor, 4=HLG, 5=HDR10+, 6=HDR Vivid`. `formatHdrType()` maps the GmTvManager `hdrType` as `1=HDR10, 2=HLG, 3=Dolby Vision, 4=HDR10+, 5=HDR Vivid`. Both values are called `hdrType` and both feed the same user-facing label, so a wrong source selection silently reports the wrong dynamic range (e.g. HLG shown as Dolby Vision).
- **Fix**: Rename the fields (`mtkHdrType` vs `gmpfHdrType`), keep one mapping table per enum space, and document which source wins.

### 2.9 Picture / color-temperature / game-mode tables exist in three languages
- **Files**:
  - [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L17-L35) — `PICTURE_MODE_OPTIONS`, `COLOR_TEMP_OPTIONS`, `GAME_MODE_OPTIONS`
  - [`homeassistant/custom_components/xgimi_bridge/const.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/const.py#L25-L48) — `PICTURE_MODES`, `COLOR_TEMPS`, `GAME_MODES`
  - [`app/src/com/xgimi/bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java#L1149-L1183) — `formatPictureMode`, `formatColorTemp`, `formatGameMode`
- **Issue**: The same enum labels are maintained three times. They already disagree: the server knows modes `0 (Standard)`, `2 (Vivid)`, `5 (Monitor)`, `14 (Filmmaker)`, `18 (IMAX)`, but both clients only offer `1, 9, 6, 4, 3`. Selecting a projector mode outside that set leaves the UI with no active pill and HA with `current_option = None`.
- **Fix**: Serve the option tables from the bridge (`GET /api/enums`) and have both clients consume them, or generate all three from one schema file.

### 2.10 Keycode tables exist in three places
- **Files**:
  - [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L122-L129) — `KEY`
  - [`homeassistant/custom_components/xgimi_bridge/button.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/button.py#L24-L45) — inline keycodes
  - [`README.md`](file:///workspaces/xgimi-bridge/README.md) — "Custom keycodes" table
- **Issue**: Three copies, already inconsistent: HA exposes `MENU` (82) which the web remote does not; the web remote exposes `ASSIST` (219) which HA does not.
- **Fix**: One `keycodes.json` consumed by both clients (or a `/api/keycodes` endpoint).

### 2.11 The knob list is encoded in five places
- **Files**:
  - [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L3-L15) — `KNOBS` + `PICTURE_ITEMS`
  - [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L95-L110) — `epToField`
  - [`homeassistant/custom_components/xgimi_bridge/const.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/const.py#L50-L59) — `SLIDERS`
  - [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L289-L333) — `handlePictureSet` switch
  - [`app/src/com/xgimi/bridge/PresetStore.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/PresetStore.java#L241-L257) + `WRITABLE` — `applyValues` + whitelist
- **Issue**: Adding one knob requires editing five places in three languages. The endpoint name (`wb-red`), JSON key (`wbRed`), AIDL transaction, and preset key must all stay in sync manually.
- **Fix**: A single declarative table on the server (`{key, endpoint, tx, arg}`) that drives `handlePictureSet`, `applyValues`, `WRITABLE`, and is exposed to clients.

### 2.12 Awake / running-app logic duplicated
- **Files**:
  - [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L216-L237) — `isAwake()`, `getRunningApp()`
  - [`app/src/com/xgimi/bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java#L88-L89) and `collectSystemResources()` — `interactiveAwake`, `runningApp`
- **Issue**: Both independently reflect on `android.os.SystemProperties.get("sys.mtk.running.application")` and fall back to `Settings.Global.getString("running_package_name")`. `isAwake()` and `interactiveAwake` are the same `PowerManager.isInteractive()` call.
- **Fix**: One `DeviceState` helper used by both.

### 2.13 `Map` → `JSONObject` conversion duplicated three times
- **Files**:
  - [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L131-L139)
  - [`app/src/com/xgimi/bridge/PresetStore.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/PresetStore.java#L228-L236)
  - [`app/src/com/xgimi/bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java#L40-L50)
- **Issue**: The same `if (value instanceof Map) new JSONObject((Map<?,?>) value)` loop appears three times.
- **Fix**: `static JSONObject toJson(Map<String,Object>)` in one place.

### 2.14 `put` / `putSafe` helpers duplicated
- **Files**: [`ProjectorInfoCollector.put`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java#L1368-L1374) and [`KeyDispatcher.putSafe`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/KeyDispatcher.java#L222-L245)
- **Issue**: Both swallow `JSONException` and skip nulls; `putSafe` additionally normalizes `Byte`/`Float`/`Double` and strips NULs. Two behaviors, two names, same job.

### 2.15 Binder reflection helpers duplicated
- **Files**: [`AidlClient`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/AidlClient.java#L218-L277) (`callBinderInt`, `callBinderIntWithIntArg`, `callBinderBool`, `callBinderString`, `callBinderStringWithStringArg`) and [`KeyDispatcher`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/KeyDispatcher.java#L162-L220) (`callNoArg`, `callIntArg`, `callIntIntArg`, `callByteArg`, `getFieldVal`)
- **Issue**: Ten near-identical reflection wrappers. `AidlClient` also has five public `getInt`/`getString`/`getIntWithIntArg`/`setInt`/`setIntInt` methods that repeat the same `Parcel.obtain / writeInterfaceToken / transact / readException / recycle` boilerplate.
- **Fix**: One `transact(IBinder, String desc, int code, ParcelWriter, ParcelReader)` helper; one `invoke(Object, String, Class<?>[], Object...)` helper.

### 2.16 `LocalAdbClient` one-shot methods and retry wrappers are copy-paste
- **File**: [`app/src/com/xgimi/bridge/LocalAdbClient.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/LocalAdbClient.java#L116-L260)
- **Issue**:
  - `injectKey` / `injectText` / `queryHardwareInfo` are identical except for the payload and expected response.
  - `injectKeyOnce` / `injectTextOnce` / `queryHardwareInfoOnce` are identical except for the payload, matcher, and timeout.
  - `authorize()` and `isAuthorized()` duplicate socket creation, handshake, and cleanup.
- **Fix**: `sendAndAwait(String payload, Predicate<String> match, long timeoutMs)` and `withDispatcherRetry(Callable<T>)`.

### 2.17 The same formatter is called twice for one value
- **File**: [`app/src/com/xgimi/bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java)
- **Issue**: `formatDigitalAudioFormat(...)` is computed for `catAudio` and again for `summary`; `formatEarcStatus(...)` is computed for `catAudio` and again for `catCec`; `formatArcType(...)` is called directly and from inside `formatDigitalAudioFormat`. Pure recomputation, and a chance for the two copies to diverge.

### 2.18 Three Home Assistant button classes for one behavior
- **File**: [`homeassistant/custom_components/xgimi_bridge/button.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/button.py#L48-L110)
- **Issue**: `XgimiKeyButton`, `XgimiPowerActionButton`, and `XgimiRestartButton` all do `async_press → coordinator.async_post_json(path)`. Only the path and metadata differ.
- **Fix**: One `XgimiActionButton(coordinator, suffix, name, path, icon, device_class=None, entity_category=None)`.

### 2.19 Power switch and power binary sensor expose the same state
- **Files**: [`switch.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/switch.py#L36-L40) and [`binary_sensor.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/binary_sensor.py#L36-L40)
- **Issue**: Both read `coordinator.data["awake"]`. The binary sensor adds no information and doubles the entity count on the device page.

### 2.20 HDMI `getHdmiDeviceInfo` reflection duplicated
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L93-L130) and [`findHdmiPassthroughInputId`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L375-L395)
- **Issue**: Both reflectively call `getHdmiDeviceInfo()` → `getPortId()` / `getDisplayName()`. The `/api/inputs` handler also inlines 30 lines of reflection inside `handleApi`.
- **Fix**: One `HdmiInput` helper that enumerates `TvInputInfo` once.

### 2.21 `PresetStore` and `HttpServer` both encode the knob → AIDL mapping
- **Files**: [`HttpServer.handlePictureSet`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L289-L333) and [`PresetStore.applyValues`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/PresetStore.java#L241-L257)
- **Issue**: The same `wb-red → TX_SET_WB_RED_GAIN`, `brightness → TX_SET_PICTURE_ITEM(0)` mapping is written twice, with different verification behavior (HTTP path writes once; preset path retries/verifies). A knob added to one path silently does nothing in the other.

### 2.22 `App.jsx` duplicates the server's input-source mapping
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L411-L438)
- **Issue**: `switchInput()` re-implements the `STORAGE/HDMI1/HDMI2/HDMI3 → E_INPUT_SOURCE_*` mapping that already exists in `ProjectorInfoCollector.formatInputSource()` and `const.py` `INPUT_SOURCES`. Three copies.

### 2.23 `App.jsx` `epToField` duplicates `KNOBS` / `PICTURE_ITEMS`
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L95-L110)
- **Issue**: The endpoint → state-key mapping is a third representation of the knob list, after `KNOBS`/`PICTURE_ITEMS` and the server switch. A lookup object derived from the same array would remove it.

### 2.24 `MiniHttp` has two response writers
- **File**: [`app/src/com/xgimi/bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java#L694-L706)
- **Issue**: `sendError()` hand-writes the status line, `Content-Type`, `Content-Length`, `Date`, `Connection`, and body. The normal path does the same thing 200 lines earlier. Any header change must be made twice.

### 2.25 `MiniHttp` `Request`/`Response` expose both public fields and getters
- **File**: [`app/src/com/xgimi/bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java#L60-L150)
- **Issue**: `Request` has public `method/uri/query/parms/headers/body` **and** `getMethod()/getUri()/getQuery()/getParms()/getHeaders()/getBody()`. `Response` has public fields **and** `getStatus()/getContentType()/getBody()/getHeaders()`. None of the getters are used. `Response.Status.getRequestStatus()` is also unused.

### 2.26 `AidlClient` public getters duplicate its private helpers
- **File**: [`app/src/com/xgimi/bridge/AidlClient.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/AidlClient.java#L218-L357)
- **Issue**: `getInt`/`getString`/`getIntWithIntArg`/`setInt`/`setIntInt` are the throwing variants of `callBinderInt`/`callBinderString`/`callBinderIntWithIntArg`/etc. Two error models (throw vs return null) for the same transaction, chosen ad hoc by callers.

### 2.27 `HttpServer.serveAsset` re-implements the stream-copy helper
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java#L473-L506)
- **Issue**: It copies the asset into a `ByteArrayOutputStream` with an 8 KB buffer, while `MiniHttp.newFixedLengthResponse(Status, String, InputStream, long)` already does exactly that — and is never called.

### 2.28 Verified dead-code inventory
Each item below was confirmed to have **zero** call sites outside its own declaration.

| Symbol                                                                                                                                                                                                                                                                                                                                                                                                            | File                                    | Notes                                                          |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- | -------------------------------------------------------------- |
| `AidlClient.readSysFsViaAidl(String)`                                                                                                                                                                                                                                                                                                                                                                             | `AidlClient.java:214`                   | Never called.                                                  |
| `AidlClient.TX_SET_GAME_MODE_OPTION_FOR_SOURCE`                                                                                                                                                                                                                                                                                                                                                                   | `AidlClient.java:82`                    | Constant only; the getter counterpart is used.                 |
| `MiniHttp.isAlive()`                                                                                                                                                                                                                                                                                                                                                                                              | `MiniHttp.java:225`                     | Never called.                                                  |
| `MiniHttp.getListeningPort()`                                                                                                                                                                                                                                                                                                                                                                                     | `MiniHttp.java:229`                     | Never called — and `/api/state` hardcodes 8080 instead (1.9).  |
| `MiniHttp.Response.Status.getRequestStatus()`                                                                                                                                                                                                                                                                                                                                                                     | `MiniHttp.java:66`                      | Never called.                                                  |
| `MiniHttp.Response.Status.getDescription()`                                                                                                                                                                                                                                                                                                                                                                       | `MiniHttp.java:70`                      | Never called.                                                  |
| `MiniHttp.Request(Method, String, Map, byte[])`                                                                                                                                                                                                                                                                                                                                                                   | `MiniHttp.java:124`                     | Unused constructor overload.                                   |
| `MiniHttp.Request.getMethod/getUri/getQuery/getParms/getHeaders/getBody`                                                                                                                                                                                                                                                                                                                                          | `MiniHttp.java:130-150`                 | All unused; callers use the public fields.                     |
| `MiniHttp.Response.getStatus/getContentType/getBody/getHeaders`                                                                                                                                                                                                                                                                                                                                                   | `MiniHttp.java:90-104`                  | All unused; callers use the public fields.                     |
| `MiniHttp.newFixedLengthResponse(Status, String, InputStream, long)`                                                                                                                                                                                                                                                                                                                                              | `MiniHttp.java:733`                     | Never called (see 2.27).                                       |
| `App.jsx` `PowerIcon()`                                                                                                                                                                                                                                                                                                                                                                                           | `App.jsx:733`                           | Never rendered.                                                |
| `App.jsx` `sendKey()`                                                                                                                                                                                                                                                                                                                                                                                             | `App.jsx:470`                           | Pure alias for `sendKeyDispatch`.                              |
| `App.jsx` `pressPower()`                                                                                                                                                                                                                                                                                                                                                                                          | `App.jsx:440`                           | Only called from `handlePowerAction`.                          |
| CSS `.state`                                                                                                                                                                                                                                                                                                                                                                                                      | `index.css:70`                          | No matching class in `App.jsx`.                                |
| CSS `--bg`                                                                                                                                                                                                                                                                                                                                                                                                        | `index.css:2`                           | Never referenced.                                              |
| `ProjectorInfoCollector.resolvePictureMode(..., isInternalStorage)`                                                                                                                                                                                                                                                                                                                                               | `ProjectorInfoCollector.java:998`       | Third parameter is never read.                                 |
| `ProjectorInfoCollector.resolveHdrStatus(..., isInternalStorage)`                                                                                                                                                                                                                                                                                                                                                 | `ProjectorInfoCollector.java:957`       | Last parameter is never read.                                  |
| `ProjectorInfoCollector.formatMtkColorTemp` / `formatDolbyVisionMode` / `formatLuminanceMode` / `formatPictureMode` / `formatGameMode` / `formatMemcLevel` / `formatColorSpace` / `formatHdmiRange` / `formatAspectRatio` / `formatEdidVersion` / `format3dMode` / `formatAudioOutputDevice` / `formatSpdifType` / `formatSoundMode` / `formatSoundProcessType` / `formatProjectorPutMode` / `formatKeystoneMode` | `ProjectorInfoCollector.java:1005-1361` | Each has exactly one call site (see 3.1).                      |
| `coordinator.py` `_last_data["online"]`                                                                                                                                                                                                                                                                                                                                                                           | `coordinator.py:37-119`                 | Written 5×, read once internally, never exposed (3.24).        |
| `config_flow.py` `errors` dict                                                                                                                                                                                                                                                                                                                                                                                    | `config_flow.py:28`                     | Created, passed, never populated (3.28).                       |

---

## 3. Over-Engineered Architecture & Single-Use Micro-Functions

### 3.1 Single-use micro-functions that should be inlined
Each of these is a one- or two-line wrapper with exactly one call site. They add indirection without reuse.

| Function                  | File / line                        | Body                                       | Verdict                                                                           |
| ------------------------- | ---------------------------------- | ------------------------------------------ | --------------------------------------------------------------------------------- |
| `KeyCharacterMapSource()` | `KeyDispatcher.java:146`           | `return KeyCharacterMap.VIRTUAL_KEYBOARD;` | Called twice; inline the constant.                                                |
| `isGetOrHead(Method)`     | `HttpServer.java:60`               | `m == GET \|\| m == HEAD`                  | Called 5×; make it `Method.isSafe()` on the enum instead of a free function.      |
| `nonEmpty(a, b)`          | `ProjectorInfoCollector.java:644`  | 5-line null/blank ternary                  | Called 3×; a shared `firstNonBlank` utility is fine, but the name hides the trim. |
| `rrr(n)`                  | `App.jsx:117`                      | `Math.round(80 + (n/100)*175)`             | Only used by `rgbDot`; inline.                                                    |
| `clamp(n)`                | `App.jsx:116`                      | `Math.max(0, Math.min(100, n))`            | Only used by `rgbDot`; inline.                                                    |
| `epToField(ep)`           | `App.jsx:95`                       | 15-line `switch`                           | Replace with a lookup object derived from `KNOBS`/`PICTURE_ITEMS`.                |
| `sendKey(keyCode)`        | `App.jsx:470`                      | `sendKeyDispatch(keyCode)`                 | Pure alias; delete and pass `sendKeyDispatch` directly.                           |
| `pressPower()`            | `App.jsx:440`                      | one `liveCall`                             | Only called from `handlePowerAction`; inline.                                     |
| `formatDolbyVisionMode`   | `ProjectorInfoCollector.java:1117` | 8-case switch                              | One call site; fold into the caller or a table.                                   |
| `formatMtkColorTemp`      | `ProjectorInfoCollector.java:1026` | 5-case switch                              | One call site.                                                                    |
| `formatLuminanceMode`     | `ProjectorInfoCollector.java:1129` | 12-case switch                             | One call site.                                                                    |
| `formatPictureMode`       | `ProjectorInfoCollector.java:1149` | 10-case switch                             | One call site.                                                                    |
| `formatColorTemp`         | `ProjectorInfoCollector.java:1165` | 5-case switch                              | Two call sites, both in the same method.                                          |
| `formatGameMode`          | `ProjectorInfoCollector.java:1176` | 3-case switch                              | One call site.                                                                    |
| `formatMemcLevel`         | `ProjectorInfoCollector.java:1185` | 5-case switch                              | One call site.                                                                    |
| `formatColorSpace`        | `ProjectorInfoCollector.java:1207` | 6-case switch                              | One call site.                                                                    |
| `formatHdmiRange`         | `ProjectorInfoCollector.java:1219` | 3-case switch                              | One call site.                                                                    |
| `formatAspectRatio`       | `ProjectorInfoCollector.java:1228` | 5-case switch                              | One call site.                                                                    |
| `formatEdidVersion`       | `ProjectorInfoCollector.java:1239` | 4-case switch                              | One call site.                                                                    |
| `format3dMode`            | `ProjectorInfoCollector.java:1249` | 5-case switch                              | One call site.                                                                    |
| `formatAudioOutputDevice` | `ProjectorInfoCollector.java:1262` | 6-case switch                              | One call site.                                                                    |
| `formatArcType`           | `ProjectorInfoCollector.java:1292` | 5-case switch                              | Two call sites (one nested).                                                      |
| `formatSpdifType`         | `ProjectorInfoCollector.java:1303` | 3-case switch                              | One call site.                                                                    |
| `formatEarcStatus`        | `ProjectorInfoCollector.java:1313` | 4-case switch                              | Two call sites.                                                                   |
| `formatSoundMode`         | `ProjectorInfoCollector.java:1323` | 5-case switch                              | One call site.                                                                    |
| `formatSoundProcessType`  | `ProjectorInfoCollector.java:1334` | 3-case switch                              | One call site.                                                                    |
| `formatProjectorPutMode`  | `ProjectorInfoCollector.java:1343` | 4-case switch                              | One call site.                                                                    |
| `formatKeystoneMode`      | `ProjectorInfoCollector.java:1353` | 4-case switch                              | One call site.                                                                    |
| `formatOffLowMedHigh`     | `ProjectorInfoCollector.java:1196` | 4-case switch                              | 5 call sites — this one is justified.                                             |
| `fmtDouble`               | `ProjectorInfoCollector.java:1363` | 2-line rounding                            | 8 call sites — justified.                                                         |

**Better than inlining the 20+ enum formatters**: replace them with a single `Map<Integer,String>` table per enum, or a `formatEnum(int, String[] labels)` helper. That removes ~250 lines and makes the label sets data instead of control flow.

### 3.2 `ProjectorInfoCollector` is a 1,374-line god class
- **File**: [`app/src/com/xgimi/bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java)
- **Issue**: One static method (`collectAll`) builds seven nested JSON categories inline (~450 lines), then the file continues with framework collectors, settings collectors, sysprop collectors, resource collectors, 20+ enum formatters, and a raw MediaTek PQ binder client. It has no instance state, no tests, and no seams.
- **Recommendation**: Split into `collectors/` (Display, Audio, Optics, Thermal, Cec, System), `formatters/`, and `MtkPqClient`. Each collector should return a `JSONObject` and be independently testable.

### 3.3 `KeyDispatcher` mixes four responsibilities
- **File**: [`app/src/com/xgimi/bridge/KeyDispatcher.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/KeyDispatcher.java) (786 lines)
- **Issue**: It is simultaneously (a) the stdin key/text injector, (b) an HTTP wake server on 8081, (c) a GMPF reflection telemetry collector across nine services, and (d) a `dumpsys hdmi_control` parser with regexes. It runs as shell uid via `app_process`, so it cannot use the app's normal classes — but that does not justify four unrelated jobs in one file.
- **Recommendation**: Keep `main()` + injection in `KeyDispatcher`; move telemetry to `ShellTelemetry` and the wake server to `WakeServer`, all in the same APK/classpath.

### 3.4 `MiniHttp` is a hand-rolled HTTP server with unused features
- **File**: [`app/src/com/xgimi/bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java) (753 lines)
- **Issue**: It implements chunked decoding, query parsing, absolute-URI handling, fragment stripping, HTTP/1.0 keep-alive negotiation, a 64-thread `SynchronousQueue` pool, and a `ThreadLocal<SimpleDateFormat>` — for a LAN API that only ever receives `GET`/`POST` with `Content-Length` from a browser and Home Assistant. Roughly 40% of the file is unreachable in practice.
- **Recommendation**: Either drop the unused paths (chunked, query, absolute URI, HTTP/1.0) or use `com.sun.net.httpserver.HttpServer`, which ships with Android and removes ~600 lines.

### 3.5 `App.jsx` is a 1,728-line monolith
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx)
- **Issue**: One file contains the API client, request-governance state machine, 12 action handlers, the remote control, 14 SVG icon components, three modal sheets, a dialog system, sliders, and a 236-line `INFO_SECTIONS` table. There is no component directory, no prop types, and no tests.
- **Recommendation**: `api.js`, `hooks/useLiveRequests.js`, `components/Remote.jsx`, `components/InfoSheet.jsx`, `components/DisplaySettings.jsx`, `components/icons.jsx`, `constants.js`.

### 3.6 The live-request governance layer is over-engineered
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L140-L250)
- **Issue**: ~110 lines of comments and code implement a shared `AbortController`, a single-flight semaphore, a pending-key slot, a circuit breaker, and a fast-poll mode — to work around iOS Safari replaying parked fetches. The problem is real, but the solution is four interacting refs plus two effects, with no tests. `liveCall` also adds an `abort` listener per call and removes it in `finally`, which is easy to leak if the promise never settles.
- **Recommendation**: Extract to a `useLiveRequests()` hook with a small state machine (`idle → inFlight → open → recovering`) and unit-test it with fake timers.

### 3.7 `SourceSheet` re-fetches data the parent already has
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L639-L660)
- **Issue**: `SourceSheet` receives `runningApp` as a prop from `App` (which polls `/api/state` every 5 s), but on mount it re-queries `/api/state`, and if that is empty it fetches the much heavier `/api/info`. Opening the source menu can therefore trigger a full telemetry collection.
- **Fix**: Use the prop; if it is null, show nothing. The parent already refreshes.

### 3.8 `InfoSheet` polls `/api/info` every 3 s in "Live" mode
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L1354-L1400)
- **Issue**: `/api/info` runs ~60 reflective binder calls, a `dumpsys` subprocess, and a MediaTek PQ transaction. Polling it every 3 s from a phone is expensive and can contend with key injection on the same dispatcher lock.
- **Fix**: Poll at 10 s, or add a `?sections=` filter so the live view only refreshes the cheap fields.

### 3.9 `PresetStore` re-reads and re-sanitizes the file on every call
- **File**: [`app/src/com/xgimi/bridge/PresetStore.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/PresetStore.java)
- **Issue**: `load()` calls `ensureBuiltins()`, which calls `readRaw()` (a second full read), then `save()` if anything changed. `create()` calls `load()` and `uniqueIdFor()` (which calls `load()` again). `update()`, `syncFromCurrent()`, `delete()`, and `apply()` each call `load()`. A single create can read and parse `presets.json` three times and rewrite it twice.
- **Fix**: Load once into memory, mutate, save once. `ensureBuiltins()` should run only on first load or on a schema-version bump.

### 3.10 `ensureBuiltins()` rewrites the built-in preset on every load
- **File**: [`app/src/com/xgimi/bridge/PresetStore.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/PresetStore.java#L76-L138)
- **Issue**: If the stored `Balanced` values differ from `balancedValues()`, it overwrites them and bumps `updatedAt`. That means a user who edits the built-in (via `PUT /api/presets/balanced`) has their change silently reverted on the next read. The "built-in is read-only" rule is enforced only for `DELETE`, not for `PUT`.
- **Fix**: Reject `PUT`/`sync` on built-ins, or stop auto-refreshing values.

### 3.11 `writeAndVerify` sleeps on the HTTP worker thread
- **File**: [`app/src/com/xgimi/bridge/PresetStore.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/PresetStore.java#L265-L296)
- **Issue**: Up to 4 attempts × 60 ms per knob, plus 150 ms per picture item. Applying a full preset blocks an HTTP worker for ~1.5–2 s. With `SynchronousQueue` and 64 max threads this is survivable, but it is still a blocking sleep in a request handler.
- **Fix**: Write without verification (the code already admits the getter is unreliable), or move apply to a background executor and return `202 Accepted`.

### 3.12 `HttpServer` is a 517-line router with inline business logic
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java)
- **Issue**: `handleApi()` is a 90-line `if` chain; `/api/inputs` inlines 35 lines of `TvInputManager` reflection; `handlePictureSet` inlines an 11-case switch; `handlePresets` inlines URL splitting. There is no route table and no separation between transport and device logic.
- **Recommendation**: A `Map<String, Handler>` route table plus `PictureService`, `InputService`, `PresetService`, `PowerService`, `AdbService`.

### 3.13 `AidlClient` exposes raw transaction codes to callers
- **File**: [`app/src/com/xgimi/bridge/AidlClient.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/AidlClient.java)
- **Issue**: `HttpServer` and `PresetStore` call `aidl.setInt(AidlClient.TX_SET_WB_RED_GAIN, value)` and `aidl.setIntInt(AidlClient.TX_SET_PICTURE_ITEM, 0, value)`. The transaction numbers leak into two other classes, so the "client" abstraction is not actually abstracting anything.
- **Fix**: Expose intent methods (`setWhiteBalance(Channel, int)`, `setPictureItem(Item, int)`) and keep the codes private.

### 3.14 `BridgeJobService` re-arms on every start with a magic window
- **File**: [`app/src/com/xgimi/bridge/BridgeJobService.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/BridgeJobService.java#L20-L50)
- **Issue**: `scheduleNextBoot()` cancels and re-schedules the job on every `onStartJob`, with a 30–90 s window justified by a comment about infinite loops. The job also starts the service unconditionally, even if it is already running, and `onStopJob` returns `false` (never retry).
- **Fix**: Check whether `BridgeService` is already running before starting it; use a named constant for the window; return `true` from `onStopJob` if the work should be retried.

### 3.15 `LauncherActivity` builds a UI to display one line
- **File**: [`app/src/com/xgimi/bridge/LauncherActivity.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/LauncherActivity.java)
- **Issue**: ~90 lines of `LinearLayout`/`TextView`/`SpannableString` plus a 30-line network-interface enumerator, all to show "Service started — open http://…". The foreground notification already exists.
- **Fix**: `Toast.makeText(...)` and `finish()`, or put the URL in the notification text.

### 3.16 `ProjectorInfoCollector` builds a summary by recomputing category values
- **File**: [`app/src/com/xgimi-bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java#L560-L580)
- **Issue**: The summary block re-calls `formatDigitalAudioFormat(...)` and re-derives `luminanceModeLabel`, `picModeLabel`, `audioOutLabel`, and `hdrLabel` instead of reading them back from the category objects it just built. Any divergence between the two computations produces a summary that disagrees with the detail view.

### 3.17 `KeyDispatcher` probes 12 sensors and 4 fans on every `/api/info`
- **File**: [`app/src/com/xgimi-bridge/KeyDispatcher.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/KeyDispatcher.java#L470-L510)
- **Issue**: 16 reflective binder calls per telemetry request, each wrapped in try/catch. Only the ToF/ambient values are cached (5 s); the rest are re-probed every time. Combined with `InfoSheet`'s 3 s live poll, this is ~5 binder calls/second.
- **Fix**: Cache the whole telemetry blob for 2–5 s, or make the sensor probe opt-in.

### 3.18 `MiniHttp` thread pool sizing is arbitrary
- **File**: [`app/src/com/xgimi-bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java#L180-L195)
- **Issue**: `new ThreadPoolExecutor(4, 64, 30s, new SynchronousQueue<>())` means the 5th concurrent request immediately spawns a new thread, up to 64. For a single-user LAN remote, 4–8 threads with a bounded queue would be simpler and safer. `SynchronousQueue` also makes `RejectedExecutionException` the overflow path, which is handled by writing a 503 from the accept thread.

### 3.19 `MiniHttp` `Response.Status` carries unused HTTP machinery
- **File**: [`app/src/com/xgimi-bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java#L40-L75)
- **Issue**: `getRequestStatus()` and `getDescription()` are never called; `NOT_IMPLEMENTED` and `SERVICE_UNAVAILABLE` are used only by the parser/overload path. The enum duplicates what a small `int` + `String` pair would do.

### 3.20 `App.jsx` `INFO_SECTIONS` is a 236-line presentation table
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L1118-L1352)
- **Issue**: 7 sections × ~30 field tuples, each `[key, label]`. It must be kept in sync by hand with the JSON keys produced by `ProjectorInfoCollector`. A renamed server field silently disappears from the UI.
- **Fix**: Move to `infoSections.js` and add a dev-time check that every key exists in a sample `/api/info` response.

### 3.21 `App.jsx` `formatInfoValue` stringifies objects into the UI
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx#L1340-L1352)
- **Issue**: `JSON.stringify(val)` is rendered directly into a table cell for any object/array-of-objects, producing unreadable one-line JSON in the "Stats for nerds" view. The special-cased sensors/fans/CEC arrays show that the generic path was never finished.

### 3.22 `coordinator.py` wake sequence is more complex than the server requires
- **File**: [`homeassistant/custom_components/xgimi_bridge/coordinator.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/coordinator.py#L79-L123)
- **Issue**: `async_turn_on()` opens TCP connections to two WoWLAN ports, sleeps 0.8 s, then retries an HTTP POST to `/wake` four times with 0.6 s sleeps, then falls back to `/api/power/on`. But `KeyDispatcher.startWakeServer` wakes on **any** TCP connection and never reads the request, so the HTTP formatting and retry loop add little. The method also mutates `_last_data` and schedules a refresh via `call_later`, bypassing the coordinator's normal update path.
- **Fix**: One `asyncio.open_connection(host, WAKE_PORT)` with a short timeout, then `async_request_refresh()`.

### 3.23 `coordinator.py` swallows all connection errors and reports "offline"
- **File**: [`homeassistant/custom_components/xgimi_bridge/coordinator.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/coordinator.py#L42-L77)
- **Issue**: Any `aiohttp.ClientError` or timeout sets `awake=False, online=False` and returns normally, so `DataUpdateCoordinator` never marks entities unavailable and never logs at warning level. A DNS failure, a wrong IP, and a sleeping projector are indistinguishable.
- **Fix**: Distinguish `ClientConnectorError` (misconfiguration → `UpdateFailed`) from timeouts (standby → keep last state).

### 3.24 `coordinator.py` `online` key is write-only
- **File**: [`homeassistant/custom_components/xgimi_bridge/coordinator.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/coordinator.py#L37-L119)
- **Issue**: `_last_data["online"]` is set in five places and read only once, internally, to decide whether to fetch presets. No entity exposes it, so the "online" concept never reaches the UI.

### 3.25 `__init__.py` sets an ad-hoc attribute on the coordinator
- **File**: [`homeassistant/custom_components/xgimi_bridge/__init__.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/__init__.py#L35)
- **Issue**: `coordinator.config_entry_id = entry.entry_id` adds an attribute that is not declared in `__init__` and is not part of `DataUpdateCoordinator`. It works, but it is invisible to type checkers and to anyone reading `coordinator.py`.
- **Fix**: Pass `entry.entry_id` into `XgimiDataUpdateCoordinator.__init__` and store it as a declared field.

### 3.26 `__init__.py` catches `ConfigEntryNotReady` and continues
- **File**: [`homeassistant/custom_components/xgimi_bridge/__init__.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/__init__.py#L38-L42)
- **Issue**: `async_config_entry_first_refresh()` raises `ConfigEntryNotReady` when the first fetch fails; catching it and continuing means setup always succeeds, even for a wrong IP. HA's retry/backoff machinery is bypassed.
- **Fix**: Let `ConfigEntryNotReady` propagate for connection errors; only tolerate a projector that is explicitly in standby.

### 3.27 `device_info` hardcodes model and OS version
- **File**: [`homeassistant/custom_components/xgimi_bridge/__init__.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/__init__.py#L79-L88)
- **Issue**: `model="Horizon 20 (XR14A)"` and `sw_version="Android 14"` are constants, even though `/api/info` returns `deviceModel`, `androidVersion`, `gmuiVersion`, and `serialNumber`. The device page shows stale/wrong data on any other model.
- **Fix**: Populate from the coordinator's `/api/info` summary (or at least from `/api/state`).

### 3.28 `config_flow.py` has an unused `errors` dict and no connectivity check
- **File**: [`homeassistant/custom_components/xgimi_bridge/config_flow.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/config_flow.py#L24-L56)
- **Issue**: `errors: dict[str, str] = {}` is created and passed to `async_show_form` but never populated, so a bad host is accepted silently. There is also no `strings.json`/`translations/`, so the flow has no localized labels or error messages.
- **Fix**: Probe `GET /api/state` before creating the entry and set `errors["base"] = "cannot_connect"`; add `strings.json`.

### 3.29 `select.py` preset entity keeps local state that the coordinator overwrites
- **File**: [`homeassistant/custom_components/xgimi_bridge/select.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/select.py#L68-L105)
- **Issue**: `XgimiPresetSelect` stores `_current_preset_name` locally and calls `async_write_ha_state()`, but the coordinator refresh does not know about it, so the value survives until HA restarts and is never derived from the projector. It also defaults `options` to `["Balanced"]` when the list is empty, which can offer a preset that does not exist.
- **Fix**: Track the applied preset in the coordinator (or drop the entity's notion of "current" and make it action-only).

### 3.30 `select.py` input source defaults to "Apps" on unknown values
- **File**: [`homeassistant/custom_components/xgimi_bridge/select.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/select.py#L55-L58)
- **Issue**: `INPUT_SOURCES.get(raw, "Apps")` reports "Apps" for `None`, for HDMI 3, and for any unrecognized source. The UI then claims the projector is on Apps when it is not.
- **Fix**: Return `None` for unknown values so HA shows "unknown".

### 3.31 `number.py` and `select.py` duplicate the POST-and-refresh pattern
- **Files**: [`number.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/number.py#L60-L66), [`select.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/select.py#L60-L181), [`button.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/button.py)
- **Issue**: Every entity calls `coordinator.async_post_json(path, body)` and ignores the boolean result. A failed write is invisible to the user (HA shows the old value after the next refresh, with no error).
- **Fix**: Raise `HomeAssistantError` when `async_post_json` returns `False`.

### 3.32 `async_post_json` refreshes on every write
- **File**: [`homeassistant/custom_components/xgimi_bridge/coordinator.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/coordinator.py#L141-L155)
- **Issue**: Each successful POST triggers a full coordinator refresh (two HTTP GETs). Dragging a slider in HA fires a POST per step, each followed by a refresh — a request amplification loop.
- **Fix**: Debounce refreshes, or update `_last_data` optimistically from the request body.

### 3.33 `async_turn_on`/`async_turn_off` bypass the coordinator update path
- **File**: [`homeassistant/custom_components/xgimi_bridge/coordinator.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/coordinator.py#L79-L139)
- **Issue**: Both mutate `_last_data` directly and call `async_set_updated_data`, then schedule `async_request_refresh` with `hass.loop.call_later(2.0, lambda: ...)`. The lambda captures `self` and creates a task that is never awaited or cancelled on unload.
- **Fix**: `await self.async_request_refresh()` after a short `asyncio.sleep`, or let the normal interval handle it.

### 3.34 `const.py` `SLIDERS` mixes four concerns in one tuple
- **File**: [`homeassistant/custom_components/xgimi_bridge/const.py`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/const.py#L50-L59)
- **Issue**: Each row is `(json_key, display_name, endpoint, icon)` — four positional strings with no names. A typo in position 3 silently posts to the wrong endpoint.
- **Fix**: A `NamedTuple` or dataclass.

### 3.35 `manifest.json` declares no `requirements` but the code imports `aiohttp`
- **File**: [`homeassistant/custom_components/xgimi_bridge/manifest.json`](file:///workspaces/xgimi-bridge/homeassistant/custom_components/xgimi_bridge/manifest.json)
- **Issue**: `aiohttp` is imported directly in `coordinator.py` and `__init__.py`. It is a Home Assistant core dependency, so this works, but it is not declared and will break if HA ever vendors or removes it. `pyproject.toml` also lists `dependencies = []`.
- **Fix**: Declare `aiohttp` in `manifest.json` `requirements` (or rely on `homeassistant.helpers.aiohttp_client` only, which is the documented pattern).

### 3.36 `pyproject.toml` requires Python ≥ 3.14
- **File**: [`homeassistant/pyproject.toml`](file:///workspaces/xgimi-bridge/homeassistant/pyproject.toml#L4)
- **Issue**: `requires-python = ">=3.14"` is far ahead of what most Home Assistant installations run, and the component itself uses no 3.14-only syntax. This makes local linting impossible on standard HA hosts.
- **Fix**: Match the HA minimum (currently 3.13) or drop the constraint.

### 3.37 `vite.config.js` `base: "./"` plus absolute asset paths
- **File**: [`web/vite.config.js`](file:///workspaces/xgimi-bridge/web/vite.config.js#L12) and [`web/index.html`](file:///workspaces/xgimi-bridge/web/index.html#L10-L12)
- **Issue**: `base: "./"` makes Vite emit relative asset URLs, but `index.html` hardcodes `./icon-180.png` etc. and the manifest uses `./`. This works only when the app is served from `/`. `HttpServer.serveAsset` maps `/` to `index.html`, so it happens to work — but any sub-path deployment breaks.
- **Fix**: Keep `base: "/"` and absolute paths, or document the root-only constraint.

### 3.38 `index.html` loads a render-blocking third-party font
- **File**: [`web/index.html`](file:///workspaces/xgimi-bridge/web/index.html#L14-L16)
- **Issue**: Two `preconnect`s plus a blocking stylesheet from `fonts.googleapis.com`. On a LAN with no internet, this delays first paint until the request times out. The font is used only for the OLED display text (`index.css` lines 735, 765).
- **Fix**: Self-host the WOFF2 in `web/public/` (a few KB) or use a system monospace fallback.

### 3.39 `web/package.json` has no lint/test/format scripts
- **File**: [`web/package.json`](file:///workspaces/xgimi-bridge/web/package.json)
- **Issue**: Only `dev` and `build`. No ESLint, Prettier, or test runner, despite a 1,728-line component with non-trivial async state.

### 3.40 `build.sh` and `install.sh` duplicate the package name and job id
- **Files**: [`app/build.sh`](file:///workspaces/xgimi-bridge/app/build.sh), [`app/install.sh`](file:///workspaces/xgimi-bridge/app/install.sh#L10), [`app/AndroidManifest.xml`](file:///workspaces/xgimi-bridge/app/AndroidManifest.xml#L4), [`BridgeJobService.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/BridgeJobService.java#L12)
- **Issue**: `com.xgimi.bridge` appears in the manifest, both scripts, and the Java package; the job id `7331` appears in `install.sh` and `BridgeJobService.JOB_ID`. Renaming either requires a repo-wide search.
- **Fix**: Derive the package from the manifest in the scripts; pass the job id from a single constant (or read it from the app).

### 3.41 `README.md` documents a build one-liner twice
- **File**: [`README.md`](file:///workspaces/xgimi-bridge/README.md)
- **Issue**: `npm --prefix web run build && (cd app && ./build.sh && ./install.sh)` appears under both "One-liner build & deploy" and "Rebuild only the web app". The second heading is misleading — it rebuilds and reinstalls the APK too.

---

## 4. Prioritized Recommendations

### Must fix (correctness / security)
1. HA options flow `config_entry` assignment (1.7) — Configure is broken.
2. Add `KILL_BACKGROUND_PROCESSES` or remove the `killBackgroundProcesses` call (1.1).
3. Fix the two conflicting `hdrType` enum mappings (2.8).
4. Make preset apply report failures instead of always `ok: true` (1.11).
5. Add range validation to `/api/picture/*` (1.13).
6. Restrict or authenticate the port-8081 wake listener (1.15).
7. Fix the `picture_advanced_performance_mode` double-read (1.18).

### Should fix (duplication / maintainability)
8. Single source of truth for knobs, enums, keycodes, and input sources (2.9–2.13, 2.21–2.23).
9. Deduplicate the MediaTek PQ binder client (2.1).
10. Deduplicate the binder/reflection helpers (2.15) and the ADB send/retry wrappers (2.16).
11. Split `ProjectorInfoCollector`, `KeyDispatcher`, `HttpServer`, and `App.jsx` (3.2–3.5, 3.12).
12. Replace the 20+ enum formatters with tables (3.1).
13. Cache presets in memory and stop rewriting built-ins on read (3.9, 3.10).
14. Remove the duplicate power binary sensor or the power switch (2.19).

### Nice to have (polish / hygiene)
15. Self-host the font (3.38).
16. Fix the clipboard fallback and `user-select` (1.8).
17. Remove dead CSS/JS/Java (2.5, 2.6, 2.25, 2.26, 2.28).
18. Add tests, CI, and a license (1.35).
19. Unify versions and document the LAN-trust security model (1.23, 1.34).
20. Fix README drift (1.36, 3.41).
21. Replace `MiniHttp` with `com.sun.net.httpserver` or trim unused features (3.4, 3.18, 3.19).
