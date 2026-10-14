#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/env.sh"
cd "$UNITY_ROOT"
cargo fmt --all --check
cargo clippy --locked --workspace --all-targets -- -D warnings
cargo test --locked --workspace
cargo build --locked --workspace
python3 -m py_compile unity-gtk/unity.py
python3 tests/architecture.py
dbus-run-session -- python3 tests/integration.py
dbus-run-session -- python3 tests/telephony_integration.py
dbus-run-session -- python3 tests/hfp_integration.py
dbus-run-session -- python3 tests/bluetooth_integration.py
dbus-run-session -- python3 tests/dual_transport_integration.py
dbus-run-session -- python3 tests/receipt_integration.py
dbus-run-session -- python3 tests/phone_alerts_integration.py
dbus-run-session -- python3 tests/audio_integration.py
dbus-run-session -- python3 tests/screen_integration.py
dbus-run-session -- python3 tests/screen_capture_test.py
python3 tests/display_guard_test.py
dbus-run-session -- python3 tests/launcher.py
