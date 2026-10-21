#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "Running Home Assistant integration regression tests..."
/workspaces/xgimi-bridge/homeassistant/.venv/bin/python -m unittest tests/ha/test_ha_integration.py
