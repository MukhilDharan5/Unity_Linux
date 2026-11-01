# Bluetooth connections

For a router-free Wi-Fi link alongside Bluetooth, use the [phone-hotspot setup](direct-hotspot.md). Companion 0.1.36 and the matching desktop update handle hotspot subnet changes and stale router-side connections.

Companion 0.1.8 and the updated Linux daemon maintain Bluetooth Classic RFCOMM and Wi-Fi / LAN simultaneously. Bluetooth-only connections are also supported. The main stream carries the existing control protocol, battery telemetry, notifications, media controls, clipboard and verified file transfers. Audio and screen sharing use separate RFCOMM profiles with the existing one-use stream tokens. Actual Android permissions and feature toggles still apply.

1. Install the updated Linux daemon and Android APK. Turn on Bluetooth on both devices and pair them in the operating systems’ Bluetooth settings.
2. In Android **Settings → Computer connection**, select **Connection method → Bluetooth + Wi-Fi / LAN**. Grant Nearby devices permission if Android requests it. Choose the paired computer using **Choose Bluetooth computer**. Alternatively enter the powered adapter’s Bluetooth address shown in Linux Unity’s Settings.
3. Compare the complete TLS fingerprint with Linux **Settings → Pair devices**, or `unityctl pairing`. Enter the computer LAN address and port as well as its Bluetooth address. Both connections use the same fingerprint and phone credential.
4. For a new Unity pairing, open **Open pairing for Wi-Fi or Bluetooth** on Linux, select **Save and connect** on Android, then approve the phone on Linux. OS Bluetooth bonding and Unity approval are separate steps.
5. Select **Save and connect**. Both apps show **Bluetooth + Wi-Fi / LAN** when both links are ready. Controls, battery/network updates, notifications, clipboard, URLs and media commands prefer Bluetooth. Files, photos, browsing, audio and screen sharing prefer Wi-Fi / LAN. Both links remain connected with independent heartbeats and reconnects. Single-transport choices remain available in Settings.

If a link drops, new tasks use the remaining connection while the missing link reconnects. An active transfer or stream stays on its original link; if that link fails it stops cleanly and can be restarted. Unity does not replay commands automatically or move a partial file between links. Joining or reconnecting Bluetooth leaves an active LAN transfer/audio/screen stream running, and vice versa. Old installations retain their saved connection method until you select the combined mode.

Linux registers three `org.bluez.Profile1` server profiles with `org.bluez.ProfileManager1`, authenticated OS bonding, and automatically allocated RFCOMM channels / SDP records. The daemon accepts file descriptors only from the current BlueZ bus owner. It owns and closes those descriptors on disconnection or profile release, and re-registers after BlueZ restarts. It never enables adapter power, discovery, or system trust automatically. The desktop app communicates only over the existing public D-Bus API; BlueZ is optional for LAN connections.

| Stream | Service UUID |
| --- | --- |
| Control / files | `ab049870-6fb6-4d30-9b3c-190770271001` |
| Audio | `ab049870-6fb6-4d30-9b3c-190770271002` |
| Screen | `ab049870-6fb6-4d30-9b3c-190770271003` |

Android uses the public secure `BluetoothDevice.createRfcommSocketToServiceRecord` API. An SSLEngine wraps the RFCOMM byte streams in TLS 1.3 and checks the saved certificate pin before Hello, pairing secrets, or feature data. Unity’s challenge/proof then authenticates the phone credential. Independent read/write buffers support fragmented records and simultaneous traffic, with bounded buffers and timeouts. No insecure socket fallback, reflection, custom encryption, Bluetooth scan/location permission, GATT transport or HID is used by this Unity RFCOMM transport. Cellular call audio uses a separate OS-managed HFP profile; see [telephony](telephony.md).

Bluetooth bandwidth and interference vary. Large transfers take longer than Wi-Fi. The current audio format is 48 kHz stereo PCM; screen capture uses bounded JPEG frames. Slow links stop stalled audio/screen streams rather than queue stale playback. The combined mode automatically prefers Wi-Fi / LAN for these streams; Bluetooth is a fallback when LAN is unavailable. A missing/off adapter, denied Nearby devices permission, changed certificate pin, or unavailable BlueZ service produces an actionable connection error.

Automated validation uses real D-Bus profile registration / FD passing, rustls TLS, Unity authentication, CLI operations, bidirectional file bytes, PCM bytes, screen JPEGs, token replay rejection, disconnect cleanup, replacement/revocation and BlueZ restart recovery. Android tests use real TLS 1.3 with fragmented records, duplex file bytes, invalid pins and truncated records, plus concurrent connection routing, independent heartbeat/reconnect, sticky transfer routes, and native UI rotation/layout tests. These exercise the stream and service contracts; physical radio pairing, throughput, OEM permission behavior and phone latency require hardware testing.

API references: [BlueZ Profile](https://github.com/bluez/bluez/blob/master/doc/org.bluez.Profile.rst), [BlueZ ProfileManager](https://github.com/bluez/bluez/blob/master/doc/org.bluez.ProfileManager.rst), [Android connecting devices](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices), [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
