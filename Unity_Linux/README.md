# Unity Linux

The Linux project contains the independent Rust `unityd` service, a versioned D-Bus API, `unityctl`, and a GTK4/libadwaita client. Closing or removing the graphical client does not stop the service. The [Android companion](../Unity_Android/README.md) is a separate project that speaks the same [wire protocol](docs/protocol.md).

```text
unityd/            Tokio user daemon
unity-core/        device lifecycle, sessions, operations, configuration
unity-protocol/    wire messages and capabilities
unity-transport/   framed TLS LAN and transport selection
unity-security/    identity, pairing credentials, authentication
unity-plugins/     feature modules and transfer handling
unity-ipc/         versioned zbus public API
unityctl/          headless D-Bus client
unity-gtk/         Python/PyGObject GTK4 + libadwaita D-Bus client
```

## Build and install on Fedora

Run these commands from `Unity_Linux/`:

```sh
./scripts/fedora-deps.sh
source scripts/env.sh
./scripts/build.sh
python3 scripts/install.py
systemctl --user daemon-reload
systemctl --user enable --now unityd.service
~/.local/bin/unityctl discover
~/.local/bin/unity-gtk
```

The GTK launcher starts the fixed installed client and approves only that child process using the public D-Bus authorization API. Third-party clients must request and receive their own access grants. Install only the daemon and CLI with `python3 scripts/install.py --component daemon`, or only the GTK client with `--component gtk`. `scripts/uninstall-gtk.py` removes the GTK client while leaving daemon binaries, service, CLI, and trust intact. Installing files does not start or stop services automatically.

The daemon's state needs a Linux filesystem that enforces ownership and permissions. Source on an NTFS mount is fine; install binaries and keep credentials on a normal Linux filesystem. The companion can pair after you compare its phone identity and the computer's full TLS certificate fingerprint. Sensitive Android features require phone-side opt-in and permissions. The currently implemented high-bandwidth transport is LAN; BLE, call answering, and screen mirroring are not advertised as working features.

## Documentation

- [Build and dependencies](docs/build.md)
- [Architecture](docs/architecture.md)
- [Public D-Bus API](docs/dbus-api.md)
- [Wire protocol](docs/protocol.md)
- [Security and permissions](docs/security.md)
- [Feature matrix](docs/features.md)
- [Testing and limitations](docs/testing.md)

The [master README](../README.md) shows screenshots of both applications.
