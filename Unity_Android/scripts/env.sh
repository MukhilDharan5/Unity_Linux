#!/usr/bin/env bash
# Source from Unity_Android to use the project's local Android toolchain.
UNITY_ANDROID_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -d "$UNITY_ANDROID_ROOT/.tools/jdk21" ]]; then export JAVA_HOME="$UNITY_ANDROID_ROOT/.tools/jdk21"; fi
if [[ -d "$UNITY_ANDROID_ROOT/.tools/android-sdk" ]]; then export ANDROID_HOME="$UNITY_ANDROID_ROOT/.tools/android-sdk"; fi
if [[ -d "$UNITY_ANDROID_ROOT/.tools/gradle" ]]; then export GRADLE_USER_HOME="$UNITY_ANDROID_ROOT/.tools/gradle"; fi
