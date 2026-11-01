#!/usr/bin/env python3
"""Real phone events -> desktop MPRIS, targeted controls, artwork and lifecycle."""
import base64
import hashlib
import io
import json
import unittest
from pathlib import Path
from urllib.parse import unquote, urlsplit
from PIL import Image
from gi.repository import Gio, GLib
import integration as h

h.CAPS.extend({'id': f, 'status': 'available', 'reason': ''} for f in ('media_artwork', 'desktop_media', 'sound'))
PATH = '/org/mpris/MediaPlayer2'
PLAYER = 'org.mpris.MediaPlayer2.Player'
PROPERTIES = 'org.freedesktop.DBus.Properties'
PREFIX = 'org.mpris.MediaPlayer2.unityconnect.d_' + h.DEVICE + '.s_'

class PhoneMedia(h.Integration):
 def test_end_to_end(self):
  info=json.loads(self.cli('discover').stdout)
  pending=h.Peer(self.port,info['fingerprint']);self.cli('pair',h.DEVICE)
  secret=pending.read()['secret'];pending.close();peer=h.Peer(self.port,info['fingerprint'],secret)
  self.addCleanup(peer.close)
  def names():
   return [n for n in self.bus.call_sync('org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','ListNames',None,None,Gio.DBusCallFlags.NONE,2000,None).unpack()[0] if n.startswith(PREFIX)]
  def call(name,interface,method,signature='()',args=()):
   result=self.bus.call_sync(name,PATH,interface,method,GLib.Variant(signature,args),None,Gio.DBusCallFlags.NONE,3000,None).unpack()
   return result[0] if len(result)==1 else result
  def props(name):return call(name,PROPERTIES,'GetAll','(s)',(PLAYER,))
  def command(feature,action,session=None):
   while True:
    m=peer.read()
    if m['type']=='ping':peer.send({'type':'pong'})
    if m['type']=='event' and m['feature']=='desktop_media':
     self.assertEqual(m['data'].get('players',[]),[],'Exported phone media must not echo as native laptop playback')
    if m['type']=='command':break
   self.assertEqual((m['feature'],m['action']),(feature,action))
   if session:self.assertEqual(m['data']['session'],session)
   peer.send({'type':'result','id':m['id'],'ok':True,'error':'','data':{'state':'submitted'}})
   return m['data']
  one={'origin':'phone','session':'ab'*16,'track_id':'track-one','package':'phone.player','app_name':'Phone Player','title':'Phone song','artist':'Artist','album':'Album','playing':True,'active':True,'duration':180000,'position':12000,'speed':1.0,'actions':['play','pause','next','previous','seek']}
  two=dict(one,session='cd'*16,track_id='track-two',title='Second phone song',playing=False,position=45000)
  def publish(players):peer.event('media',dict(players[0] if players else one,active=bool(players),playing=bool(players and players[0]['playing']),players=players))
  publish([one,two]);h.wait_for(lambda:len(names())==2)
  first,second=PREFIX+one['session'],PREFIX+two['session']
  p=props(first);self.assertEqual(p['PlaybackStatus'],'Playing');self.assertTrue(p['CanSeek'])
  self.assertEqual(p['Metadata']['xesam:title'],'Phone song');self.assertEqual(p['Metadata']['mpris:length'],180000000)
  self.assertGreaterEqual(p['Position'],12000000);self.assertEqual(props(second)['PlaybackStatus'],'Paused')
  # A late high-quality cover reaches the native desktop Metadata, not just Unity's page.
  out=io.BytesIO();Image.new('RGB',(1024,768),(20,130,210)).save(out,format='JPEG',quality=92)
  data=out.getvalue();art_id=hashlib.sha256(data).hexdigest()
  one['artwork_id']=art_id;publish([one,two])
  changes=[]
  subscription=self.bus.signal_subscribe(first,PROPERTIES,'PropertiesChanged',PATH,None,Gio.DBusSignalFlags.NONE,lambda *args:changes.append(args[-1]),None)
  peer.event('media',{'kind':'artwork','origin':'phone','session':one['session'],'track_id':one['track_id'],'artwork_id':art_id,'artwork':{'mime':'image/jpeg','width':1024,'height':768,'data':base64.b64encode(data).decode()}})
  cover=h.wait_for(lambda:props(first)['Metadata'].get('mpris:artUrl'))
  art_file=Path(unquote(urlsplit(cover).path))
  with Image.open(art_file) as image:self.assertEqual(image.size,(1024,768))
  self.assertEqual(art_file.stat().st_mode & 0o777,0o600)
  h.wait_for(lambda:bool(changes));self.bus.signal_unsubscribe(subscription)
  call(first,PLAYER,'PlayPause');command('media','pause',one['session'])
  call(second,PLAYER,'Next');command('media','next',two['session'])
  track=props(second)['Metadata']['mpris:trackid']
  call(second,PLAYER,'SetPosition','(ox)',(track,12345000))
  seek=command('media','seek',two['session']);self.assertEqual(seek['position'],12345);self.assertEqual(seek['track_id'],'track-two')
  call(second,PLAYER,'Seek','(x)',(-5000000,));self.assertEqual(command('media','seek',two['session'])['position'],40000)
  # Stale track seeks are ignored rather than sent to a new track.
  call(second,PLAYER,'SetPosition','(ox)',('/stale/track',12000))
  call(second,PLAYER,'Play');command('media','play',two['session'])
  peer.event('sound',{'origin':'phone','available':True,'output':'ef'*16,'name':'Phone speaker','volume':69,'muted':False,'fixed':False,'error':''})
  h.wait_for(lambda:props(first)['Volume']==.69)
  call(first,PROPERTIES,'Set','(ssv)',(PLAYER,'Volume',GLib.Variant('d',.4)))
  self.assertEqual(command('sound','volume'),{'source':'phone','volume':40})
  one['playing']=False;publish([one,two]);h.wait_for(lambda:props(first)['PlaybackStatus']=='Paused')
  self.cli('feature',h.DEVICE,'media','--disable');h.wait_for(lambda:not names());h.wait_for(lambda:not art_file.exists())
  with self.assertRaises(GLib.Error):call(first,PLAYER,'Next')
  self.cli('feature',h.DEVICE,'media');self.assertEqual(names(),[])
  publish([two]);h.wait_for(lambda:names()==[second])
  publish([]);h.wait_for(lambda:not names())
  publish([one]);h.wait_for(lambda:len(names())==1)
  peer.close();h.wait_for(lambda:not names())

if __name__=='__main__':unittest.main(verbosity=2)
