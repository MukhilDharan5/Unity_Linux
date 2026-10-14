#!/usr/bin/env python3
"""Real TLS/DBus call control with an isolated Bluetooth telephony service."""
import json
import os
import socket
import time
import unittest
from gi.repository import GLib
import integration as h
from fake_ofono import FakeOfono
from telephony_integration import TelephonyIntegration


class HfpIntegration(TelephonyIntegration):
    @classmethod
    def setUpClass(cls):
        os.environ['DBUS_SYSTEM_BUS_ADDRESS'] = os.environ['DBUS_SESSION_BUS_ADDRESS']
        cls.ofono = FakeOfono()
        super().setUpClass()

    @classmethod
    def tearDownClass(cls):
        super().tearDownClass()
        cls.ofono.close()

    # The older companion path is covered by telephony_integration.py.
    def test_calls_messages_audio_and_consent_lifecycle(self):
        info = json.loads(self.cli('discover').stdout)
        peer = h.Peer(self.port, info['fingerprint'])
        self.cli('pair', h.DEVICE)
        secret = peer.read()['secret']; peer.close()
        peer = h.Peer(self.port, info['fingerprint'], secret)
        self.path = '/org/unityconnect/devices/d_' + h.DEVICE
        name = self.call(self.bus, h.MANAGER, 'org.unityconnect.Manager1', 'RequestAccess', '(sas)', ('HFP test', ['read', 'control']))
        self.cli('authorize', name)

        def execute(action, data=None):
            return self.call(self.bus, self.path, 'org.unityconnect.Device1', 'Execute', '(sss)', ('call_control', action, json.dumps(data or {})))

        def complete(op):
            return h.wait_for(lambda: (lambda s: s if s['status'] != 'running' else None)(json.loads(self.call(self.bus, op, 'org.unityconnect.Operation1', 'GetState'))))

        def success(action, data=None):
            result = complete(execute(action, data)); self.assertEqual(result['status'], 'completed', result); return result['result']

        def snapshot(): return json.loads(self.call(self.bus, self.path, 'org.unityconnect.Telephony1', 'GetSnapshot')).get('call_control', {})
        peer.event('call_control', {'backend': 'bluetooth_hfp', 'calls': [], 'muted': False, 'route': 'phone', 'routes': []})
        h.wait_for(lambda: snapshot().get('backend') == 'bluetooth_hfp')
        devices = h.wait_for(lambda: (lambda r: r if r.get('devices') else None)(success('hfp_devices')))
        self.assertEqual({p['address'] for p in devices['devices']}, {'AA:BB:CC:DD:EE:FF', '11:22:33:44:55:66'})
        success('hfp_select', {'address': 'AA:BB:CC:DD:EE:FF'})
        # Service restart exposes cold modems with no Serial until Powered is set.
        # The saved phone must reconnect without another manual selection.
        self.ofono.close()
        h.wait_for(lambda: snapshot().get('error'))
        type(self).ofono = FakeOfono(cold=True)
        h.wait_for(lambda: not snapshot().get('error'),timeout=8)
        selected = '/hfp/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF'
        other = '/hfp/org/bluez/hci0/dev_11_22_33_44_55_66'
        self.assertTrue(self.ofono.modems[selected]['Powered'])
        self.assertFalse(self.ofono.modems[other]['Powered'])
        self.assertIn((selected,'SetProperty',('Powered',True)),self.ofono.commands)
        # Reconnect again in the same service instance.
        self.ofono.modems[selected]['Powered'] = False
        self.ofono.modems[selected]['Interfaces'] = []
        self.ofono.emit(selected,'org.ofono.Modem','PropertyChanged','(sv)',('Powered',GLib.Variant('b',False)))
        h.wait_for(lambda:self.ofono.modems[selected]['Powered'])
        self.assertFalse(self.ofono.modems[other]['Powered'])
        # Restore the conventional fixture paths for the remaining control checks.
        self.ofono.close(); type(self).ofono = FakeOfono()
        h.wait_for(lambda:not snapshot().get('error'),timeout=8)
        self.ofono.add('/hfp_other/voicecall01')
        call = self.ofono.add()
        state = h.wait_for(lambda: snapshot() if snapshot().get('calls') else None)
        call_id = state['calls'][0]['id']; self.assertEqual(len(state['calls']), 1)
        success('answer', {'call_id': call_id})
        h.wait_for(lambda: snapshot()['calls'][0]['state'] == 'active')
        connected = snapshot()['calls'][0]['connect_time']
        self.assertGreater(connected, 0)
        # Controls are executed on HFP, with no Android privileged call command.
        peer.sock.settimeout(.1)
        with self.assertRaises(socket.timeout): peer.read()
        peer.sock.settimeout(5)
        success('hold', {'call_id': call_id}); h.wait_for(lambda: snapshot()['calls'][0]['state'] == 'held')
        success('unhold', {'call_id': call_id}); h.wait_for(lambda: snapshot()['calls'][0]['state'] == 'active')
        self.assertEqual(snapshot()['calls'][0]['connect_time'], connected)
        # Match the physical failure: oFono repeatedly disappears for longer
        # than popup debounce while the independent phone state stays offhook.
        peer.event('call_notifications', {'state':'offhook','number':''})
        for _ in range(2):
            h.wait_for(lambda:self.notices.items and self.notices.items[-1]['title'] == 'Call in progress')
            notification = self.notices.items[-1]['id']
            closed = list(self.notices.closed)
            previous_id = call_id
            self.ofono.close()
            recovering = h.wait_for(lambda:snapshot() if snapshot().get('reconnecting') else None)
            self.assertEqual(recovering['calls'][0]['id'],previous_id)
            with self.assertRaisesRegex(GLib.Error,'reconnecting'):
                execute('hangup', {'call_id': previous_id})
            with self.assertRaisesRegex(GLib.Error,'reconnecting'):
                execute('audio_start', {'address':'AA:BB:CC:DD:EE:FF'})
            h.wait_for(lambda:self.notices.items[-1]['title'] == 'Reconnecting call controls…')
            notice = self.notices.items[-1]
            self.assertEqual(notice['replaces'],notification)
            self.assertNotIn('hangup',notice['actions'])
            self.assertNotIn('hold',notice['actions'])
            time.sleep(3)
            self.assertTrue(snapshot()['reconnecting'])
            self.assertEqual(self.notices.closed,closed)
            type(self).ofono = FakeOfono()
            call = self.ofono.add('/hfp_phone/voicecall01','active')
            state = h.wait_for(lambda:snapshot() if not snapshot().get('reconnecting') and snapshot().get('calls') else None,timeout=8)
            call_id = state['calls'][0]['id']
            self.assertNotEqual(call_id,previous_id)
            h.wait_for(lambda:self.notices.items[-1]['title'] == 'Call in progress')
            self.assertEqual(self.notices.items[-1]['replaces'],notice['id'])
            self.assertEqual(self.notices.closed,closed)
            self.assertEqual(complete(execute('hangup', {'call_id': previous_id}))['status'],'failed')
            success('hold', {'call_id':call_id}); h.wait_for(lambda:snapshot()['calls'][0]['state'] == 'held')
            success('unhold', {'call_id':call_id}); h.wait_for(lambda:snapshot()['calls'][0]['state'] == 'active')
        success('dtmf', {'call_id': call_id, 'tone': '#'})
        self.assertIn(('/hfp_phone', 'SendTones', ('#',)), self.ofono.commands)
        # Route setup and microphone use stay tied to the selected phone.
        success('route', {'route': 'desktop'}); self.assertEqual(snapshot()['route'], 'bluetooth')
        success('audio_start', {'address': 'AA:BB:CC:DD:EE:FF'})
        success('mute', {'muted': True}); self.assertTrue(snapshot()['muted'])
        audio = json.loads(self.backend.read_text()); self.assertEqual(len(audio['modules']), 1)
        success('mute', {'muted': False}); self.assertEqual(len(json.loads(self.backend.read_text())['modules']), 2)
        success('audio_mute', {'muted': True}); self.assertTrue(snapshot()['muted'])
        success('audio_mute', {'muted': False}); self.assertFalse(snapshot()['muted'])
        # Ordinary companion refreshes cannot erase HFP state or stop the route.
        peer.event('call_control', {'backend': 'bluetooth_hfp', 'calls': [], 'muted': False, 'route': 'phone', 'routes': []})
        success('refresh'); self.assertEqual(snapshot()['calls'][0]['id'], call_id)
        self.assertEqual(len(json.loads(self.backend.read_text())['modules']), 2)
        success('route', {'route': 'phone'})
        self.assertFalse(json.loads(self.backend.read_text())['modules'])
        self.assertEqual(json.loads(self.backend.read_text())['profile'], 'off')
        success('route', {'route': 'desktop'}); success('audio_start', {'address': 'AA:BB:CC:DD:EE:FF'})
        success('hangup', {'call_id': call_id}); h.wait_for(lambda: not snapshot()['calls'])
        h.wait_for(lambda: not json.loads(self.backend.read_text())['modules'])
        result = complete(execute('hangup', {'call_id': call_id})); self.assertEqual(result['status'], 'failed')
        # Dialing/contacts/history still use ordinary companion commands.
        op = execute('dial', {'number': '12345'}); command = peer.read()
        self.assertEqual(command['action'], 'dial')
        peer.send({'type': 'result', 'id': command['id'], 'ok': True, 'data': {'state': 'submitted'}, 'error': ''})
        self.assertEqual(complete(op)['status'], 'completed')
        self.ofono.add('/hfp_phone/voicecall02', 'active')
        h.wait_for(lambda: snapshot()['calls'])
        fresh_id = snapshot()['calls'][0]['id']; self.assertNotEqual(fresh_id, call_id)
        # A call can end on the phone while the HFP service is still absent.
        self.ofono.close()
        h.wait_for(lambda:snapshot().get('reconnecting'))
        peer.event('call_notifications', {'state':'idle','number':''})
        h.wait_for(lambda:not snapshot().get('calls'))
        self.assertFalse(snapshot().get('reconnecting',False))
        type(self).ofono = FakeOfono(); self.ofono.add('/hfp_phone/voicecall02','active')
        h.wait_for(lambda:not snapshot().get('error') and snapshot().get('calls'),timeout=8)
        fresh_id = snapshot()['calls'][0]['id']
        self.ofono.fail = 'Hangup'
        self.assertEqual(complete(execute('hangup', {'call_id': fresh_id}))['status'], 'failed')
        self.ofono.fail = None
        self.cli('feature', h.DEVICE, 'call_control', '--disable')
        with self.assertRaises(GLib.Error): execute('hangup', {'call_id': fresh_id})
        self.assertIn('/hfp_phone/voicecall02', self.ofono.calls)
        peer.close()


del TelephonyIntegration

if __name__ == '__main__': unittest.main(verbosity=2)
