#!/usr/bin/env python3
"""Remove only GTK frontend files. Never touches unityd, trust, CLI, or the service."""
import argparse
from pathlib import Path
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--prefix', type=Path, default=Path.home() / '.local')
a = p.parse_args()
for name in ['bin/unity-gtk', 'share/unity/unity.py', 'share/applications/org.unityconnect.Unity.desktop',
             'share/icons/hicolor/scalable/apps/org.unityconnect.Unity.svg',
             'share/icons/hicolor/symbolic/apps/org.unityconnect.Unity-symbolic.svg']:
    (a.prefix / name).unlink(missing_ok=True)
print('GTK frontend removed. Unity daemon and trust remain installed.')
