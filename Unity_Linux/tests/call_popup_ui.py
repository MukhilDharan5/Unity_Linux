#!/usr/bin/env python3
"""Exercise the real shared call UI on a virtual desktop, without phone data."""
import importlib.util
import json
from pathlib import Path
import time
import traceback
import faulthandler
import gi
gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
from gi.repository import Adw, Gio, Gtk, GLib, Graphene

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('unity', ROOT / 'unity-gtk/unity.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
faulthandler.dump_traceback_later(25)

class Client:
    def __init__(self, changed, error):
        self.commands, self.operations = [], {}
        self.state_replies, self.properties_replies = [], []
    def manager(self, *args, **kwargs):
        pass
    def call(self, path, interface, method, signature='()', args=(), done=None, failed=None):
        if method == 'Execute':
            feature, action, payload = args; payload = json.loads(payload)
            self.commands.append((action, payload))
            operation = f'/org/unityconnect/operations/o_{len(self.commands):032x}'
            result = {'state': 'requested'}
            if action == 'audio_devices':
                result = {'devices': [{'address': 'AA:BB:CC:DD:EE:FF', 'name': 'Test phone'}],
                          'outputs': [{'id': 'laptop_speakers', 'name': 'Laptop speakers'}, {'id': 'bluetooth:11:22:33:44:55:66', 'name': 'Bluetooth headphones'}],
                          'microphones': [{'id': 'laptop_microphone', 'name': 'Laptop microphone'}]}
            elif action == 'audio_select': result.update(payload)
            self.operations[operation] = json.dumps({'feature': feature, 'status': 'completed', 'progress': 100, 'result': result})
            GLib.idle_add(lambda: (done(operation), False)[1])
        elif method == 'GetState' and path in self.operations:
            GLib.idle_add(lambda: (done(self.operations[path]), False)[1])
        elif method == 'GetState':
            self.state_replies.append(done)
        elif method == 'GetAll':
            self.properties_replies.append(done)

def widgets(widget):
    yield widget
    child = widget.get_first_child()
    while child:
        yield from widgets(child)
        child = child.get_next_sibling()

def button(widget, title):
    return next(w for w in widgets(widget) if isinstance(w, Gtk.Button) and w.get_label() == title)

def capture(window, name):
    window.get_child().allocate(window.get_width(), window.get_height(), -1, None)
    paintable = Gtk.WidgetPaintable.new(window); snapshot = Gtk.Snapshot()
    paintable.snapshot(snapshot, window.get_width(), window.get_height())
    node = snapshot.to_node()
    if node is None:
        # A covered GNOME window or Broadway may have no native frame cache.
        snapshot = Gtk.Snapshot(); window.snapshot_child(window.get_child(), snapshot); node = snapshot.to_node()
    assert node is not None, 'Call controls did not render'
    texture = window.get_renderer().render_texture(node, Graphene.Rect().init(0, 0, window.get_width(), window.get_height()))
    output = Path('/tmp') / name; texture.save_to_png(str(output)); print(output, flush=True)

class App(module.App):
    def __init__(self):
        super().__init__(); self.set_application_id('org.unityconnect.Unity.CallTest')
        self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)
    def do_activate(self):
        global win
        win = module.Window(self, client_factory=Client)
        win.path = '/org/unityconnect/devices/d_' + 'a' * 32
        win.devices[win.path] = {'Name': 'Test phone', 'Connected': True}
        win.authorized = True
        win.capabilities = {'call_control': {'status': 'available', 'reason': ''}}
        win.present()
        GLib.timeout_add(100, advance)

app = App(); win = None; failures = []
call = {'id': 'ab' * 16, 'state': 'active', 'number': '+15550100', 'name': 'Alex',
        'connect_time': int(time.time() * 1000) - 125000, 'can_hold': True, 'conference': False}
control = {'backend': 'android_telecom', 'calls': [call], 'muted': False, 'route': 'phone', 'routes': ['phone', 'speaker', 'bluetooth', 'wired']}
audio = {}

def render():
    win.render(json.loads(json.dumps({'name': 'Test phone', 'connected': True, 'state': {'call_control': control, 'call_audio': audio}})))

def checks():
    assert module.call_duration(3661) == '1:01:01'
    render(); yield
    assert win.call_popup and win.call_popup.get_visible()
    assert win.call_timer_source is not None
    popup = win.call_popup_panel
    assert popup.timers[0][0].get_label().startswith('02:')
    assert win.active_calls.timers[0][0].get_label() == popup.timers[0][0].get_label()
    stable_window = win.call_popup
    clock = dict(win.call_clock)
    # A one-second empty update must clear controls without destroying the window.
    control['calls'] = []; render(); yield
    assert win.call_popup is stable_window and win.call_popup.get_visible()
    assert not popup.get_visible() and not popup.timers
    assert win.call_popup_status.get_visible()
    for _ in range(9): yield
    control['calls'] = [call]; render(); yield
    assert win.call_popup is stable_window and not win.call_popup_status.get_visible()
    assert win.call_clock == clock, 'A transient update must not reset the timer'
    # Older GetAll/GetState replies cannot erase a newer live call snapshot.
    props = {'Name': 'Test phone', 'Connected': True,
             'CapabilitiesJson': json.dumps([dict(id=key, **value) for key, value in win.capabilities.items()])}
    win.listed([win.path]); win.listed([win.path])
    old_props, new_props = win.client.properties_replies[-2:]
    new_props(props); old_props(dict(props, Connected=False))
    assert win.devices[win.path]['Connected'] and win.call_popup is stable_window
    win.properties(win.path, props)
    old_state, new_state = win.client.state_replies[-2:]
    snapshot = {'name': 'Test phone', 'connected': True, 'state': {'call_control': control}}
    new_state(json.dumps(snapshot))
    old_state(json.dumps(dict(snapshot, state={'call_control': dict(control, calls=[])})))
    assert win.call_control_state['calls'] and win.call_popup is stable_window
    clock = win.call_clock[(win.path,call['id'])]
    control.update(backend='bluetooth_hfp',address='AA:BB:CC:DD:EE:FF',reconnecting=True,error='Reconnecting Bluetooth call controls…')
    render(); yield
    assert not button(popup,'End call').get_sensitive()
    assert not button(popup,'Hold').get_sensitive()
    assert not popup.route_button.get_sensitive()
    assert not popup.mute_button.get_sensitive()
    for _ in range(35): yield
    assert win.call_popup is stable_window and win.call_popup_status.get_visible()
    assert win.call_popup_empty_source is None
    call.update(id='ac'*16,connect_time=int(time.time()*1000))
    control.update(reconnecting=False,error=''); render(); yield
    assert win.call_popup is stable_window and not win.call_popup_status.get_visible()
    assert win.call_clock[(win.path,call['id'])][1:] == clock[1:]
    assert button(popup,'End call').get_sensitive()
    assert button(popup,'Hold').get_sensitive()
    control.update(backend='android_telecom'); render()
    yield
    capture(win.call_popup, 'unity-call-popup-active.png')
    win.nav.select_row(win.pages['calls']); yield
    # Completion/state renders must not perpetually request audio_devices and
    # fill the history, which would also block laptop audio start/stop controls.
    count = sum(action == 'audio_devices' for action, _ in win.client.commands)
    for _ in range(5):
        win.load_visible(); yield
    assert sum(action == 'audio_devices' for action, _ in win.client.commands) == count
    win.refresh_call_audio_devices(); yield
    assert sum(action == 'audio_devices' for action, _ in win.client.commands) == count + 1
    capture(win, 'unity-call-page-active.png')
    button(popup, 'Hold').emit('clicked'); yield
    assert ('hold', {'call_id': call['id']}) in win.client.commands
    call['state'] = 'held'; render(); yield
    button(popup, 'Resume').emit('clicked'); yield
    assert ('unhold', {'call_id': call['id']}) in win.client.commands
    call['state'] = 'active'; render(); yield
    button(popup, 'End call').emit('clicked'); yield
    assert ('hangup', {'call_id': call['id']}) in win.client.commands
    popup.mute_button.emit('clicked'); yield
    assert ('mute', {'muted': True}) in win.client.commands
    button(popup.route_button.get_popover(), 'Phone speakerphone').emit('clicked'); yield
    assert win.client.commands[-2:] == [('audio_stop', {}), ('route', {'route': 'speaker'})]
    control.update(route='speaker', muted=True); render(); yield
    assert win.active_calls.route_button.get_label() == popup.route_button.get_label() == 'Speakerphone'
    assert popup.mute_button.get_label() == 'Unmute'
    button(popup.route_button.get_popover(), 'Computer via Bluetooth').emit('clicked'); yield
    assert ('route', {'route': 'desktop'}) in win.client.commands
    assert win.call_audio_waiting is not None, 'Wait for the real Bluetooth route callback, rather than its request acknowledgment'
    assert not any(action == 'audio_start' for action, _ in win.client.commands)
    control['route'] = 'bluetooth'; render(); yield
    assert win.call_audio_waiting is None
    assert any(action == 'audio_start' and payload['address'] == 'AA:BB:CC:DD:EE:FF' for action, payload in win.client.commands)
    audio.update(state='active', output='laptop_speakers', microphone='laptop_microphone', muted=False)
    render(); yield
    assert popup.output.get_visible() and popup.microphone.get_visible()
    popup.output.set_selected(win.call_audio_options['output'].index('bluetooth:11:22:33:44:55:66')); yield
    assert any(action == 'audio_select' and payload['output'] == 'bluetooth:11:22:33:44:55:66' for action, payload in win.client.commands)
    assert win.call_output.get_selected() == popup.output.get_selected()
    popup.route_button.popup(); yield
    capture(win.call_popup, 'unity-call-popup-audio.png')
    popup.route_button.popdown()
    win.error('Audio device disconnected')
    assert win.banner.get_title() == 'Audio device disconnected'
    win.call_popup.close(); yield
    assert win.call_popup is None
    render(); yield
    assert win.call_popup is None, 'Dismissed calls must not reopen on each state event'
    control['calls'] = []; render(); yield
    control['calls'] = [call]; render(); yield
    assert win.call_popup is None, 'A brief empty update must preserve dismissal'
    win.show_call_popup(force=True); yield
    assert win.call_popup is not None
    call.update(id='cd' * 16, state='ringing', connect_time=0); audio.clear(); render(); yield
    popup = win.call_popup_panel
    assert popup.timers[0][0].get_label() == ''
    button(popup, 'Answer').emit('clicked'); yield
    assert ('answer', {'call_id': 'cd' * 16}) in win.client.commands
    button(popup, 'Decline').emit('clicked'); yield
    assert ('reject', {'call_id': 'cd' * 16}) in win.client.commands
    capture(win.call_popup, 'unity-call-popup-incoming.png')
    control['calls'] = []; render(); yield
    pending_end = win.call_popup_empty_source
    for _ in range(10):
        render(); yield
        assert win.call_popup_empty_source == pending_end, 'Repeated empty updates must not defer closure forever'
    for _ in range(17): yield
    assert win.call_popup is None and win.call_timer_source is None
    # HFP retains its original integration, without inventing phone speakerphone support.
    call.update(id='ef' * 16, state='active', connect_time=0)
    control.update(backend='bluetooth_hfp', calls=[call], routes=['phone', 'bluetooth'])
    render(); yield
    assert not button(win.call_popup_panel.route_button.get_popover(), 'Phone speakerphone').get_sensitive()
    assert not win.call_popup_panel.mute_button.get_sensitive()
    assert 'Android Unity' in win.call_popup_panel.route_row.get_subtitle()
    control['calls'] = []; render(); yield
    assert win.call_popup_empty_source is not None
    win.authorized = False; win.render({}); yield
    assert win.call_popup is None and win.call_timer_source is None and win.call_popup_empty_source is None
    assert not win.active_calls.get_visible()
    win.authorized = True; control['calls'] = [call]; render(); yield
    assert win.call_popup is not None
    win.render({}); yield
    assert win.call_popup is None and win.call_popup_empty_source is None
    app.quit()

steps = checks()
step = 0
def advance():
    global step
    try:
        step += 1
        print(f'Call UI check {step}', flush=True)
        next(steps)
        return True
    except StopIteration:
        return False
    except Exception as error:
        failures.append(error); traceback.print_exc(); app.quit(); return False

app.run([])
faulthandler.cancel_dump_traceback_later()
if failures: raise SystemExit(1)
print('Shared call timers, actions, audio routes, popup dismissal and consent cleanup passed')
