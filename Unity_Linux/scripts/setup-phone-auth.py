#!/usr/bin/env python3
"""Review/enroll phone biometrics in PAM. Never stores or forwards a password."""
import argparse
import ctypes
import getpass
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import stat
import subprocess
import tempfile
import time

ALLOWED = {'sudo', 'sudo-i', 'login', 'gdm-password', 'gdm-fingerprint', 'polkit-1'}
DEFAULT_SERVICES = ('sudo', 'sudo-i', 'login', 'gdm-password', 'polkit-1')
MODULE = Path('/usr/local/lib/security/pam_unity.so')
CONFIG = Path('/etc/unity/pam/users')
BACKUPS = Path('/var/lib/unity-pam/backups')
RULE = f'auth       sufficient   {MODULE}\n'


def bounded_json(path, limit=8192):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as handle:
        if not stat.S_ISREG(os.fstat(handle.fileno()).st_mode):
            raise ValueError('A regular credential file is required')
        data = handle.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Credential file is too large')
    return json.loads(data)


def hex_value(value, size):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{%d}' % (size * 2), value) is not None


def validate_credential(data):
    if set(data) != {'version', 'device_id', 'computer_fingerprint', 'public_key', 'key_sha256', 'signature'} or data['version'] != 1:
        raise ValueError('Unsupported phone credential export')
    for key, size in [('device_id', 16), ('computer_fingerprint', 32), ('public_key', 65), ('key_sha256', 32)]:
        if not hex_value(data[key], size):
            raise ValueError(f'Invalid credential {key}')
    public = bytes.fromhex(data['public_key'])
    if public[0] != 4 or hashlib.sha256(public).hexdigest() != data['key_sha256']:
        raise ValueError('Credential fingerprint does not match its public key')
    signed = data['signature']
    if not isinstance(signed, str) or not 16 <= len(signed) <= 160 or not hex_value(signed, len(signed) // 2):
        raise ValueError('Invalid enrollment proof')
    # Verify the export's signed statement. The administrator still compares the phone's fingerprint.
    der = bytes.fromhex('3059301306072a8648ce3d020106082a8648ce3d030107034200') + public
    statement = f"unity-pam-enroll-v1\n{data['device_id']}\n{data['computer_fingerprint']}\n{data['public_key']}\n".encode('ascii')
    with tempfile.TemporaryDirectory(prefix='unity-pam-proof-') as directory:
        directory = Path(directory)
        (directory / 'key.der').write_bytes(der)
        (directory / 'proof').write_bytes(bytes.fromhex(signed))
        (directory / 'statement').write_bytes(statement)
        subprocess.run(['/usr/bin/openssl', 'pkey', '-pubin', '-inform', 'DER', '-in', str(directory / 'key.der'), '-out', str(directory / 'key.pem')], check=True, capture_output=True)
        subprocess.run(['/usr/bin/openssl', 'dgst', '-sha256', '-verify', str(directory / 'key.pem'), '-signature', str(directory / 'proof'), str(directory / 'statement')], check=True, capture_output=True)
    return data


def pam_text(original, enable=True):
    if '\0' in original or not any(re.match(r'^\s*account\s', line) for line in original.splitlines()):
        raise ValueError('PAM file lacks its normal account policy')
    # Handle only our exact rule. Refuse unfamiliar administrator customization involving this module.
    for line in original.splitlines(keepends=True):
        if 'pam_unity.so' in line and line != RULE:
            raise ValueError('Review the existing custom pam_unity.so rule manually')
    lines = [line for line in original.splitlines(keepends=True) if line != RULE]
    if not enable:
        return ''.join(lines)
    positions = [i for i, line in enumerate(lines) if re.match(r'^\s*auth\s', line)]
    if not positions:
        raise ValueError('No existing PAM authentication stack')
    index = positions[0]
    # Preserve GDM's SELinux admission check before adding an alternative authenticator.
    if 'pam_selinux_permit.so' in lines[index]:
        index += 1
    lines.insert(index, RULE)
    return ''.join(lines)


def service_pam_text(service, original, services):
    configured = pam_text(original)
    # sudo-i includes sudo on Fedora. One authenticator in the shared stack avoids
    # asking twice after a declined/timed-out phone approval.
    if service == 'sudo-i' and 'sudo' in services and any(
            re.match(r'^\s*auth\s+(?:include|substack)\s+sudo\s*(?:#.*)?$', line)
            for line in original.splitlines()):
        return pam_text(original, False)
    return configured


def root_directory(path, mode=0o755):
    if path == Path('/'):
        return
    root_directory(path.parent)
    if not path.exists():
        path.mkdir(mode=mode)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError(f'Untrusted administrator directory: {path}')


def atomic(path, data, mode):
    root_directory(path.parent)
    fd, name = tempfile.mkstemp(prefix='.unity-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(name, mode)
        os.chown(name, 0, 0)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def update_module(source, apply=False):
    """Replace only the native module, preserving enrollment and all PAM rules."""
    fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as handle:
        if not stat.S_ISREG(os.fstat(handle.fileno()).st_mode):
            raise ValueError('The PAM module must be a regular file')
        replacement = handle.read(16 * 1024 * 1024 + 1)
    if not replacement.startswith(b'\x7fELF') or len(replacement) > 16 * 1024 * 1024:
        raise ValueError('Build a valid native PAM module first')
    info = MODULE.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError('The installed PAM module is not a trusted administrator-owned file')
    print('Update the installed Unity PAM module. Password entry is the default; an empty Enter selects phone fingerprint.')
    print('Existing PAM rules, account/session policy and enrollment remain unchanged.')
    if not apply:
        print('Review complete; no system changes made. Use --update-module --apply to install.')
        return
    if os.geteuid() != 0:
        raise ValueError('Updating the system PAM module requires administrator authorization')
    root_directory(MODULE.parent)
    root_directory(BACKUPS, 0o700)
    original = MODULE.read_bytes()
    backup = BACKUPS / f'{time.time_ns()}-module-update'
    backup.mkdir(mode=0o700)
    atomic(backup / 'pam_unity.so', original, 0o600)
    mode = stat.S_IMODE(info.st_mode)
    try:
        atomic(MODULE, replacement, mode)
        if Path('/usr/sbin/restorecon').exists():
            subprocess.run(['/usr/sbin/restorecon', str(MODULE)], check=True)
        if MODULE.read_bytes() != replacement:
            raise RuntimeError('Installed PAM module did not match the tested build')
    except BaseException:
        atomic(MODULE, original, mode)
        if Path('/usr/sbin/restorecon').exists():
            subprocess.run(['/usr/sbin/restorecon', str(MODULE)], check=False)
        raise
    print(f'Password-first PAM module installed. Previous module backed up in {backup}')


def pam_self_test(username):
    """Real libpam with a private test stack; no live PAM service is modified."""
    pam = ctypes.CDLL('libpam.so.0')
    callback_type = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)
    callback = callback_type(lambda *_: 19)  # No password conversation is permitted in this proof-only test.
    class Conversation(ctypes.Structure):
        _fields_ = [('callback', callback_type), ('data', ctypes.c_void_p)]
    conversation = Conversation(callback, None)
    pam.pam_start_confdir.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.POINTER(Conversation), ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
    pam.pam_authenticate.argtypes = [ctypes.c_void_p, ctypes.c_int]
    pam.pam_end.argtypes = [ctypes.c_void_p, ctypes.c_int]
    with tempfile.TemporaryDirectory(prefix='unity-pam-test-') as directory:
        Path(directory, 'unity-pam-test').write_text(RULE + 'auth required pam_deny.so\naccount required pam_permit.so\n')
        handle = ctypes.c_void_p()
        result = pam.pam_start_confdir(b'unity-pam-test', username.encode(), ctypes.byref(conversation), os.fsencode(directory), ctypes.byref(handle))
        if result != 0:
            raise RuntimeError('Could not start the isolated PAM verification test')
        try:
            print('Approve the unity-pam-test request with your phone fingerprint now.', flush=True)
            result = pam.pam_authenticate(handle, 0)
            if result != 0:
                raise RuntimeError(f'Fingerprint verification did not succeed (PAM {result}). Existing login/sudo configuration was not changed.')
        finally:
            pam.pam_end(handle, result)


def pam_file(service):
    path = Path('/etc/pam.d', service)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError(f'Review this nonstandard PAM service manually: {path}')
    return path, path.read_bytes(), stat.S_IMODE(info.st_mode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--user', default=getpass.getuser())
    parser.add_argument('--enrollment', type=Path)
    local_module = Path(__file__).resolve().parent / 'pam_unity.so'
    parser.add_argument('--module', type=Path, default=local_module if local_module.exists() else Path(__file__).resolve().parents[1] / 'dist/pam_unity.so')
    parser.add_argument('--desktop-state', type=Path)
    parser.add_argument('--services', help='Comma-separated local PAM services; default: existing sudo, login, GDM and polkit services')
    parser.add_argument('--confirm-key-sha256', help='Full credential fingerprint read from the phone')
    parser.add_argument('--apply', action='store_true', help='Enroll and run a real fingerprint test before enabling the reviewed PAM rules')
    parser.add_argument('--remove', action='store_true', help='Remove this account enrollment; remove PAM rules when no enrolled accounts remain')
    parser.add_argument('--update-module', action='store_true', help='Update only the installed module, retaining enrollment and PAM rules')
    args = parser.parse_args()
    if args.update_module:
        if args.remove or args.enrollment or args.services or args.confirm_key_sha256:
            parser.error('--update-module cannot be combined with enrollment, removal or service changes')
        update_module(args.module, args.apply)
        return
    account = pwd.getpwnam(args.user)
    if not 1000 <= account.pw_uid < 4294967295:
        parser.error('Choose a regular local login account, not root')
    services = list(dict.fromkeys(args.services.split(','))) if args.services else [name for name in DEFAULT_SERVICES if Path('/etc/pam.d', name).exists()]
    if not services or not set(services) <= ALLOWED:
        parser.error('Only explicit supported local PAM services can be enabled')
    config_path = CONFIG / f'{account.pw_uid}.json'
    if args.remove:
        if not args.apply:
            print(f'Would remove {config_path}. Password authentication stays configured. Run with --apply to remove.')
            return
        if os.geteuid() != 0:
            parser.error('Removal requires an administrator')
        config_path.unlink(missing_ok=True)
        if not list(CONFIG.glob('*.json')):
            for name in sorted(ALLOWED):
                path = Path('/etc/pam.d', name)
                if path.exists() and RULE in path.read_text():
                    path, original, mode = pam_file(name)
                    atomic(path, pam_text(original.decode(), False).encode(), mode)
        print('Phone authentication enrollment removed. Password rules remain. User-service linger, if enabled, is left unchanged.')
        return
    if args.enrollment is None:
        parser.error('Export a fingerprint credential from the phone, then supply --enrollment FILE')
    credential = validate_credential(bounded_json(args.enrollment))
    state = args.desktop_state or Path(account.pw_dir) / '.local/state/unity'
    identity = bounded_json(state / 'identity.json', 65536)
    fingerprint = hashlib.sha256(bytes(identity['cert'])).hexdigest()
    if credential['computer_fingerprint'] != fingerprint:
        raise ValueError('The phone credential belongs to another Unity desktop identity')
    machine = Path('/etc/machine-id').read_text().strip()
    if not hex_value(machine, 16):
        raise ValueError('Local machine identity is unavailable')
    name = os.uname().nodename
    if not name or len(name) > 64 or any(ord(c) not in range(32, 127) for c in name):
        raise ValueError('Use a printable local hostname of at most 64 characters')
    config = dict(version=1, uid=account.pw_uid, username=account.pw_name, device_id=credential['device_id'], public_key=credential['public_key'],
                  computer_fingerprint=fingerprint, machine_id=hashlib.sha256(machine.encode()).hexdigest(), computer=name, services=services, timeout_seconds=45)
    changes = []
    for service in services:
        path, original, mode = pam_file(service)
        replacement = service_pam_text(service, original.decode(), services).encode()
        if replacement != original:
            changes.append((path, original, replacement, mode))
    module_bytes = args.module.read_bytes()
    if not module_bytes.startswith(b'\x7fELF'):
        raise ValueError('Build the native PAM module first')
    print(f'Account: {account.pw_name} ({account.pw_uid})\nPhone: {credential["device_id"]}\nCredential fingerprint: {credential["key_sha256"]}')
    print(f'PAM services: {", ".join(services)}\nRule: {RULE.strip()}\nPassword, account and session rules are retained.')
    prelogin = bool(set(services) & {'login', 'gdm-password', 'gdm-fingerprint'})
    if prelogin:
        print('Before-login support enables Unity user-service startup and loginctl linger. The user home and a Wi-Fi/Bluetooth route must be available before login.')
    if not args.apply:
        print('Review complete; no system changes made. Use --apply to enroll, test, then enable.')
        return
    if os.geteuid() != 0:
        parser.error('--apply requires an administrator password through sudo')
    confirmation = args.confirm_key_sha256 or input('Enter the full credential fingerprint shown on your PHONE: ').strip()
    if confirmation != credential['key_sha256']:
        raise ValueError('Phone fingerprint confirmation did not match; no changes made')
    root_directory(CONFIG, 0o700)
    root_directory(MODULE.parent)
    root_directory(BACKUPS, 0o700)
    backup = BACKUPS / f'{time.time_ns()}-{account.pw_uid}'
    backup.mkdir(mode=0o700)
    old_config = config_path.read_bytes() if config_path.exists() else None
    old_module = MODULE.read_bytes() if MODULE.exists() else None
    for path, original, _, _ in changes:
        atomic(backup / path.name, original, 0o600)
    if old_config:
        atomic(backup / 'enrollment.json', old_config, 0o600)
    if old_module:
        atomic(backup / 'pam_unity.so', old_module, 0o600)
    applied = []
    linger_added = False
    had_linger = Path("/var/lib/systemd/linger", account.pw_name).exists()
    try:
        atomic(MODULE, module_bytes, 0o755)
        test_config = dict(config, services=services + ['unity-pam-test'])
        atomic(config_path, (json.dumps(test_config) + '\n').encode(), 0o600)
        if Path('/usr/sbin/restorecon').exists():
            subprocess.run(['/usr/sbin/restorecon', str(MODULE), str(config_path)], check=True)
        pam_self_test(account.pw_name)
        atomic(config_path, (json.dumps(config, indent=2) + '\n').encode(), 0o600)
        # Enable service startup before touching real PAM service files.
        if prelogin:
            subprocess.run(['/usr/bin/loginctl', 'enable-linger', account.pw_name], check=True)
            linger_added = not had_linger
        subprocess.run(['/usr/sbin/runuser', '-u', account.pw_name, '--', '/usr/bin/env', f'XDG_RUNTIME_DIR=/run/user/{account.pw_uid}',
                        f'DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/{account.pw_uid}/bus', '/usr/bin/systemctl', '--user', 'enable', '--now', 'unityd.service'], check=True)
        for path, original, replacement, mode in changes:
            if path.read_bytes() != original:
                raise RuntimeError(f'{path} changed during setup; refusing to overwrite it')
            atomic(path, replacement, mode)
            applied.append((path, original, mode))
    except BaseException:
        if linger_added:
            subprocess.run(['/usr/bin/loginctl', 'disable-linger', account.pw_name], check=False)
        for path, original, mode in reversed(applied):
            atomic(path, original, mode)
        if old_config is None:
            config_path.unlink(missing_ok=True)
        else:
            atomic(config_path, old_config, 0o600)
        if old_module is None:
            MODULE.unlink(missing_ok=True)
        else:
            atomic(MODULE, old_module, 0o755)
        raise
    print(f'Phone fingerprint authentication enabled. Backups: {backup}\nKeep this terminal open and test a new sudo/login request. Password fallback remains available.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
