#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/env.sh"
cd "$UNITY_ANDROID_ROOT"
if [[ -x .tools/gradle-9.6.0/bin/gradle ]]; then
    UNITY_GRADLE="$UNITY_ANDROID_ROOT/.tools/gradle-9.6.0/bin/gradle"
else
    UNITY_GRADLE=gradle
fi
UNITY_ANDROID_VARIANT="${UNITY_ANDROID_VARIANT:-preview}"
case "$UNITY_ANDROID_VARIANT" in
    debug|preview) ;;
    *) printf 'Expected UNITY_ANDROID_VARIANT=preview or debug\n' >&2; exit 1 ;;
esac
UNITY_GRADLE_VARIANT="${UNITY_ANDROID_VARIANT^}"
"$UNITY_GRADLE" --no-daemon ":app:assemble$UNITY_GRADLE_VARIANT" ":app:test${UNITY_GRADLE_VARIANT}UnitTest" ":app:lint$UNITY_GRADLE_VARIANT"
mkdir -p dist
python3 - "$UNITY_ANDROID_VARIANT" <<'PY'
from pathlib import Path
import hashlib
import json
import re
import shutil
import sys

directory = Path('dist')
variant = sys.argv[1]
prefix = 'Unity-Preview' if variant == 'preview' else 'Unity-Companion'
name = f'{prefix}-debug.apk'
shutil.copyfile(f'app/build/outputs/apk/{variant}/app-{variant}.apk', directory / name)
data = (directory / name).read_bytes()
version = re.search(r'versionName\s*=\s*"([^"]+)"', Path('app/build.gradle.kts').read_text())
if version is None:
    raise SystemExit('Android versionName not found')
versioned = f'{prefix}-{version.group(1)}-debug.apk'
shutil.copyfile(directory / name, directory / versioned)
items = [{'file': artifact, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()} for artifact in (name, versioned)]
(directory / 'manifest.json').write_text(json.dumps({
    'version': version.group(1), 'android': 'debug APK, API 29+', 'variant': variant,
    'package': 'org.unityconnect.companion' + ('.preview' if variant == 'preview' else ''),
    'signing_certificate_sha256': Path(f'signing/{variant}.sha256').read_text().strip(),
    'artifacts': items,
}, indent=2) + '\n')
(directory / 'SHA256SUMS').write_text(''.join(f"{item['sha256']}  {item['file']}\n" for item in items))
PY
printf 'Built Android APK in %s/dist\n' "$UNITY_ANDROID_ROOT"
