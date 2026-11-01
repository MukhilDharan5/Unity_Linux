# Android build and validation

Run these commands from `Unity_Android/`. The companion requires JDK 21, Gradle 9.6.0, Android Gradle Plugin 9.4.1, Kotlin compiler 2.4.21, SDK platform 37, and build-tools 37.0.0. The app targets API 35 and supports API 29+. The repository has no Gradle wrapper; use an installed Gradle or the ignored local toolchain under `.tools/`.

`source scripts/env.sh` selects the local JDK, SDK, and Gradle cache when present. Otherwise, set `JAVA_HOME`, `ANDROID_HOME`, and `GRADLE_USER_HOME` for your own installation. An ignored `local.properties` may set `sdk.dir=/absolute/sdk/path`. AGP provides the Kotlin compiler; do not apply a second Kotlin plugin to the app.

```sh
source scripts/env.sh
./scripts/build.sh
```

The script defaults to the separate **Unity Preview** app (`org.unityconnect.companion.preview`). It runs `:app:assemblePreview`, `:app:testPreviewUnitTest`, and `:app:lintPreview`, then copies `app/build/outputs/apk/preview/app-preview.apk` into `dist/Unity-Preview-debug.apk` and `dist/Unity-Preview-0.1.36-debug.apk`. The manifest records the package, variant, public signing certificate digest, and artifact checksums. To run just the tests with a project-local Gradle installation:

```sh
.tools/gradle-9.6.0/bin/gradle --no-daemon :app:testPreviewUnitTest
```

Both variants are developer debug builds. Preview installs alongside the original Unity app without changing its data. Before connecting Preview, press **Disconnect** in the original app to avoid duplicate sessions and alerts. Open **Unity Preview**, enter the computer address and verified TLS fingerprint, approve its new pairing on the desktop, then grant the features and permissions you want. Its phone entry includes **(Preview)**. The original pairing and settings remain in the original app.

Preview uses the persistent private key at `~/.local/share/unity-build/signing/preview.keystore`; override its location with `UNITY_ANDROID_PREVIEW_KEYSTORE`. Back up this key privately before reinstalling or moving the build machine. The alias is `androiddebugkey`, with the standard debug store/key password `android`. Keep private keystores outside the source tree. Only public certificate digests are tracked in `signing/`. Packaging fails if the key is absent or does not match its pinned certificate, instead of generating an incompatible update.

`UNITY_ANDROID_VARIANT=debug ./scripts/build.sh` builds the original package and `Unity-Companion-*` artifacts. It requires the **original** signing key used by companion 0.1.18 and earlier; set `UNITY_ANDROID_KEYSTORE` to its private path (default `~/.android/debug.keystore`). The original key is currently unavailable on this machine. The installers produced as 0.1.19 and 0.1.20 used a different local key and cannot update that original installation. Re-signing or increasing the version does not recover the old identity. Do not uninstall or clear app data to resolve this conflict when keeping that data matters.

Future Preview updates keep the same package and pinned signing certificate. With a connected test phone, `adb install -r dist/Unity-Preview-debug.apk` updates Preview. ADB installation is not run by the build script.

### Play Protect blocks the file-manager installer

The warning **“App blocked to protect your device”**, followed by sensitive-data and financial-fraud text, is separate from a signing/package conflict. [Google documents this installation protection](https://developers.google.com/android/play-protect/warning-dev-guidance) for internet-sideloaded apps that declare SMS, notification-listener, or accessibility access. Unity declares these for its opt-in messaging, notification/media, and remote-touch features. Turning the features off inside Unity does not remove their manifest declarations, and changing the app name or signing key does not address this policy.

For development on your own phone, use [Android’s supported USB debugging setup](https://developer.android.com/studio/run/device). Connect the phone, enable **Developer options → USB debugging**, unlock it, and accept this computer’s authorization prompt. Confirm the intended phone is listed as `device`, then install the verified Preview APK:

```sh
.tools/android-sdk/platform-tools/adb devices -l
.tools/android-sdk/platform-tools/adb -d install -r dist/Unity-Preview-0.1.24-debug.apk
```

`-d` selects a physical USB device; with multiple USB devices, explicitly use `-s SERIAL` instead. This keeps Play Protect enabled, requests no automatic permission grants, and does not uninstall either Unity app or clear its data. Device policies can still reject a developer install; inspect the returned error rather than changing security settings automatically. For normal distribution, review Google’s developer requirements and use the documented appeal/review process when appropriate. No SMS, notification, media, or accessibility functionality is silently removed to make an APK install.

The Android suite now has 136 tests using Robolectric 4.17, including UI, protocol, telephony, audio, screen, lock, file, battery, TLS, connection and receipt checks. The optional Phone role tests cover speakerphone routing and an offline call screen. Battery tests cover power/runtime units, smoothing, charging estimates, missing sensors, measured trends, history resets, periodic sampling and disconnect cleanup. File tests cover verified publication into Downloads/Unity, cleanup after failed storage writes, empty/Unicode files, bad hashes and one-shot document providers. Receipt tests cover distinct immutable notification targets, direct file-viewer PendingIntents, browser opens after resume, new-intent delivery, exact file URI/MIME/read-only grants, original text copying and rejected URLs. Wi-Fi tests cover persistent public/private choices across restarts, reconnects and roaming, permission promotion, redacted names and reused network handles. UI checks include notification settings after denial and receipt viewer insets/dark appearance. Robolectric needs the Android 15 instrumented test runtime and native graphics libraries when first run. These dependencies stay out of the APK. UI captures are in `app/build/reports/unity-preview/`. Android permissions, OEM background restrictions, carrier behavior, and actual file/media outcomes require physical device checks. See [UI validation](ui.md) and the shared [feature matrix](../../Unity_Linux/docs/features.md).

Companion 0.1.5 saves files received from a computer in Downloads/Unity and notifies the phone after verified publication. Update over the existing debug installation to retain pairing; earlier versions keep received files in private app storage.

Companion 0.1.6 adds [phone battery power and time estimates](../../Unity_Linux/docs/battery-estimates.md). Update the Linux daemon and GTK client as well to show them in Overview. Unsupported phone sensors are reported explicitly; physical battery readings remain device-dependent.

Companion 0.1.8 maintains both transports independently. Coordinator tests cover routing, separate heartbeats/replies, loss/reconnect and pinned transfer routes. UI tests cover combined settings/rotation and LAN startup when Bluetooth is off or denied. Physical simultaneous radio behavior remains unverified.

Companion 0.1.9 adds [received-item notification actions](../../Unity_Linux/docs/received-items.md). Files, links and text open their original target directly; text also offers a Copy action. Enable the received-items notification channel in Features → Notifications. Install the updated Linux build for alerts in both directions.

Companion 0.1.12 normally keeps the normal Android Phone app and moves live call control to Linux oFono HFP. Install the APK normally on the phone, enable Call management and grant normal call/contacts/history permissions. No ADB permission setup or privileged ongoing-call grant is needed. Optional phone speakerphone controls use an explicit default Phone app chooser under Features; see the same telephony guide. Configure the Linux Bluetooth backend as described in [telephony](../../Unity_Linux/docs/telephony.md).

Companion 0.1.17 adds audio playback regression coverage for missing packets, blocking writes, temporary focus interruptions, and playback failures returned on the original transport after startup completes. Install the matching Linux update; see [audio routing](../../Unity_Linux/docs/audio-routing.md).

Companion 0.1.18 fixes Find phone with a bundled sound on the alarm stream and preferred phone speaker, temporary audible alarm volume, playback verification, a Stop notification, and timeout/volume restoration. Install the matching Linux update for Ring/Stop state.

Companion 0.1.19 adds radio-triggered reconnects, idempotent connection startup, feature-refresh isolation, and notification-listener rebind after disconnection. Install the matching Linux update for actual desktop clipboard writes and GNOME notification activation; see [GNOME integration](../../Unity_Linux/docs/gnome.md).

Companion 0.1.20 includes video gallery metadata/thumbnails, separate image/video permissions, operation-bound gallery originals, and media artwork loading separated from timeline updates. Install the matching Linux update and review Photo and video access to add videos. Its artifacts used the stable companion filename and `Unity-Companion-0.1.20-debug.apk`; 0.1.21 defaults to Preview as described above. See [gallery behavior](../../Unity_Linux/docs/gallery.md) and [media recovery](../../Unity_Linux/docs/media-sync.md).

Companion 0.1.21 provides the separate Unity Preview install and verifies persistent signing identities before packaging. It includes the GNOME, reconnect, gallery, and media fixes from 0.1.19/0.1.20.

Companion 0.1.22 adds phone media capture, pending-consent denial and stale-stop tests, and the fixed 16 kHz mono Bluetooth audio profile. Build and install the matching Linux changes for the direction/connection chooser. Physical phone audio still needs validation; see [audio routing](../../Unity_Linux/docs/audio-routing.md).

Companion 0.1.23 updates the computer viewer for sensor rotation and explicit landscape/portrait selection. The screen test preserves the active session across orientation changes, checks keyboard/touch bounds and renders a landscape preview. The matching Linux update supplies virtual-monitor handoff and output-layout restoration on GNOME/KDE.

Companion 0.1.24 adds the extended-display Sharing action and tests its actual outgoing request, default-off access gating, optional phone input, and rejection of conflicting primary/extended options. Update the Linux daemon and GTK client as well.

Companion 0.1.25 fixes campus/shared LAN addresses, IPv6 listening, encrypted Bluetooth address refresh, Wi-Fi network binding while cellular is the default, and public/unknown network review. TLS 1.3 is required on every transport; plaintext discovery is removed. Tests cover stale/missing manual addresses, both links staying open, VPN routing, malformed endpoint rejection, scoped privacy answers and the actual privacy dialog. See [public Wi-Fi behavior](../../Unity_Linux/docs/public-wifi.md).

Companion 0.1.26 adds hardware-backed per-use strong-biometric signing, the laptop-authentication review activity, signed public credential export and dedicated paired-session auth messages. Install the matching Linux PAM module and run [administrator enrollment](../../Unity_Linux/docs/phone-authentication.md) before using sudo/login approvals.

Companion 0.1.33 adds [automatic clipboard sync](../../Unity_Linux/docs/clipboard-sync.md), default-on sharing, immediate incoming writes, change listeners, focus/unlock/reconnect refresh and echo suppression. Background outgoing reads use an authorized Shizuku 13+ user service without replacing the selected keyboard. Tests cover privileged callback routing, stale callback rejection, unchanged keyboard selection, shell attribution and Android user selection. Real Shizuku permission and clipboard delivery still require a physical device.
