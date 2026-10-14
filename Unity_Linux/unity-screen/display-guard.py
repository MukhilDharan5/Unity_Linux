#!/usr/bin/env python3
"""A separate display-power lease: restore on EOF, missed heartbeats or parent death."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import select
import signal
import stat
import subprocess
import sys
import tempfile
import time


class Backlight:
    kind = 'backlight'
    def __init__(self):
        from gi.repository import Gio, GLib
        self.Gio, self.GLib = Gio, GLib
        self.bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
        devices = [p for p in Path('/sys/class/backlight').iterdir() if re.fullmatch(r'[A-Za-z0-9_.-]+', p.name)]
        if len(devices) != 1:
            raise RuntimeError('A single controllable laptop backlight is required')
        self.path = devices[0]
        self.name = self.path.name
        self.maximum = int((self.path / 'max_brightness').read_text())
        if self.maximum <= 0:
            raise RuntimeError('Laptop backlight control is unavailable')
        self.session = self.graphical_session()

    def graphical_session(self):
        G = self.GLib
        value = self.bus.call_sync('org.freedesktop.login1', f'/org/freedesktop/login1/user/_{os.getuid()}',
            'org.freedesktop.DBus.Properties', 'Get', G.Variant('(ss)', ('org.freedesktop.login1.User', 'Display')),
            None, self.Gio.DBusCallFlags.NONE, 2000, None).unpack()[0]
        if isinstance(value, G.Variant): value = value.unpack()
        session = value[1]
        properties = self.bus.call_sync('org.freedesktop.login1', session, 'org.freedesktop.DBus.Properties', 'GetAll',
            G.Variant('(s)', ('org.freedesktop.login1.Session',)), None, self.Gio.DBusCallFlags.NONE, 2000, None).unpack()[0]
        properties = {k: v.unpack() if isinstance(v, G.Variant) else v for k, v in properties.items()}
        if not properties.get('Active') or properties.get('User', (None,))[0] != os.getuid() or properties.get('Type') not in ('wayland', 'x11'):
            raise RuntimeError('An active graphical login session is required')
        return session

    def brightness(self):
        return int((self.path / 'brightness').read_text())

    def set(self, value):
        self.session = self.graphical_session()
        self.bus.call_sync('org.freedesktop.login1', self.session, 'org.freedesktop.login1.Session', 'SetBrightness',
            self.GLib.Variant('(ssu)', ('backlight', self.name, max(0, min(self.maximum, value)))),
            None, self.Gio.DBusCallFlags.NONE, 2000, None)


class DisplayPower:
    """Keep the desktop on a mirror while physically disabling the laptop panel."""
    maximum = 1

    def __init__(self, virtual_output=None, device=None, configure=True):
        self.virtual_output = virtual_output
        self.kind = 'output' if virtual_output else 'dpms'
        outputs = self.outputs()
        panels = [o for o in outputs if o.get('connected') and (o.get('type') == 7 or o['name'].startswith(('eDP-', 'LVDS-')))]
        if device:
            panels = [o for o in panels if o['name'] == device]
        if len(panels) != 1: raise RuntimeError('A single connected laptop panel is required')
        self.name = panels[0]['name']
        self.logical_size = None
        if virtual_output and configure:
            panel = panels[0]
            if not panel.get('enabled'): raise RuntimeError('Turn on the laptop panel before starting a new share')
            virtual = self.require_virtual(outputs)
            pos = panel['pos']; scale = panel.get('scale', 1)
            # Overlap both outputs, so the phone sees the existing desktop.
            # Portal virtual output names contain dots, which kscreen-doctor parses
            # as separators. Address that output by its numeric ID instead.
            identity = virtual['id']
            self.run([f'output.{identity}.position.{pos["x"]},{pos["y"]}', f'output.{identity}.scale.{scale}'])
            virtual = self.require_virtual(self.outputs())
            if virtual['pos'] != pos or abs(virtual.get('scale', 1) - scale) > .001:
                raise RuntimeError('Cannot mirror the laptop desktop to the phone display')
            mode = next(m for m in virtual['modes'] if m['id'] == virtual['currentModeId'])
            self.logical_size = [round(mode['size'][axis] / virtual.get('scale', 1)) for axis in ('width', 'height')]
        self.brightness()  # Validate actual output/power state before reporting available.

    @staticmethod
    def run(arguments):
        result = subprocess.run(['kscreen-doctor', *arguments], capture_output=True, text=True, timeout=3)
        if result.returncode: raise RuntimeError('KDE display power control failed: ' + result.stderr.strip()[:256])
        return result.stdout

    def outputs(self):
        return json.loads(self.run(['--json']))['outputs']

    def require_virtual(self, outputs):
        virtual = next((o for o in outputs if o['name'] == self.virtual_output and o.get('enabled') and o.get('connected')), None)
        if not virtual: raise RuntimeError('The phone display is no longer active')
        return virtual

    def healthy(self):
        if self.virtual_output: self.require_virtual(self.outputs())

    def brightness(self):
        if self.virtual_output:
            panel = next((o for o in self.outputs() if o['name'] == self.name and o.get('connected')), None)
            if not panel: raise RuntimeError('Laptop panel is unavailable')
            return int(panel['enabled'])
        modes = dict(re.findall(r'dpms mode for screen (.*?): (on|off)', self.run(['--dpms', 'show'])))
        if self.name not in modes: raise RuntimeError('KDE display power state is unavailable')
        return int(modes[self.name] == 'on')

    def set(self, value):
        if self.virtual_output:
            if not value: self.healthy()
            self.run([f'output.{self.name}.{"enable" if value else "disable"}'])
        else:
            # No dimming fallback: unsupported power-off must be an explicit error.
            self.run(['--dpms', 'on' if value else 'off'])


class DisplayGuard:
    def __init__(self, directory, hardware=None, virtual_output=None, recover_only=False):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.journal = self.directory / 'display-backlight.json'
        self.lock = os.open(self.directory / 'display-backlight.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.previous = None
            self.cancelled = False
            if hardware is None and self.journal.exists():
                data = self.read_journal()
                # Recover the earlier brightness-only implementation before using power control.
                if data.get('version') == 1:
                    self.hardware = Backlight()
                else:
                    self.hardware = DisplayPower(data.get('virtual_output'), data.get('device'), configure=False)
                self.recover()
            if not recover_only or hardware:
                self.hardware = hardware or DisplayPower(virtual_output)
            self.recover()
        except Exception:
            os.close(self.lock)
            raise

    def save(self, value):
        fd, path = tempfile.mkstemp(prefix='.display-backlight-', dir=self.directory)
        try:
            with os.fdopen(fd, 'w') as output:
                json.dump({'version': 2, 'backend': getattr(self.hardware, 'kind', 'backlight'),
                    'virtual_output': getattr(self.hardware, 'virtual_output', None), 'device': self.hardware.name, 'brightness': value}, output)
                output.flush(); os.fsync(output.fileno())
            os.replace(path, self.journal)
        finally:
            if os.path.exists(path): os.unlink(path)

    def read_journal(self):
        info = self.journal.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise RuntimeError('Display recovery journal must be a private owned file')
        return json.loads(self.journal.read_text())

    def recover(self):
        if not self.journal.exists(): return
        data = self.read_journal()
        if data.get('version') not in (1, 2) or data.get('device') != self.hardware.name or not isinstance(data.get('brightness'), int) or not 0 < data['brightness'] <= self.hardware.maximum:
            raise RuntimeError('Display recovery journal does not match this laptop')
        self.previous = data['brightness']
        self.restore()

    def off(self):
        if self.previous is None:
            current = self.hardware.brightness()
            self.previous = current if current > 0 else self.hardware.maximum
            self.save(self.previous)
        try:
            self.hardware.set(0)
            if self.hardware.brightness() != 0:
                raise RuntimeError('The laptop display did not turn off')
        except Exception:
            self.restore()
            raise

    def restore(self):
        if self.previous is not None:
            # Preserve a later local restoration instead of overriding it.
            if self.hardware.brightness() == 0:
                self.hardware.set(self.previous)
                if self.hardware.brightness() == 0:
                    raise RuntimeError('Laptop display restoration is still pending')
            self.previous = None
            self.journal.unlink(missing_ok=True)

    def status(self):
        return {'display_available': True, 'display_on': self.hardware.brightness() > 0,
            'logical_size': getattr(self.hardware, 'logical_size', None)}

    def run(self, input_stream=sys.stdin, output_stream=sys.stdout, lease=5.0):
        def reply(value):
            output_stream.write(json.dumps(value) + '\n'); output_stream.flush()
        reply({'type': 'ready', **self.status()})
        last = time.monotonic()
        buffered = b''
        checked = 0
        try:
            while not self.cancelled:
                if time.monotonic() - last > lease: break
                if self.previous is not None and time.monotonic() - checked > 1:
                    if hasattr(self.hardware, 'healthy'):
                        try: self.hardware.healthy()
                        except Exception: break
                    checked = time.monotonic()
                if not select.select([input_stream], [], [], min(.25, lease))[0]: continue
                chunk = os.read(input_stream.fileno(), 4096)
                if not chunk: break
                buffered += chunk
                if len(buffered) > 4096: break
                while b'\n' in buffered:
                    line, buffered = buffered.split(b'\n', 1)
                    last = time.monotonic()
                    self.command(line, reply)
        finally:
            # A temporary login service failure leaves the private journal for
            # startup recovery. Keep retrying independently of Unity's process.
            for attempt in range(15):
                try: self.restore(); break
                except Exception:
                    if attempt == 14: raise
                    time.sleep(1)

    def command(self, line, reply):
        try:
            command = json.loads(line); action = command.get('action')
            if action not in ('heartbeat', 'off', 'on'): raise ValueError('Invalid display command')
            if action == 'heartbeat': return
            if action == 'off': self.off()
            else: self.restore()
            reply({'type': 'reply', 'ok': True, **self.status()})
        except Exception as error:
            reply({'type': 'reply', 'ok': False, 'error': str(error)[:512], **self.status()})

    def close(self):
        os.close(self.lock)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', type=Path, required=True)
    parser.add_argument('--recover', action='store_true')
    parser.add_argument('--virtual-output')
    args = parser.parse_args()
    if args.recover and not (args.state_dir / 'display-backlight.json').exists(): return 0
    guard = None
    try:
        guard = DisplayGuard(args.state_dir, virtual_output=args.virtual_output, recover_only=args.recover)
        for number in (signal.SIGINT, signal.SIGTERM):
            signal.signal(number, lambda *_: setattr(guard, 'cancelled', True))
        if not args.recover: guard.run()
        return 0
    except Exception as error:
        print(json.dumps({'type': 'ready', 'display_available': False, 'display_on': True, 'error': str(error)[:512]}), flush=True)
        return 1
    finally:
        if guard: guard.close()


if __name__ == '__main__':
    raise SystemExit(main())
