#!/usr/bin/env bash
set -euo pipefail
# Explicitly run this script to install OS packages; it never runs as part of the GUI.
sudo dnf install rust cargo rustfmt clippy gcc pkgconf-pkg-config \
 gtk4 libadwaita python3-gobject python3-cairo dbus-daemon bluez
printf 'For the separate Android build, see ../Unity_Android/docs/build.md.\n'
