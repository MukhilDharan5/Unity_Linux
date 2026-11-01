#!/usr/bin/env python3
"""Real PAM conversations in private user/mount namespaces; no live PAM writes."""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FALLBACK = r'''
#include <security/pam_modules.h>
#include <security/pam_ext.h>
#include <string.h>
int pam_sm_authenticate(pam_handle_t *pamh, int flags, int argc, const char **argv) {
    if (argc && !strcmp(argv[0], "seed")) {
        pam_set_item(pamh, PAM_AUTHTOK, "fixture-password");
        return PAM_IGNORE;
    }
    const char *token = 0;
    int result = pam_get_authtok(pamh, PAM_AUTHTOK, &token, "Password: ");
    return result == PAM_SUCCESS && token && !strcmp(token, "fixture-password") ? PAM_SUCCESS : PAM_AUTH_ERR;
}
int pam_sm_setcred(pam_handle_t *pamh, int flags, int argc, const char **argv) { return PAM_IGNORE; }
'''

def inside(username):
    assert os.geteuid() == 0
    uid = pwd.getpwnam(username).pw_uid
    assert uid >= 1000
    pam = ctypes.CDLL('libpam.so.0')
    libc = ctypes.CDLL(None)
    machine_id = Path('/etc/machine-id').read_bytes()
    passwd = Path('/etc/passwd').read_bytes()
    with tempfile.TemporaryDirectory(prefix='unity-password-pam-') as directory:
        directory = Path(directory)
        etc = directory / 'etc'; etc.mkdir()
        (etc / 'machine-id').write_bytes(machine_id)
        (etc / 'passwd').write_bytes(passwd)
        (etc / 'nsswitch.conf').write_text('passwd: files\ngroup: files\n')
        if Path('/etc/ld.so.cache').exists(): shutil.copyfile('/etc/ld.so.cache', etc / 'ld.so.cache')
        enrollment = etc / 'unity/pam/users'; enrollment.mkdir(parents=True, mode=0o700)
        for private in [etc / 'unity', etc / 'unity/pam', enrollment]: private.chmod(0o700)
        data = dict(version=1, uid=uid, username=username, device_id='ab'*16, public_key='04'+'11'*64,
                    computer_fingerprint='cd'*32, machine_id=hashlib.sha256(machine_id.strip()).hexdigest(),
                    computer='PAM fixture', services=['sudo'], timeout_seconds=10)
        config = enrollment / f'{uid}.json'; config.write_text(json.dumps(data)); config.chmod(0o600)
        runtime = directory / 'run'; runtime.mkdir()
        fallback = directory / 'fallback.so'
        module = directory / 'pam_unity.so'
        shutil.copyfile(ROOT / 'target/debug/libpam_unity.so', module)
        subprocess.run(['/usr/bin/cc', '-shared', '-fPIC', '-x', 'c', '-', '-o', str(fallback), '-lpam'], input=FALLBACK, text=True, check=True)
        # These mounts are private to this process's namespace and disappear on exit.
        subprocess.run(['/usr/bin/mount', '--make-rprivate', '/'], check=True)
        subprocess.run(['/usr/bin/mount', '--bind', str(etc), '/etc'], check=True)
        subprocess.run(['/usr/bin/mount', '--bind', str(runtime), '/run'], check=True)
        class Message(ctypes.Structure):
            _fields_ = [('style', ctypes.c_int), ('text', ctypes.c_char_p)]
        class Response(ctypes.Structure):
            _fields_ = [('text', ctypes.c_void_p), ('code', ctypes.c_int)]
        callback_type = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.POINTER(Message)), ctypes.POINTER(ctypes.POINTER(Response)), ctypes.c_void_p)
        class Conversation(ctypes.Structure):
            _fields_ = [('callback', callback_type), ('data', ctypes.c_void_p)]
        libc.calloc.argtypes = [ctypes.c_size_t, ctypes.c_size_t]; libc.calloc.restype = ctypes.c_void_p
        libc.strdup.argtypes = [ctypes.c_char_p]; libc.strdup.restype = ctypes.c_void_p
        pam.pam_start_confdir.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.POINTER(Conversation), ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
        pam.pam_authenticate.argtypes = [ctypes.c_void_p, ctypes.c_int]
        pam.pam_end.argtypes = [ctypes.c_void_p, ctypes.c_int]
        stack = directory / 'stack'; stack.mkdir()
        def attempt(answers, expected, seed=False, flags=0):
            prompts = []; answers = iter(answers)
            def converse(count, messages, output, _):
                values = ctypes.cast(libc.calloc(count, ctypes.sizeof(Response)), ctypes.POINTER(Response))
                output[0] = values
                for index in range(count):
                    message = messages[index].contents
                    prompts.append((message.style, message.text.decode()))
                    if message.style in (1, 2): values[index].text = libc.strdup(next(answers).encode())
                return 0
            callback = callback_type(converse); conversation = Conversation(callback, None)
            (stack / 'sudo').write_text((f'auth optional {fallback} seed\n' if seed else '') + f'auth sufficient {module}\nauth required {fallback}\naccount required pam_permit.so\n')
            handle = ctypes.c_void_p()
            result = pam.pam_start_confdir(b'sudo', username.encode(), ctypes.byref(conversation), os.fsencode(stack), ctypes.byref(handle))
            assert result == 0, result
            try:
                result = pam.pam_authenticate(handle, flags)
                assert result == expected, (result, prompts)
            finally: pam.pam_end(handle, result)
            return prompts
        prompts = attempt(['fixture-password'], 0)
        assert [p for p in prompts if p[0] == 1] == [(1, 'Password: ')], prompts
        assert any(style == 4 and 'press Enter to use phone fingerprint' in text for style, text in prompts), prompts
        prompts = attempt(['wrong-fixture-password'], 7)
        assert [p for p in prompts if p[0] == 1] == [(1, 'Password: ')], prompts
        # No broker is present in the private /run. A declined/unavailable phone
        # must leave no empty token behind: the password module asks afresh.
        prompts = attempt(['', 'fixture-password'], 0)
        assert [p for p in prompts if p[0] == 1] == [(1, 'Password: '), (1, 'Password: ')], prompts
        assert attempt([], 0, seed=True) == [], 'An existing token must not be replaced or prompt again'
        prompts = attempt(['fixture-password'], 0, flags=0x8000)
        assert prompts == [(1, 'Password: ')], prompts
        print('Real PAM: hidden password prompt, one-shot forwarding, wrong-password rejection, blank-choice fallback, existing tokens and silent mode passed.')

if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--inside': inside(sys.argv[2])
    else:
        username = pwd.getpwuid(os.getuid()).pw_name
        subprocess.run(['unshare', '--user', '--map-root-user', '--mount', sys.executable, str(Path(__file__).resolve()), '--inside', username], check=True)
