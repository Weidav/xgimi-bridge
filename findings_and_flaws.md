# XGIMI Bridge: Codebase Audit, Flaws, and Remaining Backlog

This document tracks findings, architectural flaws, bugs, duplicate code, and backlog items across the **XGIMI Bridge** project (`app/`, `web/`, and `homeassistant/`).

---

## TODO: Physical Deployment & Hardware Verification Checklist

> [!IMPORTANT]
> **Action Required by User**:
> The core server hardening, Home Assistant options flow fix, AtomicFile preset store, and web transport layers have passed all automated JVM, Home Assistant, Web, and tooling regression tests. The following items require verification on **physical hardware** (projector and Home Assistant instance):

### 1. Physical Projector Deployment & Lifecycle
- [ ] **APK Installation & Upgrade**:
  - Deploy the compiled APK (`app/build/bridge.apk`) using `./app/install.sh [projector-ip]`.
  - Confirm upgrade succeeds without needing to uninstall first, preserving existing `presets.json` and ADB key pair (`bridge_adb.key`).
- [ ] **Background Persistence & Autostart**:
  - Reboot the projector.
  - Verify that `BridgeJobService` starts the background service (`BridgeService`) automatically on boot.
  - Confirm notification displays and HTTP server binds to port 8080 and wake listener binds to port 8081.

### 2. Hardware Mutation & AIDL PQ Binder Communication
- [ ] **Picture Setting Controls**:
  - Adjust picture sliders (`brightness`, `contrast`, `saturation`, `sharpness`, `hue`) via Web UI and Home Assistant.
  - Confirm screen visibly updates and no `device_write_failed` (HTTP 502) errors appear in bridge logs.
  - Adjust white balance gains (`wbRed`, `wbGreen`, `wbBlue`) and confirm color temperature changes.
- [ ] **Mode & Input Switching**:
  - Switch picture modes (`Standard`, `Movie`, `Game`, `Custom`) and color temperatures (`Standard`, `Warm`, `Cool`).
  - Switch input sources between internal storage/apps and HDMI ports. Confirm HDMI TvView pipeline switches cleanly without hanging.
- [ ] **Validation Boundary Checks**:
  - Attempt an invalid setting write via HTTP (e.g. `POST /api/picture/brightness` with `{"value": 150}` or `{"value": -5}`).
  - Confirm HTTP 400 Bad Request with `{"ok": false, "code": "invalid_request"}` is returned and hardware is unaffected.

### 3. Standby Wake & Power Transitions
- [x] **Standby Wake via Home Assistant**:
  - Put projector into standby (sleep).
  - Trigger "Turn on" in Home Assistant.
  - Verify Home Assistant successfully sends initial WoWLAN stimulus (broadcast UDP, Wake-on-LAN Magic Packet, mDNS Cast knock, and ports 8010/6465 knocks) followed by concurrent `/wake` (8081) and `/api/power/on` (8080) probing.
  - Confirmed on physical hardware: Projector reliably wakes in ~5.2s on first attempt; 10s power transition protection prevents UI flicker.
- [x] **Wake Endpoint Hardening**:
  - Verify that a bare TCP connection or `GET /` to port 8081 no longer wakes the projector.
  - Verify only a valid `POST /wake` triggers the key injection.
- [ ] **Concurrent Power Toggle Serialization**:
  - Rapidly trigger power on/off commands and verify that `DeviceCommands` serializes them, returning HTTP 202 `{"accepted": true, "pending": true}` while transition is in flight, preventing flip-flop toggles.

### 4. Home Assistant Integration Verification
- [ ] **Options Flow ("Configure")**:
  - In Home Assistant, go to **Settings -> Devices & Services -> XGIMI Bridge -> Configure**.
  - Confirm the options dialog opens properly without raising an `AttributeError` on `config_entry`.
- [ ] **Entity State & Error Bubbling**:
  - Test the Power switch and binary sensor: confirm state matches real projector power state.
  - Test Number slider entities: confirm adjustments update the projector. If the bridge is temporarily unreachable, confirm a clear `HomeAssistantError` alert is displayed rather than silently failing.
  - Test Input Source select and Preset select entities.
- [ ] **Clean Reload / Unload**:
  - Click "Reload" on the integration entry. Confirm all entities refresh and no orphan timers or delayed refresh callbacks log exceptions.

### 5. Web UI Features & Offline Resilience
- [ ] **Telemetry & Clipboard Fallback**:
  - Open "Stats for nerds" info sheet in a mobile browser over HTTP.
  - Click "Copy JSON" and verify JSON is copied to clipboard via the fallback textarea mechanism.
  - Confirm raw telemetry text can be manually highlighted and copied (`user-select: text`).
- [ ] **Preset Storage Durability**:
  - Create a new preset in the web UI.
  - Verify that built-in presets (`Balanced`) cannot be overwritten or deleted (HTTP 403 `builtin_read_only`).

---

## Summary of Addressed & Hardened Findings

The following items from the original audit were addressed and resolved following `spec.md`:

| Item | Description | Resolution |
| :--- | :--- | :--- |
| **1.1** | Silent `SecurityException` in Input Switch (`killBackgroundProcesses`) | Added `android.permission.KILL_BACKGROUND_PROCESSES` to `AndroidManifest.xml`. |
| **1.2** | Unsynchronized Check in `LocalAdbClient.warmUpAsync()` | Synchronized socket checks under `dispatcherLock` in `LocalAdbClient`. |
| **1.3** | Presets Inefficient Parsing & Recursive Sanitization Loop | Completely rewritten `PresetStore` using `AtomicFile`, memory-efficient reads, quotas, no recursive loops. |
| **1.4** | Blocking Sleep on HTTP Worker Pool Threads | Serialized mutations in `DeviceCommands` with non-blocking `tryLock(250ms)` returning HTTP 503 if busy. |
| **1.5 / 3.38** | Offline Remote Dependency on External Google Fonts | Self-hosted Silkscreen WOFF2 font files locally in `web/public/fonts/` and `web/src/fonts/`, loaded via `@font-face` in `index.css`; removed external Google Fonts links. |
| **1.6** | Hardcoded Private Developer IP in Home Assistant Component | Removed hardcoded IP default in `const.py` / `config_flow.py`. |
| **1.7** | Home Assistant options flow crash on modern HA | Removed invalid `__init__(config_entry)` override; uses inherited property per HA guidelines. |
| **1.8** | "Copy JSON" silently failed on plain HTTP LAN origins | Added fallback `<textarea>` + `execCommand("copy")` in `App.jsx` and `user-select: text` on `.info-raw-pre`. |
| **1.9** | `/api/state` reported hardcoded port 8080 | Returns dynamic `httpServer.getListeningPort()`. |
| **1.10** | Malformed JSON silently treated as empty body | Strict JSON validation returning HTTP 400 Bad Request with explicit error code. |
| **1.11** | Preset apply reported success when writes failed to converge | `DeviceCommands` stops on first failure and returns `{applied, failed}` with HTTP 502 `device_write_failed`. |
| **1.12** | `reset` echoed `Integer.MIN_VALUE` sentinel value | Reset aggregates actual write outcomes and omits sentinel value. |
| **1.13** | No range validation on picture knobs | Centralized numeric range and enum checking in `SettingRules`. |
| **1.14** | Power on/off check-then-toggle race condition | Serialized state machine in `DeviceCommands` returning HTTP 202 Accepted pending. |
| **1.15** | `KeyDispatcher` port 8081 wake listener triggered on raw TCP connect | Replaced with bounded `WakeHttpServer` requiring `POST /wake` and `RequestPolicy` origin validation. |
| **1.16** | Data races on `LocalAdbClient` dispatcher fields | Properly synchronized dispatcher fields with `keyLock` and `dispatcherLock`. |
| **1.17** | `KeyDispatcher` Telemetry Caches Are Unsynchronized Statics | Guarded `cachedCec`, `cachedTof`, `cachedAmbient` cache accesses and refresh logic under `TELEMETRY_CACHE_LOCK`. |
| **1.18** | `ProjectorInfoCollector` Duplicate Settings Key | Removed redundant `picture_advanced_performance_mode` from `globalStrKeys` so integer value is preserved. |
| **1.20** | `BridgeService` lacked recovery if HTTP port failed to bind | Added bounded exponential backoff retry loop during service initialization. |
| **1.24 / 2.27** | `serveAsset` Loads Assets Fully into Memory Per Request | Enabled streaming directly from asset `InputStream` via `MiniHttp.newFixedLengthResponse(..., InputStream, length)` with bounded 8KB socket writes, eliminating intermediate full-memory slurping. |
| **1.25** | Ambiguous prefix-based route matching | Exact path and segment routing implemented in `HttpServer.java`. |
| **1.26** | `MiniHttp` parsed unused query parameters and fragments | Streamlined request target parsing; rejects absolute URIs. |
| **1.27** | `MiniHttp` chunked body parsing vulnerabilities | Hardened chunk arithmetic against integer overflow; enforced aggregate request body deadlines and byte caps. |
| **1.28 / 2.24** | `MiniHttp` error path duplicated response writer | Unified response status, header, and framing generation. |
| **1.29** | `MiniHttp` unbuffered byte-by-byte line reading | Bounded buffered reading with strict monotonic deadline enforcement. |
| **1.30** | `MiniHttp.stop()` did not await executor termination | Added bounded executor termination timeout on service shutdown. |
| **1.32** | Fragile quoting and path handling in `build.sh` | Quoted variables, array-based command arguments, target directory sanity checks. |
| **1.33** | Timing races and device target assumptions in `install.sh` | Robust ADB device target resolution, bracketed IPv6 support, APK file verification. |
| **1.34** | Version Drift Across Deliverables | Unified release versioning to `1.0.0` across `AndroidManifest.xml`, `package.json`, `manifest.json`, and `pyproject.toml`. |
| **1.35** | No automated tests | Implemented comprehensive test suite in `tests/` covering JVM, Web, Home Assistant, and Tooling. |
| **1.36** | README drift | Updated `README.md` with documented LAN trust model, limits, architecture, and endpoints. |
| **2.1** | Duplication of Raw MediaTek IPq Binder IPC | Extracted identical AIDL transactions (54 & 59) against `vendor.mediatek.hardware.pq.IPq/default` into a shared `MtkPqClient` helper. |
| **2.2** | Duplicate Stream and File I/O Helpers | Consolidated redundant 8KB read/write buffer loops and file copy logic into a shared `IoUtils` utility used across `PresetStore` and `LocalAdbClient`. |
| **2.3** | Duplicate JSON String Escaping & Error Formatting | Replaced duplicated custom string escaping loops with `JSONObject.quote()` across `MiniHttp` and `HttpServer`. |
| **2.4** | Duplicate Running Application Lookups | Consolidated `sys.mtk.running.application` and `running_package_name` lookups into a unified `DeviceState.getRunningApp(Context)`. |
| **2.5** | CSS Split Rules, Dead Selectors, and Inconsistent Tokens | Removed dead `.state` selector, bound `--bg` token to `html`/`body`, consolidated duplicate `.btn` declarations with hover transitions. |
| **2.6** | Dead Code in React Icons | Removed unrendered `PowerIcon()` SVG component from `web/src/App.jsx`. |
| **2.12** | Awake / Interactive Logic Duplication | Consolidated `PowerManager.isInteractive()` checks into `DeviceState.isAwake(Context)`. |
| **2.13** | `Map` -> `JSONObject` Conversion Duplication | Centralized recursive map and collection JSON serialization in `JsonUtils.mapToJson()`. |
| **2.14** | `put` / `putSafe` Duplicated | Consolidated JSON object putters with type sanitization into `JsonUtils.putSafe()` across `KeyDispatcher` and `ProjectorInfoCollector`. |
| **2.15** | Binder Reflection Helpers Duplication | Centralized Parcel obtain/transact/recycle boilerplate into `BinderTransact` utility and simplified `AidlClient`. |
| **2.16** | `LocalAdbClient` Retry Boilerplate | Parameterized retry loop across key/text injection and hardware query into `withDispatcherRetry(DispatcherAction)`. |
| **2.17 / 3.16** | Formatter Called Twice / Recomputing Category Values | Reused precomputed audio/earc formatters and category values (`hdrStatus`, `luminancePowerMode`, `pictureMode`, `activeOutputDevice`, `digitalOutputPassThroughFormat`) in `summary` block in `ProjectorInfoCollector`. |
| **2.18** | Home Assistant Button Class Duplication | Consolidated identical `async_press` execution and error handling into `XgimiActionButton` base class. |
| **2.20** | HDMI Input Reflection Duplication | Extracted `TvInputInfo` HDMI reflection into dedicated `HdmiInputHelper` utility. |
| **2.21** | Knob list mapping duplicated in `PresetStore` and `HttpServer` | Centralized in `SettingRules` and `DeviceCommands`. |
| **2.22** | `App.jsx` Input Source Mapping Duplication | Unified input source mapping with dynamic `inputToAidlSource` helper. |
| **2.23** | `App.jsx` `epToField` Duplicates `KNOBS` / `PICTURE_ITEMS` | Centralized unified `KNOB_DEFINITIONS` in `api.js` and derived `KNOBS`, `PICTURE_ITEMS`, and `epToField` dynamically. |
| **2.25** | `MiniHttp` Request/Response Redundant Getters | Removed redundant getters on `Request` and `Response` with already `public final` fields. |
| **2.26** | `AidlClient` Standardized Transaction Pattern | Unified `AidlClient` read queries through null-safe query methods and mutation through throwing methods. |
| **2.28** | Dead-Code Inventory | Pruned `readSysFsViaAidl`, `TX_SET_GAME_MODE_OPTION_FOR_SOURCE`, `sendKey(keyCode)` wrapper alias, and unused `isInternalStorage` parameter in `resolvePictureMode`. |
| **3.24** | Coordinator Write-Only `online` Key | Exposed `XgimiConnectivityBinarySensor` in Home Assistant to expose live coordinator online state. |
| **3.9** | `PresetStore` re-read file multiple times per mutation | Loads once into memory, performs atomic write replacement on commit. |
| **3.10** | Built-in presets overwritten and unsafely mutated | Read-only loads; builtin preset edits and deletes return HTTP 403 `builtin_read_only`. |
| **3.11** | Synchronous sleep in preset application on HTTP thread | Handled via `DeviceCommands` with pacing and non-blocking caller return. |
| **3.22** | Home Assistant wake sequence complexity & timeout | Structured wake flow with WoL Magic Packets, mDNS Cast queries, dual UDP + parallel TCP knocks (8010, 6465, 9, 7), concurrent 8081/8080 probing, MAC auto-discovery from ARP cache, 24s retry window to accommodate STR resume, complementary `/wake` (8081) and `/api/power/on` (8080) optical engine strike, and 10s power transition optimistic state protection. |
| **3.23** | Home Assistant coordinator swallowed connection errors | Distinguishes `UpdateFailed` errors from expected standby timeouts. |
| **3.26** | `ConfigEntryNotReady` swallowed during setup | Allows Home Assistant setup retry and backoff machinery to function. |
| **3.28** | Home Assistant config flow lacked host validation | Config flow validates host format (IP/hostname) and populates error messages. |
| **3.31** | Home Assistant entities ignored command write failures | Entities raise `HomeAssistantError` on write failure to notify the user. |
| **2.7 / 2.9** | Cross-Language Picture & Mode Option Duplication | Exposed dynamic `/api/enums` endpoint in `HttpServer` backed by `SettingRules.getEnumsJson()` covering picture modes, color temps, game modes, knobs, and allowed inputs. |
| **2.10** | Keycode Tables Duplicated in Three Places | Unified keycode dictionary in `const.py` (`KEYCODES`) matching Web remote keycodes, generating HA button entities cleanly. |
| **2.11 / 3.34** | Knob List Duplication & Positional `SLIDERS` Tuple | Exposed knob limits via `/api/enums` and refactored positional `SLIDERS` tuples into `SliderConfig(NamedTuple)` in `const.py`. |
| **3.33** | Uncancelled delayed refresh tasks on unload | Tracked and cancelled all pending tasks in `async_unload_entry`. |
| **3.25** | Coordinator Entry ID Attribute | Declared `self.config_entry_id` attribute directly in `XgimiDataUpdateCoordinator.__init__` rather than ad-hoc runtime assignment. |
| **3.27** | `device_info` Hardcodes Model and OS Version | Expose model and OS version dynamically from `/api/picture` snapshot and coordinator telemetry with sensible fallbacks. |
| **3.29** | `select.py` Preset Entity Local State | Derived preset entity state directly from coordinator data (`active_preset`) to prevent local state divergence. |
| **3.30** | `select.py` Input Source Defaults to "Apps" | Returned `None` for unrecognized or absent input sources so Home Assistant cleanly displays unknown state. |
| **3.32** | `async_post_json` Refreshes on Every Write | Optimistically updated `_last_data` cache immediately on successful POST requests before scheduling refresh. |
| **3.35** | `manifest.json` Requirements | Explicitly declared `"requirements": []` to conform to Home Assistant custom integration packaging standards. |
| **3.36** | `pyproject.toml` Python Requirement | Lowered `requires-python` from `>=3.14` to `>=3.13` to align with standard Home Assistant environments. |
| **3.37** | `vite.config.js` Base Path | Standardized `web/index.html` module script reference to `./src/main.jsx` matching relative base path `./`. |
| **3.39** | `web/package.json` Test Script | Added `test` npm script in `web/package.json` executing node web regression tests. |
| **3.40** | Package Name and Job ID Duplication | Dynamically parse package name from `AndroidManifest.xml` and Job ID from `BridgeJobService.java` in `install.sh`. |
| **3.14** | `BridgeJobService` Periodic Re-arming | Check `BridgeService.isRunning` state before attempting to start the service to prevent redundant startup calls. |
| **3.17** | `KeyDispatcher` Sensor Probing | Cached sensor (12 indices) and fan (4 indices) telemetry for 5 seconds under `TELEMETRY_CACHE_LOCK` to reduce IPC overhead. |
| **3.7** | `SourceSheet` Redundant Telemetry Fetches | Removed redundant fallback `/api/info` fetch on mount and rely solely on parent state. |
| **3.8** | `InfoSheet` Polling Frequency | Throttled live polling frequency from 3s to 10s to reduce reflective binder load and dumpsys execution. |
| **3.18** | `MiniHttp` Thread Pool Sizing | Replaced unbounded SynchronousQueue bursts with a bounded LinkedBlockingQueue and `allowCoreThreadTimeOut(true)`. |
| **3.19** | `MiniHttp` `Response.Status` Machinery | Pruned unused HTTP status `GATEWAY_TIMEOUT` and dead helper methods `getRequestStatus()` and `getDescription()`. |
| **3.20** | `App.jsx` `INFO_SECTIONS` Table Size | Extracted 220+ line telemetry metadata table into dedicated `web/src/constants/infoSections.js` module. |
| **3.21** | `App.jsx` `formatInfoValue` Value Formatting | Cleanly format nested telemetry objects and object arrays with key-value pairs rather than raw JSON strings. |
| **3.13** | `AidlClient` Exposes Raw Transaction Codes | Wrapped raw transaction codes in semantic domain methods (`setWbRedGain`, `setWhiteBalance`, `setPictureMode`, `setPictureItem`, etc.) and centralized caller dispatch in `DeviceCommands.applyAidlSetting`. |
| **3.1** | Single-Use Micro-Functions in `ProjectorInfoCollector` | Replaced 20+ enum formatter switch functions with static lookup tables (`Map` / array) and generic `formatEnum` / `formatEnumMap` helpers. |
| **3.6** | The Live-Request Governance Layer is Over-Engineered | Extracted ~110 lines of shared abort, single-flight semaphore, and circuit breaker logic into `useLiveRequests` custom hook and testable `createLiveRequestManager` state machine with unit test coverage. |
| **3.41** | `README.md` Build Step Documentation | Clarified dedicated web-only rebuild step `npm --prefix web run build` under Step-by-step. |


---

## Items Flagged as Wrong or Intentionally Excluded

The following audit entries were reviewed and flagged as intentional, disproved, or excluded per project architecture and hardware safety decisions:

- **1.19 `BridgeService` holds a wake lock forever**:
  *Flagged as intentional / excluded without device evidence*. Holding a partial wake lock and Wi-Fi low-latency lock is necessary on Android TV projector hardware to prevent the OS from deep-sleeping the Wi-Fi SoC, which breaks Wake-on-LAN and HTTP wake. Modifying power locks without longitudinal power measurements on physical hardware risks breaking wake-over-network.
- **1.21 Exported components without permission (`LauncherActivity`)**:
  *Flagged as intentional*. `LauncherActivity` is meant to be launched from external TV launchers and command-line `am start` to display service status and configure LAN host aliases. `android:allowBackup="false"` was added to prevent ADB backup extraction.
- **1.22 Manifest permissions (`ACCESS_NETWORK_STATE`, `SYSTEM_ALERT_WINDOW`)**:
  *Flagged as required*. Retained because Android TV OEM firmware requires `SYSTEM_ALERT_WINDOW` for background activity launch bypasses, and `ACCESS_NETWORK_STATE` is standard for network reachability checks.
- **1.23 No authentication or CSRF protection beyond same-origin check**:
  *Owner Decision*. Direct unauthenticated HTTP access on an isolated, trusted private VLAN was explicitly retained by project owner decision. Because the projector lives entirely within an isolated VLAN not accessible from the public web, strict `Host` and `Origin` filtering in `RequestPolicy` (`isHostAllowed` and `isOriginAllowed`) is set to unconditionally allow all hosts/origins. This eliminates friction and enables seamless operation behind reverse proxies (Traefik, Nginx, Caddy) and custom local domains without requiring host alias configuration.
- **1.31 / 3.15 `LauncherActivity` programmatic UI**:
  *Flagged as intentional / repurposed*. Retained to display on-screen service status and port reachability directly on the projector display without requiring terminal/ADB commands.
- **2.8 Two incompatible HDR enum mappings**:
  *Flagged as excluded without physical hardware*. MediaTek PQ and GMUI vendor frameworks use different enum values for HDR types. Altering the mapping table without hardware verification against the specific projector firmware risks displaying incorrect video metadata.
- **2.19 Power switch and power binary sensor expose the same state**:
  *Flagged as standard Home Assistant pattern*. Exposing both a switch entity (controllable state) and a binary sensor (read-only state representation) is standard and recommended practice in Home Assistant integrations.

---

## Remaining Unaddressed Findings & Flaws (Kept for Future Checks)

The following items were identified during the codebase audit and remain unaddressed. They are preserved here for future refactoring, optimization, and code hygiene tasks.

---


### Section 3: Over-Engineered Architecture & Single-Use Micro-Functions

#### 3.2 `ProjectorInfoCollector` 1,374-Line God Class
- **File**: [`app/src/com/xgimi/bridge/ProjectorInfoCollector.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/ProjectorInfoCollector.java)
- **Issue**: One static class handles display, audio, optics, thermal, CEC, system properties, and MediaTek PQ AIDL calls.
- **Future Check**: Split into modular domain collectors under `com.xgimi.bridge.collector.*` (DisplayCollector, AudioCollector, ThermalCollector, etc.).

#### 3.3 `KeyDispatcher` Multi-Responsibility Monolith
- **File**: [`app/src/com/xgimi/bridge/KeyDispatcher.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/KeyDispatcher.java)
- **Issue**: Combines stdin ADB key injection, dumpsys HDMI parsing, and GMPF telemetry collection.
- **Future Check**: Extract shell telemetry into `ShellTelemetryCollector`.

#### 3.4 `MiniHttp` is a Hand-Rolled HTTP Server with Unused Features
- **File**: [`app/src/com/xgimi/bridge/MiniHttp.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/MiniHttp.java)
- **Issue**: Implements chunked decoding, query parsing, absolute-URI handling, fragment stripping, HTTP/1.0 keep-alive negotiation, and thread-local date formatters for a simple LAN API.
- **Future Check**: Consider using Android built-in `com.sun.net.httpserver.HttpServer` or pruning unused protocol variants.

#### 3.5 `App.jsx` Monolith
- **File**: [`web/src/App.jsx`](file:///workspaces/xgimi-bridge/web/src/App.jsx)
- **Issue**: ~1,800 lines combining remote control layout, modal sheets, SVG icons, and presentation logic in one file.
- **Future Check**: Split into component files under `web/src/components/` (`Remote.jsx`, `InfoSheet.jsx`, `SourceSheet.jsx`, `Icons.jsx`).

#### 3.12 `HttpServer` is a Router with Inline Business Logic
- **File**: [`app/src/com/xgimi/bridge/HttpServer.java`](file:///workspaces/xgimi-bridge/app/src/com/xgimi/bridge/HttpServer.java)
- **Issue**: ~780-line class with inline `if/else` route dispatching, TV input reflection, and request parameter handling.
- **Future Check**: Introduce a `Map<String, Handler>` route table and separate services (`InputService`, `PowerService`, `AdbService`).



