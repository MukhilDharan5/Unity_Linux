#!/usr/bin/env python3
"""Automatic clipboard changes, echo protection and recovery on isolated D-Bus/TLS."""
import json
import os
import time
import unittest
import integration as h


class ClipboardIntegration(h.Integration):
    @classmethod
    def start_daemon(cls):
        helpers = cls.dir / 'helpers'
        helpers.mkdir(exist_ok=True)
        helper = helpers / 'wl-copy'
        helper.write_text('''#!/usr/bin/python3
import os, sys
from pathlib import Path
target = Path(os.environ['UNITY_TEST_CLIPBOARD'])
if target.with_suffix('.fail').exists(): sys.exit(1)
temporary = target.with_suffix('.new')
temporary.write_bytes(sys.stdin.buffer.read())
temporary.replace(target)
''')
        helper.chmod(0o755)
        watcher = helpers / 'wl-paste'
        watcher.write_text('''#!/usr/bin/python3
import os, subprocess, sys, time
from pathlib import Path
target = Path(os.environ['UNITY_TEST_CLIPBOARD'])
last = None
while True:
 try:
  stat = target.stat()
  stamp = (stat.st_mtime_ns, stat.st_size)
  if stamp != last:
   last = stamp
   subprocess.run(sys.argv[sys.argv.index('--watch') + 1:], input=target.read_bytes(), check=True)
 except FileNotFoundError: pass
 time.sleep(.02)
''')
        watcher.chmod(0o755)
        cls.clipboard = cls.dir / 'clipboard.txt'
        previous = dict(os.environ)
        try:
            os.environ.update(PATH=str(helpers) + ':' + os.environ['PATH'], UNITY_TEST_CLIPBOARD=str(cls.clipboard))
            h.Integration.start_daemon.__func__(cls)
        finally:
            os.environ.clear(); os.environ.update(previous)

    def test_end_to_end(self):
        info = json.loads(self.cli('discover').stdout)
        peer = h.Peer(self.port, info['fingerprint'], automatic_clipboard=False); self.cli('pair', h.DEVICE)
        secret = peer.read()['secret']; peer.close()
        peer = h.Peer(self.port, info['fingerprint'], secret, automatic_clipboard=False)
        text = 'தமிழ் 😀\n$(literal) `clipboard`\n'
        peer.event('clipboard', {'text': text, 'automatic': True})
        h.wait_for(lambda: self.clipboard.exists() and self.clipboard.read_text() == text)
        time.sleep(.15)
        self.assert_no_echo(peer)
        # The daemon sends real selection changes without GTK or any IPC action.
        local = 'Desktop → phone\nதமிழ் 😀\n'
        started = time.monotonic()
        self.set_clipboard(local)
        self.expect_clipboard(peer, local)
        self.assertLess(time.monotonic() - started, 1)
        # Repeated notifications for the same selection never create another send.
        self.set_clipboard(local)
        time.sleep(.15)
        self.assert_no_echo(peer)
        self.set_clipboard('retry without a manual resend')
        self.expect_clipboard(peer, 'retry without a manual resend', acknowledge=False)
        self.expect_clipboard(peer, 'retry without a manual resend')
        # Clipboard ownership does not depend on a notification server.
        self.notices.available = False
        peer.event('clipboard', {'text': 'without a notification server'})
        h.wait_for(lambda: self.clipboard.read_text() == 'without a notification server')
        self.cli('feature', h.DEVICE, 'clipboard', '--disable')
        peer.event('clipboard', {'text': 'disabled'})
        time.sleep(.15)
        self.assertEqual(self.clipboard.read_text(), 'without a notification server')
        self.set_clipboard('changed while disabled')
        time.sleep(.15)
        self.assert_no_echo(peer)
        self.cli('feature', h.DEVICE, 'clipboard')
        self.expect_clipboard(peer, 'changed while disabled')
        self.clipboard.with_suffix('.fail').touch()
        queries = self.notices.unavailable_queries
        peer.event('clipboard', {'text': 'backend unavailable'})
        h.wait_for(lambda: self.notices.unavailable_queries > queries)
        peer.send({'type': 'ping'})
        reply = self.control(peer)
        self.assertEqual(reply['type'], 'pong')
        self.assertEqual(self.clipboard.read_text(), 'changed while disabled')
        # A temporary helper failure recovers without a manual resend.
        self.clipboard.with_suffix('.fail').unlink()
        h.wait_for(lambda: self.clipboard.read_text() == 'backend unavailable')
        time.sleep(.15)
        self.assert_no_echo(peer)
        self.set_clipboard('😀' * 17000)
        time.sleep(.15)
        self.assert_no_echo(peer)
        self.set_clipboard('')
        self.expect_clipboard(peer, '')
        # A replacement connection receives the current value once.
        peer.close()
        h.wait_for(lambda: 'disconnected' in self.cli('list').stdout)
        peer = h.Peer(self.port, info['fingerprint'], secret, automatic_clipboard=False)
        self.expect_clipboard(peer, '')
        peer.close()

    def set_clipboard(self, text):
        temporary = self.clipboard.with_suffix('.local')
        temporary.write_text(text)
        temporary.replace(self.clipboard)

    def control(self, peer):
        reply = peer.read()
        while reply['type'] == 'capabilities': reply = peer.read()
        return reply

    def expect_clipboard(self, peer, text, acknowledge=True):
        reply = self.control(peer)
        self.assertEqual((reply['type'], reply.get('feature'), reply.get('action')), ('command', 'clipboard', 'set'))
        self.assertEqual(reply['data']['text'], text)
        self.assertTrue(reply['data']['automatic'])
        if acknowledge:
            peer.send({'type': 'result', 'id': reply['id'], 'ok': True, 'data': {'state': 'applied'}, 'error': ''})

    def assert_no_echo(self, peer):
        peer.send({'type': 'ping'})
        self.assertEqual(self.control(peer)['type'], 'pong')


if __name__ == '__main__':
    unittest.main(verbosity=2)
