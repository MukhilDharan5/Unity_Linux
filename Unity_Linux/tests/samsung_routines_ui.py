#!/usr/bin/env python3
"""Real GTK controls: capability gating and fixed Samsung trigger dispatch."""
import importlib.util
import json
from pathlib import Path
import sys
import traceback

import gi
gi.require_version('Gtk', '4.0'); gi.require_version('Adw', '1')
from gi.repository import Gio, GLib

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('unity', root / 'unity-gtk/unity.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


class Client:
    def __init__(self, changed, error): self.commands = []
    def manager(self, *args, **kwargs): pass
    def call(self, path, interface, method, signature='()', args=(), done=None, failed=None):
        if method == 'Execute':
            self.commands.append((args[0], args[1], json.loads(args[2])))


failures = []


class App(module.App):
    def __init__(self):
        super().__init__()
        self.set_application_id('org.unityconnect.Unity.RoutinesTest')
        self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)

    def do_activate(self):
        try:
            win = module.Window(self, client_factory=Client)
            win.path = '/org/unityconnect/devices/d_' + 'a' * 32
            win.devices[win.path] = {'Connected': True, 'Name': 'Samsung test phone'}
            win.authorized = True
            win.render_capabilities()
            assert not win.routines_group.get_visible(), 'Old phones do not show the group'
            win.capabilities = {'samsung_routines': {'status': 'available', 'reason': ''}}
            win.render_capabilities()
            assert win.routines_group.get_visible() and win.pages['connectivity'].get_visible()
            assert win.routines_group.is_ancestor(win.stack.get_child_by_name('connectivity'))
            assert all(button.get_sensitive() for button in win.routine_buttons)
            for button in win.routine_buttons: button.emit('clicked')
            assert win.client.commands == [('samsung_routines', action, {}) for action in
                                           ['hotspot_on', 'hotspot_off', 'disconnect_bluetooth']]
            for status in ['disabled', 'permission_required', 'unsupported']:
                win.capabilities['samsung_routines']['status'] = status
                win.render_capabilities()
                assert not any(button.get_sensitive() for button in win.routine_buttons)
                assert win.routines_group.get_visible() == (status != 'unsupported')
            win.capabilities['samsung_routines']['status'] = 'available'
            win.authorized = False; win.render_capabilities()
            assert not any(button.get_sensitive() for button in win.routine_buttons)
            win.authorized = True; win.devices[win.path]['Connected'] = False; win.render_capabilities()
            assert not any(button.get_sensitive() for button in win.routine_buttons)
            win.destroy()
        except Exception:
            failures.append(traceback.format_exc())
        GLib.idle_add(self.quit)


App().run(sys.argv)
if failures: raise AssertionError('\n'.join(failures))
print('Samsung Routines GTK controls passed')
