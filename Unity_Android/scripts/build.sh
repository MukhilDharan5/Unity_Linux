#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/env.sh"
cd "$UNITY_ANDROID_ROOT"
if [[ -x .tools/gradle-9.6.0/bin/gradle ]]; then
    UNITY_GRADLE="$UNITY_ANDROID_ROOT/.tools/gradle-9.6.0/bin/gradle"
else
    UNITY_GRADLE=gradle
fi
"$UNITY_GRADLE" --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/Unity-Companion-debug.apk
python3 - <<'PY'
from pathlib import Path
import hashlib
import json
import re

directory = Path('dist')
name = 'Unity-Companion-debug.apk'
data = (directory / name).read_bytes()
version = re.search(r'versionName\s*=\s*"([^"]+)"', Path('app/build.gradle.kts').read_text())
if version is None:
    raise SystemExit('Android versionName not found')
item = {'file': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
(directory / 'manifest.json').write_text(json.dumps({'version': version.group(1), 'android': 'debug APK, API 29+', 'artifacts': [item]}, indent=2) + '\n')
(directory / 'SHA256SUMS').write_text(f"{item['sha256']}  {name}\n")
PY
printf 'Built Android APK in %s/dist\n' "$UNITY_ANDROID_ROOT"
