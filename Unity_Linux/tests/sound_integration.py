#!/usr/bin/env python3
"""Real TLS and D-Bus with isolated output volume, origin and replay guards."""
import fcntl,json,os,socket,subprocess,time,unittest
from gi.repository import Gio,GLib
import integration as h
h.CAPS.append({'id':'sound','status':'available','reason':''})
class SoundIntegration(h.Integration):
 @classmethod
 def start_daemon(cls):
  cls.backend=cls.dir/'sound.json'
  if not cls.backend.exists():cls.backend.write_text(json.dumps({'default':'laptop','sets':[],'sinks':{'1':{'name':'laptop','description':'Laptop speakers','volume':50,'muted':False},'2':{'name':'headphones','description':'Bluetooth headphones','volume':70,'muted':False}}}))
  commands=cls.dir/'commands';commands.mkdir(exist_ok=True)
  if not (commands/'pactl').exists():(commands/'pactl').symlink_to(h.ROOT/'tests/fixtures/sound_backend.py')
  env=dict(os.environ,PATH=str(commands)+':'+os.environ['PATH'],UNITY_TEST_SOUND=str(cls.backend))
  cls.log=open(cls.dir/'daemon.log','a');cls.daemon=subprocess.Popen([cls.bin/'unityd','--state-dir',cls.state,'--bind','127.0.0.1','--port',str(cls.port)],env=env,stdout=cls.log,stderr=cls.log)
  probe=Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT|Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,None,None)
  h.wait_for(lambda:'org.unityconnect' in probe.call_sync('org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','ListNames',None,None,Gio.DBusCallFlags.NONE,1000,None).unpack()[0]);probe.close_sync(None)
  h.wait_for(lambda:cls.cli('diagnose',check=False).returncode==0)
  def ready():
   with socket.socket() as sock:return sock.connect_ex(('127.0.0.1',cls.port))==0
  h.wait_for(ready)
 def test_end_to_end(self):
  info=json.loads(self.cli('discover').stdout);peer=h.Peer(self.port,info['fingerprint']);self.cli('pair',h.DEVICE)
  secret=peer.read()['secret'];peer.close();peer=h.Peer(self.port,info['fingerprint'],secret);self.addCleanup(peer.close)
  path='/org/unityconnect/devices/d_'+h.DEVICE
  name=self.call(self.bus,h.MANAGER,'org.unityconnect.Manager1','RequestAccess','(sas)',('Sound test',['read','control']));self.cli('authorize',name)
  def state():return json.loads(self.cli('status',h.DEVICE).stdout)['state']
  def backend():return json.loads(self.backend.read_text())
  def change(**changes):
   with self.backend.with_suffix('.lock').open('w') as lock:
    fcntl.flock(lock,fcntl.LOCK_EX);s=backend();s.update(changes);temp=self.backend.with_suffix('.tmp');temp.write_text(json.dumps(s));temp.replace(self.backend)
  def wire(predicate):
   while True:
    m=peer.read()
    if m['type']=='ping':peer.send({'type':'pong'});continue
    if predicate(m):return m
    self.assertNotEqual(m['type'],'command','Volume snapshots must never cause commands')
  first=wire(lambda m:m['type']=='event' and m['feature']=='sound')['data']
  self.assertEqual((first['origin'],first['volume'],first['muted']),('desktop',50,False))
  def request(action,data,id):
   peer.send({'type':'request','id':id,'feature':'sound','action':action,'data':dict(source='desktop',output=first['output'],**data)})
   return wire(lambda m:m['type']=='result' and m['id']==id)
  r=request('volume',{'volume':25},'ab'*16);self.assertTrue(r['ok']);self.assertEqual(r['data']['sound']['volume'],25)
  self.assertEqual(backend()['sinks']['1']['volume'],25)
  self.assertTrue(request('volume',{'volume':25},'ab'*16)['ok']);self.assertEqual(len(backend()['sets']),1)
  s=backend();s['sinks']['1']['volume']=80;change(sinks=s['sinks'])
  h.wait_for(lambda:state()['desktop_sound']['volume']==80)
  self.assertTrue(request('volume',{'volume':25},'ab'*16)['ok']);self.assertEqual(backend()['sinks']['1']['volume'],80,'Replay cannot undo hardware volume changes')
  self.assertFalse(request('mute',{'muted':True},'ab'*16)['ok'])
  self.assertTrue(request('mute',{'muted':True},'cd'*16)['ok']);self.assertTrue(backend()['sinks']['1']['muted'])
  # Exact laptop output identities prevent retargeting after switching output.
  change(default='headphones');h.wait_for(lambda:state()['desktop_sound']['name']=='Bluetooth headphones')
  self.assertFalse(request('volume',{'volume':10},'de'*16)['ok']);self.assertEqual(backend()['sinks']['2']['volume'],70)
  target=state()['desktop_sound']['output']
  op=self.call(self.bus,path,'org.unityconnect.Device1','Execute','(sss)',('sound','volume',json.dumps({'source':'desktop','output':target,'volume':40})))
  result=h.wait_for(lambda:(lambda v:v if v['status']!='running' else None)(json.loads(self.call(self.bus,op,'org.unityconnect.Operation1','GetState'))))
  self.assertEqual(result['status'],'completed');self.assertEqual(backend()['sinks']['2']['volume'],40)
  self.assertEqual(backend()['sinks']['1']['volume'],80)
  phone={'origin':'phone','available':True,'volume':60,'muted':False,'name':'Phone media','error':''}
  peer.event('sound',phone);h.wait_for(lambda:state()['sound']['volume']==60)
  # Metadata echoed by a peer must not overwrite phone state or set either output.
  count=len(backend()['sets']);peer.event('sound',first);time.sleep(.15)
  self.assertEqual(state()['sound']['origin'],'phone');self.assertEqual(len(backend()['sets']),count)
  # A laptop UI command to the phone stays distinct from its own output control.
  op=self.call(self.bus,path,'org.unityconnect.Device1','Execute','(sss)',('sound','mute',json.dumps({'source':'phone','muted':True})))
  command=wire(lambda m:m['type']=='command')
  self.assertEqual((command['feature'],command['action'],command['data']),('sound','mute',{'source':'phone','muted':True}))
  peer.send({'type':'result','id':command['id'],'ok':True,'data':{},'error':''})
  h.wait_for(lambda:json.loads(self.call(self.bus,op,'org.unityconnect.Operation1','GetState'))['status']=='completed')
  self.assertEqual(len(backend()['sets']),count)
  for payload in [{'source':'phone','volume':101},{'source':'phone','volume':-1},{'source':'phone','volume':.5},{'source':'desktop','volume':20,'output':'1; shutdown'},{'source':'other','volume':20}]:
   with self.assertRaises(GLib.Error):self.call(self.bus,path,'org.unityconnect.Device1','Execute','(sss)',('sound','volume',json.dumps(payload)))
  self.cli('feature',h.DEVICE,'media','--disable');h.wait_for(lambda:'desktop_sound' not in state() and 'sound' not in state())
  self.assertFalse(request('mute',{'muted':False},'ef'*16)['ok'])
  peer.event('sound',phone);time.sleep(.1);self.assertNotIn('sound',state())
  self.cli('feature',h.DEVICE,'media');h.wait_for(lambda:state().get('desktop_sound',{}).get('available'))
  change(unavailable=True);h.wait_for(lambda:state().get('desktop_sound',{}).get('available') is False)
  peer.send({'type':'ping'});self.assertEqual(wire(lambda m:m['type']=='pong')['type'],'pong')
  change(unavailable=False);h.wait_for(lambda:state().get('desktop_sound',{}).get('available'))
  peer.close()
if __name__=='__main__':unittest.main(verbosity=2)
