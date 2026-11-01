# Unity

Companion **0.1.36** and the matching Linux update support [Bluetooth plus the phone's own Wi-Fi hotspot](Unity_Linux/docs/direct-hotspot.md), including changing hotspot addresses and recovery from a stale router connection. Unity's local features do not need a separate router or internet access.

Companion **0.1.35** adds a desktop [Connectivity tab](Unity_Linux/docs/connectivity.md): observed phone Mobile Hotspot state, connected Bluetooth devices sent from the phone, and Samsung routine switches for Mobile Hotspot and disconnecting a selected device.

Companion **0.1.34** adds [Samsung Routines triggers](Unity_Linux/docs/samsung-routines.md) for hotspot on/off and disconnecting a phone-configured Bluetooth device, with matching desktop controls.

Companion **0.1.33** and matching desktop updates add [automatic clipboard sync](Unity_Linux/docs/clipboard-sync.md) with no send/receive buttons. Android background sending uses authorized Shizuku access while keeping your current keyboard; incoming text is applied automatically.

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

Companion 0.1.8 maintains [Bluetooth and Wi-Fi / LAN together](Unity_Linux/docs/bluetooth.md), routing controls and small updates over Bluetooth and files, photos, audio and screen sharing over Wi-Fi / LAN. Each link reconnects independently with fallback for new work. Phone sound is in Media; phone Mobile Hotspot, Bluetooth devices and internet sharing are in Connectivity.

Companion 0.1.9 and the updated Linux daemon post [native notifications for received files, links and text](Unity_Linux/docs/received-items.md). Click an alert to open that exact item on its destination device.

Companion 0.1.14 and the updated Linux daemon add [desktop push alerts for new phone notifications and SMS](Unity_Linux/docs/phone-notifications.md), including operation while GTK is closed and actions opening the paired device's Notifications/Messages page.

Companion 0.1.12 provides [call management and SMS reports](Unity_Linux/docs/telephony.md): ordinary Android permissions, contacts/history and dialing, with Linux oFono Bluetooth HFP call controls/audio while retaining the phone's usual Phone app. The desktop popup and Calls page share a live timer, answer/decline, hold/resume, end, mute, keypad and audio controls. An optional Android Phone app setup enables phone earpiece, speakerphone and Bluetooth routing. HFP works without that role. Physical calls and carrier receipts still require device validation.

Companion 0.1.13 adds [phone control of the shared laptop screen](Unity_Linux/docs/screen-and-lock.md), an Android keyboard toggle, and a laptop-display-off option in both apps. The panel restores when sharing ends or its connection is lost; KDE unattended startup and panel power-off/restoration were checked on the real desktop; phone gestures and end-to-end latency still need physical-device validation.

The KDE desktop update supports [starting directly from the phone](Unity_Linux/docs/screen-and-lock.md#starting-entirely-from-the-phone-on-kde) with companion 0.1.13. Enable the dedicated unattended permission once; screen-off now powers down the laptop panel while a temporary mirror keeps phone control working.

Both apps now support [laptop and phone media players with loop protection](Unity_Linux/docs/media-sync.md), using companion 0.1.15 and the current Linux build.

Companion 0.1.16 and Linux add [independent laptop/phone volume and mute controls](Unity_Linux/docs/device-volume.md) in both Media screens, with quiet external updates and replay protection.

Companion 0.1.17 and Linux fix [laptop audio streaming](Unity_Linux/docs/audio-routing.md) under Wi-Fi delays, packet gaps, and transient phone audio interruptions, with playback error reporting and silent-volume hints.

Companion 0.1.18 and Linux repair Find phone with an audible alarm and synchronized Ring/Stop controls.

Companion **0.1.22** and matching Linux support [audio in either direction](Unity_Linux/docs/audio-routing.md), with Automatic, Wi-Fi stereo and Bluetooth mono choices while keeping both links connected. Phone media capture uses Android's per-session approval.

Companion **0.1.23** and matching Linux add [phone-as-main-display handoff](Unity_Linux/docs/screen-and-lock.md): a temporary virtual monitor becomes primary and the OS disables the laptop panel, with automatic layout restoration. The phone’s computer viewer now supports sensor rotation and an explicit landscape/portrait control.

Companion **0.1.24** and matching Linux add [extended display](Unity_Linux/docs/screen-and-lock.md#phone-as-an-extended-display): use the phone as a separate monitor while continuing to use the laptop display. The existing primary-display handoff remains a separate choice.
