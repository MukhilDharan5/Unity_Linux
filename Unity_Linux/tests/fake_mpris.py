"""Native player fixture on a private bus; it never plays real laptop audio."""
import os
from gi.repository import Gio, GLib
PATH='/org/mpris/MediaPlayer2'
PLAYER='org.mpris.MediaPlayer2.Player'
ROOT='org.mpris.MediaPlayer2'
XML='''<node><interface name="org.freedesktop.DBus.Properties"><method name="GetAll"><arg type="s" direction="in"/><arg type="a{sv}" direction="out"/></method><method name="Get"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/></method></interface>
<interface name="org.mpris.MediaPlayer2.Player"><method name="Play"/><method name="Pause"/><method name="Next"/><method name="Previous"/><method name="SetPosition"><arg type="o" direction="in"/><arg type="x" direction="in"/></method></interface></node>'''
class FakePlayer:
 def __init__(self,suffix,title='Laptop track',playing=True,desktop_entry='native.player'):
  self.name='org.mpris.MediaPlayer2.'+suffix
  self.bus=Gio.DBusConnection.new_for_address_sync(os.environ['DBUS_SESSION_BUS_ADDRESS'],Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT|Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,None,None)
  self.bus.call_sync('org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','RequestName',GLib.Variant('(su)',(self.name,0)),None,Gio.DBusCallFlags.NONE,5000,None)
  self.duration=180000000;self.position=42000000;self.art_uri='';self.omit_duration=False
  self.track='/test/track/1';self.title=title;self.playing=playing;self.can_next=True;self.calls=[];self.desktop_entry=desktop_entry;self.monitor=None;self.delay_control=False;self.waiting_for_control=False
  self.registrations=[self.bus.register_object(PATH,interface,self.method,None,None) for interface in Gio.DBusNodeInfo.new_for_xml(XML).interfaces]
 def method(self,bus,sender,path,interface,method,parameters,invocation):
  if method=='Get':
   invocation.return_value(GLib.Variant('(v)',(GLib.Variant('x',self.position),)));return
  if method=='GetAll':
   metadata={'xesam:title':GLib.Variant('s',self.title),'xesam:artist':GLib.Variant('as',['Laptop artist']),'xesam:album':GLib.Variant('s','Laptop album'),'mpris:length':GLib.Variant('x',0 if self.omit_duration else self.duration),'mpris:trackid':GLib.Variant('o',self.track)}
   if self.art_uri: metadata['mpris:artUrl']=GLib.Variant('s',self.art_uri)
   props={'Metadata':GLib.Variant('a{sv}',metadata),'PlaybackStatus':GLib.Variant('s','Playing' if self.playing else 'Paused'),'Position':GLib.Variant('x',self.position)}
   props.update({key:GLib.Variant('b',self.can_next if key=='CanGoNext' else True) for key in ['CanControl','CanPlay','CanPause','CanSeek','CanGoNext','CanGoPrevious']})
   if parameters.unpack()[0]==ROOT: props={'Identity':GLib.Variant('s',self.name.rsplit('.',1)[-1]),'DesktopEntry':GLib.Variant('s',self.desktop_entry)}
   if parameters.unpack()[0]==PLAYER and self.monitor is None:self.monitor=sender
   if parameters.unpack()[0]==PLAYER and self.delay_control and sender!=self.monitor:
    self.delay_control=False;self.waiting_for_control=True
    def reply():
     invocation.return_value(GLib.Variant('(a{sv})',(props,)));self.waiting_for_control=False;return False
    GLib.timeout_add(500,reply)
   else:invocation.return_value(GLib.Variant('(a{sv})',(props,)))
  else:
   self.calls.append((method,parameters.unpack()))
   if method=='Play':self.playing=True
   if method=='Pause':self.playing=False
   invocation.return_value(GLib.Variant('()',()))
 def close(self):
  for registration in self.registrations:self.bus.unregister_object(registration)
  self.bus.close_sync(None)
