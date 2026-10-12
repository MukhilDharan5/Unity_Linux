#!/usr/bin/env python3
"""Native Unity client. Phone operations use only the public versioned D-Bus API."""
import base64
import datetime
import json
from pathlib import Path
import shutil
import signal
import gi

gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
from gi.repository import Adw, Gdk, Gio, GLib, GLibUnix, Gtk, Pango

BUS = 'org.unityconnect'
ROOT = '/org/unityconnect/Manager'

CSS = '''
.unity-content { padding: 24px; }
.unity-card { background: @card_bg_color; border: 1px solid alpha(@window_fg_color, 0.12); border-radius: 16px; }
.notification-card { padding: 18px; }
.metric-card { padding: 20px; }
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


def image_texture(image):
    """Decode only bounded, daemon-validated JPEG data; never fetch remote URLs."""
    if not image or image.get('mime') != 'image/jpeg':
        return None
    if not all(isinstance(image.get(k), int) and 1 <= image[k] <= 320 for k in ('width', 'height')):
        return None
    try:
        encoded = image['data']
        if len(encoded) > 43692:
            return None
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > 32768:
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


class Window(Adw.ApplicationWindow):
    def __init__(self, app, client_factory=Client):
        super().__init__(application=app, title='Unity', default_width=1140, default_height=780)
        provider = Gtk.CssProvider()
        provider.load_from_string(CSS)
        Gtk.StyleContext.add_provider_for_display(self.get_display(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        self.devices, self.path, self.capabilities = {}, None, {}
        self.pending_refresh = self.authorized = self.requested_access = False
        self.access_check_pending = False
        self.bus_name = None
        self.pairing_active = self.updating_dropdown = False
        self.operations = {}
        self.generation = 0
        self.last_notifications = None
        self.notification_cards = {}
        self.pending_seek = None
        self.messages_loaded = self.photos_loaded = False
        self.sms_revision = None
        self.message_data = []
        self.selected_address = None
        self.photo_items, self.photo_widgets, self.preview_queue = {}, {}, []
        self.preview_inflight = 0
        self.cap_signature = None
        self.toasts = Adw.ToastOverlay()
        self.set_content(self.toasts)
        self.split = Adw.NavigationSplitView(min_sidebar_width=220, max_sidebar_width=260,
                                             sidebar_width_unit=Adw.LengthUnit.SP)
        self.toasts.set_child(self.split)
        sidebar = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        sidebar.append(Adw.HeaderBar(title_widget=Adw.WindowTitle(title='Unity')))
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
        self.split.set_sidebar(Adw.NavigationPage.new(sidebar, 'Unity'))
        content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.title = Adw.WindowTitle(title='Overview', subtitle='')
        content.append(Adw.HeaderBar(title_widget=self.title))
        self.banner = Adw.Banner(title='Connecting to Unity…', revealed=True)
        self.banner.set_button_label('Retry')
        self.banner.connect('button-clicked', self.banner_action)
        content.append(self.banner)
        self.stack = Gtk.Stack(vexpand=True)
        content.append(self.stack)
        self.split.set_content(Adw.NavigationPage.new(content, 'Overview'))
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

    def metric(self, flow, icon, title):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, width_request=180)
        box.add_css_class('unity-card')
        box.add_css_class('metric-card')
        header = Gtk.Box(spacing=8)
        header.append(Gtk.Image(icon_name=icon))
        header.append(label(title, 'dim-label'))
        box.append(header)
        value = label('Unavailable', 'title-2', wrap=True)
        detail = label('', 'dim-label', wrap=True)
        detail.add_css_class('caption')
        box.append(value)
        box.append(detail)
        flow.insert(box, -1)
        return value, detail, box

    def empty(self, title, description, icon):
        return Adw.StatusPage(title=title, description=description, icon_name=icon)

    def build_pages(self):
        overview = self.page('overview', 'Overview', 'view-grid-symbolic')
        self.device_heading = label('Your phone, at a glance', 'title-1', wrap=True)
        overview.append(self.device_heading)
        metrics = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, homogeneous=True,
                              min_children_per_line=1, max_children_per_line=3, column_spacing=12, row_spacing=12)
        self.connection_value, self.connection_detail, _ = self.metric(metrics, 'network-wireless-symbolic', 'Connection')
        self.battery_value, self.battery_detail, battery_card = self.metric(metrics, 'battery-symbolic', 'Battery')
        self.battery_progress = Gtk.ProgressBar(show_text=False)
        battery_card.append(self.battery_progress)
        self.network_value, self.network_detail, _ = self.metric(metrics, 'network-cellular-symbolic', 'Network')
        overview.append(metrics)
        quick = self.group(overview, 'Quick actions')
        self.find_button = self.button(quick, 'Find your phone', 'Ring', lambda *_: self.execute('find', 'ring'))
        self.url_entry, self.url_button = self.entry_action(quick, 'Share a web address', 'send-to-symbolic',
            lambda *_: self.execute('urls', 'open', {'url': self.url_entry.get_text()}))
        self.clip_entry, self.clip_button = self.entry_action(quick, 'Send text to phone clipboard', 'edit-copy-symbolic',
            lambda *_: self.execute('clipboard', 'set', {'text': self.clip_entry.get_text()}))
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
        calls = self.page('calls', 'Calls', 'call-start-symbolic')
        self.heading(calls, 'Phone calls', 'Call information shared by your phone.')
        cg = self.group(calls, 'Current call')
        self.call_row = Adw.ActionRow(title='No active call', subtitle='')
        self.call_row.add_prefix(Gtk.Image(icon_name='call-start-symbolic'))
        cg.add(self.call_row)

        files = self.page('files', 'Files', 'folder-symbolic')
        self.heading(files, 'Move files between devices', 'Send a file or browse a folder shared by your phone.')
        fg = self.group(files, 'File transfers')
        self.file_button = self.button(fg, 'Send to phone', 'Choose file…', self.choose_file)
        self.file_status = Adw.ActionRow(title='No transfer active')
        self.file_status.set_subtitle_lines(2)
        self.file_open = icon_button('folder-open-symbolic', 'Show received file', self.open_received_file, 'flat')
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
        self.heading(photos, 'Your recent photos', 'Small previews load first. Download an original when you need it.', self.photos_button)
        self.photo_status = label('Open Photos to load your gallery.', 'dim-label', wrap=True)
        photos.append(self.photo_status)
        self.photos = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, homogeneous=True,
            min_children_per_line=1, max_children_per_line=5, column_spacing=12, row_spacing=12)
        self.photos.add_css_class('gallery')
        photos.append(self.photos)
        self.photos_empty = self.empty('Your gallery is waiting', 'Connect your phone and allow photo sharing.', 'image-x-generic-symbolic')
        photos.append(self.photos_empty)

        media = self.page('media', 'Media', 'multimedia-player-symbolic')
        self.media_heading = self.heading(media, 'Now playing', 'Control the active media session on your phone.')
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
        self.media_artist = label('Start playback on your phone.', 'dim-label', wrap=True, halign=Gtk.Align.CENTER)
        self.media_album = label('', 'dim-label', wrap=True, halign=Gtk.Align.CENTER)
        self.media_album.add_css_class('caption')
        for widget in (self.media_title, self.media_artist, self.media_album):
            self.media_card.append(widget)
        progress = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self.media_seek = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 1, 1)
        self.media_seek.set_draw_value(False)
        self.media_seek.connect('change-value', self.seek_media)
        progress.append(self.media_seek)
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
        self.media_previous = icon_button('media-skip-backward-symbolic', 'Previous track', lambda *_: self.execute('media', 'previous'), 'circular')
        self.media_toggle = icon_button('media-playback-start-symbolic', 'Play', self.toggle_media, 'suggested-action')
        self.media_toggle.add_css_class('player-primary')
        self.media_next = icon_button('media-skip-forward-symbolic', 'Next track', lambda *_: self.execute('media', 'next'), 'circular')
        for button in (self.media_previous, self.media_toggle, self.media_next):
            button.add_css_class('player-control')
            controls.append(button)
        self.media_card.append(controls)
        self.media_info = label('No active session', 'dim-label', halign=Gtk.Align.CENTER)
        self.media_info.add_css_class('caption')
        self.media_card.append(self.media_info)
        self.media_state = {}
        self.art_key = None

        remote = self.page('remote', 'Remote Control', 'input-keyboard-symbolic')
        self.heading(remote, 'A little closer to your phone', 'Keyboard, touch input, and phone sound controls.')
        rg = self.group(remote, 'Phone input', 'Enable Unity’s keyboard or accessibility service on your phone first.')
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
        ring = self.group(remote, 'Phone sound')
        self.ringer_combo = Adw.ComboRow(title='Ringer mode', model=Gtk.StringList.new(['Normal', 'Vibrate', 'Silent', 'DND', 'DND off']))
        ring.add(self.ringer_combo)
        self.ringer_button = self.button(ring, 'Apply mode', 'Apply', lambda *_: self.execute('ringer', 'set',
            {'mode': ['normal', 'vibrate', 'silent', 'dnd', 'off'][self.ringer_combo.get_selected()]}))
        self.hotspot_button = self.button(ring, 'Phone internet sharing', 'Request', lambda *_: self.execute('hotspot', 'request_settings'), 'Review internet sharing settings on your phone.')
        settings = self.page('settings', 'Settings', 'preferences-system-symbolic', preferences=True)
        security = self.group(settings, 'Privacy and access')
        self.access_row = Adw.ActionRow(title='Access to your phone', subtitle='Checking access…')
        self.access_row.set_subtitle_lines(3)
        self.access_button = Gtk.Button(label='Allow access', valign=Gtk.Align.CENTER)
        self.access_button.connect('clicked', self.authorize_this_app)
        self.access_button.set_sensitive(False)
        self.access_row.add_suffix(self.access_button)
        security.add(self.access_row)
        appearance = Adw.ComboRow(title='Appearance', model=Gtk.StringList.new(['System', 'Light', 'Dark']))
        appearance.connect('notify::selected', lambda row, _: Adw.StyleManager.get_default().set_color_scheme(
            [Adw.ColorScheme.DEFAULT, Adw.ColorScheme.FORCE_LIGHT, Adw.ColorScheme.FORCE_DARK][row.get_selected()]))
        security.add(appearance)
        pairing = self.group(settings, 'Pair devices')
        self.discovery_button = self.button(pairing, 'Discover on this network', 'Discover', self.discover)
        self.pair_info = label('Pairing is closed', 'dim-label', wrap=True, selectable=True)
        pairing.add(self.pair_info)
        self.pair_requests = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        pairing.add(self.pair_requests)
        self.unpair_button = self.button(pairing, 'Remove selected device', 'Unpair', self.unpair)
        self.cap_group = self.group(settings, 'Permissions and capabilities', 'Features depend on the permissions and support available on your phone.')
        self.cap_rows = []
        self.nav.select_row(self.pages['overview'])

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
        elif page == 'messages' and self.enabled('sms'):
            self.load_messages()

    def error(self, message):
        self.access_check_pending = False
        if 'NotAuthorized' in message:
            self.authorized = False
            self.render({})
            self.show_access_prompt()
            return
        if 'ServiceUnknown' in message or 'NameHasNoOwner' in message:
            message = 'Unity daemon is stopped. Start unityd with systemctl --user start unityd.'
        if any(s in message for s in ('NotAuthorized', 'daemon stopped', 'daemon is stopped', 'Disconnected')):
            self.authorized = False
            self.render({})
        self.banner.set_title(message)
        self.banner.set_button_label('Retry')
        self.banner.set_revealed(True)

    def show_access_prompt(self):
        self.access_row.set_subtitle('Allow Unity to show phone notifications, messages, and files, and control supported features.')
        self.access_button.set_visible(True)
        self.access_button.set_sensitive(bool(self.bus_name))
        self.banner.set_title('Allow Unity to access your phone.')
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
        dialog = Adw.AlertDialog(heading='Allow access to your phone?',
            body='Unity can display notifications, messages, and files, and use controls such as sending SMS and playing media. Access applies to this app session.')
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
        if path == 'daemon-restarted':
            self.requested_access = self.authorized = False
            self.access_check_pending = False
            self.bus_name = None
            self.operations.clear()
            self.render({})
            path = ROOT
        if path == ROOT and self.pairing_active and self.authorized:
            self.client.manager('PairingInfo', done=self.pairing)
        if path in self.operations:
            self.client.call(path, 'org.unityconnect.Operation1', 'GetState', done=lambda s: self.operation(path, json.loads(s)))
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
        self.devices = {p: self.devices.get(p, {}) for p in paths}
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
                lambda props, p=path: self.properties(p, props))

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
                self.client.call(path, 'org.unityconnect.Device1', 'GetState', done=lambda s:
                    self.render(json.loads(s)) if self.path == path and generation == self.generation else None)
            self.render_capabilities()

    def device_selected(self, dropdown, _):
        if self.updating_dropdown:
            return
        paths = list(self.devices)
        index = dropdown.get_selected()
        if index < len(paths) and paths[index] != self.path:
            self.path = paths[index]
            self.capabilities = {}
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
                row = Adw.SwitchRow(title=feature.replace('_', ' ').title(), subtitle=cap['reason'] or cap['status'].replace('_', ' '))
                row.set_subtitle_lines(3)
                row.set_active(cap['status'] != 'disabled')
                row.set_sensitive(self.authorized and cap['status'] in ('available', 'disabled', 'user_action_required'))
                row.connect('notify::active', lambda row, _, f=feature: self.client.call(self.path,
                    'org.unityconnect.Device1', 'SetFeatureEnabled', '(sb)', (f, row.get_active())))
                self.cap_group.add(row)
                self.cap_rows.append(row)
        controls = [(self.find_button, 'find'), (self.url_button, 'urls'), (self.clip_button, 'clipboard'),
                    (self.sms_button, 'sms'), (self.new_message, 'sms'), (self.message_refresh, 'sms'), (self.file_button, 'files'),
                    (self.photos_button, 'photos'), (self.browse_button, 'browsing'), (self.remote_button, 'remote_input'),
                    (self.pad, 'remote_input'), (self.ringer_button, 'ringer'), (self.hotspot_button, 'hotspot')]
        for widget, feature in controls:
            widget.set_sensitive(self.enabled(feature))
        self.discovery_button.set_sensitive(self.authorized)
        self.unpair_button.set_sensitive(bool(self.authorized and self.path))
        groups = {'notifications': ['notifications'], 'messages': ['sms'], 'calls': ['call_notifications'],
                  'files': ['files', 'browsing'], 'photos': ['photos'], 'media': ['media'],
                  'remote': ['remote_input', 'find', 'ringer', 'hotspot']}
        for page, features in groups.items():
            visible = any(self.capabilities.get(f, {}).get('status') in ('available', 'user_action_required') for f in features)
            self.pages[page].set_visible(visible)
            if not visible and self.stack.get_visible_child_name() == page:
                self.nav.select_row(self.pages['overview'])
        self.update_media_controls()

    @staticmethod
    def clear(box):
        while box.get_first_child():
            box.remove(box.get_first_child())

    def render(self, device):
        state = device.get('state', {})
        connected = device.get('connected', False)
        self.device_heading.set_label(device.get('name') or 'Your phone, at a glance')
        self.connection_value.set_label('Connected' if connected else 'Disconnected')
        self.connection_detail.set_label('Ready to share' if connected else 'Live information is unavailable')
        battery = state.get('battery', {})
        self.battery_value.set_label(f"{battery['level']}%" if battery else 'Unavailable')
        self.battery_detail.set_label(('Charging' if battery.get('charging') else 'On battery') if battery else 'Connect your phone')
        self.battery_progress.set_fraction(battery.get('level', 0) / 100)
        network = state.get('network', {})
        self.network_value.set_label({'wifi': 'Wi-Fi', 'cellular': 'Mobile data', 'ethernet': 'Ethernet', 'none': 'Offline'}.get(network.get('kind'), 'Unavailable'))
        signal = network.get('signal')
        self.network_detail.set_label(' · '.join(filter(None, [network.get('operator'), f'Signal {signal}/4' if signal is not None else ''])))
        call = state.get('call_notifications', {})
        self.call_row.set_title({'ringing': 'Incoming call', 'offhook': 'Call in progress', 'idle': 'No active call'}.get(call.get('state'), 'No active call'))
        self.call_row.set_subtitle(call.get('number') or ('Caller information unavailable' if call.get('state') == 'ringing' else ''))
        self.render_media(state.get('media', {}))
        self.clip_receive.set_label(state.get('clipboard', {}).get('text', ''))
        self.clip_receive.set_visible(bool(self.clip_receive.get_label()))
        self.incoming_url = state.get('urls', {}).get('url')
        self.url_receive.set_subtitle(self.incoming_url or 'No shared URL')
        self.url_open.set_sensitive(bool(self.incoming_url))
        self.received_path = state.get('files', {}).get('received_path')
        self.file_status.set_subtitle(self.received_path.rsplit('/', 1)[-1] if self.received_path else '')
        self.file_open.set_visible(bool(self.received_path))
        if self.received_path:
            self.file_status.set_title('File received')
        self.render_notifications(state.get('notifications', {}))
        revision = state.get('sms', {}).get('revision')
        if revision is not None and revision != self.sms_revision:
            self.sms_revision = revision
            self.messages_loaded = False
        if not connected:
            self.generation += 1
            self.messages_loaded = self.photos_loaded = False
            self.sms_revision = None
            self.message_data = []
            self.selected_address = None
            self.clear(self.threads)
            self.clear(self.message_bubbles)
            self.message_bubbles.append(self.empty('Your conversations', 'Connect your phone to view and send messages.', 'chat-symbolic'))
            self.chat_title.set_title('Choose a conversation')
            self.message_hint.set_label('Messages are cleared while disconnected.')
            self.photo_items, self.photo_widgets, self.preview_queue = {}, {}, []
            self.preview_inflight = 0
            self.clear(self.photos)
            self.photos_empty.set_visible(True)
            self.photo_status.set_label('Connect your phone to load previews.')
            self.clear(self.browser)
            self.sms_address.set_text('')
            self.sms_body.get_buffer().set_text('')
        else:
            self.banner.set_revealed(False)
            self.access_row.set_subtitle('Unity has access for this app session.')
        self.render_capabilities()
        if connected and hasattr(self, 'client'):
            self.load_visible()

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
        self.client.call(path, 'org.unityconnect.Operation1', 'GetState', done=lambda s: self.operation(path, json.loads(s)))

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
        self.operations.pop(path, None)
        result = op.get('result') or {}
        if meta['done']:
            meta['done'](result if op['status'] == 'completed' else None)
        if op['status'] != 'completed':
            if not meta['quiet']:
                self.error(op.get('error') or 'Operation failed')
        elif not meta['quiet']:
            self.toasts.add_toast(Adw.Toast(title='Operation completed'))

    def load_messages(self, force=False, address=None):
        if not self.enabled('sms') or (self.messages_loaded and not force and address is None):
            return
        self.messages_loaded = True
        self.message_hint.set_label('Loading messages…')
        self.execute('sms', 'list', {'address': address} if address is not None else {},
            done=lambda result: self.show_messages(result, address), quiet=True)

    def show_messages(self, result, address=None):
        if result is None:
            self.messages_loaded = False
            self.message_hint.set_label('Messages could not be loaded. Try Refresh.')
            return
        items = result.get('messages', [])
        if address is None:
            self.message_data = items
        else:
            self.message_data = [m for m in self.message_data if m.get('address') != address] + items
        self.render_threads()
        if self.selected_address:
            self.render_conversation()
        self.message_hint.set_label(f'{len(self.message_data)} recent messages' if self.message_data else 'No messages found on your phone.')
        if address is None and self.selected_address:
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
            self.send_hint.set_label('Submitted to phone. Carrier delivery is not confirmed.')
            self.selected_address = address
            self.load_messages(force=True, address=address)
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
        self.photo_items, self.photo_widgets, self.preview_queue = {}, {}, []
        # A refreshed list expires Android's old selection tokens; pending responses
        # may finish, but only tokens in the current gallery can update a tile.
        for item in result.get('items', [])[:30]:
            key = item['id']
            self.photo_items[key] = item
            button = Gtk.Button(tooltip_text=item.get('name', 'Photo'))
            button.add_css_class('photo-tile')
            button.set_overflow(Gtk.Overflow.HIDDEN)
            button.connect('clicked', lambda _, k=key: self.open_photo(k))
            tile = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, width_request=156)
            stack = Gtk.Stack(height_request=144, hexpand=True)
            picture = ThumbnailPicture(content_fit=Gtk.ContentFit.COVER, can_shrink=True)
            stack.add_named(picture, 'image')
            placeholder = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER)
            placeholder.append(Gtk.Image(icon_name='image-x-generic-symbolic', pixel_size=32))
            hint = label('Loading preview…', 'dim-label')
            hint.add_css_class('caption')
            placeholder.append(hint)
            stack.add_named(placeholder, 'empty')
            tile.append(stack)
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
            self.photos.insert(button, -1)
            self.photo_widgets[key] = {'stack': stack, 'picture': picture, 'hint': hint, 'texture': None}
            if result.get('preview_supported'):
                self.preview_queue.append(key)
            else:
                hint.set_label('Preview unavailable')
        count = len(self.photo_items)
        self.photos_empty.set_visible(not count)
        self.photo_status.set_label(f'{count} recent photos' if result.get('preview_supported') else
            'Update the Android companion to load low-resolution previews.' if count else 'No photos available. Check photo access on your phone.')
        self.pump_previews()

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
        download.set_sensitive(self.enabled('photos'))
        download.connect('clicked', lambda *_: self.execute('photos', 'get', {'id': key}))
        info.append(download)
        box.append(info)
        dialog.set_child(box)
        dialog.present(self)

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

    def render_media(self, media):
        if not media and self.pending_seek:
            GLib.source_remove(self.pending_seek)
            self.pending_seek = None
        self.media_state = media
        self.media_title.set_label(media.get('title') or 'Nothing playing')
        self.media_artist.set_label(media.get('artist') or ('Unknown artist' if media.get('title') else 'Start playback on your phone.'))
        self.media_album.set_label(media.get('album', ''))
        self.media_album.set_visible(bool(media.get('album')))
        self.media_source.set_label(media.get('app_name') or media.get('package') or 'Phone media')
        artwork = media.get('artwork')
        art_key = artwork.get('data') if artwork else None
        if art_key != self.art_key:
            self.art_key = art_key
            self.art_picture.set_paintable(image_texture(artwork))
        self.art_stack.set_visible_child_name('image' if self.art_picture.get_paintable() else 'empty')
        duration = media.get('duration', 0)
        position = min(media.get('position', 0), duration) if duration else media.get('position', 0)
        self.media_seek.set_range(0, max(1, duration))
        self.media_seek.set_value(position)
        self.media_elapsed.set_label(self.playback_time(position))
        self.media_duration.set_label(self.playback_time(duration) if duration else '—:—')
        self.media_info.set_label(('Playing' if media.get('playing') else 'Paused') if media.get('title') else 'No active session')
        self.media_toggle.set_icon_name('media-playback-pause-symbolic' if media.get('playing') else 'media-playback-start-symbolic')
        self.media_toggle.set_tooltip_text('Pause' if media.get('playing') else 'Play')
        self.update_media_controls()

    def update_media_controls(self):
        media = self.media_state
        active = media.get('active', bool(media.get('title')))
        allowed = media.get('actions')
        for button, action in [(self.media_previous, 'previous'), (self.media_next, 'next'),
                               (self.media_toggle, 'pause' if media.get('playing') else 'play'), (self.media_seek, 'seek')]:
            button.set_sensitive(bool(self.enabled('media') and active and (allowed is None or action in allowed) and
                (action != 'seek' or media.get('duration', 0) > 0)))

    def toggle_media(self, *_):
        self.execute('media', 'pause' if self.media_state.get('playing') else 'play')

    def seek_media(self, scale, scroll, value):
        if self.enabled('media') and scale.get_sensitive():
            if self.pending_seek:
                GLib.source_remove(self.pending_seek)
            def submit():
                self.pending_seek = None
                self.execute('media', 'seek', {'position': int(max(0, min(value, self.media_state.get('duration', 0))))}, quiet=True)
                return False
            self.pending_seek = GLib.timeout_add(200, submit)
        return False

    def open_received_url(self, *_):
        if self.incoming_url and self.incoming_url.startswith(('https://', 'http://')):
            Gio.AppInfo.launch_default_for_uri_async(self.incoming_url, None, None,
                lambda obj, result: Gio.AppInfo.launch_default_for_uri_finish(result))

    def open_received_file(self, *_):
        if self.received_path:
            parent = Gio.File.new_for_path(self.received_path).get_parent()
            Gio.AppInfo.launch_default_for_uri_async(parent.get_uri(), None, None, None)

    def choose_file(self, *_):
        dialog = Gtk.FileDialog(title='Send to phone')
        def selected(dialog, result):
            try:
                file = dialog.open_finish(result)
                if file.get_path():
                    self.execute('files', 'send', {'path': file.get_path()})
            except GLib.Error:
                pass
        dialog.open(self, None, selected)

    def discover(self, *_):
        self.pairing_active = True
        self.client.manager('StartDiscovery', '(u)', (120,), lambda _: self.client.manager('PairingInfo', done=self.pairing))

    def pairing(self, s):
        info = json.loads(s)
        self.pair_info.set_label(f"Port: {info['port']}\nCompare this TLS fingerprint on the phone:\n{info['fingerprint']}")
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


class App(Adw.Application):
    def __init__(self):
        super().__init__(application_id='org.unityconnect.Unity', flags=Gio.ApplicationFlags.DEFAULT_FLAGS)

    def do_startup(self):
        Adw.Application.do_startup(self)
        quit_action = Gio.SimpleAction.new('quit', None)
        quit_action.connect('activate', lambda *_: self.quit())
        self.add_action(quit_action)
        self.set_accels_for_action('app.quit', ['<primary>q'])
        # Let GApplication release its registration when a launcher/session
        # manager terminates the window, just as it does for a normal close.
        GLibUnix.signal_add(GLib.PRIORITY_DEFAULT, signal.SIGTERM,
                           lambda: (self.quit(), False)[1])

    def do_activate(self):
        win = self.props.active_window
        if not win:
            win = Window(self)
        win.present()


if __name__ == '__main__':
    raise SystemExit(App().run())
