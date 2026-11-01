#!/usr/bin/env python3
"""Native Unity client. Phone operations use only the public versioned D-Bus API."""
import base64
import datetime
import json
import math
from pathlib import Path
import shutil
import signal
import stat
import sys
import time
import weakref
import gi

gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
from gi.repository import Adw, Gdk, Gio, GLib, GLibUnix, Gtk, Pango

BUS = 'org.unityconnect'
ROOT = '/org/unityconnect/Manager'
MAX_FILE = 64 * 1024 * 1024


class GalleryDragProvider(Gdk.ContentProvider):
    """Materialize the verified original asynchronously before supplying a file URI."""
    def __init__(self, window, key):
        super().__init__()
        self.window = weakref.ref(window)
        self.key, self.device, self.generation = key, window.path, window.generation
        self.payload, self.failure, self.waiters = None, None, []
        self.deadline = time.monotonic() + 65
        self.timer = GLib.timeout_add(200, self.check)
        window.photo_status.set_label('Preparing the original file for dragging…')
        window.execute('photos', 'get', {'id': key}, done=self.downloaded, quiet=True)

    def do_ref_formats(self):
        return Gdk.ContentFormats.new(['text/uri-list'])

    def do_ref_storable_formats(self):
        return self.do_ref_formats()

    def valid(self):
        window = self.window()
        return bool(window and window.path == self.device and window.generation == self.generation
                    and self.key in window.photo_items and window.enabled('photos') and window.enabled('files'))

    def check(self):
        if not self.valid():
            self.finish(error='The phone connection or gallery permission changed.')
        elif time.monotonic() >= self.deadline:
            self.finish(error='The original file could not be downloaded in time. Try dragging it again.')
        return self.payload is None and self.failure is None

    def downloaded(self, result):
        if self.payload is not None or self.failure is not None:
            return
        path = result.get('received_path') if result and self.valid() else None
        try:
            if not path or not Path(path).is_absolute() or not Path(path).is_file():
                raise ValueError('Could not download the original file. Check the phone connection and try again.')
            self.finish(payload=(Gio.File.new_for_path(path).get_uri() + '\r\n').encode('utf-8'))
        except (OSError, ValueError) as error:
            self.finish(error=str(error))

    def finish(self, payload=None, error=None):
        if self.payload is not None or self.failure is not None:
            return
        self.payload, self.failure = payload, error
        if self.timer:
            GLib.source_remove(self.timer); self.timer = None
        window = self.window()
        if window and window.path == self.device and window.generation == self.generation:
            window.photo_status.set_label(error or 'Original file ready. Drop it to copy it into another app or folder.')
        for task, stream, priority, cancellable in self.waiters:
            self.write(task, stream, priority, cancellable)
        self.waiters.clear()

    def write(self, task, stream, priority, cancellable):
        if task.return_error_if_cancelled():
            return
        if self.failure or not self.valid():
            task.return_error(GLib.Error.new_literal(Gio.io_error_quark(), self.failure or 'Gallery access ended.', Gio.IOErrorEnum.FAILED))
            return
        def written(output, result):
            try:
                output.write_all_finish(result); task.return_boolean(True)
            except GLib.Error as error:
                task.return_error(error)
        stream.write_all_async(self.payload, priority, cancellable, written)

    def do_write_mime_type_async(self, mime, stream, priority, cancellable, callback, user_data):
        task = Gio.Task.new(self, cancellable, callback, user_data)
        task.set_priority(priority)
        if mime != 'text/uri-list':
            task.return_error(GLib.Error.new_literal(Gio.io_error_quark(), 'Unsupported drag format', Gio.IOErrorEnum.NOT_SUPPORTED))
        elif self.payload is not None or self.failure is not None:
            self.write(task, stream, priority, cancellable)
        else:
            self.waiters.append((task, stream, priority, cancellable))

    def do_write_mime_type_finish(self, result):
        return result.propagate_boolean()

CSS = '''
.unity-content { padding: 24px; }
.unity-card { background: @card_bg_color; border: 1px solid alpha(@window_fg_color, 0.12); border-radius: 16px; }
.notification-card { padding: 18px; }
.metric-card { padding: 16px; }
.media-card { padding: 24px; }
.artwork { border-radius: 12px; background: alpha(@window_fg_color, 0.06); }
.photo-tile { padding: 0; border-radius: 12px; border: 1px solid alpha(@window_fg_color, 0.12); background: @card_bg_color; }
.photo-tile:hover { border-color: alpha(@accent_bg_color, 0.8); }
.photo-info { padding: 10px 12px; }
.gallery flowboxchild { padding: 0; }
.thread-row { padding: 12px; }
.message-bubble { padding: 12px 16px; border-radius: 16px; background: @card_bg_color; border: 1px solid alpha(@window_fg_color, 0.10); }
.message-outgoing { background: alpha(@accent_bg_color, 0.13); border-color: alpha(@accent_bg_color, 0.22); }
.conversation-pane { border-right: 1px solid alpha(@window_fg_color, 0.10); }
.composer { padding: 12px; border-top: 1px solid alpha(@window_fg_color, 0.10); }
.player-control { min-width: 40px; min-height: 40px; }
.player-primary { min-width: 56px; min-height: 56px; border-radius: 50%; }
.unity-sidebar row { margin: 2px 8px; border-radius: 10px; }
.unity-sidebar row:selected { background: alpha(@accent_bg_color, 0.15); }
.unity-sidebar row:selected image, .unity-sidebar row:selected label { color: @accent_color; }
'''


def label(text='', style=None, **kwargs):
    widget = Gtk.Label(label=text, xalign=0, **kwargs)
    if kwargs.get('wrap'):
        widget.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
        widget.set_max_width_chars(80)
    if style:
        widget.add_css_class(style)
    return widget


def inset(widget, value):
    for side in ('start', 'end', 'top', 'bottom'):
        getattr(widget, 'set_margin_' + side)(value)
    return widget


def icon_button(icon, tooltip, callback=None, style=None):
    widget = Gtk.Button(icon_name=icon, tooltip_text=tooltip, valign=Gtk.Align.CENTER)
    if style:
        widget.add_css_class(style)
    if callback:
        widget.connect('clicked', callback)
    return widget


def timestamp(value, date=False):
    try:
        dt = datetime.datetime.fromtimestamp(float(value) / 1000)
        return dt.strftime('%d %b · %H:%M' if date else '%H:%M') if value else ''
    except (ValueError, TypeError, OverflowError, OSError):
        return ''


def size_text(value):
    value = max(0, int(value or 0))
    for unit in ('B', 'KB', 'MB', 'GB'):
        if value < 1024 or unit == 'GB':
            return f'{value:.0f} {unit}' if unit == 'B' else f'{value:.1f} {unit}'
        value /= 1024


def image_texture(image, media=False):
    """Decode only bounded, daemon-validated JPEG data; never fetch remote URLs."""
    if not image or image.get('mime') != 'image/jpeg':
        return None
    if not all(isinstance(image.get(k), int) and 1 <= image[k] <= (1024 if media else 320) for k in ('width', 'height')):
        return None
    try:
        encoded = image['data']
        if len(encoded) > (262144 if media else 43692):
            return None
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > (196608 if media else 32768):
            return None
        texture = Gdk.Texture.new_from_bytes(GLib.Bytes.new(raw))
        if (texture.get_width(), texture.get_height()) != (image['width'], image['height']):
            return None
        return texture
    except (ValueError, KeyError, GLib.Error):
        return None

class ThumbnailPicture(Gtk.Picture):
    """Thumbnail resolution must not dictate the gallery's column width."""
    def do_measure(self, orientation, for_size):
        if orientation == Gtk.Orientation.HORIZONTAL:
            return (0, 0, -1, -1)
        return (144, 144, -1, -1)

class Client:
    def __init__(self, changed, error):
        self.changed, self.error = changed, error
        self.bus = None
        self.owner_subscription = None
        self.subscription = None
        Gio.bus_get(Gio.BusType.SESSION, None, self._connected)

    def _connected(self, source, result):
        try:
            self.bus = Gio.bus_get_finish(result)
            self.subscription = self.bus.signal_subscribe(BUS, None, None, None, None,
                Gio.DBusSignalFlags.NONE, self._signal)
            self.owner_subscription = self.bus.signal_subscribe('org.freedesktop.DBus',
                'org.freedesktop.DBus', 'NameOwnerChanged', '/org/freedesktop/DBus', BUS,
                Gio.DBusSignalFlags.NONE, self._owner_changed)
            self.changed(ROOT)
        except GLib.Error as e:
            self.error(str(e))

    def _owner_changed(self, bus, sender, path, interface, signal, parameters):
        name, old_owner, new_owner = parameters.unpack()
        if not new_owner:
            self.error('Unity daemon stopped. Start unityd with systemctl --user start unityd.')
        else:
            self.changed("daemon-restarted")

    def _signal(self, bus, sender, path, interface, signal, parameters):
        if interface == 'org.unityconnect.Manager1' and signal == 'Changed':
            self.changed(parameters.unpack()[0])
        elif interface == 'org.unityconnect.Screen1' and signal == 'FrameAvailable':
            self.changed('screen-frame:' + path)
        elif interface == 'org.freedesktop.DBus.Properties':
            self.changed(path)

    def call(self, path, interface, method, signature='()', args=(), done=None, failed=None):
        if self.bus is None:
            (failed or self.error)('Session bus is unavailable.')
            return
        def finished(source, result):
            try:
                value = source.call_finish(result).unpack()
                if done:
                    done(value[0] if len(value) == 1 else value)
            except GLib.Error as e:
                (failed or self.error)(e.message)
        self.bus.call(BUS, path, interface, method, GLib.Variant(signature, args), None,
                      Gio.DBusCallFlags.NONE, 10000, None, finished)

    def manager(self, method, signature='()', args=(), done=None):
        self.call(ROOT, 'org.unityconnect.Manager1', method, signature, args, done)


def call_duration(seconds):
    seconds = max(0, int(seconds))
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f'{hours}:{minutes:02d}:{seconds:02d}' if hours else f'{minutes:02d}:{seconds:02d}'


class CallPanel(Gtk.Box):
    """The app and desktop popup share the same call actions and audio routing."""
    def __init__(self, owner, computer_selectors=False):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        self.owner, self.signature, self.timers = owner, None, []
        self.cards = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        self.append(self.cards)
        self.audio_group = Adw.PreferencesGroup()
        self.append(self.audio_group)
        self.route_row = Adw.ActionRow(title='Call audio')
        self.route_row.set_subtitle_lines(3)
        self.route_button = Gtk.MenuButton(label='Phone', valign=Gtk.Align.CENTER)
        self.route_row.add_suffix(self.route_button)
        self.audio_group.add(self.route_row)
        self.mute_button = Gtk.Button(label='Mute', valign=Gtk.Align.CENTER)
        self.mute_button.connect('clicked', lambda *_: owner.execute('call_control', 'mute', {'muted': not owner.call_control_state.get('muted', False)}))
        mute_row = Adw.ActionRow(title='Microphone'); mute_row.add_suffix(self.mute_button)
        self.audio_group.add(mute_row)
        self.output = self.microphone = None
        if computer_selectors:
            self.output = Adw.ComboRow(title='Computer output', model=Gtk.StringList.new(['System default']))
            self.microphone = Adw.ComboRow(title='Computer microphone', model=Gtk.StringList.new(['System default']))
            for field, row in [('output', self.output), ('microphone', self.microphone)]:
                row.connect('notify::selected', owner.select_call_audio_device, field)
                self.audio_group.add(row)
            refresh = icon_button('view-refresh-symbolic', 'Refresh computer audio devices', owner.refresh_call_audio_devices, 'flat')
            self.route_row.add_suffix(refresh)

    def render(self, control, audio):
        owner = self.owner
        calls = control.get('calls', []) if owner.enabled('call_control') else []
        ready = not control.get('reconnecting', False)
        self.set_visible(bool(calls))
        signature = json.dumps([control, audio, owner.enabled('call_control')], sort_keys=True)
        if signature == self.signature:
            return
        self.signature = signature
        owner.clear(self.cards); self.timers.clear()
        for call in calls:
            phase, cid = call.get('state'), call['id']
            card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
            card.add_css_class('unity-card')
            row = Adw.ActionRow(title=call.get('name') or call.get('number') or 'Unknown caller')
            row.set_title_lines(2)
            row.set_subtitle(' · '.join(filter(None, [call.get('number') if call.get('name') else '', {'ringing': 'Incoming call', 'dialing': 'Calling…', 'active': 'In call', 'held': 'On hold'}.get(phase, (phase or '').replace('_', ' ').title())])))
            row.set_subtitle_lines(2)
            timer = label('', 'title-3', valign=Gtk.Align.CENTER)
            row.add_suffix(timer); self.timers.append((timer, call))
            card.append(row)
            buttons = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, column_spacing=6, row_spacing=6, max_children_per_line=4)
            actions = [('Answer', 'answer'), ('Decline', 'reject')] if phase == 'ringing' else [('End call', 'hangup')]
            if call.get('can_hold') and phase in ('active', 'held'):
                actions.append(('Resume' if phase == 'held' else 'Hold', 'unhold' if phase == 'held' else 'hold'))
            for title, action in actions:
                button = Gtk.Button(label=title, sensitive=ready and phase != 'disconnecting')
                if action in ('hangup', 'reject'): button.add_css_class('destructive-action')
                elif action == 'answer': button.add_css_class('suggested-action')
                button.connect('clicked', lambda _, a=action, call_id=cid: owner.execute('call_control', a, {'call_id': call_id}))
                buttons.insert(button, -1)
            if phase == 'active':
                keypad = Gtk.MenuButton(label='Keypad', sensitive=ready)
                popover = Gtk.Popover(); grid = Gtk.Grid(column_spacing=6, row_spacing=6, column_homogeneous=True)
                for index, tone in enumerate('123456789*0#'):
                    key = Gtk.Button(label=tone)
                    key.connect('clicked', lambda _, t=tone, call_id=cid: owner.execute('call_control', 'dtmf', {'call_id': call_id, 'tone': t}, quiet=True))
                    grid.attach(key, index % 3, index // 3, 1, 1)
                popover.set_child(grid); keypad.set_popover(popover); buttons.insert(keypad, -1)
            card.append(inset(buttons, 8)); self.cards.append(card)
        self.tick()
        computer = audio.get('state') in ('active', 'waiting')
        routes = control.get('routes', [])
        current = 'Computer' if computer else {'phone': 'Phone', 'speaker': 'Speakerphone', 'wired': 'Wired', 'bluetooth': 'Bluetooth'}.get(control.get('route'), 'Phone')
        if owner.call_uses_hfp and control.get('route') == 'bluetooth': current = 'Computer'
        self.route_button.set_label(current)
        choices = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        popover = Gtk.Popover(); popover.set_child(inset(choices, 6))
        for route, title in [('phone', 'Phone earpiece'), ('speaker', 'Phone speakerphone'), ('wired', 'Phone wired headset'), ('bluetooth', 'Phone Bluetooth headset'), ('desktop', 'Computer via Bluetooth')]:
            supported = route in routes if route != 'desktop' else 'bluetooth' in routes
            if route == 'bluetooth' and owner.call_uses_hfp: supported = False
            button = Gtk.Button(label=title, sensitive=supported)
            button.add_css_class('flat')
            button.connect('clicked', lambda _, r=route: (popover.popdown(), owner.choose_call_route(r)))
            choices.append(button)
        self.route_button.set_popover(popover)
        self.route_button.set_sensitive(ready and any(c.get('state') in ('active', 'held') for c in calls))
        hint = audio.get('error') or control.get('error') or ''
        if owner.call_uses_hfp and not hint:
            hint = 'For phone speakerphone, enable phone speakerphone controls in Android Unity → Features.'
        self.route_row.set_subtitle(hint)
        self.mute_button.set_label('Unmute' if control.get('muted') else 'Mute')
        self.mute_button.set_sensitive(ready and any(c.get('state') == 'active' for c in calls) and (not owner.call_uses_hfp or computer))
        for row in (self.output, self.microphone):
            if row:
                row.set_visible(computer)
                row.set_sensitive(ready and owner.enabled('call_control') and not owner.call_audio_selection_pending)

    def tick(self):
        for widget, call in self.timers:
            widget.set_label(self.owner.call_elapsed(call))


class Window(Adw.ApplicationWindow):
    def __init__(self, app, client_factory=Client):
        super().__init__(application=app, title='Unity', default_width=1140, default_height=780)
        provider = Gtk.CssProvider()
        provider.load_from_string(CSS)
        Gtk.StyleContext.add_provider_for_display(self.get_display(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        self.devices, self.path, self.capabilities = {}, None, {}
        self.firefox_updating = False
        self.firefox_signature = None
        self.firefox_status = {}
        self.pending_refresh = self.authorized = self.requested_access = False
        self.access_check_pending = False
        self.bus_name = None
        self.pairing_active = self.updating_dropdown = False
        self.operations = {}
        self.pending_files = []
        self.file_sending = False
        self.file_send_token = 0
        self.file_send_error = ''
        self.file_send_notice = ''
        self.generation = 0
        self.properties_revision = self.state_request_revision = 0
        self.last_notifications = None
        self.notification_cards = {}
        self.pending_seek = None
        self.messages_loaded = self.photos_loaded = False
        self.sms_revision = None
        self.last_sms_id = None
        self.message_pages = {}
        self.call_audio_active = False
        self.call_audio_selection = {'output': '', 'microphone': ''}
        self.call_audio_catalog = {}
        self.call_audio_options = {'output': [''], 'microphone': ['']}
        self.call_audio_updating = self.call_audio_devices_pending = self.call_audio_selection_pending = False
        self.call_audio_devices_refresh = None
        self.call_audio_muted = False
        self.call_phone_address = None
        self.call_uses_hfp = False
        self.contacts_query = ""
        self.contacts_next = self.history_next = None
        self.history_loaded = False
        self.call_signature = None
        self.call_control_state = {}
        self.call_clock = {}
        self.call_timer_source = None
        self.call_popup = self.call_popup_panel = self.call_popup_toasts = None
        self.call_popup_status = None
        self.call_popup_empty_source = None
        self.call_popup_dismissed = set()
        self.call_popup_ids = set()
        self.popup_only = False
        self.popup_device = None
        self.open_page = None
        self.popup_has_calls = False
        self.call_audio_waiting = None
        self.call_audio_wait_timeout = None
        self.message_data = []
        self.selected_address = None
        self.photo_items, self.photo_widgets, self.preview_queue = {}, {}, []
        self.preview_inflight = 0
        self.gallery_copy_supported = False
        self.cap_signature = None
        self.phone_connectivity_state = {}
        self.connectivity_updating = False
        self.hotspot_command_pending = False
        self.bluetooth_commands_pending = set()
        self.phone_device_rows = {}
        self.connectivity_notice = ''
        self.desktop_preferences_updating = False
        self.ringer_state = {}
        self.toasts = Adw.ToastOverlay()
        self.set_content(self.toasts)
        self.split = Adw.NavigationSplitView(min_sidebar_width=220, max_sidebar_width=260,
                                             sidebar_width_unit=Adw.LengthUnit.SP)
        self.toasts.set_child(self.split)
        sidebar = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.sidebar_toolbar = Adw.ToolbarView(top_bar_style=Adw.ToolbarStyle.FLAT)
        self.sidebar_header = Adw.HeaderBar(title_widget=Adw.WindowTitle(title='Unity'))
        self.app_menu = Gtk.MenuButton(icon_name='open-menu-symbolic', tooltip_text='Unity menu')
        self.app_menu.set_popover(Gtk.PopoverMenu.new_from_model_full(app.get_menubar(), Gtk.PopoverMenuFlags.NESTED))
        self.sidebar_header.pack_end(self.app_menu)
        self.sidebar_toolbar.add_top_bar(self.sidebar_header)
        self.sidebar_toolbar.set_content(sidebar)
        self.device_dropdown = Gtk.DropDown(model=Gtk.StringList.new(['No paired devices']))
        inset(self.device_dropdown, 12)
        self.device_dropdown.connect('notify::selected', self.device_selected)
        sidebar.append(self.device_dropdown)
        self.nav = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.nav.add_css_class('navigation-sidebar')
        self.nav.add_css_class('unity-sidebar')
        self.nav.connect('row-selected', self.navigate)
        nav_scroll = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        nav_scroll.set_child(self.nav)
        sidebar.append(nav_scroll)
        footer = inset(label('Local. Private. Connected.', 'dim-label'), 18)
        footer.add_css_class('caption')
        sidebar.append(footer)
        self.split.set_sidebar(Adw.NavigationPage.new(self.sidebar_toolbar, 'Unity'))
        content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.title = Adw.WindowTitle(title='Overview', subtitle='')
        self.content_toolbar = Adw.ToolbarView(top_bar_style=Adw.ToolbarStyle.FLAT)
        self.content_header = Adw.HeaderBar(title_widget=self.title)
        self.content_toolbar.add_top_bar(self.content_header)
        self.content_toolbar.set_content(content)
        self.banner = Adw.Banner(title='Connecting to Unity…', revealed=True)
        self.banner.set_button_label('Retry')
        self.banner.connect('button-clicked', self.banner_action)
        content.append(self.banner)
        self.stack = Gtk.Stack(vexpand=True)
        content.append(self.stack)
        self.split.set_content(Adw.NavigationPage.new(self.content_toolbar, 'Overview'))
        self.pages = {}
        self.build_pages()
        # Libadwaita activates one matching breakpoint. The narrower condition
        # must come last and collapse both navigation views together.
        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse('max-width: 760sp'))
        bp.add_setter(self.split, 'collapsed', True)
        bp.add_setter(self.message_split, 'collapsed', True)
        bp.add_setter(self.media_heading, 'visible', False)
        bp.add_setter(self.art_stack, 'width-request', 160)
        bp.add_setter(self.art_stack, 'height-request', 160)
        bp.add_setter(self.media_card, 'spacing', 12)
        self.add_breakpoint(bp)
        self.render({})
        self.client = client_factory(self.changed, self.error)
        self.connect('close-request', self.close_main_call_window)
        app.update_desktop()

    def register_page(self, name, title, icon, widget):
        self.stack.add_named(widget, name)
        row = Gtk.ListBoxRow()
        box = Gtk.Box(spacing=12, margin_start=12, margin_end=12, margin_top=11, margin_bottom=11)
        box.append(Gtk.Image(icon_name=icon))
        box.append(label(title))
        row.set_child(box)
        row.page_name, row.page_title = name, title
        self.nav.append(row)
        self.pages[name] = row

    def page(self, name, title, icon, preferences=False):
        if preferences:
            page = Adw.PreferencesPage(title=title, icon_name=icon)
            self.register_page(name, title, icon, page)
            return page
        scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER)
        clamp = Adw.Clamp(maximum_size=1040, tightening_threshold=760)
        page = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=24, valign=Gtk.Align.START)
        page.add_css_class('unity-content')
        clamp.set_child(page)
        scroll.set_child(clamp)
        self.register_page(name, title, icon, scroll)
        return page

    def heading(self, page, title, description='', action=None):
        box = Gtk.Box(spacing=12)
        text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, hexpand=True)
        text.append(label(title, 'title-1', wrap=True))
        if description:
            text.append(label(description, 'dim-label', wrap=True))
        box.append(text)
        if action:
            box.append(action)
        page.append(box)
        return box

    def group(self, page, title, description=None):
        group = Adw.PreferencesGroup(title=title, description=description or '')
        page.add(group) if isinstance(page, Adw.PreferencesPage) else page.append(group)
        return group

    def button(self, group, title, text, callback, subtitle=None):
        row = Adw.ActionRow(title=title, subtitle=subtitle or '')
        row.set_title_lines(2)
        row.set_subtitle_lines(2)
        button = Gtk.Button(label=text, valign=Gtk.Align.CENTER)
        button.connect('clicked', callback)
        row.add_suffix(button)
        row.set_activatable_widget(button)
        group.add(row)
        return button

    def entry(self, group, title):
        entry = Adw.EntryRow(title=title)
        group.add(entry)
        return entry

    def entry_action(self, group, title, icon, callback):
        entry = self.entry(group, title)
        button = icon_button(icon, title, callback, 'flat')
        entry.add_suffix(button)
        return entry, button

    def metric(self, row, icon, title):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        box.add_css_class('unity-card')
        box.add_css_class('metric-card')
        header = Gtk.Box(spacing=8)
        header.append(Gtk.Image(icon_name=icon))
        header.append(label(title, 'dim-label'))
        box.append(header)
        value = label('Unavailable', 'title-2', wrap=True)
        readings = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        readings.append(value)
        box.append(readings)
        row.append(box)
        return value, readings

    def empty(self, title, description, icon):
        return Adw.StatusPage(title=title, description=description, icon_name=icon)

    def build_pages(self):
        app = self.get_application()
        overview = self.page('overview', 'Overview', 'view-grid-symbolic')
        self.device_heading = label('Your phone, at a glance', 'title-1', wrap=True)
        overview.append(self.device_heading)
        metrics = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12,
                          halign=Gtk.Align.START)
        self.connection_value, _ = self.metric(metrics, 'network-wireless-symbolic', 'Connection')
        self.battery_value, battery_card = self.metric(metrics, 'battery-symbolic', 'Battery')
        self.battery_power = label('', 'dim-label', wrap=True)
        self.battery_power.set_tooltip_text('Net power at the phone battery. Charging power excludes energy used directly by the phone.')
        battery_card.append(self.battery_power)
        overview.append(metrics)
        quick = self.group(overview, 'Quick actions')
        self.find_state = {}
        self.find_button = self.button(quick, 'Find your phone', 'Ring', self.toggle_find_phone,
            'Ring for 30 seconds. Alarm volume is restored after ringing stops.')
        self.url_entry, self.url_button = self.entry_action(quick, 'Share a web address', 'send-to-symbolic',
            lambda *_: self.execute('urls', 'open', {'url': self.url_entry.get_text()}))
        self.clip_status = Adw.ActionRow(title='Automatic clipboard sync')
        self.clip_status.set_subtitle_lines(3)
        quick.add(self.clip_status)
        self.url_receive = Adw.ActionRow(title='Received web page', subtitle='No shared URL')
        self.url_receive.set_subtitle_lines(2)
        self.url_open = icon_button('external-link-symbolic', 'Open received web page', self.open_received_url, 'flat')
        self.url_receive.add_suffix(self.url_open)
        quick.add(self.url_receive)
        self.incoming_url = None
        self.clip_receive = label('', 'dim-label', wrap=True, selectable=True)
        quick.add(self.clip_receive)
        activity = self.group(overview, 'Recent activity')
        self.activity_row = Adw.ActionRow(title='You’re all caught up', subtitle='Transfers and actions will appear here.')
        self.activity_row.set_subtitle_lines(2)
        activity.add(self.activity_row)

        notifications = self.page('notifications', 'Notifications', 'preferences-system-notifications-symbolic')
        self.notification_count = label('Phone notifications', 'title-1')
        notifications.append(self.notification_count)
        self.notifications = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14)
        notifications.append(self.notifications)
        self.notifications_empty = self.empty('All caught up', 'Notifications from your phone will appear here.', 'preferences-system-notifications-symbolic')
        notifications.append(self.notifications_empty)

        self.build_messages()
        self.build_calls()

        files = self.page('files', 'Files', 'folder-symbolic')
        self.heading(files, 'Move files between devices', 'Drop files anywhere in Unity to select them, or choose files below.')
        fg = self.group(files, 'Send to device', 'All file types are supported, up to 64 MiB per file.')
        self.file_button = self.button(fg, 'Select files', 'Choose files…', self.choose_file)
        self.pending_file_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.pending_file_list.add_css_class('boxed-list')
        fg.add(self.pending_file_list)
        self.file_send_row = Adw.ActionRow(title='No files selected')
        self.file_send_row.set_subtitle_lines(3)
        self.file_send_button = Gtk.Button(label='Send to device', valign=Gtk.Align.CENTER)
        self.file_send_button.add_css_class('suggested-action')
        self.file_send_button.connect('clicked', self.send_selected_files)
        self.file_send_row.add_suffix(self.file_send_button)
        fg.add(self.file_send_row)
        fg = self.group(files, 'Transfer activity')
        self.file_status = Adw.ActionRow(title='No transfer active')
        self.file_status.set_subtitle_lines(2)
        self.file_open = icon_button('document-open-symbolic', 'Open received file', self.open_received_file, 'flat')
        self.file_status.add_suffix(self.file_open)
        self.received_path = None
        fg.add(self.file_status)
        bg = self.group(files, 'Shared phone folder', 'Only folders you approve on your phone are visible.')
        self.browse_button = self.button(bg, 'Browse phone files', 'Browse', lambda *_: self.execute('browsing', 'list', {'id': ''}, done=self.show_browser))
        self.browser = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.browser.add_css_class('boxed-list')
        bg.add(self.browser)

        photos = self.page('photos', 'Photos', 'image-x-generic-symbolic')
        self.photos_button = icon_button('view-refresh-symbolic', 'Refresh photos', lambda *_: self.load_photos(True), 'flat')
        self.heading(photos, 'Photos and videos', 'Thumbnails load first. Right-click an item to copy or download the original.', self.photos_button)
        self.photo_status = label('Open Photos to load your gallery.', 'dim-label', wrap=True)
        photos.append(self.photo_status)
        self.photos = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, homogeneous=True,
            min_children_per_line=1, max_children_per_line=5, column_spacing=12, row_spacing=12)
        self.photos.add_css_class('gallery')
        photos.append(self.photos)
        self.photos_empty = self.empty('Your gallery is waiting', 'Connect your phone and allow photo sharing.', 'image-x-generic-symbolic')
        photos.append(self.photos_empty)

        connectivity = self.page('connectivity', 'Connectivity', 'network-wireless-symbolic')
        self.heading(connectivity, 'Your phone connections',
            'Mobile hotspot and Bluetooth devices are reported by your phone. Enable Phone connectivity and Nearby devices in Unity on Android.')
        radio = self.group(connectivity, 'Phone Mobile Hotspot',
            'Share your phone’s internet using your Samsung hotspot routines. The switch shows the state last reported by the phone.')
        self.phone_hotspot = Adw.SwitchRow(title='Mobile Hotspot', subtitle='Connect and enable Phone connectivity on your phone.')
        self.phone_hotspot.set_subtitle_lines(3)
        self.phone_hotspot.connect('notify::active', self.toggle_phone_hotspot)
        radio.add(self.phone_hotspot)
        self.hotspot_choices = Adw.ActionRow(title='Set Mobile Hotspot',
            subtitle='Hotspot state is unavailable. Send an explicit on or off request.')
        self.hotspot_choices.set_subtitle_lines(3)
        choices = Gtk.Box(spacing=8, valign=Gtk.Align.CENTER)
        self.hotspot_on_button = Gtk.Button(label='On'); self.hotspot_off_button = Gtk.Button(label='Off')
        self.hotspot_on_button.connect('clicked', lambda *_: self.request_phone_hotspot(True))
        self.hotspot_off_button.connect('clicked', lambda *_: self.request_phone_hotspot(False))
        choices.append(self.hotspot_on_button); choices.append(self.hotspot_off_button)
        self.hotspot_choices.add_suffix(choices); radio.add(self.hotspot_choices)
        self.connectivity_refresh = self.button(radio, 'Update phone connections', 'Refresh',
            lambda *_: self.execute('phone_connectivity', 'refresh'))
        self.phone_bluetooth_group = self.group(connectivity, 'Bluetooth devices connected to your phone',
            'Turn a connected device off below to request its disconnection. Create its device-specific routine on the phone first.')
        self.phone_bluetooth_hint = Adw.ActionRow(title='No phone connection report',
            subtitle='Connect and enable Phone connectivity in Unity on Android.')
        self.phone_bluetooth_hint.set_subtitle_lines(3)
        self.phone_bluetooth_group.add(self.phone_bluetooth_hint)
        self.connectivity_status = label('', 'dim-label', wrap=True)
        connectivity.append(self.connectivity_status)

        media = self.page('media', 'Media', 'multimedia-player-symbolic')
        audio_group = Adw.PreferencesGroup(title='Audio routing',
            description='Wi-Fi sends stereo; Bluetooth sends mono. Both links stay connected.')
        self.audio_route = Adw.SwitchRow(title='Play laptop audio on this phone',
            subtitle='Enable Laptop audio on phone in the Android Features page, then connect.')
        self.audio_route.set_subtitle_lines(3)
        self.audio_route.set_title_lines(2)
        self.audio_route.set_sensitive(False)
        self.audio_route.connect('notify::active', self.toggle_audio_route)
        self.audio_updating = False
        self.audio_pending = False
        self.audio_pending_operation = None
        self.audio_state = {}
        self.audio_transport = Adw.ComboRow(title='Audio connection', use_subtitle=True,
            model=Gtk.StringList.new(['Automatic', 'Wi-Fi / LAN', 'Bluetooth']))
        self.audio_transport.set_tooltip_text('Automatic prefers Wi-Fi and uses Bluetooth when Wi-Fi is disconnected.')
        self.audio_transport.set_selected(['auto', 'lan', 'bluetooth'].index(app.preferences['audio_transport']))
        self.audio_transport.connect('notify::selected', lambda row, _: app.set_preference('audio_transport',
            ['auto', 'lan', 'bluetooth'][row.get_selected()]) if not self.desktop_preferences_updating else None)
        audio_group.add(self.audio_transport)
        audio_group.add(self.audio_route)
        self.phone_audio_route = Adw.SwitchRow(title='Play phone audio on this laptop',
            subtitle='Approve media audio capture on the phone each time. Some apps block capture.')
        self.phone_audio_route.set_subtitle_lines(3)
        self.phone_audio_route.set_title_lines(2)
        self.phone_audio_route.set_sensitive(False)
        self.phone_audio_route.connect('notify::active', self.toggle_audio_route)
        audio_group.add(self.phone_audio_route)
        media.append(audio_group)
        sound_group = self.group(media, 'Device volume')
        self.sound_widgets = {}
        self.sound_pending = {}
        self.sound_state = {}
        for source, title in [('desktop', 'Laptop output volume'), ('phone', 'Phone media volume')]:
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_start=14, margin_end=14, margin_top=12, margin_bottom=12)
            row = Gtk.Box(spacing=12)
            heading = label(title, 'heading', hexpand=True, wrap=True)
            level = label('Unavailable', 'dim-label')
            mute = icon_button('audio-volume-high-symbolic', 'Mute ' + ('laptop' if source == 'desktop' else 'phone'), lambda _, s=source: self.mute_sound(s), 'flat')
            row.append(heading); row.append(level); row.append(mute); box.append(row)
            scale = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 100, 1)
            scale.set_draw_value(False)
            scale.set_tooltip_text(title)
            scale.connect('change-value', self.change_sound, source)
            box.append(scale)
            hint = label('', 'caption', wrap=True)
            box.append(hint)
            sound_group.add(box)
            self.sound_widgets[source] = {'scale': scale, 'mute': mute, 'level': level, 'hint': hint}
        self.media_heading = self.heading(media, 'Now playing', 'Choose a player on your laptop or phone.')
        players_group = self.group(media, 'Players')
        self.media_picker = Adw.ComboRow(title='Player', model=Gtk.StringList.new(['No active players']))
        players_group.add(self.media_picker)
        self.media_players = []
        self.media_selection = None
        self.media_model_signature = None
        self.media_picker_updating = False
        self.media_picker.connect('notify::selected', self.select_media_player)
        self.media_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        self.media_card.add_css_class('unity-card')
        self.media_card.add_css_class('media-card')
        player_clamp = Adw.Clamp(maximum_size=680, tightening_threshold=440)
        player_clamp.set_child(self.media_card)
        media.append(player_clamp)
        self.media_source = label('Phone media', 'dim-label')
        self.media_source.add_css_class('caption')
        self.media_card.append(self.media_source)
        self.art_stack = Gtk.Stack(halign=Gtk.Align.CENTER, width_request=220, height_request=220)
        self.art_stack.add_css_class('artwork')
        self.art_stack.set_overflow(Gtk.Overflow.HIDDEN)
        self.art_picture = Gtk.Picture(content_fit=Gtk.ContentFit.CONTAIN, can_shrink=True)
        self.art_stack.add_named(self.art_picture, 'image')
        self.art_stack.add_named(Gtk.Image(icon_name='audio-x-generic-symbolic', pixel_size=72), 'empty')
        self.media_card.append(self.art_stack)
        self.media_title = label('Nothing playing', 'title-1', wrap=True, justify=Gtk.Justification.CENTER, halign=Gtk.Align.CENTER)
        self.media_artist = label('Start playback on your laptop or phone.', 'dim-label', wrap=True, halign=Gtk.Align.CENTER)
        self.media_album = label('', 'dim-label', wrap=True, halign=Gtk.Align.CENTER)
        self.media_album.add_css_class('caption')
        for widget in (self.media_title, self.media_artist, self.media_album):
            self.media_card.append(widget)
        progress = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.media_seek = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 1, 1)
        self.media_seek.set_draw_value(False)
        self.media_seek.connect('change-value', self.seek_media)
        progress.append(self.media_seek)
        self.media_waiting = Gtk.ProgressBar(visible=False)
        self.media_waiting.set_tooltip_text('Waiting for the player to publish the track duration')
        progress.append(self.media_waiting)
        timebox = Gtk.Box()
        self.media_elapsed = label('0:00', 'dim-label', hexpand=True)
        self.media_duration = label('0:00', 'dim-label')
        self.media_elapsed.add_css_class('caption')
        self.media_duration.add_css_class('caption')
        timebox.append(self.media_elapsed)
        timebox.append(self.media_duration)
        progress.append(timebox)
        self.media_card.append(progress)
        controls = Gtk.Box(spacing=20, halign=Gtk.Align.CENTER)
        self.media_previous = icon_button('media-skip-backward-symbolic', 'Previous track', lambda *_: self.media_action('previous'), 'circular')
        self.media_toggle = icon_button('media-playback-start-symbolic', 'Play', self.toggle_media, 'suggested-action')
        self.media_toggle.add_css_class('player-primary')
        self.media_next = icon_button('media-skip-forward-symbolic', 'Next track', lambda *_: self.media_action('next'), 'circular')
        for button in (self.media_previous, self.media_toggle, self.media_next):
            button.add_css_class('player-control')
            controls.append(button)
        self.media_card.append(controls)
        self.media_info = label('No active session', 'dim-label', halign=Gtk.Align.CENTER)
        self.media_info.add_css_class('caption')
        self.media_card.append(self.media_info)
        self.media_state = {}
        self.media_sample_at = time.monotonic()
        self.media_tick_source = None
        self.connect('realize', lambda *_: self.start_media_clock())
        self.connect('unrealize', lambda *_: self.stop_media_clock())
        self.art_key = None

        ring = self.group(media, 'Phone sound')
        self.ringer_combo = Adw.ComboRow(title='Ringer mode', model=Gtk.StringList.new(['Normal', 'Vibrate', 'Silent', 'DND', 'DND off']))
        ring.add(self.ringer_combo)
        self.ringer_button = self.button(ring, 'Apply mode', 'Apply', lambda *_: self.execute('ringer', 'set',
            {'mode': ['normal', 'vibrate', 'silent', 'dnd', 'off'][self.ringer_combo.get_selected()]}))
        internet = self.group(connectivity, 'Phone internet sharing')
        self.hotspot_button = self.button(internet, 'Phone internet sharing', 'Request', lambda *_: self.execute('hotspot', 'request_settings'), 'Review internet sharing settings on your phone.')
        self.routines_group = self.group(connectivity, 'Samsung Routines',
            'Set up keyword routines in Unity on your phone. Samsung runs the actions; Unity confirms only that the trigger was sent.')
        self.routine_buttons = []
        for title, action, subtitle in [
                ('Mobile hotspot on', 'hotspot_on', 'UNITY_HOTSPOT_ON'),
                ('Mobile hotspot off', 'hotspot_off', 'UNITY_HOTSPOT_OFF'),
                ('Disconnect Bluetooth device', 'disconnect_bluetooth', 'The specific device is selected in your Samsung routine.')]:
            self.routine_buttons.append(self.button(self.routines_group, title, 'Send trigger',
                lambda *_, action=action: self.execute('samsung_routines', action), subtitle))
        self.routines_group.set_visible(False)

        remote = self.page('remote', 'Remote Control', 'input-keyboard-symbolic')
        self.heading(remote, 'A little closer to your phone', 'Lock devices, share screens, and type on your phone.')
        lock = self.group(remote, 'Device locking')
        self.lock_phone_button = self.button(lock, 'Lock this phone', 'Lock phone',
            lambda *_: self.execute('lock', 'lock'), 'Enable Remote device locking and activate phone locking in Android’s Features page.')
        screens = self.group(remote, 'Screen sharing', 'Approve capture on the device sharing its screen each time.')
        self.share_desktop_button = self.button(screens, 'Computer screen → phone', 'Share', lambda *_: self.start_screen('desktop'))
        self.screen_control_switch = Adw.SwitchRow(title='Control computer from phone', subtitle='Allow keyboard and mouse access in the desktop sharing chooser.')
        self.screen_control_switch.set_active(True)
        screens.add(self.screen_control_switch)
        self.screen_blank_switch = Adw.SwitchRow(title='Use phone as main display', subtitle='Make the phone the primary monitor and disable the laptop panel after the viewer opens. Restore the previous display layout when sharing stops.')
        self.screen_blank_switch.set_subtitle_lines(3)
        screens.add(self.screen_blank_switch)
        self.screen_extend_switch = Adw.SwitchRow(title='Use phone as extended display',
            subtitle='Add a separate desktop beside the laptop display. Both screens stay on.')
        self.screen_extend_switch.set_subtitle_lines(3)
        screens.add(self.screen_extend_switch)
        self.screen_extend_switch.connect('notify::active', lambda row, _: self.screen_blank_switch.set_active(False) if row.get_active() else None)
        self.screen_blank_switch.connect('notify::active', lambda row, _: self.screen_extend_switch.set_active(False) if row.get_active() else None)
        def control_changed(row, _):
            self.screen_blank_switch.set_sensitive(row.get_active())
            if not row.get_active(): self.screen_blank_switch.set_active(False)
        self.screen_control_switch.connect('notify::active', control_changed)
        self.view_phone_button = self.button(screens, 'Phone screen → computer', 'View', lambda *_: self.start_screen('phone'))
        self.stop_screen_button = self.button(screens, 'End screen sharing', 'Stop', lambda *_: self.execute('screen', 'stop'))
        self.laptop_display_button = self.button(screens, 'Laptop display', 'Turn off', self.toggle_laptop_display,
            'Restore the display at any time. Ending sharing or losing the connection also restores it.')
        self.screen_status = Adw.ActionRow(title='No screen share', subtitle='Enable Screen sharing in the Android Features page, then connect.')
        self.screen_status.set_subtitle_lines(3)
        screens.add(self.screen_status)
        self.screen_state = {}
        self.screen_viewer = None
        self.screen_picture = None
        self.screen_fetching = False
        self.screen_sequence = 0
        self.screen_epoch = 0
        rg = self.group(remote, 'Phone input' , 'Enable Unity’s keyboard or accessibility service on your phone first.')
        self.remote_text, self.remote_button = self.entry_action(rg, 'Text to type', 'input-keyboard-symbolic',
            lambda *_: self.execute('remote_input', 'text', {'text': self.remote_text.get_text()}))
        self.pad = Gtk.DrawingArea(content_height=200)
        self.pad.set_tooltip_text('Phone touch surface: drag to swipe, click to tap')
        self.pad.set_accessible_role(Gtk.AccessibleRole.GROUP)
        self.pad.set_draw_func(self.draw_pad)
        rg.add(self.pad)
        drag = Gtk.GestureDrag()
        drag.connect('drag-begin', self.drag_begin)
        drag.connect('drag-end', self.drag_end)
        self.pad.add_controller(drag)
        firefox = self.page('firefox', 'Firefox', 'web-browser-symbolic')
        self.heading(firefox, 'Continue in Firefox', 'See saved pages and restore your place in Firefox on this computer.')
        connection = self.group(firefox, 'Connection')
        self.firefox_connection = Adw.ActionRow()
        self.firefox_connection.set_use_markup(False)
        self.firefox_connection.set_title('Firefox extension')
        self.firefox_connection.set_subtitle('Enable the Firefox extension in Settings, then connect from its Settings & privacy page.')
        self.firefox_connection.set_subtitle_lines(3)
        connection.add(self.firefox_connection)
        saved = self.group(firefox, 'Saved pages', 'Full checkpoints and approved form values stay in Firefox. Summaries are removed when the browser disconnects.')
        self.firefox_contexts = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.firefox_contexts.add_css_class('boxed-list')
        saved.add(self.firefox_contexts)
        settings = self.page('settings', 'Settings', 'preferences-system-symbolic', preferences=True)
        desktop = self.group(settings, 'Desktop and menus')
        self.background_switch = Adw.SwitchRow(title='Keep Unity in the top bar',
            subtitle='Keep the indicator and quick actions available after closing the window.')
        self.background_switch.set_active(app.preferences['keep_running'])
        self.background_switch.connect('notify::active', lambda row, _: app.set_preference('keep_running', row.get_active())
            if not self.desktop_preferences_updating else None)
        desktop.add(self.background_switch)
        self.indicator_status_switch = Adw.SwitchRow(title='Show phone status in the indicator',
            subtitle='Show the selected phone, connection and battery above the quick actions.')
        self.indicator_status_switch.set_active(app.preferences['show_status'])
        self.indicator_status_switch.connect('notify::active', lambda row, _: app.set_preference('show_status', row.get_active())
            if not self.desktop_preferences_updating else None)
        desktop.add(self.indicator_status_switch)
        self.appearance_combo = Adw.ComboRow(title='Appearance', model=Gtk.StringList.new(['System', 'Light', 'Dark']))
        self.appearance_combo.set_selected(['system', 'light', 'dark'].index(app.preferences['appearance']))
        self.appearance_combo.connect('notify::selected', lambda row, _: app.set_preference('appearance',
            ['system', 'light', 'dark'][row.get_selected()]) if not self.desktop_preferences_updating else None)
        desktop.add(self.appearance_combo)
        browser = self.group(settings, 'Firefox companion')
        self.firefox_switch = Adw.SwitchRow(title='Enable Firefox extension', subtitle='Allow the Session Continuity extension to share saved-page summaries and receive restore requests on this computer.')
        self.firefox_switch.set_subtitle_lines(3)
        self.firefox_switch.set_sensitive(False)
        self.firefox_switch.connect('notify::active', self.toggle_firefox)
        browser.add(self.firefox_switch)
        security = self.group(settings, 'Privacy and access')
        self.access_row = Adw.ActionRow(title='App access', subtitle='Checking access…')
        self.access_row.set_subtitle_lines(3)
        self.access_button = Gtk.Button(label='Allow access', valign=Gtk.Align.CENTER)
        self.access_button.connect('clicked', self.authorize_this_app)
        self.access_button.set_sensitive(False)
        self.access_row.add_suffix(self.access_button)
        security.add(self.access_row)
        pairing = self.group(settings, 'Pair devices')
        self.discovery_button = self.button(pairing, 'Open pairing for Wi-Fi or Bluetooth', 'Pair', self.discover)
        self.pair_info = label('Pairing is closed', 'dim-label', wrap=True, selectable=True)
        pairing.add(self.pair_info)
        self.pair_requests = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        pairing.add(self.pair_requests)
        self.unpair_button = self.button(pairing, 'Remove selected device', 'Unpair', self.unpair)
        self.cap_group = self.group(settings, 'Permissions and capabilities', 'Features depend on the permissions and support available on your phone.')
        self.cap_rows = []
        self.nav.select_row(self.pages['overview'])

    def toggle_firefox(self, row, _):
        if self.firefox_updating or not self.authorized:
            return
        row.set_sensitive(False)
        def failed(message):
            self.render_firefox(json.dumps(self.firefox_status))
            self.error(message)
        self.client.call(ROOT, 'org.unityconnect.Manager1', 'SetFirefoxEnabled', '(b)', (row.get_active(),),
                         lambda _: self.fetch_firefox(), failed=failed)

    def fetch_firefox(self):
        if self.authorized:
            self.client.manager('FirefoxStatus', done=self.render_firefox)

    def restore_firefox(self, profile_id, context_id=''):
        if not self.authorized:
            return
        self.client.manager('RestoreFirefoxContext', '(ss)', (profile_id, context_id),
                            lambda _: self.fetch_firefox())

    def render_firefox(self, payload):
        status = json.loads(payload) if self.authorized else {}
        self.firefox_status = status
        self.firefox_updating = True
        self.firefox_switch.set_active(bool(status.get('enabled')))
        self.firefox_switch.set_sensitive(self.authorized)
        self.firefox_updating = False
        profiles = status.get('profiles', []) if status.get('enabled') else []
        count = len(profiles)
        subtitle = status.get('error') or ('Connected to Firefox' if count else
            'In the extension, open Settings & privacy and turn on Connect to Unity Linux.' if status.get('enabled') else
            'Enable Firefox extension in Unity Settings to connect.')
        if not self.authorized:
            subtitle = 'Allow this app access to see Firefox summaries and use restore controls.'
        self.firefox_connection.set_subtitle(subtitle)
        self.get_application().update_desktop()
        signature = json.dumps([self.authorized, status], sort_keys=True)
        if signature == self.firefox_signature:
            return
        self.firefox_signature = signature
        self.clear(self.firefox_contexts)
        for index, profile in enumerate(profiles, 1):
            summary = profile.get('summary', {})
            active_id = summary.get('activeId')
            header = Adw.ActionRow(title=f'Firefox profile {index}', subtitle='Tracking' if summary.get('tracking') else 'Tracking paused')
            header.set_use_markup(False)
            last = profile.get('last_result') or {}
            if last.get('note'):
                header.set_subtitle(last['note'] + ' · ' + summary.get('restoreState', 'idle'))
            restore = Gtk.Button(label='Restore last active', valign=Gtk.Align.CENTER)
            restore.set_sensitive(bool(active_id))
            restore.connect('clicked', lambda _, p=profile['id']: self.restore_firefox(p))
            header.add_suffix(restore)
            self.firefox_contexts.append(header)
            for context in summary.get('contexts', []):
                details = []
                if context['id'] == active_id:
                    details.append('Last active')
                for media in context.get('media', []):
                    details.append(f"{media['kind'].title()} at {call_duration(media['time'])}")
                if context.get('scrollProgress') is not None:
                    details.append(f"Read {round(context['scrollProgress'] * 100)}%")
                if context.get('closed'):
                    details.append('Closed')
                row = Adw.ActionRow()
                row.set_use_markup(False)
                row.set_title(context['url'])
                row.set_subtitle(' · '.join(details) or 'Saved page')
                row.set_title_lines(2)
                row.set_subtitle_lines(2)
                button = Gtk.Button(label='Restore', valign=Gtk.Align.CENTER)
                button.set_sensitive(not context.get('closed'))
                button.connect('clicked', lambda _, p=profile['id'], c=context['id']: self.restore_firefox(p, c))
                row.add_suffix(button)
                self.firefox_contexts.append(row)
        if not profiles:
            empty = Adw.ActionRow()
            empty.set_use_markup(False)
            empty.set_title('No Firefox pages connected')
            empty.set_subtitle(subtitle)
            empty.set_subtitle_lines(3)
            self.firefox_contexts.append(empty)

    def build_messages(self):
        self.message_split = Adw.NavigationSplitView(min_sidebar_width=220, max_sidebar_width=280, sidebar_width_unit=Adw.LengthUnit.SP)
        self.register_page('messages', 'Messages', 'mail-unread-symbolic', self.message_split)
        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse('max-width: 1080sp'))
        bp.add_setter(self.message_split, 'collapsed', True)
        self.add_breakpoint(bp)
        threads = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        threads.add_css_class('conversation-pane')
        header = Adw.HeaderBar(title_widget=Adw.WindowTitle(title='Conversations'))
        header.set_show_end_title_buttons(False)
        header.set_show_start_title_buttons(False)
        self.message_refresh = icon_button('view-refresh-symbolic', 'Refresh messages', lambda *_: self.load_messages(True), 'flat')
        self.new_message = icon_button('list-add-symbolic', 'New message', self.compose_message, 'flat')
        header.pack_start(self.message_refresh)
        header.pack_end(self.new_message)
        threads.append(header)
        self.message_search = Gtk.SearchEntry(placeholder_text='Search conversations', margin_start=12, margin_end=12)
        self.message_search.connect('search-changed', lambda *_: self.render_threads())
        threads.append(self.message_search)
        scroll = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.threads = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.threads.connect('row-selected', self.thread_selected)
        self.threads.add_css_class('navigation-sidebar')
        scroll.set_child(self.threads)
        threads.append(scroll)
        self.message_hint = inset(label('Open Messages to load conversations.', 'dim-label', wrap=True), 12)
        self.message_hint.add_css_class('caption')
        threads.append(self.message_hint)
        self.message_more = Gtk.Button(label='More conversations', visible=False, margin_start=12, margin_end=12)
        self.message_more.connect('clicked',lambda *_: self.load_messages(offset=self.message_pages.get(None),append=True))
        threads.append(self.message_more)
        self.message_split.set_sidebar(Adw.NavigationPage.new(threads, 'Messages'))
        chat = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.chat_title = Adw.WindowTitle(title='Choose a conversation')
        chat_header = Adw.HeaderBar(title_widget=self.chat_title)
        chat_header.set_show_end_title_buttons(False)
        chat_header.set_show_start_title_buttons(False)
        chat.append(chat_header)
        self.message_scroll = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.message_bubbles = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12,
                                       margin_start=18, margin_end=18, margin_top=18, margin_bottom=18)
        self.message_scroll.set_child(self.message_bubbles)
        chat.append(self.message_scroll)
        self.conversation_more = Gtk.Button(label='Load older messages', visible=False)
        self.conversation_more.connect('clicked',lambda *_: self.load_messages(address=self.selected_address,offset=self.message_pages.get(self.selected_address),append=True))
        chat.append(self.conversation_more)
        self.composer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        self.composer.add_css_class('composer')
        self.sms_address = Gtk.Entry(placeholder_text='Phone number', tooltip_text='Recipient phone number')
        self.composer.append(self.sms_address)
        body = Gtk.Box(spacing=10)
        body_scroll = Gtk.ScrolledWindow(min_content_height=54, max_content_height=120, propagate_natural_height=True, hexpand=True)
        self.sms_body = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR, top_margin=10, bottom_margin=10, left_margin=12, right_margin=12)
        self.sms_body.set_tooltip_text('Message text')
        body_scroll.set_child(self.sms_body)
        body.append(body_scroll)
        self.sms_button = icon_button('send-to-symbolic', 'Send SMS', self.send_message, 'suggested-action')
        body.append(self.sms_button)
        self.composer.append(body)
        self.send_hint = label('SMS sends may incur carrier charges.', 'dim-label', wrap=True)
        self.send_hint.add_css_class('caption')
        self.composer.append(self.send_hint)
        chat.append(self.composer)
        self.message_split.set_content(Adw.NavigationPage.new(chat, 'Conversation'))

    def build_calls(self):
        calls = self.page('calls', 'Calls', 'call-start-symbolic')
        self.heading(calls, 'Phone calls', 'Enable Call management on your phone. Phone speakerphone controls also need the optional Phone app setup in Android Unity → Features.')
        cg = self.group(calls, 'Current calls')
        self.call_row = Adw.ActionRow(title='No active call')
        self.call_phone_button = Gtk.Button(label='Choose call phone', valign=Gtk.Align.CENTER)
        self.call_phone_button.connect('clicked', self.choose_call_phone)
        self.call_row.add_suffix(self.call_phone_button)
        cg.add(self.call_row)
        self.active_calls = CallPanel(self)
        calls.append(self.active_calls)
        self.call_popup_button = Gtk.Button(label='Show call controls', visible=False)
        self.call_popup_button.connect('clicked', lambda *_: self.show_call_popup(force=True))
        calls.append(self.call_popup_button)
        cg = self.group(calls, 'Computer call audio')
        self.call_audio_row = Adw.ActionRow(title='Audio on phone', subtitle='PC audio requires Bluetooth hands-free support and a connected phone.')
        cg.add(self.call_audio_row)
        self.call_audio_button = Gtk.Button(label='Use computer', valign=Gtk.Align.CENTER)
        self.call_audio_button.connect('clicked', self.desktop_call_audio)
        self.call_audio_row.add_suffix(self.call_audio_button)
        self.call_audio_refresh = icon_button('view-refresh-symbolic', 'Refresh call audio devices', self.refresh_call_audio_devices, 'flat')
        self.call_audio_row.add_suffix(self.call_audio_refresh)
        self.call_audio_mute = Gtk.Button(label='Mute microphone', valign=Gtk.Align.CENTER, visible=False)
        self.call_audio_mute.connect('clicked', lambda *_: self.execute('call_control', 'audio_mute', {'muted': not self.call_audio_muted}))
        self.call_audio_mute_row = Adw.ActionRow(title='Computer microphone', visible=False)
        self.call_audio_mute_row.add_suffix(self.call_audio_mute)
        self.call_output = Adw.ComboRow(title='Call output', model=Gtk.StringList.new(['System default']))
        self.call_microphone = Adw.ComboRow(title='Call microphone', model=Gtk.StringList.new(['System default']))
        self.call_output.set_subtitle('Follows sound settings. Select headphones for listening.')
        self.call_microphone.set_subtitle('A Bluetooth microphone uses hands-free mode and may reduce playback quality.')
        for field, row in [('output', self.call_output), ('microphone', self.call_microphone)]:
            row.set_subtitle_lines(2)
            row.connect('notify::selected', self.select_call_audio_device, field)
            cg.add(row)
        cg.add(self.call_audio_mute_row)
        dial = self.group(calls, 'Dial a number')
        self.call_number = Gtk.Entry(placeholder_text='Phone number, including country code', input_purpose=Gtk.InputPurpose.PHONE)
        dial.add(self.call_number)
        keypad = Gtk.Grid(column_spacing=6, row_spacing=6, column_homogeneous=True)
        for index, tone in enumerate('123456789*0#'):
            button = Gtk.Button(label=tone)
            button.connect('clicked', lambda _, t=tone: self.call_number.set_text(self.call_number.get_text() + t))
            keypad.attach(button, index % 3, index // 3, 1, 1)
        dial.add(keypad)
        self.dial_button = self.button(dial, 'Start call', 'Call', self.dial_call)
        self.call_number.connect('activate', self.dial_call)
        contacts = self.group(calls, 'Contacts')
        self.contact_query = Gtk.SearchEntry(placeholder_text='Search name or phone number')
        self.contact_query.connect('activate', lambda *_: self.load_contacts())
        contacts.add(self.contact_query)
        self.contact_search_button = self.button(contacts, 'Search your phone', 'Search', lambda *_: self.load_contacts())
        self.call_contacts = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        contacts.add(self.call_contacts)
        self.contact_more = Gtk.Button(label='More contacts', visible=False)
        self.contact_more.connect('clicked', lambda *_: self.load_contacts(True))
        contacts.add(self.contact_more)
        recent = self.group(calls, 'Recent calls', 'Calls from the past 90 days. History is managed on your phone.')
        self.history_refresh = self.button(recent, 'Call history', 'Refresh', lambda *_: self.load_call_history())
        self.call_history = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        recent.add(self.call_history)
        self.history_more = Gtk.Button(label='More calls', visible=False)
        self.history_more.connect('clicked', lambda *_: self.load_call_history(True))
        recent.add(self.history_more)

    def dial_call(self, *_):
        number = self.call_number.get_text().strip()
        if not number:
            self.error('Enter a phone number.'); return
        self.execute('call_control', 'dial', {'number': number}, done=lambda result: self.toasts.add_toast(Adw.Toast(title='Call requested on phone.')) if result else None)

    def call_entry(self, box, name, number, subtitle=''):
        row = Adw.ActionRow(title=name or number or 'Unknown caller', subtitle=' · '.join(filter(None, [number if name else '', subtitle])))
        row.set_subtitle_lines(2)
        button = Gtk.Button(label='Select', valign=Gtk.Align.CENTER)
        button.connect('clicked', lambda *_: self.call_number.set_text(number))
        row.add_suffix(button); box.append(row)

    def load_contacts(self, more=False):
        query = self.contact_query.get_text().strip()
        if more and self.contacts_query != query: more = False
        offset = self.contacts_next if more else 0
        self.contacts_query = query
        def loaded(result):
            if result is None or query != self.contact_query.get_text().strip(): return
            if not more: self.clear(self.call_contacts)
            for contact in result.get('contacts', []): self.call_entry(self.call_contacts, contact.get('name'), contact.get('number', ''))
            self.contacts_next = result.get('next_offset')
            self.contact_more.set_visible(self.contacts_next is not None)
        self.execute('call_control', 'contacts', {'query': query, 'offset': offset or 0}, done=loaded)

    def load_call_history(self, more=False):
        self.history_loaded = True
        offset = self.history_next if more else 0
        def loaded(result):
            if result is None: return
            if not more: self.clear(self.call_history)
            for call in result.get('calls', []):
                direction = {1:'Incoming', 2:'Outgoing', 3:'Missed', 4:'Voicemail', 5:'Rejected', 6:'Blocked', 7:'Answered elsewhere'}.get(call.get('type'), 'Call')
                self.call_entry(self.call_history, call.get('name'), call.get('number', ''), f"{direction} · {timestamp(call.get('timestamp'), True)}")
            self.history_next = result.get('next_offset')
            self.history_more.set_visible(self.history_next is not None)
        self.execute('call_control', 'history', {'offset': offset or 0}, done=loaded)

    def render_calls(self, state, connected=True):
        control = state.get('call_control', {})
        previous = self.call_control_state
        if previous.get('reconnecting') and not control.get('reconnecting') and previous.get('address') == control.get('address'):
            # The service owns fresh IDs after restart. Carry only display
            # elapsed time across an unambiguous match, never the old action ID.
            old_calls, new_calls = previous.get('calls', []), control.get('calls', [])
            for call in new_calls:
                candidates = [old for old in old_calls if old.get('number') == call.get('number')]
                peers = [new for new in new_calls if new.get('number') == call.get('number')]
                if len(candidates) == len(peers) == 1:
                    clock = self.call_clock.get((self.path, candidates[0]['id']))
                    if clock:
                        self.call_clock[(self.path, call['id'])] = (call.get('connect_time') or 0, clock[1], clock[2])
        self.call_uses_hfp = control.get('backend') == 'bluetooth_hfp'
        self.call_phone_address = control.get('address')
        self.call_phone_button.set_sensitive(self.enabled('call_control'))
        self.call_phone_button.set_visible(self.call_uses_hfp)
        calls = control.get('calls', [])
        old = state.get('call_notifications', {})
        self.call_row.set_title(f'{len(calls)} current call(s)' if calls else {'ringing':'Incoming call', 'offhook':'Call in progress'}.get(old.get('state'), 'No active call'))
        self.call_row.set_subtitle(control.get('error') or ('' if calls else (old.get('number') or self.capabilities.get('call_control', {}).get('reason', ''))))
        self.call_row.set_subtitle_lines(3)
        self.call_control_state = control
        if self.call_audio_waiting:
            device, generation, address = self.call_audio_waiting
            if self.call_uses_hfp or device != self.path or generation != self.generation or not self.enabled('call_control') or not any(c.get('state') == 'active' for c in calls):
                self.cancel_call_audio_wait()
            elif control.get('route') == 'bluetooth':
                self.cancel_call_audio_wait()
                self.start_call_audio(address)
        self.update_call_popup(control, state.get('call_audio', {}), connected)
        audio = state.get('call_audio', {})
        active = audio.get('state') in ('active', 'waiting')
        self.call_audio_active = active
        self.call_audio_muted = audio.get('muted', False)
        if active and not self.call_audio_selection_pending:
            self.call_audio_selection = {field: audio.get(field, '') for field in ('output', 'microphone')}
            self.populate_call_audio_devices(self.call_audio_catalog)
        self.call_audio_button.set_label('Stop computer audio' if active else 'Use computer')
        self.call_audio_button.set_sensitive(self.enabled('call_control') and not control.get('reconnecting') and any(c.get('state') == 'active' for c in calls))
        self.call_audio_row.set_title(('Waiting for call audio' if audio.get('state') == 'waiting' else 'Audio on computer') if active else f"Audio: {control.get('route', 'phone')}")
        self.call_audio_row.set_subtitle(audio.get('error') or ('Device changes apply during the call.' if active else 'Choose output and microphone, then use computer audio.'))
        self.call_audio_row.set_subtitle_lines(3)
        self.call_audio_mute.set_visible(active)
        self.call_audio_mute_row.set_visible(active)
        self.call_audio_mute.set_label('Unmute microphone' if self.call_audio_muted else 'Mute microphone')
        self.call_audio_mute.set_sensitive(self.enabled('call_control') and not control.get('reconnecting'))
        self.call_audio_refresh.set_sensitive(self.enabled('call_control') and not self.call_audio_devices_pending)
        for row in (self.call_output, self.call_microphone): row.set_sensitive(self.enabled('call_control') and not control.get('reconnecting') and not self.call_audio_selection_pending)
        for button in (self.dial_button, self.contact_search_button, self.history_refresh, self.contact_more, self.history_more): button.set_sensitive(self.enabled('call_control'))
        if not self.enabled('call_control'):
            self.history_loaded = False
            self.clear(self.call_contacts); self.clear(self.call_history)
            self.contact_more.set_visible(False); self.history_more.set_visible(False)

    def desktop_call_audio(self, *_):
        if self.call_audio_active:
            self.execute('call_control','audio_stop',done=lambda result:self.execute('call_control','route',{'route':'phone'}) if result else None); return
        if self.call_uses_hfp and self.call_phone_address:
            address = self.call_phone_address
            self.execute('call_control','route',{'route':'desktop'},done=lambda result: self.start_call_audio(address) if result is not None else None)
            return
        def devices(result):
            if result is None: return
            phones = result.get('devices', [])
            self.populate_call_audio_devices(result)
            if not phones:
                self.error('No phone hands-free audio gateway is connected. Pair the phone for calls in Bluetooth settings.'); return
            if len(phones) == 1:
                address = phones[0]['address']
                self.execute('call_control', 'route', {'route': 'desktop'}, done=lambda result:
                    self.start_call_audio(address) if result is not None else None)
                return
            dialog = Adw.MessageDialog(transient_for=self.call_popup or self, heading='Choose the paired phone', body='Choose the phone used by this Unity connection. Enable calls for this computer in the phone’s Bluetooth settings.')
            choices = Gtk.DropDown(model=Gtk.StringList.new([f"{p['name']} · {p['address']}" for p in phones]))
            dialog.set_extra_child(choices); dialog.add_response('cancel','Cancel'); dialog.add_response('connect','Use computer'); dialog.set_response_appearance('connect',Adw.ResponseAppearance.SUGGESTED)
            path, generation = self.path, self.generation
            def response(_, answer):
                if answer != 'connect' or self.path != path or self.generation != generation: return
                address = phones[choices.get_selected()]['address']
                def routed(result):
                    if result is None: return
                    # Android acknowledges the request before its route callback. Refresh
                    # the snapshot, then let the user retry if Bluetooth has not settled.
                    self.execute('call_control','refresh',done=lambda _: self.start_call_audio(address))
                self.execute('call_control','route',{'route':'desktop'},done=routed)
            dialog.connect('response',response); dialog.present()
        self.execute('call_control','audio_devices',done=devices)

    def choose_call_route(self, route):
        if route == 'desktop':
            if not self.call_audio_active:
                self.desktop_call_audio()
            return
        self.cancel_call_audio_wait()
        def routed(result):
            if result is not None:
                self.execute('call_control', 'route', {'route': route})
        self.execute('call_control', 'audio_stop', done=routed)

    def call_elapsed(self, call):
        if call.get('state') not in ('active', 'held', 'disconnecting'):
            return ''
        key = (self.path, call['id'])
        connected = call.get('connect_time') or 0
        if key not in self.call_clock or (connected and self.call_clock[key][0] != connected):
            elapsed = max(0, (time.time() * 1000 - connected) / 1000) if connected else 0
            self.call_clock[key] = (connected, time.monotonic(), elapsed)
        _, observed, elapsed = self.call_clock[key]
        return call_duration(elapsed + time.monotonic() - observed)

    def tick_calls(self):
        self.active_calls.tick()
        if self.call_popup_panel:
            self.call_popup_panel.tick()
        return True

    def update_call_popup(self, control, audio, connected=True):
        available = connected and self.enabled('call_control')
        calls = control.get('calls', []) if available else []
        ids = {(self.path, c['id']) for c in calls}
        self.call_popup_button.set_visible(bool(calls))
        self.active_calls.render(control, audio)
        if calls:
            if self.call_popup_empty_source is not None:
                GLib.source_remove(self.call_popup_empty_source); self.call_popup_empty_source = None
            self.call_clock = {key: value for key, value in self.call_clock.items() if key in ids}
            self.call_popup_dismissed.intersection_update(ids)
            self.call_popup_ids = ids
            self.popup_has_calls = True
            if self.call_timer_source is None:
                self.call_timer_source = GLib.timeout_add_seconds(1, self.tick_calls)
            self.show_call_popup()
            if self.call_popup_panel:
                self.call_popup_status.set_label(control.get('error') or 'Reconnecting Bluetooth call controls…')
                self.call_popup_status.set_visible(bool(control.get('reconnecting')))
                self.call_popup_panel.render(control, audio)
                self.call_popup.set_default_size(460, min(760, 380 + 140 * (len(calls) - 1) + (160 if audio.get('state') in ('active', 'waiting') else 0)))
        else:
            # HFP and asynchronous refreshes can briefly report no calls. Keep
            # the same window through that gap, but clear all actionable data.
            # Access loss/disconnection bypasses this confirmation immediately.
            if available and self.popup_has_calls:
                if self.call_popup_panel:
                    self.call_popup_panel.render(control, audio)
                    self.call_popup_status.set_label(control.get('error') or 'Updating call status…')
                    self.call_popup_status.set_visible(True)
                if self.call_popup_empty_source is None:
                    self.call_popup_empty_source = GLib.timeout_add(2500, self.confirm_call_popup_end)
            else:
                self.close_call_popup()

    def confirm_call_popup_end(self):
        self.call_popup_empty_source = None
        if not self.call_control_state.get('calls') or not self.enabled('call_control'):
            self.close_call_popup()
        return False

    def close_call_popup(self):
        if self.call_popup_empty_source is not None:
            GLib.source_remove(self.call_popup_empty_source); self.call_popup_empty_source = None
        if self.call_timer_source is not None:
            GLib.source_remove(self.call_timer_source); self.call_timer_source = None
        self.call_clock.clear(); self.call_popup_dismissed.clear(); self.call_popup_ids.clear()
        if self.call_popup:
            self.call_popup.destroy()
        self.call_popup = self.call_popup_panel = self.call_popup_toasts = self.call_popup_status = None
        if self.popup_has_calls and (self.popup_only or not self.get_visible()):
            def close_if_finished():
                if (self.call_popup is None and (not self.call_control_state.get('calls') or not self.enabled('call_control'))
                        and not self.get_application().keep_in_background(self)):
                    self.destroy()
                return False
            GLib.idle_add(close_if_finished)
        self.popup_has_calls = False

    def show_call_popup(self, force=False):
        ids = {(self.path, c['id']) for c in self.call_control_state.get('calls', [])}
        if not ids or not self.enabled('call_control') or (not force and ids.issubset(self.call_popup_dismissed)):
            return
        if force:
            self.call_popup_dismissed.clear()
        if self.call_popup is None:
            self.call_popup = Adw.Window(application=self.get_application(), title='Unity · Call controls', default_width=460, default_height=380)
            content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
            self.call_popup_toasts = Adw.ToastOverlay(); self.call_popup_toasts.set_child(content)
            self.call_popup.set_content(self.call_popup_toasts)
            header = Adw.HeaderBar(title_widget=Adw.WindowTitle(title='Call controls', subtitle=self.devices.get(self.path, {}).get('Name', 'Your phone')))
            open_app = Gtk.Button(label='Open app')
            open_app.connect('clicked', lambda *_: self.open_calls_page())
            header.pack_start(open_app); content.append(header)
            self.call_popup_status = inset(label('Updating call status…', wrap=True, visible=False), 16)
            content.append(self.call_popup_status)
            self.call_popup_panel = CallPanel(self, computer_selectors=True)
            scroll = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
            scroll.set_child(inset(self.call_popup_panel, 16)); content.append(scroll)
            self.call_popup_panel.render(self.call_control_state, {})
            self.populate_call_audio_devices(self.call_audio_catalog)
            self.call_popup.connect('close-request', self.dismiss_call_popup)
            self.call_popup.present()
            GLib.idle_add(lambda: (self.refresh_call_audio_devices(), False)[1])
        elif force:
            self.call_popup.present()

    def dismiss_call_popup(self, *_):
        self.call_popup_dismissed.update(self.call_popup_ids)
        self.call_popup = self.call_popup_panel = self.call_popup_toasts = self.call_popup_status = None
        if self.popup_only or not self.get_visible():
            if self.call_popup_empty_source is not None:
                GLib.source_remove(self.call_popup_empty_source); self.call_popup_empty_source = None
            if self.call_timer_source is not None:
                GLib.source_remove(self.call_timer_source); self.call_timer_source = None
            if not self.get_application().keep_in_background(self):
                self.destroy()
        return False

    def close_main_call_window(self, *_):
        if self.get_application().keep_in_background(self):
            self.set_visible(False)
            return True
        if self.call_popup and self.call_popup.get_visible():
            self.set_visible(False)
            return True
        if self.call_timer_source is not None:
            GLib.source_remove(self.call_timer_source); self.call_timer_source = None
        return False

    def open_calls_page(self):
        self.popup_only = False
        self.nav.select_row(self.pages['calls'])
        self.present()

    def choose_call_phone(self, *_):
        def loaded(result):
            if result is None: return
            phones = result.get('devices', [])
            if not phones:
                self.error(result.get('error') or 'Pair your phone for Bluetooth calls, install/start oFono and enable its WirePlumber HFP backend.'); return
            dialog = Adw.MessageDialog(transient_for=self, heading='Choose your call phone', body='Choose the phone used by this Unity connection. Enable Calls for this computer in Android Bluetooth settings. Your usual Phone app stays in use.')
            choices = Gtk.DropDown(model=Gtk.StringList.new([f"{p.get('name') or 'Phone'} · {p['address']}" for p in phones]))
            dialog.set_extra_child(choices); dialog.add_response('cancel', 'Cancel'); dialog.add_response('connect', 'Connect calls')
            dialog.set_response_appearance('connect', Adw.ResponseAppearance.SUGGESTED)
            path, generation = self.path, self.generation
            def response(_, answer):
                if answer == 'connect' and self.path == path and self.generation == generation:
                    self.execute('call_control', 'hfp_select', {'address': phones[choices.get_selected()]['address']})
            dialog.connect('response', response); dialog.present()
        self.execute('call_control', 'hfp_devices', done=loaded, quiet=True)

    def start_call_audio(self, address):
        if not self.call_uses_hfp and self.call_control_state.get('route') != 'bluetooth':
            self.cancel_call_audio_wait()
            self.call_audio_waiting = (self.path, self.generation, address)
            def expired():
                self.call_audio_wait_timeout = None
                self.call_audio_waiting = None
                self.error('The phone has not switched to computer Bluetooth audio. Check that Calls is enabled for this computer, then try again.')
                return False
            self.call_audio_wait_timeout = GLib.timeout_add_seconds(5, expired)
            return
        def started(result):
            if result is None: self.execute('call_control','route',{'route':'phone'},quiet=True)
        self.execute('call_control','audio_start',{'address':address, **self.call_audio_selection},done=started)

    def cancel_call_audio_wait(self):
        self.call_audio_waiting = None
        if self.call_audio_wait_timeout is not None:
            GLib.source_remove(self.call_audio_wait_timeout)
            self.call_audio_wait_timeout = None

    def populate_call_audio_devices(self, result):
        self.call_audio_catalog = result
        self.call_audio_updating = True
        try:
            rows = [('output', 'outputs', self.call_output), ('microphone', 'microphones', self.call_microphone)]
            if self.call_popup_panel:
                rows += [('output', 'outputs', self.call_popup_panel.output), ('microphone', 'microphones', self.call_popup_panel.microphone)]
            for field, key, row in rows:
                choices = [{'id': '', 'name': 'System default'}] + result.get(key, [])
                selected = self.call_audio_selection[field]
                if selected not in [c['id'] for c in choices]:
                    choices.append({'id': selected, 'name': 'Unavailable · ' + selected})
                self.call_audio_options[field] = [c['id'] for c in choices]
                names = [c['name'] for c in choices]
                model = row.get_model()
                if model is None or model.get_n_items() != len(names) or any(model.get_string(i) != name for i, name in enumerate(names)):
                    row.set_model(Gtk.StringList.new(names))
                index = self.call_audio_options[field].index(selected)
                if row.get_selected() != index:
                    row.set_selected(index)
        finally:
            self.call_audio_updating = False

    def refresh_call_audio_devices(self, *_, automatic=False):
        if not self.enabled('call_control') or self.call_audio_devices_pending: return
        target = (self.path, self.generation)
        now = time.monotonic()
        previous = self.call_audio_devices_refresh
        # Rendering an operation's own completion also reloads the visible page.
        # Avoid turning that refresh into an endless chain of audio_devices calls.
        if automatic and previous and previous[:2] == target and now - previous[2] < 15:
            return
        self.call_audio_devices_refresh = (*target, now)
        self.call_audio_devices_pending = True
        self.call_audio_refresh.set_sensitive(False)
        def loaded(result):
            self.call_audio_devices_pending = False
            self.call_audio_refresh.set_sensitive(self.enabled('call_control'))
            if result is not None: self.populate_call_audio_devices(result)
        self.execute('call_control', 'audio_devices', done=loaded, quiet=True)

    def select_call_audio_device(self, row, _, field):
        if self.call_audio_updating: return
        index = row.get_selected()
        if index >= len(self.call_audio_options[field]): return
        self.call_audio_selection[field] = self.call_audio_options[field][index]
        # Replacing an Adw.ComboRow model during its selection notification can
        # recurse inside GTK. Synchronize the other surface after this event.
        GLib.idle_add(lambda: (self.populate_call_audio_devices(self.call_audio_catalog), False)[1])
        if not self.call_audio_active: return
        self.call_audio_selection_pending = True
        choices = [self.call_output, self.call_microphone]
        if self.call_popup_panel: choices += [self.call_popup_panel.output, self.call_popup_panel.microphone]
        for choice in choices: choice.set_sensitive(False)
        def selected(result):
            self.call_audio_selection_pending = False
            for choice in choices: choice.set_sensitive(self.enabled('call_control'))
            if result is not None:
                self.call_audio_selection = {key: result.get(key, '') for key in ('output', 'microphone')}
                self.populate_call_audio_devices(self.call_audio_catalog)
            else: self.changed(self.path)
        self.execute('call_control', 'audio_select', dict(self.call_audio_selection), done=selected)

    def draw_pad(self, area, cr, width, height):
        cr.set_source_rgba(0.5, 0.5, 0.5, 0.10)
        cr.rectangle(0, 0, width, height)
        cr.fill()
        cr.set_source_rgba(0.5, 0.5, 0.5, 0.8)
        cr.move_to(20, height / 2)
        cr.show_text('Drag to swipe on phone')

    def drag_begin(self, gesture, x, y):
        self.drag_start = (x, y)

    def drag_end(self, gesture, dx, dy):
        w, h = max(1, self.pad.get_width()), max(1, self.pad.get_height())
        x, y = self.drag_start
        clamp = lambda n: max(0.0, min(1.0, n))
        self.execute('remote_input', 'tap' if abs(dx) + abs(dy) < 5 else 'swipe',
            {'x': clamp(x / w), 'y': clamp(y / h), 'end_x': clamp((x + dx) / w), 'end_y': clamp((y + dy) / h)})

    def navigate(self, box, row):
        if not row:
            return
        self.stack.set_visible_child_name(row.page_name)
        self.title.set_title(row.page_title)
        self.split.set_show_content(True)
        if hasattr(self, 'client'):
            self.load_visible()

    def load_visible(self):
        page = self.stack.get_visible_child_name()
        if page == 'photos' and self.enabled('photos'):
            self.load_photos()
            self.pump_previews()
        elif page == 'calls' and self.enabled('call_control'):
            if not self.history_loaded: self.load_call_history()
            self.refresh_call_audio_devices(automatic=True)
        elif page == 'messages' and self.enabled('sms'):
            self.load_messages()

    def error(self, message):
        if self.get_application().keep_in_background(self) and not self.get_visible():
            self.present()
        self.access_check_pending = False
        if 'NotAuthorized' in message:
            self.authorized = False
            self.render({})
            self.render_firefox('{}')
            self.show_access_prompt()
            return
        if 'ServiceUnknown' in message or 'NameHasNoOwner' in message:
            message = 'Unity daemon is stopped. Start unityd with systemctl --user start unityd.'
        if any(s in message for s in ('NotAuthorized', 'daemon stopped', 'daemon is stopped', 'Disconnected')):
            self.authorized = False
            self.render({})
            self.render_firefox('{}')
        if self.call_popup_toasts and self.call_popup and self.call_popup.get_visible():
            self.call_popup_toasts.add_toast(Adw.Toast(title=message))
        self.banner.set_title(message)
        self.banner.set_button_label('Retry')
        self.banner.set_revealed(True)

    def show_access_prompt(self):
        self.access_row.set_subtitle('Allow Unity to show phone data and Firefox page summaries, and control supported features.')
        self.access_button.set_visible(True)
        self.access_button.set_sensitive(bool(self.bus_name))
        self.banner.set_title('Allow Unity to access your devices and browser.')
        self.banner.set_button_label('Allow access')
        self.banner.set_revealed(True)

    def banner_action(self, *_):
        if self.banner.get_button_label() == 'Allow access' and self.bus_name and not self.authorized:
            self.authorize_this_app()
        else:
            self.refresh()

    def authorize_this_app(self, *_):
        if not self.bus_name or self.authorized:
            return
        dialog = Adw.AlertDialog(heading='Allow Unity access?',
            body='Unity can display phone notifications, messages, files and Firefox page summaries, and use controls such as sending SMS, playing media and restoring Firefox pages. Access applies to this app session.')
        dialog.add_response('cancel', 'Cancel')
        dialog.add_response('allow', 'Allow access')
        dialog.set_response_appearance('allow', Adw.ResponseAppearance.SUGGESTED)
        name = self.bus_name
        def respond(dialog, response):
            if response != 'allow' or name != self.bus_name:
                return
            installed = Path(__file__).resolve().parents[2] / 'libexec/unity/unityctl'
            helper = str(installed) if installed.is_file() else shutil.which('unityctl')
            if not helper:
                self.access_row.set_subtitle(f'Install unityctl or run: unityctl authorize {name}')
                return
            self.access_button.set_sensitive(False)
            try:
                process = Gio.Subprocess.new([helper, 'authorize', name], Gio.SubprocessFlags.STDOUT_SILENCE | Gio.SubprocessFlags.STDERR_PIPE)
                def finished(process, result):
                    try:
                        process.communicate_utf8_finish(result)
                        if not process.get_successful():
                            raise RuntimeError('Approval failed. Check that unityctl and unityd are installed together.')
                        self.refresh()
                    except (GLib.Error, RuntimeError) as e:
                        self.access_row.set_subtitle(str(e))
                        self.access_button.set_sensitive(True)
                process.communicate_utf8_async(None, None, finished)
            except GLib.Error as e:
                self.access_row.set_subtitle(e.message)
                self.access_button.set_sensitive(True)
        dialog.connect('response', respond)
        dialog.present(self)

    def changed(self, path):
        if path.startswith('screen-frame:'):
            if path.removeprefix('screen-frame:') == self.path:
                self.fetch_screen_frame()
            return
        if path == 'daemon-restarted':
            self.requested_access = self.authorized = False
            self.access_check_pending = False
            self.bus_name = None
            self.operations.clear()
            self.render({})
            self.render_firefox('{}')
            path = ROOT
        if path == ROOT and self.pairing_active and self.authorized:
            self.client.manager('PairingInfo', done=self.pairing)
        if path in self.operations:
            self.client.call(path, 'org.unityconnect.Operation1', 'GetState', done=lambda s: self.operation(path, json.loads(s)),
                             failed=lambda message: self.operation_error(path, message))
        if '/operations/' in path:
            return
        if not self.pending_refresh:
            self.pending_refresh = True
            GLib.idle_add(self.refresh)

    def refresh(self):
        if self.access_check_pending:
            return False
        self.pending_refresh = False
        self.access_check_pending = True
        if not self.requested_access:
            self.requested_access = True
            self.client.manager('RequestAccess', '(sas)', ('Unity GTK', ['read', 'control']), self.access_requested)
        else:
            self.client.manager('GetAccessStatus', done=self.access_status)
        return False

    def access_requested(self, name):
        self.bus_name = name
        self.client.manager('GetAccessStatus', done=self.access_status)

    def access_status(self, payload):
        self.access_check_pending = False
        status = json.loads(payload)
        approved = status.get('approved') and all(s in status.get('scopes', []) for s in ('read', 'control'))
        if self.authorized and not approved:
            self.authorized = False
            self.render({})
        self.authorized = bool(approved)
        if self.authorized:
            self.fetch_firefox()
        else:
            self.render_firefox('{}')
        self.access_button.set_visible(not self.authorized)
        if self.authorized:
            self.access_row.set_subtitle('Unity has access for this app session.')
            self.banner.set_button_label('Retry')
            self.banner.set_revealed(False)
        else:
            self.show_access_prompt()
        self.render_capabilities()
        self.client.manager('ListDevices', done=self.listed)
        # Approval can arrive while GetAccessStatus is in flight. Preserve the
        # signal-triggered refresh so an earlier unapproved snapshot cannot win.
        if self.pending_refresh:
            GLib.idle_add(self.refresh)

    def listed(self, paths):
        self.properties_revision += 1
        revision = self.properties_revision
        self.devices = {p: self.devices.get(p, {}) for p in paths}
        if self.popup_device:
            target = '/org/unityconnect/devices/d_' + self.popup_device
            if target in paths:
                if self.path != target:
                    self.generation += 1
                    self.render({})
                self.path = target
                self.popup_device = None
        if not paths:
            self.path = None
            self.capabilities = {}
            self.updating_dropdown = True
            self.device_dropdown.set_model(Gtk.StringList.new(['No paired devices']))
            self.updating_dropdown = False
            self.render({})
            self.banner.set_title('No paired devices. Open Settings to pair a phone.')
            self.banner.set_revealed(True)
            return
        for path in paths:
            self.client.call(path, 'org.freedesktop.DBus.Properties', 'GetAll', '(s)', ('org.unityconnect.Device1',),
                lambda props, p=path: self.properties(p, props) if revision == self.properties_revision else None)

    def properties(self, path, props):
        if path not in self.devices:
            return
        self.devices[path] = {k: v.unpack() if isinstance(v, GLib.Variant) else v for k, v in props.items()}
        paths = list(self.devices)
        if self.path not in paths:
            self.path = paths[0]
        self.updating_dropdown = True
        self.device_dropdown.set_model(Gtk.StringList.new([self.devices[p].get('Name', 'Loading…') for p in paths]))
        self.device_dropdown.set_selected(paths.index(self.path))
        self.updating_dropdown = False
        if path == self.path:
            self.capabilities = {c['id']: c for c in json.loads(props.get('CapabilitiesJson', '[]'))}
            if not props.get('Connected'):
                self.render({})
            elif self.authorized:
                generation = self.generation
                self.state_request_revision += 1
                revision = self.state_request_revision
                self.client.call(path, 'org.unityconnect.Device1', 'GetState', done=lambda s:
                    self.render(json.loads(s)) if self.path == path and generation == self.generation
                    and revision == self.state_request_revision
                    and self.authorized and self.devices.get(path, {}).get('Connected') else None)
            self.render_capabilities()
            if self.open_page and self.open_page[0] == path:
                self.nav.select_row(self.pages[self.open_page[1]])
                self.open_page = None

    def device_selected(self, dropdown, _):
        if self.updating_dropdown:
            return
        paths = list(self.devices)
        index = dropdown.get_selected()
        if index < len(paths) and paths[index] != self.path:
            self.path = paths[index]
            self.capabilities = {}
            self.call_audio_selection = {'output': '', 'microphone': ''}
            self.call_audio_selection_pending = self.call_audio_devices_pending = False
            self.populate_call_audio_devices({})
            self.render({})
            self.refresh()

    def enabled(self, feature):
        return bool(self.authorized and self.path and self.devices.get(self.path, {}).get('Connected') and
                    self.capabilities.get(feature, {}).get('status') in ('available', 'user_action_required'))

    def render_capabilities(self):
        signature = json.dumps([self.authorized, self.capabilities], sort_keys=True)
        if signature != self.cap_signature:
            self.cap_signature = signature
            for row in self.cap_rows:
                self.cap_group.remove(row)
            self.cap_rows.clear()
            for feature, cap in self.capabilities.items():
                row = Adw.SwitchRow(title='Phone and laptop audio' if feature == 'audio' else feature.replace('_', ' ').title(), subtitle=cap['reason'] or cap['status'].replace('_', ' '))
                row.set_subtitle_lines(3)
                row.set_active(cap['status'] != 'disabled')
                row.set_sensitive(self.authorized and cap['status'] in ('available', 'disabled', 'user_action_required'))
                row.connect('notify::active', lambda row, _, f=feature: self.client.call(self.path,
                    'org.unityconnect.Device1', 'SetFeatureEnabled', '(sb)', (f, row.get_active())))
                self.cap_group.add(row)
                self.cap_rows.append(row)
        controls = [(self.find_button, 'find'), (self.url_button, 'urls'),
                    (self.sms_button, 'sms'), (self.new_message, 'sms'), (self.message_refresh, 'sms'),
                    (self.photos_button, 'photos'), (self.browse_button, 'browsing'), (self.remote_button, 'remote_input'),
                    (self.lock_phone_button, 'lock'), (self.share_desktop_button, 'screen'), (self.view_phone_button, 'screen'),
                    (self.pad, 'remote_input'), (self.ringer_button, 'ringer'), (self.hotspot_button, 'hotspot')]
        for widget, feature in controls:
            widget.set_sensitive(self.enabled(feature))
        self.routines_group.set_visible(self.capabilities.get('samsung_routines', {}).get('status') in
                                        ('available', 'disabled', 'permission_required', 'user_action_required'))
        for button in self.routine_buttons:
            button.set_sensitive(self.enabled('samsung_routines'))
        self.render_phone_connectivity(self.phone_connectivity_state)
        self.discovery_button.set_sensitive(self.authorized)
        self.unpair_button.set_sensitive(bool(self.authorized and self.path))
        groups = {'notifications': ['notifications'], 'messages': ['sms'], 'calls': ['call_notifications', 'call_control'],
                  'files': ['files', 'browsing'], 'photos': ['photos'], 'media': ['media', 'desktop_media', 'sound', 'audio', 'ringer'],
                  'remote': ['remote_input', 'find', 'lock', 'screen']}
        for page, features in groups.items():
            visible = any(self.capabilities.get(f, {}).get('status') in ('available', 'user_action_required') for f in features)
            if page == 'media' and 'audio' in self.capabilities:
                visible = True
            if page == 'files':
                visible = True
            self.pages[page].set_visible(visible)
            if not visible and self.stack.get_visible_child_name() == page:
                self.nav.select_row(self.pages['overview'])
        self.update_media_controls()
        self.render_sound(self.sound_state)
        self.render_audio_route(self.audio_state)
        self.render_screen(self.screen_state)
        self.render_file_selection()
        self.get_application().update_desktop()

    @staticmethod
    def clear(box):
        while box.get_first_child():
            box.remove(box.get_first_child())

    def render(self, device):
        state = device.get('state', {})
        connected = device.get('connected', False)
        self.device_heading.set_label(device.get('name') or 'Your phone, at a glance')
        transport = state.get('transport', {})
        self.connection_value.set_label({'both': 'LAN + Bluetooth connected', 'bluetooth': 'Bluetooth connected',
            'lan': 'LAN connected'}.get(transport.get('kind'), 'Connected') if connected else 'Disconnected')
        self.render_battery(state.get('battery', {}) if connected else {})
        self.render_find_phone(state.get('find', {}) if connected else {})
        self.render_calls(state if connected and self.authorized else {}, connected)
        reports = state.get('sms', {}).get('reports', {})
        if self.last_sms_id in reports:
            self.send_hint.set_label({'submitted_to_modem':'Waiting for carrier reports.', 'sent':'Sent. Delivery report pending.', 'delivered':'Delivered.', 'failed':'Message failed to send.', 'delivery_failed':'Carrier reported delivery failure.'}.get(reports[self.last_sms_id].get('state'), 'Status unavailable.'))
        self.render_media_sources(state if connected else {})
        self.render_sound(state if connected else {})
        self.ringer_state = state.get('ringer', {}) if connected and self.authorized else {}
        self.render_phone_connectivity(state.get('phone_connectivity', {}) if connected and self.authorized else {})
        if not connected:
            self.audio_pending = False
        self.render_audio_route(state.get('audio', {}))
        self.render_screen(state.get('screen', {}))
        self.clip_receive.set_label(state.get('clipboard', {}).get('text', ''))
        self.clip_receive.set_visible(bool(self.clip_receive.get_label()))
        self.incoming_url = state.get('urls', {}).get('url')
        self.url_receive.set_subtitle(self.incoming_url or 'No shared URL')
        self.url_open.set_sensitive(bool(self.incoming_url))
        previous_received_path = self.received_path
        self.received_path = state.get('files', {}).get('received_path')
        self.file_open.set_visible(bool(self.received_path))
        if self.received_path and self.received_path != previous_received_path and not self.file_sending:
            self.file_status.set_title('File received')
            self.file_status.set_subtitle(self.received_path.rsplit('/', 1)[-1])
        self.render_notifications(state.get('notifications', {}))
        revision = state.get('sms', {}).get('revision')
        if revision is not None and revision != self.sms_revision:
            self.sms_revision = revision
            self.messages_loaded = False
        if not connected:
            self.generation += 1
            self.call_audio_selection_pending = self.call_audio_devices_pending = False
            if self.file_sending:
                self.file_send_token += 1
                self.file_sending = False
                self.file_send_error = 'Transfer interrupted. Reconnect and send the remaining files again.'
                self.file_status.set_title('Transfer interrupted')
            self.messages_loaded = self.photos_loaded = False
            self.sms_revision = None
            self.last_sms_id = None
            self.message_pages.clear()
            self.message_more.set_visible(False); self.conversation_more.set_visible(False)
            self.clear(self.call_contacts); self.clear(self.call_history)
            self.contacts_next = self.history_next = None
            self.history_loaded = False
            self.message_data = []
            self.selected_address = None
            self.clear(self.threads)
            self.clear(self.message_bubbles)
            self.message_bubbles.append(self.empty('Your conversations', 'Connect your phone to view and send messages.', 'chat-symbolic'))
            self.chat_title.set_title('Choose a conversation')
            self.message_hint.set_label('Messages are cleared while disconnected.')
            self.photo_items, self.photo_widgets, self.preview_queue = {}, {}, []
            self.gallery_copy_supported = False
            self.preview_inflight = 0
            self.clear(self.photos)
            self.photos_empty.set_visible(True)
            self.photo_status.set_label('Connect your phone to load previews.')
            self.clear(self.browser)
            self.sms_address.set_text('')
            self.sms_body.get_buffer().set_text('')
            self.call_number.set_text(''); self.contact_query.set_text('')
        else:
            self.banner.set_revealed(False)
            self.access_row.set_subtitle('Unity has access for this app session.')
        self.render_capabilities()
        self.clip_status.set_subtitle('Text syncs automatically in both directions. For Android background sending, allow Shizuku access in Unity on your phone; keep your current keyboard.'
            if connected and self.enabled('clipboard') else 'Connect and enable Clipboard to sync text automatically.')
        if connected and hasattr(self, 'client'):
            self.load_visible()

    def render_phone_connectivity(self, state):
        reporting = self.enabled('phone_connectivity')
        if state != self.phone_connectivity_state:
            self.connectivity_notice = ''
        self.phone_connectivity_state = state if reporting else {}
        state = self.phone_connectivity_state
        if not reporting or not state:
            self.hotspot_command_pending = False
            self.bluetooth_commands_pending.clear()
            self.connectivity_notice = ''
        self.connectivity_updating = True
        hotspot = state.get('hotspot_enabled')
        self.phone_hotspot.set_active(hotspot is True)
        self.phone_hotspot.set_sensitive(reporting and self.enabled('samsung_routines') and
                                      isinstance(hotspot, bool) and not self.hotspot_command_pending)
        self.phone_hotspot.set_subtitle(('On' if hotspot else 'Off') + ' · Reported by phone' if isinstance(hotspot, bool) else
            'Hotspot state unavailable · use the On and Off buttons below')
        self.hotspot_choices.set_visible(not isinstance(hotspot, bool))
        for button in (self.hotspot_on_button, self.hotspot_off_button):
            button.set_sensitive(self.enabled('samsung_routines') and not self.hotspot_command_pending)
        self.connectivity_refresh.set_sensitive(reporting)
        devices = {device['address']: device for device in state.get('devices', [])}
        for address in list(self.phone_device_rows):
            if address not in devices:
                self.phone_bluetooth_group.remove(self.phone_device_rows.pop(address))
                self.bluetooth_commands_pending.discard(address)
        for address, device in devices.items():
            row = self.phone_device_rows.get(address)
            if row is None:
                row = Adw.SwitchRow()
                row.set_use_markup(False); row.set_subtitle_lines(3); row.set_title_lines(2)
                row.connect('notify::active', lambda row, _, target=address: self.toggle_phone_bluetooth(row, target))
                self.phone_device_rows[address] = row
                self.phone_bluetooth_group.add(row)
            row.set_title(device.get('name') or 'Bluetooth device')
            row.set_subtitle(address + ' · ' + ', '.join(device.get('profiles', [])) +
                             (' · Disconnect trigger pending' if address in self.bluetooth_commands_pending else ' · Connected'))
            row.set_tooltip_text('Samsung routine keyword: UNITY_BLUETOOTH_DISCONNECT_' + address.replace(':', ''))
            row.set_active(True)
            row.set_sensitive(reporting and self.enabled('samsung_routines') and address not in self.bluetooth_commands_pending)
        self.phone_bluetooth_hint.set_visible(not devices)
        status = state.get('bluetooth_status')
        self.phone_bluetooth_hint.set_title({'permission_required': 'Allow Nearby devices on your phone', 'off': 'Phone Bluetooth is off',
            'unsupported': 'Phone Bluetooth is unavailable'}.get(status, 'No connected Bluetooth devices reported' if state else 'No phone connection report'))
        self.phone_bluetooth_hint.set_subtitle(state.get('reason') or 'Enable Phone connectivity and allow Nearby devices in Unity on your phone.')
        self.connectivity_status.set_label(self.connectivity_notice or state.get('reason', ''))
        self.connectivity_updating = False
        self.get_application().update_desktop()

    def toggle_phone_hotspot(self, row, _):
        if self.connectivity_updating:
            return
        desired = row.get_active()
        if not isinstance(self.phone_connectivity_state.get('hotspot_enabled'), bool) or desired == self.phone_connectivity_state['hotspot_enabled']:
            return
        self.request_phone_hotspot(desired)

    def request_phone_hotspot(self, desired):
        if not self.enabled('samsung_routines'):
            self.render_phone_connectivity(self.phone_connectivity_state)
            return
        self.hotspot_command_pending = True
        self.connectivity_notice = 'Sending Mobile Hotspot trigger…'
        self.render_phone_connectivity(self.phone_connectivity_state)
        def finished(result):
            self.hotspot_command_pending = False
            self.connectivity_notice = result.get('reason', 'Hotspot trigger sent; waiting for the phone state.') if result else 'Hotspot trigger failed. Check phone permissions and routines.'
            self.render_phone_connectivity(self.phone_connectivity_state)
        self.execute('samsung_routines', 'hotspot_on' if desired else 'hotspot_off', done=finished)

    def toggle_phone_bluetooth(self, row, address):
        if self.connectivity_updating or row.get_active():
            return
        self.request_bluetooth_disconnect(address)

    def request_bluetooth_disconnect(self, address):
        if address not in self.phone_device_rows or not self.enabled('samsung_routines'):
            self.render_phone_connectivity(self.phone_connectivity_state)
            return
        self.bluetooth_commands_pending.add(address)
        self.connectivity_notice = 'Sending Bluetooth disconnect trigger…'
        self.render_phone_connectivity(self.phone_connectivity_state)
        def finished(result):
            self.bluetooth_commands_pending.discard(address)
            self.connectivity_notice = result.get('reason', 'Disconnect trigger sent; waiting for the phone state.') if result else 'Disconnect trigger failed. Check the device-specific phone routine.'
            self.render_phone_connectivity(self.phone_connectivity_state)
        self.execute('samsung_routines', 'disconnect_bluetooth_device', {'address': address}, done=finished)

    def render_battery(self, battery):
        self.battery_value.set_label(f"{battery['level']}%" if battery else 'Unavailable')
        self.battery_power.set_visible(bool(battery))
        if not battery:
            self.battery_power.set_label('')
            return
        watts = battery.get('power_w')
        valid_power = isinstance(watts, (int, float)) and not isinstance(watts, bool) and math.isfinite(watts)
        self.battery_power.set_label(f'{"Charging" if watts < 0 else "Power draw"} · {abs(watts):.2f} W' if valid_power else 'Power draw unavailable')

    def render_notifications(self, notifications):
        if notifications == self.last_notifications:
            return
        self.last_notifications = notifications
        for key in list(self.notification_cards):
            if key not in notifications or self.notification_cards[key][0] != notifications[key]:
                self.notifications.remove(self.notification_cards.pop(key)[1])
        self.notification_count.set_label(f"Notifications · {len(notifications)}" if notifications else 'Notifications')
        self.notifications_empty.set_visible(not notifications)
        previous = None
        for key, item in sorted(notifications.items(), key=lambda pair: pair[1].get('timestamp', 0), reverse=True):
            if key in self.notification_cards:
                card = self.notification_cards[key][1]
                self.notifications.reorder_child_after(card, previous)
                previous = card
                continue
            card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
            card.add_css_class('unity-card')
            card.add_css_class('notification-card')
            header = Gtk.Box(spacing=10)
            header.append(Gtk.Image(icon_name='application-x-executable-symbolic'))
            app = item.get('app_name') or item.get('app', '').rsplit('.', 1)[-1]
            header.append(label(app, 'heading', hexpand=True, ellipsize=Pango.EllipsizeMode.END))
            header.append(label(timestamp(item.get('timestamp')), 'dim-label'))
            dismiss = icon_button('window-close-symbolic', 'Dismiss notification', lambda _, k=key: self.execute('notifications', 'dismiss', {'id': k}), 'flat')
            dismiss.set_sensitive(self.enabled('notifications'))
            header.append(dismiss)
            card.append(header)
            if item.get('title'):
                card.append(label(item['title'], 'heading', wrap=True, selectable=True))
            if item.get('text'):
                text = label(item['text'], wrap=True, selectable=True)
                text.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
                card.append(text)
            actions = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, column_spacing=8, row_spacing=8, max_children_per_line=4, homogeneous=False)
            for action in item.get('actions', []):
                row = Gtk.Box(spacing=8)
                reply = Gtk.Entry(placeholder_text='Write a reply…', hexpand=True) if action.get('reply') else None
                if reply:
                    row.append(reply)
                button = Gtk.Button(label=action.get('title') or 'Action')
                button.connect('clicked', lambda _, k=key, a=action, e=reply: self.execute('notification_actions',
                    'reply' if e else 'invoke', {'id': k, 'action': a['id'], **({'text': e.get_text()} if e else {})}))
                button.set_sensitive(self.enabled('notification_actions'))
                row.append(button)
                actions.insert(row, -1)
            if item.get('actions'):
                card.append(actions)
            self.notifications.append(card)
            self.notification_cards[key] = (item, card)
            self.notifications.reorder_child_after(card, previous)
            previous = card

    def execute(self, feature, action, data=None, done=None, quiet=False):
        if feature == 'call_control' and self.call_control_state.get('reconnecting') and action not in (
                'refresh', 'dial', 'contacts', 'history', 'audio_devices', 'audio_stop', 'hfp_devices', 'hfp_select'):
            if not quiet: self.error('Bluetooth call controls are reconnecting. Try again when connected.')
            if done: done(None)
            return
        if not self.path or not self.enabled(feature):
            if done:
                done(None)
            elif not quiet:
                self.error('This feature is currently unavailable.')
            return
        meta = {'feature': feature, 'action': action, 'device': self.path, 'generation': self.generation, 'done': done, 'quiet': quiet}
        def failed(message):
            if meta['generation'] != self.generation:
                return
            if done:
                done(None)
            if not quiet:
                self.error(message)
        self.client.call(self.path, 'org.unityconnect.Device1', 'Execute', '(sss)',
            (feature, action, json.dumps(data or {})), lambda path: self.started(path, meta), failed=failed)

    def started(self, path, meta):
        if meta['generation'] != self.generation:
            return
        self.operations[path] = meta
        if not meta['quiet']:
            self.activity_row.set_title(f"{meta['feature'].replace('_', ' ').title()} in progress")
        self.client.call(path, 'org.unityconnect.Operation1', 'GetState', done=lambda s: self.operation(path, json.loads(s)),
                         failed=lambda message: self.operation_error(path, message))

    def operation_error(self, path, message):
        meta = self.operations.pop(path, None)
        if not meta or meta['device'] != self.path or meta['generation'] != self.generation:
            return
        if meta['done']:
            meta['done'](None)
        if not meta['quiet']:
            self.error(message)

    def operation(self, path, op):
        meta = self.operations.get(path)
        if not meta:
            return
        if meta['device'] != self.path or meta['generation'] != self.generation:
            self.operations.pop(path, None)
            return
        if not meta['quiet']:
            self.activity_row.set_title(f"{op['feature'].replace('_', ' ').title()}: {op['status']}")
            self.activity_row.set_subtitle(op.get('error') or f"Progress: {op['progress']}%")
        if op['status'] == 'running':
            if op['feature'] == 'files':
                self.file_status.set_title(f"Sending · {op['progress']}%")
            return
        if op['feature'] == 'files':
            self.file_status.set_title('File sent' if op['status'] == 'completed' else 'Transfer failed')
            self.file_status.set_subtitle(op.get('error') or (op.get('result') or {}).get('name', ''))
        self.operations.pop(path, None)
        result = op.get('result') or {}
        if meta['done']:
            meta['done'](result if op['status'] == 'completed' else None)
        if op['status'] != 'completed':
            if not meta['quiet']:
                self.error(op.get('error') or 'Operation failed')
        elif not meta['quiet']:
            self.toasts.add_toast(Adw.Toast(title=result.get('reason', 'Operation completed')
                                          if op['feature'] == 'samsung_routines' else 'Operation completed'))

    def load_messages(self, force=False, address=None, offset=0, append=False):
        if not self.enabled('sms') or (self.messages_loaded and not force and address is None and not append):
            return
        self.messages_loaded = True
        self.message_hint.set_label('Loading messages…')
        payload = {'offset': offset or 0}
        if address is not None: payload['address'] = address
        self.execute('sms', 'list', payload,
            done=lambda result: self.show_messages(result, address, append), quiet=True)

    def show_messages(self, result, address=None, append=False):
        if result is None:
            self.messages_loaded = False
            self.message_hint.set_label('Messages could not be loaded. Try Refresh.')
            return
        items = result.get('messages', [])
        if append:
            self.message_data = list({m.get('id'):m for m in self.message_data + items}.values())
        elif address is None:
            self.message_data = items
        else:
            self.message_data = [m for m in self.message_data if m.get('address') != address] + items
        self.message_pages[address] = result.get('next_offset')
        self.message_more.set_visible(self.message_pages.get(None) is not None)
        self.conversation_more.set_visible(self.selected_address is not None and self.message_pages.get(self.selected_address) is not None)
        self.render_threads()
        if self.selected_address:
            self.render_conversation()
        self.message_hint.set_label(f'{len(self.message_data)} recent messages' if self.message_data else 'No messages found on your phone.')
        if address is None and self.selected_address and not append:
            self.load_messages(address=self.selected_address)

    def render_threads(self):
        selected = self.selected_address
        self.clear(self.threads)
        grouped = {}
        for message in sorted(self.message_data, key=lambda m: m.get('timestamp', 0), reverse=True):
            grouped.setdefault(message.get('address', 'Unknown sender'), []).append(message)
        search = self.message_search.get_text().casefold()
        for address, messages in grouped.items():
            if search and search not in address.casefold() and not any(search in m.get('body', '').casefold() for m in messages):
                continue
            row = Gtk.ListBoxRow()
            row.address = address
            box = Gtk.Box(spacing=10)
            box.add_css_class('thread-row')
            box.append(Adw.Avatar(size=36, text=address, show_initials=True))
            text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=5, hexpand=True)
            text.append(label(address, 'heading', ellipsize=Pango.EllipsizeMode.END))
            preview = label(messages[0].get('body', '').replace('\n', ' '), 'dim-label', ellipsize=Pango.EllipsizeMode.END)
            preview.set_max_width_chars(22)
            text.append(preview)
            time = label(timestamp(messages[0].get('timestamp'), True), 'dim-label')
            time.add_css_class('caption')
            text.append(time)
            box.append(text)
            row.set_child(box)
            self.threads.append(row)
            if address == selected:
                self.threads.select_row(row)

    def thread_selected(self, box, row):
        if row is None or self.selected_address == row.address:
            return
        self.selected_address = row.address
        self.render_conversation()
        self.message_split.set_show_content(True)
        self.load_messages(address=row.address)

    def render_conversation(self):
        self.chat_title.set_title(self.selected_address or 'New message')
        self.sms_address.set_text(self.selected_address or '')
        self.sms_address.set_visible(not self.selected_address)
        # Alphanumeric service senders cannot accept ordinary SMS replies.
        can_reply = bool(self.selected_address and all(c.isascii() and (c.isdigit() or c in '+ -()') for c in self.selected_address))
        self.composer.set_visible(not self.selected_address or can_reply)
        self.clear(self.message_bubbles)
        messages = sorted((m for m in self.message_data if m.get('address') == self.selected_address), key=lambda m: m.get('timestamp', 0))
        for message in messages:
            outgoing = message.get('direction') == 'outgoing'
            align = Gtk.Align.END if outgoing else Gtk.Align.START
            clamp = Adw.Clamp(maximum_size=520, tightening_threshold=360, halign=align, hexpand=True)
            bubble = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
            bubble.add_css_class('message-bubble')
            if outgoing:
                bubble.add_css_class('message-outgoing')
            text = label(message.get('body', ''), wrap=True, selectable=True)
            text.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
            bubble.append(text)
            info = timestamp(message.get('timestamp'), True)
            if message.get('type') == 5:
                info += ' · Failed to send'
            elif message.get('type') in (4, 6):
                info += ' · Pending on phone'
            time = label(info, 'dim-label', halign=Gtk.Align.END)
            time.add_css_class('caption')
            bubble.append(time)
            clamp.set_child(bubble)
            self.message_bubbles.append(clamp)
        if not messages:
            self.message_bubbles.append(self.empty('Start a conversation', 'Write a message below.', 'chat-symbolic'))
        GLib.idle_add(self.scroll_messages_end)

    def scroll_messages_end(self):
        adjustment = self.message_scroll.get_vadjustment()
        adjustment.set_value(max(adjustment.get_lower(), adjustment.get_upper() - adjustment.get_page_size()))
        return False

    def compose_message(self, *_):
        self.selected_address = None
        self.threads.unselect_all()
        self.render_conversation()
        self.message_split.set_show_content(True)
        self.sms_address.grab_focus()

    def send_message(self, *_):
        buffer = self.sms_body.get_buffer()
        body = buffer.get_text(buffer.get_start_iter(), buffer.get_end_iter(), False).strip()
        address = self.sms_address.get_text().strip()
        if not body or not address:
            self.toasts.add_toast(Adw.Toast(title='Enter a phone number and a message.'))
            return
        self.sms_button.set_sensitive(False)
        self.send_hint.set_label('Submitting to your phone…')
        def sent(result):
            self.sms_button.set_sensitive(self.enabled('sms'))
            if result is None:
                self.send_hint.set_label('Message was not submitted. Your draft is kept.')
                return
            buffer.set_text('')
            self.last_sms_id = result.get('message_id')
            self.send_hint.set_label('Submitted to phone. Waiting for carrier reports.')
            self.selected_address = address
            self.load_messages(force=True, address=address)
            self.refresh()
        self.execute('sms', 'send', {'address': address, 'body': body}, done=sent)

    def load_photos(self, force=False):
        if not self.enabled('photos') or (self.photos_loaded and not force):
            return
        self.photos_loaded = True
        self.photo_status.set_label('Loading your gallery…')
        self.photos_button.set_sensitive(False)
        self.execute('photos', 'list', done=self.show_photos, quiet=True)

    def show_photos(self, result):
        self.photos_button.set_sensitive(self.enabled('photos'))
        if result is None:
            self.photos_loaded = False
            self.photo_status.set_label('Photos could not be loaded. Try Refresh.')
            return
        self.clear(self.photos)
        self.gallery_copy_supported = bool(result.get('clipboard_supported'))
        self.photo_items, self.photo_widgets, self.preview_queue = {}, {}, []
        # A refreshed list expires Android's old selection tokens; pending responses
        # may finish, but only tokens in the current gallery can update a tile.
        for item in result.get('items', [])[:60]:
            key = item['id']
            self.photo_items[key] = item
            button = Gtk.Button(tooltip_text=item.get('name', 'Photo'))
            button.add_css_class('photo-tile')
            button.set_overflow(Gtk.Overflow.HIDDEN)
            button.connect('clicked', lambda _, k=key: self.open_photo(k))
            context = Gtk.GestureClick(button=3)
            context.connect('released', lambda gesture, count, x, y, b=button, k=key: self.photo_menu(b, k, x, y))
            button.add_controller(context)
            keyboard = Gtk.EventControllerKey()
            keyboard.connect('key-pressed', lambda controller, value, code, state, b=button, k=key:
                (self.photo_menu(b, k) or True) if value == Gdk.KEY_Menu or value == Gdk.KEY_F10 and state & Gdk.ModifierType.SHIFT_MASK else False)
            button.add_controller(keyboard)
            tile = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, width_request=156)
            stack = Gtk.Stack(height_request=144, hexpand=True)
            picture = ThumbnailPicture(content_fit=Gtk.ContentFit.COVER, can_shrink=True)
            stack.add_named(picture, 'image')
            placeholder = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER)
            placeholder.append(Gtk.Image(icon_name='video-x-generic-symbolic' if item.get('kind') == 'video' else 'image-x-generic-symbolic', pixel_size=32))
            hint = label('Loading preview…', 'dim-label')
            hint.add_css_class('caption')
            placeholder.append(hint)
            stack.add_named(placeholder, 'empty')
            overlay = Gtk.Overlay(child=stack)
            if item.get('kind') == 'video':
                badge = Gtk.Box(spacing=4, halign=Gtk.Align.END, valign=Gtk.Align.END, margin_end=6, margin_bottom=6)
                badge.add_css_class('osd')
                badge.append(Gtk.Image(icon_name='media-playback-start-symbolic'))
                badge.append(label(self.playback_time(item.get('duration', 0))))
                overlay.add_overlay(badge)
            tile.append(overlay)
            info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
            info.add_css_class('photo-info')
            name = label(item.get('name', 'Photo'), ellipsize=Pango.EllipsizeMode.MIDDLE)
            name.set_max_width_chars(18)
            info.append(name)
            caption = label(timestamp(item.get('timestamp'), True) or size_text(item.get('size')), 'dim-label', ellipsize=Pango.EllipsizeMode.END)
            caption.add_css_class('caption')
            info.append(caption)
            tile.append(info)
            button.set_child(tile)
            drag = Gtk.DragSource(actions=Gdk.DragAction.COPY)
            drag.connect('prepare', lambda source, x, y, k=key: self.prepare_gallery_drag(source, k))
            button.add_controller(drag)
            button.set_tooltip_text(item.get('name', 'Photo') + '\nDrag to copy the original into another app or folder.')
            self.photos.insert(button, -1)
            self.photo_widgets[key] = {'stack': stack, 'picture': picture, 'hint': hint, 'texture': None, 'drag': drag}
            if result.get('preview_supported'):
                self.preview_queue.append(key)
            else:
                hint.set_label('Preview unavailable')
        count = len(self.photo_items)
        self.photos_empty.set_visible(not count)
        self.photo_status.set_label(f'{count} photos and videos' if result.get('preview_supported') else
            'Update the Android companion to load low-resolution previews.' if count else 'No photos or videos available. Check gallery access on your phone.')
        self.pump_previews()

    def prepare_gallery_drag(self, source, key):
        if key not in self.photo_items or not self.enabled('photos') or not self.enabled('files'):
            return None
        if self.photo_items[key].get('size', 0) > MAX_FILE:
            self.error('This file exceeds Unity’s 64 MiB transfer limit.')
            return None
        texture = self.photo_widgets[key]['texture']
        if texture:
            source.set_icon(texture, 0, 0)
        return GalleryDragProvider(self, key)

    def pump_previews(self):
        if self.stack.get_visible_child_name() != 'photos' or not self.enabled('photos'):
            return
        while self.preview_queue and self.preview_inflight < 2:
            key = self.preview_queue.pop(0)
            self.preview_inflight += 1
            self.execute('photos', 'preview', {'id': key, 'edge': 256}, done=lambda result, k=key: self.show_preview(k, result), quiet=True)

    def show_preview(self, key, result):
        self.preview_inflight = max(0, self.preview_inflight - 1)
        widgets = self.photo_widgets.get(key)
        if widgets:
            texture = image_texture(result.get('preview')) if result and result.get('id') == key else None
            widgets['texture'] = texture
            if texture:
                widgets['picture'].set_paintable(texture)
                widgets['stack'].set_visible_child_name('image')
            else:
                widgets['hint'].set_label('Preview unavailable')
        self.pump_previews()

    def open_photo(self, key):
        item = self.photo_items.get(key)
        if not item:
            return
        dialog = Adw.Dialog(title=item.get('name', 'Photo'), content_width=620, content_height=540)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18)
        box.append(Adw.HeaderBar())
        picture = Gtk.Picture(content_fit=Gtk.ContentFit.CONTAIN, can_shrink=True, vexpand=True)
        picture.set_paintable(self.photo_widgets[key]['texture'])
        box.append(picture)
        info = inset(Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12), 18)
        info.append(label(item.get('name', 'Photo'), 'heading', wrap=True))
        details = [size_text(item.get('size'))]
        if item.get('width') and item.get('height'):
            details.append(f"{item['width']} × {item['height']}")
        info.append(label(' · '.join(details), 'dim-label'))
        info.append(label('Low-resolution preview · Download for original quality', 'dim-label', wrap=True))
        download = Gtk.Button(label='Download original', halign=Gtk.Align.END)
        download.add_css_class('suggested-action')
        download.set_sensitive(self.enabled('photos') and self.enabled('files'))
        download.connect('clicked', lambda *_: self.gallery_action(key, 'get'))
        actions = Gtk.Box(spacing=8, halign=Gtk.Align.END)
        copy = Gtk.Button(label='Copy to clipboard', sensitive=self.enabled('photos') and self.enabled('files') and self.gallery_copy_supported)
        copy.connect('clicked', lambda *_: self.gallery_action(key, 'copy'))
        actions.append(copy); actions.append(download)
        info.append(actions)
        box.append(info)
        dialog.set_child(box)
        dialog.present(self)

    def photo_menu(self, button, key, x=None, y=None):
        if key not in self.photo_items:
            return
        popover = Gtk.Popover(autohide=True)
        popover.set_parent(button)
        if x is not None:
            rect = Gdk.Rectangle(); rect.x = int(x); rect.y = int(y); rect.width = rect.height = 1
            popover.set_pointing_to(rect)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)
        for title, action in [('Copy to clipboard', 'copy'), ('Download original', 'get')]:
            control = Gtk.Button(label=title, sensitive=self.enabled('photos') and self.enabled('files') and (action != 'copy' or self.gallery_copy_supported))
            if action == 'copy' and not self.gallery_copy_supported: control.set_tooltip_text('Update the Android companion to copy original files.')
            control.add_css_class('flat')
            control.connect('clicked', lambda _, a=action: (popover.popdown(), self.gallery_action(key, a)))
            box.append(control)
        popover.set_child(box)
        popover.connect('closed', lambda *_: popover.unparent())
        popover.popup()

    def gallery_action(self, key, action):
        if key not in self.photo_items or not self.enabled('photos') or not self.enabled('files'):
            return
        if action == 'copy' and not self.gallery_copy_supported:
            self.error('Update the Android companion to copy gallery originals.')
            return
        name = self.photo_items[key].get('name', 'Gallery item')
        self.photo_status.set_label(f'Downloading {name}' + (' for the clipboard…' if action == 'copy' else '…'))
        def finished(result):
            if result is None:
                self.photo_status.set_label('Could not download this item. Check the connection and try again.')
            elif action == 'copy':
                self.photo_status.set_label('File copied. Its temporary copy is removed when the clipboard changes.')
            else:
                self.photo_status.set_label(f'Downloaded {name}. Your original download is kept.')
        self.execute('photos', action, {'id': key}, done=finished)

    def show_browser(self, result):
        if result is None:
            return
        self.clear(self.browser)
        for item in result.get('entries', []):
            row = Adw.ActionRow(title=item.get('name', ''), subtitle='Folder' if item.get('directory') else size_text(item.get('size')))
            row.set_title_lines(2)
            row.add_prefix(Gtk.Image(icon_name='folder-symbolic' if item.get('directory') else 'text-x-generic-symbolic'))
            button = icon_button('go-next-symbolic' if item.get('directory') else 'folder-download-symbolic',
                'Open folder' if item.get('directory') else 'Download file', lambda _, i=item: self.execute('browsing',
                    'list' if i.get('directory') else 'get', {'id': i['id']}, done=self.show_browser if i.get('directory') else None), 'flat')
            row.add_suffix(button)
            row.set_activatable_widget(button)
            self.browser.append(row)

    @staticmethod
    def playback_time(ms):
        seconds = max(0, int(ms or 0)) // 1000
        return f'{seconds // 60}:{seconds % 60:02}'

    def render_sound(self, state):
        self.sound_state = state
        for source, widgets in self.sound_widgets.items():
            sound = state.get('desktop_sound' if source == 'desktop' else 'sound', {})
            allowed = bool(self.enabled('sound') and sound.get('available') and sound.get('origin') == source)
            pending = self.sound_pending.get(source)
            target = (self.path, self.generation, sound.get('output'))
            if pending and (not allowed or pending['target'] != target):
                GLib.source_remove(pending['timer'])
                self.sound_pending.pop(source, None)
                pending = None
            widgets['scale'].set_sensitive(allowed)
            widgets['mute'].set_sensitive(allowed)
            if not pending: widgets['scale'].set_value(sound.get('volume', 0))
            widgets['level'].set_label(('>100%' if sound.get('boosted') else str(sound.get('volume', 0)) + '%') if sound else 'Unavailable')
            widgets['hint'].set_label(sound.get('error') or sound.get('name') or 'Connect and enable Media and sound controls on your phone.')
            widgets['mute'].set_icon_name('audio-volume-muted-symbolic' if sound.get('muted') else 'audio-volume-high-symbolic')
            widgets['mute'].set_tooltip_text(('Unmute' if sound.get('muted') else 'Mute') + (' laptop' if source == 'desktop' else ' phone'))
        self.get_application().update_desktop()

    def change_sound(self, scale, scroll, value, source):
        if not scale.get_sensitive(): return True
        sound = self.sound_state.get('desktop_sound' if source == 'desktop' else 'sound', {})
        target = (self.path, self.generation, sound.get('output'))
        old = self.sound_pending.pop(source, None)
        if old: GLib.source_remove(old['timer'])
        percent = int(round(max(0, min(100, value))))
        self.sound_widgets[source]['level'].set_label(str(percent) + '%')
        def submit():
            pending = self.sound_pending.pop(source, None)
            current = self.sound_state.get('desktop_sound' if source == 'desktop' else 'sound', {})
            if pending and pending['target'] == (self.path, self.generation, current.get('output')):
                payload = {'source': source, 'volume': percent}
                if source == 'desktop': payload['output'] = current.get('output', '')
                self.execute('sound', 'volume', payload, quiet=True)
            return False
        timer = GLib.timeout_add(160, submit)
        self.sound_pending[source] = {'timer': timer, 'target': target}
        return False

    def mute_sound(self, source):
        pending = self.sound_pending.pop(source, None)
        if pending: GLib.source_remove(pending['timer'])
        sound = self.sound_state.get('desktop_sound' if source == 'desktop' else 'sound', {})
        if not self.enabled('sound') or not sound.get('available'): return
        payload = {'source': source, 'muted': not sound.get('muted', False)}
        if source == 'desktop': payload['output'] = sound.get('output', '')
        self.execute('sound', 'mute', payload, quiet=True)

    def render_media_sources(self, state):
        phone = state.get('media', {})
        phones = phone.get('players') if 'players' in phone else ([phone] if phone.get('active', bool(phone.get('title'))) else [])
        players = [dict(p, origin='phone') for p in (phones or []) if p.get('origin', 'phone') == 'phone']
        players += [dict(p) for p in state.get('desktop_media', {}).get('players', []) if p.get('origin') == 'desktop']
        self.media_players = players
        key = lambda p: (p.get('origin'), p.get('session', ''))
        selected = next((p for p in players if key(p) == self.media_selection), None)
        if selected is None:
            selected = next((p for p in players if p.get('playing')), players[0] if players else {})
        self.media_selection = key(selected) if selected else None
        signature = [(key(p), p.get('app_name') or p.get('package')) for p in players]
        self.media_picker_updating = True
        if signature != self.media_model_signature:
            names = [('Laptop' if p.get('origin') == 'desktop' else 'Phone') + ' · ' + (p.get('app_name') or p.get('package') or 'Media player') for p in players]
            self.media_picker.set_model(Gtk.StringList.new(names or ['No active players']))
            self.media_model_signature = signature
        if selected: self.media_picker.set_selected(next(i for i,p in enumerate(players) if key(p) == self.media_selection))
        self.media_picker.set_sensitive(bool(players))
        self.media_picker_updating = False
        self.render_media(selected)

    def select_media_player(self, *_):
        if self.media_picker_updating: return
        index = self.media_picker.get_selected()
        if index < len(self.media_players):
            player = self.media_players[index]
            self.media_selection = (player.get('origin'), player.get('session', ''))
            self.render_media(player)

    def media_action(self, action, data=None, quiet=False):
        player = self.media_state
        payload = dict(data or {})
        if player.get('session'): payload['session'] = player['session']
        if action == 'seek' and player.get('origin') == 'desktop': payload['track_id'] = player.get('track_id', '')
        feature = 'desktop_media' if player.get('origin') == 'desktop' else 'media'
        self.execute(feature, action, payload, quiet=quiet)

    @staticmethod
    def media_track_key(media):
        return (media.get('origin'), media.get('session'), media.get('track_id') or
                (media.get('title'), media.get('artist'), media.get('album')))

    def start_media_clock(self):
        if self.media_tick_source is None:
            self.media_tick_source = GLib.timeout_add(250, self.tick_media)

    def stop_media_clock(self):
        if self.media_tick_source is not None:
            GLib.source_remove(self.media_tick_source)
            self.media_tick_source = None

    def tick_media(self):
        media = self.media_state
        duration = max(0, media.get('duration', 0))
        advanced = (time.monotonic() - self.media_sample_at) * 1000 * max(0, min(4, media.get('speed', 1))) if media.get('playing') else 0
        position = max(0, media.get('position', 0) + advanced)
        if duration: position = min(position, duration)
        if not self.pending_seek:
            self.media_seek.set_value(position)
            self.media_elapsed.set_label(self.playback_time(position))
        if self.media_waiting.get_visible(): self.media_waiting.pulse()
        return GLib.SOURCE_CONTINUE

    def render_media(self, media):
        old = self.media_state
        media = dict(media)
        same_track = bool(media and old and self.media_track_key(old) == self.media_track_key(media))
        if same_track and not media.get('duration') and old.get('duration'):
            media['duration'] = old['duration']
        if same_track and not media.get('artwork') and old.get('artwork'):
            media['artwork'] = old['artwork']
        if self.pending_seek and (not media or (old.get('origin'), old.get('session'), old.get('track_id')) != (media.get('origin'), media.get('session'), media.get('track_id'))):
            GLib.source_remove(self.pending_seek)
            self.pending_seek = None
        self.media_state = media
        self.media_sample_at = time.monotonic()
        self.media_title.set_label(media.get('title') or 'Nothing playing')
        self.media_artist.set_label(media.get('artist') or ('Unknown artist' if media.get('title') else 'Start playback on your laptop or phone.'))
        self.media_album.set_label(media.get('album', ''))
        self.media_album.set_visible(bool(media.get('album')))
        self.media_source.set_label(('Laptop' if media.get('origin') == 'desktop' else 'Phone') + ' · ' + (media.get('app_name') or media.get('package') or 'Media'))
        artwork = media.get('artwork')
        art_key = artwork.get('data') if artwork else None
        if art_key != self.art_key:
            self.art_key = art_key
            self.art_picture.set_paintable(image_texture(artwork, media=True))
        self.art_stack.set_visible_child_name('image' if self.art_picture.get_paintable() else 'empty')
        duration = media.get('duration', 0)
        position = min(media.get('position', 0), duration) if duration else media.get('position', 0)
        self.media_seek.set_visible(bool(duration))
        self.media_waiting.set_visible(bool(media.get('active', bool(media.get('title'))) and not duration))
        self.media_seek.set_range(0, max(1, duration))
        self.media_seek.set_value(position)
        self.media_elapsed.set_label(self.playback_time(position))
        self.media_duration.set_label(self.playback_time(duration) if duration else '—:—')
        self.media_info.set_label(('Playing' if media.get('playing') else 'Paused') if media.get('title') else 'No active session')
        self.media_toggle.set_icon_name('media-playback-pause-symbolic' if media.get('playing') else 'media-playback-start-symbolic')
        self.media_toggle.set_tooltip_text('Pause' if media.get('playing') else 'Play')
        self.update_media_controls()
        self.get_application().update_desktop()

    def update_media_controls(self):
        media = self.media_state
        active = media.get('active', bool(media.get('title')))
        allowed = media.get('actions')
        for button, action in [(self.media_previous, 'previous'), (self.media_next, 'next'),
                               (self.media_toggle, 'pause' if media.get('playing') else 'play'), (self.media_seek, 'seek')]:
            button.set_sensitive(bool(self.enabled('desktop_media' if media.get('origin') == 'desktop' else 'media') and active and (allowed is None or action in allowed) and
                (action != 'seek' or media.get('duration', 0) > 0)))

    def start_screen(self, source):
        if source == 'phone':
            self.open_screen_viewer()
        options = {'source': source}
        if source == 'desktop': options.update(control=self.screen_control_switch.get_active(), blank_display=self.screen_blank_switch.get_active(), extend_display=self.screen_extend_switch.get_active())
        self.execute('screen', 'start', options)

    def toggle_laptop_display(self, *_):
        session = self.screen_state.get('session_id')
        if session:
            self.execute('screen', 'display', {'session_id': session, 'on': not self.screen_state.get('display_on', True)})

    def open_screen_viewer(self):
        if self.screen_viewer:
            self.screen_viewer.present()
            return
        viewer_path = self.path
        self.screen_viewer_path = viewer_path
        viewer = Adw.Window(title='Phone screen', default_width=420, default_height=740,
                            transient_for=self, application=self.get_application())
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.append(Adw.HeaderBar(title_widget=Adw.WindowTitle(title='Phone screen')))
        self.screen_picture = Gtk.Picture(content_fit=Gtk.ContentFit.CONTAIN, can_shrink=True, vexpand=True)
        box.append(self.screen_picture)
        stop = Gtk.Button(label='Stop screen sharing', margin_start=12, margin_end=12, margin_top=12, margin_bottom=12)
        stop.connect('clicked', lambda *_: viewer.close())
        box.append(stop)
        viewer.set_content(box)
        self.screen_viewer = viewer
        self.screen_sequence = 0
        def closed(*_):
            self.screen_picture.set_paintable(None)
            self.screen_viewer = self.screen_picture = None
            if self.authorized and viewer_path:
                self.client.call(viewer_path, 'org.unityconnect.Device1', 'Execute', '(sss)',
                    ('screen', 'stop', '{}'), failed=lambda _: None)
            return False
        viewer.connect('close-request', closed)
        viewer.present()
        self.fetch_screen_frame()

    def fetch_screen_frame(self):
        if not self.screen_viewer or self.screen_fetching or self.screen_viewer_path != self.path or not self.enabled('screen'):
            return
        self.screen_fetching = True
        path = self.path
        epoch, viewer = self.screen_epoch, self.screen_viewer
        def complete(value):
            self.screen_fetching = False
            if path != self.path or not self.screen_picture or self.screen_viewer_path != path or not self.enabled('screen') or epoch != self.screen_epoch or viewer is not self.screen_viewer:
                return
            sequence, width, height, jpeg = value
            if sequence <= self.screen_sequence or not (1 <= max(width, height) <= 1280 and width * height <= 921600 and len(jpeg) <= 262144):
                return
            try:
                texture = Gdk.Texture.new_from_bytes(GLib.Bytes.new(bytes(jpeg)))
                if (texture.get_width(), texture.get_height()) != (width, height):
                    return
                self.screen_sequence = sequence
                self.screen_picture.set_paintable(texture)
            except GLib.Error as e:
                self.error(e.message)
        self.client.call(path, 'org.unityconnect.Screen1', 'GetFrame', done=complete,
                         failed=lambda _: setattr(self, 'screen_fetching', False))

    def render_screen(self, screen):
        if self.screen_state != screen:
            self.screen_epoch += 1
        self.screen_state = screen
        active = screen.get('state') in ('starting', 'streaming')
        self.share_desktop_button.set_sensitive(self.enabled('screen') and not active)
        self.view_phone_button.set_sensitive(self.enabled('screen') and not active)
        self.stop_screen_button.set_sensitive(self.enabled('screen') and active)
        desktop = active and screen.get('source') == 'desktop'
        self.laptop_display_button.set_label('Turn off' if screen.get('display_on', True) else 'Turn on')
        self.laptop_display_button.set_sensitive(self.enabled('screen') and desktop and screen.get('state') == 'streaming' and screen.get('display_available', False))
        self.screen_control_switch.set_sensitive(not active)
        self.screen_extend_switch.set_sensitive(not active)
        self.screen_blank_switch.set_sensitive(not active and self.screen_control_switch.get_active())
        self.screen_status.set_title(('Sharing computer screen' if screen.get('source') == 'desktop' else 'Viewing phone screen') if active else 'No screen share')
        self.screen_status.set_subtitle(screen.get('error') or screen.get('display_error') or ('Phone is a separate monitor beside the laptop display. Move windows between them in your desktop display settings.' if desktop and screen.get('extended_display') else 'Phone is the primary display; the laptop panel is disabled.' if desktop and screen.get('phone_primary') else 'Laptop display is off; restore it here or on the phone.' if desktop and not screen.get('display_on', True) else 'Approve capture on the sharing device…' if screen.get('state') == 'starting' else
            'Live · Close the viewer or press Stop to end sharing.' if active else 'Enable Screen sharing in the Android Features page, then connect.'))
        if active and screen.get('source') == 'phone' and not self.screen_viewer:
            self.open_screen_viewer()
        if self.screen_picture and (not active or self.screen_viewer_path != self.path):
            self.screen_picture.set_paintable(None)
        self.get_application().update_desktop()

    def render_find_phone(self, state):
        self.find_state = state
        self.find_button.set_label('Stop' if state.get('state') == 'ringing' else 'Ring')
        self.get_application().update_desktop()

    def toggle_find_phone(self, *_):
        target = (self.path, self.generation)
        def finished(result):
            if result is not None and (self.path, self.generation) == target:
                self.render_find_phone(result)
        self.execute('find', 'stop' if self.find_state.get('state') == 'ringing' else 'ring', done=finished)

    def render_audio_route(self, audio):
        self.audio_state = audio
        active = audio.get('state') in ('starting', 'streaming')
        source = audio.get('source', 'desktop')
        if self.audio_pending and self.audio_pending_operation == 'start' and active:
            self.audio_pending = False
        self.audio_updating = True
        if not self.audio_pending:
            self.audio_route.set_active(active and source == 'desktop')
            self.phone_audio_route.set_active(active and source == 'phone')
        self.audio_updating = False
        self.audio_transport.set_sensitive(self.enabled('audio') and not active and not self.audio_pending)
        transport = {'lan': 'Wi-Fi / LAN', 'bluetooth': 'Bluetooth'}.get(audio.get('transport'), 'encrypted connection')
        for row, direction in ((self.audio_route, 'desktop'), (self.phone_audio_route, 'phone')):
            row.set_sensitive(self.enabled('audio') and not self.audio_pending and (not active or source == direction))
            if audio.get('error') and source == direction:
                subtitle = audio['error']
            elif active and source == direction:
                if audio.get('state') == 'starting':
                    subtitle = ('Approve audio sharing on your phone…' if source == 'phone' else 'Connecting audio…')
                else:
                    subtitle = ('Phone media plays on this laptop.' if source == 'phone' else
                                'Laptop sound plays on the phone. Turn off to restore laptop output.')
                subtitle += f' Using {transport}.'
                phone = self.sound_state.get('sound', {})
                if source == 'desktop' and phone.get('available') and phone.get('origin') == 'phone':
                    if phone.get('muted'):
                        subtitle += ' Phone media is muted; unmute it under Device volume.'
                    elif phone.get('volume') == 0:
                        subtitle += ' Phone media volume is 0%; raise it under Device volume.'
            elif self.enabled('audio'):
                subtitle = ('Send laptop sound to your phone using the selected connection.' if direction == 'desktop' else
                            'Approve media audio capture on the phone each time. Some apps block capture; phone speakers may also play.')
                if active:
                    subtitle = 'Stop the current audio stream to change direction.'
            else:
                cap = self.capabilities.get('audio', {})
                subtitle = ('Update the Android companion to enable audio routing.' if cap.get('status') == 'unsupported' else
                            'Enable Phone and laptop audio in the Android Features page, then connect.')
            row.set_subtitle(subtitle)
        self.get_application().update_desktop()

    def toggle_audio_route(self, row, _):
        if self.audio_updating or self.audio_pending:
            return
        self.request_audio_route('phone' if row is self.phone_audio_route else 'desktop', row.get_active())

    def request_audio_route(self, source, active):
        if self.audio_pending or source not in ('phone', 'desktop'):
            return
        if not self.enabled('audio'):
            self.render_audio_route(self.audio_state)
            return
        self.audio_pending = True
        self.audio_route.set_sensitive(False)
        self.phone_audio_route.set_sensitive(False)
        self.audio_transport.set_sensitive(False)
        self.audio_pending_operation = 'start' if active else 'stop'
        transport = ('auto', 'lan', 'bluetooth')[min(self.audio_transport.get_selected(), 2)]
        def finished(result):
            self.audio_pending = False
            self.render_audio_route(self.audio_state)
            self.refresh()
        self.execute('audio', 'start' if active else 'stop',
                     {'source': source, 'transport': transport} if active else {}, done=finished)

    def toggle_media(self, *_):
        self.media_action('pause' if self.media_state.get('playing') else 'play')

    def seek_media(self, scale, scroll, value):
        if scale.get_sensitive():
            if self.pending_seek: GLib.source_remove(self.pending_seek)
            player = dict(self.media_state)
            position = int(max(0, min(value, player.get('duration', 0))))
            def submit():
                self.pending_seek = None
                if (player.get('origin'), player.get('session'), player.get('track_id')) == (self.media_state.get('origin'), self.media_state.get('session'), self.media_state.get('track_id')):
                    self.media_action('seek', {'position': position}, quiet=True)
                return False
            self.pending_seek = GLib.timeout_add(200, submit)
        return False

    def open_received_url(self, *_):
        if self.incoming_url and self.incoming_url.startswith(('https://', 'http://')):
            Gio.AppInfo.launch_default_for_uri_async(self.incoming_url, None, None,
                lambda obj, result: Gio.AppInfo.launch_default_for_uri_finish(result))

    def open_received_file(self, *_):
        if self.received_path:
            file = Gio.File.new_for_path(self.received_path)
            Gio.AppInfo.launch_default_for_uri_async(file.get_uri(), None, None, None)

    def choose_file(self, *_):
        dialog = Gtk.FileDialog(title='Send to device')
        def selected(dialog, result):
            try:
                files = dialog.open_multiple_finish(result)
                self.select_files([files.get_item(i) for i in range(files.get_n_items())])
            except GLib.Error as e:
                if not e.matches(Gtk.DialogError.quark(), Gtk.DialogError.DISMISSED):
                    self.error(e.message)
        dialog.open_multiple(self, None, selected)

    def select_files(self, files):
        """Picker and window drops share validation and retain files until acknowledged."""
        accepted, errors = False, []
        for file in files:
            try:
                path = file.get_path()
                if not path:
                    raise ValueError('Copy remote files to this computer before sending them.')
                # The daemon opens with O_NOFOLLOW. Resolve an explicitly selected
                # symlink first, just as unityctl send does.
                path = Path(path).resolve(strict=True)
                info = path.stat()
                if not stat.S_ISREG(info.st_mode):
                    raise ValueError(f'{path.name}: select a file. Compress folders before sending.')
                if info.st_size > MAX_FILE:
                    raise ValueError(f'{path.name}: exceeds the 64 MiB file limit.')
                name = path.name
                if len(name.encode('utf-8')) > 255 or any(c == '\\' or ord(c) < 32 or 127 <= ord(c) <= 159 for c in name):
                    raise ValueError(f'{name}: this filename cannot be transferred. Rename it first.')
                path = str(path)
                if path not in self.pending_files:
                    self.pending_files.append(path)
                accepted = True
            except (OSError, RuntimeError, ValueError) as e:
                errors.append(str(e))
        if accepted:
            self.file_send_error = ''
            self.file_send_notice = ''
            self.pages['files'].set_visible(True)
            self.nav.select_row(self.pages['files'])
            self.present()
        self.render_file_selection()
        if errors:
            self.error('\n'.join(errors[:3]))
        return accepted

    def render_file_selection(self):
        self.clear(self.pending_file_list)
        for path in self.pending_files:
            row = Adw.ActionRow(title=Path(path).name, subtitle=path)
            row.set_title_lines(2)
            row.set_subtitle_lines(2)
            remove = icon_button('list-remove-symbolic', 'Remove file',
                lambda _, p=path: self.remove_selected_file(p), 'flat')
            remove.set_sensitive(not self.file_sending)
            row.add_suffix(remove)
            self.pending_file_list.append(row)
        self.pending_file_list.set_visible(bool(self.pending_files))
        count = len(self.pending_files)
        self.file_send_row.set_title(f'{count} file{"s" if count != 1 else ""} selected' if count else 'No files selected')
        device = self.devices.get(self.path, {})
        if self.file_sending:
            detail = f'Sending to {device.get("Name", "device")}…'
        elif self.file_send_error:
            detail = self.file_send_error
        elif not self.authorized:
            detail = 'Allow Unity access, then send your selected files.'
        elif not device.get('Connected'):
            detail = 'Connect a paired device, then send your selected files.'
        elif not self.enabled('files'):
            detail = self.capabilities.get('files', {}).get('reason') or 'Enable file transfers on both devices.'
        else:
            detail = self.file_send_notice or f'Ready to send to {device.get("Name", "device")}.'
        self.file_send_row.set_subtitle(detail)
        self.file_send_button.set_sensitive(bool(count and self.enabled('files') and not self.file_sending))

    def remove_selected_file(self, path):
        if not self.file_sending and path in self.pending_files:
            self.pending_files.remove(path)
            self.render_file_selection()

    def send_selected_files(self, *_):
        if self.file_sending or not self.pending_files or not self.enabled('files'):
            return
        self.file_sending = True
        self.file_send_error = self.file_send_notice = ''
        self.file_send_token += 1
        self.send_next_file(self.file_send_token, self.path, self.generation)

    def send_next_file(self, token, device, generation):
        if token != self.file_send_token:
            return
        if self.path != device or self.generation != generation or not self.enabled('files'):
            self.file_send_failed(token, 'Transfer interrupted. Reconnect and send the remaining files again.')
            return
        if not self.pending_files:
            self.file_sending = False
            self.file_send_notice = f'Files sent to {self.devices.get(device, {}).get("Name", "device")}.'
            self.render_file_selection()
            return
        source = self.pending_files[0]
        self.file_status.set_title('Preparing file…')
        self.file_status.set_subtitle(Path(source).name)
        self.render_file_selection()
        def completed(result):
            if token != self.file_send_token:
                return
            if result is None:
                self.file_send_failed(token, 'Transfer failed. Your file is still selected; check the error and try again.')
                return
            self.pending_files.remove(source)
            self.send_next_file(token, device, generation)
        meta = {'feature': 'files', 'action': 'send', 'device': device, 'generation': generation,
                'done': completed, 'quiet': False}
        self.client.call(device, 'org.unityconnect.Transfer1', 'SendFile', '(s)', (source,),
            lambda path: self.started(path, meta), failed=lambda message: self.file_send_failed(token, message))

    def file_send_failed(self, token, message):
        if token != self.file_send_token:
            return
        self.file_sending = False
        self.file_send_error = message
        self.file_status.set_title('Transfer failed')
        self.file_status.set_subtitle(message)
        self.render_file_selection()
        self.error(message)

    def discover(self, *_):
        self.pairing_active = True
        self.client.manager('StartDiscovery', '(u)', (120,), lambda _: self.client.manager('PairingInfo', done=self.pairing))

    def pairing(self, s):
        info = json.loads(s)
        bluetooth = info.get('bluetooth', {})
        addresses = [a.get('address', '') for a in bluetooth.get('adapters', []) if a.get('powered')]
        bt_text = 'Bluetooth: ' + ', '.join(addresses) if addresses else 'Bluetooth: ' + (bluetooth.get('reason') or 'Turn on Bluetooth in system settings')
        self.pair_info.set_label(f"{bt_text}\nPorts: {', '.join(str(p) for p in info.get('network', {}).get('ports', [info['port']]))}\nEncrypted address discovery over the paired connection.\nCompare this TLS fingerprint on the phone:\n{info['fingerprint']}")
        self.clear(self.pair_requests)
        for pending in info['pending']:
            row = Adw.ActionRow(title=pending['name'], subtitle=pending['id'])
            button = Gtk.Button(label='Approve', valign=Gtk.Align.CENTER)
            button.connect('clicked', lambda _, id=pending['id']: self.client.manager('ApprovePairing', '(sb)', (id, True)))
            row.add_suffix(button)
            self.pair_requests.append(row)

    def unpair(self, *_):
        if not self.path:
            return
        dialog = Adw.AlertDialog(heading='Unpair this device?', body='The phone will lose access until you pair it again.')
        dialog.add_response('cancel', 'Cancel')
        dialog.add_response('unpair', 'Unpair')
        dialog.set_response_appearance('unpair', Adw.ResponseAppearance.DESTRUCTIVE)
        dialog.connect('response', lambda _, response: self.client.manager('Unpair', '(s)',
            (self.devices[self.path]['Id'],)) if response == 'unpair' else None)
        dialog.present(self)


class StatusIndicator:
    """GTK4-compatible AppIndicator, using the session bus and the app's actions.

    GNOME's AppIndicator extension renders the popup; no GTK3 library or helper
    process is needed. Item IDs remain stable across updates and are never reused
    for another device, so a click from an old popup cannot control a new phone.
    """
    ITEM_PATH = '/StatusNotifierItem'
    MENU_PATH = '/org/unityconnect/IndicatorMenu'
    ITEM_IFACE = 'org.kde.StatusNotifierItem'
    MENU_IFACE = 'com.canonical.dbusmenu'
    WATCHER = 'org.kde.StatusNotifierWatcher'
    XML = '''<node>
      <interface name="org.kde.StatusNotifierItem">
        <property name="Category" type="s" access="read"/>
        <property name="Id" type="s" access="read"/>
        <property name="Title" type="s" access="read"/>
        <property name="Status" type="s" access="read"/>
        <property name="WindowId" type="i" access="read"/>
        <property name="IconName" type="s" access="read"/>
        <property name="IconThemePath" type="s" access="read"/>
        <property name="IconPixmap" type="a(iiay)" access="read"/>
        <property name="OverlayIconName" type="s" access="read"/>
        <property name="OverlayIconPixmap" type="a(iiay)" access="read"/>
        <property name="AttentionIconName" type="s" access="read"/>
        <property name="AttentionIconPixmap" type="a(iiay)" access="read"/>
        <property name="AttentionMovieName" type="s" access="read"/>
        <property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
        <property name="ItemIsMenu" type="b" access="read"/>
        <property name="Menu" type="o" access="read"/>
        <method name="Activate"><arg type="i" direction="in"/><arg type="i" direction="in"/></method>
        <method name="SecondaryActivate"><arg type="i" direction="in"/><arg type="i" direction="in"/></method>
        <method name="ContextMenu"><arg type="i" direction="in"/><arg type="i" direction="in"/></method>
        <method name="Scroll"><arg type="i" direction="in"/><arg type="s" direction="in"/></method>
        <method name="ProvideXdgActivationToken"><arg type="s" direction="in"/></method>
        <signal name="NewToolTip"/>
      </interface>
      <interface name="com.canonical.dbusmenu">
        <property name="Version" type="u" access="read"/>
        <property name="TextDirection" type="s" access="read"/>
        <property name="Status" type="s" access="read"/>
        <property name="IconThemePath" type="as" access="read"/>
        <method name="GetLayout">
          <arg type="i" direction="in"/><arg type="i" direction="in"/><arg type="as" direction="in"/>
          <arg type="u" direction="out"/><arg type="(ia{sv}av)" direction="out"/>
        </method>
        <method name="GetGroupProperties">
          <arg type="ai" direction="in"/><arg type="as" direction="in"/><arg type="a(ia{sv})" direction="out"/>
        </method>
        <method name="GetProperty">
          <arg type="i" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/>
        </method>
        <method name="Event">
          <arg type="i" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="in"/><arg type="u" direction="in"/>
        </method>
        <method name="EventGroup"><arg type="a(isvu)" direction="in"/><arg type="ai" direction="out"/></method>
        <method name="AboutToShow"><arg type="i" direction="in"/><arg type="b" direction="out"/></method>
        <method name="AboutToShowGroup">
          <arg type="ai" direction="in"/><arg type="ai" direction="out"/><arg type="ai" direction="out"/>
        </method>
        <signal name="LayoutUpdated"><arg type="u"/><arg type="i"/></signal>
      </interface>
    </node>'''

    def __init__(self, app):
        self.app = app
        self.bus = app.get_dbus_connection()
        self.ready = self.closed = False
        self.host_available = False
        self.owner = self.proxy = None
        self.activation_token = None
        self.revision = 0
        self.ids, self.nodes = {}, {0: ({}, [], None, None)}
        self.signature = None
        self.description = 'Unity'
        info = Gio.DBusNodeInfo.new_for_xml(self.XML)
        self.registrations = [self.bus.register_object(path, info.lookup_interface(interface),
            self.method, self.get_property, None) for path, interface in
            [(self.ITEM_PATH, self.ITEM_IFACE), (self.MENU_PATH, self.MENU_IFACE)]]
        self.watch = Gio.bus_watch_name_on_connection(self.bus, self.WATCHER,
            Gio.BusNameWatcherFlags.NONE, self.appeared, self.vanished)

    def get_property(self, bus, sender, path, interface, name):
        if interface == self.MENU_IFACE:
            values = {'Version': ('u', 3), 'TextDirection': ('s', 'ltr'),
                      'Status': ('s', 'normal'), 'IconThemePath': ('as', [])}
        else:
            values = {key: ('s', '') for key in ('IconThemePath', 'OverlayIconName',
                      'AttentionIconName', 'AttentionMovieName')}
            values.update({key: ('a(iiay)', []) for key in ('IconPixmap', 'OverlayIconPixmap', 'AttentionIconPixmap')})
            values.update({'Category': ('s', 'Communications'), 'Id': ('s', 'org.unityconnect.Unity'),
                'Title': ('s', 'Unity'), 'Status': ('s', 'Active'), 'WindowId': ('i', 0),
                'IconName': ('s', 'org.unityconnect.Unity-symbolic'), 'ItemIsMenu': ('b', True),
                'Menu': ('o', self.MENU_PATH),
                'ToolTip': ('(sa(iiay)ss)', ('org.unityconnect.Unity-symbolic', [], 'Unity', self.description))})
        return GLib.Variant(*values[name])

    def appeared(self, bus, name, owner):
        self.owner = owner
        Gio.DBusProxy.new(bus, Gio.DBusProxyFlags.DO_NOT_AUTO_START, None, owner,
            '/StatusNotifierWatcher', self.WATCHER, None, self.proxy_created, owner)

    def proxy_created(self, source, result, owner):
        try:
            proxy = Gio.DBusProxy.new_finish(result)
        except GLib.Error:
            return
        if self.closed or self.owner != owner:
            return
        self.proxy = proxy
        host = proxy.get_cached_property('IsStatusNotifierHostRegistered')
        self.host_available = bool(host and host.unpack())
        proxy.connect('g-signal', self.host_signal)
        proxy.connect('g-properties-changed', self.host_properties)
        self.register()

    def host_properties(self, proxy, changed, invalidated):
        if proxy != self.proxy:
            return
        if 'IsStatusNotifierHostRegistered' in changed.unpack() or 'IsStatusNotifierHostRegistered' in invalidated:
            host = proxy.get_cached_property('IsStatusNotifierHostRegistered')
            self.host_available = bool(host and host.unpack())
            self.register()

    def host_signal(self, proxy, sender, name, parameters):
        if proxy != self.proxy:
            return
        if name == 'StatusNotifierHostRegistered':
            self.host_available = True
            self.register()
        elif name == 'StatusNotifierHostUnregistered':
            self.host_available = False
            self.set_ready(False)

    def register(self):
        if not self.host_available:
            self.set_ready(False)
            return
        if self.ready:
            return
        owner = self.owner
        def registered(bus, result):
            try:
                bus.call_finish(result)
            except GLib.Error:
                return
            if not self.closed and self.owner == owner and self.host_available:
                self.set_ready(True)
        self.bus.call(owner, '/StatusNotifierWatcher', self.WATCHER, 'RegisterStatusNotifierItem',
            GLib.Variant('(s)', (self.ITEM_PATH,)), None, Gio.DBusCallFlags.NONE, 5000, None, registered)

    def set_ready(self, ready):
        was_ready, self.ready = self.ready, ready
        if was_ready and not ready and not self.closed:
            win = self.app.main_window()
            if win and not win.get_visible() and not win.popup_only:
                win.present()

    def vanished(self, *args):
        self.owner = self.proxy = None
        self.host_available = False
        self.set_ready(False)

    def update(self, items, description):
        signature = (repr(items), description)
        if signature == self.signature:
            return
        self.signature = signature
        self.description = description
        nodes = {}
        def add(item, parent=()):
            key = (*parent, item['key'])
            if key not in self.ids:
                self.ids[key] = len(self.ids) + 1
            ident = self.ids[key]
            children = [add(child, key) for child in item.get('children', [])]
            props = {k: GLib.Variant('b' if isinstance(v, bool) else 'i' if isinstance(v, int) else 's', v)
                     for k, v in item.get('props', {}).items()}
            if children: props['children-display'] = GLib.Variant('s', 'submenu')
            nodes[ident] = (props, children, item.get('action'), item.get('target'))
            return ident
        children = [add(item) for item in items]
        nodes[0] = ({'children-display': GLib.Variant('s', 'submenu')}, children, None, None)
        self.nodes = nodes
        self.revision += 1
        self.bus.emit_signal(None, self.MENU_PATH, self.MENU_IFACE, 'LayoutUpdated', GLib.Variant('(ui)', (self.revision, 0)))
        self.bus.emit_signal(None, self.ITEM_PATH, self.ITEM_IFACE, 'NewToolTip', None)

    def properties(self, ident, names):
        props = self.nodes[ident][0]
        return {key: value for key, value in props.items() if not names or key in names}

    def layout(self, ident, depth, names):
        children = [] if depth == 0 else [GLib.Variant('(ia{sv}av)', self.layout(child, depth - 1, names))
                                        for child in self.nodes[ident][1]]
        return ident, self.properties(ident, names), children

    def event(self, ident, event, data, timestamp):
        if ident not in self.nodes:
            return False
        props, children, action, target = self.nodes[ident]
        if event == 'clicked' and action and props.get('enabled', GLib.Variant('b', True)).unpack():
            # GAction also checks current permission/connection state.
            self.activate(action, target)
        return True

    def activate(self, action, target=None):
        win = self.app.main_window()
        if win and self.activation_token:
            win.set_startup_id(self.activation_token)
        self.activation_token = None
        self.app.activate_action(action, GLib.Variant('s', target) if target is not None else None)

    def method(self, bus, sender, path, interface, method, parameters, invocation):
        args = parameters.unpack()
        try:
            if interface == self.ITEM_IFACE:
                if method == 'ProvideXdgActivationToken':
                    self.activation_token = args[0]
                elif method in ('Activate', 'SecondaryActivate'):
                    self.activate('show')
                invocation.return_value(None)
            elif method == 'GetLayout':
                invocation.return_value(GLib.Variant('(u(ia{sv}av))', (self.revision, self.layout(*args))))
            elif method == 'GetGroupProperties':
                invocation.return_value(GLib.Variant('(a(ia{sv}))', ([(i, self.properties(i, args[1]))
                    for i in (args[0] or self.nodes) if i in self.nodes],)))
            elif method == 'GetProperty':
                invocation.return_value(GLib.Variant('(v)', (self.properties(args[0], [args[1]])[args[1]],)))
            elif method == 'Event':
                self.event(*args); invocation.return_value(None)
            elif method == 'EventGroup':
                errors = [event[0] for event in args[0] if not self.event(*event)]
                invocation.return_value(GLib.Variant('(ai)', (errors,)))
            elif method == 'AboutToShow':
                invocation.return_value(GLib.Variant('(b)', (False,)))
            elif method == 'AboutToShowGroup':
                invocation.return_value(GLib.Variant('(aiai)', ([], [i for i in args[0] if i not in self.nodes])))
        except (KeyError, ValueError) as error:
            invocation.return_dbus_error(self.MENU_IFACE + '.InvalidItem', str(error))

    def close(self):
        self.closed = True
        Gio.bus_unwatch_name(self.watch)
        for registration in self.registrations:
            self.bus.unregister_object(registration)


class App(Adw.Application):
    PAGE_TITLES = {'overview': 'Overview', 'notifications': 'Notifications', 'messages': 'Messages',
        'calls': 'Calls', 'files': 'Files', 'photos': 'Photos', 'connectivity': 'Connectivity',
        'media': 'Media', 'remote': 'Remote Control', 'firefox': 'Firefox', 'settings': 'Settings'}
    MENU_TITLES = {'file': 'File', 'phone': 'Phone', 'media': 'Media', 'screen': 'Screen',
                   'view': 'View', 'settings': 'Settings', 'help': 'Help'}
    DEFAULT_PREFERENCES = {'keep_running': True, 'show_status': True, 'appearance': 'system', 'audio_transport': 'auto'}
    FEATURE_TITLES = {'sms': 'Text Messages', 'urls': 'Link Sharing', 'find': 'Find Phone', 'files': 'File Transfers',
        'browsing': 'Browse Phone Files', 'photos': 'Photos and Videos', 'audio': 'Audio Streaming',
        'media': 'Phone Playback Controls', 'desktop_media': 'Laptop Playback Controls', 'sound': 'Device Volume',
        'screen': 'Screen Sharing', 'remote_input': 'Remote Input', 'lock': 'Lock Phone', 'ringer': 'Phone Ringer and DND',
        'clipboard': 'Automatic Clipboard Sync', 'notifications': 'Phone Notifications',
        'notification_actions': 'Notification Actions', 'call_notifications': 'Call Notifications', 'call_control': 'Call Controls',
        'samsung_routines': 'Samsung Routines', 'phone_connectivity': 'Phone Connections', 'hotspot': 'Internet Sharing',
        'media_artwork': 'Media Artwork'}

    def __init__(self):
        super().__init__(application_id='org.unityconnect.Unity', flags=Gio.ApplicationFlags.HANDLES_COMMAND_LINE)
        self.indicator = None
        self.desktop_source = None
        self.preferences = dict(self.DEFAULT_PREFERENCES)
        self.menu_signatures = {}
        self.feature_actions = {}
        self.feature_pending = set()
        self.add_main_option('call-popup', 0, GLib.OptionFlags.NONE, GLib.OptionArg.STRING, 'Show call controls for a phone', 'DEVICE')
        self.add_main_option('open-messages', 0, GLib.OptionFlags.NONE, GLib.OptionArg.STRING, 'Open messages for a phone', 'DEVICE')
        self.add_main_option('open-notifications', 0, GLib.OptionFlags.NONE, GLib.OptionArg.STRING, 'Open notifications for a phone', 'DEVICE')

    def do_startup(self):
        Adw.Application.do_startup(self)
        self.load_preferences()
        for name, callback, accel in [
                ('quit', lambda: self.quit(), '<primary>q'),
                ('show', self.activate, '<primary>o'),
                ('send-files', self.send_files, '<primary>u'),
                ('find-phone', lambda: self.main_window().toggle_find_phone(), '<primary>f'),
                ('stop-screen', lambda: self.main_window().execute('screen', 'stop'), None),
                ('refresh', lambda: self.main_window().changed(ROOT), '<primary>r'),
                ('discover', self.discover, None),
                ('about', self.show_about, None)]:
            action = Gio.SimpleAction.new(name, None)
            action.connect('activate', lambda action, parameter, callback=callback: callback())
            self.add_action(action)
            if accel: self.set_accels_for_action('app.' + name, [accel])
        for page in self.PAGE_TITLES:
            action = Gio.SimpleAction.new('page-' + page, None)
            action.connect('activate', lambda action, parameter, page=page: self.show_page(page))
            self.add_action(action)
        self.set_accels_for_action('app.page-settings', ['<primary>comma'])
        device = Gio.SimpleAction.new_stateful('device', GLib.VariantType.new('s'), GLib.Variant('s', ''))
        device.connect('activate', self.select_device)
        device.connect('change-state', self.select_device)
        self.add_action(device)
        self.add_quick_actions()
        menu = Gio.Menu()
        self.menu_models = {key: Gio.Menu() for key in self.MENU_TITLES}
        for key, title in self.MENU_TITLES.items(): menu.append_submenu(title, self.menu_models[key])
        self.set_menubar(menu)
        if self.get_dbus_connection():
            self.indicator = StatusIndicator(self)
        self.connect('window-removed', lambda *_: self.update_desktop())
        self.connect('window-added', self.add_file_drop_target)
        self.update_desktop()
        # Let GApplication release its registration when a launcher/session
        # manager terminates the window, just as it does for a normal close.
        GLibUnix.signal_add(GLib.PRIORITY_DEFAULT, signal.SIGTERM,
                           lambda: (self.quit(), False)[1])

    def do_activate(self):
        win = self.main_window()
        if not win:
            win = Window(self)
        win.popup_only = False
        win.present()
        self.update_desktop()

    def main_window(self):
        return next((w for w in self.get_windows() if isinstance(w, Window)), None)

    def keep_in_background(self, win):
        return bool(self.preferences['keep_running'] and self.indicator and self.indicator.ready and not win.popup_only)

    def show_page(self, page):
        self.activate()
        win = self.main_window()
        win.nav.select_row(win.pages[page])

    def send_files(self):
        self.show_page('files')
        self.main_window().choose_file()

    def discover(self):
        self.show_page('settings')
        self.main_window().discover()

    def show_about(self):
        self.activate()
        Adw.AboutDialog(application_name='Unity', application_icon='org.unityconnect.Unity',
            comments='Connect with your phone. Local. Private. Connected.',
            website='https://github.com/MukhilDharan5/Unity_Linux', license_type=Gtk.License.GPL_3_0).present(self.main_window())

    def select_device(self, action, target):
        win = self.main_window()
        path = target.unpack()
        if win and path in win.devices:
            win.device_dropdown.set_selected(list(win.devices).index(path))
            self.update_desktop()

    def load_preferences(self):
        self.preference_path = Path(GLib.get_user_config_dir()) / 'unity/desktop.json'
        try:
            saved = json.loads(self.preference_path.read_text())
            if isinstance(saved, dict):
                for key, default in self.DEFAULT_PREFERENCES.items():
                    value = saved.get(key, default)
                    if self.valid_preference(key, value): self.preferences[key] = value
        except (OSError, ValueError):
            pass
        self.apply_appearance()

    @staticmethod
    def valid_preference(key, value):
        return (type(value) is bool if key in ('keep_running', 'show_status') else
                value in ('system', 'light', 'dark') if key == 'appearance' else
                value in ('auto', 'lan', 'bluetooth') if key == 'audio_transport' else False)

    def apply_appearance(self):
        Adw.StyleManager.get_default().set_color_scheme({'system': Adw.ColorScheme.DEFAULT,
            'light': Adw.ColorScheme.FORCE_LIGHT, 'dark': Adw.ColorScheme.FORCE_DARK}[self.preferences['appearance']])

    def set_preference(self, key, value):
        if not self.valid_preference(key, value): return
        if self.preferences[key] != value:
            updated = dict(self.preferences, **{key: value})
            try:
                self.preference_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                Gio.File.new_for_path(str(self.preference_path)).replace_contents(
                    (json.dumps(updated, indent=2) + '\n').encode(), None, False, Gio.FileCreateFlags.PRIVATE, None)
            except (OSError, GLib.Error) as error:
                win = self.main_window()
                if win: win.error('Could not save desktop settings: ' + str(error))
                self.update_desktop()
                return
            self.preferences = updated
            self.apply_appearance()
        if key == 'keep_running' and not value:
            win = self.main_window()
            if win and not win.get_visible() and not win.popup_only: win.present()
        self.update_desktop()

    def add_action_handler(self, name, callback, parameter=None, state=None):
        parameter_type = GLib.VariantType.new(parameter) if parameter else None
        action = (Gio.SimpleAction.new_stateful(name, parameter_type, GLib.Variant('b' if type(state) is bool else 's', state))
                  if state is not None else Gio.SimpleAction.new(name, parameter_type))
        def activated(action, value):
            if not action.get_enabled(): return
            callback(value.unpack() if value is not None else not action.get_state().unpack() if type(state) is bool else None)
            self.update_desktop()
        action.connect('activate', activated)
        if state is not None: action.connect('change-state', activated)
        self.add_action(action)

    def add_quick_actions(self):
        handlers = {
            'share-link': (lambda w, _: self.share_link(), None, None),
            'new-message': (lambda w, _: (self.show_page('messages'), w.compose_message()), None, None),
            'browse-files': (lambda w, _: (self.show_page('files'), w.execute('browsing', 'list', {'id': ''}, done=w.show_browser)), None, None),
            'open-received-file': (lambda w, _: w.open_received_file(), None, None),
            'lock-phone': (lambda w, _: w.execute('lock', 'lock'), None, None),
            'hotspot-on': (lambda w, _: w.request_phone_hotspot(True), None, None),
            'hotspot-off': (lambda w, _: w.request_phone_hotspot(False), None, None),
            'disconnect-bluetooth': (self.disconnect_bluetooth, 's', None),
            'refresh-connections': (lambda w, _: w.execute('phone_connectivity', 'refresh'), None, None),
            'ringer-mode': (lambda w, mode: w.execute('ringer', 'set', {'mode': mode})
                if mode in ('normal', 'vibrate', 'silent', 'dnd', 'off') else None, 's', ''),
            'media-play-pause': (lambda w, _: w.toggle_media(), None, None),
            'media-previous': (lambda w, _: w.media_action('previous'), None, None),
            'media-next': (lambda w, _: w.media_action('next'), None, None),
            'media-player': (self.select_player, 's', ''),
            'audio-start': (self.start_audio, 's', None),
            'audio-stop': (lambda w, _: w.request_audio_route(w.audio_state.get('source', 'desktop'), False), None, None),
            'screen-start': (self.start_screen_mode, 's', None),
            'laptop-display': (lambda w, _: w.toggle_laptop_display(), None, None),
            'firefox-enabled': (lambda w, desired: w.firefox_switch.set_active(desired), None, False),
        }
        for source, prefix in [('phone', 'phone'), ('desktop', 'laptop')]:
            handlers[prefix + '-muted'] = (lambda w, desired, source=source: self.set_muted(w, source, desired), None, False)
            for direction, amount in [('up', 10), ('down', -10)]:
                handlers[prefix + '-volume-' + direction] = (lambda w, _, source=source, amount=amount:
                    self.change_volume(w, source, amount), None, None)
        self.quick_handlers = handlers
        for name, (_, parameter, state) in handlers.items():
            self.add_action_handler(name, lambda value, name=name: self.run_quick_action(name, value), parameter, state)
        for action, key in [('keep-running', 'keep_running'), ('show-status', 'show_status'),
                            ('appearance', 'appearance'), ('audio-transport', 'audio_transport')]:
            value = self.preferences[key]
            self.add_action_handler(action, lambda value, key=key: self.set_preference(key, value),
                                    's' if type(value) is str else None, value)
        self.set_accels_for_action('app.new-message', ['<primary>n'])
        self.set_accels_for_action('app.share-link', ['<primary>l'])

    def action_availability(self, win):
        if win is None or not hasattr(win, 'client'):
            return {name: False for name in self.quick_handlers}
        active_screen = win.screen_state.get('state') in ('starting', 'streaming')
        active_audio = win.audio_state.get('state') in ('starting', 'streaming')
        available = {'share-link': win.enabled('urls'), 'new-message': win.enabled('sms'),
            'browse-files': win.enabled('browsing'), 'open-received-file': bool(win.authorized and win.received_path),
            'lock-phone': win.enabled('lock'), 'hotspot-on': win.enabled('samsung_routines') and not win.hotspot_command_pending,
            'hotspot-off': win.enabled('samsung_routines') and not win.hotspot_command_pending,
            'disconnect-bluetooth': win.enabled('samsung_routines') and bool(win.phone_device_rows),
            'refresh-connections': win.enabled('phone_connectivity'), 'ringer-mode': win.enabled('ringer'),
            'media-play-pause': win.media_toggle.get_sensitive(), 'media-previous': win.media_previous.get_sensitive(),
            'media-next': win.media_next.get_sensitive(), 'media-player': bool(win.authorized and win.media_players),
            'audio-start': win.enabled('audio') and not active_audio and not win.audio_pending,
            'audio-stop': win.enabled('audio') and active_audio and not win.audio_pending,
            'screen-start': win.enabled('screen') and not active_screen,
            'laptop-display': win.laptop_display_button.get_sensitive(),
            'firefox-enabled': win.authorized and win.firefox_switch.get_sensitive()}
        hotspot = win.phone_connectivity_state.get('hotspot_enabled')
        available['hotspot-on'] = available['hotspot-on'] and hotspot is not True
        available['hotspot-off'] = available['hotspot-off'] and hotspot is not False
        for source, prefix in [('phone', 'phone'), ('desktop', 'laptop')]:
            allowed = win.sound_widgets[source]['mute'].get_sensitive()
            sound = win.sound_state.get('sound' if source == 'phone' else 'desktop_sound', {})
            available[prefix + '-muted'] = allowed
            available[prefix + '-volume-up'] = allowed and sound.get('volume', 0) < 100
            available[prefix + '-volume-down'] = allowed and sound.get('volume', 0) > 0
        return available

    def run_quick_action(self, name, value):
        win = self.main_window()
        if self.action_availability(win).get(name): self.quick_handlers[name][0](win, value)

    def set_muted(self, win, source, desired):
        sound = win.sound_state.get('sound' if source == 'phone' else 'desktop_sound', {})
        if sound.get('muted', False) != desired: win.mute_sound(source)

    def change_volume(self, win, source, amount):
        sound = win.sound_state.get('sound' if source == 'phone' else 'desktop_sound', {})
        data = {'source': source, 'volume': max(0, min(100, sound.get('volume', 0) + amount))}
        if source == 'desktop': data['output'] = sound.get('output', '')
        win.execute('sound', 'volume', data, quiet=True)

    def disconnect_bluetooth(self, win, address):
        row = win.phone_device_rows.get(address)
        if row and row.get_sensitive() and address not in win.bluetooth_commands_pending:
            win.request_bluetooth_disconnect(address)

    def select_player(self, win, target):
        for index, player in enumerate(win.media_players):
            if json.dumps([player.get('origin'), player.get('session', '')]) == target:
                win.media_picker.set_selected(index)
                break

    def start_audio(self, win, source):
        if source in ('desktop', 'phone'):
            win.request_audio_route(source, True)

    def start_screen_mode(self, win, mode):
        if mode not in ('phone', 'view', 'control', 'extend', 'primary'): return
        if mode != 'phone':
            win.screen_extend_switch.set_active(mode == 'extend')
            win.screen_control_switch.set_active(mode != 'view')
            win.screen_blank_switch.set_active(mode == 'primary')
        win.start_screen('phone' if mode == 'phone' else 'desktop')

    def share_link(self):
        self.activate()
        win = self.main_window()
        target = (win.path, win.generation)
        entry = Gtk.Entry(placeholder_text='https://example.com', activates_default=True)
        dialog = Adw.AlertDialog(heading='Send a link to your phone',
            body='Open a web page on ' + win.devices.get(win.path, {}).get('Name', 'your phone') + '.')
        dialog.set_extra_child(entry)
        dialog.add_response('cancel', 'Cancel'); dialog.add_response('send', 'Send Link')
        dialog.set_response_appearance('send', Adw.ResponseAppearance.SUGGESTED)
        dialog.set_default_response('send'); dialog.set_close_response('cancel')
        def valid_link():
            try:
                uri = GLib.Uri.parse(entry.get_text().strip(), GLib.UriFlags.NONE)
                return uri.get_scheme() in ('http', 'https') and bool(uri.get_host())
            except GLib.Error: return False
        entry.connect('changed', lambda *_: dialog.set_response_enabled('send', valid_link()))
        dialog.set_response_enabled('send', False)
        def response(dialog, response):
            if response != 'send' or not valid_link(): return
            if target != (win.path, win.generation) or not win.enabled('urls'):
                win.error('The selected phone changed or disconnected. Open Send Link again.')
                return
            win.execute('urls', 'open', {'url': entry.get_text().strip()})
        dialog.connect('response', response)
        dialog.present(win)

    def set_feature(self, feature, enabled):
        win = self.main_window()
        if not win or not win.authorized or not win.path: return
        cap = win.capabilities.get(feature, {})
        token = (win.path, win.generation, feature)
        if cap.get('status') not in ('available', 'disabled', 'user_action_required') or token in self.feature_pending: return
        if (cap['status'] != 'disabled') == enabled: return
        self.feature_pending.add(token)
        path = win.path
        def finished(message=None):
            self.feature_pending.discard(token)
            if message and (win.path, win.generation) == token[:2]: win.error(message)
            win.changed(path); self.update_desktop()
        win.client.call(path, 'org.unityconnect.Device1', 'SetFeatureEnabled', '(sb)', (feature, enabled),
                        lambda _: finished(), failed=finished)
        self.update_desktop()

    def update_desktop(self):
        # Coalesce all widget/state renders into one menu update after the full
        # device snapshot has been applied, including during window construction.
        if self.desktop_source is None:
            self.desktop_source = GLib.idle_add(self.sync_desktop)

    def sync_desktop(self):
        self.desktop_source = None
        win = self.main_window()
        valid = win is not None and hasattr(win, 'client')
        path = win.path if valid else ''
        availability = self.action_availability(win)
        availability.update({
            'find-phone': valid and win.enabled('find'),
            'stop-screen': valid and win.enabled('screen') and win.screen_state.get('state') in ('starting', 'streaming'),
            'refresh': valid and win.authorized, 'discover': valid and win.authorized,
            'device': valid and bool(win.devices),
            'audio-transport': valid and win.audio_transport.get_sensitive()})
        for name, enabled in availability.items(): self.lookup_action(name).set_enabled(bool(enabled))
        for page in self.PAGE_TITLES:
            self.lookup_action('page-' + page).set_enabled(bool(valid and win.pages[page].get_visible()))
        self.lookup_action('device').set_state(GLib.Variant('s', path or ''))
        for name, key in [('keep-running', 'keep_running'), ('show-status', 'show_status'),
                          ('appearance', 'appearance'), ('audio-transport', 'audio_transport')]:
            value = self.preferences[key]
            self.lookup_action(name).set_state(GLib.Variant('b' if type(value) is bool else 's', value))
        if valid:
            win.desktop_preferences_updating = True
            win.background_switch.set_active(self.preferences['keep_running'])
            win.indicator_status_switch.set_active(self.preferences['show_status'])
            win.appearance_combo.set_selected(['system', 'light', 'dark'].index(self.preferences['appearance']))
            win.audio_transport.set_selected(['auto', 'lan', 'bluetooth'].index(self.preferences['audio_transport']))
            win.desktop_preferences_updating = False
        self.lookup_action('ringer-mode').set_state(GLib.Variant('s', win.ringer_state.get('mode', '') if valid else ''))
        self.lookup_action('firefox-enabled').set_state(GLib.Variant('b', bool(valid and win.firefox_status.get('enabled'))))
        player = win.media_state if valid else {}
        self.lookup_action('media-player').set_state(GLib.Variant('s', json.dumps([player.get('origin'), player.get('session', '')])))
        for source, prefix in [('phone', 'phone'), ('desktop', 'laptop')]:
            sound = win.sound_state.get('sound' if source == 'phone' else 'desktop_sound', {}) if valid else {}
            self.lookup_action(prefix + '-muted').set_state(GLib.Variant('b', bool(sound.get('muted'))))
        for feature, cap in (win.capabilities.items() if valid else []):
            if feature not in self.feature_actions and cap.get('status') in ('available', 'disabled', 'user_action_required'):
                # Feature IDs are protocol identifiers. Encode them to keep
                # GAction names valid and distinct, including underscores.
                name = 'feature-' + feature.encode().hex()
                self.feature_actions[feature] = name
                self.add_action_handler(name, lambda enabled, f=feature: self.set_feature(f, enabled), state=False)
        for feature, name in self.feature_actions.items():
            cap = win.capabilities.get(feature, {}) if valid else {}
            action = self.lookup_action(name)
            action.set_state(GLib.Variant('b', cap.get('status') in ('available', 'user_action_required')))
            action.set_enabled(bool(valid and win.authorized and path and cap.get('status') in
                ('available', 'disabled', 'user_action_required') and (path, win.generation, feature) not in self.feature_pending))
        groups, sharing = self.desktop_menu_groups(win if valid else None)
        for key, entries in groups.items():
            signature = repr(self.native_menu_shape(entries))
            if self.menu_signatures.get(key) != signature:
                self.menu_signatures[key] = signature
                self.fill_native_menu(self.menu_models[key], entries)
        if not self.indicator: return False
        props = win.devices.get(path, {}) if valid else {}
        connected = bool(valid and win.authorized and props.get('Connected'))
        status = (win.connection_value.get_label() if connected else
                  'Access required' if valid and not win.authorized else 'Disconnected').removesuffix(' connected')
        battery = win.battery_value.get_label() if connected else 'Unavailable'
        if battery != 'Unavailable': status += ' · ' + battery
        phone_name = props.get('Name', 'No paired phone')
        item, separator = self.menu_item, self.menu_separator
        items = [item('phone-name', phone_name), item('status', status)] if self.preferences['show_status'] else []
        items.extend([item('show', 'Open Unity', 'show'),
                      item(str(path) + ':find-phone', 'Stop Ringing' if valid and win.find_state.get('state') == 'ringing' else 'Find Phone', 'find-phone'),
                      item(str(path) + ':send-files', 'Send Files…', 'send-files'), separator('indicator-actions')])
        for key, title, entries in [('file', 'Share', sharing), ('phone', 'Phone', groups['phone']),
                ('media', 'Media and Audio', groups['media']), ('screen', 'Screen Sharing', groups['screen']),
                ('view', 'Open Page', groups['view']), ('settings', 'Settings', groups['settings'])]:
            items.append(item(str(path) + ':group-' + key, title, children=entries))
        items.extend([separator('indicator-quit'), item('quit', 'Quit Unity', 'quit')])
        self.indicator.update(items, phone_name + ' · ' + status)
        return False

    def menu_item(self, key, title, action=None, target=None, children=None):
        props = {'label': title, 'enabled': False}
        if children is not None:
            props['enabled'] = any(child['props'].get('enabled') for child in children)
        elif action:
            command = self.lookup_action(action)
            props['enabled'] = command.get_enabled()
            state = command.get_state()
            if state is not None and state.get_type_string() == 'b':
                props.update({'toggle-type': 'checkmark', 'toggle-state': int(state.unpack())})
            elif state is not None and target is not None:
                props.update({'toggle-type': 'radio', 'toggle-state': int(state.unpack() == target)})
        node = dict(key=key, props=props, action=action, target=target)
        if children is not None: node['children'] = children
        return node

    @staticmethod
    def menu_separator(key):
        return dict(key=key, props={'type': 'separator'})

    def desktop_menu_groups(self, win):
        path = (win.path or '') if win else ''
        def item(title, action=None, target=None, children=None, key=None):
            return self.menu_item(key or path + ':' + (action or title) + ':' + str(target or ''), title, action, target, children)
        def separator(key): return self.menu_separator(path + ':' + key)
        find = 'Stop Ringing' if win and win.find_state.get('state') == 'ringing' else 'Find Phone'
        view = [item(title, 'page-' + page) for page, title in self.PAGE_TITLES.items()]
        sharing = [item('Send Files…', 'send-files'), item('Send a Link…', 'share-link'), item('New Message…', 'new-message'),
            item('Browse Phone Files', 'browse-files'), item('Open Last Received File', 'open-received-file'),
            item('Photos and Videos', 'page-photos'), item('Notifications', 'page-notifications'), item('Calls', 'page-calls')]
        files = [item('Open Unity', 'show'), *sharing, separator('file-end'),
                 item('Pair a Phone…', 'discover'), item('Refresh', 'refresh'), item('Quit Unity', 'quit')]
        phones = [item(find, 'find-phone'), item('Lock Phone', 'lock-phone'), separator('phone-controls')]
        ringer = [item(title, 'ringer-mode', mode) for mode, title in [('normal', 'Ring'), ('vibrate', 'Vibrate'),
                  ('silent', 'Silent'), ('dnd', 'Do Not Disturb'), ('off', 'Turn Off Do Not Disturb')]]
        phones.append(item('Phone Sound', children=ringer))
        hotspot = win.phone_connectivity_state.get('hotspot_enabled') if win else None
        phones.append(item('Mobile Hotspot', children=[
            item('On' if hotspot is True else 'Off' if hotspot is False else 'State unavailable'),
            item('Turn On Hotspot', 'hotspot-on'), item('Turn Off Hotspot', 'hotspot-off')]))
        bluetooth = []
        if win:
            for device in win.phone_connectivity_state.get('devices', []):
                address = device.get('address', '')
                if address not in win.phone_device_rows: continue
                pending = address in win.bluetooth_commands_pending
                bluetooth.append(item(device.get('name') or address, None if pending else 'disconnect-bluetooth',
                    None if pending else address, key=path + ':bluetooth:' + address))
        if bluetooth: phones.append(item('Disconnect Bluetooth Device', children=bluetooth))
        phones.append(item('Refresh Phone Connections', 'refresh-connections'))
        devices = [item(props.get('Name', 'Phone') + (' · Connected' if props.get('Connected') else ' · Disconnected'),
                   'device', device) for device, props in (win.devices.items() if win else [])]
        if devices: phones.extend([separator('phone-select'), item('Selected Phone', children=devices)])
        phones.append(item('Pair a Phone…', 'discover'))
        phones.append(item('Refresh Unity', 'refresh'))
        players = [item(('Laptop' if p.get('origin') == 'desktop' else 'Phone') + ' · ' +
                   (p.get('app_name') or p.get('package') or 'Player'), 'media-player',
                   json.dumps([p.get('origin'), p.get('session', '')])) for p in (win.media_players if win else [])]
        media = [item((win.media_state.get('title') or 'Nothing playing')[:48] if win else 'Nothing playing')]
        if players: media.append(item('Choose Player', children=players))
        media.extend([item('Pause' if win and win.media_state.get('playing') else 'Play', 'media-play-pause'),
                      item('Previous Track', 'media-previous'), item('Next Track', 'media-next'), separator('media-volume')])
        for source, prefix, title in [('phone', 'phone', 'Phone Volume'), ('desktop', 'laptop', 'Laptop Volume')]:
            sound = win.sound_state.get('sound' if source == 'phone' else 'desktop_sound', {}) if win else {}
            media.append(item(title, children=[item(str(sound['volume']) + '%' if sound.get('available') and 'volume' in sound else 'Unavailable'),
                item('Mute', prefix + '-muted'), item('Volume Up (10%)', prefix + '-volume-up'),
                item('Volume Down (10%)', prefix + '-volume-down')]))
        transport = [item(title, 'audio-transport', value) for value, title in
                     [('auto', 'Automatic'), ('lan', 'Wi-Fi / LAN'), ('bluetooth', 'Bluetooth')]]
        media.append(item('Audio Streaming', children=[item('Play Laptop Audio on Phone', 'audio-start', 'desktop'),
            item('Play Phone Audio on Laptop', 'audio-start', 'phone'), item('Stop Audio Streaming', 'audio-stop'),
            separator('audio-connection'), item('Audio Connection', children=transport)]))
        screen = [item(title, 'screen-start', mode) for title, mode in [
            ('View Phone Screen', 'phone'), ('Share Computer Screen (View Only)', 'view'),
            ('Share and Control Computer', 'control'), ('Use Phone as Extra Display', 'extend'), ('Use Phone as Main Display', 'primary')]]
        screen.extend([item('Stop Screen Sharing', 'stop-screen'),
            item('Turn Laptop Display ' + ('Off' if not win or win.screen_state.get('display_on', True) else 'On'), 'laptop-display'),
            separator('screen-settings'), item('Screen and Input Settings', 'page-remote')])
        features = []
        for feature, cap in sorted(win.capabilities.items() if win else [], key=lambda pair: self.FEATURE_TITLES.get(pair[0], pair[0])):
            if cap.get('status') in ('available', 'disabled', 'user_action_required'):
                features.append(item(self.FEATURE_TITLES.get(feature, feature.replace('_', ' ').title()), self.feature_actions[feature]))
        settings = [item('Open Settings', 'page-settings')]
        if features: settings.append(item('Phone Features', children=features))
        settings.extend([item('Appearance', children=[item(title, 'appearance', value) for value, title in
                         [('system', 'Follow System'), ('light', 'Light'), ('dark', 'Dark')]]),
            item('Keep Running When Window Closes', 'keep-running'), item('Show Phone Status in Indicator', 'show-status'),
            item('Firefox Companion', 'firefox-enabled'), separator('settings-pages'),
            item('Connectivity Settings', 'page-connectivity'), item('Audio and Volume Settings', 'page-media'), item('About Unity', 'about')])
        return dict(file=files, phone=phones, media=media, screen=screen, view=view, settings=settings,
                    help=[item('About Unity', 'about')]), sharing

    def fill_native_menu(self, menu, entries):
        menu.remove_all()
        section = Gio.Menu()
        for node in entries:
            if node['props'].get('type') == 'separator':
                if section.get_n_items(): menu.append_section(None, section)
                section = Gio.Menu()
                continue
            if 'children' in node:
                child = Gio.Menu(); self.fill_native_menu(child, node['children'])
                section.append_submenu(node['props']['label'], child)
            else:
                entry = Gio.MenuItem.new(node['props']['label'], 'app.' + node['action'] if node['action'] else None)
                if node['target'] is not None:
                    entry.set_action_and_target_value('app.' + node['action'], GLib.Variant('s', node['target']))
                section.append_item(entry)
        if section.get_n_items(): menu.append_section(None, section)

    @classmethod
    def native_menu_shape(cls, entries):
        # Enabled and checked states are already exported by GAction; changing
        # them must not rebuild an open native menu.
        return [(node['props'].get('label'), node['props'].get('type'), node.get('action'), node.get('target'),
                 cls.native_menu_shape(node.get('children', []))) for node in entries]

    def do_shutdown(self):
        if self.desktop_source is not None:
            GLib.source_remove(self.desktop_source)
            self.desktop_source = None
        if self.indicator:
            self.indicator.close()
        Adw.Application.do_shutdown(self)

    def do_command_line(self, command_line):
        for option_name, page in [('open-messages', 'messages'), ('open-notifications', 'notifications')]:
            target = command_line.get_options_dict().lookup_value(option_name, None)
            if target is None: continue
            device = target.unpack()
            if len(device) != 32 or any(c not in '0123456789abcdef' for c in device): return 1
            win = next((w for w in self.get_windows() if isinstance(w, Window)), None)
            if not win: win = Window(self)
            win.popup_only = False
            win.popup_device = device
            win.open_page = ('/org/unityconnect/devices/d_' + device, page)
            win.present(); win.changed(ROOT)
            return 0
        option = command_line.get_options_dict().lookup_value('call-popup', None)
        if option is None:
            self.activate()
            return 0
        device = option.unpack()
        if len(device) != 32 or any(c not in '0123456789abcdef' for c in device):
            return 1
        win = next((w for w in self.get_windows() if isinstance(w, Window)), None)
        if not win:
            win = Window(self)
            win.popup_only = True
            # A call can end while the verified launcher is starting.
            GLib.timeout_add_seconds(15, lambda: (win.destroy() if win.popup_only and not win.popup_has_calls else None, False)[1])
        win.popup_device = device
        win.call_popup_dismissed.clear()
        if win.path == '/org/unityconnect/devices/d_' + device:
            win.show_call_popup(force=True)
        else:
            win.popup_has_calls = False
            win.render({})
        win.changed(ROOT)
        return 0

    def add_file_drop_target(self, app, window):
        target = Gtk.DropTarget.new(Gdk.FileList, Gdk.DragAction.COPY)
        target.set_gtypes([Gdk.FileList, Gio.File])
        target.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)
        def dropped(target, value, x, y):
            win = next((w for w in self.get_windows() if isinstance(w, Window)), None)
            if win is None:
                return False
            files = value.get_files() if isinstance(value, Gdk.FileList) else [value]
            return win.select_files(files)
        target.connect('drop', dropped)
        window.add_controller(target)


if __name__ == '__main__':
    raise SystemExit(App().run(sys.argv))
