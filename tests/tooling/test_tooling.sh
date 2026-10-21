#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
echo "=== Running Tooling Tests ==="

TMP_DIR="$(mktemp -d /tmp/tooling_test_XXXXXX)"
trap 'rm -rf "$TMP_DIR"' EXIT

MOCK_BIN="$TMP_DIR/bin"
mkdir -p "$MOCK_BIN"

# Mock adb
cat <<'EOF' > "$MOCK_BIN/adb"
#!/usr/bin/env bash
if [ "${1:-}" = "devices" ]; then
    cat "$TMP_DEVICES_FILE"
    exit 0
fi
echo "MOCK_ADB_CALLED: $@" >> "$TMP_LOG_FILE"
EOF
chmod +x "$MOCK_BIN/adb"

# Mock apksigner
cat <<'EOF' > "$MOCK_BIN/apksigner"
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$MOCK_BIN/apksigner"

export PATH="$MOCK_BIN:$PATH"
export ANDROID_SDK_ROOT="/nonexistent" # force fallback to mock apksigner in PATH

FAKE_APK="$TMP_DIR/fake.apk"
touch "$FAKE_APK"

export TMP_LOG_FILE="$TMP_DIR/adb.log"
export TMP_DEVICES_FILE="$TMP_DIR/devices.txt"

# Test 1: Explicit IPv4 without port
: > "$TMP_LOG_FILE"
PROJECTOR="192.168.1.100" APK="$FAKE_APK" "$ROOT_DIR/app/install.sh" >/dev/null 2>&1
grep -q -- "-s 192.168.1.100:5555 install" "$TMP_LOG_FILE" || {
    echo "FAIL: Explicit IPv4 should append default port 5555"
    exit 1
}
echo "  [PASS] Explicit IPv4 target"

# Test 2: Explicit IPv4 with port
: > "$TMP_LOG_FILE"
PROJECTOR="192.168.1.100:6000" APK="$FAKE_APK" "$ROOT_DIR/app/install.sh" >/dev/null 2>&1
grep -q -- "-s 192.168.1.100:6000 install" "$TMP_LOG_FILE" || {
    echo "FAIL: Explicit IPv4 with port should preserve port"
    exit 1
}
echo "  [PASS] Explicit IPv4 with port"

# Test 3: Bracketed IPv6 without port
: > "$TMP_LOG_FILE"
PROJECTOR="[2001:db8::1]" APK="$FAKE_APK" "$ROOT_DIR/app/install.sh" >/dev/null 2>&1
grep -q -- "-s \[2001:db8::1\]:5555 install" "$TMP_LOG_FILE" || {
    echo "FAIL: Bracketed IPv6 should append :5555"
    exit 1
}
echo "  [PASS] Bracketed IPv6 target"

# Test 4: Unbracketed IPv6 literal should be rejected
set +e
PROJECTOR="2001:db8::1" APK="$FAKE_APK" "$ROOT_DIR/app/install.sh" >/dev/null 2>&1
RES=$?
set -e
if [ "$RES" -eq 0 ]; then
    echo "FAIL: Unbracketed IPv6 should be rejected"
    exit 1
fi
echo "  [PASS] Unbracketed IPv6 rejection"

# Test 5: Auto-detected USB device serial preserved exactly
: > "$TMP_LOG_FILE"
cat <<'EOF' > "$TMP_DEVICES_FILE"
List of devices attached
HT12345678	device
EOF
PROJECTOR="" APK="$FAKE_APK" "$ROOT_DIR/app/install.sh" >/dev/null 2>&1
grep -q -- "-s HT12345678 install" "$TMP_LOG_FILE" || {
    echo "FAIL: USB device serial should be preserved exactly without :5555"
    exit 1
}
echo "  [PASS] USB serial preservation"

# Test 6: Multiple devices should error out
cat <<'EOF' > "$TMP_DEVICES_FILE"
List of devices attached
HT12345678	device
emulator-5554	device
EOF
set +e
PROJECTOR="" APK="$FAKE_APK" "$ROOT_DIR/app/install.sh" >/dev/null 2>&1
RES=$?
set -e
if [ "$RES" -eq 0 ]; then
    echo "FAIL: Multiple devices should exit with error"
    exit 1
fi
echo "  [PASS] Multiple devices rejection"

# Test 7: Missing APK should error out
set +e
PROJECTOR="192.168.1.100" APK="$TMP_DIR/missing.apk" "$ROOT_DIR/app/install.sh" >/dev/null 2>&1
RES=$?
set -e
if [ "$RES" -eq 0 ]; then
    echo "FAIL: Missing APK should exit with error"
    exit 1
fi
echo "  [PASS] Missing APK rejection"

echo "=== ALL TOOLING TESTS PASSED ==="
