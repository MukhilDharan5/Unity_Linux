#!/usr/bin/env python3
"""Choose each direction on each authenticated transport while both remain connected."""
import json
import struct
import unittest
from gi.repository import GLib
import dual_transport_integration as dual
from fake_bluez import AUDIO
from integration import Peer, DEVICE, MANAGER, wait_for


class AudioChoices(dual.DualTransportAudio):
    side_transport = 'lan'

    def open_stream(self, kind):
        if self.side_transport == 'bluetooth':
            return self.bluez.open(AUDIO)
        return super().open_stream(kind)

    def test_audio_security_bytes_cleanup_and_recovery(self):
        self.fingerprint = json.loads(self.cli('discover').stdout)['fingerprint']
        pending = Peer(self.port, self.fingerprint)
        self.cli('pair', DEVICE); secret = pending.read()['secret']; pending.close()
        lan = Peer(self.port, self.fingerprint, secret)
        bt = Peer(self.port, self.fingerprint, secret, self.bluez.open())
        self.peer = lan
        self.path = '/org/unityconnect/devices/d_' + DEVICE
        name = self.call(self.bus, MANAGER, 'org.unityconnect.Manager1', 'RequestAccess', '(sas)',
                         ('Audio transport choices', ['read', 'control']))
        self.cli('authorize', name)
        wait_for(lambda: json.loads(self.cli('status', DEVICE).stdout)['state']['transport']['kind'] == 'both')
        played = self.backend.with_suffix('.played')
        total = b''
        for transport, peer in [('lan', lan), ('bluetooth', bt)]:
            for source in ['desktop', 'phone']:
                with self.subTest(transport=transport, source=source):
                    self.peer = peer
                    op = self.execute('start', {'source': source, 'transport': transport})
                    command = self.next_command()
                    data = command['data']; count = data['frames'] * data['channels'] * 2
                    self.assertEqual((data['source'], data['transport']), (source, transport))
                    self.assertEqual((data['rate'], data['channels'], count),
                                     (48000, 2, 1920) if transport == 'lan' else (16000, 1, 320))
                    # A token cannot cross from its parent transport onto the other link.
                    self.side_transport = 'bluetooth' if transport == 'lan' else 'lan'
                    with self.audio_socket(data['token']) as wrong:
                        self.assertTrue(self.closed(wrong))
                    self.side_transport = transport
                    stream = self.audio_socket(data['token']); self.ready(stream)
                    pcm = (bytes(range(256)) * 8)[:count]
                    if source == 'phone':
                        stream.sendall(struct.pack('>Q', 0) + pcm); total += pcm
                        wait_for(lambda: played.exists() and played.read_bytes() == total)
                        self.assertTrue(self.restored())
                    else:
                        self.assertEqual(self.exact(stream, count + 8)[8:], pcm)
                    wait_for(lambda: self.operation(op)['status'] == 'completed')
                    state = self.state_audio()
                    self.assertEqual((state['source'], state['transport']), (source, transport))
                    stopped = self.execute('stop')
                    wait_for(lambda: self.operation(stopped)['status'] == 'completed')
                    stream.close(); self.assertTrue(self.restored())
                    self.assertEqual(json.loads(self.cli('status', DEVICE).stdout)['state']['transport']['kind'], 'both')
        # Automatic starts prefer LAN and then use Bluetooth only after LAN disconnects.
        lan.close()
        wait_for(lambda: json.loads(self.cli('status', DEVICE).stdout)['state']['transport']['kind'] == 'bluetooth')
        with self.assertRaises(GLib.Error): self.execute('start', {'transport': 'lan'})
        self.peer = bt; self.side_transport = 'bluetooth'
        op = self.execute('start', {'source': 'phone'})
        command = self.next_command()
        self.assertEqual(command['data']['transport'], 'bluetooth')
        stream = self.audio_socket(command['data']['token']); self.ready(stream)
        stream.sendall(struct.pack('>Q', 0) + bytes(320))
        wait_for(lambda: self.operation(op)['status'] == 'completed')
        bt.close()
        wait_for(lambda: 'disconnected' in self.cli('list').stdout)
        self.assertTrue(self.closed(stream)); stream.close(); self.assertTrue(self.restored())


if __name__ == '__main__':
    unittest.main(verbosity=2)
