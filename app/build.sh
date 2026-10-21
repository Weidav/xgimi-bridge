#!/usr/bin/env bash
set -e
ASR="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT="$ASR/build-tools/34.0.0"
PLATFORM="$ASR/platforms/android-34/android.jar"
PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJ/build"

if [ -n "$OUT" ] && [ -d "$OUT" ] && [ "$OUT" = "$PROJ/build" ]; then
    rm -rf "$OUT"
fi
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/5] javac"
find "$PROJ/src" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$PLATFORM" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "[2/5] d8 (classes -> classes.dex)"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$BT/d8" --release --output "$OUT/dex" --lib "$PLATFORM" @"$OUT/classes.txt"

echo "[3/5] aapt2 link (manifest, no resources)"
"$BT/aapt2" link \
    --manifest "$PROJ/AndroidManifest.xml" \
    -I "$PLATFORM" \
    --min-sdk-version 34 --target-sdk-version 34 \
    -o "$OUT/base.apk"

echo "[4/5] inject classes.dex + assets/"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -j "$OUT/unsigned.apk" classes.dex )
( cd "$PROJ" && zip -r "$OUT/unsigned.apk" assets/ -x "*.DS_Store" )

echo "[5/5] zipalign + apksigner"
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS="$PROJ/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -v -keystore "$KS" -storepass android \
        -alias androiddebugkey -keypass android \
        -dname "CN=Bridge,O=Test,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android \
    --ks-key-alias androiddebugkey --key-pass pass:android \
    --out "$OUT/bridge.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify "$OUT/bridge.apk"

ls -la "$OUT/bridge.apk"
echo "DONE"
