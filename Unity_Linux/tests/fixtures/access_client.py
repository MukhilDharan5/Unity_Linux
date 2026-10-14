"""Test-only installed client; exercises the actual launcher's PID checks."""
import json
import os
from pathlib import Path
import signal
import sys

from gi.repository import Gio, GLib

BUS = 'org.unityconnect'
PATH = '/org/unityconnect/Manager'
INTERFACE = 'org.unityconnect.Manager1'
state = Path(os.environ['UNITY_TEST_ACCESS_STATE'])
bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
loop = GLib.MainLoop()


def call(method, signature='()', args=()):
    return bus.call_sync(BUS, PATH, INTERFACE, method, GLib.Variant(signature, args),
                         None, Gio.DBusCallFlags.NONE, 5000, None).unpack()[0]


def snapshot(*_):
    status = json.loads(call('GetAccessStatus'))
    status.update(pid=os.getpid(), bus_name=bus.get_unique_name(), arguments=sys.argv[1:])
    temporary = state.with_suffix('.tmp')
    temporary.write_text(json.dumps(status))
    temporary.replace(state)


def request():
    call('RequestAccess', '(sas)', ('Unity GTK', ['read', 'control']))
    snapshot()


def owner_changed(connection, sender, path, interface, name, params):
    _, old, new = params.unpack()
    if new:
        request()
    elif old:
        state.write_text(json.dumps({'approved': False, 'pid': os.getpid(), 'bus_name': bus.get_unique_name()}))


bus.signal_subscribe(BUS, INTERFACE, 'Changed', PATH, None,
                     Gio.DBusSignalFlags.NONE, snapshot)
bus.signal_subscribe('org.freedesktop.DBus', 'org.freedesktop.DBus', 'NameOwnerChanged',
                     '/org/freedesktop/DBus', BUS, Gio.DBusSignalFlags.NONE, owner_changed)
GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, signal.SIGTERM, lambda: (loop.quit(), False)[1])
request()
loop.run()
bus.close_sync(None)
