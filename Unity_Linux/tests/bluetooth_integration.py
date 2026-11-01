#!/usr/bin/env python3
"""Pair, authenticate, files, reconnect and revoke through the real BlueZ FD contract."""
import hashlib
import json
import os
import ssl
import struct
import unittest
import integration as integ
from integration import Peer, DEVICE, wait_for
from fake_bluez import FakeBlueZ, CONTROL, AUDIO, SCREEN
from gi.repository import GLib
import audio_integration as audio_tests
import screen_integration as screen_tests
class Bluetooth01Integration(integ.Integration):
    outgoing_chunk_limit = 8192
    @classmethod
    def setUpClass(cls):
        os.environ['DBUS_SYSTEM_BUS_ADDRESS'] = os.environ['DBUS_SESSION_BUS_ADDRESS']
        cls.bluez = FakeBlueZ()
        super().setUpClass()
        wait_for(lambda: len(cls.bluez.profiles) == 3)
    @classmethod
    def tearDownClass(cls):
        super().tearDownClass(); cls.bluez.close()
    def create_peer(self, port, fingerprint, secret=None):
        wait_for(lambda: json.loads(self.cli('diagnose').stdout)['bluetooth']['status'] == 'ready')
        peer = Peer(port, fingerprint, secret, stream=self.bluez.open())
        if secret is not None and not getattr(self, 'disconnect_checked', False):
            self.assertEqual(json.loads(self.cli('status', DEVICE).stdout)['state']['transport']['kind'], 'bluetooth')
            self.bluez.disconnect()
            wait_for(lambda: 'disconnected' in self.cli('list').stdout)
            peer.close(); self.disconnect_checked = True
            peer = Peer(port, fingerprint, secret, stream=self.bluez.open())
        return peer
    def test_end_to_end(self):
        with self.assertRaises(GLib.Error): self.bluez.open(bus=self.other)
        info = json.loads(self.cli('pairing').stdout)
        self.assertEqual(info['bluetooth']['adapters'][0]['address'], '11:22:33:44:55:66')
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); context.check_hostname = False; context.verify_mode = ssl.CERT_NONE
        context.minimum_version = ssl.TLSVersion.TLSv1_3
        for channel, message in [(AUDIO, {'type': 'audio_hello', 'version': 1, 'device_id': DEVICE, 'token': '00' * 32}),
                                 (SCREEN, {'type': 'screen_hello', 'version': 1, 'device_id': DEVICE, 'token': '00' * 32}),
                                 (AUDIO, {'type': 'hello', 'version': 1, 'device_id': DEVICE, 'name': 'Bad profile', 'capabilities': []})]:
            with context.wrap_socket(self.bluez.open(channel), server_hostname='unity.local') as stream:
                stream.settimeout(5)
                self.assertEqual(hashlib.sha256(stream.getpeercert(binary_form=True)).hexdigest(), info['fingerprint'])
                payload = json.dumps(message).encode(); stream.sendall(struct.pack('>I', len(payload)) + payload)
                try: self.assertEqual(stream.recv(1), b'')
                except (BrokenPipeError, ConnectionResetError, ssl.SSLError): pass
        super().test_end_to_end()
        # Losing the BlueZ owner unregisters every profile; a fresh owner recovers.
        self.bluez.close()
        wait_for(lambda: json.loads(self.cli('diagnose').stdout)['bluetooth']['status'] == 'unavailable', timeout=15)
        type(self).bluez = FakeBlueZ()
        wait_for(lambda: json.loads(self.cli('diagnose').stdout)['bluetooth']['status'] == 'ready', timeout=15)
        self.assertEqual(len(self.bluez.profiles), 3)
class BluetoothStreams:
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
    def create_peer(self, port, fingerprint, secret=None):
        wait_for(lambda: json.loads(self.cli('diagnose').stdout)['bluetooth']['status'] == 'ready')
        return Peer(port, fingerprint, secret, stream=self.bluez.open())
    def open_stream(self, kind):
        return self.bluez.open(AUDIO if kind == 'audio' else SCREEN)

class BluetoothAudio(BluetoothStreams, audio_tests.AudioIntegration):
    audio_transport = "bluetooth"
    pcm_bytes = 320
    def check_transport_disconnect(self):
        stream, _ = self.start_route()
        self.bluez.disconnect(AUDIO)
        wait_for(self.restored)
        stream.close()
        self.assertIn('connected', self.cli('list').stdout)

class BluetoothScreens(BluetoothStreams, screen_tests.ScreenIntegration):
    def check_transport_disconnect(self):
        _, stream, _ = self.start_phone()
        self.bluez.disconnect(SCREEN)
        wait_for(self.stopped)
        stream.close()
        self.assertIn('connected', self.cli('list').stdout)


if __name__ == '__main__': unittest.main(verbosity=2)
