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

The 17 Android tests include 10 UI tests using Robolectric 4.17 native Android 15 graphics and seven protocol/image tests. Robolectric needs the Android 15 instrumented test runtime and native graphics libraries when first run. These dependencies stay out of the APK. UI captures are in `app/build/reports/unity-preview/`. Lint reported zero errors and six warnings in the 0.1.2 validation run. Android permissions, OEM background restrictions, carrier behavior, and actual file/media outcomes require physical device checks. See [UI validation](ui.md) and the shared [feature matrix](../../Unity_Linux/docs/features.md).
