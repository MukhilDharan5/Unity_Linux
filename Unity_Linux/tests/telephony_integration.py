#!/usr/bin/env python3
"""Real daemon, TLS, public D-Bus and native alerts; HFP sound server is isolated."""
import json
import fcntl
import os
import subprocess
import socket
import unittest
from gi.repository import Gio, GLib
import integration as h

class TelephonyIntegration(unittest.TestCase):
 cli = h.Integration.__dict__['cli']
 call = h.Integration.__dict__['call']
 @classmethod
 def setUpClass(cls): h.Integration.setUpClass.__func__(cls)
 @classmethod
 def start_daemon(cls):
  cls.backend = cls.dir / 'call-audio.json'
  if not cls.backend.exists(): cls.backend.write_text(json.dumps({'profile':'off','modules':[], 'next':1, 'muted':False}))
  commands = cls.dir / 'commands'; commands.mkdir(exist_ok=True)
  if not (commands / 'pactl').exists(): (commands / 'pactl').symlink_to(h.ROOT / 'tests/fixtures/call_audio_backend.py')
  env = dict(os.environ,PATH=str(commands)+':'+os.environ['PATH'],UNITY_TEST_CALL_AUDIO=str(cls.backend))
  cls.log = open(cls.dir / 'daemon.log','a')
  cls.daemon = subprocess.Popen([cls.bin/'unityd','--state-dir',cls.state,'--bind','127.0.0.1','--port',str(cls.port)],env=env,stdout=cls.log,stderr=cls.log)
  probe = Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,None,None)
  h.wait_for(lambda: 'org.unityconnect' in probe.call_sync('org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','ListNames',None,None,Gio.DBusCallFlags.NONE,1000,None).unpack()[0])
  probe.close_sync(None)
  h.wait_for(lambda: cls.cli('diagnose',check=False).returncode == 0)
  def listening():
   try:
    with socket.create_connection(('127.0.0.1',cls.port),timeout=.2): return True
   except OSError: return False
  h.wait_for(listening)
 @classmethod
 def tearDownClass(cls): h.Integration.tearDownClass.__func__(cls)
 def test_calls_messages_audio_and_consent_lifecycle(self):
  info = json.loads(self.cli('discover').stdout)
  peer = h.Peer(self.port,info['fingerprint'])
  self.cli('pair',h.DEVICE)
  secret = peer.read()['secret']; peer.close()
  peer = h.Peer(self.port,info['fingerprint'],secret)
  self.path = '/org/unityconnect/devices/d_'+h.DEVICE
  name = self.call(self.bus,h.MANAGER,'org.unityconnect.Manager1','RequestAccess','(sas)',('Telephony test',['read','control']))
  self.cli('authorize',name)
  def execute(action,data=None): return self.call(self.bus,self.path,'org.unityconnect.Device1','Execute','(sss)',('call_control',action,json.dumps(data or {})))
  def operation(op): return json.loads(self.call(self.bus,op,'org.unityconnect.Operation1','GetState'))
  def complete(op): return h.wait_for(lambda: (lambda s: s if s['status'] != 'running' else None)(operation(op)))
  def snapshot(): return json.loads(self.call(self.bus,self.path,'org.unityconnect.Telephony1','GetSnapshot'))
  def audio_state(): return json.loads(self.backend.read_text())
  def backend_change(**changes):
   with self.backend.with_suffix('.lock').open('w') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX)
    backend=audio_state(); backend.update(changes)
    temporary=self.backend.with_suffix('.tmp'); temporary.write_text(json.dumps(backend)); temporary.replace(self.backend)
  def links(): return [m['argument'] for m in audio_state()['modules']]
  def routed(source,sink): return any(f'source={source} sink={sink}' in m for m in links())
  def success(action,data=None):
   result=complete(execute(action,data)); self.assertEqual(result['status'],'completed',result); return result['result']
  call_id = 'ab'*16
  call = {'id':call_id,'state':'ringing','number':'+15551234','name':'Test <caller>','connect_time':0,'can_hold':True,'conference':False}
  state = {'backend':'android_telecom','calls':[call],'muted':False,'route':'phone','routes':['phone','bluetooth','speaker']}
  peer.event('call_control',state)
  h.wait_for(lambda: snapshot().get('call_control') == state)
  notice = h.wait_for(lambda: next((n for n in self.notices.items if n['title']=='Incoming call'),None))
  self.assertIn('Test &lt;caller&gt;',notice['body']); self.assertIn('answer',notice['actions'])
  self.notices.action(notice['id'],'answer',bus=self.other)
  peer.sock.settimeout(.15)
  with self.assertRaises(socket.timeout): peer.read()
  peer.sock.settimeout(5)
  self.notices.action(notice['id'],'answer')
  command = peer.read(); self.assertEqual((command['feature'],command['action'],command['data']),('call_control','answer',{'call_id':call_id}))
  peer.send({'type':'result','id':command['id'],'ok':True,'data':{'state':'submitted'},'error':''})
  for action,data in [('dial',{'number':'*123#'}),('dtmf',{'tone':'12'}),('answer',{'call_id':'stale'}),('history',{'offset':-1})]:
   with self.assertRaises(GLib.Error): execute(action,data)
  op = self.call(self.bus,self.path,'org.unityconnect.Telephony1','ListContacts','(su)',('Alex',0))
  command = peer.read(); self.assertEqual(command['data'],{'query':'Alex','offset':0})
  peer.send({'type':'result','id':command['id'],'ok':True,'data':{'contacts':[{'id':'1','name':'Alex','number':'123'}],'next_offset':None},'error':''})
  self.assertEqual(complete(op)['result']['contacts'][0]['name'],'Alex')
  op = self.call(self.bus,self.path,'org.unityconnect.Telephony1','SendTone','(ss)',(call_id,'#'))
  command = peer.read(); self.assertEqual(command['data'],{'call_id':call_id,'tone':'#'})
  peer.send({'type':'result','id':command['id'],'ok':True,'data':{'state':'submitted'},'error':''}); self.assertEqual(complete(op)['status'],'completed')
  # Optional Android Phone integration exposes real speakerphone routes.
  op = execute('route', {'route':'speaker'}); command = peer.read()
  self.assertEqual((command['action'], command['data']), ('route', {'route':'speaker'}))
  peer.send({'type':'result','id':command['id'],'ok':True,'data':{'state':'requested'},'error':''})
  self.assertEqual(complete(op)['status'], 'completed')
  call['state']='active'; state['route']='bluetooth'; peer.event('call_control',state)
  h.wait_for(lambda: snapshot()['call_control']['route']=='bluetooth')
  devices=success('audio_devices'); self.assertEqual(devices['devices'][0]['address'],'AA:BB:CC:DD:EE:FF')
  self.assertTrue(any(c['id']=='bluetooth:11:22:33:44:55:66' for c in devices['microphones']))
  self.assertFalse(any('monitor' in c['id'] or 'phone_source'==c['id'] for c in devices['microphones']))
  for bad in [{'output':'laptop_speaker sink=phone_sink','microphone':''}, {'output':'','microphone':'laptop_speaker.monitor bad=1'}, {'output':'bluetooth:not-a-mac','microphone':''}]:
   with self.assertRaises(GLib.Error): execute('audio_select',bad)
  op = execute('audio_start',{'address':'AA:BB:CC:DD:EE:FF'}); self.assertEqual(complete(op)['status'],'completed')
  audio = json.loads(self.backend.read_text()); self.assertEqual(len(audio['modules']),2)
  self.assertTrue(any('source=laptop_microphone sink=phone_sink' in m['argument'] for m in audio['modules']))
  # Defaults change independently: only the affected direction is rebuilt.
  capture_id=next(m['index'] for m in audio_state()['modules'] if 'source=laptop_microphone ' in m['argument'])
  backend_change(default_sink='usb_speaker')
  h.wait_for(lambda:routed('phone_source','usb_speaker'))
  self.assertEqual(capture_id,next(m['index'] for m in audio_state()['modules'] if 'source=laptop_microphone ' in m['argument']))
  backend_change(default_source='usb_microphone')
  h.wait_for(lambda:routed('usb_microphone','phone_sink'))
  # Explicit A2DP output + laptop microphone; defaults no longer retarget it.
  headset='bluetooth:11:22:33:44:55:66'
  op=self.call(self.bus,self.path,'org.unityconnect.Telephony1','SelectDesktopAudioDevices','(ss)',(headset,'laptop_microphone'))
  self.assertEqual(complete(op)['status'],'completed')
  self.assertTrue(routed('phone_source','headphone_sink')); self.assertEqual(audio_state()['headphone_profile'] if 'headphone_profile' in audio_state() else 'a2dp-sink','a2dp-sink')
  self.assertTrue(routed('laptop_microphone','phone_sink'))
  # Headset microphone prepares HFP and waits for asynchronous endpoints.
  backend_change(profile_delay=.35)
  result=success('audio_select',{'output':headset,'microphone':headset})
  self.assertIn(result['state'],('waiting','active'))
  h.wait_for(lambda:routed('headset_source','phone_sink') and routed('phone_source','headset_sink'))
  self.assertEqual(audio_state()['headphone_profile'],'headset-head-unit')
  success('audio_mute',{'muted':True})
  self.assertEqual(len(audio_state()['modules']),1)
  loads=len(audio_state().get('loads',[]))
  # Losing/recreating the headset keeps the address and mute selection.
  backend_change(headphone_connected=False)
  h.wait_for(lambda:snapshot().get('call_audio',{}).get('state')=='waiting')
  self.assertFalse(audio_state()['modules'])
  backend_change(headphone_connected=True,headphone_profile='a2dp-sink',headphone_epoch=50)
  h.wait_for(lambda:snapshot().get('call_audio',{}).get('state')=='active')
  self.assertEqual(len(audio_state()['modules']),1)
  self.assertTrue(snapshot()['call_audio']['muted'])
  self.assertFalse(any(m['sink']=='phone_sink' for m in audio_state()['loads'][loads:]))
  # A codec/profile switch recreates endpoints under the same device address.
  old_playback=audio_state()['modules'][0]['index']
  backend_change(headphone_profile='a2dp-sink',headphone_epoch=60)
  h.wait_for(lambda: any(m['index']!=old_playback for m in audio_state()['modules']))
  self.assertTrue(snapshot()['call_audio']['muted']); self.assertEqual(len(audio_state()['modules']),1)
  success('audio_mute',{'muted':False})
  self.assertTrue(routed('headset_source','phone_sink'))
  # Stop restores both the phone and the headphone profiles Unity changed.
  success('audio_stop'); self.assertEqual(audio_state()['headphone_profile'],'a2dp-sink')
  # Stop while disconnected defers only profile restoration, never the call.
  success('audio_start',{'address':'AA:BB:CC:DD:EE:FF','output':headset,'microphone':headset})
  backend_change(headphone_connected=False)
  h.wait_for(lambda:snapshot().get('call_audio',{}).get('state')=='waiting')
  result=success('audio_stop'); self.assertTrue(result['restoration_pending'])
  self.assertFalse(audio_state()['modules']); self.assertTrue((self.state/'call-audio.json').exists())
  backend_change(headphone_connected=True)
  h.wait_for(lambda:audio_state()['headphone_profile']=='a2dp-sink' and not (self.state/'call-audio.json').exists())
  # A missing explicit microphone waits without falling back to another input.
  success('audio_start',{'address':'AA:BB:CC:DD:EE:FF','output':'laptop_speaker','microphone':'laptop_microphone'})
  result=success('audio_select',{'output':'laptop_speaker','microphone':'missing_microphone'})
  self.assertEqual(result['state'],'waiting'); self.assertEqual(len(audio_state()['modules']),1)
  success('audio_select',{'output':'laptop_speaker','microphone':'laptop_microphone'})
  # Reject a feedback route through the phone or a monitor source.
  result=success('audio_select',{'output':'phone_sink','microphone':'laptop_speaker.monitor'})
  self.assertEqual(result['state'],'waiting'); self.assertFalse(audio_state()['modules'])
  success('audio_select',{'output':'laptop_speaker','microphone':'laptop_microphone'})
  # A local link failure is surfaced and retried once the device recovers.
  backend_change(fail_source='usb_microphone')
  result=success('audio_select',{'output':'laptop_speaker','microphone':'usb_microphone'})
  self.assertEqual(result['state'],'waiting'); self.assertFalse(audio_state()['modules'])
  backend_change(fail_source=None)
  h.wait_for(lambda:routed('usb_microphone','phone_sink'))
  peer.event('sms',{'message_id':'cd'*16,'state':'sent','revision':1})
  peer.event('sms',{'message_id':'cd'*16,'state':'delivered','revision':2})
  peer.event('sms',{'revision':3})
  h.wait_for(lambda: snapshot()['sms']['revision']==3)
  self.assertEqual(snapshot()['sms']['reports']['cd'*16]['state'],'delivered')
  state['calls']=[]; peer.event('call_control',state)
  h.wait_for(lambda: not json.loads(self.backend.read_text())['modules'] and json.loads(self.backend.read_text())['profile']=='off')
  self.assertEqual(json.loads(self.backend.read_text())['profile'],'off')
  with self.assertRaises(GLib.Error): self.call(self.other,self.path,'org.unityconnect.Telephony1','GetSnapshot')
  # Cancellation during loopback setup releases the microphone and restores the profile.
  state['calls']=[call]; peer.event('call_control',state)
  h.wait_for(lambda: snapshot()['call_control']['calls'])
  backend_change(delay_load=True)
  op=execute('audio_start',{'address':'AA:BB:CC:DD:EE:FF'})
  h.wait_for(lambda: len(json.loads(self.backend.read_text())['modules'])==1)
  self.call(self.bus,op,'org.unityconnect.Operation1','Cancel')
  self.assertEqual(complete(op)['status'],'cancelled')
  h.wait_for(lambda: not json.loads(self.backend.read_text())['modules'] and json.loads(self.backend.read_text())['profile']=='off')
  backend_change(delay_load=False)
  # A killed daemon recovers only its own loopbacks from its private journal.
  op=execute('audio_start',{'address':'AA:BB:CC:DD:EE:FF','output':headset,'microphone':headset}); self.assertEqual(complete(op)['status'],'completed')
  self.daemon.kill(); self.daemon.wait(timeout=5); self.log.close(); peer.close()
  # Model a module created just before its ID could be saved to the journal,
  # plus an unrelated application's loopback that cleanup must leave alone.
  backend=audio_state(); extra=dict(backend['modules'][0]); extra['index']=backend['next']; backend['next']+=1
  unrelated={**extra,'index':backend['next'],'tag':'other_application',
             'argument':extra['argument'].replace(extra['tag'],'other_application')}; backend['next']+=1
  backend['modules'] += [extra,unrelated]; backend_change(**backend)
  h.wait_for(lambda: 'org.unityconnect' not in self.bus.call_sync('org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','ListNames',None,None,Gio.DBusCallFlags.NONE,1000,None).unpack()[0])
  type(self).start_daemon()
  h.wait_for(lambda: audio_state()['modules']==[unrelated] and audio_state()['profile']=='off')
  self.assertEqual(audio_state()['headphone_profile'],'a2dp-sink')
  backend_change(modules=[])
  peer=h.Peer(self.port,info['fingerprint'],secret)
  name=self.call(self.bus,h.MANAGER,'org.unityconnect.Manager1','RequestAccess','(sas)',('Telephony test',['read','control']))
  self.cli('authorize',name)
  peer.event('call_control',state); h.wait_for(lambda: snapshot().get('call_control')==state)
  op=execute('audio_start',{'address':'AA:BB:CC:DD:EE:FF'}); self.assertEqual(complete(op)['status'],'completed')
  self.cli('feature',h.DEVICE,'call_control','--disable')
  self.assertFalse(json.loads(self.backend.read_text())['modules'])
  with self.assertRaises(GLib.Error): execute('dial',{'number':'12345'})
  peer.close()

if __name__ == '__main__': unittest.main(verbosity=2)
