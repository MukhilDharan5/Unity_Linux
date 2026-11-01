#!/usr/bin/env python3
"""Architectural requirements are executable checks, including frontend removal independence."""
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import tomllib

root = Path(__file__).resolve().parents[1]
metadata = json.loads(subprocess.check_output(['cargo', 'metadata', '--format-version=1', '--no-deps'], cwd=root))
for package in metadata['packages']:
    assert not any(re.search(r'gtk|adwaita|qt', d['name'], re.I) for d in package['dependencies']), package['name']
gui = (root / 'unity-gtk/unity.py').read_text()
assert 'org.unityconnect.Device1' in gui
assert not re.search(r'^\s*(?:from|import) (?:socket|ssl|bluetooth|requests|unity_core)', gui, re.M)
assert 'Restart=on-failure' in (root / 'packaging/systemd/unityd.service').read_text()
assert 'unity-gtk' not in (root / 'packaging/systemd/unityd.service').read_text()
assert 'AF_NETLINK' in (root / 'packaging/systemd/unityd.service').read_text().split('RestrictAddressFamilies=', 1)[1].splitlines()[0], 'Address discovery requires route netlink inside systemd'
with tempfile.TemporaryDirectory(prefix='unity-install-test-') as tmp:
    prefix = Path(tmp) / 'prefix'
    subprocess.check_call(['python3', 'scripts/install.py', '--debug', '--prefix', str(prefix),
                           '--firefox-manifest-dir', str(prefix / 'firefox-hosts')], cwd=root)
    daemon = prefix / 'libexec/unity/unityd'
    before = daemon.stat()
    subprocess.check_call(['python3', 'scripts/uninstall-gtk.py', '--prefix', str(prefix)], cwd=root)
    assert daemon.exists() and daemon.stat().st_mtime == before.st_mtime
    assert (prefix / 'share/systemd/user/unityd.service').exists()
    assert (prefix / 'bin/unityctl').exists()
    host = json.loads((prefix / 'firefox-hosts/org.unityconnect.firefox.json').read_text())
    assert host['allowed_extensions'] == ['session-continuity@local.invalid']
    assert Path(host['path']).is_file() and Path(host['path']).stat().st_mode & 0o111
    assert not (prefix / 'bin/unity-gtk').exists()
    assert not (prefix / 'share/icons/hicolor/symbolic/apps/org.unityconnect.Unity-symbolic.svg').exists()
print('Architecture and independent frontend uninstall checks passed')
