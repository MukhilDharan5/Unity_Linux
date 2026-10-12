#!/usr/bin/env python3
"""Real installed launcher, isolated bus/daemon, no display or phone required."""
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import unittest

from gi.repository import Gio, GLib

ROOT = Path(__file__).resolve().parents[1]
BUS = 'org.unityconnect'
MANAGER = '/org/unityconnect/Manager'
INTERFACE = 'org.unityconnect.Manager1'


def wait_for(predicate, timeout=8):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = predicate()
        if result:
            return result
        time.sleep(.03)
    raise AssertionError('Timed out waiting for condition')


class Launcher(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='unity-launcher-test-')
        self.dir = Path(self.temp.name)
        self.prefix = self.dir / 'prefix'
        subprocess.run(['python3', str(ROOT / 'scripts/install.py'), '--debug', '--prefix', str(self.prefix)], check=True)
        # A test-only client takes the fixed installed script's place. Production
        # has no fixture flag or caller-controlled script path in its launcher.
        script = self.prefix / 'share/unity/unity.py'
        shutil.copyfile(ROOT / 'tests/fixtures/access_client.py', script)
        script.chmod(0o644)
        self.bus = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        self.log = open(self.dir / 'processes.log', 'a')
        self.state = self.dir / 'client.json'
        self.launcher = self.daemon = None
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            self.port = probe.getsockname()[1]
        self.start_daemon()

    def tearDown(self):
        if self.launcher and self.launcher.poll() is None:
            self.launcher.terminate()
            self.launcher.wait(timeout=5)
        if self.daemon and self.daemon.poll() is None:
            self.daemon.terminate()
            self.daemon.wait(timeout=5)
        self.bus.close_sync(None)
        self.log.close()
        self.temp.cleanup()

    def start_daemon(self):
        self.daemon = subprocess.Popen([self.prefix / 'libexec/unity/unityd', '--state-dir', self.dir / 'state',
            '--bind', '127.0.0.1', '--port', str(self.port)], stdout=self.log, stderr=self.log)
        # Do not activate a production installation on the isolated test bus.
        wait_for(lambda: BUS in self.bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus',
            'org.freedesktop.DBus', 'ListNames', None, None, Gio.DBusCallFlags.NONE, 1000, None).unpack()[0])

    def cli(self, *args):
        return subprocess.run([self.prefix / 'bin/unityctl', *args], check=True, capture_output=True, text=True, timeout=10)

    def call(self, method, signature='()', args=()):
        return self.bus.call_sync(BUS, MANAGER, INTERFACE, method, GLib.Variant(signature, args),
                                 None, Gio.DBusCallFlags.NONE, 5000, None).unpack()[0]

    def status(self):
        try:
            return json.loads(self.state.read_text())
        except (FileNotFoundError, json.JSONDecodeError):
            return {}

    def launch(self):
        self.state.unlink(missing_ok=True)
        self.launcher = subprocess.Popen([self.prefix / 'bin/unity-gtk'],
            env=dict(os.environ, UNITY_TEST_ACCESS_STATE=str(self.state)), stdout=self.log, stderr=self.log)
        return wait_for(lambda: self.status() if self.status().get('approved') else None)

    def test_launch_reopen_restart_and_revocation(self):
        first = self.launch()
        # The actual child is approved; a matching label in another process is not.
        self.assertNotEqual(first['pid'], self.launcher.pid)
        self.assertEqual(json.loads(self.call('GetAccessStatus')), {'approved': False, 'requested': False, 'scopes': []})
        other = self.call('RequestAccess', '(sas)', ('Unity GTK', ['read', 'control']))
        self.assertFalse(json.loads(self.call('GetAccessStatus'))['approved'])
        with self.assertRaises(GLib.Error):
            self.call('Diagnostics')
        self.cli('authorize', first['bus_name'], '--revoke')
        wait_for(lambda: not self.status().get('approved', True))
        # An unrelated access request/state signal must not regrant a revoked child.
        self.call('RequestAccess', '(sas)', ('Unity GTK', ['read', 'control']))
        time.sleep(.15)
        self.assertFalse(self.status()['approved'])
        clients = json.loads(self.cli('clients').stdout)
        self.assertFalse(next(c['approved'] for c in clients if c['bus_name'] == first['bus_name']))
        self.cli('authorize', first['bus_name'])
        wait_for(lambda: self.status().get('approved'))
        # Repeating an identical request preserves a grant, expansion resets it.
        self.call('RequestAccess', '(sas)', ('Widget', ['read']))
        self.cli('authorize', other)
        self.call('RequestAccess', '(sas)', ('Widget', ['read']))
        self.assertTrue(json.loads(self.call('GetAccessStatus'))['approved'])
        self.call('RequestAccess', '(sas)', ('Widget', ['read', 'control']))
        self.assertFalse(json.loads(self.call('GetAccessStatus'))['approved'])
        # Restart loses ephemeral grants. The launched live child requests again
        # and its parent reapproves only that verified process, using signals.
        self.daemon.terminate()
        self.daemon.wait(timeout=5)
        wait_for(lambda: not self.status().get('approved', True))
        self.start_daemon()
        wait_for(lambda: self.status().get('approved'))
        self.assertEqual(self.status()['pid'], first['pid'])
        self.assertFalse(json.loads(self.call('GetAccessStatus'))['approved'])
        # Closing/reopening grants the fresh child connection without any manual approval.
        os.kill(first['pid'], signal.SIGTERM)
        self.assertEqual(self.launcher.wait(timeout=5), 0)
        self.assertIsNone(self.daemon.poll())
        second = self.launch()
        self.assertNotEqual(first['bus_name'], second['bus_name'])
        self.assertNotEqual(first['pid'], second['pid'])
        wait_for(lambda: all(c['bus_name'] != first['bus_name'] for c in json.loads(self.cli('clients').stdout)))
        os.kill(second['pid'], signal.SIGTERM)
        self.assertEqual(self.launcher.wait(timeout=5), 0)
        self.assertIsNone(self.daemon.poll())
        self.assertEqual(json.loads(self.cli('diagnose').stdout)['api_version'], 1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
