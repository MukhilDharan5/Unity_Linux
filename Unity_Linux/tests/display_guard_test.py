#!/usr/bin/env python3
"""Exercise the real independent backlight lease against an isolated fake panel."""
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
        self.file.write_text(str(value))

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
            {'name':'eDP-1','type':7,'connected':True,'enabled':True,'pos':{'x':0,'y':0},'scale':1.25},
            {'name':'Virtual-org.unityconnect.Unity.Screen','id':2,'type':0,'connected':True,'enabled':True,'pos':{'x':1920,'y':0},'scale':1,
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
                elif value.startswith('output.2.position.'): self.outputs[1]['pos'] = {'x':0,'y':0}
            return SimpleNamespace(returncode=0,stdout='',stderr='')
        self.mock = patch.object(module.subprocess,'run',run); self.mock.start()
        self.temp = tempfile.TemporaryDirectory(prefix='unity-panel-power-test-')
    def tearDown(self): self.mock.stop(); self.temp.cleanup()

    def test_mirrored_phone_output_keeps_desktop_alive_while_panel_is_powered_off(self):
        hardware = module.DisplayPower('Virtual-org.unityconnect.Unity.Screen')
        self.assertEqual(hardware.logical_size,[1536,864])
        guard = module.DisplayGuard(self.temp.name,hardware)
        try:
            guard.off()
            self.assertFalse(self.outputs[0]['enabled']); self.assertTrue(self.outputs[1]['enabled'])
            self.assertFalse(any('brightness' in str(c) for c in self.commands))
            guard.restore(); self.assertTrue(self.outputs[0]['enabled'])
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

if __name__ == '__main__': unittest.main(verbosity=2)
