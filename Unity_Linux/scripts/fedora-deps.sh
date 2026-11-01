#!/usr/bin/env bash
set -euo pipefail
# Explicitly run this script to install OS packages; it never runs as part of the GUI.
sudo dnf install rust cargo rustfmt clippy gcc pam-devel openssl python3-cryptography pkgconf-pkg-config \
 gtk4 libadwaita python3-gobject python3-cairo python3-pillow dbus-daemon bluez ofono pulseaudio-utils wl-clipboard xclip python3-gstreamer1 gstreamer1-plugins-base gstreamer1-plugins-good pipewire-gstreamer xdg-desktop-portal
printf 'For the separate Android build, see ../Unity_Android/docs/build.md.\n'
