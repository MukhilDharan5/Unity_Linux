#!/usr/bin/env python3
"""Real native PAM ABI, signed enrollment, policy preservation; no live PAM writes."""
import ctypes
import getpass
import hashlib
import importlib.util
import json
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('phone_auth_setup', ROOT / 'scripts/setup-phone-auth.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class PhonePamIntegration(unittest.TestCase):
    def credential(self, pin='ab' * 32):
        key = ec.generate_private_key(ec.SECP256R1())
        public = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
        data = dict(version=1, device_id='cd' * 16, computer_fingerprint=pin, public_key=public.hex(), key_sha256=hashlib.sha256(public).hexdigest())
        statement = f"unity-pam-enroll-v1\n{data['device_id']}\n{pin}\n{public.hex()}\n".encode()
        data['signature'] = key.sign(statement, ec.ECDSA(hashes.SHA256())).hex()
        return data

    def test_real_enrollment_signature_rejects_replacement_and_tampering(self):
        data = self.credential()
        self.assertEqual(setup.validate_credential(data), data)
        for change in [dict(key_sha256='00' * 32), dict(computer_fingerprint='00' * 32), dict(device_id='00' * 16), dict(signature='00' * 70)]:
            with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                setup.validate_credential(dict(data, **change))

    def test_pam_rules_preserve_password_account_session_and_selinux_checks(self):
        original = 'auth [success=done ignore=ignore default=bad] pam_selinux_permit.so\nauth substack password-auth\nauth optional pam_gnome_keyring.so\naccount required pam_nologin.so\naccount include password-auth\npassword include password-auth\nsession include password-auth\n'
        configured = setup.pam_text(original)
        self.assertEqual(configured.splitlines()[0], original.splitlines()[0])
        self.assertEqual(configured.splitlines()[1] + '\n', setup.RULE)
        self.assertIn('auth substack password-auth', configured)
        self.assertEqual(setup.pam_text(configured), configured)
        self.assertEqual(setup.pam_text(configured, False), original)
        with self.assertRaises(ValueError):
            setup.pam_text('auth required pam_deny.so\n')
        with self.assertRaises(ValueError):
            setup.pam_text(original + 'auth required pam_unity.so unsafe-option\n')

    def test_plan_is_read_only_and_checks_the_desktop_identity(self):
        with tempfile.TemporaryDirectory(prefix='unity-pam-plan-') as tmp:
            tmp = Path(tmp)
            cert = b'fixture-desktop-certificate'
            pin = hashlib.sha256(cert).hexdigest()
            (tmp / 'identity.json').write_text(json.dumps({'cert': list(cert)}))
            (tmp / 'credential.json').write_text(json.dumps(self.credential(pin)))
            before = Path('/etc/pam.d/sudo').read_bytes()
            command = ['python3', str(ROOT / 'scripts/setup-phone-auth.py'), '--user', getpass.getuser(), '--enrollment', str(tmp / 'credential.json'),
                       '--desktop-state', str(tmp), '--module', str(ROOT / 'target/debug/libpam_unity.so'), '--services', 'sudo']
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('no system changes made', result.stdout)
            self.assertEqual(before, Path('/etc/pam.d/sudo').read_bytes())
            data = json.loads((tmp / 'credential.json').read_text())
            (tmp / 'credential.json').write_text(json.dumps(self.credential('ef' * 32)))
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('another Unity desktop', result.stderr)

    def test_sudo_login_shell_uses_one_authenticator_through_its_included_stack(self):
        original = 'auth include sudo\naccount include sudo\npassword include sudo\nsession include sudo\n'
        shared = setup.service_pam_text('sudo-i', original, ['sudo', 'sudo-i'])
        self.assertEqual(shared, original)
        self.assertEqual(setup.service_pam_text('sudo-i', setup.RULE + original, ['sudo', 'sudo-i']), original)
        self.assertEqual(setup.service_pam_text('sudo-i', original, ['sudo-i']), setup.RULE + original)

    def test_module_update_review_preserves_live_rules_and_enrollment(self):
        before = Path('/etc/pam.d/sudo').read_bytes()
        command = ['python3', str(ROOT / 'scripts/setup-phone-auth.py'), '--update-module', '--module', str(ROOT / 'target/debug/libpam_unity.so')]
        result = subprocess.run(command, text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Password entry is the default', result.stdout)
        self.assertIn('no system changes made', result.stdout)
        self.assertEqual(before, Path('/etc/pam.d/sudo').read_bytes())

    def test_export_reader_rejects_symlinks_and_fifo_without_blocking(self):
        with tempfile.TemporaryDirectory(prefix='unity-pam-input-') as tmp:
            tmp = Path(tmp)
            (tmp / 'real').write_text('{}')
            (tmp / 'link').symlink_to(tmp / 'real')
            with self.assertRaises(OSError):
                setup.bounded_json(tmp / 'link')
            os.mkfifo(tmp / 'fifo')
            with self.assertRaises(ValueError):
                setup.bounded_json(tmp / 'fifo')

    def test_real_libpam_never_accepts_missing_enrollment_and_retains_fallback(self):
        library = ROOT / 'target/debug/libpam_unity.so'
        native = ctypes.CDLL(str(library))
        native.pam_sm_authenticate.restype = ctypes.c_int
        self.assertEqual(native.pam_sm_authenticate(None, 0, 0, None), 25)
        pam = ctypes.CDLL('libpam.so.0')
        callback_type = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)
        callback = callback_type(lambda *_: 19)
        class Conversation(ctypes.Structure):
            _fields_ = [('callback', callback_type), ('data', ctypes.c_void_p)]
        conversation = Conversation(callback, None)
        pam.pam_start_confdir.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.POINTER(Conversation), ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
        pam.pam_authenticate.argtypes = [ctypes.c_void_p, ctypes.c_int]
        pam.pam_end.argtypes = [ctypes.c_void_p, ctypes.c_int]
        with tempfile.TemporaryDirectory(prefix='unity-native-pam-') as tmp:
            for fallback, expected in [('pam_deny.so', 7), ('pam_permit.so', 0)]:
                Path(tmp, 'unity-test').write_text(f'auth sufficient {library}\nauth required {fallback}\naccount required pam_permit.so\n')
                handle = ctypes.c_void_p()
                result = pam.pam_start_confdir(b'unity-test', getpass.getuser().encode(), ctypes.byref(conversation), os.fsencode(tmp), ctypes.byref(handle))
                self.assertEqual(result, 0)
                try:
                    self.assertEqual(pam.pam_authenticate(handle, 0), expected)
                finally:
                    pam.pam_end(handle, 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
