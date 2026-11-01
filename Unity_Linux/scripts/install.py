#!/usr/bin/env python3
"""Stage or install daemon, CLI, and GTK separately. Does not start/stop services."""
import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--prefix', type=Path, default=Path.home() / '.local')
p.add_argument('--destdir', type=Path)
p.add_argument('--component', choices=['all', 'daemon', 'gtk'], default='all')
p.add_argument('--debug', action='store_true', help='Install debug binaries for developer validation')
p.add_argument('--firefox-manifest-dir', type=Path, help='Override Firefox native host registration directory (for sandboxed Firefox or packaging)')
a = p.parse_args()
prefix = a.prefix.absolute()
if any(c in str(prefix) for c in '\n\r\t"\\'):
    raise SystemExit('Unsupported prefix characters')
stage = lambda path: (a.destdir / path.relative_to('/')) if a.destdir else path
libexec = prefix / 'libexec/unity'

def copy(src, dst, mode):
    dst = stage(dst); dst.parent.mkdir(parents=True, exist_ok=True)
    # Atomic replacement also permits updating a running daemon executable.
    fd, name = tempfile.mkstemp(prefix='.unity-install-', dir=dst.parent)
    os.close(fd)
    temporary = Path(name)
    try:
        shutil.copyfile(src, temporary); temporary.chmod(mode)
        temporary.replace(dst)
    finally:
        temporary.unlink(missing_ok=True)

def text(dst, value, mode=0o644):
    dst = stage(dst); dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(value); dst.chmod(mode)

if a.component in ('all', 'daemon'):
    for name in ('unityd', 'unityctl'):
        copy(root / 'target' / ('debug' if a.debug else 'release') / name, libexec / name, 0o755)
    copy(root / 'unity-firefox/firefox-host.py', libexec / 'firefox-host.py', 0o755)
    manifest_dir = a.firefox_manifest_dir or (Path('/usr/lib/mozilla/native-messaging-hosts')
        if prefix in (Path('/usr'), Path('/usr/local')) else Path.home() / '.mozilla/native-messaging-hosts')
    text(manifest_dir / 'org.unityconnect.firefox.json', json.dumps({
        'name': 'org.unityconnect.firefox', 'description': 'Unity Linux Firefox companion',
        'path': str(libexec / 'firefox-host.py'), 'type': 'stdio',
        'allowed_extensions': ['session-continuity@local.invalid'],
    }, indent=2) + '\n')
    copy(root / 'unity-media/thumbnail.py', libexec / 'media-thumbnail.py', 0o644)
    copy(root / 'unity-screen/screen-capture.py', libexec / 'screen-capture.py', 0o644)
    copy(root / 'unity-screen/display-guard.py', libexec / 'display-guard.py', 0o644)
    copy(root / 'scripts/setup-screen-sharing.py', libexec / 'setup-screen-sharing.py', 0o644)
    copy(root / 'scripts/setup-phone-auth.py', libexec / 'setup-phone-auth.py', 0o644)
    copy(root / 'target' / ('debug' if a.debug else 'release') / 'libpam_unity.so', libexec / 'pam_unity.so', 0o644)
    text(prefix / 'share/applications/org.unityconnect.Unity.Screen.desktop',
         (root / 'packaging/org.unityconnect.Unity.Screen.desktop').read_text().replace('@LIBEXEC@', str(libexec)))
    bindir = stage(prefix / 'bin'); bindir.mkdir(parents=True, exist_ok=True)
    ctl = bindir / 'unityctl'
    if ctl.exists() or ctl.is_symlink(): ctl.unlink()
    ctl.symlink_to('../libexec/unity/unityctl')
    substitutions = {'@LIBEXEC@': str(libexec)}
    for source, dest in [('packaging/systemd/unityd.service', prefix / 'share/systemd/user/unityd.service'),
                         ('packaging/dbus/org.unityconnect.service', prefix / 'share/dbus-1/services/org.unityconnect.service')]:
        value = (root / source).read_text()
        for key, replacement in substitutions.items(): value = value.replace(key, replacement)
        # Systemd supports quoted executable paths. D-Bus Exec is also quoted.
        value = value.replace(f'ExecStart={libexec}/unityd', f'ExecStart="{libexec}/unityd"')
        value = value.replace(f'Exec={libexec}/unityd', f'Exec="{libexec}/unityd"')
        text(dest, value)
if a.component in ('all', 'gtk'):
    copy(root / 'scripts/setup-call-popup.py', libexec / 'setup-call-popup.py', 0o644)
    copy(root / 'unity-gtk/unity.py', prefix / 'share/unity/unity.py', 0o644)
    text(prefix / 'bin/unity-gtk', '#!/usr/bin/env python3\nimport os\nos.execv(' + repr(str(libexec / 'unityctl')) + ', ["unityctl", "launch-gtk"])\n', 0o755)
    copy(root / 'packaging/org.unityconnect.Unity.desktop', prefix / 'share/applications/org.unityconnect.Unity.desktop', 0o644)
    copy(root / 'packaging/icons/org.unityconnect.Unity.svg', prefix / 'share/icons/hicolor/scalable/apps/org.unityconnect.Unity.svg', 0o644)
    copy(root / 'packaging/icons/org.unityconnect.Unity-symbolic.svg', prefix / 'share/icons/hicolor/symbolic/apps/org.unityconnect.Unity-symbolic.svg', 0o644)
for doc in (root / 'docs').glob('*.md'):
    copy(doc, prefix / 'share/doc/unity' / doc.name, 0o644)
for xml in (root / 'docs/api').glob('*.xml'):
    copy(xml, prefix / 'share/doc/unity/api' / xml.name, 0o644)
print(f'Installed {a.component} to {prefix}' + (f' (staged at {a.destdir})' if a.destdir else ''))
if not a.destdir and a.component in ('all', 'daemon'):
    print('Enable explicitly: systemctl --user daemon-reload && systemctl --user enable --now unityd.service')
