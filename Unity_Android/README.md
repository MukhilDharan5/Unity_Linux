# Unity Android

This directory contains the native Kotlin companion: its connection service, feature handlers, permission flows, Adwaita-inspired Android interface, and tests. It connects to the independent [Unity Linux daemon](../Unity_Linux/README.md) over an authenticated local network session. Android and Linux have separate builds and release artifacts.

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
