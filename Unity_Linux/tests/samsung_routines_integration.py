#!/usr/bin/env python3
"""Authenticated Samsung trigger dispatch and policy, without claiming execution."""
import json
import unittest

from gi.repository import GLib
import integration as h

h.CAPS.append({'id': 'samsung_routines', 'status': 'available', 'reason': 'Phone-configured routines'})
h.CAPS.append({'id': 'phone_connectivity', 'status': 'available', 'reason': 'Phone radio reports'})


class SamsungRoutinesIntegration(h.Integration):
    def test_end_to_end(self):
        info = json.loads(self.cli('discover').stdout)
        peer = h.Peer(self.port, info['fingerprint']); self.cli('pair', h.DEVICE)
        secret = peer.read()['secret']; peer.close()
        peer = h.Peer(self.port, info['fingerprint'], secret); self.addCleanup(peer.close)
        path = '/org/unityconnect/devices/d_' + h.DEVICE

        def execute(action, payload=None):
            return self.call(self.bus, path, 'org.unityconnect.Device1', 'Execute', '(sss)',
                             ('samsung_routines', action, json.dumps(payload or {})))

        with self.assertRaises(GLib.Error): execute('hotspot_on')
        name = self.call(self.bus, h.MANAGER, 'org.unityconnect.Manager1', 'RequestAccess',
                         '(sas)', ('Samsung Routines test', ['read', 'control']))
        self.cli('authorize', name)
        snapshot = {'hotspot_enabled': True, 'bluetooth_enabled': True, 'bluetooth_status': 'available',
                    'complete': False, 'reason': 'Android reports', 'devices': [
                        {'address': '11:22:33:44:55:66', 'name': 'Headphones', 'profiles': ['Audio', 'Calls']},
                        {'address': '22:33:44:55:66:77', 'name': 'Speaker', 'profiles': ['Audio']}]}
        def phone_state(): return json.loads(self.cli('status', h.DEVICE).stdout)['state'].get('phone_connectivity')
        def barrier():
            peer.send({'type': 'ping'})
            while True:
                message = peer.read()
                if message['type'] == 'capabilities': continue
                if message['type'] == 'ping': peer.send({'type': 'pong'}); continue
                self.assertEqual(message['type'], 'pong'); break
        peer.event('phone_connectivity', snapshot)
        h.wait_for(lambda: phone_state() == snapshot)
        for action, keyword in [('hotspot_on', 'UNITY_HOTSPOT_ON'), ('hotspot_off', 'UNITY_HOTSPOT_OFF'),
                                ('disconnect_bluetooth', 'UNITY_BLUETOOTH_DISCONNECT'),
                                ('disconnect_bluetooth_device', 'UNITY_BLUETOOTH_DISCONNECT_112233445566')]:
            payload = {'address': '11:22:33:44:55:66'} if action == 'disconnect_bluetooth_device' else {}
            op = execute(action, payload)
            while True:
                command = peer.read()
                if command['type'] == 'ping': peer.send({'type': 'pong'})
                if command['type'] == 'command': break
            self.assertEqual((command['feature'], command['action'], command['data']),
                             ('samsung_routines', action, payload))
            response = {'state': 'trigger_sent', 'keyword': keyword, 'confirmed': False}
            peer.send({'type': 'result', 'id': command['id'], 'ok': True, 'data': response, 'error': ''})
            result = h.wait_for(lambda: (lambda v: v if v['status'] != 'running' else None)(
                json.loads(self.call(self.bus, op, 'org.unityconnect.Operation1', 'GetState'))))
            self.assertEqual(result['status'], 'completed'); self.assertEqual(result['result'], response)
            self.assertEqual(phone_state(), snapshot, 'Trigger results cannot manufacture device state')
        invalid = dict(snapshot, devices=[snapshot['devices'][0], snapshot['devices'][0]])
        peer.event('phone_connectivity', invalid); barrier()
        self.assertEqual(phone_state(), snapshot)
        changed = dict(snapshot, hotspot_enabled=False, devices=snapshot['devices'][1:])
        peer.event('phone_connectivity', changed); h.wait_for(lambda: phone_state() == changed)
        self.cli('feature', h.DEVICE, 'phone_connectivity', '--disable')
        h.wait_for(lambda: phone_state() is None)
        peer.event('phone_connectivity', snapshot); barrier()
        self.assertIsNone(phone_state())
        self.cli('feature', h.DEVICE, 'phone_connectivity')
        for action, payload in [('toggle', {}), ('hotspot_on', {'keyword': 'custom'}),
                                ('disconnect_bluetooth', {'address': 'AA:BB:CC:DD:EE:FF'}),
                                ('disconnect_bluetooth_device', {'address': '../device'})]:
            with self.assertRaises(GLib.Error): execute(action, payload)
        self.cli('feature', h.DEVICE, 'samsung_routines', '--disable')
        with self.assertRaises(GLib.Error): execute('hotspot_on')
        self.cli('feature', h.DEVICE, 'samsung_routines')
        for status in ['disabled', 'permission_required', 'unsupported']:
            peer.send({'type': 'capabilities', 'capabilities': [
                dict(cap, status=status) if cap['id'] == 'samsung_routines' else cap for cap in h.CAPS]})
            h.wait_for(lambda: next(cap for cap in json.loads(self.cli('capabilities', h.DEVICE).stdout)
                                    if cap['id'] == 'samsung_routines')['status'] == status)
            with self.assertRaises(GLib.Error): execute('hotspot_on')


if __name__ == '__main__': unittest.main(verbosity=2)
