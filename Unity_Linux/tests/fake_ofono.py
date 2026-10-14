"""Private-bus HFP model; never controls a physical phone."""
import os
from gi.repository import Gio, GLib

XML = '''<node>
<interface name="org.ofono.Manager"><method name="GetModems"><arg type="a(oa{sv})" direction="out"/></method><signal name="ModemRemoved"><arg type="o"/></signal></interface>
<interface name="org.ofono.Modem"><method name="SetProperty"><arg type="s" direction="in"/><arg type="v" direction="in"/></method><signal name="PropertyChanged"><arg type="s"/><arg type="v"/></signal></interface>
<interface name="org.ofono.VoiceCallManager"><method name="GetCalls"><arg type="a(oa{sv})" direction="out"/></method><method name="SwapCalls"/><method name="SendTones"><arg type="s" direction="in"/></method><signal name="CallAdded"><arg type="o"/><arg type="a{sv}"/></signal><signal name="CallRemoved"><arg type="o"/></signal></interface>
<interface name="org.ofono.VoiceCall"><method name="GetProperties"><arg type="a{sv}" direction="out"/></method><method name="Answer"/><method name="Hangup"/><signal name="PropertyChanged"><arg type="s"/><arg type="v"/></signal></interface>
<interface name="org.ofono.CallVolume"><method name="SetProperty"><arg type="s" direction="in"/><arg type="v" direction="in"/></method><signal name="PropertyChanged"><arg type="s"/><arg type="v"/></signal></interface>
<interface name="org.ofono.HandsfreeAudioManager"><method name="GetCards"><arg type="a(oa{sv})" direction="out"/></method></interface>
<interface name="org.ofono.HandsfreeAudioCard"><method name="Connect"/></interface>
</node>'''


def variants(properties):
    return {key: GLib.Variant('b' if isinstance(value, bool) else 'as' if isinstance(value, list) else 's', value)
            for key, value in properties.items()}


class FakeOfono:
    def __init__(self, cold=False):
        self.bus = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        self.bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
                          GLib.Variant('(su)', ('org.ofono', 0)), None, Gio.DBusCallFlags.NONE, 5000, None)
        self.registrations = []
        self.interfaces = {i.name: i for i in Gio.DBusNodeInfo.new_for_xml(XML).interfaces}
        self.commands = []
        self.fail = None
        self.modems = {'/hfp_phone': {'Serial': 'AA:BB:CC:DD:EE:FF', 'Type': 'hfp', 'Name': 'Test phone', 'Powered': True,
                        'Interfaces': ['org.ofono.VoiceCallManager', 'org.ofono.CallVolume']},
                       '/hfp_other': {'Serial': '11:22:33:44:55:66', 'Type': 'hfp', 'Name': 'Other phone', 'Powered': True,
                        'Interfaces': ['org.ofono.VoiceCallManager', 'org.ofono.CallVolume']}}
        if cold:
            self.modems = {'/hfp/org/bluez/hci0/dev_' + value['Serial'].replace(':','_'): {**value,'Powered':False,'Interfaces':[]} for value in self.modems.values()}
            for value in self.modems.values(): value.pop('Serial')
        self.calls = {}
        self.register('/', 'org.ofono.Manager')
        self.register('/', 'org.ofono.HandsfreeAudioManager')
        self.register('/audio_phone', 'org.ofono.HandsfreeAudioCard')
        for path in self.modems:
            for interface in ('org.ofono.Modem', 'org.ofono.VoiceCallManager', 'org.ofono.CallVolume'):
                self.register(path, interface)

    def register(self, path, interface):
        self.registrations.append(self.bus.register_object(path, self.interfaces[interface], self.method, None, None))

    def emit(self, path, interface, name, signature, data):
        self.bus.emit_signal(None, path, interface, name, GLib.Variant(signature, data))
        self.bus.flush_sync(None)

    def add(self, path='/hfp_phone/voicecall01', state='incoming', number='+15550123'):
        self.calls[path] = {'State': state, 'LineIdentification': number, 'Name': 'Test caller', 'Multiparty': False, 'Emergency': False}
        self.register(path, 'org.ofono.VoiceCall')
        modem = path.rsplit('/', 1)[0]
        self.emit(modem, 'org.ofono.VoiceCallManager', 'CallAdded', '(oa{sv})', (path, variants(self.calls[path])))
        return path

    def change(self, path, state):
        self.calls[path]['State'] = state
        self.emit(path, 'org.ofono.VoiceCall', 'PropertyChanged', '(sv)', ('State', GLib.Variant('s', state)))

    def remove(self, path):
        self.calls.pop(path, None)
        self.emit(path.rsplit('/', 1)[0], 'org.ofono.VoiceCallManager', 'CallRemoved', '(o)', (path,))

    def method(self, bus, sender, path, interface, method, parameters, invocation):
        try:
            args = parameters.unpack()
            self.commands.append((path, method, args))
            if method == self.fail:
                invocation.return_dbus_error('org.ofono.Error.NotImplemented', 'Injected HFP command failure'); return
            if method == 'GetModems': result = GLib.Variant('(a(oa{sv}))', ([(p, variants(v)) for p, v in self.modems.items()],))
            elif method == 'GetCalls': result = GLib.Variant('(a(oa{sv}))', ([(p, variants(v)) for p, v in self.calls.items() if p.rsplit('/', 1)[0] == path],))
            elif method == 'GetProperties': result = GLib.Variant('(a{sv})', (variants(self.calls[path]),))
            elif method == 'GetCards': result = GLib.Variant('(a(oa{sv}))', ([('/audio_phone', variants({'Type': 'handsfree', 'RemoteAddress': 'AA:BB:CC:DD:EE:FF'}))],))
            else:
                if method == 'Answer': self.change(path, 'active')
                elif method == 'Hangup': self.remove(path)
                elif method == 'SwapCalls':
                    call = next(p for p in self.calls if p.rsplit('/', 1)[0] == path)
                    self.change(call, 'held' if self.calls[call]['State'] == 'active' else 'active')
                elif method == 'SetProperty':
                    if interface == 'org.ofono.Modem':
                        self.modems[path][args[0]] = args[1]
                        if args[0] == 'Powered' and args[1]:
                            value = self.modems[path]
                            value.setdefault('Serial',path.rsplit('dev_',1)[-1].replace('_',':'))
                            value['Interfaces'] = ['org.ofono.VoiceCallManager','org.ofono.CallVolume']
                            self.emit(path,interface,'PropertyChanged','(sv)',('Serial',GLib.Variant('s',value['Serial'])))
                            self.emit(path,interface,'PropertyChanged','(sv)',('Interfaces',GLib.Variant('as',value['Interfaces'])))
                    self.emit(path, interface, 'PropertyChanged', '(sv)', (args[0], parameters.get_child_value(1).get_variant()))
                result = GLib.Variant('()', ())
            invocation.return_value(result)
        except Exception as error:
            invocation.return_dbus_error('org.ofono.Error.Failed', str(error))

    def close(self):
        for registration in self.registrations: self.bus.unregister_object(registration)
        self.bus.close_sync(None)
