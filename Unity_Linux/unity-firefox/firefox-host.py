#!/usr/bin/python3
"""Firefox native host: bounded native-endian JSON frames to the local unityd socket.

No network listener or browser data storage. stdout is exclusively the native
messaging protocol; all diagnostics go to stderr.
"""
import json
import os
from pathlib import Path
import socket
import stat
import struct
import sys
import threading

MAX_FRAME = 512 * 1024
EXTENSION_ID = 'session-continuity@local.invalid'


def exact(source, size):
    data = bytearray()
    while len(data) < size:
        chunk = source.read(size - len(data))
        if not chunk:
            raise EOFError()
        data.extend(chunk)
    return bytes(data)


def frame(source):
    header = exact(source, 4)
    size, = struct.unpack('=I', header)
    if not 0 < size <= MAX_FRAME:
        raise ValueError('Invalid native message size')
    data = exact(source, size)
    if not isinstance(json.loads(data), dict):
        raise ValueError('Expected a JSON object')
    return header + data


def state_dir():
    # Custom state location is also useful for isolated development instances.
    return Path(os.environ.get('UNITY_FIREFOX_STATE_DIR') or
                str(Path(os.environ.get('XDG_STATE_HOME') or Path.home() / '.local/state') / 'unity'))


def main():
    # Firefox supplies manifest path and add-on ID. Direct invocation remains
    # useful for local diagnostics; the manifest restricts browser callers.
    if len(sys.argv) > 2 and sys.argv[2] != EXTENSION_ID:
        raise ValueError('Unexpected Firefox extension ID')
    directory = state_dir()
    metadata = directory.lstat()
    if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != os.getuid() or metadata.st_mode & 0o077:
        raise ValueError('Unity state directory must be private and owned by this user')
    path = directory / 'firefox.sock'
    metadata = path.lstat()
    if not stat.S_ISSOCK(metadata.st_mode) or metadata.st_uid != os.getuid() or metadata.st_mode & 0o077:
        raise ValueError('Unity Firefox socket must be private and owned by this user')
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.connect(str(path))
        _, uid, _ = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize('3i')))
        if uid != os.getuid():
            raise ValueError('Unity daemon belongs to another user')
        def forward_input():
            try:
                while True:
                    connection.sendall(frame(sys.stdin.buffer))
            except (EOFError, OSError, ValueError):
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
        threading.Thread(target=forward_input, daemon=True).start()
        with connection.makefile('rb', buffering=0) as source:
            while True:
                sys.stdout.buffer.write(frame(source))
                sys.stdout.buffer.flush()


if __name__ == '__main__':
    try:
        main()
    except EOFError:
        pass
    except (OSError, ValueError) as error:
        print(f'Unity Firefox bridge: {error}. Start Unity Linux and enable the Firefox extension in Settings.', file=sys.stderr)
        sys.exit(1)
