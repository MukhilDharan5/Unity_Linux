"""Isolated capture/control fixture. Never touches the actual display or input."""
import json
from pathlib import Path
import signal
import struct
import sys
import threading
import time


def run(jpeg, directory):
    directory = Path(directory)
    panel = directory / 'fake-panel'
    log = directory / 'screen-input.jsonl'
    stop = threading.Event()
    controlled = '--control' in sys.argv
    lock = threading.Lock()
    panel.write_text('72')

    def report(value):
        with lock:
            print(json.dumps(value), file=sys.stderr, flush=True)

    def state():
        report({'type': 'state', 'input_enabled': controlled, 'display_available': controlled, 'display_on': panel.read_text() != '0', 'display_error': ''})

    def commands():
        try:
            for line in sys.stdin:
                command = json.loads(line)
                if command['action'] == 'heartbeat': continue
                with log.open('a') as output: output.write(json.dumps(command) + '\n')
                if command['action'] == 'display':
                    panel.write_text('72' if command['data']['on'] else '0')
                    state()
                report({'type': 'reply', 'id': command['id'], 'ok': True, 'result': {'state': 'sent'}})
        finally:
            stop.set()

    for number in (signal.SIGTERM, signal.SIGINT): signal.signal(number, lambda *_: stop.set())
    threading.Thread(target=commands, daemon=True).start()
    state()
    try:
        while not stop.is_set():
            sys.stdout.buffer.write(struct.pack('>I', len(jpeg)) + jpeg)
            sys.stdout.buffer.flush()
            stop.wait(.067)
    finally:
        panel.write_text('72')
