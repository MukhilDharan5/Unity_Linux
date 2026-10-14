# Android build and validation

Run these commands from `Unity_Android/`. The companion requires JDK 21, Gradle 9.6.0, Android Gradle Plugin 9.4.1, Kotlin compiler 2.4.21, SDK platform 37, and build-tools 37.0.0. The app targets API 35 and supports API 29+. The repository has no Gradle wrapper; use an installed Gradle or the ignored local toolchain under `.tools/`.

`source scripts/env.sh` selects the local JDK, SDK, and Gradle cache when present. Otherwise, set `JAVA_HOME`, `ANDROID_HOME`, and `GRADLE_USER_HOME` for your own installation. An ignored `local.properties` may set `sdk.dir=/absolute/sdk/path`. AGP provides the Kotlin compiler; do not apply a second Kotlin plugin to the app.

```sh
source scripts/env.sh
./scripts/build.sh
```

The script runs `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug`. It copies `app/build/outputs/apk/debug/app-debug.apk` into `dist/Unity-Companion-debug.apk` and writes an artifact manifest and SHA-256 checksum. To run just the tests with a project-local Gradle installation:

```sh
.tools/gradle-9.6.0/bin/gradle --no-daemon :app:testDebugUnitTest
```

The APK is a developer debug build, signed with the local Android debug key. Install it over the previous debug companion to keep pairing and settings; do not clear app data. With a connected test phone, `adb install -r dist/Unity-Companion-debug.apk` updates the app. ADB installation is not run by the build script. A new signing key would prevent an in-place update.

The Android suite now has 69 tests using Robolectric 4.17, including UI, protocol, telephony, audio, screen, lock, file, battery, TLS, connection and receipt checks. The optional Phone role tests cover speakerphone routing and an offline call screen. Battery tests cover power/runtime units, smoothing, charging estimates, missing sensors, measured trends, history resets, periodic sampling and disconnect cleanup. File tests cover verified publication into Downloads/Unity, cleanup after failed storage writes, empty/Unicode files, bad hashes and one-shot document providers. Receipt tests cover distinct immutable notification targets, browser opens, exact file URI/MIME/read-only grants, original text copying and rejected URLs. UI checks include notification settings after denial and receipt viewer insets/dark appearance. Robolectric needs the Android 15 instrumented test runtime and native graphics libraries when first run. These dependencies stay out of the APK. UI captures are in `app/build/reports/unity-preview/`. Android permissions, OEM background restrictions, carrier behavior, and actual file/media outcomes require physical device checks. See [UI validation](ui.md) and the shared [feature matrix](../../Unity_Linux/docs/features.md).

Companion 0.1.5 saves files received from a computer in Downloads/Unity and notifies the phone after verified publication. Update over the existing debug installation to retain pairing; earlier versions keep received files in private app storage.

Companion 0.1.6 adds [phone battery power and time estimates](../../Unity_Linux/docs/battery-estimates.md). Update the Linux daemon and GTK client as well to show them in Overview. Unsupported phone sensors are reported explicitly; physical battery readings remain device-dependent.

Companion 0.1.8 maintains both transports independently. Coordinator tests cover routing, separate heartbeats/replies, loss/reconnect and pinned transfer routes. UI tests cover combined settings/rotation and LAN startup when Bluetooth is off or denied. Physical simultaneous radio behavior remains unverified.

Companion 0.1.9 adds [received-item notification actions](../../Unity_Linux/docs/received-items.md). Files, links and text open their original target directly; text also offers a Copy action. Enable the received-items notification channel in Features → Notifications. Install the updated Linux build for alerts in both directions.

Companion 0.1.12 normally keeps the normal Android Phone app and moves live call control to Linux oFono HFP. Install the APK normally on the phone, enable Call management and grant normal call/contacts/history permissions. No ADB permission setup or privileged ongoing-call grant is needed. Optional phone speakerphone controls use an explicit default Phone app chooser under Features; see the same telephony guide. Configure the Linux Bluetooth backend as described in [telephony](../../Unity_Linux/docs/telephony.md).
