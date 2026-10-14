#!/usr/bin/env python3
"""Real GTK drop signals and public transfer calls, with an isolated client."""
import importlib.util
import json
from pathlib import Path
import tempfile
import traceback

import gi
gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
from gi.repository import Adw, Gdk, Gio, Gtk, GLib

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('unity', root / 'unity-gtk/unity.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Client:
    def __init__(self, changed, error):
        self.calls, self.responses = [], {}
        self.fail = self.defer = False
        self.pending = None

    def manager(self, *args, **kwargs):
        pass

    def call(self, path, interface, method, signature='()', args=(), done=None, failed=None):
        self.calls.append((interface, method, args))
        if method == 'SendFile':
            assert interface == 'org.unityconnect.Transfer1'
            if self.fail:
                failed('Test receiver could not save file')
                return
            op = f'/org/unityconnect/operations/o_{len(self.calls):032x}'
            self.responses[op] = json.dumps({'feature': 'files', 'status': 'completed', 'progress': 100,
                'result': {'name': Path(args[0]).name, 'location': 'Downloads/Unity'}})
            if self.defer:
                self.pending = lambda: done(op)
            else:
                done(op)
        elif method == 'GetState' and path in self.responses:
            done(self.responses[path])


class App(module.App):
    def __init__(self):
        super().__init__()
        self.set_application_id('org.unityconnect.Unity.FileTest')
        self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)

    def do_activate(self):
        try:
            verify(self)
        except Exception:
            traceback.print_exc()
            failures.append(True)
        self.quit()


def drop(window, files):
    controllers = window.observe_controllers()
    target = next(controllers.get_item(i) for i in range(controllers.get_n_items())
                  if isinstance(controllers.get_item(i), Gtk.DropTarget))
    assert target.get_propagation_phase() == Gtk.PropagationPhase.CAPTURE
    return target.emit('drop', Gdk.FileList.new_from_array(files), 5., 5.)


def verify(app):
    win = module.Window(app, client_factory=Client)
    with tempfile.TemporaryDirectory(prefix='unity-drop-test-') as directory:
        folder = Path(directory)
        files = [folder / name for name in ('space name.txt', 'தமிழ்.pdf', 'binary.unknown', 'empty')]
        for i, path in enumerate(files):
            path.write_bytes(bytes([i]) * i)
        selected = [Gio.File.new_for_path(str(path)) for path in files]
        # Files remain selectable without a connection or client grant.
        assert drop(win, selected)
        assert win.stack.get_visible_child_name() == 'files'
        assert win.pending_files == [str(p) for p in files]
        assert not win.file_send_button.get_sensitive()
        assert not any(method == 'SendFile' for _, method, _ in win.client.calls)
        win.path = '/org/unityconnect/devices/d_' + 'a' * 32
        win.devices[win.path] = {'Connected': True, 'Name': 'Test phone'}
        win.authorized = True
        win.capabilities = {'files': {'id': 'files', 'status': 'available', 'reason': ''}}
        win.render_capabilities()
        assert win.file_send_button.get_sensitive()
        # A drop from every page selects Files without duplicating or sending.
        for row in win.pages.values():
            win.nav.select_row(row)
            assert drop(win, selected)
            assert win.stack.get_visible_child_name() == 'files'
            assert len(win.pending_files) == len(files)
        viewer = Adw.Window(application=app, transient_for=win)
        assert drop(viewer, selected)
        viewer.close()
        link = folder / 'link.txt'
        link.symlink_to(files[0])
        assert win.select_files([Gio.File.new_for_path(str(link))])
        assert len(win.pending_files) == len(files)
        assert not win.select_files([Gio.File.new_for_path(str(folder)), Gio.File.new_for_uri('https://example.test/file')])
        large = folder / 'large.bin'
        with large.open('wb') as stream:
            stream.truncate(module.MAX_FILE + 1)
        assert not win.select_files([Gio.File.new_for_path(str(large))])
        # The receiver must acknowledge each file before the queue advances.
        win.client.fail = True
        win.send_selected_files()
        assert not win.file_sending and len(win.pending_files) == len(files)
        assert 'could not save' in win.file_send_row.get_subtitle()
        win.client.fail = False
        win.send_selected_files()
        assert not win.file_sending and not win.pending_files
        sent = [args[0] for _, method, args in win.client.calls if method == 'SendFile']
        assert sent == [str(files[0])] + [str(p) for p in files]
        assert win.file_status.get_title() == 'File sent'
        # Disconnects preserve the unacknowledged selection for an explicit retry.
        win.select_files(selected)
        win.client.defer = True
        win.send_selected_files()
        win.devices[win.path]['Connected'] = False
        win.render({})
        win.client.pending()
        assert not win.file_sending and len(win.pending_files) == len(files)
        assert not win.file_send_button.get_sensitive()
        win.devices[win.path]['Connected'] = True
        win.capabilities['files']['status'] = 'disabled'
        win.render_capabilities()
        assert win.pages['files'].get_visible() and not win.file_send_button.get_sensitive()
        assert len(win.pending_files) == len(files)
    win.close()


failures = []
App().run([])
if failures:
    raise SystemExit(1)
print('File drops across all pages/windows, selection, retries, queue and disconnect checks passed')
