#!/usr/bin/env python3
"""Configure KDE's documented unattended permission for Unity only."""
import argparse
from gi.repository import Gio, GLib

APP_ID = 'org.unityconnect.Unity.Screen'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--disable', action='store_true', help='Require desktop approval again')
    args = parser.parse_args()
    if Gio.DesktopAppInfo.new(APP_ID + '.desktop') is None:
        raise SystemExit('Install the Unity daemon screen-sharing components first')
    bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    owner = bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'NameHasOwner',
        GLib.Variant('(s)', ('org.kde.KWin',)), None, Gio.DBusCallFlags.NONE, 2000, None).unpack()[0]
    if not owner: raise SystemExit('Unattended setup requires an active KDE Plasma Wayland session')
    bus.call_sync('org.freedesktop.impl.portal.PermissionStore', '/org/freedesktop/impl/portal/PermissionStore',
        'org.freedesktop.impl.portal.PermissionStore', 'SetPermission',
        GLib.Variant('(sbssas)', ('kde-authorized', True, 'remote-desktop', APP_ID, ['no' if args.disable else 'yes'])),
        None, Gio.DBusCallFlags.NONE, 3000, None)
    print('Unity unattended screen sharing ' + ('disabled' if args.disable else 'enabled') + '. Existing phone pairing and screen feature permissions still apply.')


if __name__ == '__main__': main()
