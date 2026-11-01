#!/usr/bin/env python3
"""Gallery refreshes reclaim consumed results without dropping active or unread work."""
import base64
import hashlib
import json
import socket
import ssl
import struct
import unittest
from gi.repository import GLib
import integration as h


class OperationHistory(h.Integration):
    def test_end_to_end(self):
        info = json.loads(self.cli('discover').stdout)
        peer = h.Peer(self.port, info['fingerprint'])
        self.cli('pair', h.DEVICE)
        secret = peer.read()['secret']; peer.close()
        peer = h.Peer(self.port, info['fingerprint'], secret)
        self.addCleanup(peer.close)
        for bus in (self.bus, self.other):
            name = self.call(bus, h.MANAGER, 'org.unityconnect.Manager1', 'RequestAccess',
                             '(sas)', ('History test', ['read', 'control']))
            self.cli('authorize', name)
        path = '/org/unityconnect/devices/d_' + h.DEVICE
        image = {'mime': 'image/jpeg', 'width': 256, 'height': 192,
                 'data': base64.b64encode((h.ROOT / 'tests/fixtures/preview.jpg').read_bytes()).decode()}

        def state(op, bus=None):
            return json.loads(self.call(bus or self.bus, op, 'org.unityconnect.Operation1', 'GetState'))

        def command_for(feature, action):
            while True:
                command = peer.read()
                if command['type'] == 'ping':
                    peer.send({'type': 'pong'})
                elif (command.get('feature'), command.get('action')) == (feature, action):
                    return command

        def request():
            op = self.call(self.bus, path, 'org.unityconnect.Sharing1', 'GetPhotoPreview', '(su)', ('ab' * 16, 256))
            return op, command_for('photos', 'preview')

        def finish(command):
            peer.send({'type': 'result', 'id': command['id'], 'ok': True, 'error': '',
                       'data': {'id': 'ab' * 16, 'preview': image}})

        def consumed():
            op, command = request(); finish(command)
            result = h.wait_for(lambda: (lambda value: value if value['status'] == 'completed' else None)(state(op)))
            self.assertEqual(result['result']['preview'], image)
            return op

        # A real phone-screen stream has a completed start request while capture
        # remains live. History pressure must preserve that operation too.
        screen_op = self.call(self.bus, path, 'org.unityconnect.Device1', 'Execute',
                              '(sss)', ('screen', 'start', '{"source":"phone"}'))
        command = command_for('screen', 'start')
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False; context.verify_mode = ssl.CERT_NONE
        context.minimum_version = ssl.TLSVersion.TLSv1_3
        stream = context.wrap_socket(socket.create_connection(('127.0.0.1', self.port)), server_hostname='unity.local')
        self.addCleanup(stream.close); stream.settimeout(5)
        self.assertEqual(hashlib.sha256(stream.getpeercert(binary_form=True)).hexdigest(), info['fingerprint'])
        hello = json.dumps({'type': 'screen_hello', 'version': 1, 'device_id': h.DEVICE,
                            'token': command['data']['token']}).encode()
        stream.sendall(struct.pack('>I', len(hello)) + hello)

        def exact(size):
            data = b''
            while len(data) < size:
                block = stream.recv(size - len(data))
                self.assertTrue(block); data += block
            return data

        self.assertEqual(json.loads(exact(struct.unpack('>I', exact(4))[0])), {'type': 'ready'})
        jpeg = base64.b64decode(image['data'])
        stream.sendall(struct.pack('>I', len(jpeg)) + jpeg)
        h.wait_for(lambda: state(screen_op)['status'] == 'completed')
        unread, command = request(); finish(command)
        oldest = consumed()
        for _ in range(510):
            consumed()
        running, command = request()
        self.assertEqual(state(running)['status'], 'running')
        for _ in range(20):
            consumed()
        self.assertEqual(state(running)['status'], 'running', 'Active work survives history pressure')
        self.assertEqual(state(screen_op)['status'], 'completed', 'Live streams keep their completed start operation')
        self.assertEqual(state(unread)['status'], 'completed', 'Unread results survive history pressure')
        with self.assertRaises(GLib.Error):
            state(oldest)
        finish(command)
        h.wait_for(lambda: state(running)['status'] == 'completed')
        stream.close()
        h.wait_for(lambda: json.loads(self.cli('status', h.DEVICE).stdout)['state'].get('screen', {}).get('state') != 'streaming')

        # All slots containing unread results still apply backpressure. Reading
        # another owner's result must not acknowledge it or free a slot.
        unread = []
        for _ in range(512):
            op, command = request(); finish(command); unread.append(op)
        with self.assertRaises(GLib.Error):
            state(unread[0], self.other)
        with self.assertRaises(GLib.Error) as error:
            request()
        self.assertIn('operation limit reached', str(error.exception))
        h.wait_for(lambda: state(unread[0])['status'] == 'completed')
        consumed()
        history = json.loads(self.call(self.bus, h.MANAGER, 'org.unityconnect.Manager1', 'ListOperations'))
        self.assertLessEqual(len(history), 512)


if __name__ == '__main__':
    unittest.main(verbosity=2)
