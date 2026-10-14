#!/usr/bin/env python3
"""Actual installed GTK cold-start on an isolated virtual KDE desktop."""
import json
import os
import signal
import subprocess
import time
import unittest
from gi.repository import Gio, GLib
from launcher import ROOT, wait_for
import launcher as launcher_harness
import integration as harness
from fake_notifications import FakeNotifications
from fake_ofono import FakeOfono


class CallPopupIntegration(unittest.TestCase):
    start_daemon = launcher_harness.Launcher.start_daemon
    cli = launcher_harness.Launcher.cli
    def setUp(self):
        os.environ['DBUS_SYSTEM_BUS_ADDRESS'] = os.environ['DBUS_SESSION_BUS_ADDRESS']
        launcher_harness.Launcher.setUp(self)
        self.gui = self.dir / 'gui.json'
        script = self.prefix / 'share/unity/unity.py'
        source = (ROOT / 'unity-gtk/unity.py').read_text()
        # Instrument only this staged test copy. Production has no fixture hooks.
        source = source.replace("    raise SystemExit(App().run(sys.argv))", f'''    test_app = App()
    def report_test_ui():
        main = next((w for w in test_app.get_windows() if isinstance(w, Window)), None)
        if main:
            data = {{'pid': __import__('os').getpid(), 'main_visible': main.get_visible(),
                'popup_visible': bool(main.call_popup and main.call_popup.get_visible()),
                'popup_id': id(main.call_popup), 'updating': bool(main.call_popup_status and main.call_popup_status.get_visible()),
                'reconnecting': bool(main.call_control_state.get('reconnecting')),
                'page': main.stack.get_visible_child_name(),
                'call_ids': [c['id'] for c in main.call_control_state.get('calls', [])],
                'timer': main.call_popup_panel.timers[0][0].get_label() if main.call_popup_panel and main.call_popup_panel.timers else '',
                'approved': main.authorized, 'calls': len(main.call_control_state.get('calls', []))}}
            target = Path({str(self.gui)!r}); temporary = target.with_suffix('.tmp')
            temporary.write_text(json.dumps(data)); temporary.replace(target)
        return True
    GLib.timeout_add(100, report_test_ui)
    test_app.run(sys.argv)''')
        script.write_text(source); script.chmod(0o644)
        self.notices = FakeNotifications(); self.peer = None; self.client_pid = None; self.ofono = None
        subprocess.run(['python3',str(ROOT / 'scripts/setup-call-popup.py')],check=True)

    def tearDown(self):
        if self.peer: self.peer.close()
        if self.client_pid:
            try: os.kill(self.client_pid,signal.SIGTERM)
            except ProcessLookupError: pass
        self.notices.close(); launcher_harness.Launcher.tearDown(self)
        if self.ofono: self.ofono.close()

    def gui_state(self):
        try: return json.loads(self.gui.read_text())
        except (FileNotFoundError,json.JSONDecodeError): return {}

    def test_real_gtk_popup_opens_when_closed_and_stays_above_other_windows(self):
        info = json.loads(self.cli('discover').stdout)
        self.peer = harness.Peer(self.port,info['fingerprint']); self.cli('pair',harness.DEVICE)
        secret = self.peer.read()['secret']; self.peer.close()
        self.peer = harness.Peer(self.port,info['fingerprint'],secret)
        call = {'id':'ab'*16,'state':'ringing','number':'15550123','name':'Test caller','connect_time':0,'can_hold':True,'conference':False}
        state = {'backend':'android_telecom','calls':[call],'muted':False,'route':'phone','routes':['phone','speaker']}
        self.peer.event('call_control',state)
        current = wait_for(lambda:self.gui_state() if self.gui_state().get('popup_visible') else None)
        self.client_pid = current['pid']
        self.assertTrue(current['approved']); self.assertFalse(current['main_visible'])
        # KWin reports the actual compositor keepAbove flag, not widget intent.
        self.window = None
        self.call_bus_name('org.unityconnect.PopupTest')
        info = Gio.DBusNodeInfo.new_for_xml('<node><interface name="org.unityconnect.PopupTest"><method name="Report"><arg type="s" direction="in"/></method></interface></node>')
        def report(*args):
            self.window = json.loads(args[-2].unpack()[0]); args[-1].return_value(GLib.Variant('()',()))
        registration = self.bus.register_object('/PopupTest',info.interfaces[0],report,None,None)
        script = self.dir / 'inspect-popup.js'
        script.write_text('workspace.windowList().forEach(w => { if (w.caption === "Unity · Call controls") callDBus("org.unityconnect.PopupTest", "/PopupTest", "org.unityconnect.PopupTest", "Report", JSON.stringify({above:w.keepAbove, app:String(w.resourceClass)})); });')
        self.bus.call_sync('org.kde.KWin','/Scripting','org.kde.kwin.Scripting','loadScript',GLib.Variant('(ss)',(str(script),'unity-popup-check')),None,Gio.DBusCallFlags.NONE,3000,None)
        self.bus.call_sync('org.kde.KWin','/Scripting','org.kde.kwin.Scripting','start',None,None,Gio.DBusCallFlags.NONE,3000,None)
        wait_for(lambda:self.window)
        self.bus.unregister_object(registration)
        self.assertTrue(self.window['above'],self.window)
        call['state'] = 'active'; self.peer.event('call_control',state)
        wait_for(lambda:self.gui_state().get('calls') == 1)
        self.assertEqual(self.gui_state()['pid'],self.client_pid)
        stable_popup = self.gui_state()['popup_id']
        for _ in range(3):
            state['calls'] = []; self.peer.event('call_control',state)
            wait_for(lambda:self.gui_state().get('calls') == 0 and self.gui_state().get('updating'))
            time.sleep(1)
            self.assertTrue(self.gui_state()['popup_visible'])
            self.assertEqual(self.gui_state()['popup_id'],stable_popup)
            state['calls'] = [call]; self.peer.event('call_control',state)
            wait_for(lambda:self.gui_state().get('calls') == 1 and not self.gui_state().get('updating'))
            self.assertEqual(self.gui_state()['popup_id'],stable_popup)
            self.assertEqual(self.gui_state()['pid'],self.client_pid)
        state['calls'] = []; self.peer.event('call_control',state)
        wait_for(lambda:'org.unityconnect.Unity' not in self.bus.call_sync('org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','ListNames',None,None,Gio.DBusCallFlags.NONE,1000,None).unpack()[0])

    def call_bus_name(self,name):
        self.bus.call_sync('org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','RequestName',GLib.Variant('(su)',(name,0)),None,Gio.DBusCallFlags.NONE,1000,None)

    def test_popup_and_notification_survive_repeated_call_service_restarts(self):
        self.ofono = FakeOfono()
        info = json.loads(self.cli('discover').stdout)
        self.peer = harness.Peer(self.port,info['fingerprint']); self.cli('pair',harness.DEVICE)
        secret = self.peer.read()['secret']; self.peer.close()
        self.peer = harness.Peer(self.port,info['fingerprint'],secret)
        self.peer.event('call_control',{'backend':'bluetooth_hfp','calls':[],'muted':False,'route':'phone','routes':[]})
        wait_for(lambda:len(json.loads(self.cli('run',harness.DEVICE,'call_control','hfp_devices').stdout).get('result',{}).get('devices',[])) == 2)
        self.cli('run',harness.DEVICE,'call_control','hfp_select',json.dumps({'address':'AA:BB:CC:DD:EE:FF'}))
        self.peer.event('call_notifications',{'state':'offhook','number':''})
        self.ofono.add('/hfp_phone/voicecall01','active')
        current = wait_for(lambda:self.gui_state() if self.gui_state().get('popup_visible') else None)
        self.client_pid = current['pid']; popup = current['popup_id']
        previous_ids = current['call_ids']
        for _ in range(2):
            closed = list(self.notices.closed)
            self.ofono.close(); self.ofono = None
            wait_for(lambda:self.gui_state().get('reconnecting'))
            time.sleep(3.2)
            current = self.gui_state()
            self.assertTrue(current['popup_visible'])
            self.assertEqual(current['popup_id'],popup)
            self.assertEqual(current['pid'],self.client_pid)
            self.assertEqual(current['call_ids'],previous_ids)
            self.assertEqual(self.notices.closed,closed)
            self.ofono = FakeOfono(cold=True)
            self.ofono.add('/hfp/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF/voicecall01','active')
            wait_for(lambda:not self.gui_state().get('reconnecting') and self.gui_state().get('call_ids') != previous_ids)
            current = self.gui_state(); previous_ids = current['call_ids']
            self.assertEqual(current['popup_id'],popup)
            self.assertEqual(current['pid'],self.client_pid)
            self.assertEqual(self.notices.closed,closed)
            timer = current['timer']
            self.assertTrue(timer and int(timer.split(':')[-1]) >= 3,timer)
        self.ofono.close(); self.ofono = None
        wait_for(lambda:self.gui_state().get('reconnecting'))
        self.peer.event('call_notifications',{'state':'idle','number':''})
        wait_for(lambda:'org.unityconnect.Unity' not in self.bus.call_sync('org.freedesktop.DBus','/org/freedesktop/DBus','org.freedesktop.DBus','ListNames',None,None,Gio.DBusCallFlags.NONE,1000,None).unpack()[0])
        self.assertTrue(self.notices.closed)

    def test_phone_push_actions_open_the_correct_device_page(self):
        info=json.loads(self.cli('discover').stdout)
        self.peer=harness.Peer(self.port,info['fingerprint']);self.cli('pair',harness.DEVICE)
        secret=self.peer.read()['secret'];self.peer.close()
        self.peer=harness.Peer(self.port,info['fingerprint'],secret)
        self.peer.event('notifications',{'id':'test-post','app':'test.chat','title':'Alex','text':'Hello','actions':[],'removed':False,'alert':True})
        wait_for(lambda:self.notices.items)
        self.notices.action(self.notices.items[-1]['id'])
        current=wait_for(lambda:self.gui_state() if self.gui_state().get('page')=='notifications' and self.gui_state().get('approved') else None)
        self.client_pid=current['pid'];self.assertTrue(current['main_visible'])
        self.peer.event('sms',{'revision':1,'incoming':{'id':'12','address':'15550123','body':'New SMS','timestamp':1000}})
        wait_for(lambda:self.notices.items[-1]['title'].startswith('SMS ·'))
        self.notices.action(self.notices.items[-1]['id'])
        wait_for(lambda:self.gui_state().get('page')=='messages')
        command=self.peer.read();self.assertEqual((command['feature'],command['action']),('sms','list'))
        self.peer.send({'type':'result','id':command['id'],'ok':True,'data':{'messages':[{'id':'12','address':'15550123','body':'New SMS','timestamp':1000,'direction':'incoming','type':1,'thread_id':'1'}],'next_offset':None},'error':''})
        self.assertEqual(self.gui_state()['pid'],self.client_pid)


if __name__ == '__main__': unittest.main(verbosity=2)
