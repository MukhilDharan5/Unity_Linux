#!/usr/bin/env python3
"""Real GTK exports and AppIndicator protocol on an isolated display/session bus."""
import importlib.util
import json
import os
from pathlib import Path
import time
import traceback
import tempfile
settings_dir = tempfile.TemporaryDirectory(prefix='unity-desktop-settings-')
os.environ['XDG_CONFIG_HOME'] = settings_dir.name
import gi

gi.require_version('Gtk', '4.0'); gi.require_version('Adw', '1')
from gi.repository import Gio, GLib, Gtk

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('unity', root / 'unity-gtk/unity.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


def connection():
    return Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
        Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)


def wait_for(predicate, timeout=5):
    deadline = time.monotonic() + timeout
    while not predicate():
        assert time.monotonic() < deadline, 'Timed out waiting for desktop integration'
        GLib.MainContext.default().iteration(False)
        time.sleep(.001)


def call(bus, destination, path, interface, method, signature='()', args=()):
    result, errors = [], []
    def done(source, value):
        try: result.append(source.call_finish(value).unpack())
        except GLib.Error as error: errors.append(error)
    bus.call(destination, path, interface, method, GLib.Variant(signature, args), None,
             Gio.DBusCallFlags.NONE, 5000, None, done)
    wait_for(lambda: result or errors)
    if errors: raise errors[0]
    return result[0]


class Watcher:
    XML = '''<node><interface name="org.kde.StatusNotifierWatcher">
      <property name="IsStatusNotifierHostRegistered" type="b" access="read"/>
      <method name="RegisterStatusNotifierItem"><arg type="s" direction="in"/></method>
      <signal name="StatusNotifierHostRegistered"/>
      <signal name="StatusNotifierHostUnregistered"/>
    </interface></node>'''
    def __init__(self):
        self.bus = connection(); self.items = []; self.host = True
        self.bus.register_object('/StatusNotifierWatcher', Gio.DBusNodeInfo.new_for_xml(self.XML).interfaces[0],
            self.method, lambda *args: GLib.Variant('b', self.host), None)
        self.bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
            GLib.Variant('(su)', ('org.kde.StatusNotifierWatcher', 0)), None, Gio.DBusCallFlags.NONE, 5000, None)
    def method(self, bus, sender, path, interface, method, parameters, invocation):
        self.items.append((sender, parameters.unpack()[0]))
        self.bus.emit_signal(None, '/StatusNotifierWatcher', 'org.freedesktop.DBus.Properties', 'PropertiesChanged',
            GLib.Variant('(sa{sv}as)', ('org.kde.StatusNotifierWatcher',
                {'RegisteredStatusNotifierItems': GLib.Variant('as', [sender])}, [])))
        invocation.return_value(None)
    def set_host(self, available):
        self.host = available
        self.bus.emit_signal(None, '/StatusNotifierWatcher', 'org.freedesktop.DBus.Properties', 'PropertiesChanged',
            GLib.Variant('(sa{sv}as)', ('org.kde.StatusNotifierWatcher',
                {'IsStatusNotifierHostRegistered': GLib.Variant('b', available)}, [])))
    def close(self): self.bus.close_sync(None)


class Client:
    def __init__(self, changed, error): self.commands = []; self.features = []; self.results = {}
    def manager(self, *args, **kwargs): pass
    def call(self, path, interface, method, signature='()', args=(), done=None, failed=None):
        if method == 'Execute':
            self.commands.append((path, args[0], args[1], json.loads(args[2])))
            operation = '/org/unityconnect/operations/o_' + f'{len(self.commands):032x}'
            result = {'state': 'ringing' if args[1] == 'ring' else 'idle'} if args[0] == 'find' else {}
            self.results[operation] = json.dumps({'feature': args[0], 'status': 'completed', 'progress': 100, 'result': result})
            if done: done(operation)
        elif method == 'GetState' and path in self.results and done: done(self.results[path])
        elif method == 'SetFeatureEnabled':
            self.features.append((path, *args))
            if done: done(())


failures = []
watcher = None
remote = connection()


class App(module.App):
    def __init__(self):
        super().__init__(); self.set_application_id('org.unityconnect.Unity.DesktopTest')
        self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)
    def do_activate(self):
        global win
        if self.main_window(): return module.App.do_activate(self)
        win = module.Window(self, client_factory=Client)
        win.path = '/org/unityconnect/devices/d_' + 'a' * 32
        win.devices = {win.path: {'Name': 'Test Phone', 'Connected': True},
                       '/org/unityconnect/devices/d_' + 'b' * 32: {'Name': 'Other Phone', 'Connected': False}}
        win.authorized = True
        win.capabilities = {f: {'status': 'available', 'reason': ''} for f in ('find', 'files', 'sms', 'notifications',
            'screen', 'media', 'desktop_media', 'sound', 'audio', 'urls', 'lock', 'browsing', 'ringer', 'samsung_routines', 'phone_connectivity')}
        win.updating_dropdown = True
        win.device_dropdown.set_model(Gtk.StringList.new(['Test Phone', 'Other Phone']))
        win.device_dropdown.set_selected(0); win.updating_dropdown = False
        win.render({'name': 'Test Phone', 'connected': True, 'state': {'battery': {'level': 73}, 'transport': {'kind': 'both'}}})
        win.present()
        GLib.idle_add(checks)


app = App()


def menu_node(tree, *labels):
    for title in labels:
        tree = next(node for node in tree[2] if node[1].get('label') == title)
    return tree

def press_dialog(dialog, title):
    def descendants(widget):
        yield widget
        child = widget.get_first_child()
        while child:
            yield from descendants(child)
            child = child.get_next_sibling()
    next(widget for widget in descendants(dialog) if isinstance(widget, Gtk.Button) and widget.get_label() == title).emit('clicked')


def expanded_checks(destination, app_path, indicator, click):
    def wire_action(name, value=None):
        call(remote, destination, app_path, 'org.gtk.Actions', 'Activate', '(sava{sv})',
             (name, [] if value is None else [GLib.Variant('s', value)], {}))
    def read_tree():
        app.sync_desktop()
        return call(remote, destination, indicator.MENU_PATH, indicator.MENU_IFACE, 'GetLayout', '(iias)', (0, -1, []))[1]
    def choose(*titles):
        click(menu_node(read_tree(), *titles)[0])
        app.sync_desktop()
    def all_nodes(tree):
        yield tree
        for child in tree[2]: yield from all_nodes(child)
    phone = {'origin': 'phone', 'session': 'ab' * 16, 'title': 'Phone track', 'app_name': 'Phone Player',
             'active': True, 'playing': True, 'duration': 120000, 'position': 1000, 'actions': ['play', 'pause', 'next', 'previous']}
    laptop = dict(phone, origin='desktop', session='cd' * 16, title='Laptop track', app_name='Laptop Player')
    state = {'sound': {'origin': 'phone', 'available': True, 'volume': 60, 'muted': False},
             'desktop_sound': {'origin': 'desktop', 'available': True, 'volume': 50, 'muted': False, 'output': 'ef' * 16},
             'media': dict(phone, players=[phone]), 'desktop_media': {'players': [laptop]},
             'ringer': {'mode': 'normal'}, 'phone_connectivity': {'hotspot_enabled': False, 'bluetooth_enabled': True,
                 'bluetooth_status': 'available', 'devices': [{'address': '11:22:33:44:55:66', 'name': 'Test headphones', 'profiles': ['Audio']}]}}
    before = len(win.client.commands)
    win.render({'name': 'Test Phone', 'connected': True, 'state': state})
    tree = read_tree()
    assert len(win.client.commands) == before, 'Menu and widget updates never send commands'
    assert menu_node(tree, 'Settings', 'Appearance', 'Follow System')[1]['toggle-state'] == 1
    assert menu_node(tree, 'Media and Audio', 'Phone Volume', 'Mute')[1]['toggle-state'] == 0
    # Read all GTK menu groups to compare the actions exported to Orbit with
    # those offered by the real indicator protocol, including typed targets.
    native = set(); seen = set(); pending = {0}
    while pending:
        groups = sorted(pending); seen.update(groups)
        records = call(remote, destination, app_path + '/menus/menubar', 'org.gtk.Menus', 'Start', '(au)', (groups,))[0]
        pending = set()
        for group, index, items in records:
            for item in items:
                if 'action' in item: native.add((item['action'], str(item.get('target', ''))))
                for link in (':submenu', ':section'):
                    if link in item and item[link][0] not in seen: pending.add(item[link][0])
    popup = {('app.' + indicator.nodes[node[0]][2], str(indicator.nodes[node[0]][3] or ''))
             for node in all_nodes(tree) if indicator.nodes[node[0]][2]}
    assert native == popup, (native - popup, popup - native)
    choose('Media and Audio', 'Phone Volume', 'Mute')
    assert win.client.commands[-1][1:] == ('sound', 'mute', {'source': 'phone', 'muted': True})
    assert not app.lookup_action('phone-muted').get_state().unpack(), 'Mute checkmark follows the phone report'
    state['sound']['muted'] = True; win.render_sound(state)
    assert menu_node(read_tree(), 'Media and Audio', 'Phone Volume', 'Mute')[1]['toggle-state'] == 1
    choose('Media and Audio', 'Phone Volume', 'Volume Up (10%)')
    assert win.client.commands[-1][1:] == ('sound', 'volume', {'source': 'phone', 'volume': 70})
    wire_action('laptop-volume-down')
    assert win.client.commands[-1][1:] == ('sound', 'volume', {'source': 'desktop', 'volume': 40, 'output': 'ef' * 16})
    choose('Media and Audio', 'Choose Player', 'Laptop · Laptop Player')
    choose('Media and Audio', 'Next Track')
    assert win.client.commands[-1][1:] == ('desktop_media', 'next', {'session': laptop['session']})
    wire_action('media-play-pause')
    assert win.client.commands[-1][1:] == ('desktop_media', 'pause', {'session': laptop['session']})
    choose('Phone', 'Phone Sound', 'Vibrate')
    assert win.client.commands[-1][1:] == ('ringer', 'set', {'mode': 'vibrate'})
    assert menu_node(read_tree(), 'Phone', 'Phone Sound', 'Ring')[1]['toggle-state'] == 1
    choose('Phone', 'Mobile Hotspot', 'Turn On Hotspot')
    assert win.client.commands[-1][1:] == ('samsung_routines', 'hotspot_on', {})
    assert win.phone_connectivity_state['hotspot_enabled'] is False
    assert not menu_node(read_tree(), 'Phone', 'Mobile Hotspot', 'Turn Off Hotspot')[1]['enabled']
    choose('Phone', 'Disconnect Bluetooth Device', 'Test headphones')
    assert win.client.commands[-1][1:] == ('samsung_routines', 'disconnect_bluetooth_device', {'address': '11:22:33:44:55:66'})
    choose('Media and Audio', 'Audio Streaming', 'Audio Connection', 'Wi-Fi / LAN')
    wait_for(lambda: win.audio_transport.get_selected() == 1)
    assert win.audio_transport.get_selected() == 1
    choose('Media and Audio', 'Audio Streaming', 'Play Phone Audio on Laptop')
    assert win.client.commands[-1][1:] == ('audio', 'start', {'source': 'phone', 'transport': 'lan'}), win.client.commands[-4:]
    win.render_audio_route({'state': 'streaming', 'source': 'phone', 'transport': 'lan'})
    assert not menu_node(read_tree(), 'Media and Audio', 'Audio Streaming', 'Play Laptop Audio on Phone')[1]['enabled']
    assert not app.lookup_action('audio-transport').get_enabled()
    choose('Media and Audio', 'Audio Streaming', 'Stop Audio Streaming')
    assert win.client.commands[-1][1:] == ('audio', 'stop', {})
    win.render_audio_route({'state': 'idle'})
    for mode, control, extend, primary in [('view', False, False, False), ('control', True, False, False),
                                         ('extend', True, True, False), ('primary', True, False, True)]:
        wire_action('screen-start', mode)
        assert win.client.commands[-1][1:] == ('screen', 'start',
            {'source': 'desktop', 'control': control, 'blank_display': primary, 'extend_display': extend})
    choose('Share', 'New Message…')
    assert win.stack.get_visible_child_name() == 'messages' and win.message_split.get_show_content()
    choose('Share', 'Send a Link…')
    dialog = win.get_visible_dialog(); assert dialog is not None
    entry = dialog.get_extra_child()
    entry.set_text('javascript:alert(1)'); assert not dialog.get_response_enabled('send')
    entry.set_text('https://example.com/a?b=c'); assert dialog.get_response_enabled('send')
    press_dialog(dialog, 'Send Link')
    assert win.client.commands[-1][1:] == ('urls', 'open', {'url': 'https://example.com/a?b=c'})
    wait_for(lambda: win.get_visible_dialog() is None)
    choose('Share', 'Send a Link…')
    dialog = win.get_visible_dialog(); dialog.get_extra_child().set_text('https://example.com/stale')
    before = len(win.client.commands); win.generation += 1; press_dialog(dialog, 'Send Link')
    assert len(win.client.commands) == before, 'A link dialog cannot send to a changed/disconnected phone'
    wait_for(lambda: win.get_visible_dialog() is None)
    choose('Settings', 'Appearance', 'Dark')
    assert win.appearance_combo.get_selected() == 2 and app.preferences['appearance'] == 'dark'
    win.appearance_combo.set_selected(0); app.sync_desktop()
    assert app.lookup_action('appearance').get_state().unpack() == 'system'
    choose('Settings', 'Keep Running When Window Closes')
    assert not app.keep_in_background(win) and not win.background_switch.get_active()
    choose('Settings', 'Keep Running When Window Closes')
    choose('Settings', 'Show Phone Status in Indicator')
    assert not app.preferences['show_status'] and not win.indicator_status_switch.get_active()
    assert all(node[1].get('label') != 'Test Phone' for node in read_tree()[2])
    choose('Settings', 'Show Phone Status in Indicator')
    win.capabilities['clipboard'] = {'status': 'disabled', 'reason': 'Disabled for this phone'}
    win.render_capabilities(); app.sync_desktop()
    choose('Settings', 'Phone Features', 'Automatic Clipboard Sync')
    assert win.client.features[-1] == (win.path, 'clipboard', True)
    action_name = app.feature_actions['clipboard']
    assert not app.lookup_action(action_name).get_state().unpack(), 'Feature checkmarks wait for daemon state'
    win.capabilities['clipboard']['status'] = 'available'; win.render_capabilities(); app.sync_desktop()
    assert app.lookup_action(action_name).get_state().unpack()
    call(remote, destination, app_path, 'org.gtk.Actions', 'SetState', '(sva{sv})',
         (action_name, GLib.Variant('b', False), {}))
    assert win.client.features[-1] == (win.path, 'clipboard', False)
    saved = json.loads(app.preference_path.read_text())
    assert saved == {'keep_running': True, 'show_status': True, 'appearance': 'system', 'audio_transport': 'lan'}
    app.preferences = dict(app.DEFAULT_PREFERENCES); app.load_preferences(); app.sync_desktop()
    assert app.preferences == saved, 'Desktop settings survive a new load'


def checks():
    global watcher
    try:
        indicator = app.indicator
        assert indicator and not indicator.ready
        assert app.get_menubar().get_n_items() == 7
        assert win.app_menu.get_popover().get_menu_model() == app.get_menubar()
        # Verify the same real wire exports Orbit consumes, rather than just
        # inspecting Python menu objects.
        destination = app.get_dbus_connection().get_unique_name()
        app_path = app.get_dbus_object_path()
        layout = call(remote, destination, app_path + '/menus/menubar', 'org.gtk.Menus', 'Start', '(au)', ([0],))
        assert layout[0], 'GTK must export the menubar over D-Bus'
        actions = call(remote, destination, app_path, 'org.gtk.Actions', 'List')[0]
        assert all(name in actions for name in ('find-phone', 'device', 'page-messages', 'send-files', 'quit'))
        app.sync_desktop()
        assert app.lookup_action('find-phone').get_enabled()
        assert not app.lookup_action('stop-screen').get_enabled()
        described = call(remote, destination, app_path, 'org.gtk.Actions', 'Describe', '(s)', ('find-phone',))
        assert described[0][0] is True
        assert not app.keep_in_background(win), 'Without a host, closing must exit normally'
        assert win.close_main_call_window() is False
        # A host can appear after Unity starts.
        watcher = Watcher()
        wait_for(lambda: indicator.ready)
        assert watcher.items[-1] == (destination, indicator.ITEM_PATH)
        assert len(watcher.items) == 1, 'Item-list changes must not register recursively'
        item_props = call(remote, destination, indicator.ITEM_PATH, 'org.freedesktop.DBus.Properties',
                          'GetAll', '(s)', (indicator.ITEM_IFACE,))[0]
        assert item_props['ItemIsMenu'] is True and item_props['Menu'] == indicator.MENU_PATH
        assert item_props['IconName'] == 'org.unityconnect.Unity-symbolic'
        revision, tree = call(remote, destination, indicator.MENU_PATH, indicator.MENU_IFACE,
                              'GetLayout', '(iias)', (0, -1, []))
        assert revision > 0 and tree[0] == 0
        entries = {node[1].get('label'): node for node in tree[2] if node[1].get('label')}
        assert 'Test Phone' in entries and 'LAN + Bluetooth · 73%' in entries
        assert all(title in entries for title in ('Open Unity', 'Find Phone', 'Send Files…', 'Share', 'Media and Audio', 'Settings', 'Quit Unity'))
        phone_menu = menu_node(tree, 'Phone', 'Selected Phone')
        assert len(phone_menu[2]) == 2 and phone_menu[2][0][1]['toggle-state'] == 1
        find_id = entries['Find Phone'][0]
        def click(ident):
            call(remote, destination, indicator.MENU_PATH, indicator.MENU_IFACE, 'Event',
                 '(isvu)', (ident, 'clicked', GLib.Variant('i', 0), 0))
        click(find_id)
        assert win.client.commands[-1] == (win.path, 'find', 'ring', {})
        call(remote, destination, app_path, 'org.gtk.Actions', 'Activate', '(sava{sv})', ('find-phone', [], {}))
        assert win.client.commands[-1] == (win.path, 'find', 'stop', {})
        win.render_find_phone({'state': 'ringing'}); app.sync_desktop()
        _, tree = call(remote, destination, indicator.MENU_PATH, indicator.MENU_IFACE, 'GetLayout', '(iias)', (0, -1, []))
        assert any(node[1].get('label') == 'Stop Ringing' for node in tree[2])
        click(find_id)
        assert win.client.commands[-1][2] == 'stop', 'IDs remain stable during live state changes'
        win.close(); assert not win.get_visible() and win in app.get_windows()
        click(menu_node(tree, 'Open Page', 'Messages')[0])
        assert win.get_visible() and win.stack.get_visible_child_name() == 'messages'
        win.close(); click(entries['Open Unity'][0]); assert win.get_visible()
        choices = []; win.choose_file = lambda: choices.append(True)
        click(entries['Send Files…'][0])
        assert choices == [True] and win.stack.get_visible_child_name() == 'files'
        win.render_screen({'state': 'streaming', 'source': 'desktop'}); win.render_capabilities(); app.sync_desktop()
        assert app.lookup_action('stop-screen').get_enabled()
        app.activate_action('stop-screen', None)
        assert win.client.commands[-1] == (win.path, 'screen', 'stop', {})
        expanded_checks(destination, app_path, indicator, click)
        # Revocation updates both action transports and blocks a stale click.
        win.authorized = False; win.render({}); app.sync_desktop()
        before = len(win.client.commands)
        click(find_id); app.activate_action('find-phone', None)
        assert len(win.client.commands) == before and not app.lookup_action('find-phone').get_enabled()
        described = call(remote, destination, app_path, 'org.gtk.Actions', 'Describe', '(s)', ('find-phone',))
        assert described[0][0] is False
        props = call(remote, destination, indicator.MENU_PATH, indicator.MENU_IFACE,
                     'GetGroupProperties', '(aias)', ([find_id], ['enabled']))[0]
        assert props[0][1]['enabled'] is False
        # Disconnect blocks actions, and selecting another phone invalidates IDs
        # from the previous phone's popup.
        win.authorized = True; win.devices[win.path]['Connected'] = False
        win.render_capabilities(); app.sync_desktop(); click(find_id)
        assert len(win.client.commands) == before
        win.devices[win.path]['Connected'] = True; win.render_capabilities(); app.sync_desktop()
        other = phone_menu[2][1][0]; click(other); app.sync_desktop()
        assert win.path.endswith('b' * 32)
        click(find_id); assert len(win.client.commands) == before
        errors, = call(remote, destination, indicator.MENU_PATH, indicator.MENU_IFACE, 'EventGroup',
                       '(a(isvu))', ([(find_id, 'clicked', GLib.Variant('i', 0), 0)],))
        assert errors == [find_id]
        # Host disappearance restores a hidden regular window, and restarts
        # register the same item again without duplicates or an idle timer.
        win.close(); assert not win.get_visible()
        watcher.close(); watcher = None
        wait_for(lambda: not indicator.ready)
        assert win.get_visible()
        watcher = Watcher(); wait_for(lambda: indicator.ready)
        assert len(watcher.items) == 1
        win.close(); watcher.set_host(False)
        wait_for(lambda: not indicator.ready)
        assert win.get_visible()
        watcher.set_host(True); wait_for(lambda: indicator.ready)
        assert len(watcher.items) == 2
        win.popup_only = True
        assert not app.keep_in_background(win), 'Call-only launches retain their existing lifetime'
        win.popup_only = False
        # Verify actual Quit action and cleanup while backgrounded.
        win.close(); assert not win.get_visible()
        app.activate_action('quit', None)
    except Exception:
        failures.append(traceback.format_exc()); app.quit()
    return False


app.run([])
if watcher: watcher.close()
remote.close_sync(None)
assert app.indicator.closed
settings_dir.cleanup()
if failures: raise AssertionError('\n'.join(failures))
print('GTK global-menu exports, AppIndicator actions, permissions, host restart and background lifetime passed')
