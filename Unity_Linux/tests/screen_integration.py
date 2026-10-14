#!/usr/bin/env python3
"""Real daemon/IPC/TLS for screens and locking; capture/lock fixtures are isolated."""
import hashlib
import json
import os
import socket
import ssl
import struct
import threading
import uuid
import unittest
from gi.repository import Gio, GLib
import integration as harness


class ScreenIntegration(unittest.TestCase):
    cli = harness.Integration.__dict__['cli']
    call = harness.Integration.__dict__['call']
    start_daemon = harness.Integration.__dict__['start_daemon']

    @classmethod
    def setUpClass(cls):
        harness.CAPS.append({'id': 'lock', 'status': 'available', 'reason': ''})
        harness.Integration.setUpClass.__func__(cls)
        cls.jpeg = (harness.ROOT / 'tests/fixtures/preview.jpg').read_bytes()
        # The installed helper entry point emits real JPEG, with bounded test frames.
        (cls.bin / 'screen-capture.py').write_text(
            'import sys\n'
            f'sys.path.insert(0,{str(harness.ROOT / "tests/fixtures")!r})\n'
            'from screen_backend import run\n'
            f'data=bytes.fromhex({cls.jpeg.hex()!r})\n'
            f'run(data,{str(cls.dir)!r})\n')
        cls.lock_count = 0
        cls.lock_ready = threading.Event()

        def locker():
            context = GLib.MainContext.new(); context.push_thread_default()
            cls.lock_loop = GLib.MainLoop(context=context)
            cls.lock_bus = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],
                Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
            cls.lock_bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                'RequestName', GLib.Variant('(su)', ('org.freedesktop.ScreenSaver', 0)), None, Gio.DBusCallFlags.NONE, 1000, None)
            info = Gio.DBusNodeInfo.new_for_xml('<node><interface name="org.freedesktop.ScreenSaver"><method name="Lock"/></interface></node>')

            def lock(*args):
                cls.lock_count += 1
                args[-1].return_value(GLib.Variant('()', ()))

            cls.lock_bus.register_object('/ScreenSaver', info.interfaces[0], lock, None, None)
            cls.lock_ready.set(); cls.lock_loop.run(); cls.lock_bus.close_sync(None)
            context.pop_thread_default()

        cls.lock_thread = threading.Thread(target=locker, daemon=True); cls.lock_thread.start()
        assert cls.lock_ready.wait(5)

    @classmethod
    def tearDownClass(cls):
        cls.lock_loop.quit(); cls.lock_thread.join(3)
        harness.Integration.tearDownClass.__func__(cls)

    def create_peer(self, port, fingerprint, secret=None):
        return harness.Peer(port, fingerprint, secret)

    def open_stream(self, kind):
        raw = socket.create_connection(('127.0.0.1', self.port))
        raw.settimeout(5)
        return raw

    @staticmethod
    def closed(stream):
        try: return stream.recv(1) == b''
        except (ConnectionError, ssl.SSLError): return True

    def check_transport_disconnect(self): pass

    def command(self, action):
        while True:
            message = self.peer.read()
            if message['type'] == 'ping': self.peer.send({'type': 'pong'})
            if message.get('feature') == 'screen' and message.get('action') == action:
                return message

    def request(self, feature, action, data=None):
        request = uuid.uuid4().hex
        self.peer.send({'type':'request', 'id':request, 'feature':feature, 'action':action, 'data':data or {}})
        while True:
            message = self.peer.read()
            if message['type'] == 'ping': self.peer.send({'type':'pong'})
            if message['type'] == 'result' and message['id'] == request: return message
            if message.get('feature') == 'screen' and message.get('action') == 'start':
                self.pending_command = message

    def execute(self, action, data=None):
        return self.call(self.bus, self.path, 'org.unityconnect.Device1', 'Execute', '(sss)', ('screen',action,json.dumps(data or {})))

    def operation(self, op):
        return json.loads(self.call(self.bus, op, 'org.unityconnect.Operation1', 'GetState'))

    def screen_socket(self, token, device=harness.DEVICE):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False; context.verify_mode = ssl.CERT_NONE; context.minimum_version = ssl.TLSVersion.TLSv1_3
        stream = context.wrap_socket(self.open_stream("screen"), server_hostname='unity.local')
        stream.settimeout(5)
        self.assertEqual(hashlib.sha256(stream.getpeercert(binary_form=True)).hexdigest(), self.fingerprint)
        body = json.dumps({'type':'screen_hello','version':1,'device_id':device,'token':token}).encode()
        stream.sendall(struct.pack('>I',len(body))+body)
        return stream

    @staticmethod
    def exact(stream, size):
        output = bytearray()
        while len(output) < size:
            data = stream.recv(size-len(output))
            if not data: raise EOFError('Screen stream closed')
            output.extend(data)
        return bytes(output)

    def ready(self, stream):
        length, = struct.unpack('>I',self.exact(stream,4))
        self.assertEqual(json.loads(self.exact(stream,length)), {'type':'ready'})

    def frame(self):
        return self.call(self.bus,self.path,'org.unityconnect.Screen1','GetFrame')

    def stopped(self):
        response = self.cli('status',harness.DEVICE,check=False)
        return response.returncode != 0 or json.loads(response.stdout)['state'].get('screen',{}).get('state') not in ('starting','streaming')

    def start_phone(self):
        op = self.execute('start', {'source':'phone'}); command = self.command('start')
        stream = self.screen_socket(command['data']['token']); self.ready(stream)
        stream.sendall(struct.pack('>I',len(self.jpeg))+self.jpeg)
        harness.wait_for(lambda:self.operation(op)['status']=='completed')
        return op, stream, command

    def start_desktop(self):
        def begin():
            try: return self.execute('start', {'source':'desktop', 'control':True})
            except GLib.Error as error:
                if 'Stop the current screen share first' in str(error): return None
                raise
        op = harness.wait_for(begin)
        command = self.command('start')
        stream = self.screen_socket(command['data']['token']); self.ready(stream)
        length, = struct.unpack('>I',self.exact(stream,4)); self.exact(stream,length)
        harness.wait_for(lambda:self.operation(op)['status']=='completed')
        harness.wait_for(lambda:self.screen_state().get('display_available'))
        return op, stream, command

    def screen_state(self):
        return json.loads(self.cli('status',harness.DEVICE).stdout)['state'].get('screen',{})

    def blank(self, command):
        result = self.request('screen','display',{'session_id':command['data']['request_id'], 'on':False})
        self.assertTrue(result['ok'], result)
        self.assertEqual((self.dir / 'fake-panel').read_text(), '0')

    def restored(self):
        harness.wait_for(lambda:(self.dir / 'fake-panel').read_text() == '72')

    def test_bidirectional_streams_locking_authorization_and_cleanup(self):
        self.fingerprint = json.loads(self.cli('discover').stdout)['fingerprint']
        pending = self.create_peer(self.port,self.fingerprint); self.cli('pair',harness.DEVICE)
        secret = pending.read()['secret']; pending.close(); self.peer = self.create_peer(self.port,self.fingerprint,secret)
        self.path = '/org/unityconnect/devices/d_'+harness.DEVICE
        name = self.call(self.bus,harness.MANAGER,'org.unityconnect.Manager1','RequestAccess','(sas)',('Screen test',['read','control']))
        self.cli('authorize',name)
        caps = self.peer.read(); self.assertEqual(caps['type'],'capabilities')
        self.assertTrue(self.request('lock','lock')['ok']); self.assertEqual(self.lock_count,1)
        for feature,action,data in [('files','send',{}),('lock','lock',{'shell':'bad'}),('screen','start',{'source':'bad'}),('screen','start',{'source':'phone','token':'bad'})]:
            self.assertFalse(self.request(feature,action,data)['ok'])
        self.cli('feature',harness.DEVICE,'lock','--disable')
        self.assertFalse(self.request('lock','lock')['ok']); self.assertEqual(self.lock_count,1)
        self.cli('feature',harness.DEVICE,'lock')
        op = self.call(self.bus,self.path,'org.unityconnect.Device1','Execute','(sss)',('lock','lock','{}'))
        message = self.peer.read()
        while message.get('feature') != 'lock': message = self.peer.read()
        self.peer.send({'type':'result','id':message['id'],'ok':True,'data':{'state':'locked'},'error':''})
        harness.wait_for(lambda:self.operation(op)['status']=='completed')
        with self.screen_socket('00'*32) as bad: self.assertTrue(self.closed(bad))
        op, stream, command = self.start_phone(); token = command['data']['token']
        sequence,width,height,jpeg = self.frame()
        self.assertEqual((sequence,width,height),(1,256,192)); self.assertEqual(bytes(jpeg),self.jpeg)
        with self.assertRaises(GLib.Error): self.call(self.other,self.path,'org.unityconnect.Screen1','GetFrame')
        self.assertNotIn(token,self.cli('status',harness.DEVICE).stdout)
        with self.screen_socket(token) as replay: self.assertTrue(self.closed(replay))
        with self.screen_socket(token,'ab'*16) as wrong: self.assertTrue(self.closed(wrong))
        with self.assertRaises(GLib.Error): self.execute('start',{'source':'desktop'})
        self.peer.event('battery',{'level':80,'charging':False})
        harness.wait_for(lambda:json.loads(self.cli('status',harness.DEVICE).stdout)['state']['battery']['level']==80)
        stop = self.execute('stop'); harness.wait_for(lambda:self.operation(stop)['status']=='completed'); stream.close()
        with self.assertRaises(GLib.Error): self.frame()
        self.check_transport_disconnect()
        # A phone can initiate either direction; the start operation remains owned by that phone.
        response = self.request('screen','start',{'source':'desktop'}); self.assertTrue(response['ok'])
        command = getattr(self,'pending_command',None) or self.command('start'); self.pending_command = None
        with self.assertRaises(GLib.Error): self.operation('/org/unityconnect/operations/o_' + response['data']['operation'])
        stream = self.screen_socket(command['data']['token']); self.ready(stream)
        length, = struct.unpack('>I',self.exact(stream,4)); self.assertEqual(self.exact(stream,length),self.jpeg)
        harness.wait_for(lambda:self.screen_state().get('input_enabled'))
        session = command['data']['request_id']
        self.assertEqual(self.screen_state()['session_id'], session)
        self.assertTrue(self.request('screen','input',{'session_id':session,'seq':1,'kind':'tap','x':.25,'y':.75})['ok'])
        self.assertTrue(self.request('screen','input',{'session_id':session,'seq':2,'kind':'text','text':'தமிழ் 😀'})['ok'])
        recorded = [json.loads(line) for line in (self.dir / 'screen-input.jsonl').read_text().splitlines()]
        self.assertEqual(recorded[-2]['data']['x'], .25)
        self.assertEqual(recorded[-1]['data']['text'], 'தமிழ் 😀')
        for data in [ {'session_id':'ab'*16,'seq':3,'kind':'tap','x':.5,'y':.5},
                      {'session_id':session,'seq':3,'kind':'tap','x':1.1,'y':.5},
                      {'session_id':session,'seq':3,'kind':'key','key':'shell'},
                      {'session_id':session,'seq':0,'kind':'text','text':'bad'} ]:
            self.assertFalse(self.request('screen','input',data)['ok'])
        name = self.call(self.other,harness.MANAGER,'org.unityconnect.Manager1','RequestAccess','(sas)',('Screen reader',['read']))
        self.cli('authorize',name)
        with self.assertRaises(GLib.Error):
            self.call(self.other,self.path,'org.unityconnect.Device1','Execute','(sss)',('screen','display',json.dumps({'session_id':session,'on':False})))
        self.blank(command)
        display = self.execute('display',{'session_id':session,'on':True})
        harness.wait_for(lambda:self.operation(display)['status']=='completed'); self.restored()
        self.blank(command)
        self.assertTrue(self.request('screen','stop')['ok']); harness.wait_for(self.stopped); self.restored(); stream.close()
        self.assertFalse(self.request('screen','input',{'session_id':session,'seq':4,'kind':'tap','x':.5,'y':.5})['ok'])
        # A stalled receiver must close rather than accumulate a video backlog.
        padded = self.jpeg[:2] + (b'\xff\xfe' + struct.pack('>H', 60002) + bytes(60000)) * 3 + self.jpeg[2:]
        helper = self.bin / 'screen-capture.py'
        original = helper.read_text(); helper.write_text(original.replace(self.jpeg.hex(), padded.hex()))
        op = self.execute('start', {'source':'desktop'}); command = self.command('start')
        stream = self.screen_socket(command['data']['token']); stream.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096); self.ready(stream)
        harness.wait_for(lambda:self.operation(op)['status']!='running')
        harness.wait_for(self.stopped, timeout=10); stream.close(); helper.write_text(original)
        # Invalid binary frames are rejected before allocation/decode and fail start.
        op = self.execute('start',{'source':'phone'}); command = self.command('start')
        with self.screen_socket(command['data']['token']) as stream:
            self.ready(stream); stream.sendall(struct.pack('>I',262145)); self.assertTrue(self.closed(stream))
        harness.wait_for(lambda:self.operation(op)['status']=='failed')
        # Permission denial, cancellation before consent, toggles, replacement, and revocation.
        op = self.execute('start',{'source':'phone'}); command = self.command('start')
        self.peer.send({'type':'result','id':command['id'],'ok':False,'data':{},'error':'Permission denied'})
        harness.wait_for(lambda:self.operation(op)['status']=='failed'); harness.wait_for(self.stopped)
        op = self.execute('start',{'source':'phone'}); self.command('start')
        self.call(self.bus,op,'org.unityconnect.Operation1','Cancel'); harness.wait_for(self.stopped)
        _,stream,command = self.start_desktop(); self.blank(command); self.cli('feature',harness.DEVICE,'screen','--disable')
        self.assertFalse(self.request('screen','start',{'source':'phone'})['ok']); stream.close()
        with self.assertRaises(GLib.Error): self.frame()
        self.cli('feature',harness.DEVICE,'screen'); harness.wait_for(self.stopped)
        self.restored()
        _,stream,_ = self.start_phone()
        caps = [dict(cap,status='disabled') if cap['id']=='screen' else cap for cap in harness.CAPS]
        self.peer.send({'type':'capabilities','capabilities':caps}); stream.close()
        harness.wait_for(lambda:not json.loads(self.cli('status',harness.DEVICE).stdout)['state'].get('screen'))
        self.peer.send({'type':'capabilities','capabilities':harness.CAPS})
        harness.wait_for(lambda:any(c['id']=='screen' and c['status']=='available' for c in json.loads(self.cli('status',harness.DEVICE).stdout)['capabilities']))
        _,stream,command = self.start_desktop(); self.blank(command); replacement = self.create_peer(self.port,self.fingerprint,secret)
        self.peer.close(); self.peer=replacement; stream.close()
        harness.wait_for(lambda:not json.loads(self.cli('status',harness.DEVICE).stdout)['state'].get('screen'))
        self.restored()
        _,stream,command = self.start_desktop(); self.blank(command)
        self.peer.close(); stream.close(); harness.wait_for(self.stopped); self.restored()
        self.peer = self.create_peer(self.port,self.fingerprint,secret)
        _,stream,command = self.start_desktop(); self.blank(command)
        self.cli('unpair',harness.DEVICE); stream.close(); self.peer.close(); self.restored()


if __name__ == '__main__': unittest.main(verbosity=2)
