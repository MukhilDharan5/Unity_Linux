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
    """Configure the temporary phone monitor while preserving the original display layout."""
    maximum = 1

    def __init__(self, virtual_output=None, device=None, configure=True, recovery=None):
        self.virtual_output = virtual_output
        self.kind = 'output'
        if not virtual_output and configure:
            raise RuntimeError('Start Use phone as main display to switch off the laptop panel')
        outputs = self.outputs()
        panels = [o for o in outputs if o.get('connected') and (o.get('type') == 7 or o['name'].startswith(('eDP-', 'LVDS-')))]
        if device:
            panels = [o for o in panels if o['name'] == device]
        if len(panels) != 1: raise RuntimeError('A single connected laptop panel is required')
        self.name = panels[0]['name']
        self.logical_size = None
        self.recovery = recovery or {'outputs': [{key: o.get(key) for key in ('name', 'pos', 'scale', 'priority', 'enabled')} for o in outputs]}
        if virtual_output and configure:
            if not panels[0].get('enabled'): raise RuntimeError('Turn on the laptop panel before starting a new share')
            virtual = self.require_virtual(outputs)
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

    def phone_primary(self):
        if not self.virtual_output: return False
        virtual = next((o for o in self.outputs() if o['name'] == self.virtual_output and o.get('enabled')), None)
        return bool(virtual and virtual.get('priority') == 1)

    def extend(self):
        outputs = self.outputs(); virtual = self.require_virtual(outputs)
        physical = [o for o in outputs if o['name'] != self.virtual_output and o.get('enabled') and o.get('connected')]
        if not any(o['name'] == self.name for o in physical): raise RuntimeError('Keep the laptop display enabled to extend the desktop')
        def right(output):
            mode = next(m for m in output['modes'] if m['id'] == output['currentModeId'])
            width = mode['size']['height' if output.get('rotation', 1) in (2,8) else 'width']
            return output['pos']['x'] + round(width / output.get('scale',1))
        anchor = max(physical,key=right); x = right(anchor)
        priority = max(o.get('priority') or 1 for o in physical) + 1
        identity = virtual['id']
        self.run([f'output.{identity}.position.{x},{anchor["pos"]["y"]}', f'output.{identity}.priority.{priority}'])
        if not self.brightness() or self.phone_primary(): raise RuntimeError('The OS did not keep the laptop display active and primary')
        virtual = self.require_virtual(self.outputs())
        if virtual['pos']['x'] < x: raise RuntimeError('The phone display overlaps the laptop desktop')
        mode = next(m for m in virtual['modes'] if m['id'] == virtual['currentModeId'])
        self.logical_size = [round(mode['size'][axis] / virtual.get('scale',1)) for axis in ('width','height')]

    def set(self, value):
        if not self.virtual_output:
            # This branch is only for recovering old DPMS journals.
            self.run(['--dpms', 'on' if value else 'off'])
            return
        if not value:
            outputs = self.outputs(); virtual = self.require_virtual(outputs)
            panel = next(o for o in outputs if o['name'] == self.name)
            identity = virtual['id']; pos = panel['pos']; scale = panel.get('scale', 1)
            self.run([f'output.{identity}.position.{pos["x"]},{pos["y"]}', f'output.{identity}.scale.{scale}',
                      f'output.{identity}.priority.1', f'output.{self.name}.disable'])
            if self.brightness() or not self.phone_primary():
                raise RuntimeError('The OS did not make the phone the primary display')
            virtual = self.require_virtual(self.outputs())
            mode = next(m for m in virtual['modes'] if m['id'] == virtual['currentModeId'])
            self.logical_size = [round(mode['size'][axis] / virtual.get('scale', 1)) for axis in ('width', 'height')]
        else:
            outputs = {o['name']: o for o in self.outputs() if o.get('connected')}
            arguments = [f'output.{self.name}.enable']
            for original in self.recovery['outputs']:
                output = outputs.get(original['name'])
                if output is None: continue
                identity = output.get('id', output['name'])
                if original.get('priority') is not None:
                    arguments.append(f'output.{identity}.priority.{original["priority"]}')
                if original['name'] == self.virtual_output:
                    pos = original.get('pos')
                    if pos: arguments.append(f'output.{identity}.position.{pos["x"]},{pos["y"]}')
                    if original.get('scale'): arguments.append(f'output.{identity}.scale.{original["scale"]}')
            self.run(arguments)


class GnomeDisplayPower:
    """Temporary Mutter monitor layout, with no brightness or global DPMS writes."""
    kind = 'gnome-output'
    maximum = 1
    destination = 'org.gnome.Mutter.DisplayConfig'
    path = '/org/gnome/Mutter/DisplayConfig'
    interface = destination

    def __init__(self, virtual_output, device=None, configure=True, recovery=None):
        from gi.repository import Gio, GLib
        self.Gio, self.GLib = Gio, GLib
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        self.virtual_output = virtual_output
        serial, monitors, logical, properties = self.read_state()
        panels = [spec[0] for spec, _, props in monitors if props.get('is-builtin')]
        if device: panels = [name for name in panels if name == device]
        if len(panels) != 1: raise RuntimeError('A single connected laptop panel is required')
        self.name = panels[0]
        self.recovery = recovery or self.configuration(monitors, logical, properties)
        self.logical_size = None
        if configure:
            if not virtual_output: raise RuntimeError('Start Use phone as main display to switch off the laptop panel')
            self.require_virtual(monitors, logical)
            self.update_size(monitors, logical)
        self.brightness()

    def read_state(self):
        return self.bus.call_sync(self.destination, self.path, self.interface, 'GetCurrentState',
            None, None, self.Gio.DBusCallFlags.NONE, 2000, None).unpack()

    @staticmethod
    def configuration(monitors, logical, properties):
        modes = {spec[0]: next((mode[0] for mode in available if mode[-1].get('is-current')), None)
                 for spec, available, _ in monitors}
        return {'layout_mode': properties.get('layout-mode'),
            'monitor_properties': {spec[0]: {key: props[source] for key, source in
                [('color-mode','color-mode'),('underscanning','is-underscanning')] if source in props}
                for spec, _, props in monitors}, 'logical': [
            [x, y, scale, transform, primary, [[spec[0], modes[spec[0]]] for spec in specs]]
            for x, y, scale, transform, primary, specs, _ in logical]}

    @classmethod
    def list_outputs(cls):
        from gi.repository import Gio, GLib
        bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        _, monitors, logical, _ = bus.call_sync(cls.destination, cls.path, cls.interface, 'GetCurrentState',
            None, None, Gio.DBusCallFlags.NONE, 2000, None).unpack()
        outputs = []
        for spec, modes, props in monitors:
            group = next((g for g in logical if any(m[0] == spec[0] for m in g[5])), None)
            mode = next((m for m in modes if m[-1].get('is-current')), None)
            outputs.append({'name': spec[0], 'backend': 'gnome', 'connected': True,
                'enabled': group is not None, 'type': 7 if props.get('is-builtin') else 0,
                'pos': {'x': group[0] if group else 0, 'y': group[1] if group else 0},
                'scale': group[2] if group else 1, 'primary': bool(group and group[4]),
                'currentModeId': mode[0] if mode else None,
                'modes': [{'id': m[0], 'size': {'width': m[1], 'height': m[2]}} for m in modes]})
        return outputs

    def require_virtual(self, monitors, logical):
        if not any(spec[0] == self.virtual_output for spec, _, _ in monitors) or not any(
                any(spec[0] == self.virtual_output for spec in group[5]) for group in logical):
            raise RuntimeError('The phone display is no longer active')

    def update_size(self, monitors, logical):
        group = next(g for g in logical if any(m[0] == self.virtual_output for m in g[5]))
        modes = next(m for spec, m, _ in monitors if spec[0] == self.virtual_output)
        mode = next(m for m in modes if m[-1].get('is-current'))
        width, height = mode[1:3]
        if group[3] % 2: width, height = height, width
        self.logical_size = [round(width / group[2]), round(height / group[2])]

    def healthy(self):
        _, monitors, logical, _ = self.read_state()
        self.require_virtual(monitors, logical)

    def brightness(self):
        _, monitors, logical, _ = self.read_state()
        if not any(spec[0] == self.name for spec, _, _ in monitors): raise RuntimeError('Laptop panel is unavailable')
        return int(any(any(spec[0] == self.name for spec in group[5]) for group in logical))

    def phone_primary(self):
        _, _, logical, _ = self.read_state()
        return any(group[4] and any(spec[0] == self.virtual_output for spec in group[5]) for group in logical)

    def apply(self, configuration):
        serial, monitors, _, _ = self.read_state()
        available = {spec[0]: modes for spec, modes, _ in monitors}
        groups = []
        for x, y, scale, transform, primary, members in configuration['logical']:
            selected = []
            for name, mode_id in members:
                if name not in available: continue  # A portal output can disappear during recovery.
                modes = available[name]
                mode = next((m for m in modes if m[0] == mode_id), None)
                if mode is None:
                    mode = next((m for m in modes if m[-1].get('is-current') or m[-1].get('is-preferred')), None)
                if mode is None: raise RuntimeError('Original monitor mode is unavailable')
                if scale not in mode[5]: raise RuntimeError('Original monitor scale is unavailable')
                settings = configuration.get('monitor_properties', {}).get(name, {})
                selected.append((name, mode[0], {key: self.GLib.Variant('b' if key == 'underscanning' else 'u', value)
                    for key, value in settings.items()}))
            if selected: groups.append((x, y, scale, transform, primary, selected))
        if not groups: raise RuntimeError('No display is available for restoration')
        if not any(g[4] for g in groups):
            x, y, scale, transform, _, members = groups[0]
            groups[0] = (x, y, scale, transform, True, members)
        properties = {}
        if configuration.get('layout_mode') is not None:
            properties['layout-mode'] = self.GLib.Variant('u', configuration['layout_mode'])
        # Method 1 is temporary: do not rewrite the user's saved display settings.
        self.bus.call_sync(self.destination, self.path, self.interface, 'ApplyMonitorsConfig',
            self.GLib.Variant('(uua(iiduba(ssa{sv}))a{sv})', (serial, 1, groups, properties)),
            None, self.Gio.DBusCallFlags.NONE, 3000, None)

    def extend(self):
        _, monitors, logical, properties = self.read_state()
        self.require_virtual(monitors,logical)
        if not self.brightness(): raise RuntimeError('Keep the laptop display enabled to extend the desktop')
        current = self.configuration(monitors,logical,properties)
        available = {spec[0]: modes for spec,modes,_ in monitors}
        virtual = next(g for g in current['logical'] if any(m[0] == self.virtual_output for m in g[5]))
        virtual_mode = next(m for m in virtual[5] if m[0] == self.virtual_output)
        target = []
        for x,y,scale,transform,primary,members in current['logical']:
            retained = [m for m in members if m[0] != self.virtual_output]
            if retained: target.append([x,y,scale,transform,primary,retained])
        if not target: raise RuntimeError('A laptop desktop is required for an extended display')
        def right(group):
            name, mode_id = group[5][0]
            mode = next(m for m in available[name] if m[0] == mode_id)
            width = mode[2] if group[3] % 2 else mode[1]
            return group[0] + round(width / group[2])
        primary = next((g for g in target if g[4]), None)
        if primary is None:
            primary = next(g for g in target if any(m[0] == self.name for m in g[5])); primary[4] = True
        # Keep every existing output's position, mode, scale and primary status.
        # Add only the phone's monitor, adjacent to the right edge of the desktop.
        anchor = max(target,key=right)
        target.append([right(anchor),anchor[1],virtual[2],virtual[3],False,[virtual_mode]])
        self.apply({'layout_mode':current['layout_mode'],'logical':target,
                    'monitor_properties':current['monitor_properties']})
        if not self.brightness() or self.phone_primary(): raise RuntimeError('The OS did not keep the laptop display active and primary')
        _,monitors,logical,_ = self.read_state(); self.update_size(monitors,logical)

    def set(self, value):
        if value:
            self.apply(self.recovery)
            return
        _, monitors, logical, properties = self.read_state()
        self.require_virtual(monitors, logical)
        if not self.brightness() and self.phone_primary(): return
        current = self.configuration(monitors, logical, properties)
        panel = next((g for g in current['logical'] if any(m[0] == self.name for m in g[5])), None)
        if panel is None: raise RuntimeError('Laptop panel layout is unavailable')
        virtual = next(g for g in current['logical'] if any(m[0] == self.virtual_output for m in g[5]))
        virtual_mode = next(m for m in virtual[5] if m[0] == self.virtual_output)
        target = []
        for x, y, scale, transform, _, members in current['logical']:
            retained = [m for m in members if m[0] not in (self.name, self.virtual_output)]
            if retained: target.append([x, y, scale, transform, False, retained])
        # The virtual monitor replaces the built-in output and owns the primary desktop.
        modes = next(m for spec, m, _ in monitors if spec[0] == self.virtual_output)
        mode = next(m for m in modes if m[0] == virtual_mode[1])
        scale = panel[2] if panel[2] in mode[5] else virtual[2]
        if scale != panel[2] and target:
            raise RuntimeError('The phone display cannot preserve the current multi-monitor scale')
        target.append([panel[0], panel[1], scale, virtual[3], True, [virtual_mode]])
        self.apply({'layout_mode': current['layout_mode'], 'logical': target,
                    'monitor_properties': current['monitor_properties']})
        if self.brightness() or not self.phone_primary():
            raise RuntimeError('The OS did not make the phone the primary display')
        _, monitors, logical, _ = self.read_state()
        self.update_size(monitors, logical)


def gnome_session():
    # User services can start before desktop environment variables are imported.
    from gi.repository import Gio, GLib
    bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    return bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
        'NameHasOwner', GLib.Variant('(s)', (GnomeDisplayPower.destination,)), None,
        Gio.DBusCallFlags.NONE, 2000, None).unpack()[0]


def display_hardware(virtual_output, device=None, configure=True, recovery=None, backend=None):
    if backend == 'gnome-output' or (backend is None and gnome_session()):
        return GnomeDisplayPower(virtual_output, device, configure, recovery)
    return DisplayPower(virtual_output, device, configure, recovery)


def list_outputs():
    if gnome_session():
        return GnomeDisplayPower.list_outputs()
    return json.loads(DisplayPower.run(['--json']))['outputs']


class DisplayGuard:
    def __init__(self, directory, hardware=None, virtual_output=None, recover_only=False):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.journal = self.directory / 'display-backlight.json'
        self.lock = os.open(self.directory / 'display-backlight.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.previous = None
            self.extended = False
            self.cancelled = False
            if hardware is None and self.journal.exists():
                data = self.read_journal()
                # Recover the earlier brightness-only implementation before using power control.
                if data.get('version') == 1:
                    self.hardware = Backlight()
                else:
                    self.hardware = display_hardware(data.get('virtual_output'), data.get('device'), configure=False,
                        recovery=data.get('configuration'), backend=data.get('backend'))
                self.recover()
            if not recover_only or hardware:
                self.hardware = hardware or display_hardware(virtual_output)
            self.recover()
        except Exception:
            os.close(self.lock)
            raise

    def save(self, value):
        fd, path = tempfile.mkstemp(prefix='.display-backlight-', dir=self.directory)
        try:
            with os.fdopen(fd, 'w') as output:
                json.dump({'version': 3, 'mode': 'extend' if self.extended else 'primary', 'backend': getattr(self.hardware, 'kind', 'backlight'),
                    'virtual_output': getattr(self.hardware, 'virtual_output', None), 'device': self.hardware.name,
                    'configuration': getattr(self.hardware, 'recovery', None), 'brightness': value}, output)
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
        if data.get('version') not in (1, 2, 3) or data.get('device') != self.hardware.name or not isinstance(data.get('brightness'), int) or not 0 < data['brightness'] <= self.hardware.maximum:
            raise RuntimeError('Display recovery journal does not match this laptop')
        self.previous = data['brightness']
        self.extended = data.get('mode') == 'extend'
        self.restore()

    def off(self):
        if self.extended: raise RuntimeError("Stop the extended display before switching off the laptop panel")
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

    def extend(self):
        if self.extended: return
        if self.previous is not None: raise RuntimeError('Stop the primary-display handoff before extending the desktop')
        if self.hardware.brightness() <= 0: raise RuntimeError('Keep the laptop display enabled to extend the desktop')
        self.previous = self.hardware.brightness()
        self.extended = True
        self.save(self.previous)
        try: self.hardware.extend()
        except Exception:
            self.restore(); raise

    def restore(self):
        if self.previous is not None:
            # Extended mode restores its layout even while the panel stays on.
            # Primary mode preserves a later local panel restoration.
            if self.extended or self.hardware.brightness() == 0:
                self.hardware.set(self.previous)
                if self.hardware.brightness() == 0:
                    raise RuntimeError('Laptop display restoration is still pending')
            self.previous = None
            self.extended = False
            self.journal.unlink(missing_ok=True)

    def status(self):
        return {'display_available': not self.extended, 'extended_display': self.extended, 'display_on': self.hardware.brightness() > 0,
            'logical_size': getattr(self.hardware, 'logical_size', None),
            'phone_primary': bool(getattr(self.hardware, 'phone_primary', lambda: False)())}

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
            elif not self.extended: self.restore()
            reply({'type': 'reply', 'ok': True, **self.status()})
        except Exception as error:
            reply({'type': 'reply', 'ok': False, 'error': str(error)[:512], **self.status()})

    def close(self):
        os.close(self.lock)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-dir', type=Path)
    parser.add_argument('--list-outputs', action='store_true')
    parser.add_argument('--recover', action='store_true')
    parser.add_argument('--virtual-output')
    parser.add_argument('--extended-display',action='store_true')
    args = parser.parse_args()
    if args.list_outputs:
        print(json.dumps(list_outputs())); return 0
    if args.state_dir is None: parser.error('--state-dir is required')
    if args.recover and not (args.state_dir / 'display-backlight.json').exists(): return 0
    guard = None
    try:
        guard = DisplayGuard(args.state_dir, virtual_output=args.virtual_output, recover_only=args.recover)
        for number in (signal.SIGINT, signal.SIGTERM):
            signal.signal(number, lambda *_: setattr(guard, 'cancelled', True))
        if not args.recover:
            if args.extended_display: guard.extend()
            guard.run()
        return 0
    except Exception as error:
        print(json.dumps({'type': 'ready', 'display_available': False, 'display_on': True, 'error': str(error)[:512]}), flush=True)
        return 1
    finally:
        if guard: guard.close()


if __name__ == '__main__':
    raise SystemExit(main())
