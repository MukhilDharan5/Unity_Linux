#!/usr/bin/env python3
"""Render real GTK widgets with explicit test fixtures, isolated from phone data."""
import base64
import importlib.util
import json
from pathlib import Path
import sys
import traceback
import time
import gi
for name, version in [('Gtk', '4.0'), ('Adw', '1'), ('Gsk', '4.0')]: gi.require_version(name, version)
from gi.repository import Adw, Gio, Gtk, GLib, Graphene
root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('unity', root / 'unity-gtk/unity.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
preview = {'mime': 'image/jpeg', 'width': 256, 'height': 192,
           'data': base64.b64encode((root / 'tests/fixtures/preview.jpg').read_bytes()).decode()}
photo_items = [{'id': f'{n:032x}', 'name': f'Photo {n + 1}.jpg', 'size': 2480200,
                'timestamp': 1791800100000 - n * 86000000, 'width': 4000, 'height': 3000} for n in range(8)]

class FixtureClient:
    def __init__(self, changed, error):
        self.calls, self.responses = [], {}
    def manager(self, *args, **kwargs):
        self.calls.append((args[0], args[2] if len(args) > 2 else ()))
    def call(self, path, interface, method, signature='()', args=(), done=None, failed=None):
        self.calls.append((method, args))
        if method == 'Execute':
            feature, action, payload = args; payload = json.loads(payload)
            op = f'/org/unityconnect/operations/o_{len(self.calls):032x}'
            result = {'id': payload.get('id'), 'preview': preview} if action == 'preview' else {'items': photo_items, 'preview_supported': True}
            if action == 'audio_devices':
                result = {'devices': [{'address': 'AA:BB:CC:DD:EE:FF', 'name': 'Test phone'}],
                          'outputs': [{'id': 'bluetooth:11:22:33:44:55:66', 'name': 'Test headphones'}],
                          'microphones': [{'id': 'laptop_microphone', 'name': 'Laptop microphone'},
                                          {'id': 'bluetooth:11:22:33:44:55:66', 'name': 'Test headphones'}]}
            elif action == 'audio_select': result = {'state': 'active', **payload}
            self.responses[op] = json.dumps({'feature': feature, 'status': 'completed', 'progress': 100, 'result': result})
            if done: GLib.idle_add(lambda: (done(op), False)[1])
        elif method == 'GetFrame':
            GLib.idle_add(lambda: (done((1, 256, 192, (root / 'tests/fixtures/preview.jpg').read_bytes())), False)[1])
        elif method == 'GetState' and path in self.responses:
            GLib.idle_add(lambda: (done(self.responses[path]), False)[1])

class TestApp(module.App):
    def __init__(self):
        super().__init__()
        self.set_application_id('org.unityconnect.Unity.VisualTest')
        self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)

    def do_activate(self):
        activated(self)


app = TestApp()
failures = []
win = None
cases = [('calls', 1140, 820, False), ('calls', 420, 740, False), ('overview', 1480, 900, False), ('notifications', 1140, 820, False),
         ('messages', 1140, 820, False), ('media', 1140, 820, False), ('photos', 1140, 820, False),
         ('messages', 420, 740, False), ('media', 420, 740, False),
         ('photos', 420, 740, False), ('notifications', 420, 740, False), ('remote', 420, 740, False), ('remote', 1140, 820, False), ('photos', 1140, 820, True),
         ('files', 1140, 820, False), ('files', 420, 740, False), ('overview', 420, 740, False)]
index = 0

def capture():
    global index
    try:
        name, width, height, dark = cases[index]
        assert len(win.pages) == 9
        assert win.ringer_combo.is_ancestor(win.stack.get_child_by_name('media'))
        assert win.hotspot_button.is_ancestor(win.stack.get_child_by_name('media'))
        assert not win.ringer_combo.is_ancestor(win.stack.get_child_by_name('remote'))
        assert win.pages['messages'].page_title == 'Messages'
        assert win.pages['photos'].page_title == 'Photos'
        assert win.call_output.is_ancestor(win.stack.get_child_by_name('calls'))
        assert win.call_microphone.is_ancestor(win.stack.get_child_by_name('calls'))
        sp = Adw.LengthUnit.to_px(Adw.LengthUnit.SP, 1, Gtk.Settings.get_default())
        assert win.message_split.get_collapsed() == (win.get_width() <= 1080 * sp), 'Conversation breakpoint'
        assert win.split.get_collapsed() == (win.get_width() <= 760 * sp), 'Main breakpoint'
        assert win.art_picture.get_paintable() is not None
        assert sum(1 for _ in win.photo_widgets.values()) == 8
        assert all(w['texture'] is not None for w in win.photo_widgets.values())
        assert win.preview_inflight <= 2
        paintable = Gtk.WidgetPaintable.new(win); snapshot = Gtk.Snapshot()
        paintable.snapshot(snapshot, win.get_width(), win.get_height())
        texture = win.get_renderer().render_texture(snapshot.to_node(), Graphene.Rect().init(0, 0, win.get_width(), win.get_height()))
        out = Path(f'/tmp/unity-refined-{name}-{width}{"-dark" if dark else ""}.png')
        texture.save_to_png(str(out))
        print(f'Rendered {name}: {win.get_width()}×{win.get_height()} to {out}', flush=True)
    except Exception as e:
        failures.append(e); traceback.print_exc()
    index += 1
    if index < len(cases):
        GLib.idle_add(next_case)
    else:
        # Sensitive views are cleared on disconnect, and previews use two bounded requests.
        win.render({})
        assert not win.photo_items and not win.message_data
        assert win.art_picture.get_paintable() is None
        assert not win.battery_power.get_visible() and not win.battery_time.get_visible()
        assert win.battery_power.get_label() == win.battery_time.get_label() == ''
        app.activate_action('quit', None)
    return False

def next_case():
    name, width, height, dark = cases[index]
    win.set_default_size(width, height)
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK if dark else Adw.ColorScheme.FORCE_LIGHT)
    win.nav.select_row(win.pages[name])
    if name == 'photos' and index == 6:
        win.preview_queue = list(win.photo_items)
        win.pump_previews()
        assert win.preview_inflight == 2
    if name == 'messages':
        win.selected_address = '+1 555 0100'
        win.render_conversation()
        win.message_split.set_show_content(True)
    win.present()
    GLib.timeout_add(700, capture)
    return False

def activated(application):
    global win
    assert application.has_action('quit')
    assert application.get_accels_for_action('app.quit') == ['<Control>q']
    win = module.Window(application, client_factory=FixtureClient)
    win.path = '/org/unityconnect/devices/d_' + 'a' * 32
    win.devices[win.path] = {'Connected': True, 'Name': 'Test phone'}
    # Approval is checked before any private snapshot. Raw authorization errors
    # become an actionable in-app state, with sensitive controls disabled.
    win.properties(win.path, {'Connected': True, 'Name': 'Test phone', 'CapabilitiesJson': '[]'})
    assert not any(method == 'GetState' for method, _ in win.client.calls)
    win.bus_name = ':1.test'
    win.error('GDBus.Error:org.unityconnect.Error.NotAuthorized: private test error')
    assert win.banner.get_title() == 'Allow Unity to access your phone.'
    assert win.banner.get_button_label() == 'Allow access'
    assert 'GDBus' not in win.access_row.get_subtitle()
    assert not win.discovery_button.get_sensitive()
    win.access_status(json.dumps({'approved': True, 'scopes': ['read', 'control']}))
    assert win.authorized and not win.access_button.get_visible()
    win.properties(win.path, {'Connected': True, 'Name': 'Test phone', 'CapabilitiesJson': '[]'})
    assert any(method == 'GetState' for method, _ in win.client.calls)
    win.access_status(json.dumps({'approved': False, 'scopes': ['read', 'control']}))
    assert not win.authorized and not win.discovery_button.get_sensitive()
    win.authorized = True
    win.updating_dropdown = True
    win.device_dropdown.set_model(Gtk.StringList.new(['Test phone']))
    win.updating_dropdown = False
    win.capabilities = {f: {'id': f, 'status': 'available', 'reason': ''} for f in
        ('notifications','notification_actions','sms','call_notifications','call_control','files','photos','browsing','media','audio','find','urls','clipboard','remote_input','ringer','hotspot','lock','screen')}
    win.render_audio_route({'state': 'streaming'})
    assert win.audio_route.get_active() and win.audio_route.get_sensitive()
    win.authorized = False
    win.render_audio_route({})
    assert not win.audio_route.get_active() and not win.audio_route.get_sensitive()
    win.authorized = True
    win.messages_loaded = win.photos_loaded = True
    win.render_battery({'level': 50, 'charging': False})
    assert 'Update the Android companion' in win.battery_time.get_label()
    win.render_battery({'level': 50, 'charging': True, 'status': 'charging', 'power_w': -4.,
                        'remaining_seconds': 4500, 'estimate_kind': 'full', 'estimate_state': 'available', 'estimate_source': 'android'})
    assert win.battery_power.get_label() == 'Charging · 4.00 W'
    assert win.battery_time.get_label() == 'Estimated · 1 h 15 min to full'
    win.render_battery({'level': 50, 'charging': False, 'power_w': None, 'remaining_seconds': None, 'estimate_state': 'collecting'})
    assert win.battery_power.get_label() == 'Power not reported by phone'
    assert win.battery_time.get_label() == 'Estimating time remaining…'
    win.render({'connected': True, 'name': 'Test phone', 'state': {
        'transport': {'kind': 'both', 'links': ['bluetooth', 'lan'], 'control': 'bluetooth', 'bulk': 'lan'},
        'battery': {'level': 76, 'charging': False, 'status': 'discharging', 'plugged': False, 'power_w': 1.9,
                    'remaining_seconds': 21600, 'estimate_kind': 'empty', 'estimate_state': 'available', 'estimate_source': 'charge_counter'},
        'network': {'kind': 'wifi', 'operator': 'Example network', 'signal': 3},
        'call_control': {'backend': 'bluetooth_hfp', 'address': 'AA:BB:CC:DD:EE:FF', 'calls':[{'id':'ab'*16,'state':'active','name':'Alex','number':'+15550100','connect_time':0,'can_hold':True,'conference':False}], 'muted':False,'route':'phone','routes':['phone','speaker','bluetooth']},
        'call_audio': {'state': 'waiting', 'address': 'AA:BB:CC:DD:EE:FF', 'output': 'bluetooth:11:22:33:44:55:66',
                       'microphone': 'laptop_microphone', 'muted': True, 'error': 'Waiting for the selected call output'},
        'notifications': {
            'one': {'app_name': 'Messages', 'title': 'Alex', 'text': 'Are we still meeting this afternoon?', 'timestamp': 1791800100000,
                    'actions': [{'id': '0', 'title': 'Reply', 'reply': True}]},
            'two': {'app_name': 'Calendar', 'title': 'Design review', 'text': 'Starts in 15 minutes · Project studio', 'timestamp': 1791800000000,
                    'actions': [{'id': '0', 'title': 'Snooze', 'reply': False}, {'id': '1', 'title': 'Open', 'reply': False}]},
        }, 'media': {'title': 'Evening Light', 'artist': 'The Coastline', 'album': 'A place to return', 'app_name': 'Music', 'active': True,
                      'playing': True, 'duration': 241000, 'position': 84000, 'artwork': preview, 'actions': ['play','pause','next','previous','seek']}}})
    assert 'Bluetooth + Wi-Fi / LAN' in win.connection_detail.get_label()
    assert win.call_phone_button.get_visible() and win.call_uses_hfp
    assert win.call_audio_active and win.call_audio_muted
    assert win.call_audio_button.get_label() == 'Stop computer audio'
    assert win.call_audio_mute.get_label() == 'Unmute microphone'
    assert win.call_audio_row.get_title() == 'Waiting for call audio'
    assert win.call_audio_selection['microphone'] == 'laptop_microphone'
    # Rebuilding selector models must never submit a routing operation.
    assert not any(method == 'Execute' and args[:2] == ('call_control', 'audio_select') for method,args in win.client.calls)
    assert 'Controls over Bluetooth' in win.connection_detail.get_label()
    assert win.battery_power.get_label() == 'Battery draw · 1.90 W'
    assert win.battery_time.get_label() == 'Estimated · 6 h 0 min left'
    win.show_messages({'messages': [
        {'id': '1', 'address': '+1 555 0100', 'body': 'Are we still meeting this afternoon?', 'direction': 'incoming', 'timestamp': 1791800100000},
        {'id': '2', 'address': '+1 555 0100', 'body': 'Yes! I’ll be there at four.', 'direction': 'outgoing', 'timestamp': 1791800300000},
        {'id': '3', 'address': 'Service updates', 'body': 'Your appointment has been confirmed.\nநன்றி!', 'timestamp': 1791700000000}]})
    # An unrelated notification must not replace the card containing a reply draft.
    card = win.notification_cards['one'][1]
    notifications = dict(win.last_notifications)
    notifications['extra'] = {'app_name': 'Test', 'title': 'Another notification', 'text': 'Test only', 'actions': []}
    win.render_notifications(notifications)
    assert win.notification_cards['one'][1] is card
    notifications.pop('extra')
    win.render_notifications(notifications)
    # Older companions must not silently download full images as previews.
    win.show_photos({'items': photo_items})
    assert not win.preview_queue
    assert 'Update the Android companion' in win.photo_status.get_label()
    win.show_photos({'items': photo_items, 'preview_supported': True})
    # Complete previews ahead of capture without introducing production fixture data.
    for item in photo_items:
        win.show_preview(item['id'], {'id': item['id'], 'preview': preview})
    win.preview_queue.clear()
    win.render_capabilities()
    win.select_files([Gio.File.new_for_path(str(root / 'tests/fixtures/hello.json')),
                      Gio.File.new_for_path(str(root / 'tests/fixtures/preview.jpg'))])
    assert win.lock_phone_button.get_sensitive()
    assert win.share_desktop_button.get_sensitive()
    assert win.screen_control_switch.get_active()
    win.screen_blank_switch.set_active(True)
    win.start_screen('desktop')
    assert any(method == 'Execute' and args[:2] == ('screen', 'start') and json.loads(args[2]) == {'source':'desktop','control':True,'blank_display':True} for method,args in win.client.calls)
    win.render_screen({'source':'desktop','state':'streaming','session_id':'ab'*16,'input_enabled':True,'display_available':True,'display_on':False})
    assert win.laptop_display_button.get_sensitive() and win.laptop_display_button.get_label() == 'Turn on'
    win.toggle_laptop_display()
    assert any(method == 'Execute' and args[:2] == ('screen', 'display') and json.loads(args[2]) == {'session_id':'ab'*16,'on':True} for method,args in win.client.calls)
    win.render_screen({})
    win.screen_blank_switch.set_active(False)
    win.screen_control_switch.set_active(False)
    assert not win.screen_blank_switch.get_sensitive()
    win.screen_control_switch.set_active(True)
    win.start_screen('phone')
    assert win.screen_viewer is not None
    frame_deadline = time.monotonic() + 5
    def check_viewer():
        if win.screen_picture.get_paintable() is None and time.monotonic() < frame_deadline:
            return True
        if win.screen_picture.get_paintable() is None:
            failures.append(AssertionError('Screen fixture did not render within five seconds'))
            app.activate_action('quit', None)
            return False
        win.authorized = False
        win.render_screen({})
        assert win.screen_picture.get_paintable() is None
        win.authorized = True
        win.screen_viewer.close()
        next_case()
        return False
    GLib.timeout_add(100, check_viewer)
    assert any(method == 'Execute' and args[:2] == ('screen', 'start') for method, args in win.client.calls)

app.run([])
if failures: raise SystemExit(1)
print('GTK layout, artwork, gallery, message separation, and disconnect checks passed')
