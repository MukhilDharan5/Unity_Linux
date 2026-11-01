# Unity Android

Companion **0.1.36** adds [direct phone-hotspot routing](../Unity_Linux/docs/direct-hotspot.md): keep Bluetooth connected, join your phone's hotspot from the laptop, and use Unity's local features without a router. Matching Linux updates restore address discovery and retire stale router-side LAN sessions.

Companion **0.1.35** adds opt-in [phone connectivity reports](../Unity_Linux/docs/connectivity.md) for the new desktop Connectivity tab. Enable **Features → Phone connectivity**, allow Nearby devices, and configure Samsung `UNITY_HOTSPOT_ON` / `UNITY_HOTSPOT_OFF` and device-specific disconnect routines. Device names, addresses and connected profiles are sent to your paired computer; switches follow the phone's reported state.

Companion **0.1.34** adds an opt-in [Samsung Routines bridge](../Unity_Linux/docs/samsung-routines.md): fixed notification keywords for Mobile Hotspot on/off and disconnecting the Bluetooth device selected in your Samsung routine. Set up the three routines in **Features → Samsung Routines** and update the desktop app. Unity reports trigger submission; Samsung performs the action.

Preview **0.1.31** selects the best available phone media cover across embedded images, image URIs, provider thumbnails and notification artwork, preserving up to 1024 pixels. Update Linux too for phone playback in desktop Now Playing, with native play/pause/skip/seek/phone-volume controls, and original photo/video dragging from the desktop gallery. See [media synchronization](../Unity_Linux/docs/media-sync.md) and [gallery dragging](../Unity_Linux/docs/gallery.md).

Preview **0.1.30** remembers public/private Wi-Fi choices across reconnects and restarts, with Android's Wi-Fi name permission. Received file notifications now open supported viewers directly with read-only access and retain a visible fallback if an item cannot open. See [network privacy](../Unity_Linux/docs/public-wifi.md) and [received items](../Unity_Linux/docs/received-items.md).

Preview **0.1.29** fixes fingerprint notifications opening Unity without the approval page. Notifications now open the exact request directly, pending requests can be reopened from **Features → Review fingerprint request**, and expiry/biometric errors stay visible. Update over the existing Preview app to keep the enrolled credential.

Preview **0.1.28** publishes laptop playback as native Android media controls: Now playing, lock screen, Quick Settings and media keys, with play/pause, previous/next, seeking and laptop volume where supported. Media covers now preserve up to 1024 pixels and use original image sources. Install the matching Linux build for full-quality artwork in both directions; see [media synchronization](../Unity_Linux/docs/media-sync.md).

Preview **0.1.27** extends the app’s Adwaita-inspired styling to the dialer, incoming and active calls, fingerprint setup and approval, dialogs, media selector/sliders, and screen-viewer controls. These screens follow the saved appearance and adapt to larger text, system bars and the keyboard.

Preview **0.1.26** adds **Features → Laptop fingerprint approval** and credential export for [Linux PAM/sudo/login setup](../Unity_Linux/docs/phone-authentication.md). Each approval uses a hardware-backed strong-biometric signing operation, with password fallback on the laptop.

This directory contains the native Kotlin companion: its connection service, feature handlers, permission flows, Adwaita-inspired Android interface, and tests. It connects to the independent [Unity Linux daemon](../Unity_Linux/README.md) over concurrent authenticated Wi-Fi / LAN and Bluetooth sessions. Android and Linux have separate builds and release artifacts.

Preview **0.1.25** fixes campus/public Wi-Fi: private address refresh over Bluetooth, campus IPv4/IPv6 support, a compatible LAN port and explicit Wi-Fi socket routing when mobile data is the default. All device data uses pinned TLS 1.3 on every network. Unknown Wi-Fi trust prompts for Public/shared or Private; plaintext discovery is removed. See [network behavior and setup](../Unity_Linux/docs/public-wifi.md).

![Android companion interface](docs/images/android-ui.png)

The image contains real Android widget renders from UI tests. The phone was not paired in these captures.

## Build

From `Unity_Android/`:

```sh
source scripts/env.sh
./scripts/build.sh
```

The build runs Android unit/UI tests and lint, then copies `dist/Unity-Preview-debug.apk` and a versioned filename with a checksum and manifest. It requires JDK 21, Gradle 9.6.0, and Android SDK platform/build-tools 37. The debug APK supports Android API 29 or later. See the [build guide](docs/build.md) for the required persistent signing key and validation details.

**Unity Preview** installs alongside the original Unity app, preserving its data when the original signing key is unavailable. Disconnect the original app, then pair Preview once: on the same local network, enter your computer's address and the verified full TLS certificate fingerprint shown by `unityctl discover`. Explicitly approve the pairing request on Linux. Only enable sensitive features and Android permissions you want to use. Later Preview updates preserve its pairing through the same package and pinned signing key. Original-package builds require the original key; see the [build guide](docs/build.md).

- [Interface and screenshots](docs/ui.md)
- [Build and test guide](docs/build.md)
- [Linux wire protocol](../Unity_Linux/docs/protocol.md)
- [Security and Android permissions](../Unity_Linux/docs/security.md)
- [Feature support matrix](../Unity_Linux/docs/features.md)

Enable **Features → Media → Laptop audio on phone**, then select routing from the Linux Media page. Companion 0.1.3 supports encrypted low-latency PCM playback without microphone or notification-listener permission. See [audio routing](../Unity_Linux/docs/audio-routing.md).

Companion 0.1.4 adds **Remote device locking** and **Screen sharing** in Features → Remote control. Use **Overview → Lock computer** and **Sharing → Share phone screen / View computer screen**. Activate phone locking through Android’s device-admin dialog to allow Linux to lock the phone. Each screen capture asks for local OS approval. See [setup and validation](../Unity_Linux/docs/screen-and-lock.md).

Companion 0.1.8 defaults to **Settings → Connection method → Bluetooth + Wi-Fi / LAN**. Verify the desktop TLS fingerprint. With 0.1.25 and the matching Linux build, a paired Bluetooth link learns the current LAN addresses automatically; a manual address remains optional in combined mode. Bluetooth carries controls and small updates; Wi-Fi / LAN carries files, photos, audio and screens. Both links stay connected, heartbeat and reconnect independently; new tasks fall back to the remaining link. Single-transport modes remain available. See [Bluetooth setup](../Unity_Linux/docs/bluetooth.md) and its physical-device validation limits.

Companion 0.1.9 adds native received-file, link and text notifications with actions to open the original item directly. Shared text also offers Copy text. Allow alerts from **Features → Notifications → Received file, link and text alerts**. Update Linux too for notifications in both directions. See [received-item behavior and validation](../Unity_Linux/docs/received-items.md).

Companion 0.1.12 provides [call management and SMS reports](../Unity_Linux/docs/telephony.md): ordinary Android permissions, contacts/history and dialing, with Linux oFono Bluetooth HFP call controls/audio while retaining the phone's usual Phone app. The desktop popup and Calls page share a live timer, answer/decline, hold/resume, end, mute, keypad and audio controls. An optional Android Phone app setup enables phone earpiece, speakerphone and Bluetooth routing. HFP works without that role. Physical calls and carrier receipts still require device validation.

Companion 0.1.13 adds [phone control of the shared laptop screen](../Unity_Linux/docs/screen-and-lock.md), an Android keyboard toggle, and a laptop-display-off option in both apps. The panel restores when sharing ends or its connection is lost; KDE unattended startup and panel power-off/restoration were checked on the real desktop; phone gestures and end-to-end latency still need physical-device validation.

Companion 0.1.14 adds [laptop push alerts for phone notifications and incoming SMS](../Unity_Linux/docs/phone-notifications.md). Notification-list snapshots and SMS history stay quiet on reconnect. Enable mirroring/listener access and SMS in Features; update Linux as well. Desktop alert clicks open the matching Unity page.

The KDE desktop update supports [starting directly from the phone](../Unity_Linux/docs/screen-and-lock.md#starting-entirely-from-the-phone-on-kde) with companion 0.1.13. Enable the dedicated unattended permission once; screen-off now powers down the laptop panel while a temporary mirror keeps phone control working.

Companion **0.1.15** adds [laptop and phone media players](../Unity_Linux/docs/media-sync.md) in **Overview → Media players**. Select an app on either device and control it directly. Native origins, session IDs, quiet metadata and bounded replay histories prevent feedback loops and repeated track skips. Update Linux too.

Companion **0.1.16** adds [device volume](../Unity_Linux/docs/device-volume.md): laptop output and phone music sliders and mute buttons in **Overview → Media players**. Enable **Media and sound controls** and update Linux. Volume works independently of active media players and listener access.

Companion **0.1.17** fixes laptop-audio playback under packet gaps and temporary phone audio interruptions, requests low-latency Wi-Fi while streaming, and reports playback failures on both devices. Install the matching Linux update. If playback is silent, raise **Phone media volume** in Device volume; streaming preserves your volume settings.

Companion **0.1.18** fixes Find phone: an audible bundled alarm, phone-speaker playback, a 30-second timeout, Stop ringing in the notification, and restoration of the previous alarm volume. Do Not Disturb can still block alarms; failures are reported instead of silent success.

Companion **0.1.22** supports [audio in both directions](../Unity_Linux/docs/audio-routing.md) with the matching Linux update. Choose Automatic, Wi-Fi stereo or Bluetooth mono from Linux Media while both links stay connected. Phone-to-laptop media audio requires Android recording permission and fresh capture approval; apps can block capture. Laptop playback also fixes foreground-service ordering before requesting audio focus. The build produces `dist/Unity-Preview-0.1.22-debug.apk`.

Companion **0.1.23** adds a landscape/portrait Rotate button and sensor rotation to the computer-screen viewer, preserving the live stream across rotation. **Use phone as main display** now requests a real virtual monitor on the updated Linux daemon; GNOME/KDE make it primary and disable the built-in laptop output instead of dimming its backlight. See [screen sharing and restoration](../Unity_Linux/docs/screen-and-lock.md).

Companion **0.1.24** adds **Sharing → Use phone as extended display**. With the matching Linux update, the phone is a second monitor to the right of the desktop while the laptop display stays active. Landscape/portrait rotation remains available. See [extended-display setup](../Unity_Linux/docs/screen-and-lock.md#phone-as-an-extended-display).

Companion **0.1.33** adds [automatic clipboard text sync](../Unity_Linux/docs/clipboard-sync.md). Received text is applied immediately. For sending from other apps without changing keyboards, start Shizuku and allow Unity under **Sharing → Background clipboard access**. Non-root Shizuku must be restarted after a phone reboot.
