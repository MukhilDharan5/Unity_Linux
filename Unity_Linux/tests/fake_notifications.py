"""Test-only native notification server; never shows test transfers on the real desktop."""
import os
import threading
from gi.repository import Gio, GLib
SERVICE = 'org.freedesktop.Notifications'
PATH = '/org/freedesktop/Notifications'
XML = '''<node><interface name="org.freedesktop.Notifications">
<method name="GetCapabilities"><arg type="as" direction="out"/></method>
<method name="Notify"><arg type="s" direction="in"/><arg type="u" direction="in"/><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="as" direction="in"/><arg type="a{sv}" direction="in"/><arg type="i" direction="in"/><arg type="u" direction="out"/></method>
<method name="CloseNotification"><arg type="u" direction="in"/></method>
<signal name="ActionInvoked"><arg type="u"/><arg type="s"/></signal>
<signal name="NotificationClosed"><arg type="u"/><arg type="u"/></signal>
<signal name="ActivationToken"><arg type="u"/><arg type="s"/></signal>
</interface></node>'''
class FakeNotifications:
    def __init__(self):
        self.bus = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        self.bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
            GLib.Variant('(su)', (SERVICE, 0)), None, Gio.DBusCallFlags.NONE, 5000, None)
        self.items = []; self.closed = []; self.capabilities = ['actions', 'body-markup']
        self.available = True; self.unavailable_queries = 0
        self.registration = self.bus.register_object(PATH, Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0], self.method, None, None)
        self.loop = GLib.MainLoop(); self.thread = threading.Thread(target=self.loop.run, daemon=True); self.thread.start()
    def method(self, bus, sender, path, interface, method, parameters, invocation):
        if not self.available:
            self.unavailable_queries += 1
            invocation.return_dbus_error('org.freedesktop.DBus.Error.ServiceUnknown', 'Isolated notification service unavailable')
            return
        if method == 'GetCapabilities': invocation.return_value(GLib.Variant('(as)', (self.capabilities,)))
        elif method == 'Notify':
            app, replaces, icon, title, body, actions, hints, expiry = parameters.unpack()
            id = len(self.items) + 1
            self.items.append(dict(id=id, replaces=replaces, app=app, title=title, body=body, actions=actions, hints=hints, expiry=expiry, sender=sender))
            invocation.return_value(GLib.Variant('(u)', (id,)))
        elif method == 'CloseNotification':
            id, = parameters.unpack(); self.closed.append(id)
            self.bus.emit_signal(None, PATH, SERVICE, 'NotificationClosed', GLib.Variant('(uu)', (id, 3)))
            invocation.return_value(GLib.Variant('()', ()))
    def action(self, id, action='default', bus=None):
        (bus or self.bus).emit_signal(None, PATH, SERVICE, 'ActionInvoked', GLib.Variant('(us)', (id, action)))
        (bus or self.bus).flush_sync(None)
    def token(self, id, token):
        self.bus.emit_signal(None, PATH, SERVICE, 'ActivationToken', GLib.Variant('(us)', (id, token)))
        self.bus.flush_sync(None)
    def dismiss(self, id):
        self.bus.emit_signal(None, PATH, SERVICE, 'NotificationClosed', GLib.Variant('(uu)', (id, 2)))
        self.bus.flush_sync(None)
    def close(self):
        self.bus.unregister_object(self.registration); self.bus.close_sync(None); self.loop.quit(); self.thread.join(2)
