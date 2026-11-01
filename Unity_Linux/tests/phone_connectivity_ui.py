#!/usr/bin/env python3
"""Production Connectivity widgets, exact device targeting and observed-state switches."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import traceback
import gi
gi.require_version('Gtk', '4.0'); gi.require_version('Adw', '1')
from gi.repository import Gio, GLib, Gtk, Graphene

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('unity', root / 'unity-gtk/unity.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
state = {'hotspot_enabled': True, 'bluetooth_enabled': True, 'bluetooth_status': 'available', 'complete': False,
         'reason': 'Connected profiles and live Bluetooth links reported by Android.', 'devices': [
             {'name': 'Headphones', 'address': '11:22:33:44:55:66', 'profiles': ['Audio', 'Calls']},
             {'name': 'Speaker', 'address': '22:33:44:55:66:77', 'profiles': ['Audio']}]}


class Client:
    def __init__(self, changed, error): self.commands = []; self.results = {}
    def manager(self, *args, **kwargs): pass
    def call(self, path, interface, method, signature='()', args=(), done=None, failed=None):
        if method == 'Execute':
            feature, action, payload = args; self.commands.append((feature, action, json.loads(payload)))
            op = f'/org/unityconnect/operations/o_{len(self.commands):032x}'
            self.results[op] = {'feature': feature, 'status': 'completed', 'progress': 100,
                               'result': {'state': 'trigger_sent', 'confirmed': False, 'reason': 'Trigger sent; execution is unconfirmed.'}}
            if done: done(op)
        elif method == 'GetState' and path in self.results and done: done(json.dumps(self.results[path]))


failures = []
win = None
capture_attempts = 0


class App(module.App):
    def __init__(self):
        super().__init__(); self.set_application_id('org.unityconnect.Unity.ConnectivityTest')
        self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)

    def do_activate(self):
        global win
        try:
            win = module.Window(self, client_factory=Client)
            win.path = '/org/unityconnect/devices/d_' + 'a' * 32
            win.devices[win.path] = {'Connected': True, 'Name': 'Test Samsung phone'}; win.authorized = True
            win.capabilities = {f: {'status': 'available', 'reason': ''} for f in ['phone_connectivity', 'samsung_routines']}
            win.render_capabilities(); win.render_phone_connectivity(copy.deepcopy(state))
            assert len(win.pages) == 11 and win.pages['connectivity'].page_title == 'Connectivity'
            assert win.phone_hotspot.get_active() and win.phone_hotspot.get_sensitive()
            assert len(win.phone_device_rows) == 2 and not win.client.commands, 'Reports never cause radio commands'
            win.phone_hotspot.set_active(False)
            assert win.client.commands[-1] == ('samsung_routines', 'hotspot_off', {})
            assert win.phone_hotspot.get_active(), 'Submission cannot fake a hotspot change'
            off = copy.deepcopy(state); off['hotspot_enabled'] = False; win.render_phone_connectivity(off)
            assert not win.phone_hotspot.get_active() and len(win.client.commands) == 1
            win.phone_hotspot.set_active(True)
            assert win.client.commands[-1] == ('samsung_routines', 'hotspot_on', {})
            assert not win.phone_hotspot.get_active()
            first = win.phone_device_rows['11:22:33:44:55:66']; first.set_active(False)
            assert win.client.commands[-1] == ('samsung_routines', 'disconnect_bluetooth_device', {'address': '11:22:33:44:55:66'})
            assert first.get_active() and len(win.phone_device_rows) == 2
            disconnected = copy.deepcopy(off); disconnected['devices'] = disconnected['devices'][1:]
            win.render_phone_connectivity(disconnected)
            assert list(win.phone_device_rows) == ['22:33:44:55:66:77']
            first.set_active(False); assert len(win.client.commands) == 3, 'Stale rows cannot target another device'
            win.capabilities['samsung_routines']['status'] = 'disabled'; win.render_capabilities()
            assert not win.phone_hotspot.get_sensitive() and not any(row.get_sensitive() for row in win.phone_device_rows.values())
            denied = copy.deepcopy(state); denied.update(bluetooth_status='permission_required', bluetooth_enabled=None, devices=[])
            win.render_phone_connectivity(denied)
            assert not win.phone_device_rows and 'Nearby' in win.phone_bluetooth_hint.get_title()
            win.capabilities['samsung_routines']['status'] = 'available'; win.render_capabilities()
            unknown = copy.deepcopy(state); unknown['hotspot_enabled'] = None; win.render_phone_connectivity(unknown)
            assert not win.phone_hotspot.get_sensitive() and win.hotspot_choices.get_visible()
            win.hotspot_on_button.emit('clicked'); assert win.client.commands[-1] == ('samsung_routines', 'hotspot_on', {})
            win.hotspot_off_button.emit('clicked'); assert win.client.commands[-1] == ('samsung_routines', 'hotspot_off', {})
            assert win.phone_connectivity_state['hotspot_enabled'] is None
            win.render_phone_connectivity(copy.deepcopy(state)); win.authorized = False; win.render_capabilities()
            assert not win.phone_device_rows and not win.phone_hotspot.get_sensitive()
            win.authorized = True; win.render_phone_connectivity(copy.deepcopy(state))
            win.devices[win.path]['Connected'] = False; win.render_capabilities()
            assert not win.phone_device_rows and not win.phone_hotspot.get_sensitive()
            win.devices[win.path]['Connected'] = True; win.render_capabilities(); win.render_phone_connectivity(copy.deepcopy(state))
            win.updating_dropdown = True; win.device_dropdown.set_model(Gtk.StringList.new(['Test Samsung phone']))
            win.device_dropdown.set_selected(0); win.updating_dropdown = False; win.banner.set_revealed(False)
            win.nav.select_row(win.pages['connectivity']); win.set_default_size(1140, 900); win.present()
            if '--capture' in sys.argv: GLib.timeout_add(1000, capture)
            else: GLib.idle_add(self.quit)
        except Exception:
            failures.append(traceback.format_exc()); GLib.idle_add(self.quit)


app = App()


def capture():
    global capture_attempts
    try:
        win.get_child().allocate(win.get_width(), win.get_height(), -1, None)
        paintable = Gtk.WidgetPaintable.new(win); snapshot = Gtk.Snapshot()
        paintable.snapshot(snapshot, win.get_width(), win.get_height()); node = snapshot.to_node()
        if node is None:
            snapshot = Gtk.Snapshot(); win.snapshot_child(win.get_child(), snapshot); node = snapshot.to_node()
        if node is None and capture_attempts < 20:
            capture_attempts += 1; win.queue_draw(); GLib.timeout_add(250, capture); return False
        assert node is not None, 'Window did not render'
        bounds = Graphene.Rect().init(0, 0, win.get_width(), win.get_height())
        canvas = Gtk.Snapshot()
        found, background = win.get_style_context().lookup_color('window_bg_color')
        assert found
        canvas.append_color(background, bounds); canvas.append_node(node)
        texture = win.get_renderer().render_texture(canvas.to_node(), bounds)
        texture.save_to_png('/tmp/unity-connectivity.png')
    except Exception: failures.append(traceback.format_exc())
    app.quit(); return False


app.run([arg for arg in sys.argv if arg != '--capture'])
if failures: raise AssertionError('\n'.join(failures))
print('Connectivity GTK controls and phone-state updates passed')
