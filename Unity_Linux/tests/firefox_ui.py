#!/usr/bin/env python3
"""Actual GTK Firefox controls, markup handling, revoke clearing and layouts.

Run with --broadway for an isolated display without opening desktop windows.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import traceback

if '--broadway' in sys.argv:
    with tempfile.TemporaryDirectory(prefix='unity-firefox-ui-') as runtime:
        env = dict(os.environ, XDG_RUNTIME_DIR=runtime, GDK_BACKEND='broadway',
                   BROADWAY_DISPLAY=':17', GSETTINGS_BACKEND='memory', GSK_RENDERER='cairo')
        display = subprocess.Popen(['gtk4-broadwayd', '-a', '127.0.0.1', '-p', '18087', ':17'], env=env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        browser = None
        try:
            for _ in range(100):
                if (Path(runtime) / 'broadway17.socket').exists():
                    break
                if display.poll() is not None:
                    raise RuntimeError(display.stderr.read().decode())
                time.sleep(.03)
            (Path(runtime) / 'browser').mkdir()
            browser = subprocess.Popen([os.environ.get('FIREFOX_BINARY', '/usr/bin/firefox'), '-no-remote',
                '-headless', '-profile', str(Path(runtime) / 'browser'), 'http://127.0.0.1:18087/'],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            result = subprocess.run([sys.executable, __file__], env=env)
        finally:
            if browser:
                browser.terminate(); browser.wait(timeout=10)
            display.terminate(); display.wait(timeout=5); display.stderr.close()
        sys.exit(result.returncode)

import gi
gi.require_version('Gtk', '4.0'); gi.require_version('Adw', '1')
from gi.repository import Gio, Gtk, GLib, Graphene

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('unity', ROOT / 'unity-gtk/unity.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
STATUS = {'enabled': True, 'error': '', 'profiles': [{'id': 'profile-one', 'last_result': None, 'summary': {
    'activeId': 'context-one', 'tracking': True, 'restoreState': 'idle', 'contexts': [
        {'id': 'context-one', 'url': 'https://example.org/article?title=<b>Plain text</b>', 'updatedAt': 1793151000000,
         'closed': False, 'scrollProgress': .75, 'formCount': 0, 'media': [{'kind': 'video', 'time': 123}]},
        {'id': 'context-two', 'url': 'https://example.org/closed', 'updatedAt': 1793151000000,
         'closed': True, 'scrollProgress': None, 'formCount': 0, 'media': []},
    ]}}]}


class Client:
    def __init__(self, changed, error):
        self.commands = []
        self.status = json.loads(json.dumps(STATUS))

    def manager(self, method, signature='()', args=(), done=None):
        self.call(module.ROOT, 'org.unityconnect.Manager1', method, signature, args, done)

    def call(self, path, interface, method, signature='()', args=(), done=None, failed=None):
        if method == 'FirefoxStatus':
            if done: done(json.dumps(self.status))
        elif method == 'SetFirefoxEnabled':
            self.commands.append((method, args)); self.status['enabled'] = args[0]
            if done: done(())
        elif method == 'RestoreFirefoxContext':
            self.commands.append((method, args))
            if done: done('command-one')


failures = []
win = None
attempts = 0


def rows():
    result = []
    child = win.firefox_contexts.get_first_child()
    while child:
        result.append(child); child = child.get_next_sibling()
    return result


def button(row):
    # PreferencesRow internals vary by libadwaita; walk the real widget tree.
    def walk(widget):
        if isinstance(widget, Gtk.Button): return widget
        child = widget.get_first_child()
        while child:
            found = walk(child)
            if found: return found
            child = child.get_next_sibling()
        return None
    return walk(row)


def capture(name):
    paintable = Gtk.WidgetPaintable.new(win); snapshot = Gtk.Snapshot()
    paintable.snapshot(snapshot, win.get_width(), win.get_height())
    node = snapshot.to_node()
    if node is None:
        return False
    texture = win.get_renderer().render_texture(node, Graphene.Rect().init(0, 0, win.get_width(), win.get_height()))
    texture.save_to_png('/tmp/' + name)
    return True


def checks():
    global attempts
    try:
        attempts += 1
        if not win.get_mapped() and attempts < 80:
            return True
        assert win.get_mapped(), 'Broadway browser did not connect'
        if not capture('unity-firefox-wide.png'):
            assert attempts < 80, 'Broadway did not draw the window'
            return True
        assert win.firefox_switch.get_active() and win.firefox_switch.get_sensitive()
        assert len(rows()) == 3
        assert not rows()[1].get_use_markup()
        assert not button(rows()[2]).get_sensitive()
        assert not win.client.commands, 'Rendering must not issue setters'
        button(rows()[1]).emit('clicked')
        assert win.client.commands[-1] == ('RestoreFirefoxContext', ('profile-one', 'context-one'))
        button(rows()[0]).emit('clicked')
        assert win.client.commands[-1] == ('RestoreFirefoxContext', ('profile-one', ''))
        win.set_default_size(420, 740)
        GLib.timeout_add(250, narrow)
    except Exception:
        failures.append(traceback.format_exc()); app.quit()
    return False


def narrow():
    try:
        capture('unity-firefox-narrow.png')
        win.firefox_switch.set_active(False)
        assert win.client.commands[-1] == ('SetFirefoxEnabled', (False,))
        assert len(rows()) == 1
        win.firefox_switch.set_active(True)
        assert win.client.commands[-1] == ('SetFirefoxEnabled', (True,))
        assert len(rows()) == 3
        count = len(win.client.commands)
        win.error('NotAuthorized: revoked for test')
        assert len(rows()) == 1 and not win.firefox_switch.get_sensitive()
        assert win.firefox_status == {}
        assert len(win.client.commands) == count, 'Revocation cannot dispatch a setter'
        win.authorized = True; win.render_firefox(json.dumps(STATUS))
        win.set_default_size(1140, 780)
        win.nav.select_row(win.pages['settings'])
        GLib.timeout_add(250, finish)
    except Exception:
        failures.append(traceback.format_exc()); app.quit()
    return False


def finish():
    try:
        capture('unity-firefox-settings.png')
    except Exception:
        failures.append(traceback.format_exc())
    app.quit()
    return False


class App(module.App):
    def __init__(self):
        super().__init__(); self.set_application_id('org.unityconnect.Unity.FirefoxTest')
        self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)

    def do_activate(self):
        global win
        win = module.Window(self, client_factory=Client)
        win.authorized = True
        win.render_firefox(json.dumps(STATUS))
        win.nav.select_row(win.pages['firefox']); win.present()
        GLib.timeout_add(250, checks)


app = App(); app.run([])
if failures:
    print('\n'.join(failures), file=sys.stderr); sys.exit(1)
print('Firefox GTK controls, disabled and revoked states, and wide/narrow layouts passed')
