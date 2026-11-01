#!/usr/bin/env python3
"""Authenticated address refresh over real LAN/RFCOMM TLS; no plaintext discovery."""
import ipaddress
import json
import os
from pathlib import Path
import socket
import ssl
import unittest
import integration as harness
from integration import Peer, DEVICE, CAPS, wait_for
from fake_bluez import FakeBlueZ


class PublicWifiIntegration(unittest.TestCase):
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
        try:
            harness.Integration.tearDownClass.__func__(cls)
        finally:
            cls.bluez.close()

    def test_only_authenticated_encrypted_links_get_addresses_and_old_clients_keep_working(self):
        info = json.loads(self.cli('discover').stdout)
        fingerprint = info['fingerprint']
        pending = Peer(self.port, fingerprint, network_info=True)
        self.assertTrue(pending.pending)
        pending.sock.settimeout(.2)
        with self.assertRaises(socket.timeout):
            pending.read()  # No addresses or device data while approval is pending.
        pending.sock.settimeout(5)
        self.cli('pair', DEVICE)
        secret = pending.read()['secret']
        pending.close()

        # Neither plaintext nor TLS 1.2 can reach application authentication.
        with socket.create_connection(('127.0.0.1', self.port)) as plain:
            plain.settimeout(2)
            plain.sendall(b'UNITY_DISCOVER_V1')
            try:
                response = plain.recv(1024)
            except ConnectionResetError:
                response = b''
            self.assertNotIn(b'lan_endpoints', response)
            self.assertNotIn(fingerprint.encode(), response)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_2
        with socket.create_connection(('127.0.0.1', self.port)) as raw:
            with self.assertRaises(ssl.SSLError):
                context.wrap_socket(raw, server_hostname='unity.local')

        # A correct certificate does not replace the per-phone device proof.
        context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_3
        forged = Peer.__new__(Peer)
        forged.automatic_clipboard = False
        forged.sock = context.wrap_socket(socket.create_connection(('127.0.0.1', self.port)), server_hostname='unity.local')
        forged.sock.settimeout(2)
        forged.send({'type':'hello','version':1,'device_id':DEVICE,'name':'Forged proof','capabilities':CAPS,'network_info':True})
        self.assertEqual('challenge', forged.read()['type'])
        forged.send({'type':'proof','mac':'00' * 32})
        with self.assertRaises((EOFError, ConnectionError)):
            forged.read()
        forged.close()

        for bluetooth in (False, True):
            peer = Peer(self.port, fingerprint, secret, self.bluez.open() if bluetooth else None, network_info=True)
            self.assertEqual(peer.sock.version(), 'TLSv1.3')
            event = peer.read()
            if event['type'] == 'capabilities':
                event = peer.read()
            self.assertEqual(('event','connectivity'), (event['type'],event['feature']))
            data = event['data']
            self.assertEqual(data['discovery'], 'authenticated_link_only')
            self.assertEqual(data['tls'], 'TLSv1.3')
            self.assertTrue(data['encrypted'])
            self.assertEqual(data['ports'], [self.port])
            self.assertLessEqual(len(data['lan_endpoints']), 16)
            for endpoint in data['lan_endpoints']:
                address = ipaddress.ip_address(endpoint['host'])
                self.assertFalse(address.is_loopback or address.is_multicast or address.is_unspecified)
                self.assertEqual(endpoint['port'],self.port)
            self.assertNotIn(secret, json.dumps(data))
            peer.close()

        legacy = Peer(self.port, fingerprint, secret)
        legacy.send({'type':'ping'})
        self.assertEqual('pong', legacy.read()['type'])
        legacy.close()

        # Inspect only the isolated daemon's sockets, independently of the user's installed service.
        owned = {os.readlink(path) for path in Path(f'/proc/{self.daemon.pid}/fd').iterdir() if path.exists()}
        for table in ('udp','udp6'):
            for line in Path(f'/proc/{self.daemon.pid}/net/{table}').read_text().splitlines()[1:]:
                self.assertNotIn(f'socket:[{line.split()[9]}]',owned)


if __name__ == '__main__':
    unittest.main(verbosity=2)
