#!/usr/bin/env python3
"""Exercise real portal calls, FD passing and GStreamer JPEG capture without a screen."""
import importlib.util
import os
from pathlib import Path
import struct
import threading
import tempfile
import unittest
from unittest.mock import patch
import gi
gi.require_version('Gst', '1.0')
from gi.repository import Gio, GLib, Gst

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('capture', ROOT / 'unity-screen/screen-capture.py')
capture = importlib.util.module_from_spec(spec); spec.loader.exec_module(capture)
XML = '''<node><interface name="org.freedesktop.portal.ScreenCast">
<property name="AvailableSourceTypes" type="u" access="read"/><property name="AvailableCursorModes" type="u" access="read"/><method name="CreateSession"><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
<method name="SelectSources"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
<method name="Start"><arg type="o" direction="in"/><arg type="s" direction="in"/><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
<method name="OpenPipeWireRemote"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/><arg type="h" direction="out"/></method>
</interface></node>'''
CLOSE = '<node><interface name="org.freedesktop.portal.Session"><method name="Close"/></interface></node>'
REMOTE = '''<node><interface name="org.freedesktop.portal.RemoteDesktop">
<method name="CreateSession"><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
<method name="SelectDevices"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
<method name="Start"><arg type="o" direction="in"/><arg type="s" direction="in"/><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
<method name="NotifyPointerMotionAbsolute"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/><arg type="u" direction="in"/><arg type="d" direction="in"/><arg type="d" direction="in"/></method>
<method name="NotifyPointerButton"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/><arg type="i" direction="in"/><arg type="u" direction="in"/></method>
<method name="NotifyKeyboardKeysym"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/><arg type="i" direction="in"/><arg type="u" direction="in"/></method>
<method name="NotifyPointerAxis"><arg type="o" direction="in"/><arg type="a{sv}" direction="in"/><arg type="d" direction="in"/><arg type="d" direction="in"/></method>
</interface></node>'''
REGISTRY = '<node><interface name="org.freedesktop.host.portal.Registry"><method name="Register"><arg type="s" direction="in"/><arg type="a{sv}" direction="in"/></method></interface></node>'
PERMISSIONS = '<node><interface name="org.freedesktop.impl.portal.PermissionStore"><method name="GetPermission"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="as" direction="out"/></method></interface></node>'


class PortalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ready = threading.Event(); cls.calls = []; cls.denied = False; cls.closed = 0; cls.devices = 3; cls.unattended = False
        def server():
            context = GLib.MainContext.new(); context.push_thread_default()
            cls.loop = GLib.MainLoop(context=context)
            cls.bus = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
                Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
            cls.bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
                GLib.Variant('(su)', (capture.PORTAL, 0)), None, Gio.DBusCallFlags.NONE, 1000, None)
            cls.bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
                GLib.Variant('(su)', ('org.freedesktop.impl.portal.PermissionStore', 0)), None, Gio.DBusCallFlags.NONE, 1000, None)

            def method(bus, sender, path, interface, name, parameters, invocation):
                args = parameters.unpack(); cls.calls.append((name, args))
                if name.startswith('Notify') or name == 'Register':
                    invocation.return_value(GLib.Variant('()', ())); return
                if name == 'GetPermission':
                    invocation.return_value(GLib.Variant('(as)', (['yes'] if cls.unattended else [],))); return
                if name == 'OpenPipeWireRemote':
                    descriptors = Gio.UnixFDList.new(); fd = os.open('/dev/null', os.O_RDONLY)
                    index = descriptors.append(fd); os.close(fd)
                    invocation.return_value_with_unix_fd_list(GLib.Variant('(h)', (index,)), descriptors)
                    return
                options = args[-1]
                request = capture.PATH + '/request/' + sender[1:].replace('.', '_') + '/' + options['handle_token']
                invocation.return_value(GLib.Variant('(o)', (request,)))
                values = {}
                if name == 'CreateSession':
                    values = {'session_handle': GLib.Variant('s', cls.session)}
                elif name == 'Start':
                    values = {'streams': GLib.Variant('a(ua{sv})', [(42, {'size': GLib.Variant('(ii)', (1920, 1080)), 'logical_size': GLib.Variant('(ii)', (960, 540))})]), 'devices': GLib.Variant('u', cls.devices)}
                bus.emit_signal(sender, request, 'org.freedesktop.portal.Request', 'Response',
                    GLib.Variant('(ua{sv})', (1 if cls.denied and name == 'Start' else 0, values)))

            cls.session = capture.PATH + '/session/test/unity'
            def close(*args):
                cls.closed += 1; args[-1].return_value(GLib.Variant('()', ()))
            cls.bus.register_object(capture.PATH, Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0], method, lambda *args: GLib.Variant('u', 7 if args[-1] == 'AvailableSourceTypes' else 2), None)
            cls.bus.register_object(capture.PATH, Gio.DBusNodeInfo.new_for_xml(REMOTE).interfaces[0], method, None, None)
            cls.bus.register_object(capture.PATH, Gio.DBusNodeInfo.new_for_xml(REGISTRY).interfaces[0], method, None, None)
            cls.bus.register_object('/org/freedesktop/impl/portal/PermissionStore', Gio.DBusNodeInfo.new_for_xml(PERMISSIONS).interfaces[0], method, None, None)
            cls.bus.register_object(cls.session, Gio.DBusNodeInfo.new_for_xml(CLOSE).interfaces[0], close, None, None)
            cls.ready.set(); cls.loop.run(); cls.bus.close_sync(None); context.pop_thread_default()
        cls.thread = threading.Thread(target=server, daemon=True); cls.thread.start()
        assert cls.ready.wait(5)

    @classmethod
    def tearDownClass(cls):
        cls.loop.quit(); cls.thread.join(3)

    def test_capture_portal_and_real_encoder(self):
        stream = bytearray(); instance = capture.Capture()
        parse = Gst.parse_launch
        def pipeline(description):
            # Only the capture source is substituted; real caps/scaling/JPEG/appsink remain.
            source, rest = description.split(' ! ', 1)
            self.assertIn('pipewiresrc name=source fd=', source)
            return parse('videotestsrc is-live=true ! video/x-raw,width=1920,height=1080,framerate=30/1 ! ' + rest)
        def output(fd, data):
            self.assertEqual(fd, 1); stream.extend(data)
            GLib.idle_add(instance.cancel)
            return len(data)
        try:
            with patch.object(Gst, 'parse_launch', pipeline), patch.object(os, 'write', output): instance.run()
        finally: instance.close()
        length, = struct.unpack('>I', stream[:4]); self.assertLessEqual(length, 262144)
        jpeg = bytes(stream[4:4+length]); self.assertTrue(jpeg.startswith(b'\xff\xd8') and jpeg.endswith(b'\xff\xd9'))
        self.assertEqual(capture.dimensions(1920,1080), (1280,720))
        self.assertEqual(capture.dimensions(1080,1920), (720,1280))
        names = [name for name, _ in self.calls if name != 'Register']
        self.assertEqual(names[:4], ['CreateSession','SelectSources','Start','OpenPipeWireRemote'])
        selection = next(args for name,args in self.calls if name == 'SelectSources')
        self.assertEqual(selection[-1]['types'], 3)
        self.assertFalse(selection[-1]['multiple'])
        self.assertGreater(self.closed, 0)

    def test_denied_capture_closes_session(self):
        type(self).denied = True
        instance = capture.Capture(); before = self.closed
        try:
            with self.assertRaisesRegex(RuntimeError, 'denied'): instance.run()
        finally: instance.close(); type(self).denied = False
        self.assertGreater(self.closed, before)
        self.assertFalse(instance.pipeline)

    def test_remote_input_uses_the_approved_stream_and_releases_buttons_and_keys(self):
        instance = capture.Capture(control=True); before = len(self.calls); reports = []; parse = Gst.parse_launch
        def pipeline(description):
            return parse('videotestsrc is-live=true ! video/x-raw,width=1920,height=1080,framerate=30/1 ! ' + description.split(' ! ', 1)[1])
        def controls():
            for seq, data in enumerate([
                {'kind':'pointer','x':.5,'y':.5,'pressed':True},
                {'kind':'motion','x':1.,'y':1.},
                {'kind':'pointer','x':.5,'y':.5,'pressed':False},
                {'kind':'text','text':'Aதமிழ்😀\n'},
                {'kind':'key','key':'a','modifiers':['ctrl']},
                {'kind':'scroll','dx':0.,'dy':25.},
            ], 1):
                instance.control_command({'id':'ab'*16,'action':'input','data':dict(data, seq=seq)})
            instance.control_command({'id':'cd'*16,'action':'input','data':{'kind':'pointer','x':.5,'y':.5,'pressed':True,'seq':1}})
            instance.cancel(); return False
        def output(fd, data):
            GLib.idle_add(controls); return len(data)
        try:
            with patch.object(Gst, 'parse_launch', pipeline), patch.object(os, 'write', output), patch.object(instance, 'report', reports.append): instance.run()
        finally: instance.close()
        calls = [entry for entry in self.calls[before:] if entry[0] != 'GetPermission']; names = [name for name, _ in calls]
        self.assertEqual(names[:5], ['CreateSession','SelectDevices','SelectSources','Start','OpenPipeWireRemote'])
        self.assertEqual(calls[1][1][-1]['types'], 3)
        self.assertEqual(calls[2][1][-1]['types'], 1)
        moves = [args for name,args in calls if name == 'NotifyPointerMotionAbsolute']
        self.assertEqual(moves[0][-3:], (42,480.,270.)); self.assertEqual(moves[1][-2:], (959.,539.))
        buttons = [args[-2:] for name,args in calls if name == 'NotifyPointerButton']
        self.assertEqual(buttons, [(272,1),(272,0)])
        keys = [args[-2:] for name,args in calls if name == 'NotifyKeyboardKeysym']
        self.assertIn((0x01000000 | ord('த'),1), keys); self.assertIn((0x01000000 | ord('😀'),0), keys)
        self.assertIn((0xffe3,0), keys); self.assertFalse(instance.buttons); self.assertFalse(instance.keys)
        self.assertTrue(any(v.get('result',{}).get('state') == 'ignored' for v in reports))

    def test_partial_remote_permission_cannot_start_controllable_capture(self):
        type(self).devices = 1; instance = capture.Capture(control=True)
        try:
            with self.assertRaisesRegex(RuntimeError, 'keyboard and mouse'): instance.run()
        finally: instance.close(); type(self).devices = 3
        self.assertFalse(instance.pipeline)

    def test_unattended_permission_selects_virtual_monitor_and_maps_its_logical_size(self):
        type(self).unattended = True
        reports = []; before = len(self.calls); parse = Gst.parse_launch
        with tempfile.TemporaryDirectory() as directory:
            instance = capture.Capture(control=True, state_dir=Path(directory))
            class Guard:
                state = {'display_available': True, 'display_on': True, 'logical_size': [1536,864]}
                def __init__(self, path, output): self.output = output
                def close(self): pass
            def pipeline(description):
                return parse('videotestsrc is-live=true ! video/x-raw,width=1920,height=1080,framerate=30/1 ! ' + description.split(' ! ', 1)[1])
            def output(fd, data): GLib.idle_add(instance.cancel); return len(data)
            try:
                with patch.object(capture, 'output_config', side_effect=[[{'name':'eDP-1'}],[{'name':'eDP-1'},{'name':'Virtual-1'}]]), patch.object(capture, 'GuardClient', Guard), patch.object(Gst, 'parse_launch', pipeline), patch.object(os, 'write', output), patch.object(instance, 'report', reports.append):
                    instance.run()
                self.assertEqual(instance.guard.output, 'Virtual-1')
                self.assertEqual(instance.logical_size, [1536,864])
                selection = next(args for name,args in self.calls[before:] if name == 'SelectSources')
                self.assertEqual(selection[-1]['types'], 4)
                self.assertEqual(self.calls[before], ('Register', (capture.APP_ID, {})))
            finally: instance.close(); type(self).unattended = False


if __name__ == '__main__': unittest.main(verbosity=2)
