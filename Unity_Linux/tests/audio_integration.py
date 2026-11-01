#!/usr/bin/env python3
"""Actual D-Bus/TLS routing and lifecycle, with isolated fake sound-server executables."""
import hashlib
import json
import os
from pathlib import Path
import socket
import ssl
import struct
import subprocess
import time
import unittest

import integration as harness
from gi.repository import Gio, GLib


class AudioIntegration(unittest.TestCase):
    audio_transport = "lan"
    pcm_bytes = 1920
    cli = harness.Integration.__dict__['cli']
    call = harness.Integration.__dict__['call']

    @classmethod
    def setUpClass(cls):
        harness.CAPS.append({'id': 'audio', 'status': 'available', 'reason': ''})
        harness.Integration.setUpClass.__func__(cls)

    @classmethod
    def start_daemon(cls):
        cls.backend = cls.dir / 'audio.json'
        if not cls.backend.exists():
            cls.backend.write_text(json.dumps({'default': 'laptop', 'sinks': {'1': 'laptop', '2': 'headphones'}, 'inputs': {'41': '1', '42': '2'}}))
        tools = cls.dir / 'audio-tools'; tools.mkdir(exist_ok=True)
        for name in ('pactl', 'parec', 'pacat'):
            link = tools / name
            if not link.exists():
                link.symlink_to(harness.ROOT / 'tests/fixtures/audio_backend.py')
        env = dict(os.environ, PATH=str(tools) + ':' + os.environ['PATH'], UNITY_TEST_AUDIO_STATE=str(cls.backend))
        cls.log = open(cls.dir / 'daemon.log', 'a')
        cls.daemon = subprocess.Popen([cls.bin / 'unityd', '--state-dir', cls.state, '--bind', '127.0.0.1', '--port', str(cls.port)], env=env, stdout=cls.log, stderr=cls.log)
        # Never accidentally activate a separately installed daemon in the private test bus.
        probe = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        harness.wait_for(lambda: 'org.unityconnect' in probe.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus',
            'org.freedesktop.DBus', 'ListNames', None, None, Gio.DBusCallFlags.NONE, 1000, None).unpack()[0])
        probe.close_sync(None)
        harness.wait_for(lambda: cls.cli('diagnose', check=False).returncode == 0)
        # The daemon exposes D-Bus before recovery finishes and TLS binds.
        # This is also required after the restart exercised below.
        def listening():
            with socket.socket() as connection:
                connection.settimeout(.2)
                return connection.connect_ex(('127.0.0.1', cls.port)) == 0
        harness.wait_for(listening)

    @classmethod
    def tearDownClass(cls):
        harness.Integration.tearDownClass.__func__(cls)

    def create_peer(self, port, fingerprint, secret=None):
        return harness.Peer(port, fingerprint, secret)

    def open_stream(self, kind):
        raw = socket.create_connection(('127.0.0.1', self.port))
        raw.settimeout(5)
        return raw

    @staticmethod
    def closed(stream):
        try: return stream.recv(1) == b''
        except (ConnectionError, ssl.SSLError): return True

    def check_transport_disconnect(self): pass

    def execute(self, action, data=None):
        return self.call(self.bus, self.path, 'org.unityconnect.Device1', 'Execute', '(sss)', ('audio', action, json.dumps(data or {})))

    def operation(self, op):
        return json.loads(self.call(self.bus, op, 'org.unityconnect.Operation1', 'GetState'))

    def state_audio(self):
        return json.loads(self.cli('status', harness.DEVICE).stdout)['state'].get('audio', {})

    def next_command(self):
        while True:
            message = self.peer.read()
            if message['type'] == 'ping':
                self.peer.send({'type': 'pong'}); continue
            if message.get('feature') == 'audio' and message.get('action') == 'start':
                return message

    def audio_socket(self, token, device=harness.DEVICE, version=1, receive_buffer=4096):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False; context.verify_mode = ssl.CERT_NONE
        context.minimum_version = ssl.TLSVersion.TLSv1_3
        raw = self.open_stream("audio")
        raw.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, receive_buffer)
        stream = context.wrap_socket(raw, server_hostname='unity.local')
        stream.settimeout(5)
        self.assertEqual(hashlib.sha256(stream.getpeercert(binary_form=True)).hexdigest(), self.fingerprint)
        body = json.dumps({'type': 'audio_hello', 'version': version, 'device_id': device, 'token': token}).encode()
        stream.sendall(struct.pack('>I', len(body)) + body)
        return stream

    @staticmethod
    def exact(stream, size):
        data = bytearray()
        while len(data) < size:
            block = stream.recv(size - len(data))
            if not block:
                raise EOFError('Audio stream closed')
            data.extend(block)
        return bytes(data)

    def ready(self, stream):
        size, = struct.unpack('>I', self.exact(stream, 4))
        self.assertEqual(json.loads(self.exact(stream, size)), {'type': 'ready'})

    def restored(self):
        value = json.loads(self.backend.read_text())
        return 'route' not in value and value['default'] == 'laptop'

    def start_route(self, receive_buffer=4096):
        # Restoring outputs and retiring the old session are separate asynchronous steps.
        # Retry only the documented busy state until cleanup permits the next route.
        def start_when_retired():
            try:
                return self.execute('start')
            except GLib.Error as error:
                if 'Laptop audio is already being routed' in str(error):
                    return None
                raise
        op = harness.wait_for(start_when_retired)
        command = self.next_command()
        stream = self.audio_socket(command['data']['token'], receive_buffer=receive_buffer); self.ready(stream)
        harness.wait_for(lambda: self.operation(op)['status'] == 'completed')
        harness.wait_for(lambda: self.state_audio().get('state') == 'streaming')
        return stream, command

    def check_phone_audio(self):
        # Exact transport requests fail before routing rather than silently falling back.
        with self.assertRaises(GLib.Error): self.execute('start', {'transport': 'bluetooth' if self.audio_transport == 'lan' else 'lan'})
        self.assertTrue(self.restored())
        # Cancellation while phone approval is pending does not touch laptop routing.
        op = self.execute('start', {'source': 'phone', 'transport': self.audio_transport})
        command = self.next_command()
        self.assertEqual(command['data']['source'], 'phone')
        self.assertEqual(self.state_audio()['transport'], self.audio_transport)
        self.call(self.bus, op, 'org.unityconnect.Operation1', 'Cancel')
        harness.wait_for(lambda: self.state_audio().get('state') == 'stopped')
        with self.audio_socket(command['data']['token']) as expired:
            self.assertTrue(self.closed(expired))
        # Real reverse TLS packets reach the sound output intact; no null sink is created.
        played = self.backend.with_suffix('.played')
        pcm = (bytes(range(256)) * 8)[:self.pcm_bytes]
        op = self.execute('start', {'source': 'phone', 'transport': self.audio_transport})
        command = self.next_command()
        stream = self.audio_socket(command['data']['token']); self.ready(stream)
        self.assertEqual(self.operation(op)['status'], 'running')
        stream.sendall(struct.pack('>Q', 1) + pcm)
        harness.wait_for(lambda: played.exists() and played.stat().st_size == len(pcm))
        self.assertEqual(played.read_bytes(), pcm)
        harness.wait_for(lambda: self.operation(op)['status'] == 'completed')
        self.assertTrue(self.restored())
        self.assertEqual(self.state_audio()['source'], 'phone')
        with self.audio_socket(command['data']['token']) as replay:
            self.assertTrue(self.closed(replay))
        # Replayed PCM terminates only this stream and preserves the laptop's normal outputs.
        stream.sendall(struct.pack('>Q', 1) + pcm)
        harness.wait_for(lambda: self.state_audio().get('state') == 'failed')
        self.assertIn('sequence', self.state_audio()['error']); stream.close()
        self.assertTrue(self.restored())
        # Truncated packets are never published to playback.
        op = self.execute('start', {'source': 'phone'})
        command = self.next_command()
        stream = self.audio_socket(command['data']['token']); self.ready(stream)
        stream.sendall(struct.pack('>Q', 3) + pcm[:50]); stream.close()
        harness.wait_for(lambda: self.operation(op)['status'] == 'failed')
        self.assertEqual(played.read_bytes(), pcm); self.assertTrue(self.restored())
        # Normal stop works in reverse while capture is connected but not yet producing PCM.
        op = self.execute('start', {'source': 'phone'})
        command = self.next_command()
        stream = self.audio_socket(command['data']['token']); self.ready(stream)
        stopped = self.execute('stop')
        harness.wait_for(lambda: self.operation(stopped)['status'] == 'completed')
        self.assertTrue(self.closed(stream)); stream.close(); self.assertTrue(self.restored())
        self.assertEqual(self.operation(op)['status'], 'completed')
        self.assertEqual(self.operation(op)['result']['state'], 'stopped')

    def test_audio_security_bytes_cleanup_and_recovery(self):
        info = json.loads(self.cli('discover').stdout); self.fingerprint = info['fingerprint']
        pending = self.create_peer(self.port, self.fingerprint)
        self.cli('pair', harness.DEVICE); secret = pending.read()['secret']; pending.close()
        self.peer = self.create_peer(self.port, self.fingerprint, secret)
        self.path = '/org/unityconnect/devices/d_' + harness.DEVICE
        name = self.call(self.bus, harness.MANAGER, 'org.unityconnect.Manager1', 'RequestAccess', '(sas)', ('Audio test', ['read', 'control']))
        self.cli('authorize', name)
        for data in ({'token': 'user token'}, {'rate': 1}, {'source': 'microphone'}, {'transport': 'unknown'}):
            with self.assertRaises(GLib.Error): self.execute('start', data)
        # No capture or route can be obtained without an explicit control request.
        with self.audio_socket('00' * 32) as bad:
            self.assertTrue(self.closed(bad))
        op = self.execute('start'); command = self.next_command(); token = command['data']['token']
        self.assertEqual((command['data']['rate'], command['data']['channels'], command['data']['frames']), ((48000, 2, 480) if self.audio_transport == "lan" else (16000, 1, 160)))
        for value, device, version in [('00' * 32, harness.DEVICE, 1), (token, 'ab' * 16, 1), (token, harness.DEVICE, 2)]:
            with self.audio_socket(value, device, version) as bad:
                self.assertTrue(self.closed(bad))
        stream = self.audio_socket(token); self.ready(stream)
        harness.wait_for(lambda: self.operation(op)['status'] == 'completed')
        seq, = struct.unpack('>Q', self.exact(stream, 8)); self.assertGreaterEqual(seq, 0)
        self.assertEqual(self.exact(stream, self.pcm_bytes), (bytes(range(256)) * 8)[:self.pcm_bytes])
        with self.audio_socket(token) as replay:
            self.assertTrue(self.closed(replay))
        with self.assertRaises(GLib.Error): self.execute('start')
        backend = json.loads(self.backend.read_text())
        self.assertEqual(backend['inputs'], {'41': '3', '42': '3'})
        backend['inputs']['43'] = '3'; self.backend.write_text(json.dumps(backend))
        # Control and metadata remain usable while binary audio is streaming.
        self.peer.event('battery', {'level': 84, 'charging': False})
        harness.wait_for(lambda: json.loads(self.cli('status', harness.DEVICE).stdout)['state']['battery']['level'] == 84)
        stop = self.execute('stop')
        harness.wait_for(lambda: self.operation(stop)['status'] == 'completed')
        stream.close(); self.assertTrue(self.restored())
        self.assertEqual(json.loads(self.backend.read_text())['inputs'], {'41': '1', '42': '2', '43': '1'})
        self.check_phone_audio()
        # A phone radio or scheduler can pause reads for longer than 100 ms.
        # Resume the same stream after a short pause, without losing the route.
        stream, _ = self.start_route(receive_buffer=16384)
        time.sleep(.4)
        previous = -1
        for _ in range(100):
            packet = self.exact(stream, self.pcm_bytes + 8)
            sequence, = struct.unpack('>Q', packet[:8])
            self.assertGreater(sequence, previous)
            self.assertEqual(packet[8:], (bytes(range(256)) * 8)[:self.pcm_bytes])
            previous = sequence
        self.assertEqual(self.state_audio()['state'], 'streaming')
        stop = self.execute('stop')
        harness.wait_for(lambda: self.operation(stop)['status'] == 'completed')
        stream.close(); self.assertTrue(self.restored())
        self.check_transport_disconnect()
        # Initial success retires the operation, but a later phone playback
        # error must still stop/restore that exact stream and retain its reason.
        stream, command = self.start_route()
        self.peer.send({'type': 'result', 'id': command['id'], 'ok': False,
                        'data': {}, 'error': 'Phone audio playback failed (-6)'})
        harness.wait_for(self.restored)
        harness.wait_for(lambda: self.state_audio().get('error') == 'Phone audio playback failed (-6)')
        self.assertEqual(self.state_audio()['state'], 'failed'); stream.close()
        old = command
        stream, command = self.start_route(receive_buffer=16384)
        self.peer.send({'type': 'result', 'id': old['id'], 'ok': False,
                        'data': {}, 'error': 'Late failure from the previous stream'})
        for _ in range(20): self.exact(stream, self.pcm_bytes + 8)
        self.assertEqual(self.state_audio()['state'], 'streaming')
        stop = self.execute('stop')
        harness.wait_for(lambda: self.operation(stop)['status'] == 'completed')
        stream.close(); self.assertTrue(self.restored())
        # Startup failure must restore speakers and fail the pending operation.
        self.backend.with_suffix('.fail-capture').touch()
        failed = self.execute('start'); command = self.next_command()
        with self.audio_socket(command['data']['token']) as stream:
            self.assertTrue(self.closed(stream))
        harness.wait_for(lambda: self.operation(failed)['status'] == 'failed')
        self.assertTrue(self.restored()); self.backend.with_suffix('.fail-capture').unlink()
        # Explicit cancellation is immediate, including before the data connection.
        cancelled = self.execute('start'); self.next_command()
        self.call(self.bus, cancelled, 'org.unityconnect.Operation1', 'Cancel')
        harness.wait_for(lambda: self.state_audio().get('state') == 'stopped')
        self.assertEqual(self.operation(cancelled)['status'], 'cancelled')
        # A receiver that stops reading cannot queue seconds of stale audio.
        stream, _ = self.start_route()
        harness.wait_for(self.restored)
        # Output restoration precedes publication of the final session state.
        harness.wait_for(lambda: self.state_audio().get('state') == 'failed')
        self.assertEqual(self.state_audio()['state'], 'failed'); stream.close()
        # Local disabling, remote disabling, and replacement sessions all stop routing.
        stream, _ = self.start_route()
        self.cli('feature', harness.DEVICE, 'audio', '--disable')
        harness.wait_for(self.restored); stream.close()
        self.cli('feature', harness.DEVICE, 'audio')
        stream, _ = self.start_route()
        caps = [dict(cap, status='disabled') if cap['id'] == 'audio' else cap for cap in harness.CAPS]
        self.peer.send({'type': 'capabilities', 'capabilities': caps})
        harness.wait_for(self.restored); stream.close()
        self.peer.send({'type': 'capabilities', 'capabilities': harness.CAPS})
        harness.wait_for(lambda: any(c['id'] == 'audio' and c['status'] == 'available' for c in json.loads(self.cli('status', harness.DEVICE).stdout)['capabilities']))
        stream, _ = self.start_route()
        replacement = self.create_peer(self.port, self.fingerprint, secret)
        harness.wait_for(self.restored); stream.close(); self.peer.close(); self.peer = replacement
        # A crash journal restores all output when the daemon starts again.
        stream, _ = self.start_route()
        self.daemon.kill(); self.daemon.wait(timeout=5); self.log.close(); stream.close(); self.peer.close()
        self.assertTrue((self.state / 'audio-route.json').exists())
        self.start_daemon(); harness.wait_for(self.restored)
        self.peer = self.create_peer(self.port, self.fingerprint, secret)
        name = self.call(self.bus, harness.MANAGER, 'org.unityconnect.Manager1', 'RequestAccess', '(sas)', ('Audio test', ['read', 'control']))
        self.cli('authorize', name)
        stream, _ = self.start_route()
        self.cli('unpair', harness.DEVICE); harness.wait_for(self.restored); stream.close(); self.peer.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
