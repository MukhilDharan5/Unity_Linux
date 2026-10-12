#!/usr/bin/env python3
"""Real isolated D-Bus, rustls TCP, and CLI integration. Test peer never enters production UI."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import struct
import subprocess
import tempfile
import time
import unittest

import gi
from gi.repository import Gio, GLib

ROOT = Path(__file__).resolve().parents[1]
BUS = 'org.unityconnect'
MANAGER = '/org/unityconnect/Manager'
DEVICE = '0123456789abcdef0123456789abcdef'
CAPS = [{'id': f, 'status': 'available', 'reason': ''} for f in
        ('pairing', 'reconnect', 'connectivity', 'battery', 'network', 'find', 'clipboard', 'files', 'urls', 'media', 'notifications', 'notification_actions', 'sms', 'call_notifications', 'photos', 'browsing', 'ringer', 'remote_input', 'screen', 'call_control')]


def wait_for(predicate, timeout=5):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = predicate()
        if result:
            return result
        time.sleep(.03)
    raise AssertionError('Timed out waiting for condition')


class Peer:
    def __init__(self, port, fingerprint, secret=None):
        # A test-only pin verifier; production Android verifies in X509TrustManager before application data.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        context.minimum_version = ssl.TLSVersion.TLSv1_3
        self.sock = context.wrap_socket(socket.create_connection(('127.0.0.1', port)), server_hostname='unity.local')
        self.sock.settimeout(5)
        assert hashlib.sha256(self.sock.getpeercert(binary_form=True)).hexdigest() == fingerprint
        self.send({'type': 'hello', 'version': 1, 'device_id': DEVICE, 'name': 'Integration peer', 'capabilities': CAPS})
        reply = self.read()
        self.pending = reply['type'] == 'pair_pending'
        if not self.pending:
            assert reply['type'] == 'challenge'
            mac = hmac.new(bytes.fromhex(secret or '00' * 32),
                           f"unity-v1:{DEVICE}:{reply['nonce']}".encode(), hashlib.sha256).hexdigest()
            self.send({'type': 'proof', 'mac': mac})
            assert self.read()['type'] == 'ready'

    def send(self, m):
        payload = json.dumps(m).encode()
        self.sock.sendall(struct.pack('>I', len(payload)) + payload)

    def exact(self, size):
        value = b''
        while len(value) < size:
            chunk = self.sock.recv(size - len(value))
            if not chunk:
                raise EOFError('Peer closed')
            value += chunk
        return value

    def read(self):
        size, = struct.unpack('>I', self.exact(4))
        return json.loads(self.exact(size))

    def event(self, feature, data):
        self.send({'type': 'event', 'feature': feature, 'data': data})

    def close(self):
        self.sock.close()

    def __del__(self):
        if hasattr(self, "sock"):
            self.sock.close()


class Integration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='unity-integration-')
        cls.dir = Path(cls.temp.name)
        cls.bin = cls.dir / 'bin'; cls.bin.mkdir()
        for name in ('unityd', 'unityctl'):
            shutil.copy2(ROOT / 'target/debug' / name, cls.bin / name)
            (cls.bin / name).chmod(0o755)
        cls.state = cls.dir / 'state'
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0)); cls.port = probe.getsockname()[1]
        cls.bus = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        cls.other = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        cls.start_daemon()

    @classmethod
    def start_daemon(cls):
        cls.log = open(cls.dir / 'daemon.log', 'a')
        cls.daemon = subprocess.Popen([cls.bin / 'unityd', '--state-dir', cls.state, '--bind', '127.0.0.1', '--port', str(cls.port)], stdout=cls.log, stderr=cls.log)
        # Probing unityctl before the test daemon owns its name would activate
        # a separately installed production service on this otherwise private bus.
        probe = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        wait_for(lambda: 'org.unityconnect' in probe.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus',
            'org.freedesktop.DBus', 'ListNames', None, None, Gio.DBusCallFlags.NONE, 1000, None).unpack()[0])
        probe.close_sync(None)
        wait_for(lambda: cls.cli('diagnose', check=False).returncode == 0)

    @classmethod
    def cli(cls, *args, check=True):
        return subprocess.run([cls.bin / 'unityctl', *args], text=True, capture_output=True, check=check, timeout=10)

    @classmethod
    def call(cls, bus, path, interface, method, signature='()', args=()):
        result = bus.call_sync(BUS, path, interface, method, GLib.Variant(signature, args), None, Gio.DBusCallFlags.NONE, 10000, None).unpack()
        return result[0] if len(result) == 1 else result

    @classmethod
    def tearDownClass(cls):
        cls.daemon.terminate(); cls.daemon.wait(timeout=5); cls.log.close()

        for bus in (cls.bus, cls.other):
            if not bus.is_closed(): bus.close_sync(None)
        cls.temp.cleanup()

    def test_end_to_end(self):
        # Pairing window cannot be opened by an arbitrary session-bus caller.
        with self.assertRaises(GLib.Error):
            self.call(self.bus, MANAGER, 'org.unityconnect.Manager1', 'StartDiscovery', '(u)', (120,))
        info = json.loads(self.cli('discover').stdout)
        peer = Peer(self.port, info['fingerprint'])
        self.assertTrue(peer.pending)
        wait_for(lambda: json.loads(self.cli('pairing').stdout)['pending'])
        self.cli('pair', DEVICE)
        pair = peer.read(); self.assertEqual(pair['type'], 'pair_result')
        secret = pair['secret']; peer.close()
        self.assertEqual((self.state / 'trust.json').stat().st_mode & 0o777, 0o600)
        peer = Peer(self.port, info['fingerprint'], secret)
        path = '/org/unityconnect/devices/d_' + DEVICE
        wait_for(lambda: 'connected' in self.cli('list').stdout)
        caps = json.loads(self.cli('capabilities', DEVICE).stdout)
        self.assertEqual(len(caps), 21)
        for feature in ('screen', 'call_control'):
            self.assertEqual(next(c for c in caps if c['id'] == feature)['status'], 'unsupported')
        # Private phone state is blocked before an explicit client grant.
        with self.assertRaises(GLib.Error):
            self.call(self.bus, path, 'org.unityconnect.Device1', 'GetState')
        with self.assertRaises(GLib.Error):
            self.call(self.bus, MANAGER, 'org.unityconnect.Manager1', 'ApproveAccess', '(sb)', (self.bus.get_unique_name(), True))
        name = self.call(self.bus, MANAGER, 'org.unityconnect.Manager1', 'RequestAccess', '(sas)', ('Integration', ['read', 'control']))
        self.cli('authorize', name)
        peer.event('battery', {'level': 73, 'charging': True})
        peer.event('network', {'kind': 'wifi', 'signal': 3, 'operator': 'Test'})
        wait_for(lambda: json.loads(self.cli('status', DEVICE).stdout)['state'].get('battery', {}).get('level') == 73)
        # Photos use a bounded preview operation instead of transferring originals.
        photo = 'ef' * 16
        preview = {'mime': 'image/jpeg', 'width': 256, 'height': 192,
                   'data': base64.b64encode((ROOT / 'tests/fixtures/preview.jpg').read_bytes()).decode()}
        photo_op = self.call(self.bus, path, 'org.unityconnect.Sharing1', 'GetPhotoPreview', '(su)', (photo, 256))
        command = peer.read()
        self.assertEqual((command['feature'], command['action'], command['data']),
                         ('photos', 'preview', {'id': photo, 'edge': 256}))
        peer.send({'type': 'result', 'id': command['id'], 'ok': True,
                   'data': {'id': photo, 'preview': preview}, 'error': ''})
        photo_result = wait_for(lambda: (lambda op: op if op['status'] == 'completed' else None)(
            json.loads(self.call(self.bus, photo_op, 'org.unityconnect.Operation1', 'GetState'))))
        self.assertEqual(photo_result['result']['preview'], preview)
        with self.assertRaises(GLib.Error):
            self.call(self.other, photo_op, 'org.unityconnect.Operation1', 'GetState')
        with self.assertRaises(GLib.Error):
            self.call(self.bus, path, 'org.unityconnect.Sharing1', 'GetPhotoPreview', '(su)', (photo, 4096))
        peer.event('media', {'title': 'Test track', 'artist': 'Test artist', 'playing': True,
                            'album': 'Test album', 'app_name': 'Music', 'duration': 241000,
                            'position': 84000, 'artwork': preview, 'actions': ['pause', 'next']})
        wait_for(lambda: json.loads(self.cli('status', DEVICE).stdout)['state'].get('media', {}).get('artwork') == preview)
        sms_op = self.call(self.bus, path, 'org.unityconnect.Device1', 'Execute', '(sss)',
                           ('sms', 'list', json.dumps({'address': 'Service updates'})))
        command = peer.read()
        self.assertEqual(command['data']['address'], 'Service updates')
        peer.send({'type': 'result', 'id': command['id'], 'ok': True,
                   'data': {'messages': [{'id': '1', 'address': 'Service updates', 'body': 'Test', 'direction': 'incoming', 'timestamp': 1}]}, 'error': ''})
        wait_for(lambda: json.loads(self.call(self.bus, sms_op, 'org.unityconnect.Operation1', 'GetState'))['status'] == 'completed')
        # Command dispatch and owned operation tracking.
        # Force an outgoing command while an incoming frame header is incomplete.
        fragmented = json.dumps({'type': 'event', 'feature': 'battery', 'data': {'level': 74, 'charging': True}}).encode()
        header = struct.pack('>I', len(fragmented))
        peer.sock.sendall(header[:2])
        time.sleep(.03)
        op = self.call(self.bus, path, 'org.unityconnect.Device1', 'Execute', '(sss)', ('find', 'ring', '{}'))
        if os.environ.get('UNITY_EXPORT_API'):
            out = ROOT / 'docs/api'; out.mkdir(exist_ok=True)
            for name, object_path in [('manager', MANAGER), ('device', path), ('operation', op)]:
                xml = self.call(self.bus, object_path, 'org.freedesktop.DBus.Introspectable', 'Introspect')
                (out / f'{name}1.xml').write_text(xml + '\n')
        cmd = peer.read(); self.assertEqual(cmd['action'], 'ring')
        peer.sock.sendall(header[2:] + fragmented)
        wait_for(lambda: json.loads(self.cli('battery', DEVICE).stdout).get('level') == 74)
        peer.send({'type': 'result', 'id': cmd['id'], 'ok': True, 'data': {'state': 'ringing'}, 'error': ''})
        wait_for(lambda: json.loads(self.call(self.bus, op, 'org.unityconnect.Operation1', 'GetState'))['status'] == 'completed')
        other_name = self.call(self.other, MANAGER, 'org.unityconnect.Manager1', 'RequestAccess', '(sas)', ('Other', ['read', 'control']))
        self.cli('authorize', other_name)
        with self.assertRaises(GLib.Error):
            self.call(self.other, op, 'org.unityconnect.Operation1', 'GetState')
        # Streamed phone->Linux transfer and integrity.
        data = os.urandom(100003); transfer = 'cd' * 16
        peer.event('files', {'id': transfer, 'phase': 'begin', 'name': 'received.bin', 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        for i in range(0, len(data), 49152):
            peer.event('files', {'id': transfer, 'phase': 'chunk', 'data': base64.b64encode(data[i:i + 49152]).decode()})
        peer.event('files', {'id': transfer, 'phase': 'end'})
        received = self.state / 'received' / (transfer + '-received.bin')
        wait_for(received.exists); self.assertEqual(received.read_bytes(), data)
        # Linux->phone transfer uses the same operation and sends exact bytes.
        source = self.dir / 'source.bin'; source.write_bytes(data)
        op2 = self.call(self.bus, path, 'org.unityconnect.Transfer1', 'SendFile', '(s)', (str(source),))
        messages = []
        while True:
            m = peer.read()
            if m['type'] == 'ping':
                peer.send({'type': 'pong'}); continue
            messages.append(m)
            if m['action'] == 'end': break
        self.assertEqual(messages[0]['action'], 'begin')
        self.assertEqual(b''.join(base64.b64decode(m['data']['data']) for m in messages if m['action'] == 'chunk'), data)
        peer.send({'type': 'result', 'id': messages[-1]['id'], 'ok': True, 'data': {'state': 'received'}, 'error': ''})
        wait_for(lambda: json.loads(self.call(self.bus, op2, 'org.unityconnect.Operation1', 'GetState'))['status'] == 'completed')
        # Validation fails before a remote command is emitted.
        for feature, action, payload in [('urls', 'open', {'url': 'file:///etc/passwd'}), ('remote_input', 'tap', {'x': 2, 'y': 0}), ('screen', 'start', {})]:
            with self.assertRaises(GLib.Error):
                self.call(self.bus, path, 'org.unityconnect.Device1', 'Execute', '(sss)', (feature, action, json.dumps(payload)))
        self.cli('feature', DEVICE, 'clipboard', '--disable')
        peer.event('clipboard', {'text': 'must not be retained'})
        time.sleep(.05)
        self.assertNotIn('clipboard', json.loads(self.cli('status', DEVICE).stdout)['state'])
        # Multiple simultaneous API clients do not own daemon lifetime.
        self.bus.close_sync(None)
        self.assertEqual(self.cli('diagnose').returncode, 0)
        peer.close()
        wait_for(lambda: 'disconnected' in self.cli('list').stdout)
        with self.assertRaises(subprocess.CalledProcessError): self.cli('status', DEVICE)
        # Trust survives daemon restart, live state does not.
        self.daemon.terminate(); self.daemon.wait(timeout=5); self.log.close()
        self.start_daemon()
        peer = Peer(self.port, info['fingerprint'], secret)
        self.assertEqual(json.loads(self.cli('status', DEVICE).stdout)['state'], {})
        # Wrong credentials never establish a session, and unpairing cancels an existing one.
        with self.assertRaises((EOFError, ssl.SSLError, ConnectionError)):
            Peer(self.port, info['fingerprint'], '00' * 32)
        self.cli('unpair', DEVICE)
        wait_for(lambda: not self.cli('list').stdout.strip())
        with self.assertRaises((EOFError, ssl.SSLError, ConnectionError)): peer.read()
        peer.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
