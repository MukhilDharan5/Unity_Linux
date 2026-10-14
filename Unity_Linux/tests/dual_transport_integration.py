#!/usr/bin/env python3
"""Real simultaneous TLS LAN and BlueZ RFCOMM sessions with workload routing and independent lifecycle."""
import base64
import hashlib
import json
import os
import socket
import unittest
from gi.repository import GLib
import integration as harness
from integration import Peer, DEVICE, MANAGER, wait_for
from fake_bluez import FakeBlueZ
import audio_integration as audio_tests
import screen_integration as screen_tests

class DualTransportIntegration(unittest.TestCase):
    cli = harness.Integration.__dict__['cli']
    call = harness.Integration.__dict__['call']
    start_daemon = harness.Integration.__dict__['start_daemon']
    @classmethod
    def setUpClass(cls):
        os.environ['DBUS_SYSTEM_BUS_ADDRESS'] = os.environ['DBUS_SESSION_BUS_ADDRESS']
        cls.bluez = FakeBlueZ()
        harness.Integration.setUpClass.__func__(cls)
        wait_for(lambda: len(cls.bluez.profiles) == 3)
    @classmethod
    def tearDownClass(cls):
        try: harness.Integration.tearDownClass.__func__(cls)
        finally: cls.bluez.close()
    def status(self): return json.loads(self.cli('status', DEVICE).stdout)['state']
    def command(self, peer):
        while True:
            message = peer.read()
            if message['type'] == 'ping': peer.send({'type': 'pong'})
            elif message['type'] == 'command': return message
    def execute(self, feature, action, data=None):
        return self.call(self.bus, self.path, 'org.unityconnect.Device1', 'Execute', '(sss)', (feature, action, json.dumps(data or {})))
    def operation(self, path): return json.loads(self.call(self.bus, path, 'org.unityconnect.Operation1', 'GetState'))
    def result(self, peer, command, data=None):
        peer.send({'type': 'result', 'id': command['id'], 'ok': True, 'data': data or {}, 'error': ''})
    def test_independent_links_routing_files_replacement_fallback_and_revocation(self):
        info = json.loads(self.cli('discover').stdout)
        pending = Peer(self.port, info['fingerprint']); self.cli('pair', DEVICE)
        secret = pending.read()['secret']; pending.close()
        name = self.call(self.bus, MANAGER, 'org.unityconnect.Manager1', 'RequestAccess', '(sas)', ('Dual transport test', ['read', 'control']))
        self.cli('authorize', name); self.path = '/org/unityconnect/devices/d_' + DEVICE
        lan = Peer(self.port, info['fingerprint'], secret)
        lan.event('battery', {'level': 82, 'charging': False})
        wait_for(lambda: self.status().get('battery', {}).get('level') == 82)
        # Begin a LAN transfer before BT joins. The receiver and cached state must survive.
        data = os.urandom(100003); transfer = 'ab' * 16
        lan.event('files', {'phase': 'begin', 'id': transfer, 'name': 'dual.bin', 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        wait_for(lambda: (self.state / 'received' / ('.' + transfer + '.part')).exists())
        bt = Peer(self.port, info['fingerprint'], secret, self.bluez.open())
        wait_for(lambda: self.status()['transport']['kind'] == 'both')
        self.assertEqual(self.status()['battery']['level'], 82)
        self.assertEqual(self.status()['transport'], {'kind': 'both', 'links': ['bluetooth', 'lan'], 'control': 'bluetooth', 'bulk': 'lan'})
        for i in range(0, len(data), 49152): lan.event('files', {'phase': 'chunk', 'id': transfer, 'data': base64.b64encode(data[i:i+49152]).decode()})
        lan.event('files', {'phase': 'end', 'id': transfer})
        received = self.state / 'received' / (transfer + '-dual.bin')
        wait_for(received.exists); self.assertEqual(received.read_bytes(), data)
        # Controls and bulk commands choose different live sockets.
        control = self.execute('find', 'ring'); cmd = self.command(bt); self.assertEqual(cmd['feature'], 'find')
        self.result(bt, cmd); wait_for(lambda: self.operation(control)['status'] == 'completed')
        bulk = self.execute('photos', 'list'); cmd = self.command(lan); self.assertEqual(cmd['feature'], 'photos')
        self.result(lan, cmd, {'items': [], 'preview_supported': True}); wait_for(lambda: self.operation(bulk)['status'] == 'completed')
        for peer in (lan, bt):
            peer.send({'type': 'ping'})
            while True:
                message = peer.read()
                if message['type'] == 'pong': break
                if message['type'] == 'ping': peer.send({'type': 'pong'})
        # A BT replacement fails only work owned by the old BT generation.
        lost = self.execute('find', 'ring'); self.command(bt)
        retained = self.execute('photos', 'list'); command = self.command(lan)
        bt2 = Peer(self.port, info['fingerprint'], secret, self.bluez.open()); bt.close(); bt = bt2
        wait_for(lambda: self.operation(lost)['status'] == 'failed')
        self.assertEqual(self.operation(retained)['status'], 'running')
        self.result(lan, command, {'items': [], 'preview_supported': True})
        wait_for(lambda: self.operation(retained)['status'] == 'completed')
        self.assertEqual(self.status()['transport']['kind'], 'both')
        self.assertEqual(self.status()['battery']['level'], 82)
        # Losing BT does not clear LAN state or fail a LAN operation.
        retained = self.execute('photos', 'list'); command = self.command(lan)
        self.bluez.disconnect(); bt.close()
        wait_for(lambda: self.status()['transport']['kind'] == 'lan')
        self.assertEqual(self.status()['battery']['level'], 82)
        self.assertEqual(self.operation(retained)['status'], 'running')
        self.result(lan, command, {'items': [], 'preview_supported': True})
        wait_for(lambda: self.operation(retained)['status'] == 'completed')
        fallback = self.execute('find', 'ring'); cmd = self.command(lan); self.result(lan, cmd)
        wait_for(lambda: self.operation(fallback)['status'] == 'completed')
        bt = Peer(self.port, info['fingerprint'], secret, self.bluez.open())
        wait_for(lambda: self.status()['transport']['kind'] == 'both')
        lan.close(); wait_for(lambda: self.status()['transport']['kind'] == 'bluetooth')
        fallback = self.execute('photos', 'list'); cmd = self.command(bt)
        self.result(bt, cmd, {'items': [], 'preview_supported': True}); wait_for(lambda: self.operation(fallback)['status'] == 'completed')
        # Restore LAN; desktop policy changes broadcast to both links.
        lan = Peer(self.port, info['fingerprint'], secret)
        wait_for(lambda: self.status()['transport']['kind'] == 'both')
        bt.send({'type': 'capabilities', 'capabilities': harness.CAPS + [{'id': 'lock', 'status': 'available', 'reason': ''}]})
        wait_for(lambda: any(c['id'] == 'lock' and c['status'] == 'available' for c in json.loads(self.cli('capabilities', DEVICE).stdout)))
        self.cli('feature', DEVICE, 'lock', '--disable')
        for peer in (lan, bt):
            while True:
                message = peer.read()
                if message['type'] == 'ping': peer.send({'type': 'pong'})
                elif message['type'] == 'capabilities':
                    self.assertEqual(next(c for c in message['capabilities'] if c['id'] == 'lock')['status'], 'disabled'); break
        self.call(self.bus, self.path, 'org.unityconnect.Device1', 'Disconnect')
        wait_for(lambda: 'disconnected' in self.cli('list').stdout)
        for peer in (lan, bt):
            peer.sock.settimeout(2)
            with self.assertRaises((EOFError, ConnectionError)): peer.read()
            peer.close()
        lan = Peer(self.port, info['fingerprint'], secret)
        bt = Peer(self.port, info['fingerprint'], secret, self.bluez.open())
        wait_for(lambda: self.status()['transport']['kind'] == 'both')
        op = self.execute('find', 'ring'); self.command(bt)
        self.cli('unpair', DEVICE)
        wait_for(lambda: self.operation(op)['status'] == 'failed')
        for peer in (lan, bt):
            peer.sock.settimeout(2)
            with self.assertRaises((EOFError, ConnectionError)): peer.read()
            peer.close()

class DualTransportAudio(audio_tests.AudioIntegration):
    @classmethod
    def setUpClass(cls):
        os.environ['DBUS_SYSTEM_BUS_ADDRESS'] = os.environ['DBUS_SESSION_BUS_ADDRESS']
        cls.bluez = FakeBlueZ()
        super().setUpClass()
        wait_for(lambda: len(cls.bluez.profiles) == 3)
    @classmethod
    def tearDownClass(cls):
        try: super().tearDownClass()
        finally: cls.bluez.close()
    def test_audio_security_bytes_cleanup_and_recovery(self):
        # This class has its own focused lifecycle case instead of rerunning the entire single-link suite.
        info = json.loads(self.cli('discover').stdout); self.fingerprint = info['fingerprint']
        pending = Peer(self.port, self.fingerprint); self.cli('pair', DEVICE)
        secret = pending.read()['secret']; pending.close()
        self.peer = Peer(self.port, self.fingerprint, secret)
        self.path = '/org/unityconnect/devices/d_' + DEVICE
        name = self.call(self.bus, MANAGER, 'org.unityconnect.Manager1', 'RequestAccess', '(sas)', ('Dual audio test', ['read', 'control']))
        self.cli('authorize', name)
        stream, _ = self.start_route()
        bt = Peer(self.port, self.fingerprint, secret, self.bluez.open())
        wait_for(lambda: json.loads(self.cli('status', DEVICE).stdout)['state']['transport']['kind'] == 'both')
        self.assertEqual(self.state_audio()['state'], 'streaming')
        self.bluez.disconnect(); bt.close()
        wait_for(lambda: json.loads(self.cli('status', DEVICE).stdout)['state']['transport']['kind'] == 'lan')
        self.assertEqual(self.state_audio()['state'], 'streaming'); self.assertFalse(self.restored())
        # Ensure capture still sends actual bytes after BT joins and leaves.
        self.assertGreaterEqual(len(self.exact(stream, 1928)), 1928)
        self.peer.close(); wait_for(self.restored); stream.close()

class DualTransportScreens(screen_tests.ScreenIntegration):
    @classmethod
    def setUpClass(cls):
        os.environ['DBUS_SYSTEM_BUS_ADDRESS'] = os.environ['DBUS_SESSION_BUS_ADDRESS']
        cls.bluez = FakeBlueZ()
        super().setUpClass()
        wait_for(lambda: len(cls.bluez.profiles) == 3)
    @classmethod
    def tearDownClass(cls):
        try: super().tearDownClass()
        finally: cls.bluez.close()
    def test_bidirectional_streams_locking_authorization_and_cleanup(self):
        self.fingerprint = json.loads(self.cli('discover').stdout)['fingerprint']
        pending = Peer(self.port, self.fingerprint); self.cli('pair', DEVICE)
        secret = pending.read()['secret']; pending.close()
        self.peer = Peer(self.port, self.fingerprint, secret)
        self.path = '/org/unityconnect/devices/d_' + DEVICE
        name = self.call(self.bus, MANAGER, 'org.unityconnect.Manager1', 'RequestAccess', '(sas)', ('Dual screen test', ['read', 'control']))
        self.cli('authorize', name)
        self.assertEqual(self.peer.read()['type'], 'capabilities')
        _, stream, _ = self.start_phone()
        bt = Peer(self.port, self.fingerprint, secret, self.bluez.open())
        self.assertEqual(bt.read()['type'], 'capabilities')
        wait_for(lambda: json.loads(self.cli('status', DEVICE).stdout)['state']['transport']['kind'] == 'both')
        self.assertFalse(self.stopped())
        self.bluez.disconnect(); bt.close()
        wait_for(lambda: json.loads(self.cli('status', DEVICE).stdout)['state']['transport']['kind'] == 'lan')
        self.assertFalse(self.stopped()); self.assertEqual(bytes(self.frame()[3]), self.jpeg)
        bt = Peer(self.port, self.fingerprint, secret, self.bluez.open()); bt.read()
        self.peer.close(); wait_for(self.stopped)
        state = json.loads(self.cli('status', DEVICE).stdout)['state']
        self.assertEqual(state['transport']['kind'], 'bluetooth'); self.assertNotIn('screen', state)
        stream.close(); bt.close()

if __name__ == '__main__': unittest.main(verbosity=2)
