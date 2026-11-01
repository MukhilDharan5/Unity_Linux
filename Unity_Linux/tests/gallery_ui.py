#!/usr/bin/env python3
"""Real mixed gallery widgets, thumbnail rendering and right-click actions."""
import base64
import importlib.util
import json
from pathlib import Path
import traceback
import tempfile
import gi
gi.require_version('Gtk','4.0');gi.require_version('Adw','1')
from gi.repository import Gio,Gtk,GLib,Graphene,Gdk
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('unity',ROOT/'unity-gtk/unity.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
preview={'mime':'image/jpeg','width':256,'height':192,'data':base64.b64encode((ROOT/'tests/fixtures/preview.jpg').read_bytes()).decode()}
items=[{'id':'ab'*16,'name':'Holiday video.mp4','kind':'video','duration':45000,'size':123456,'width':1920,'height':1080},
       {'id':'cd'*16,'name':'Holiday photo.jpg','kind':'image','size':12345,'width':1920,'height':1080}]
originals=tempfile.TemporaryDirectory(prefix='unity-gallery-drag-')
original=Path(originals.name)/'தமிழ் # original.mp4';original.write_bytes(b'original video bytes, not the thumbnail')
class Client:
 def __init__(self,changed,error):self.commands=[];self.operations={}
 def manager(self,*args,**kwargs):pass
 def call(self,path,interface,method,signature='()',args=(),done=None,failed=None):
  if method=='Execute':
   feature,action,payload=args;payload=json.loads(payload);self.commands.append((feature,action,payload))
   result={'items':items,'preview_supported':True,'video_supported':True,'clipboard_supported':True} if action=='list' else {'id':payload.get('id'),'preview':preview} if action=='preview' else {'state':'copied' if action=='copy' else 'transferred','received_path':str(original)}
   op=f'/org/unityconnect/operations/o_{len(self.commands):032x}'
   self.operations[op]=json.dumps({'feature':feature,'status':'completed','progress':100,'result':result})
   GLib.idle_add(lambda:(done(op),False)[1])
  elif method=='GetState' and path in self.operations:GLib.idle_add(lambda:(done(self.operations[path]),False)[1])
def descendants(widget):
 yield widget
 child=widget.get_first_child()
 while child:
  yield from descendants(child);child=child.get_next_sibling()
failures=[];win=None;attempts=0
class App(module.App):
 def __init__(self):super().__init__();self.set_application_id('org.unityconnect.Unity.GalleryTest');self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)
 def do_activate(self):
  global win
  win=module.Window(self,client_factory=Client);win.path='/org/unityconnect/devices/d_'+'a'*32
  win.devices[win.path]={'Connected':True,'Name':'Test phone'};win.authorized=True
  win.capabilities={f:{'status':'available','reason':''} for f in ('photos','files')}
  win.render({'connected':True,'state':{}});win.nav.select_row(win.pages['photos']);win.set_default_size(1000,760);win.present()
  GLib.timeout_add(200,checks)
app=App()
def checks():
 global attempts
 try:
  attempts+=1
  if any(not value['texture'] for value in win.photo_widgets.values()) or len(win.photo_widgets)!=2:
   assert attempts<30,'Gallery thumbnails failed to render';return True
  assert any(isinstance(w,Gtk.Label) and w.get_label()=='0:45' for w in descendants(win.photos))
  tile=win.photos.get_child_at_index(0).get_child()
  win.photo_menu(tile,items[0]['id'])
  menu=next(w for w in descendants(tile) if isinstance(w,Gtk.Popover))
  next(w for w in descendants(menu) if isinstance(w,Gtk.Button) and w.get_label()=='Copy to clipboard').emit('clicked')
  assert win.client.commands[-1]==('photos','copy',{'id':items[0]['id']})
  win.gallery_action(items[1]['id'],'get')
  assert win.client.commands[-1]==('photos','get',{'id':items[1]['id']})
  source=win.photo_widgets[items[0]['id']]['drag']
  assert source.get_actions()==Gdk.DragAction.COPY
  provider=source.emit('prepare',0.,0.)
  assert isinstance(provider,module.GalleryDragProvider)
  assert provider.payload is None,'A drag must await verification of the original'
  assert provider.ref_formats().contain_mime_type('text/uri-list')
  stream=Gio.MemoryOutputStream.new_resizable()
  def delivered(source,result,*_):
   try:
    assert source.write_mime_type_finish(result)
    stream.close(None)
    payload=stream.steal_as_bytes().get_data().decode()
    assert payload==original.as_uri()+'\r\n',payload
    assert '%23' in payload and '%20' in payload
    assert win.client.commands[-1]==('photos','get',{'id':items[0]['id']})
    GLib.timeout_add(100,capture)
   except Exception:failures.append(traceback.format_exc());app.quit()
  provider.write_mime_type_async('text/uri-list',stream,GLib.PRIORITY_DEFAULT,None,delivered,None)
 except Exception:failures.append(traceback.format_exc());app.quit()
 return False
def capture():
 try:
  win.get_child().allocate(win.get_width(),win.get_height(),-1,None)
  snapshot=Gtk.Snapshot();paintable=Gtk.WidgetPaintable.new(win);paintable.snapshot(snapshot,win.get_width(),win.get_height());node=snapshot.to_node()
  if node is None:
   snapshot=Gtk.Snapshot();win.snapshot_child(win.get_child(),snapshot);node=snapshot.to_node()
  assert node is not None
  texture=win.get_renderer().render_texture(node,Graphene.Rect().init(0,0,win.get_width(),win.get_height()));texture.save_to_png('/tmp/unity-gallery-videos.png')
  before=len(win.client.commands);win.authorized=False;win.gallery_action(items[0]['id'],'copy')
  assert win.prepare_gallery_drag(win.photo_widgets[items[0]['id']]['drag'],items[0]['id']) is None
  assert len(win.client.commands)==before,'Revoked access cannot download originals'
 except Exception:failures.append(traceback.format_exc())
 finally:app.quit()
 return False
app.run([])
originals.cleanup()
if failures:raise AssertionError('\n'.join(failures))
print('Gallery original-file drag delivery, thumbnails, actions and permission checks passed')
