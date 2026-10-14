# Unity Linux

The Linux project contains the independent Rust `unityd` service, a versioned D-Bus API, `unityctl`, and a GTK4/libadwaita client. Closing or removing the graphical client does not stop the service. The [Android companion](../Unity_Android/README.md) is a separate project that speaks the same [wire protocol](docs/protocol.md).

```text
unityd/            Tokio user daemon
unity-core/        device lifecycle, sessions, operations, configuration
unity-protocol/    wire messages and capabilities
unity-transport/   framed TLS LAN / Bluetooth RFCOMM and transport selection
unity-security/    identity, pairing credentials, authentication
unity-plugins/     feature modules and transfer handling
unity-ipc/         versioned zbus public API
unityctl/          headless D-Bus client
unity-gtk/         Python/PyGObject GTK4 + libadwaita D-Bus client
unity-screen/      consent-based portal/PipeWire capture helper
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

The daemon's state needs a Linux filesystem that enforces ownership and permissions. Source on an NTFS mount is fine; install binaries and keep credentials on a normal Linux filesystem. The companion can pair after you compare its phone identity and the computer's full TLS certificate fingerprint. Sensitive Android features require phone-side opt-in and permissions. LAN and Bluetooth Classic RFCOMM stay authenticated together. Bluetooth carries controls and small updates; Wi-Fi / LAN carries files, photos, audio and screen streams. New work falls back to the surviving link while the other reconnects. BLE remains unavailable. Call management and separate HFP computer audio are implemented with companion 0.1.10; see [telephony setup](docs/telephony.md). See [Bluetooth setup and validation](docs/bluetooth.md). Screen sharing and remote locking are implemented in both directions with local permission flows.

## Documentation

- [Build and dependencies](docs/build.md)
- [Architecture](docs/architecture.md)
- [Public D-Bus API](docs/dbus-api.md)
- [Wire protocol](docs/protocol.md)
- [Security and permissions](docs/security.md)
- [Feature matrix](docs/features.md)
- [Testing and limitations](docs/testing.md)
- [Phone battery power and time estimates](docs/battery-estimates.md)
- [Received file, link and text notifications](docs/received-items.md)

The [master README](../README.md) shows screenshots of both applications.

[Laptop audio on phone](docs/audio-routing.md) is available from the Media page with companion 0.1.3+, PipeWire-Pulse/PulseAudio and pulseaudio-utils. Update both the daemon and Android app to use it.

[Screen sharing and device locking](docs/screen-and-lock.md) are available in both directions with companion 0.1.4+. Use Remote Control on Linux and Overview/Sharing on Android.

Drop one or more files onto any Unity page or the phone screen viewer to open Files → Send to device with those files selected. Choose the destination using the sidebar device selector, then click Send to device. Selection remains available while disconnected or after a failed transfer. Any file type is accepted up to 64 MiB per file; compress folders before sending. With companion 0.1.5+, received files are saved in the phone’s Downloads/Unity folder after integrity verification.

Phone sound (ringer / DND) and phone internet-sharing requests are now in the Linux Media tab.

With companion 0.1.9, receiving a file, link or shared text creates a native notification on either device. Tap it to open that exact item; Android text alerts also offer Copy text. Enable phone alerts in Features → Notifications. See [received-item notifications](docs/received-items.md).

[Calls and SMS](docs/telephony.md) now include desktop dialing, contacts, recent calls, native Answer/Reject alerts, in-call controls, Bluetooth computer audio and carrier report tracking.

Companion 0.1.13 adds [phone control of the shared laptop screen](docs/screen-and-lock.md), an Android keyboard toggle, and a laptop-display-off option in both apps. The panel restores when sharing ends or its connection is lost; KDE unattended startup and panel power-off/restoration were checked on the real desktop; phone gestures and end-to-end latency still need physical-device validation.

The KDE desktop update supports [starting directly from the phone](docs/screen-and-lock.md#starting-entirely-from-the-phone-on-kde) with companion 0.1.13. Enable the dedicated unattended permission once; screen-off now powers down the laptop panel while a temporary mirror keeps phone control working.
