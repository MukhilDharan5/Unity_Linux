#!/usr/bin/env python3
"""Production GTK player selection, commands and stale seek cancellation."""
import importlib.util
import json
import base64
import io
from PIL import Image
from pathlib import Path
import sys
import traceback
import gi
gi.require_version('Gtk','4.0');gi.require_version('Adw','1')
from gi.repository import Gio,Gtk,GLib,Graphene
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('unity',ROOT/'unity-gtk/unity.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class Client:
 def __init__(self,changed,error):self.commands=[];self.operations={}
 def manager(self,*args,**kwargs):pass
 def call(self,path,interface,method,signature='()',args=(),done=None,failed=None):
  if method=='Execute':
   feature,action,payload=args;self.commands.append((feature,action,json.loads(payload)))
   op=f'/org/unityconnect/operations/o_{len(self.commands):032x}'
   self.operations[op]=json.dumps({'feature':feature,'status':'completed','result':{'state':'submitted'},'progress':100})
   GLib.idle_add(lambda:(done(op),False)[1])
  elif method=='GetState' and path in self.operations:GLib.idle_add(lambda:(done(self.operations[path]),False)[1])
def player(origin,session,title):return {'origin':origin,'session':session,'title':title,'app_name':'Native player','artist':'Test artist','album':'Test album','active':True,'playing':False,'position':12000,'duration':180000,'track_id':'/track/1','actions':['play','pause','next','previous','seek']}
phone=player('phone','ab'*16,'Phone track');phone2=player('phone','cd'*16,'Second phone track');laptop=player('desktop','ef'*16,'Laptop track');laptop2=player('desktop','12'*16,'Second laptop track')
state={'sound':{'origin':'phone','available':True,'volume':60,'muted':False,'name':'Phone media','error':''},'desktop_sound':{'origin':'desktop','available':True,'volume':50,'muted':False,'output':'ef'*16,'name':'Laptop speakers','error':''},'media':dict(phone,players=[phone,phone2]),'desktop_media':{'origin':'desktop','players':[laptop,laptop2]}}
failures=[];win=None
class App(module.App):
 def __init__(self):super().__init__();self.set_application_id('org.unityconnect.Unity.MediaTest');self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)
 def do_activate(self):
  global win
  win=module.Window(self,client_factory=Client);win.path='/org/unityconnect/devices/d_'+'a'*32
  win.devices[win.path]={'Connected':True,'Name':'Test phone'};win.authorized=True
  win.capabilities={feature:{'status':'available','reason':''} for feature in ['media','desktop_media','sound','audio']}
  win.render_capabilities();win.render({'connected':True,'state':state});win.stack.set_visible_child_name('media');win.set_default_size(1140,900);win.present()
  GLib.timeout_add(250,checks)
app=App()
def capture(name):
 win.get_child().allocate(win.get_width(),win.get_height(),-1,None)
 paintable=Gtk.WidgetPaintable.new(win);snapshot=Gtk.Snapshot();paintable.snapshot(snapshot,win.get_width(),win.get_height())
 node=snapshot.to_node()
 if node is None:
  snapshot=Gtk.Snapshot();win.snapshot_child(win.get_child(),snapshot);node=snapshot.to_node()
 texture=win.get_renderer().render_texture(node,Graphene.Rect().init(0,0,win.get_width(),win.get_height()));texture.save_to_png('/tmp/'+name)
def checks():
 try:
  assert win.media_picker.get_model().get_n_items()==4
  output=io.BytesIO();Image.new('RGB',(1024,768),(30,100,200)).save(output,format='JPEG',quality=92)
  cover={'mime':'image/jpeg','width':1024,'height':768,'data':base64.b64encode(output.getvalue()).decode()}
  assert module.image_texture(cover,media=True).get_width()==1024
  assert module.image_texture(cover) is None,'Gallery preview bounds remain separate from media artwork'
  laptop['artwork']=cover;win.render_media_sources(state)
  count=len(win.client.commands)
  win.media_picker.set_selected(2);assert win.media_title.get_label()=='Laptop track';assert len(win.client.commands)==count
  win.media_next.emit('clicked');assert win.client.commands[-1]==('desktop_media','next',{'session':laptop['session']})
  laptop['position']=33000;win.render_media_sources(state)
  assert win.media_state['session']==laptop['session'];assert win.media_seek.get_value()==33000
  assert len(win.client.commands)==count+1,'Rendering metadata cannot dispatch controls'
  # A partial update of the same track cannot erase its known timeline.
  partial=dict(laptop,duration=0,playing=True)
  win.render_media(partial)
  assert win.media_seek.get_visible() and win.media_state['duration']==180000
  win.media_sample_at-=2;win.tick_media()
  assert win.media_seek.get_value()>=35000
  assert len(win.client.commands)==count+1,'The progress clock cannot issue playback commands'
  win.render_media(dict(partial,track_id='/track/new',title='New stream'))
  assert not win.media_seek.get_visible() and win.media_waiting.get_visible()
  win.render_media_sources(state)
  GLib.timeout_add(100,after_wide)
 except Exception:
  failures.append(traceback.format_exc());app.quit()
 return False
def after_wide():
 try:
  capture('unity-media-both-wide.png')
  win.seek_media(win.media_seek,None,60000)
  win.media_picker.set_selected(0)
  assert win.pending_seek is None,'Switching players must cancel pending seek'
  win.media_toggle.emit('clicked');assert win.client.commands[-1]==('media','play',{'session':phone['session']})
  win.set_default_size(420,740)
  GLib.timeout_add(350,finish)
 except Exception:
  failures.append(traceback.format_exc());app.quit()
 return False
def finish():
 try:
  assert len(win.client.commands)==2,'Old seek must not target newly selected player'
  capture('unity-media-both-phone.png')
  # Old companions still use the primary phone metadata without session IDs.
  old={k:v for k,v in phone.items() if k not in ['origin','session']};win.render_media_sources({'media':old})
  win.media_next.emit('clicked');assert win.client.commands[-1]==('media','next',{})
  win.render_media_sources({});assert not win.media_next.get_sensitive()
  count=len(win.client.commands)
  state['sound']['volume']=0;win.render_sound(state);win.render_audio_route({'state':'streaming'})
  assert 'volume is 0%' in win.audio_route.get_subtitle()
  state['sound']['muted']=True;win.render_sound(state);win.render_audio_route({'state':'streaming'})
  assert 'media is muted' in win.audio_route.get_subtitle()
  state['sound'].update(volume=60,muted=False);win.render_sound(state)
  assert len(win.client.commands)==count,'Volume warnings cannot issue setters'
  win.render_sound(state)
  assert win.sound_widgets['desktop']['scale'].get_value()==50
  assert win.sound_widgets['phone']['scale'].get_value()==60
  for value in [10,30,40]:win.change_sound(win.sound_widgets['desktop']['scale'],None,value,'desktop')
  assert len(win.client.commands)==count
  GLib.timeout_add(250,sound_checks)
 except Exception:
  failures.append(traceback.format_exc());app.quit()
 return False
def sound_checks():
 try:
  assert win.client.commands[-1]==('sound','volume',{'source':'desktop','volume':40,'output':'ef'*16})
  count=len(win.client.commands)
  win.sound_widgets['phone']['mute'].emit('clicked')
  assert win.client.commands[-1]==('sound','mute',{'source':'phone','muted':True})
  win.change_sound(win.sound_widgets['desktop']['scale'],None,10,'desktop')
  state['desktop_sound'].update(output='cd'*16,volume=88)
  win.render_sound(state)
  assert not win.sound_pending,'Switching output must cancel a pending volume change'
  assert win.sound_widgets['desktop']['scale'].get_value()==88
  assert len(win.client.commands)==count+1,'External volume metadata cannot dispatch setters'
  win.change_sound(win.sound_widgets['phone']['scale'],None,10,'phone')
  win.capabilities['sound']['status']='disabled';win.render_sound(state)
  assert not win.sound_pending and not win.sound_widgets['phone']['scale'].get_sensitive()
  GLib.timeout_add(250,sound_finish)
 except Exception:
  failures.append(traceback.format_exc());app.quit()
 return False
def sound_finish():
 try:
  assert len(win.client.commands)==5,'Cancelled or revoked volume gestures must stay cancelled'
  win.capabilities['find']={'status':'available','reason':''}
  win.render_find_phone({'state':'ringing'});assert win.find_button.get_label()=='Stop'
  win.find_button.emit('clicked');assert win.client.commands[-1]==('find','stop',{})
  win.render_find_phone({});assert win.find_button.get_label()=='Ring'
  win.find_button.emit('clicked');assert win.client.commands[-1]==('find','ring',{})
  print('GTK media and device-volume source routing, quiet updates, debounce and stale gesture checks passed',flush=True)
 except Exception:failures.append(traceback.format_exc())
 app.quit();return False
app.run([])
if failures:print('\n'.join(failures));sys.exit(1)
