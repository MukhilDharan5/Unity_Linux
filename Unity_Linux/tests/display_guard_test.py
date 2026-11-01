#!/usr/bin/env python3
"""Exercise independent display restoration and real GNOME/KDE configuration contracts."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import tempfile
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('display_guard', ROOT / 'unity-screen/display-guard.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

def wait_for(predicate, timeout=4):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate(): return
        time.sleep(.02)
    raise AssertionError('Display was not restored')

class Panel:
    name = 'test_laptop'
    maximum = 100
    def __init__(self, file): self.file = Path(file); self.fail_restore = False
    def brightness(self): return int(self.file.read_text())
    def set(self, value):
        if self.fail_restore and value > 0: raise RuntimeError('Temporary login service failure')
        temporary = self.file.with_suffix(".next"); temporary.write_text(str(value)); temporary.replace(self.file)

class GuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='unity-display-test-'); self.directory = Path(self.temp.name)
        self.level = self.directory / 'brightness'; self.level.write_text('73'); self.panel = Panel(self.level)
    def tearDown(self): self.temp.cleanup()
    def guard(self): return module.DisplayGuard(self.directory / 'state', self.panel)
    def test_off_keeps_session_and_restores_the_original_level(self):
        guard = self.guard()
        try:
            guard.off(); self.assertEqual(self.panel.brightness(), 0)
            self.assertEqual(guard.journal.stat().st_mode & 0o777, 0o600)
            guard.off(); guard.restore(); self.assertEqual(self.panel.brightness(), 73)
            self.assertFalse(guard.journal.exists())
        finally: guard.restore(); guard.close()
    def test_user_brightness_key_change_wins(self):
        guard = self.guard()
        try:
            guard.off(); self.panel.set(45); guard.restore(); self.assertEqual(self.panel.brightness(), 45)
        finally: guard.close()
    def test_failed_restoration_is_journaled_and_recovered(self):
        guard = self.guard(); guard.off(); self.panel.fail_restore = True
        with self.assertRaises(RuntimeError): guard.restore()
        self.assertTrue(guard.journal.exists()); guard.close()
        self.panel.fail_restore = False
        recovered = self.guard()
        try: self.assertEqual(self.panel.brightness(), 73); self.assertFalse(recovered.journal.exists())
        finally: recovered.close()
    def test_a_second_share_cannot_take_over_the_panel(self):
        guard = self.guard()
        try:
            guard.off()
            with self.assertRaises(BlockingIOError): self.guard()
            self.assertEqual(self.panel.brightness(), 0)
        finally: guard.restore(); guard.close()
    def test_missed_heartbeats_restore_even_while_parent_pipe_is_open(self):
        reader, writer = os.pipe(); child = os.fork()
        if child == 0:
            os.close(writer)
            with os.fdopen(reader) as stream, open(os.devnull, 'w') as output:
                guard = self.guard()
                try: guard.run(stream, output, lease=.4)
                finally: guard.close()
            os._exit(0)
        os.close(reader)
        try:
            os.write(writer, b'{"action":"off"}\n'); wait_for(lambda: self.panel.brightness() == 0)
            wait_for(lambda: self.panel.brightness() == 73)
            _, status = os.waitpid(child, 0); self.assertEqual(status, 0)
        finally: os.close(writer)
    def test_killing_capture_parent_still_restores_from_independent_guard(self):
        marker = self.directory / 'guard-pid'
        parent = os.fork()
        if parent == 0:
            reader, writer = os.pipe(); guard_pid = os.fork()
            if guard_pid == 0:
                os.close(writer)
                with os.fdopen(reader) as stream, open(os.devnull, 'w') as output:
                    guard = self.guard()
                    try: guard.run(stream, output, lease=2)
                    finally: guard.close()
                os._exit(0)
            os.close(reader); marker.write_text(str(guard_pid)); os.write(writer, b'{"action":"off"}\n')
            time.sleep(10); os._exit(0)
        wait_for(lambda: marker.exists() and self.panel.brightness() == 0)
        os.kill(parent, signal.SIGKILL); os.waitpid(parent, 0)
        wait_for(lambda: self.panel.brightness() == 73)
        self.assertFalse((self.directory / 'state/display-backlight.json').exists())


class DisplayPowerTests(unittest.TestCase):
    def setUp(self):
        self.outputs = [
            {'name':'eDP-1','priority':1,'type':7,'connected':True,'enabled':True,'pos':{'x':0,'y':0},'scale':1.25,
             'currentModeId':'1','modes':[{'id':'1','size':{'width':1920,'height':1080}}]},
            {'name':'Virtual-org.unityconnect.Unity.Screen','priority':2,'id':2,'type':0,'connected':True,'enabled':True,'pos':{'x':1920,'y':0},'scale':1,
             'currentModeId':'1','modes':[{'id':'1','size':{'width':1920,'height':1080}}]},
        ]
        self.commands = []
        def run(command, **kwargs):
            self.commands.append(command)
            if command[-1] == '--json': return SimpleNamespace(returncode=0,stdout=json.dumps({'outputs':self.outputs}),stderr='')
            for value in command[1:]:
                if value == 'output.eDP-1.disable': self.outputs[0]['enabled'] = False
                elif value == 'output.eDP-1.enable': self.outputs[0]['enabled'] = True
                elif value.startswith('output.2.scale.'): self.outputs[1]['scale'] = float(value.removeprefix('output.2.scale.'))
                elif value.startswith('output.2.position.'):
                    x, y = map(int, value.removeprefix('output.2.position.').split(',')); self.outputs[1]['pos'] = {'x':x,'y':y}
                elif value.startswith('output.2.priority.'): self.outputs[1]['priority'] = int(value.removeprefix('output.2.priority.'))
                elif value.startswith('output.eDP-1.priority.'): self.outputs[0]['priority'] = int(value.removeprefix('output.eDP-1.priority.'))
            return SimpleNamespace(returncode=0,stdout='',stderr='')
        self.mock = patch.object(module.subprocess,'run',run); self.mock.start()
        self.temp = tempfile.TemporaryDirectory(prefix='unity-panel-power-test-')
    def tearDown(self): self.mock.stop(); self.temp.cleanup()

    def test_phone_is_primary_while_the_laptop_panel_is_disabled(self):
        hardware = module.DisplayPower('Virtual-org.unityconnect.Unity.Screen')
        self.assertEqual(hardware.logical_size,[1920,1080])
        guard = module.DisplayGuard(self.temp.name,hardware)
        try:
            guard.off()
            self.assertFalse(self.outputs[0]['enabled']); self.assertTrue(self.outputs[1]['enabled'])
            self.assertEqual(self.outputs[1]['priority'], 1); self.assertEqual(hardware.logical_size, [1536,864])
            self.assertFalse(any('brightness' in str(c) for c in self.commands))
            guard.restore(); self.assertTrue(self.outputs[0]['enabled'])
            self.assertEqual(self.outputs[0]['priority'], 1); self.assertEqual(self.outputs[1]['priority'], 2)
            self.assertEqual(self.outputs[1]['pos'], {'x':1920,'y':0})
        finally: guard.restore(); guard.close()

    def test_extended_phone_keeps_both_outputs_on_without_changing_primary(self):
        guard = module.DisplayGuard(self.temp.name,module.DisplayPower('Virtual-org.unityconnect.Unity.Screen'))
        try:
            guard.extend()
            self.assertTrue(self.outputs[0]['enabled']); self.assertTrue(self.outputs[1]['enabled'])
            self.assertEqual(self.outputs[0]['priority'],1); self.assertEqual(self.outputs[1]['priority'],2)
            self.assertEqual(self.outputs[1]['pos'],{'x':1536,'y':0})
            self.assertTrue(guard.status()['extended_display']); self.assertFalse(guard.status()['display_available'])
            self.assertFalse(any('.disable' in item for command in self.commands for item in command))
            guard.restore(); self.assertTrue(self.outputs[0]['enabled'])
            self.assertEqual(self.outputs[1]['pos'],{'x':1920,'y':0})
        finally: guard.restore(); guard.close()

    def test_panel_cannot_be_disabled_when_the_phone_output_has_disappeared(self):
        hardware = module.DisplayPower('Virtual-org.unityconnect.Unity.Screen'); self.outputs[1]['enabled'] = False
        guard = module.DisplayGuard(self.temp.name,hardware)
        try:
            with self.assertRaisesRegex(RuntimeError,'no longer active'): guard.off()
            self.assertTrue(self.outputs[0]['enabled'])
        finally: guard.restore(); guard.close()

    def test_crash_recovery_enables_panel_even_after_virtual_output_disappears(self):
        guard = module.DisplayGuard(self.temp.name,module.DisplayPower('Virtual-org.unityconnect.Unity.Screen'))
        guard.off(); guard.close(); self.outputs.pop()
        recovered = module.DisplayGuard(self.temp.name,recover_only=True)
        try:
            self.assertTrue(self.outputs[0]['enabled']); self.assertFalse(recovered.journal.exists())
        finally: recovered.close()


class MutterBus:
    """Actual GLib variant signatures, with isolated display topology instead of hardware."""
    def __init__(self):
        from gi.repository import GLib
        self.GLib = GLib; self.serial = 1; self.calls = []; self.fail_once = False
        self.monitors = [
            (('eDP-1','LEN','panel','1'), [('1920x1080',1920,1080,60.,1.,[1.,1.25],{'is-current':GLib.Variant('b',True),'is-preferred':GLib.Variant('b',True)})], {'is-builtin':GLib.Variant('b',True)}),
            (('Meta-0','Meta','Unity','2'), [('1920x1080',1920,1080,60.,1.,[1.,1.25],{'is-current':GLib.Variant('b',True)})], {}),
        ]
        self.logical = [(0,0,1.,0,True,[self.monitors[0][0]],{}),(1920,0,1.,0,False,[self.monitors[1][0]],{})]

    def call_sync(self, destination, path, interface, method, parameters, *args):
        G = self.GLib
        self.calls.append((method, parameters.unpack() if parameters else None))
        if method == 'NameHasOwner': return G.Variant('(b)',(True,))
        if method == 'GetCurrentState':
            return G.Variant('(ua((ssss)a(siiddada{sv})a{sv})a(iiduba(ssss)a{sv})a{sv})',
                (self.serial,self.monitors,self.logical,{'layout-mode':G.Variant('u',1)}))
        if method == 'ApplyMonitorsConfig':
            serial, policy, logical, properties = parameters.unpack()
            assert serial == self.serial and policy == 1
            if self.fail_once: self.fail_once = False; raise RuntimeError('Temporary display configuration failure')
            specs = {m[0][0]:m[0] for m in self.monitors}
            self.logical = [(x,y,scale,transform,primary,[specs[m[0]] for m in members],{}) for x,y,scale,transform,primary,members in logical]
            self.serial += 1
            return G.Variant('()', ())
        raise AssertionError(method)


class GnomeDisplayTests(unittest.TestCase):
    def setUp(self):
        from gi.repository import Gio
        self.bus = MutterBus()
        self.mock = patch.object(Gio, 'bus_get_sync', return_value=self.bus); self.mock.start()
        self.temp = tempfile.TemporaryDirectory(prefix='unity-gnome-display-test-')
    def tearDown(self): self.mock.stop(); self.temp.cleanup()
    def hardware(self): return module.GnomeDisplayPower('Meta-0')
    def active(self, name): return any(any(spec[0] == name for spec in group[5]) for group in self.bus.logical)

    def test_primary_phone_replaces_panel_without_brightness_and_restores_layout(self):
        original = list(self.bus.logical)
        hardware = self.hardware(); guard = module.DisplayGuard(self.temp.name, hardware)
        try:
            guard.off()
            self.assertFalse(self.active('eDP-1')); self.assertTrue(self.active('Meta-0'))
            self.assertEqual(self.bus.logical[0][0:5], (0,0,1.,0,True))
            self.assertTrue(guard.status()['phone_primary']); self.assertEqual(hardware.logical_size,[1920,1080])
            self.assertEqual(guard.journal.stat().st_mode & 0o777,0o600)
            self.assertEqual(json.loads(guard.journal.read_text())['backend'],'gnome-output')
            guard.off(); self.assertFalse(self.active('eDP-1'))  # Repeated Off is idempotent.
            guard.restore(); self.assertEqual(self.bus.logical,original)
            self.assertFalse(guard.journal.exists())
            self.assertTrue(all(name in ('GetCurrentState','ApplyMonitorsConfig') for name,_ in self.bus.calls))
        finally: guard.restore(); guard.close()

    def test_extended_display_is_secondary_and_cannot_power_off_the_laptop(self):
        original = list(self.bus.logical)
        guard = module.DisplayGuard(self.temp.name,self.hardware())
        try:
            guard.extend(); guard.extend()
            self.assertTrue(self.active('eDP-1')); self.assertTrue(self.active('Meta-0'))
            self.assertTrue(self.bus.logical[0][4]); self.assertFalse(self.bus.logical[1][4])
            self.assertEqual(self.bus.logical[1][:2],(1920,0))
            self.assertTrue(guard.status()['extended_display']); self.assertFalse(guard.status()['display_available'])
            with self.assertRaisesRegex(RuntimeError,'extended display'): guard.off()
            replies=[]; guard.command(b'{"action":"on"}',replies.append)
            self.assertTrue(replies[0]['extended_display']); self.assertTrue(self.active('eDP-1'))
            guard.restore(); self.assertEqual(self.bus.logical,original); self.assertFalse(guard.journal.exists())
        finally: guard.restore(); guard.close()

    def test_extended_display_restores_after_virtual_monitor_disappears_without_blanking_laptop(self):
        guard=module.DisplayGuard(self.temp.name,self.hardware()); guard.extend(); guard.close()
        self.bus.monitors.pop(); self.bus.logical=self.bus.logical[:1]
        recovered=module.DisplayGuard(self.temp.name,recover_only=True)
        try:
            self.assertTrue(self.active('eDP-1')); self.assertTrue(self.bus.logical[0][4])
            self.assertFalse(recovered.journal.exists())
        finally: recovered.close()

    def test_extended_display_is_adjacent_to_rightmost_monitor_and_preserves_existing_outputs(self):
        from gi.repository import GLib
        monitor=(('DP-1','External','Monitor','3'), [('1920x1080',1920,1080,60.,1.,[1.],{'is-current':GLib.Variant('b',True)})],{})
        self.bus.monitors.append(monitor)
        self.bus.logical[1]=(0,1080,1.,0,False,[self.bus.monitors[1][0]],{})
        external=(1920,540,1.,0,False,[monitor[0]],{})
        self.bus.logical.append(external)
        guard=module.DisplayGuard(self.temp.name,self.hardware())
        try:
            guard.extend()
            self.assertEqual(self.bus.logical[0],(0,0,1.,0,True,[self.bus.monitors[0][0]],{}))
            self.assertIn(external,self.bus.logical)
            self.assertEqual(self.bus.logical[-1][:2],(3840,540))
            self.assertFalse(self.bus.logical[-1][4]); self.assertTrue(self.active('eDP-1'))
        finally: guard.restore(); guard.close()

    def test_phone_preserves_the_laptop_logical_scale_and_coordinates(self):
        _, _, _, transform, primary, specs, props = self.bus.logical[0]
        self.bus.logical[0] = (0,0,1.25,transform,primary,specs,props)
        guard = module.DisplayGuard(self.temp.name,self.hardware())
        try:
            guard.off(); self.assertEqual(guard.status()['logical_size'],[1536,864])
            self.assertEqual(self.bus.logical[0][2],1.25)
            guard.restore(); self.assertEqual(self.bus.logical[0][2],1.25)
        finally: guard.restore(); guard.close()

    def test_crash_recovery_restores_builtin_even_after_portal_removes_virtual_monitor(self):
        guard = module.DisplayGuard(self.temp.name,self.hardware()); guard.off(); guard.close()
        self.bus.monitors = self.bus.monitors[:1]; self.bus.logical = []
        recovered = module.DisplayGuard(self.temp.name,recover_only=True)
        try:
            self.assertTrue(self.active('eDP-1')); self.assertTrue(self.bus.logical[0][4])
            self.assertFalse(recovered.journal.exists())
        finally: recovered.close()

    def test_missing_phone_or_non_virtual_capture_cannot_disable_panel(self):
        with self.assertRaisesRegex(RuntimeError,'main display'): module.GnomeDisplayPower(None)
        self.bus.monitors.pop(); self.bus.logical.pop()
        with self.assertRaisesRegex(RuntimeError,'no longer active'): self.hardware()
        self.assertTrue(self.active('eDP-1'))
        self.assertFalse(any(name == 'ApplyMonitorsConfig' for name,_ in self.bus.calls))

    def test_failure_before_switch_leaves_original_configuration_and_no_journal(self):
        hardware = self.hardware(); guard = module.DisplayGuard(self.temp.name,hardware)
        original = list(self.bus.logical); self.bus.fail_once = True
        try:
            with self.assertRaisesRegex(RuntimeError,'Temporary'): guard.off()
            self.assertEqual(self.bus.logical,original); self.assertFalse(guard.journal.exists())
        finally: guard.restore(); guard.close()

    def test_failed_restoration_is_retained_and_retried_using_original_configuration(self):
        original = list(self.bus.logical)
        guard = module.DisplayGuard(self.temp.name,self.hardware()); guard.off(); self.bus.fail_once = True
        with self.assertRaisesRegex(RuntimeError,'Temporary'): guard.restore()
        self.assertTrue(guard.journal.exists()); guard.close()
        recovered = module.DisplayGuard(self.temp.name,recover_only=True)
        try: self.assertEqual(self.bus.logical,original); self.assertFalse(recovered.journal.exists())
        finally: recovered.close()

    def test_gnome_detection_uses_the_running_compositor_without_desktop_environment_variables(self):
        with patch.dict(os.environ, {'XDG_CURRENT_DESKTOP':''}): self.assertTrue(module.gnome_session())

    def test_output_inventory_identifies_builtin_and_primary_without_modification(self):
        outputs = module.GnomeDisplayPower.list_outputs()
        self.assertEqual(outputs[0]['type'],7); self.assertTrue(outputs[0]['primary'])
        self.assertEqual(outputs[1]['name'],'Meta-0'); self.assertTrue(outputs[1]['enabled'])
        self.assertEqual(outputs[0]['modes'][0]['size'],{'width':1920,'height':1080})
        self.assertFalse(any(name == 'ApplyMonitorsConfig' for name,_ in self.bus.calls))

if __name__ == '__main__': unittest.main(verbosity=2)
