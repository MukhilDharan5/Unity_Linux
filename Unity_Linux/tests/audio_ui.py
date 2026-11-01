#!/usr/bin/env python3
"""Exercise the production audio chooser, directions, approval cancellation and quiet rendering."""
import importlib.util
import json
from pathlib import Path
import sys
import traceback
import os
import tempfile
settings_dir = tempfile.TemporaryDirectory(prefix='unity-audio-settings-')
os.environ['XDG_CONFIG_HOME'] = settings_dir.name
import gi
gi.require_version('Gtk', '4.0'); gi.require_version('Adw', '1')
from gi.repository import Gio, Gtk, GLib, Graphene

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('unity', ROOT / 'unity-gtk/unity.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class Client:
    def __init__(self, changed, error): self.commands = []; self.operations = {}
    def manager(self, *args, **kwargs): pass
    def call(self, path, interface, method, signature='()', args=(), done=None, failed=None):
        if method == 'Execute':
            feature, action, data = args; self.commands.append((feature, action, json.loads(data)))
            op = f'/org/unityconnect/operations/o_{len(self.commands):032x}'
            self.operations[op] = json.dumps({'feature': feature, 'status': 'running', 'progress': 0})
            GLib.idle_add(lambda: (done(op), False)[1])
        elif method == 'GetState' and path in self.operations:
            GLib.idle_add(lambda: (done(self.operations[path]), False)[1])

failures = []
class App(module.App):
    def __init__(self):
        super().__init__(); self.set_application_id('org.unityconnect.Unity.AudioTest'); self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)
    def do_activate(self):
        self.capture_attempts = 0
        self.narrow = False
        self.win = module.Window(self, client_factory=Client)
        win = self.win
        win.path = '/org/unityconnect/devices/d_' + 'a' * 32
        win.devices[win.path] = {'Connected': True, 'Name': 'Test phone'}; win.authorized = True
        win.capabilities = {'audio': {'status': 'available', 'reason': ''}}
        win.render_capabilities(); win.stack.set_visible_child_name('media')
        win.set_default_size(1140, 900); win.present()
        GLib.timeout_add(300, self.check)
    def check(self):
        try:
            win = self.win
            win.audio_transport.set_selected(1)
            win.phone_audio_route.set_active(True)
            assert win.client.commands[-1] == ('audio', 'start', {'source': 'phone', 'transport': 'lan'})
            assert win.audio_pending
            win.render_audio_route({'state': 'starting', 'source': 'phone', 'transport': 'lan'})
            assert win.phone_audio_route.get_active() and win.phone_audio_route.get_sensitive()
            assert not win.audio_route.get_sensitive() and not win.audio_transport.get_sensitive()
            assert 'Approve' in win.phone_audio_route.get_subtitle()
            assert len(win.client.commands) == 1, 'State rendering cannot start another stream'
            win.phone_audio_route.set_active(False)
            assert win.client.commands[-1] == ('audio', 'stop', {})
            win.audio_pending = False; win.render_audio_route({'state': 'stopped'})
            win.audio_transport.set_selected(2); win.audio_route.set_active(True)
            assert win.client.commands[-1] == ('audio', 'start', {'source': 'desktop', 'transport': 'bluetooth'})
            win.render_audio_route({'state': 'streaming', 'source': 'desktop', 'transport': 'bluetooth'})
            assert win.audio_route.get_active() and not win.phone_audio_route.get_active()
            assert 'Bluetooth' in win.audio_route.get_subtitle()
            count = len(win.client.commands)
            win.render_audio_route({'state': 'failed', 'source': 'desktop', 'error': 'Selected Wi-Fi connection unavailable'})
            assert not win.audio_route.get_active() and win.audio_transport.get_sensitive()
            assert 'unavailable' in win.audio_route.get_subtitle()
            assert len(win.client.commands) == count
            win.capabilities['audio']['status'] = 'disabled'; win.render_audio_route({})
            assert not win.audio_route.get_sensitive() and not win.phone_audio_route.get_sensitive()
            win.capabilities['audio']['status'] = 'available'; win.render_audio_route({})
            GLib.timeout_add(150, self.capture)
        except Exception:
            failures.append(traceback.format_exc()); self.quit()
        return False
    def capture(self):
        try:
            win = self.win; snapshot = Gtk.Snapshot()
            Gtk.WidgetPaintable.new(win).snapshot(snapshot, win.get_width(), win.get_height())
            node = snapshot.to_node()
            if node is None:
                snapshot = Gtk.Snapshot(); win.snapshot_child(win.get_child(), snapshot); node = snapshot.to_node()
            if node is None and self.capture_attempts < 100:
                self.capture_attempts += 1; win.queue_draw(); GLib.timeout_add(100, self.capture); return False
            assert node is not None
            win.get_renderer().render_texture(node, Graphene.Rect().init(0, 0, win.get_width(), win.get_height())).save_to_png('/tmp/unity-audio-routing' + ('-narrow' if self.narrow else '') + '.png')
            if not self.narrow:
                self.narrow = True; self.capture_attempts = 0; win.set_default_size(420, 740); GLib.timeout_add(350, self.capture); return False
            print('GTK audio transport, direction, consent cancellation and failure-state checks passed', flush=True)
        except Exception: failures.append(traceback.format_exc())
        self.quit(); return False

app = App(); app.run([])
if failures:
    print('\n'.join(failures)); sys.exit(1)
