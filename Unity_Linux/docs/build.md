# Linux build, install and Fedora dependencies

Run these commands from `Unity_Linux/`. The tested host is Fedora 44 with GTK 4.22.5, libadwaita 1.9.4, Python 3.14.8, and Rust 1.99.0. You need Rust/Cargo, GCC, pkg-config, GTK4 >= 4.10, libadwaita >= 1.5, PyGObject, Pycairo, a user session bus, and a systemd user manager. BlueZ is optional while BLE transport is unavailable. The lockfile has been verified with Rust 1.99.0; the workspace baseline is 1.88.

Run `scripts/fedora-deps.sh` explicitly to install OS packages. Build scripts do not change the OS package set. `source scripts/env.sh` uses the optional project-local Rust toolchain in `.tools/` without changing your shell profile.

```sh
source scripts/env.sh
./scripts/build.sh
./scripts/test.sh
```

The build creates `target/release/unityd` and `target/release/unityctl`, copies them to `dist/`, and writes a checksum and manifest. Install them on a normal Linux filesystem:

```sh
python3 scripts/install.py
systemctl --user daemon-reload
systemctl --user enable --now unityd.service
```

`scripts/install.py` supports `--component daemon|gtk|all`, `--prefix`, `--destdir` for staging, and `--debug` for developer binaries. The daemon and CLI are colocated in `libexec/unity`; `bin/unityctl` is a symlink. The systemd user and D-Bus activation templates are expanded for the selected prefix. The default prefix is `~/.local`. The installer does not edit trust, open pairing, or start services. A staging install is covered by architecture tests.

A GTK-only install or removal does not control the daemon. Remove just the frontend with `scripts/uninstall-gtk.py`. The GTK entry point uses the installed `unityctl launch-gtk` command, which verifies and approves only its own child. Install or update the daemon and CLI component before the GTK client, because the launcher needs the `GetAccessStatus` method and `AccessRequested` signal.

LAN uses TCP 17161 and discovery UDP 17162. Fedora's firewall may block inbound traffic. If needed, explicitly allow those ports in your trusted LAN zone after reviewing your network setup. Unity does not open firewall ports or assume Wi-Fi Direct, hotspot/station concurrency, or Classic Bluetooth telephony. Manual IP entry works when broadcast discovery is blocked.

Credential storage must enforce POSIX ownership and 0700/0600 modes. This source workspace may be on NTFS, but installed binaries and state belong on a Linux filesystem. Do not configure `--state-dir` on a world-writable, FAT, or NTFS mount. Integration tests use isolated temporary directories.

The Android toolchain, APK, and tests live in [Unity_Android](../../Unity_Android/README.md), with [separate build instructions](../../Unity_Android/docs/build.md). Linux and Android share the versioned [wire protocol](protocol.md).
