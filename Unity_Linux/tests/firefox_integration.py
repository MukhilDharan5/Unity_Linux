#!/usr/bin/env python3
"""Real daemon/D-Bus/CLI/native host, with isolated state and no browser profile."""
import json
import os
from pathlib import Path
import select
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import unittest
from gi.repository import Gio, GLib

ROOT = Path(__file__).resolve().parents[1]


def wait_for(predicate):
    end = time.monotonic() + 8
    while time.monotonic() < end:
        result = predicate()
        if result:
            return result
        time.sleep(.03)
    raise AssertionError('Timed out waiting for Firefox bridge')


class Firefox(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='unity-firefox-')
        self.dir = Path(self.temp.name)
        self.bin = self.dir / 'bin'
        self.bin.mkdir()
        for name in ('unityd', 'unityctl'):
            shutil.copy2(ROOT / 'target/debug' / name, self.bin / name)
            (self.bin / name).chmod(0o755)
        self.state = self.dir / 'state'
        self.log = open(self.dir / 'daemon.log', 'a')
        self.hosts = []
        self.bus = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        self.daemon = None
        self.start()

    def tearDown(self):
        for host in self.hosts:
            if host.poll() is None:
                host.terminate()
            host.wait(timeout=5)
            host.stdin.close(); host.stdout.close(); host.stderr.close()
        self.stop()
        self.bus.close_sync(None)
        self.log.close()
        self.temp.cleanup()

    def start(self):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        self.daemon = subprocess.Popen([self.bin / 'unityd', '--state-dir', self.state,
            '--bind', '127.0.0.1', '--port', str(port)], stdout=self.log, stderr=self.log)
        wait_for(lambda: (self.state / 'firefox.sock').exists())
        wait_for(lambda: 'org.unityconnect' in self.bus.call_sync('org.freedesktop.DBus',
            '/org/freedesktop/DBus', 'org.freedesktop.DBus', 'ListNames', None, None,
            Gio.DBusCallFlags.NONE, 1000, None).unpack()[0])

    def stop(self):
        if self.daemon and self.daemon.poll() is None:
            self.daemon.terminate(); self.daemon.wait(timeout=10)

    def cli(self, *args):
        return subprocess.check_output([self.bin / 'unityctl', 'firefox', *args], text=True, timeout=10)

    def status(self):
        return json.loads(self.cli('status'))

    def host(self, profile='profile-one'):
        host = subprocess.Popen(['python3', ROOT / 'unity-firefox/firefox-host.py', 'manifest.json',
            'session-continuity@local.invalid'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, bufsize=0, env=dict(os.environ, UNITY_FIREFOX_STATE_DIR=str(self.state)))
        self.hosts.append(host)
        self.send(host, {'type': 'hello', 'version': 1, 'profileId': profile})
        return host

    def send(self, host, value):
        raw = json.dumps(value).encode()
        # Force fragmented input; native messaging is a stream, not a line protocol.
        frame = struct.pack('=I', len(raw)) + raw
        host.stdin.write(frame[:2]); host.stdin.write(frame[2:7]); host.stdin.write(frame[7:])
        host.stdin.flush()

    def read(self, host):
        def exact(size):
            data = b''
            while len(data) < size:
                assert select.select([host.stdout], [], [], 5)[0], 'Native reply timed out'
                chunk = host.stdout.read(size - len(data))
                assert chunk, 'Native host closed unexpectedly'
                data += chunk
            return data
        size, = struct.unpack('=I', exact(4))
        return json.loads(exact(size))

    def summary(self):
        return {'tracking': True, 'activeId': 'context-one', 'restoreState': 'idle', 'contexts': [{
            'id': 'context-one', 'url': 'https://example.org/article', 'updatedAt': 1793151000000,
            'closed': False, 'formCount': 0, 'scrollProgress': .75,
            'media': [{'kind': 'video', 'time': 123.5}],
        }]}

    def sync(self, host, summary=None):
        self.send(host, {'type': 'sync', 'sequence': 1, 'summary': summary or self.summary()})
        self.assertEqual(self.read(host), {'type': 'synced', 'sequence': 1})

    def test_bidirectional_disable_and_reconnect(self):
        self.assertFalse(self.status()['enabled'])
        host = self.host()
        self.assertFalse(self.read(host)['enabled'])
        self.send(host, {'type': 'sync', 'sequence': 1, 'summary': self.summary()})
        self.assertFalse(self.read(host)['enabled'])
        self.assertEqual(self.status()['profiles'][0]['summary'], {})
        self.cli('enable'); self.assertTrue(self.read(host)['enabled'])
        self.sync(host)
        self.assertEqual(self.status()['profiles'][0]['summary'], self.summary())
        command = self.cli('restore', 'profile-one', 'context-one').strip()
        self.assertEqual(self.read(host), {'type': 'restore', 'commandId': command, 'contextId': 'context-one'})
        self.send(host, {'type': 'result', 'commandId': command, 'ok': True, 'note': 'Restoration started'})
        wait_for(lambda: self.status()['profiles'][0]['last_result'].get('note') == 'Restoration started')
        self.sync(host, {'tracking': False, 'activeId': None, 'restoreState': 'idle', 'contexts': []})
        self.assertEqual(self.status()['profiles'][0]['summary']['contexts'], [])
        self.cli('disable'); self.assertFalse(self.read(host)['enabled'])
        self.assertEqual(self.status()['profiles'][0]['summary'], {})
        self.cli('enable'); self.assertTrue(self.read(host)['enabled']); self.sync(host)
        host.stdin.close(); host.wait(timeout=5)
        wait_for(lambda: not self.status()['profiles'])
        self.stop(); self.start()
        self.assertTrue(self.status()['enabled'])
        self.assertEqual(self.status()['profiles'], [])
        second = self.host(); self.assertTrue(self.read(second)['enabled']); self.sync(second)
        self.assertEqual((self.state / 'firefox.sock').stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.state / 'firefox.json').stat().st_mode & 0o777, 0o600)
        self.assertEqual(sorted(p.name for p in self.state.glob('firefox*')), ['firefox.json', 'firefox.sock'])

    def test_invalid_payload_and_oversized_frame_are_disconnected(self):
        self.cli('enable')
        host = self.host(); self.read(host)
        summary = self.summary(); summary['contexts'][0]['forms'] = [{'value': 'must not enter Unity'}]
        self.send(host, {'type': 'sync', 'sequence': 1, 'summary': summary})
        host.wait(timeout=5)
        self.assertEqual(self.status()['profiles'], [])
        host = self.host(); self.read(host)
        host.stdin.write(struct.pack('=I', 512 * 1024 + 1)); host.stdin.flush()
        host.wait(timeout=5)
        wait_for(lambda: not self.status()['profiles'])

    def test_multiple_profiles_and_authorization(self):
        self.cli('enable')
        one = self.host(); self.read(one); self.sync(one)
        two = self.host('profile-two'); self.read(two); self.sync(two)
        duplicate = self.host(); duplicate.wait(timeout=5)
        self.assertEqual(len(self.status()['profiles']), 2)
        command = self.cli('restore', 'profile-two').strip()
        self.assertEqual(self.read(two)['commandId'], command)
        self.assertFalse(select.select([one.stdout], [], [], .1)[0])
        for method, signature, args in [('FirefoxStatus', '()', ()), ('SetFirefoxEnabled', '(b)', (False,)),
                                         ('RestoreFirefoxContext', '(ss)', ('profile-one', 'context-one'))]:
            with self.assertRaises(GLib.Error) as denied:
                self.bus.call_sync('org.unityconnect', '/org/unityconnect/Manager', 'org.unityconnect.Manager1',
                    method, GLib.Variant(signature, args), None, Gio.DBusCallFlags.NONE, 3000, None)
            self.assertIn('NotAuthorized', str(denied.exception))


if __name__ == '__main__':
    unittest.main()
