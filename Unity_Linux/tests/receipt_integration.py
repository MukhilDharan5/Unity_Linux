#!/usr/bin/env python3
"""Actual daemon, TLS receipts, notification actions and portal file descriptors on a private bus."""
import base64
import hashlib
import json
import os
import time
import unittest
from unittest.mock import patch
from gi.repository import Gio, GLib
import integration as harness
from integration import DEVICE, Peer, wait_for
from fake_notifications import FakeNotifications

PORTAL_XML = '''<node><interface name="org.freedesktop.portal.OpenURI">
<method name="OpenURI"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
<method name="OpenFile"><arg type="s" direction="in"/><arg type="h" direction="in"/><arg type="a{sv}" direction="in"/><arg type="o" direction="out"/></method>
</interface></node>'''
class Receipts(harness.Integration):
    @classmethod
    def start_daemon(cls):
        # A portal failure must use the exact saved target without opening a real viewer.
        stub = cls.bin / 'gio'
        stub.write_text('''#!/usr/bin/python3
import json, os, sys
from pathlib import Path
with (Path(__file__).resolve().parents[1] / 'gio-open.log').open('a') as log:
    log.write(json.dumps([sys.argv[1:], os.environ.get('XDG_ACTIVATION_TOKEN')]) + '\\n')
''')
        stub.chmod(0o755)
        with patch.dict(os.environ, {'PATH': str(cls.bin) + os.pathsep + os.environ['PATH']}):
            super().start_daemon()

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.opens = []
        cls.portal_response = 0
        cls.portal = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        cls.portal.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'RequestName',
            GLib.Variant('(su)', ('org.freedesktop.portal.Desktop', 0)), None, Gio.DBusCallFlags.NONE, 5000, None)
        def open(bus, sender, path, interface, method, parameters, invocation):
            parent, item, options = parameters.unpack()
            token = options.pop('handle_token')
            request = '/org/freedesktop/portal/desktop/request/' + sender.lstrip(':').replace('.', '_') + '/' + token
            if method == 'OpenFile':
                fd = invocation.get_message().get_unix_fd_list().get(item)
                with os.fdopen(fd, 'rb') as file: item = file.read()
            cls.opens.append((method, item, options))
            # Deliberately respond before the method reply to exercise the subscription race.
            bus.emit_signal(sender, request, 'org.freedesktop.portal.Request', 'Response', GLib.Variant('(ua{sv})', (cls.portal_response, {})))
            invocation.return_value(GLib.Variant('(o)', (request,)))
        cls.registration = cls.portal.register_object('/org/freedesktop/portal/desktop', Gio.DBusNodeInfo.new_for_xml(PORTAL_XML).interfaces[0], open, None, None)
    @classmethod
    def tearDownClass(cls):
        cls.portal.unregister_object(cls.registration); cls.portal.close_sync(None)
        super().tearDownClass()
    def test_end_to_end(self):
        info = json.loads(self.cli('discover').stdout)
        pending = Peer(self.port, info['fingerprint']); self.cli('pair', DEVICE)
        secret = pending.read()['secret']; pending.close()
        peer = Peer(self.port, info['fingerprint'], secret)
        def receipt(count): return wait_for(lambda: self.notices.items[count-1] if len(self.notices.items) >= count else None)
        first = 'https://example.org/first?x=1&y=2'; second = 'https://example.org/second'
        peer.event('urls', {'url': first}); peer.event('urls', {'url': second})
        one, two = receipt(1), receipt(2)
        self.assertEqual(one['title'], 'Link received'); self.assertIn('&amp;', one['body'])
        self.assertEqual(one['actions'], ['default', 'Open', 'open', 'Open'])
        self.assertEqual(one['expiry'], 0)  # Remain actionable until the user dismisses the receipt.
        self.assertEqual(self.opens, [])
        # Unrelated bus clients cannot forge clicks, and later receipts don't change the target.
        self.notices.action(one['id'], bus=self.other); time.sleep(.1); self.assertEqual(self.opens, [])
        self.notices.token(one['id'], 'test-activation-token')
        self.notices.action(one['id'])
        wait_for(lambda: len(self.opens) == 1)
        self.assertEqual(self.opens[-1], ('OpenURI', first, {'writable': False, 'ask': False, 'activation_token': 'test-activation-token'}))
        self.notices.action(one['id']); time.sleep(.05); self.assertEqual(len(self.opens), 1)
        # Only completed, hash-verified files get a notification; chunks don't create alerts.
        data = b'received contents\n'; transfer = 'ac' * 16
        peer.event('files', {'phase': 'begin', 'id': transfer, 'name': 'receipt space.txt', 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        peer.event('files', {'phase': 'chunk', 'id': transfer, 'data': base64.b64encode(data).decode()})
        time.sleep(.05); self.assertEqual(len(self.notices.items), 2)
        peer.event('files', {'phase': 'end', 'id': transfer})
        file = receipt(3); self.assertEqual(file['title'], 'File received')
        self.notices.dismiss(file['id'])  # Some desktops close the notification before its action.
        self.notices.action(file['id'], 'open'); wait_for(lambda: len(self.opens) == 2)
        self.assertEqual(self.opens[-1][0:2], ('OpenFile', data))
        text = '<img src="file:///secret"> Unicode தமிழ்'
        peer.event('clipboard', {'text': text}); shared = receipt(4)
        self.assertIn('&lt;img', shared['body']); self.assertNotIn('<img', shared['body'])
        self.notices.action(shared['id']); wait_for(lambda: len(self.opens) == 3)
        self.assertEqual(self.opens[-1][0:2], ('OpenFile', text.encode()))
        saved = next((self.state/'received').glob('*-shared-text.txt'))
        self.assertEqual(saved.stat().st_mode & 0o777, 0o600)
        # Receipt actions remain usable offline; telemetry never produces receipt alerts.
        peer.event('battery', {'level': 64, 'charging': False}); peer.close()
        self.notices.action(two['id']); wait_for(lambda: len(self.opens) == 4)
        self.assertEqual(self.opens[-1][0:2], ('OpenURI', second)); self.assertEqual(len(self.notices.items), 4)
        peer = Peer(self.port, info['fingerprint'], secret)
        self.cli('feature', DEVICE, 'urls', '--disable'); peer.event('urls', {'url': 'https://example.org/disabled'})
        time.sleep(.05); self.assertEqual(len(self.notices.items), 4); self.cli('feature', DEVICE, 'urls')
        bad = 'ba' * 16
        peer.event('files', {'phase': 'begin', 'id': bad, 'name': 'bad.bin', 'size': len(data), 'sha256': '00'*32})
        peer.event('files', {'phase': 'chunk', 'id': bad, 'data': base64.b64encode(data).decode()})
        peer.event('files', {'phase': 'end', 'id': bad})
        wait_for(lambda: 'disconnected' in self.cli('list').stdout)
        self.assertEqual(len(self.notices.items), 4); peer.close()
        # Missing notification servers don't disconnect transport; a restarted server works.
        peer = Peer(self.port, info['fingerprint'], secret)
        peer.event('urls', {'url': 'https://example.org/stale-server'})
        stale = receipt(5)
        # Keep the isolated name occupied: a real desktop server must never auto-start for this test.
        self.notices.available = False; peer.event('urls', {'url': 'https://example.org/unavailable'})
        wait_for(lambda: self.notices.unavailable_queries)
        peer.send({'type': 'ping'}); self.assertEqual(peer.read()['type'], 'pong')
        self.notices.close()
        type(self).notices = FakeNotifications()
        self.notices.action(stale['id']); time.sleep(.05); self.assertEqual(len(self.opens), 4)
        peer.event('urls', {'url': 'https://example.org/restored'}); restored = receipt(1)
        self.notices.action(restored['id']); wait_for(lambda: len(self.opens) == 5)
        self.assertEqual(self.opens[-1][0:2], ('OpenURI', 'https://example.org/restored'))
        # A successful request path alone is insufficient. Cancellation must not force Gio,
        # while a portal Response error must fall back to a real opener invocation.
        type(self).portal_response = 1
        peer.event('urls', {'url': 'https://example.org/cancelled'}); cancelled = receipt(2)
        self.notices.action(cancelled['id']); wait_for(lambda: len(self.opens) == 6)
        time.sleep(.2); self.assertFalse((self.dir / 'gio-open.log').exists())
        type(self).portal_response = 2
        fallback = 'https://example.org/fallback?original=1&second=2'
        peer.event('urls', {'url': fallback}); failed = receipt(3)
        self.notices.token(failed['id'], 'fallback-activation')
        self.notices.action(failed['id']); wait_for(lambda: (self.dir / 'gio-open.log').exists())
        calls = [json.loads(line) for line in (self.dir / 'gio-open.log').read_text().splitlines()]
        self.assertEqual(calls, [[['open', fallback], 'fallback-activation']])
        self.assertEqual(len(self.opens), 7)
        transfer = 'ef' * 16
        peer.event('files', {'phase': 'begin', 'id': transfer, 'name': 'fallback space.bin', 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        peer.event('files', {'phase': 'chunk', 'id': transfer, 'data': base64.b64encode(data).decode()})
        peer.event('files', {'phase': 'end', 'id': transfer})
        failed_file = receipt(4); self.notices.action(failed_file['id'])
        wait_for(lambda: len((self.dir / 'gio-open.log').read_text().splitlines()) == 2)
        calls = [json.loads(line) for line in (self.dir / 'gio-open.log').read_text().splitlines()]
        saved = self.state / 'received' / (transfer + '-fallback space.bin')
        self.assertEqual(calls[-1], [['open', str(saved)], None])
        self.assertEqual(saved.read_bytes(), data)
        self.assertEqual(self.opens[-1][0:2], ('OpenFile', data))
        peer.close()

if __name__ == '__main__': unittest.main(verbosity=2)
