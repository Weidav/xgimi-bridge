#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "=================================================="
echo "RUNNING FULL XGIMI BRIDGE REGRESSION TEST MATRIX"
echo "=================================================="

echo ""
echo ">>> [1/4] JVM Core Engine Tests"
"$ROOT_DIR/tests/run_jvm_tests.sh"

echo ""
echo ">>> [2/4] Home Assistant Integration Tests"
"$ROOT_DIR/tests/run_ha_tests.sh"

echo ""
echo ">>> [3/4] Web Frontend Tests"
node --test "$ROOT_DIR/tests/web/test_web.mjs"

echo ""
echo ">>> [4/4] Tooling & Installer Tests"
"$ROOT_DIR/tests/tooling/test_tooling.sh"

echo ""
echo "=================================================="
echo "ALL TEST SUITES PASSED SUCCESSFULLY!"
echo "=================================================="
