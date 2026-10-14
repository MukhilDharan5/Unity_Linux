# Unity Android

This directory contains the native Kotlin companion: its connection service, feature handlers, permission flows, Adwaita-inspired Android interface, and tests. It connects to the independent [Unity Linux daemon](../Unity_Linux/README.md) over concurrent authenticated Wi-Fi / LAN and Bluetooth sessions. Android and Linux have separate builds and release artifacts.

![Android companion interface](docs/images/android-ui.png)

The image contains real Android widget renders from UI tests. The phone was not paired in these captures.

## Build

From `Unity_Android/`:

```sh
source scripts/env.sh
./scripts/build.sh
```

The build runs Android unit/UI tests and lint, then copies `dist/Unity-Companion-debug.apk` with a checksum and manifest. It requires JDK 21, Gradle 9.6.0, and Android SDK platform/build-tools 37. The debug APK supports Android API 29 or later. See the [build guide](docs/build.md) for local toolchain setup and validation details.

To update an existing debug installation, [build the APK](docs/build.md) and install it over the prior version without clearing app data. On the same local network, enter your computer's address and the verified full TLS certificate fingerprint shown by `unityctl discover`. Explicitly approve the pairing request on Linux. Only enable sensitive features and Android permissions you want to use.

- [Interface and screenshots](docs/ui.md)
- [Build and test guide](docs/build.md)
- [Linux wire protocol](../Unity_Linux/docs/protocol.md)
- [Security and Android permissions](../Unity_Linux/docs/security.md)
- [Feature support matrix](../Unity_Linux/docs/features.md)

Enable **Features → Media → Laptop audio on phone**, then select routing from the Linux Media page. Companion 0.1.3 supports encrypted low-latency PCM playback without microphone or notification-listener permission. See [audio routing](../Unity_Linux/docs/audio-routing.md).

Companion 0.1.4 adds **Remote device locking** and **Screen sharing** in Features → Remote control. Use **Overview → Lock computer** and **Sharing → Share phone screen / View computer screen**. Activate phone locking through Android’s device-admin dialog to allow Linux to lock the phone. Each screen capture asks for local OS approval. See [setup and validation](../Unity_Linux/docs/screen-and-lock.md).

Companion 0.1.8 defaults to **Settings → Connection method → Bluetooth + Wi-Fi / LAN**. Set both addresses and verify the desktop TLS fingerprint. Bluetooth carries controls and small updates; Wi-Fi / LAN carries files, photos, audio and screens. Both links stay connected, heartbeat and reconnect independently; new tasks fall back to the remaining link. Single-transport modes remain available. See [Bluetooth setup](../Unity_Linux/docs/bluetooth.md) and its physical-device validation limits.

Companion 0.1.9 adds native received-file, link and text notifications with actions to open the original item directly. Shared text also offers Copy text. Allow alerts from **Features → Notifications → Received file, link and text alerts**. Update Linux too for notifications in both directions. See [received-item behavior and validation](../Unity_Linux/docs/received-items.md).

Companion 0.1.12 provides [call management and SMS reports](../Unity_Linux/docs/telephony.md): ordinary Android permissions, contacts/history and dialing, with Linux oFono Bluetooth HFP call controls/audio while retaining the phone's usual Phone app. The desktop popup and Calls page share a live timer, answer/decline, hold/resume, end, mute, keypad and audio controls. An optional Android Phone app setup enables phone earpiece, speakerphone and Bluetooth routing. HFP works without that role. Physical calls and carrier receipts still require device validation.

Companion 0.1.13 adds [phone control of the shared laptop screen](../Unity_Linux/docs/screen-and-lock.md), an Android keyboard toggle, and a laptop-display-off option in both apps. The panel restores when sharing ends or its connection is lost; KDE unattended startup and panel power-off/restoration were checked on the real desktop; phone gestures and end-to-end latency still need physical-device validation.

Companion 0.1.14 adds [laptop push alerts for phone notifications and incoming SMS](../Unity_Linux/docs/phone-notifications.md). Notification-list snapshots and SMS history stay quiet on reconnect. Enable mirroring/listener access and SMS in Features; update Linux as well. Desktop alert clicks open the matching Unity page.

The KDE desktop update supports [starting directly from the phone](../Unity_Linux/docs/screen-and-lock.md#starting-entirely-from-the-phone-on-kde) with companion 0.1.13. Enable the dedicated unattended permission once; screen-off now powers down the laptop panel while a temporary mirror keeps phone control working.
