#!/usr/bin/env python3
"""Install Unity's oFono automatic startup and recovery configuration."""
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    if os.geteuid() != 0: raise SystemExit('Run with Linux administrator authorization (pkexec or sudo).')
    if not Path('/usr/sbin/ofonod').is_file(): raise SystemExit('Install the ofono package first.')
    package = Path(__file__).resolve().parents[1] / 'packaging'
    for source, destination in [
        (package / 'systemd/ofono-recovery.conf',Path('/etc/systemd/system/ofono.service.d/50-unity-recovery.conf')),
        (package / 'dbus/org.ofono.service',Path('/usr/share/dbus-1/system-services/org.ofono.service')),
    ]:
        destination.parent.mkdir(parents=True,exist_ok=True,mode=0o755)
        fd, name = tempfile.mkstemp(prefix='.unity-ofono-',dir=destination.parent)
        try:
            with os.fdopen(fd,'w') as output:
                output.write(source.read_text()); output.flush(); os.fsync(output.fileno())
            os.chmod(name,0o644); os.replace(name,destination)
        finally:
            if os.path.exists(name): os.unlink(name)
    for arguments in [['systemctl','daemon-reload'],['systemctl','reset-failed','ofono.service'],
        ['busctl','--system','call','org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','ReloadConfig'],
        ['systemctl','enable','--now','ofono.service']]:
        subprocess.run(arguments,check=True)
    print('oFono boot startup, D-Bus activation, and automatic restart are enabled.')


if __name__ == '__main__': main()
