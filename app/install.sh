#!/usr/bin/env bash
# Install the bridge APK to the projector and grant runtime exemptions it needs.
#
# Target selection (in order):
#   1. PROJECTOR=<host[:port]> ./install.sh   — explicit, e.g. PROJECTOR=192.168.3.17 or PROJECTOR=[::1]
#   2. Auto-pick from `adb devices` when exactly one device is connected.
#   3. Otherwise fail with instructions.
set -e
PROJ="$(cd "$(dirname "$0")" && pwd)"
APK="${APK:-$PROJ/build/bridge.apk}"
PKG=com.xgimi.bridge

# 1. Check required tools
if ! command -v adb >/dev/null 2>&1; then
    echo "Error: adb tool is required but not found in PATH." >&2
    exit 1
fi

# 2. Check input APK
if [ ! -f "$APK" ]; then
    echo "Error: APK file not found at $APK. Build the project first with ./build.sh" >&2
    exit 1
fi

# 3. Verify APK signature if apksigner is available
ASR="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT_APKSIGNER="$ASR/build-tools/34.0.0/apksigner"
if [ -x "$BT_APKSIGNER" ]; then
    "$BT_APKSIGNER" verify "$APK" >/dev/null 2>&1 || {
        echo "Error: APK signature verification failed for $APK." >&2
        exit 1
    }
elif command -v apksigner >/dev/null 2>&1; then
    apksigner verify "$APK" >/dev/null 2>&1 || {
        echo "Error: APK signature verification failed for $APK." >&2
        exit 1
    }
fi

# 4. Target selection
TARGET=""
if [ -n "$PROJECTOR" ]; then
    # Explicit host/IP input
    P="$PROJECTOR"
    if [[ "$P" =~ ^\[(.*)\](:[0-9]+)?$ ]]; then
        # Bracketed IPv6: [fe80::1] or [fe80::1]:5555
        if [[ "$P" =~ ^\[.*\]:[0-9]+$ ]]; then
            TARGET="$P"
        else
            TARGET="$P:5555"
        fi
    elif [[ "$P" =~ :.*: ]]; then
        # Unbracketed IPv6 literal with multiple colons
        echo "Error: Ambiguous unbracketed IPv6 address '$P'. Enclose IPv6 in brackets: '[$P]' or '[$P]:5555'" >&2
        exit 1
    elif [[ "$P" =~ :[0-9]+$ ]]; then
        # Host:port or IPv4:port
        TARGET="$P"
    else
        # Bare hostname or IPv4 without port -> append default port 5555
        TARGET="$P:5555"
    fi
else
    # Auto-detected from adb devices
    devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    if [ -z "$devices" ]; then
        echo "No ADB device connected." >&2
        echo "Pair over Wi-Fi first, then re-run:" >&2
        echo "    adb connect <projector-ip>:5555" >&2
        echo "Or set PROJECTOR=<host[:port]> ./install.sh" >&2
        exit 1
    fi

    count=$(printf '%s\n' "$devices" | grep -c .)
    if [ "$count" = "1" ]; then
        # Preserve auto-detected USB / emulator / TCP serials exactly
        TARGET="$devices"
        echo "Using auto-detected device: $TARGET"
    else
        echo "Multiple ADB devices connected — pick one:" >&2
        printf '%s\n' "$devices" | sed 's/^/    /' >&2
        echo "Re-run with: PROJECTOR=<host[:port]> ./install.sh" >&2
        exit 1
    fi
fi

echo "Installing to target: $TARGET"
adb -s "$TARGET" install -r "$APK"

# Doze whitelist — keeps the persisted JobScheduler reliable across boots
adb -s "$TARGET" shell "dumpsys deviceidle whitelist +$PKG"

# AppOps — allow background activity launch + background run
adb -s "$TARGET" shell "appops set $PKG SYSTEM_ALERT_WINDOW allow"
adb -s "$TARGET" shell "appops set $PKG START_FOREGROUND allow"
adb -s "$TARGET" shell "appops set $PKG RUN_IN_BACKGROUND allow"
adb -s "$TARGET" shell "appops set $PKG RUN_ANY_IN_BACKGROUND allow"

# Bridge has no LAUNCHER icon — start the helper activity by explicit component
# (it schedules the persistent JobScheduler job and starts BridgeService).
adb -s "$TARGET" shell "pkill -f '[K]eyDispatcher' || true"
adb -s "$TARGET" shell "am force-stop $PKG"
adb -s "$TARGET" shell "am start -n $PKG/.LauncherActivity"
sleep 2
adb -s "$TARGET" shell "cmd jobscheduler run $PKG 7331" >/dev/null 2>&1 || true
echo "Installed + whitelisted + job scheduled (persists across reboot)."
