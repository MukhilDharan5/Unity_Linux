# Unity

Unity is a local-first companion platform with two independent projects. The Linux daemon provides the public D-Bus API; its GTK application and CLI are clients of that API. The Android companion communicates with the daemon over simultaneous authenticated Wi-Fi / LAN and Bluetooth connections.

| Project | Contents | Build guide |
| --- | --- | --- |
| [Unity_Linux](Unity_Linux/README.md) | Rust daemon, protocol, transports, D-Bus API, CLI, GTK frontend, systemd unit, and Linux tests | [Linux build](Unity_Linux/docs/build.md) |
| [Unity_Android](Unity_Android/README.md) | Kotlin companion, Android services, permission flows, UI, and Android tests | [Android build](Unity_Android/docs/build.md) |

Both projects can be built from their own directories. They share [wire protocol v1](Unity_Linux/docs/protocol.md); changing messages or capabilities requires coordinating both sides. The Linux daemon runs without either graphical client. See the [feature matrix](Unity_Linux/docs/features.md) for the distinction between implemented, partial, unsupported, and hardware-untested features.

## Linux app

![Unity Linux Overview](Unity_Linux/docs/images/linux-overview.png)

![Unity Linux Media](Unity_Linux/docs/images/linux-media.png)

![Unity Linux Photos](Unity_Linux/docs/images/linux-photos.png)

These screenshots render the real GTK widgets with isolated test data. They do not show a live phone session.

## Android app

![Unity Android overview, sharing, features, and dark appearance](Unity_Android/docs/images/android-ui.png)

These screenshots render the production Android widgets in automated UI tests. No computer was paired in the screenshots.

## Get started

```sh
cd Unity_Linux
source scripts/env.sh
./scripts/build.sh
python3 scripts/install.py
systemctl --user daemon-reload
systemctl --user enable --now unityd.service

cd ../Unity_Android
source scripts/env.sh
./scripts/build.sh
```

Build the Android APK using the [Android build guide](Unity_Android/docs/build.md), then install it over an existing debug companion to retain pairing. On the phone, enter the computer's LAN address and the verified certificate fingerprint printed by `unityctl discover`. Review and approve the pending phone identity on the Linux computer. Follow each project's build guide for dependencies and the full setup procedure.

There is no mandatory account, external server, telemetry, or root daemon. Unity remains an early functional implementation with platform and permission limits documented in the [testing guide](Unity_Linux/docs/testing.md).

Unity now includes [laptop audio routing](Unity_Linux/docs/audio-routing.md), plus [screen sharing and remote device locking in both directions](Unity_Linux/docs/screen-and-lock.md). Update Linux and the Android companion together; phone permissions remain opt-in.

Companion 0.1.8 maintains [Bluetooth and Wi-Fi / LAN together](Unity_Linux/docs/bluetooth.md), routing controls and small updates over Bluetooth and files, photos, audio and screen sharing over Wi-Fi / LAN. Each link reconnects independently with fallback for new work. Linux Media also contains the Phone sound and internet-sharing controls.

Companion 0.1.9 and the updated Linux daemon post [native notifications for received files, links and text](Unity_Linux/docs/received-items.md). Click an alert to open that exact item on its destination device.

Companion 0.1.14 and the updated Linux daemon add [desktop push alerts for new phone notifications and SMS](Unity_Linux/docs/phone-notifications.md), including operation while GTK is closed and actions opening the paired device's Notifications/Messages page.

Companion 0.1.12 provides [call management and SMS reports](Unity_Linux/docs/telephony.md): ordinary Android permissions, contacts/history and dialing, with Linux oFono Bluetooth HFP call controls/audio while retaining the phone's usual Phone app. The desktop popup and Calls page share a live timer, answer/decline, hold/resume, end, mute, keypad and audio controls. An optional Android Phone app setup enables phone earpiece, speakerphone and Bluetooth routing. HFP works without that role. Physical calls and carrier receipts still require device validation.

Companion 0.1.13 adds [phone control of the shared laptop screen](Unity_Linux/docs/screen-and-lock.md), an Android keyboard toggle, and a laptop-display-off option in both apps. The panel restores when sharing ends or its connection is lost; KDE unattended startup and panel power-off/restoration were checked on the real desktop; phone gestures and end-to-end latency still need physical-device validation.

The KDE desktop update supports [starting directly from the phone](Unity_Linux/docs/screen-and-lock.md#starting-entirely-from-the-phone-on-kde) with companion 0.1.13. Enable the dedicated unattended permission once; screen-off now powers down the laptop panel while a temporary mirror keeps phone control working.
