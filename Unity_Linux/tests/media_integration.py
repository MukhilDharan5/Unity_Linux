#!/usr/bin/env python3
"""Actual daemon, TLS and MPRIS: two sources, targeted controls and loop/replay guards."""
import json
import socket
import time
import unittest
from PIL import Image
from gi.repository import GLib
import integration as h
from fake_mpris import FakePlayer
h.CAPS.append({'id':'desktop_media','status':'available','reason':''})
h.CAPS.append({'id':'media_artwork','status':'available','reason':''})
class MediaIntegration(h.Integration):
 def test_end_to_end(self):
  a=FakePlayer('native_a');b=FakePlayer('native_b','Second track',False)
  bridge=FakePlayer('unity.phone','Must not reflect')
  disguised=FakePlayer('remote_alias','Must not reflect',desktop_entry='org.kde.kdeconnect')
  self.addCleanup(a.close);self.addCleanup(bridge.close);self.addCleanup(disguised.close)
  info=json.loads(self.cli('discover').stdout)
  peer=h.Peer(self.port,info['fingerprint']);self.cli('pair',h.DEVICE)
  secret=peer.read()['secret'];peer.close();peer=h.Peer(self.port,info['fingerprint'],secret)
  self.addCleanup(peer.close)
  path='/org/unityconnect/devices/d_'+h.DEVICE
  name=self.call(self.bus,h.MANAGER,'org.unityconnect.Manager1','RequestAccess','(sas)',('Media test',['read','control']))
  self.cli('authorize',name)
  def snapshot():return json.loads(self.cli('status',h.DEVICE).stdout)['state']
  def wire(predicate):
   while True:
    m=peer.read()
    if m['type']=='ping':peer.send({'type':'pong'});continue
    self.assertNotEqual(m['type'],'command','Metadata must not emit playback commands')
    if predicate(m):return m
  def media_event():return wire(lambda m:m['type']=='event' and m['feature']=='desktop_media')['data']
  first=media_event();self.assertEqual(first['origin'],'desktop');self.assertEqual(len(first['players']),2)
  h.wait_for(lambda:len(snapshot().get('desktop_media',{}).get('players',[]))==2)
  players={p['package']:p for p in first['players']};bp=players[b.name];ap=players[a.name]
  # Missing covers retry without pausing, and a transient missing duration
  # cannot clear the timeline of the same track.
  cover=self.dir/'late-cover.jpg';a.art_uri=cover.as_uri();a.omit_duration=True;a.position=87000000
  h.wait_for(lambda:next(p for p in snapshot()['desktop_media']['players'] if p['package']==a.name)['position']>=87000)
  current=next(p for p in snapshot()['desktop_media']['players'] if p['package']==a.name)
  self.assertEqual(current['duration'],180000);self.assertFalse(a.calls)
  time.sleep(.3);Image.new('RGB',(1600,1200),(30,100,200)).save(cover)
  h.wait_for(lambda:next(p for p in snapshot()['desktop_media']['players'] if p['package']==a.name).get('artwork'),timeout=8)
  full=wire(lambda m:m['type']=='event' and m['feature']=='desktop_media' and m['data'].get('kind')=='artwork')['data']
  self.assertEqual(full['artwork']['width'],1024)
  self.assertEqual(next(p for p in snapshot()['desktop_media']['players'] if p['package']==a.name)['artwork']['width'],1024)
  self.assertFalse(a.calls,'Artwork recovery must not issue play/pause commands')
  a.omit_duration=False
  self.assertNotEqual(bp['session'],ap['session']);self.assertEqual(ap['position'],42000)
  phone={'origin':'phone','session':'ab'*16,'track_id':'phone-track-1','title':'Phone track','artist':'Phone artist','playing':True,'active':True,'actions':['play','pause','next'],'duration':100000,'position':0,'artwork_id':full['artwork_id'],'artwork':full['artwork']['preview']}
  # Bulk covers can beat metadata arriving over the independent control link.
  peer.event('media',dict(full,origin='phone',session=phone['session'],track_id=phone['track_id']))
  peer.event('media',dict(phone,players=[phone]));h.wait_for(lambda:snapshot().get('media',{}).get('players',[{}])[0].get('title')=='Phone track')
  self.assertEqual(snapshot()['media']['players'][0]['artwork']['width'],1024)
  phone['position']=12000;peer.event('media',dict(phone,players=[phone]))
  h.wait_for(lambda:snapshot()['media']['position']==12000)
  self.assertEqual(snapshot()['media']['artwork']['width'],1024)
  # Echoes cannot overwrite either native source or invoke a player.
  peer.event('desktop_media',first);peer.event('media',dict(phone,origin='desktop'))
  time.sleep(.2);self.assertEqual(snapshot()['media']['origin'],'phone');self.assertEqual(a.calls+b.calls,[])
  def request(action,data=None,id=None):
   id=id or ('%032x' % (len(a.calls)+len(b.calls)+100))
   peer.send({'type':'request','id':id,'feature':'desktop_media','action':action,'data':data or {'session':bp['session']}})
   return wire(lambda m:m['type']=='result' and m['id']==id)
  rid='cd'*16
  self.assertTrue(request('next',id=rid)['ok']);self.assertTrue(request('next',id=rid)['ok'])
  self.assertEqual(b.calls,[('Next',())]);self.assertEqual(a.calls,[])
  self.assertFalse(request('pause',id=rid)['ok'],'Changed replay must be rejected')
  self.assertFalse(request('next',{'session':'ef'*16},id='aa'*16)['ok']);self.assertEqual(len(b.calls),1)
  self.assertFalse(request('seek',{'session':bp['session'],'track_id':'/stale','position':12000},id='11'*16)['ok'])
  self.assertTrue(request('seek',{'session':bp['session'],'track_id':bp['track_id'],'position':12000},id='12'*16)['ok'])
  self.assertEqual(b.calls[-1],('SetPosition',('/test/track/1',12000000)))
  # Public GTK/CLI API executes laptop actions locally, never over phone Command.
  op=self.call(self.bus,path,'org.unityconnect.Device1','Execute','(sss)',('desktop_media','pause',json.dumps({'session':ap['session']})))
  result=h.wait_for(lambda:(lambda s:s if s['status']!='running' else None)(json.loads(self.call(self.bus,op,'org.unityconnect.Operation1','GetState'))))
  self.assertEqual(result['status'],'completed');self.assertEqual(a.calls,[('Pause',())]);h.wait_for(lambda:not snapshot()['desktop_media']['players'][0]['playing'])
  b.can_next=False
  self.assertFalse(request('next',id='13'*16)['ok']);self.assertEqual(len(b.calls),2)
  # Reusing a bus name after a restart must not reuse a session ID.
  b.close();b=FakePlayer('native_b','Restarted track',False);self.addCleanup(b.close)
  h.wait_for(lambda:any(p['title']=='Restarted track' for p in snapshot()['desktop_media']['players']))
  self.assertFalse(request('play',id='14'*16)['ok']);self.assertEqual(b.calls,[])
  # Revocation while a slow MPRIS property query is in flight blocks dispatch.
  fresh=next(p for p in snapshot()['desktop_media']['players'] if p['title']=='Restarted track')
  b.delay_control=True;late='16'*16
  peer.send({'type':'request','id':late,'feature':'desktop_media','action':'play','data':{'session':fresh['session']}})
  h.wait_for(lambda:b.waiting_for_control)
  self.cli('feature',h.DEVICE,'media','--disable')
  self.assertFalse(wire(lambda m:m['type']=='result' and m['id']==late)['ok'])
  self.assertEqual(b.calls,[],'Permission revoked before dispatch must block playback')
  h.wait_for(lambda:'desktop_media' not in snapshot())
  self.assertFalse(request('next',id='15'*16)['ok'])
  self.cli('feature',h.DEVICE,'media')
  h.wait_for(lambda:len(snapshot().get('desktop_media',{}).get('players',[]))==2)
  # A paused, unchanged snapshot settles instead of bouncing across the wire.
  peer.sock.settimeout(.1)
  while True:
   try: peer.read()
   except socket.timeout:break
  peer.sock.settimeout(.25)
  with self.assertRaises(socket.timeout):peer.read()
  self.assertEqual(b.calls,[])
  peer.close()
if __name__=='__main__':unittest.main(verbosity=2)
