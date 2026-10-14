#!/usr/bin/python3
"""Test-only PulseAudio tools. Never connects to or changes the desktop sound server."""
import json
import os
from pathlib import Path
import sys
import time

state_file = Path(os.environ['UNITY_TEST_AUDIO_STATE'])
if Path(sys.argv[0]).name == 'parec':
    if state_file.with_suffix('.fail-capture').exists():
        sys.exit(1)
    # Deterministic stereo PCM, one packet per 10 ms.
    pcm = bytes(range(256)) * 7 + bytes(range(128))
    while True:
        sys.stdout.buffer.write(pcm); sys.stdout.buffer.flush(); time.sleep(.01)

state = json.loads(state_file.read_text())
args = sys.argv[1:]
if args == ['get-default-sink']:
    print(state['default'])
elif args == ['list', 'short', 'sinks']:
    for index, name in state['sinks'].items():
        print(f'{index}\t{name}\tmodule\ts16le 2ch 48000Hz\tRUNNING')
elif args == ['list', 'short', 'sink-inputs']:
    for index, sink in state['inputs'].items():
        print(f'{index}\t{sink}\tclient\tprotocol\ts16le')
elif args == ['list', 'short', 'modules']:
    if state.get('route'):
        print(f"77\tmodule-null-sink\tsink_name={state['route']}")
elif args[0] == 'load-module':
    if state_file.with_suffix('.fail-route').exists():
        sys.stderr.write('Audio server unavailable'); sys.exit(1)
    name = next(arg.split('=', 1)[1] for arg in args if arg.startswith('sink_name='))
    state['sinks']['3'] = name; state['route'] = name; print('77')
elif args[0] == 'set-default-sink':
    assert args[1] in state['sinks'].values()
    state['default'] = args[1]
elif args[0] == 'move-sink-input':
    assert args[1] in state['inputs']
    state['inputs'][args[1]] = next(index for index, name in state['sinks'].items() if name == args[2])
elif args[0] == 'unload-module':
    assert args[1] == '77'
    state['sinks'].pop('3'); state.pop('route', None)
else:
    raise AssertionError(args)
# Readers must never observe a truncated intermediate JSON state during restore.
if args[0] not in ('get-default-sink', 'list'):
    temporary = state_file.with_name(state_file.name + f'.{os.getpid()}.tmp')
    temporary.write_text(json.dumps(state))
    temporary.replace(state_file)
