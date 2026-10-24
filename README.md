# XGIMI Horizon 20 Series Remote

<p align="left"><img src="app/assets/web/icon-512.png" alt="XGIMI Remote" width="300"></p>

Background-only Android service for an **XGIMI Horizon 20** (Base, Pro & Max) projector that exposes a local-LAN HTTP API and a small web app. Together they replace the physical remote: every button on the original Xgimi remote is reachable from your phone's browser, plus picture-quality controls and named presets.

- HTTP API on port **8080**
- Web UI on the same port — works in any modern mobile or desktop browser; add to your phone's home screen (iOS Safari "Add to Home Screen", Android Chrome "Install app") for a standalone-app feel. The bridge sets long `Cache-Control` headers on the hashed asset bundle so the OS keeps the shell on disk after first load. A "Disconnected" badge surfaces in the header when the bridge is unreachable.
- No app icon, no apps-screen presence on the projector, survives reboot via persisted `JobScheduler`

> **Note on offline behaviour**: the bridge serves over plain HTTP, so a service worker can't register (browsers require HTTPS for SW registration). Offline-shell caching relies on the browser's home-screen / installed-app cache plus our `Cache-Control: immutable` headers. Effective for the typical "projector asleep / Wi-Fi blip" case, less so when the OS evicts the cache. If you want a true SW-backed offline shell, terminate the bridge behind HTTPS yourself (self-signed cert + per-device trust install).

> **Tested device**: XGIMI Horizon 20 on **Android 14 (Google TV, MT9689)**. The `com.xgimi.xgimiservice` AIDL surface is derived from this specific firmware. The web client itself is browser-based and not tied to any particular phone OS.

## What works

- D-pad / OK / back / home — full remote navigation
- Volume up / down / mute
- Power (display on/off, same toggle as the physical remote)
- Settings / gear (opens Google TV settings dashboard)
- Auto-focus
- Google Assistant key
- Keyboard / on-screen text input injection
- Source switch (Android TV with running app detection / HDMI 1 / HDMI 2)
- Projector diagnostics & telemetry dashboard (`/api/info`)
- White balance R/G/B (slot-0 — visible in HDMI + game-mode combos)
- Brightness / contrast / saturation / sharpness / hue
- Named picture presets — built-in **Balanced** preset is non-removable

## How it works

The interesting design decision is how key events get injected. The straightforward path — talking to Google's `com.google.android.tv.remote.service` over its mTLS pair / control protocol — is **broken on this device**: the control acceptor at port 6466 has a same-host filter (`isLoopbackAddress() || NetworkInterface.getByInetAddress(peer) != null` returns true → connection closed), so any client running on the projector itself gets dropped. Verified by decompiling the service across versions 2.1 → 6.7 — the filter is identical and present in every shipping build.

Instead, the bridge becomes its own ADB client over loopback:

```
Web client  ──HTTP──▶  Bridge (untrusted_app)
                       │
                       │  loopback TCP
                       ▼
                127.0.0.1:5555  (adbd, listening for ADB-over-TCP)
                       │
                       │  ADB protocol, RSA-2048 auth
                       ▼
                shell-uid session  ──spawns──▶  app_process running KeyDispatcher
                                                          │
                                                          │  InputManagerGlobal.injectInputEvent
                                                          │  (shell uid has INJECT_EVENTS)
                                                          ▼
                                                    KeyEvent to active window
```

On first run the bridge generates an RSA-2048 keypair, opens a TCP connection to `127.0.0.1:5555`, and speaks the ADB protocol. The server-side acceptance flow surfaces the standard "Allow USB debugging from this computer?" prompt on the TV — the user clicks Allow + Always **once**, with the physical remote. From that point on the bridge has shell-uid execution.

`KeyDispatcher` is a small Java class loaded into a long-lived `app_process` JVM via the bridge's own APK on the classpath. It loops on stdin reading keycode integers and calls `InputManagerGlobal.injectInputEvent` reflectively (it's a hidden API; shell uid bypasses the hidden-API restriction). One JVM cold-start per bridge run, then ~5 ms per keypress.

A handful of keys do not go through the dispatcher because they are intercepted by Xgimi's `PhoneWindowManager.interceptKeyBeforeDispatching` and route to specific services:

- **Power (`KEYCODE_POWER` = 26)** — toggles display sleep/wake.
- **Settings / gear (`KEYCODE_SETTINGS` = 176)** — opens the Google TV settings dashboard.
- **Auto-focus (`KEYCODE_F10` = 140)** — triggers `XgimiGlobalKeyBroadcast.gotoAF` to run projector auto-focus.

These all just go through the normal `injectKey` path; the Xgimi WindowManager catches them upstream of normal dispatch.

Picture-quality knobs and source switching go through Xgimi's system-uid AIDL service (`com.xgimi.xgimiservice`), which exposes a number of binder methods to any caller without permission gates. `AidlClient.java` speaks raw Binder transactions to it.

## Layout

```
bridge/
├── app/                              Android app (plain Android SDK build, no Gradle)
│   ├── assets/web/                   Built web bundle served by the bridge
│   └── src/com/xgimi/bridge/         AIDL client, local ADB loopback, MiniHttp server
├── homeassistant/                    Home Assistant integration
│   └── custom_components/xgimi_bridge/ Custom component (switch, number, select, buttons)
└── web/                              React + Vite web app
    ├── public/                       Web app icons & manifest
    └── src/                          Frontend UI source
```

## Build & install

> [!TIP]
> Use the prepared **Dev Container** (`.devcontainer/`). It comes preconfigured with JDK 17, Android SDK tools (`android-34`, `build-tools/34.0.0`), Node.js, and `adb`, so you can build and deploy without manually configuring local toolchains.

### One-liner build & deploy

Run directly from the repository root:

```bash
npm --prefix web run build && (cd app && ./build.sh && ./install.sh)
```

Or pass the projector IP/host explicitly if not already paired:

```bash
npm --prefix web run build && (cd app && ./build.sh && PROJECTOR=192.168.3.17 ./install.sh)
```

### Step-by-step

```bash
# 1) Build the web app (writes the bundle into app/assets/web/)
cd web && npm install && npm run build

# 2) Build the APK
cd ../app && ./build.sh        # output: build/bridge.apk

# 3) Install on the projector and grant runtime exemptions
./install.sh                   # auto-picks the only connected ADB device
# or pass the host explicitly:
PROJECTOR=192.168.3.17 ./install.sh
```

If no device is connected, pair first:

```bash
adb connect <projector-ip>:5555
```

`install.sh` does these things, in order:

1. `adb install -r build/bridge.apk`
2. `dumpsys deviceidle whitelist +com.xgimi.bridge`
3. `appops set` for `SYSTEM_ALERT_WINDOW`, `START_FOREGROUND`, `RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND`
4. `am start -n com.xgimi.bridge/.LauncherActivity` (kicks the service + schedules the persistent job)
5. `cmd jobscheduler run com.xgimi.bridge 7331` (force-fires the job once)

After this, the bridge is reachable at `http://<projector-ip>:8080/` from any phone on the same Wi-Fi.

## First-run authorization

When you open the web app the first time after install, the d-pad area is replaced by an **Authorize** card. Tap it. Within ~1 s an "Allow USB debugging from this computer?" dialog appears on the projector. Use the physical remote to navigate to **Always allow from this computer** + **OK**. The card flips to the d-pad within ~1 s of approval. The authorization is persisted on the projector (`/data/misc/adb/adb_keys`) and survives reboot — you only do this once.

## Boot auto-start

Boot is handled entirely by a `JobScheduler` job with `setPersisted(true)` (`BridgeJobService`, id `7331`):

- Scheduled with `(minLatency=30s, deadline=90s)`.
- `setPersisted(true)` survives reboot — once the device has been off longer than the deadline window, the persisted deadline is already in the past, so the job fires almost immediately on next boot.
- `onStartJob` re-arms with the same 30–90 s window. A tighter window infinite-loops (the framework re-fires before `onStartJob` returns); a longer window means the bridge waits minutes after boot to come up.

`setPersisted(true)` requires `RECEIVE_BOOT_COMPLETED` or the framework throws — that's the only reason that permission is in the manifest. The `BOOT_COMPLETED` broadcast itself is silently dropped on this firmware for non-system apps.

Worst-case latency from boot to `http://<projector-ip>:8080` answering: ~90 seconds.

## HTTP API

State / telemetry / picture / sources:

- `GET  /api/state`                  `{ ready, awake, port, runningApp, ts }`
- `GET  /api/info`                   comprehensive projector diagnostics & telemetry (optics, thermals, display, audio, system)
- `GET  /api/picture`                full picture snapshot (including `awake`, `pictureMode`, `colorTemp`, `gameMode`, `runningApp`, etc.)
- `GET  /api/inputs`                 current `TvInputManager` listing with CEC-derived names
- `POST /api/picture/<knob>`         `{ "value": <int> }` — knobs: `wb-red`, `wb-green`, `wb-blue`, `color-temp`, `picture-mode`, `game-mode`, `brightness`, `contrast`, `saturation`, `sharpness`, `hue`
- `POST /api/picture/reset`          `{ "mode": <int>, "force": <bool> }`
- `POST /api/input`                  `{ "to": "HDMI1" | "HDMI2" | "STORAGE" | "HOME" }`

Presets (persist to `/data/data/com.xgimi.bridge/files/presets.json`):

- `GET    /api/presets`
- `POST   /api/presets`              `{ "name": "...", "values": {...} }`
- `POST   /api/presets/<id>/apply`
- `POST   /api/presets/<id>/sync`    refresh from current state
- `PUT    /api/presets/<id>`         rename / replace
- `DELETE /api/presets/<id>`         (built-ins refuse)

Power:

- `POST /api/power/on`               idempotent turn on (wakes display if in standby)
- `POST /api/power/off`              idempotent turn off (puts display into standby if awake)
- `POST /api/power/toggle`           toggle display sleep/wake (KEYCODE_POWER = 26)
- `POST /api/power/led-off`          turn projector lamp off while Android keeps running
- `POST /api/power/led-on`           turn projector lamp back on
- `POST /api/power/restart`          reboot the projector

Remote (ADB-loopback / KeyDispatcher):

- `GET  /api/adb/status`             `{ "authorized": bool }` — non-prompting probe
- `POST /api/adb/authorize`          surfaces the on-screen Allow prompt; returns when accepted
- `POST /api/adb/keyevent/<n>`       inject `KEYCODE_<n>` (Android keycode int)
- `POST /api/adb/text`               `{ "text": "..." }` — inject arbitrary text

## Home Assistant Integration

A native custom component is included in [`homeassistant/custom_components/xgimi_bridge/`](homeassistant/custom_components/xgimi_bridge/). It groups all controls into a single **XGIMI Horizon 20** Device in Home Assistant with zero YAML needed.

### Installation

1. Copy the `xgimi_bridge` folder into your Home Assistant `custom_components/` directory. From your HA config dir:
   ```bash
    rm -rf custom_components/xgimi_bridge && \
    curl -fsSL https://github.com/Weidav/xgimi-bridge/archive/refs/heads/main.tar.gz | \
    tar -xz --strip-components=2 xgimi-bridge-main/homeassistant/custom_components/xgimi_bridge
   ```
2. Restart Home Assistant.
3. In Home Assistant, go to **Settings → Devices & Services → Add Integration**.
4. Search for **XGIMI Bridge**, enter your projector IP (e.g. `192.168.3.17`), and click **Submit**.

### Features on the Device Page
- **Power Switch**: Turn on from WoWLAN deep standby (automatic TCP knock + port 8081 wake) and turn off.
- **Selects**: Input Source (`Apps`, `HDMI 1`, `HDMI 2`), Presets (dynamically populated from bridge presets), Picture Mode, Color Temperature, Game Mode.
- **Sliders (`0–100`)**: Brightness, Contrast, Saturation, Sharpness, Hue, and White Balance R/G/B gains.
- **Buttons**: Auto Focus, Settings Dashboard, Home, Back, Menu, Volume Up/Down/Mute, Lamp Off/On, Restart Projector.
- **Reconfigurable in GUI**: Click **Configure** on the Device page anytime to change the IP address.

## Notes / known quirks

- **WB sliders only visibly affect the picture on color-temp slot 0.** HDMI + game mode is the typical combination that lands there. Other combinations write to slot 0 successfully but you won't see the change.
- **AIDL `switchToSignalSource` is a stub** on this firmware. The bridge instead switches HDMI inputs via `TvContract.buildChannelUriForPassthroughInput` on the root hardware `TvInputInfo`, and switches Home/Apps via `Intent.ACTION_MAIN` + `Intent.CATEGORY_HOME` (stopping `com.google.android.tv.inputplayer`).
- **BAL bypass for the input switch** requires the `SYSTEM_ALERT_WINDOW` appop (granted by `install.sh`). The grant survives until reboot; on a fresh boot you'd need to re-run `install.sh` only if you want the cold-start input switch to work — the rest of the bridge functions normally.
- **The bridge needs ADB-over-TCP enabled on the projector** to authorize itself. Anyone else on the same LAN who can reach port 5555 already has full ADB access — this isn't worse than what ADB-over-TCP already gives you. (On most XGIMI firmware this is enabled by default; if not, run `setprop service.adb.tcp.port 5555` and restart `adbd`).


## Security model & isolated LAN deployment

The bridge is designed for direct HTTP operation on an **isolated, trusted local network**.
- **No API tokens, passwords, or client pairing**: Network isolation is the access boundary. Any device reachable on the local LAN may control the projector.
- **RequestPolicy protection**: While authentication is intentionally excluded per owner deployment choice, cross-origin drive-by attacks and DNS rebinding from malicious websites are blocked:
  - Canonical `Host` validation: Only requests naming the projector's local IP address, `localhost` (loopback), or explicitly configured local DNS aliases (e.g. configured via `LauncherActivity`) are accepted.
  - Strict `Origin` checks: Mutations require a matching LAN origin or an absent origin (for native clients like Home Assistant and curl). Wildcard CORS is disabled.
- **Wake security**: The wake listener on port 8081 accepts only valid `POST /wake` requests with an empty body and approved Host/Origin. Bare TCP accepts or GET requests do not wake the display.

## HTTP limits & error semantics

The bridge implements strict HTTP/1.1 limits and resource bounds:
- **Port 8080 (API & Web)**: 64 KB decoded body ceiling, 16 KB request line + headers, 64 max headers, 10 s total request deadline, 5 s write watchdog, admission control of 64 total active connections (max 8 per peer address).
- **Port 8081 (Wake listener)**: 0 bytes body, 1 s deadline, admission control of 8 total connections (max 2 per peer address).
- **Consistent Error Codes**: Failed requests return explicit JSON error bodies with `ok: false`:
  - `400` (`invalid_request`): Malformed JSON, depth > 16, trailing data, non-integral values, or out-of-range knobs.
  - `403` (`request_forbidden` / `builtin_read_only`): Disallowed Host/Origin or attempted modification/deletion of built-in presets.
  - `404` (`not_found`): Unknown routes or preset IDs.
  - `405` (`method_not_allowed`): Incorrect HTTP method (includes `Allow` header).
  - `408` (`request_timeout`): Request deadline exceeded.
  - `413` (`limit_exceeded`): Payload/text exceeds length bounds or storage quotas.
  - `415` (`unsupported_media_type`): Non-empty body with incompatible Content-Type.
  - `502` (`device_write_failed`): Hardware setter returned false or readback non-convergence. Returns partial outcome `{ applied: [...], failed: "..." }`.
  - `503` (`busy`): Mutation lock held (>250 ms) or admission limit reached.
- **Power Actions**: Power commands return `202 Accepted` with `{ "accepted": true, "pending": true }` separating command acceptance from observed transition.

## Preset storage durability & quotas

- **AtomicFile Persistence**: Writes use Android `AtomicFile` (`.bak` rollbacks and fsync) to eliminate partial-write file corruption.
- **Quotas**: Maximum of 100 custom presets, 256 KB max store size, and 80 code points per preset name.
- **Corrupt File Preservation**: Corrupt stores are never overwritten or reset with defaults automatically; they are preserved as-is with explicit errors returned to allow recovery.
- **Recovery Backup**: Stored at `presets.json.recovery`.
- **Read-Only Built-ins**: Built-in presets (`balanced`) are read-only and return 403 on edit/delete.

## Automated regression tests

The repository includes automated regression test suites covering the JVM server, Home Assistant integration, web frontend, and installer tooling:

```bash
# Run the complete test matrix
./tests/run_all_tests.sh

# Run individual test suites
./tests/run_jvm_tests.sh        # JVM core: MiniHttp, RequestPolicy, SettingRules, DeviceCommands, PresetStore, Wake
./tests/run_ha_tests.sh         # Home Assistant: options flow, coordinator responses, entity error handling, unload
node --test tests/web/test_web.mjs # Web client: writable fields filtering, api errors, slider coalescing
./tests/tooling/test_tooling.sh # Tooling: path spaces, USB serial preservation, target parsing
```

## Custom keycodes

The web client uses standard Android keycodes for most buttons (`KEYCODE_DPAD_*`, `KEYCODE_BACK`, `KEYCODE_VOLUME_*`, etc.) and a few Xgimi-custom ones:

| Keycode | Constant           | Triggered by                    |
| ------- | ------------------ | ------------------------------- |
| 140     | `KEYCODE_F10`      | Physical focus button           |
| 26      | `KEYCODE_POWER`    | Physical power button           |
| 176     | `KEYCODE_SETTINGS` | Physical gear / settings button |

These pass through `injectInputEvent` and Xgimi's `PhoneWindowManager` intercept routes them the same way the physical remote does.
