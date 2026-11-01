# Unity for Windows

A native **WinUI 3** port of Unity Linux, using the existing **Unity protocol v1** and the unmodified Unity Android companion. Everything specific to Windows lives in this directory. Linux and Android sources remain independent.

The interface uses fully opaque surfaces with rounded rectangular buttons, fields and navigation items, plus rounded cards, dialogs and media frames. A slightly lighter navigation pane and darker content pane run from top to bottom through the integrated title bar, with no title-bar band or divider. Each pane's title is centered within that pane. System/light/dark appearances, native file pickers, keyboard navigation and a notification-area icon are included. See [Windows appearance](docs/ui.md).

## Run

Requires **Windows 11 x64**. TLS 1.3 uses Windows Schannel, so Windows 10 is not a supported host. No account or external service is required.

After building, open **`dist/Unity/Unity.exe`**. Keep the entire `dist/Unity` directory together. The self-contained publish includes .NET and the Windows App SDK; the smaller default publish requires the .NET 10 Desktop Runtime.

```powershell
cd Unity_Windows
./scripts/build.ps1 -Publish -SelfContained
./scripts/run.ps1
```

Building requires the .NET 10 SDK. NuGet restores the pinned Windows App SDK and audio dependencies. `-Offline` uses the current user's existing NuGet cache; normal builds use nuget.org. All project build caches and output stay here, under `.tools`, `bin`, `obj`, and `dist`.

To install the standalone build for your Windows account, run `./scripts/install.ps1 -Launch`. It copies and verifies the build under `%LOCALAPPDATA%/Programs/UnityWindows`, adds **Unity Phone Companion** to the Start menu and desktop, and registers an uninstaller in Windows Installed apps. Administrator access is not required. Pairings and received files remain in the separate per-user data directory. Installation does not enable automatic startup.

Open `Unity.slnx` in a current Visual Studio with .NET desktop/WinUI tooling, or use the PowerShell scripts. `-Configuration Debug` creates a debug build. The CLI build does not require Visual Studio.

## Pair the existing Android app

1. Connect the computer and phone to the same private Wi-Fi/LAN. Open Windows Unity → **Settings**.
2. Enter the displayed computer address and TCP port **17161** in Android Unity → **Settings → Computer connection**. Compare and enter the **complete SHA-256 certificate fingerprint from this computer**. Discovery replies are not identity proof.
3. Click **Discover** on Windows to open the 90-second pairing window, then **Save and connect** on Android. Review the phone name and identity in Windows and click **Approve**.
4. The phone reconnects with its saved credential. Enable desired features and permissions on Android. Windows **Permissions and capabilities** provides separate per-phone policy switches; remote lock and screen sharing start disabled.
5. For Bluetooth, bond the devices in Windows and Android Bluetooth settings first. Choose this computer in Android's Bluetooth computer picker. Select **Bluetooth + Wi-Fi / LAN** to keep both links connected. Both links use the same verified fingerprint and secret.

If Windows Firewall asks, allow Unity on your **private** network. Unity does not alter firewall rules, adapter power, OS Bluetooth trust, Android permissions, or Wi-Fi settings. If a managed firewall blocks the connection, allow inbound TCP 17161 on the private network; UDP 17162 is optional discovery. The Android companion currently uses manual address entry.

Windows has its own certificate and pairing store; verify its fingerprint and pair with it independently of an existing Linux installation. No Android protocol or APK update is required by this port.

## Features

| Area | Windows behavior |
| --- | --- |
| Pairing and reconnect | Persistent identity, local approval, pinned TLS 1.3, device-bound HMAC-SHA256 challenge; Android reconnects automatically |
| LAN and Bluetooth | Simultaneous independent authenticated sessions; Bluetooth control preference, LAN bulk preference, fallback for new work; same three RFCOMM UUIDs as Linux |
| Overview | Actual connection, battery/power/time telemetry, network, find Ring/Stop, URLs and automatic bidirectional clipboard text sync |
| Notifications | Phone notification list, dismiss, actions and replies; native Windows notification-area alerts |
| SMS | Conversations, paginated messages, send, provider/report-driven updates; submission is not a delivery receipt |
| Calls | Dial, contacts, paginated history, call state and elapsed timer; live answer/reject/hold/mute/keypad/phone routing through Android's optional Unity Phone app role |
| Files | Native chooser and drag/drop, streaming in both directions, 64 MiB limit, SHA-256 verification, abort cleanup, no automatic opening |
| Folder browsing and photos | Android-granted folder tokens, download originals, validated small JPEG photo previews |
| Media | Android MediaSessions plus Windows GSMTC players, exact-session controls, track-bound seeking; native origins stay separate |
| Device volume | Windows Core Audio output and Android media volume/mute, current-output tokens, quiet received snapshots |
| Connectivity | Phone Mobile Hotspot switch, phone-reported connected Bluetooth devices and address-scoped disconnect switches through Samsung Routines; opt-in companion 0.1.35 reporting and Nearby devices permission. See [setup](../Unity_Linux/docs/connectivity.md). |
| Windows audio on phone | Native WASAPI loopback → bounded 10 ms PCM packets; silent startup, default-output tracking and capture-error cleanup; separate authenticated TLS stream |
| Screen sharing | Windows Graphics Capture and bounded JPEG streams in both directions; local Windows approval and Android consent; optional local black-screen mode |
| Phone control of Windows | Mouse, scroll, Unicode text, allowlisted keys/modifiers, sequence and session checks, only during an approved Windows screen share |
| Phone remote input | Android Unity IME text and accessibility tap/swipe with normalized coordinates |
| Locking, ringer, hotspot | Native Windows lock, Android device-admin lock, ringer/DND, phone-reviewed tethering-settings request; optional [Samsung Routines triggers](../Unity_Linux/docs/samsung-routines.md) for hotspot on/off and a phone-configured Bluetooth disconnect with companion 0.1.34 |
| Background operation | Close to notification area; incoming data and connections continue. Right-click → Quit or Settings → Quit stops Unity |

### Platform limits

- **Windows Bluetooth HFP cellular-call audio/control is not implemented.** Linux oFono/WirePlumber code cannot run on Windows. Live call controls require the optional Unity Phone app role on Android; ordinary dialing, contacts and history keep their own Android permissions. The app explains and disables unavailable HFP controls. Cellular audio stays on the phone or its OS-managed headset.
- Windows screen capture shares the **primary display** using Windows Graphics Capture after local confirmation. **Blank laptop display while sharing** covers local displays in black and keeps the phone view live. Ctrl+Alt+Shift+U, stop, disconnect, policy disable, unpair and exit restore them. This keeps the panel powered; physical panel power-off freezes capture or wakes the panel on this laptop. Unattended startup, multi-monitor selection and elevated-window input remain unavailable. See [screen sharing and audio](docs/screen-audio.md).
- WASAPI **mirrors** the current Windows output; it does not create a virtual audio device or silence the computer. Phone playback and physical Bluetooth throughput/latency still need device testing.
- Native Windows media discovery currently sends bounded text and playback metadata without Windows album artwork. Phone artwork is supported and validated.
- Alerts use the native notification-area balloon API, not persistent Action Center notifications. Clicking an alert opens Unity; the matching item remains available in its page. There is no separate Windows service, public IPC API, or auto-start installer; keep the tray process running.

## Data and security

**Settings → Local device data → Collect local device data** adds optional, consent-gated collection of this Windows PC's battery/power, network, media/audio, hardware and active-app usage. It starts off and polls every **5 seconds** after approval, saving a readable CSV at `%LOCALAPPDATA%/UnityWindows/local-collection.csv`. The CSV keeps the most recent **30 minutes** of samples and prunes older rows while Unity runs and when collection resumes. Turning it off stops collection and deletes the CSV. There is no upload, dashboard connection or phone sharing. Unsupported/denied fields are explicitly unavailable. See [local collection, sources and limits](docs/local-collection.md).

Runtime data is per Windows user, at `%LOCALAPPDATA%/UnityWindows`. `identity.dat` encrypts the certificate private key and phone credentials together using **DPAPI CurrentUser**. An unreadable store fails rather than silently generating a new identity. An imported private-key handle uses the Windows user key provider needed by Schannel; the persisted application identity remains DPAPI protected.

Received files go to `Received` inside that directory. Pending `.part` files are never offered as completed files. Windows reserved names, path separators, alternate data streams, trailing dots/spaces, invalid lengths and digest mismatches are rejected. Incoming links and files never open automatically. Clipboard text is applied automatically while enabled; see [clipboard setup and Android background limits](../Unity_Linux/docs/clipboard-sync.md). Live phone state and previews clear after the final link disconnects. Operations and stream tokens remain bound to their original connection and transport; interrupted work is never replayed on a replacement link.

`unity.log` records bounded connection/operation diagnostics, not message bodies, file contents, secrets or screen/audio data. Keep `identity.dat` private; do not copy it to another Windows account. Unpairing revokes the credential and closes both links.

## Verification

```powershell
./scripts/test.ps1
./scripts/test.ps1 -AndroidInterop
./scripts/test.ps1 -ProbePlatform
```

The dependency-free test runner uses actual loopback TLS 1.3 sockets, independent framed peers, DPAPI, transfer verification, dual-link routing, replay checks and session-bound screen side streams. `-AndroidInterop` compiles **the actual, unmodified Android `Protocol.kt` and `PinnedTls.kt`** with `tests/AndroidPeer.kt`, using Android Studio's bundled JDK and Kotlin compiler plus the pinned org.json test jar. It verifies pairing, HMAC, pinning, Unicode command/result frames and file bytes in both directions. Android Studio is optional for ordinary builds and tests.

The standard suite passes **197 checks**, including **69 local-collection checks** using fixture data, plus feature event/permission lifecycles, audio queue/capture-failure handling and display-restoration scenarios. Android interoperability and native audio/display probes are separate options. Earlier native probes verified WASAPI PCM framing during silence and changing captured content behind the local black cover. See [validation and physical-device checklist](docs/testing.md) for the boundary between automated checks and phone/radio validation.

Use `./scripts/run.ps1 -Demo` for an explicitly labeled, disconnected UI preview. It uses separate state and sample data; it never starts network listeners. `Unity.exe --state-dir C:\path\to\isolated-state` isolates manual tests from real pairing. Remote commands are disabled in preview mode.

## Source layout

| Directory | Responsibility |
| --- | --- |
| `src/Unity.Core` | Bounded v1 framing, identity/DPAPI, TLS pairing/auth, independent session routing, transfer quarantine, validation |
| `src/Unity.Platform` | Windows RFCOMM, Core Audio/WASAPI, GSMTC, screen streams, native lock/input |
| `src/Unity.Windows` | WinUI 3 shell and all pages, native chooser, tray lifetime/alerts, consent dialogs |
| `tests` | Protocol/security/integration checks and actual Kotlin Android protocol peer |
| `scripts` | Build, portable publish, run and test entry points |

The compatibility reference is [Linux protocol v1](../Unity_Linux/docs/protocol.md), [Bluetooth](../Unity_Linux/docs/bluetooth.md), [media](../Unity_Linux/docs/media-sync.md), and [device volume](../Unity_Linux/docs/device-volume.md). Native API references: [WinUI 3](https://learn.microsoft.com/windows/apps/winui/winui3/), [self-contained deployment](https://learn.microsoft.com/windows/apps/package-and-deploy/self-contained-deploy/deploy-self-contained-apps), [RFCOMM server](https://github.com/microsoft/Windows-universal-samples/blob/main/Samples/BluetoothRfcommChat/cs/Scenario2_ChatServer.xaml.cs), [NAudio](https://github.com/naudio/NAudio).
