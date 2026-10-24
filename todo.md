# TODO

- [ ] is this realy true? Check that:
```
The interesting design decision is how key events get injected. The straightforward path — talking to Google's `com.google.android.tv.remote.service` over its mTLS pair / control protocol — is **broken on this device**: the control acceptor at port 6466 has a same-host filter (`isLoopbackAddress() || NetworkInterface.getByInetAddress(peer) != null` returns true → connection closed), so any client running on the projector itself gets dropped. Verified by decompiling the service across versions 2.1 → 6.7 — the filter is identical and present in every shipping build.
```
- [ ] whit that also this:
```
## Custom keycodes

The web client uses standard Android keycodes for most buttons (`KEYCODE_DPAD_*`, `KEYCODE_BACK`, `KEYCODE_VOLUME_*`, etc.) and a few Xgimi-custom ones:

| Keycode | Constant           | Triggered by                    |
| ------- | ------------------ | ------------------------------- |
| 140     | `KEYCODE_F10`      | Physical focus button           |
| 26      | `KEYCODE_POWER`    | Physical power button           |
| 176     | `KEYCODE_SETTINGS` | Physical gear / settings button |

These pass through `injectInputEvent` and Xgimi's `PhoneWindowManager` intercept routes them the same way the physical remote does.
```
- [ ] **Simplify Home Assistant Integration**:
  - Restructure and reduce the HA integration scope to only handle Wake-on-LAN (WOL) and projector power state verification (online/offline check).
  - Rely on the native Home Assistant Android TV integration for all navigation, media playback, volume, and remote control capabilities.

- [ ] Gemini said "I have decompiled and extracted the exact picture mode enum definitions directly from the projector's system framework (GmDisplayManager in
  XgimiTvSettingsPartner.apk & XgimiTvSettingsVendor.apk)." can you check other things as well this way? so i dont have to manually check everything else that the "stats for nerds" deliver.
