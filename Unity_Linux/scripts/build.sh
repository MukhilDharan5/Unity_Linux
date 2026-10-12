#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/env.sh"
cd "$UNITY_ROOT"
cargo build --locked --release --workspace
mkdir -p dist
cp target/release/unityd target/release/unityctl dist/
python3 - <<'PY'
from pathlib import Path
import hashlib
import json

directory = Path('dist')
items = []
for name in ('unityd', 'unityctl'):
    data = (directory / name).read_bytes()
    items.append({'file': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
(directory / 'manifest.json').write_text(json.dumps({'version': '0.1.0', 'linux': 'x86_64', 'artifacts': items}, indent=2) + '\n')
(directory / 'SHA256SUMS').write_text(''.join(f"{item['sha256']}  {item['file']}\n" for item in items))
PY
printf 'Built binaries in %s/dist\n' "$UNITY_ROOT"
