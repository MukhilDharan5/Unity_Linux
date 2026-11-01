#!/usr/bin/env python3
"""Exercise the actual GTK display choices and outgoing extended-display request."""
import importlib.util,json,sys,traceback
from pathlib import Path
import gi
gi.require_version('Gtk','4.0');gi.require_version('Adw','1')
from gi.repository import Gio,GLib
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('unity',ROOT/'unity-gtk/unity.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class Client:
 def __init__(self,changed,error): self.commands=[]; self.operations={}
 def manager(self,*args,**kwargs): pass
 def call(self,path,interface,method,signature='()',args=(),done=None,failed=None):
  if method=='Execute':
   feature,action,data=args; self.commands.append((feature,action,json.loads(data)))
   op=f'/org/unityconnect/operations/o_{len(self.commands):032x}'
   self.operations[op]=json.dumps({'feature':feature,'status':'running','progress':0})
   GLib.idle_add(lambda:(done(op),False)[1])
  elif method=='GetState' and path in self.operations: GLib.idle_add(lambda:(done(self.operations[path]),False)[1])
failures=[]
class App(module.App):
 def __init__(self): super().__init__();self.set_application_id('org.unityconnect.Unity.ExtendedDisplayTest');self.set_flags(Gio.ApplicationFlags.NON_UNIQUE)
 def do_activate(self):
  self.win=module.Window(self,client_factory=Client);win=self.win
  win.path='/org/unityconnect/devices/d_'+'a'*32
  win.devices[win.path]={'Connected':True,'Name':'Test phone'};win.authorized=True
  win.capabilities={'screen':{'status':'available','reason':''}}
  win.render_capabilities();win.stack.set_visible_child_name('remote');win.present()
  GLib.timeout_add(300,self.check)
 def check(self):
  try:
   win=self.win
   win.screen_blank_switch.set_active(True);win.screen_extend_switch.set_active(True)
   assert not win.screen_blank_switch.get_active()
   win.screen_blank_switch.set_active(True);assert not win.screen_extend_switch.get_active()
   win.screen_extend_switch.set_active(True)
   win.screen_control_switch.set_active(False)
   assert win.screen_extend_switch.get_active(),'A second monitor does not require phone mouse/keyboard control'
   assert not win.client.commands,'Selecting a display option cannot start capture'
   win.start_screen('desktop')
   assert win.client.commands[-1]==('screen','start',{'source':'desktop','control':False,'blank_display':False,'extend_display':True})
   win.render_screen({'state':'streaming','source':'desktop','extended_display':True,'display_on':True,'display_available':False})
   assert not win.laptop_display_button.get_sensitive()
   assert not win.screen_blank_switch.get_sensitive() and not win.screen_extend_switch.get_sensitive()
   assert 'separate monitor' in win.screen_status.get_subtitle()
   assert len(win.client.commands)==1,'Rendering state cannot change the monitor layout'
   win.render_screen({});win.screen_control_switch.set_active(True);win.screen_blank_switch.set_active(True)
   win.start_screen('desktop')
   assert win.client.commands[-1]==('screen','start',{'source':'desktop','control':True,'blank_display':True,'extend_display':False})
   print('GTK extended/main display selection, optional input, panel protection and quiet state checks passed',flush=True)
  except Exception: failures.append(traceback.format_exc())
  self.quit();return False
app=App();app.run([])
if failures:print('\n'.join(failures));sys.exit(1)
