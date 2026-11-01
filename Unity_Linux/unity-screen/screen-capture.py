#!/usr/bin/env python3
"""Consent-based PipeWire capture. stdout contains length-prefixed JPEG only."""
import math
import argparse
import json
import os
from pathlib import Path
import select
import signal
import struct
import subprocess
import sys
import threading
import uuid

import gi
gi.require_version('Gst', '1.0')
from gi.repository import Gio, GLib, Gst

PORTAL = 'org.freedesktop.portal.Desktop'
PATH = '/org/freedesktop/portal/desktop'
SCREEN = 'org.freedesktop.portal.ScreenCast'
REMOTE = 'org.freedesktop.portal.RemoteDesktop'
APP_ID = 'org.unityconnect.Unity.Screen'


def output_config():
    process = subprocess.run(['/usr/bin/python3', str(Path(__file__).with_name('display-guard.py')), '--list-outputs'],
        capture_output=True, text=True, timeout=4)
    if process.returncode:
        reason = process.stderr.strip().splitlines()
        raise RuntimeError(reason[-1][:300] if reason else "Cannot read the desktop monitor configuration")
    return json.loads(process.stdout)



class GuardClient:
    def __init__(self, directory, virtual_output=None, extended_display=False):
        self.lock = threading.RLock()
        arguments = ['/usr/bin/python3', str(Path(__file__).with_name('display-guard.py')), '--state-dir', str(directory)]
        if virtual_output: arguments += ['--virtual-output', virtual_output]
        if extended_display: arguments.append('--extended-display')
        self.process = subprocess.Popen(arguments,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
        try: self.state = self.read()
        except Exception:
            self.close()
            raise
        if not (self.state.get('extended_display') if extended_display else self.state.get('display_available')):
            self.close()
            raise RuntimeError(self.state.get('error', 'Laptop display control is unavailable'))

    def read(self):
        if not select.select([self.process.stdout], [], [], 3)[0]:
            raise RuntimeError('Laptop display control timed out')
        line = self.process.stdout.readline(4097)
        if not line or len(line) > 4096:
            raise RuntimeError('Laptop display guard stopped')
        return json.loads(line)

    def send(self, action):
        with self.lock:
            if self.process.poll() is not None:
                raise RuntimeError('Laptop display guard stopped; its recovery journal will restore the laptop display')
            self.process.stdin.write(json.dumps({'action': action}) + '\n'); self.process.stdin.flush()
            if action != 'heartbeat':
                self.state = self.read()
                if not self.state.get('ok'): raise RuntimeError(self.state.get('error', 'Display control failed'))
            return self.state

    def close(self):
        with self.lock:
            if self.process.stdin: self.process.stdin.close()
            try: self.process.wait(timeout=1)
            except subprocess.TimeoutExpired: pass  # The independent guard finishes restoration.
            if self.process.stdout: self.process.stdout.close()


def dimensions(width, height):
    if width <= 0 or height <= 0:
        raise ValueError('Invalid screen dimensions')
    ratio = min(1.0, 1280 / max(width, height), math.sqrt(921600 / (width * height)))
    return max(1, int(width * ratio)), max(1, int(height * ratio))


class Capture:
    def __init__(self, control=False, state_dir=None, phone_display=False, extended_display=False):
        Gst.init(None)
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        self.registered = False
        try:
            self.bus.call_sync(PORTAL, PATH, 'org.freedesktop.host.portal.Registry', 'Register',
                GLib.Variant('(sa{sv})', (APP_ID, {})), None, Gio.DBusCallFlags.NONE, 2000, None)
            self.registered = True
        except GLib.Error: pass  # Older portals retain their ordinary approval flow.
        self.session = None
        self.request = None
        self.pipeline = None
        self.loop = GLib.MainLoop()
        self.cancelled = False
        self.error = None
        self.stop = threading.Event()
        self.control = control
        if phone_display and extended_display: raise ValueError('Choose primary or extended display, not both')
        self.phone_display = phone_display
        self.extended_display = extended_display
        self.guard_requested = False
        self.display_attempts = 0
        self.virtual = False
        self.before_outputs = set()
        self.virtual_size = None
        self.state_dir = state_dir
        self.guard = None
        self.ready = False
        self.devices = 0
        self.node = None
        self.logical_size = None
        self.buttons = set()
        self.keys = set()
        self.last_input_sequence = 0
        self.inhibit = None
        self.signals = [GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, s, self.cancel) for s in (signal.SIGTERM, signal.SIGINT)]

    def cancel(self):
        self.cancelled = True
        self.stop.set()
        self.loop.quit()
        return GLib.SOURCE_CONTINUE

    def request_call(self, method, prefix, signature, options, interface=SCREEN):
        token = 'unity_' + uuid.uuid4().hex
        options = dict(options, handle_token=GLib.Variant('s', token))
        sender = self.bus.get_unique_name()[1:].replace('.', '_')
        path = PATH + '/request/' + sender + '/' + token
        self.request = path
        result = []

        def response(*args):
            result.append(args[-1].unpack())
            self.loop.quit()

        subscription = self.bus.signal_subscribe(PORTAL, 'org.freedesktop.portal.Request',
            'Response', path, None, Gio.DBusSignalFlags.NONE, response)
        timer = GLib.timeout_add_seconds(120, lambda: (self.cancel(), GLib.SOURCE_REMOVE)[1])
        try:
            reply = self.bus.call_sync(PORTAL, PATH, interface, method,
                GLib.Variant(signature, (*prefix, options)), GLib.VariantType.new('(o)'),
                Gio.DBusCallFlags.NONE, 10000, None)
            if reply.unpack()[0] != path:
                raise RuntimeError('Portal returned an unexpected request path')
            if not result and not self.cancelled:
                self.loop.run()
            if self.cancelled or not result or result[0][0] != 0:
                raise RuntimeError('Screen capture permission was denied or cancelled')
            self.request = None
            return result[0][1]
        finally:
            self.bus.signal_unsubscribe(subscription)
            if GLib.MainContext.default().find_source_by_id(timer):
                GLib.source_remove(timer)

    def close(self):
        self.stop.set()
        if self.guard: self.guard.close(); self.guard = None
        for button in list(self.buttons):
            try: self.remote('NotifyPointerButton', '(oa{sv}iu)', (button, 0))
            except Exception: pass
        self.buttons.clear()
        for key in list(self.keys):
            try: self.remote('NotifyKeyboardKeysym', '(oa{sv}iu)', (key, 0))
            except Exception: pass
        self.keys.clear()
        for source in self.signals:
            GLib.source_remove(source)
        self.signals.clear()
        if self.pipeline:
            self.pipeline.set_state(Gst.State.NULL)
        for path, interface in [(self.request, 'org.freedesktop.portal.Request'), (self.inhibit, 'org.freedesktop.portal.Request'),
                                (self.session, 'org.freedesktop.portal.Session')]:
            if path:
                try:
                    self.bus.call_sync(PORTAL, path, interface, 'Close', None, None,
                        Gio.DBusCallFlags.NONE, 2000, None)
                except GLib.Error:
                    pass

    def run(self):
        props = self.bus.call_sync(PORTAL, PATH, 'org.freedesktop.DBus.Properties', 'GetAll',
            GLib.Variant('(s)', (SCREEN,)), GLib.VariantType.new('(a{sv})'),
            Gio.DBusCallFlags.NONE, 10000, None).unpack()[0]
        source_types = int(props.get('AvailableSourceTypes', 1)) & (1 if self.control else 3)
        virtual = False
        before_outputs = set()
        if self.phone_display or self.extended_display:
            if (self.phone_display and not self.control) or not self.state_dir or not int(props.get('AvailableSourceTypes', 1)) & 4:
                raise RuntimeError('This desktop cannot use the phone as a virtual primary display' if self.phone_display else 'This desktop cannot create a virtual extended display')
            baseline = output_config()
            before_outputs = {o['name'] for o in baseline}
            panel = next((o for o in baseline if o.get('type') == 7 and o.get('enabled')), None)
            if panel is None: raise RuntimeError('An active built-in laptop display is required')
            mode = next((m for m in panel['modes'] if m['id'] == panel['currentModeId']), None)
            if mode: self.virtual_size = (mode['size']['width'], mode['size']['height'])
            source_types = 4; virtual = True
        elif self.control and self.registered and int(props.get('AvailableSourceTypes', 1)) & 4:
            try:
                permission = self.bus.call_sync('org.freedesktop.impl.portal.PermissionStore', '/org/freedesktop/impl/portal/PermissionStore',
                    'org.freedesktop.impl.portal.PermissionStore', 'GetPermission',
                    GLib.Variant('(sss)', ('kde-authorized', 'remote-desktop', APP_ID)), None, Gio.DBusCallFlags.NONE, 1000, None).unpack()[0]
                if 'yes' in permission:
                    before_outputs = {o['name'] for o in output_config()}
                    source_types = 4
                    virtual = True
            except (GLib.Error, OSError, ValueError, subprocess.SubprocessError): pass
        self.virtual = virtual; self.before_outputs = before_outputs
        if not source_types:
            raise RuntimeError('This desktop portal cannot share monitors or windows')
        cursor_modes = int(props.get('AvailableCursorModes', 1))
        cursor_mode = next((mode for mode in (2, 1, 4) if cursor_modes & mode), 1)
        interface = REMOTE if self.control else SCREEN
        created = self.request_call('CreateSession', (), '(a{sv})',
            {'session_handle_token': GLib.Variant('s', 'unity_' + uuid.uuid4().hex)}, interface)
        self.session = created['session_handle']
        if self.control:
            self.request_call('SelectDevices', (self.session,), '(oa{sv})', {'types': GLib.Variant('u', 3), 'persist_mode': GLib.Variant('u', 0)}, REMOTE)
        selection = {'types': GLib.Variant('u', source_types), 'multiple': GLib.Variant('b', False), 'cursor_mode': GLib.Variant('u', cursor_mode)}
        if not self.control: selection['persist_mode'] = GLib.Variant('u', 0)
        self.request_call('SelectSources', (self.session,), '(oa{sv})',
            selection)
        selected = self.request_call('Start', (self.session, ''), '(osa{sv})', {}, interface)
        self.devices = int(selected.get('devices', 0))
        if self.control and self.devices & 3 != 3:
            raise RuntimeError('Allow keyboard and mouse control in the desktop sharing chooser')
        streams = selected.get('streams', [])
        if len(streams) != 1:
            raise RuntimeError('Choose one screen or window')
        node, stream_props = streams[0]
        size = stream_props.get('size')
        if size and (size[0] <= 0 or size[1] <= 0): size = None
        if virtual and stream_props.get('source_type', 4) != 4:
            raise RuntimeError('The desktop did not create the requested phone display')
        self.node = int(node)
        self.logical_size = stream_props.get('logical_size', size)
        try:
            self.inhibit = self.bus.call_sync(PORTAL, PATH, 'org.freedesktop.portal.Inhibit', 'Inhibit',
                GLib.Variant('(sua{sv})', ('', 12, {'reason': GLib.Variant('s', 'Unity screen sharing')})),
                GLib.VariantType.new('(o)'), Gio.DBusCallFlags.NONE, 2000, None).unpack()[0]
        except GLib.Error: pass
        # PipeWire may omit size. Negotiate it on the source pad before scaling.
        reply, fd_list = self.bus.call_with_unix_fd_list_sync(PORTAL, PATH, SCREEN,
            'OpenPipeWireRemote', GLib.Variant('(oa{sv})', (self.session, {})),
            GLib.VariantType.new('(h)'), Gio.DBusCallFlags.NONE, 10000, None, None)
        fd = fd_list.get(reply.unpack()[0])
        try:
            self.pipeline = Gst.parse_launch(
                f'pipewiresrc name=source fd={fd} path={int(node)} do-timestamp=true ! '
                + (f'video/x-raw,width={self.virtual_size[0]},height={self.virtual_size[1]} ! ' if self.virtual_size else '')
                + 'queue leaky=downstream max-size-buffers=1 max-size-bytes=0 max-size-time=0 ! '
                'videorate drop-only=true ! video/x-raw,framerate=15/1 ! videoconvert ! videoscale name=scale add-borders=false ! '
                'capsfilter name=limits caps="video/x-raw,framerate=15/1,pixel-aspect-ratio=1/1" ! '
                'jpegenc name=encoder quality=70 ! appsink name=frames max-buffers=1 drop=true sync=false')
            source = self.pipeline.get_by_name('source')
            if source and stream_props.get('pipewire-serial') is not None and source.find_property('target-object'):
                source.set_property('path', None)
                source.set_property('target-object', str(int(stream_props['pipewire-serial'])))
            limits = self.pipeline.get_by_name('limits')
            if size:
                w, h = dimensions(*size)
                limits.set_property('caps', Gst.Caps.from_string(f'video/x-raw,width={w},height={h},framerate=15/1,pixel-aspect-ratio=1/1'))
            else:
                # All frames remain bounded even on portals that omit source dimensions.
                limits.set_property('caps', Gst.Caps.from_string('video/x-raw,width=[1,1280],height=[1,720],framerate=15/1,pixel-aspect-ratio=1/1'))
            def source_caps(pad, info):
                event = info.get_event()
                if event and event.type == Gst.EventType.CAPS:
                    structure = event.parse_caps().get_structure(0)
                    w, h = dimensions(structure.get_value('width'), structure.get_value('height'))
                    if self.logical_size is None:
                        self.logical_size = (structure.get_value('width'), structure.get_value('height'))
                    limits.set_property('caps', Gst.Caps.from_string(f'video/x-raw,width={w},height={h},framerate=15/1,pixel-aspect-ratio=1/1'))
                return Gst.PadProbeReturn.OK
            self.pipeline.get_by_name('scale').get_static_pad('sink').add_probe(Gst.PadProbeType.EVENT_DOWNSTREAM, source_caps)
            bus = self.pipeline.get_bus()
            bus.add_signal_watch()

            def message(_bus, msg):
                if msg.type in (Gst.MessageType.ERROR, Gst.MessageType.EOS):
                    self.error = str(msg.parse_error()[0]) if msg.type == Gst.MessageType.ERROR else 'Screen capture ended'
                    self.loop.quit()

            bus.connect('message', message)
            subscription = self.bus.signal_subscribe(PORTAL, 'org.freedesktop.portal.Session',
                'Closed', self.session, None, Gio.DBusSignalFlags.NONE, lambda *args: self.cancel())
            sink = self.pipeline.get_by_name('frames')

            def output():
                try:
                    while not self.stop.is_set():
                        sample = sink.emit('try-pull-sample', Gst.SECOND)
                        if sample is None:
                            continue
                        buffer = sample.get_buffer()
                        length = buffer.get_size()
                        if length > 262144:
                            encoder = self.pipeline.get_by_name('encoder')
                            encoder.set_property('quality', max(10, encoder.get_property('quality') - 15))
                            continue
                        if self.virtual and not self.guard_requested:
                            self.guard_requested = True
                            GLib.idle_add(self.prepare_display)
                        data = struct.pack('>I', length) + buffer.extract_dup(0, length)
                        view = memoryview(data)
                        while view and not self.stop.is_set():
                            view = view[os.write(1, view):]
                except Exception as exc:
                    self.error = str(exc)
                    GLib.idle_add(self.loop.quit)

            worker = threading.Thread(target=output, name='unity-screen-output', daemon=True)
            self.pipeline.set_state(Gst.State.PLAYING)
            self.ready = True
            self.report({'type': 'state', 'input_enabled': self.control, 'display_available': self.guard is not None,
                'display_on': True, 'phone_primary': False, 'display_error': ''})
            worker.start()
            self.loop.run()
            self.bus.signal_unsubscribe(subscription)
            if self.error and not self.cancelled:
                raise RuntimeError(self.error)
        finally:
            if self.pipeline:
                self.pipeline.set_state(Gst.State.NULL)
            os.close(fd)

    def prepare_display(self):
        # Mutter creates the virtual output only after PipeWire negotiates video.
        # Do not claim a display or switch the panel off before the first frame.
        if self.cancelled or self.stop.is_set(): return GLib.SOURCE_REMOVE
        try:
            created = [o for o in output_config() if o['name'] not in self.before_outputs and o.get('enabled')]
            if not created and self.display_attempts < 30:
                self.display_attempts += 1
                GLib.timeout_add(100, self.prepare_display)
                return GLib.SOURCE_REMOVE
            if len(created) != 1: raise RuntimeError('Cannot identify the active Unity phone display')
            self.guard = (GuardClient(self.state_dir, created[0]['name'], extended_display=True)
                if self.extended_display else GuardClient(self.state_dir, created[0]['name']))
            if self.guard.state.get('logical_size'): self.logical_size = self.guard.state['logical_size']
            self.report({'type': 'state', 'input_enabled': self.control,
                'display_available': not self.extended_display, 'extended_display': self.extended_display,
                'display_on': True, 'phone_primary': False, 'display_error': ''})
        except Exception as error:
            self.error = 'Phone display setup failed: ' + str(error)
            self.report({'type': 'state', 'input_enabled': self.control,
                'display_available': False, 'display_on': True, 'display_error': self.error[:512]})
            self.cancel()
        return GLib.SOURCE_REMOVE

    def report(self, value):
        print(json.dumps(value, ensure_ascii=True), file=sys.stderr, flush=True)

    def remote(self, method, signature, values):
        return self.bus.call_sync(PORTAL, PATH, REMOTE, method, GLib.Variant(signature, (self.session, {}, *values)),
            None, Gio.DBusCallFlags.NONE, 2000, None)

    def keysym(self, value, modifiers=()):
        codes = {'ctrl': 0xffe3, 'alt': 0xffe9, 'shift': 0xffe1, 'super': 0xffeb}
        pressed = []
        try:
            for modifier in modifiers:
                code = codes[modifier]; self.remote('NotifyKeyboardKeysym', '(oa{sv}iu)', (code, 1)); pressed.append(code); self.keys.add(code)
            self.remote('NotifyKeyboardKeysym', '(oa{sv}iu)', (value, 1))
            self.keys.add(value)
            self.remote('NotifyKeyboardKeysym', '(oa{sv}iu)', (value, 0)); self.keys.discard(value)
        finally:
            for code in reversed(pressed):
                try: self.remote('NotifyKeyboardKeysym', '(oa{sv}iu)', (code, 0)); self.keys.discard(code)
                except Exception: pass

    def control_command(self, command):
        identity = command.get('id', '')
        try:
            if not self.ready or self.cancelled: raise RuntimeError('Screen sharing is not ready')
            action, data = command['action'], command.get('data', {})
            if action == 'display':
                if not self.guard: raise RuntimeError('Laptop display power control is unavailable')
                result = self.guard.send('on' if data['on'] else 'off')
                if result.get('logical_size'): self.logical_size = result['logical_size']
                self.report({'type': 'state', 'input_enabled': self.control, **{k: result[k] for k in ('display_available', 'display_on', 'phone_primary', 'extended_display')}, 'display_error': ''})
            elif action == 'input':
                if not self.control or self.devices & 3 != 3: raise RuntimeError('Mouse and keyboard access was not approved')
                sequence = data['seq']
                if sequence <= self.last_input_sequence:
                    self.report({'type': 'reply', 'id': identity, 'ok': True, 'result': {'state': 'ignored'}}); return False
                self.last_input_sequence = sequence
                kind = data['kind']
                if kind in ('pointer', 'motion', 'tap'):
                    if not self.logical_size: raise RuntimeError('Screen coordinates are unavailable')
                    x, y = data['x'] * self.logical_size[0], data['y'] * self.logical_size[1]
                    self.remote('NotifyPointerMotionAbsolute', '(oa{sv}udd)', (self.node, min(x, self.logical_size[0] - 1), min(y, self.logical_size[1] - 1)))
                    if kind != 'motion':
                        button = {'left': 272, 'right': 273, 'middle': 274}[data.get('button', 'left')]
                        pressed = data.get('pressed', True)
                        self.remote('NotifyPointerButton', '(oa{sv}iu)', (button, int(pressed)))
                        if pressed: self.buttons.add(button)
                        else: self.buttons.discard(button)
                        if kind == 'tap':
                            self.remote('NotifyPointerButton', '(oa{sv}iu)', (button, 0)); self.buttons.discard(button)
                elif kind == 'scroll':
                    self.remote('NotifyPointerAxis', '(oa{sv}dd)', (data['dx'], data['dy']))
                elif kind == 'text':
                    for character in data['text']:
                        value = {'\n': 0xff0d, '\t': 0xff09}.get(character, ord(character) if ord(character) < 256 else 0x01000000 | ord(character))
                        self.keysym(value)
                elif kind == 'key':
                    keys = {'enter': 0xff0d, 'backspace': 0xff08, 'delete': 0xffff, 'tab': 0xff09, 'escape': 0xff1b,
                        'left': 0xff51, 'up': 0xff52, 'right': 0xff53, 'down': 0xff54, 'home': 0xff50, 'end': 0xff57, 'page_up': 0xff55, 'page_down': 0xff56}
                    name = data['key']; value = keys[name] if name in keys else ord(name)
                    for _ in range(data.get('repeat', 1)): self.keysym(value, data.get('modifiers', []))
                else: raise ValueError('Invalid remote input')
                result = {'state': 'sent'}
            else: raise ValueError('Invalid screen control')
            self.report({'type': 'reply', 'id': identity, 'ok': True, 'result': result})
        except Exception as error:
            self.report({'type': 'reply', 'id': identity, 'ok': False, 'error': str(error)[:512]})
        return False

    def read_controls(self):
        try:
            for line in sys.stdin:
                if len(line) > 4096: raise ValueError('Screen control exceeds limit')
                command = json.loads(line)
                if command.get('action') == 'heartbeat':
                    if self.guard:
                        try: self.guard.send('heartbeat')
                        except Exception:
                            self.guard.close(); self.guard = None
                            self.error = 'Display guard stopped; sharing ended to restore the laptop display'
                            self.report({'type': 'state', 'input_enabled': False, 'display_available': False, 'display_error': self.error})
                            GLib.idle_add(self.cancel)
                            return
                    continue
                GLib.idle_add(self.control_command, command)
        except Exception as error: self.error = str(error)
        GLib.idle_add(self.cancel)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--control', action='store_true')
    parser.add_argument('--state-dir', type=Path)
    parser.add_argument('--phone-display', action='store_true')
    parser.add_argument('--extended-display', action='store_true')
    args = parser.parse_args()
    capture = Capture(args.control, args.state_dir, args.phone_display, args.extended_display)
    threading.Thread(target=capture.read_controls, name='unity-screen-control', daemon=True).start()
    try:
        capture.run()
    except Exception as exc:
        print('Unity screen capture: ' + str(exc), file=sys.stderr)
        return 1
    finally:
        capture.close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
