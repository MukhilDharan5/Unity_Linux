"""Isolated BlueZ profile fixture supplying real stream file descriptors."""
import os
import socket
import threading
from gi.repository import Gio, GLib
CONTROL = 'ab049870-6fb6-4d30-9b3c-190770271001'
AUDIO = 'ab049870-6fb6-4d30-9b3c-190770271002'
SCREEN = 'ab049870-6fb6-4d30-9b3c-190770271003'
DEVICE_PATH = '/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF'
XML = '''<node>
<interface name="org.bluez.ProfileManager1">
<method name="RegisterProfile"><arg type="o" direction="in"/><arg type="s" direction="in"/><arg type="a{sv}" direction="in"/></method>
<method name="UnregisterProfile"><arg type="o" direction="in"/></method>
</interface>
<interface name="org.freedesktop.DBus.ObjectManager">
<method name="GetManagedObjects"><arg type="a{oa{sa{sv}}}" direction="out"/></method>
</interface></node>'''
class FakeBlueZ:
    def __init__(self):
        self.bus = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        self.bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
            GLib.Variant('(su)', ('org.bluez', 0)), None, Gio.DBusCallFlags.NONE, 5000, None)
        self.profiles = {}; self.registrations = []
        for interface in Gio.DBusNodeInfo.new_for_xml(XML).interfaces:
            path = '/org/bluez' if interface.name == 'org.bluez.ProfileManager1' else '/'
            self.registrations.append(self.bus.register_object(path, interface, self.method, None, None))
        self.loop = GLib.MainLoop()
        self.thread = threading.Thread(target=self.loop.run, daemon=True); self.thread.start()
    def method(self, bus, sender, path, interface, method, parameters, invocation):
        try:
            if method == 'GetManagedObjects':
                invocation.return_value(GLib.Variant('(a{oa{sa{sv}}})', ({'/org/bluez/hci0': {'org.bluez.Adapter1': {
                    'Address': GLib.Variant('s', '11:22:33:44:55:66'), 'Alias': GLib.Variant('s', 'Test adapter'), 'Powered': GLib.Variant('b', True)}}},)))
            elif method == 'RegisterProfile':
                profile, uuid, options = parameters.unpack()
                assert uuid in (CONTROL, AUDIO, SCREEN)
                assert options['Role'] == 'server' and options['Channel'] == 0 and options['RequireAuthentication'] is True
                self.profiles[uuid] = (sender, profile)
                invocation.return_value(GLib.Variant('()', ()))
            elif method == 'UnregisterProfile':
                profile, = parameters.unpack()
                self.profiles = {u: p for u, p in self.profiles.items() if p != (sender, profile)}
                invocation.return_value(GLib.Variant('()', ()))
        except Exception as e: invocation.return_dbus_error('org.bluez.Error.Failed', str(e))
    def open(self, uuid=CONTROL, bus=None):
        sender, path = self.profiles[uuid]
        client, server = socket.socketpair()
        fds = Gio.UnixFDList.new(); index = fds.append(server.fileno())
        try:
            (bus or self.bus).call_with_unix_fd_list_sync(sender, path, 'org.bluez.Profile1', 'NewConnection',
                GLib.Variant('(oha{sv})', (DEVICE_PATH, index, {})), None, Gio.DBusCallFlags.NONE, 5000, fds, None)
        except Exception: client.close(); raise
        finally: server.close()
        return client
    def disconnect(self, uuid=CONTROL):
        sender, path = self.profiles[uuid]
        self.bus.call_sync(sender, path, 'org.bluez.Profile1', 'RequestDisconnection', GLib.Variant('(o)', (DEVICE_PATH,)),
            None, Gio.DBusCallFlags.NONE, 5000, None)
    def close(self):
        self.bus.close_sync(None); self.loop.quit(); self.thread.join(2)
