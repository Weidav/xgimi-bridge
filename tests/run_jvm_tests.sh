#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "Building and running JVM regression tests..."
mkdir -p tests/jvm/build

javac -cp tests/jvm/lib/android-json-0.0.20131108.vaadin1.jar:tests/jvm/build \
  -d tests/jvm/build \
  $(find tests/jvm/stubs -name "*.java") \
  app/src/com/xgimi/bridge/SettingRules.java \
  app/src/com/xgimi/bridge/RequestPolicy.java \
  app/src/com/xgimi/bridge/DeviceCommands.java \
  app/src/com/xgimi/bridge/PresetStore.java \
  app/src/com/xgimi/bridge/MiniHttp.java \
  app/src/com/xgimi/bridge/HttpServer.java \
  app/src/com/xgimi/bridge/KeyDispatcher.java \
  app/src/com/xgimi/bridge/MtkPqClient.java \
  app/src/com/xgimi/bridge/IoUtils.java \
  app/src/com/xgimi/bridge/DeviceState.java \
  app/src/com/xgimi/bridge/JsonUtils.java \
  app/src/com/xgimi/bridge/BinderTransact.java \
  app/src/com/xgimi/bridge/HdmiInputHelper.java \
  $(find tests/jvm/src -name "*.java")

java -Xmx64m -cp tests/jvm/lib/android-json-0.0.20131108.vaadin1.jar:tests/jvm/build com.xgimi.bridge.TestRunner
