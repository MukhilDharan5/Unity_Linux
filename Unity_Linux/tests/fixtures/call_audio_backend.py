#!/usr/bin/env python3
"""Isolated pactl model for actual daemon call routing tests."""
import fcntl
import json
import os
from pathlib import Path
import sys
import time

state_file = Path(os.environ['UNITY_TEST_CALL_AUDIO'])
a = sys.argv[1:]
PHONE = 'AA:BB:CC:DD:EE:FF'
HEADSET = '11:22:33:44:55:66'
PHONE_CARD = 'bluez_card.AA_BB_CC_DD_EE_FF'
HEADSET_CARD = 'bluez_card.11_22_33_44_55_66'


def node(name, index, address=None):
    return {'name': name, 'index': index, 'description': name.replace('_', ' ').title(),
            'properties': {'api.bluez5.address': address} if address else {}}


def endpoints(s, kind):
    capture = kind == 'sources'
    result = [node('laptop_microphone' if capture else 'laptop_speaker', 1),
              node('usb_microphone' if capture else 'usb_speaker', 2)]
    if capture:
        result.append({**node('laptop_speaker.monitor', 3), 'monitor_of_sink': 1})
    if s.get('phone_connected', True) and s['profile'] == 'headset-audio-gateway':
        result.append(node('phone_source' if capture else 'phone_sink', 100, PHONE))
    if s.get('headphone_connected', True) and time.monotonic() >= s.get('headphone_ready_at', 0):
        profile = s.get('headphone_profile', 'a2dp-sink')
        epoch = s.get('headphone_epoch', 0)
        if profile.startswith('headset-head-unit'):
            result.append(node('headset_source' if capture else 'headset_sink', 200 + epoch, HEADSET))
        elif not capture and profile.startswith('a2dp-sink'):
            result.append(node('headphone_sink', 300 + epoch, HEADSET))
    return result


# Parallel pactl reads do not rewrite state. Serialise mutations and publish
# complete JSON atomically so the daemon and test reader never see partial data.
with state_file.with_suffix('.lock').open('w') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    s = json.loads(state_file.read_text())
    if a == ['list', 'short', 'modules']:
        for m in s['modules']: print(f"{m['index']}\t{m['name']}\t{m['argument']}\t")
        raise SystemExit(0)
    if a[:2] == ['--format=json', 'list']:
        kind = a[2]
        if kind == 'cards':
            cards = []
            if s.get('phone_connected', True):
                cards.append({'name': PHONE_CARD, 'active_profile': s['profile'],
                              'properties': {'device.description': 'Test phone'},
                              'profiles': {'headset-audio-gateway': {'available': 'yes'}}})
            if s.get('headphone_connected', True):
                cards.append({'name': HEADSET_CARD, 'active_profile': s.get('headphone_profile', 'a2dp-sink'),
                              'properties': {'device.description': 'Test headphones'},
                              'profiles': {'a2dp-sink': {'available': 'yes'},
                                           'headset-head-unit': {'available': 'yes'},
                                           'headset-head-unit-msbc': {'available': 'yes'}}})
            result = cards
        elif kind in ('sources', 'sinks'):
            result = endpoints(s, kind)
        elif kind == 'modules':
            result = s['modules']
        elif kind in ('source-outputs', 'sink-inputs'):
            field = 'source' if kind == 'source-outputs' else 'sink'
            result = [{'index': m['index'] + 1000, 'owner_module': str(m['index']), field: m[field],
                       'properties': {'media.name': m['tag']}} for m in s['modules']]
        else:
            raise SystemExit(1)
        print(json.dumps(result))
        raise SystemExit(0)
    if a[0].startswith('get-default-'):
        print(s.get('default_source' if a[0].endswith('source') else 'default_sink',
                    'laptop_microphone' if a[0].endswith('source') else 'laptop_speaker'))
        raise SystemExit(0)
    if a[0] == 'set-card-profile':
        key = 'profile' if a[1] == PHONE_CARD else 'headphone_profile'
        s[key] = a[2]
        if key == 'headphone_profile':
            s['headphone_epoch'] = s.get('headphone_epoch', 0) + 1
            s['headphone_ready_at'] = time.monotonic() + s.get('profile_delay', 0)
    elif a[0] == 'load-module':
        if s.get('delay_load'):
            time.sleep(.2)
        args = dict(arg.split('=', 1) for arg in a[2:])
        if s.get('fail_source') == args['source']:
            raise SystemExit('Injected loopback failure')
        sources = {n['name']: n['index'] for n in endpoints(s, 'sources')}
        sinks = {n['name']: n['index'] for n in endpoints(s, 'sinks')}
        if args['source'] not in sources or args['sink'] not in sinks:
            raise SystemExit('Missing loopback endpoint')
        index = s['next']
        s['next'] += 1
        tag = args['source_output_properties'].split('media.name=')[1]
        s['modules'].append({'index': index, 'name': a[1], 'argument': ' '.join(a[2:]), 'tag': tag,
                             'source': sources[args['source']], 'sink': sinks[args['sink']]})
        s.setdefault('loads', []).append({'source': args['source'], 'sink': args['sink'], 'index': index})
        print(index)
    elif a[0] == 'unload-module':
        if s.get('fail_unload'):
            raise SystemExit('Injected unload failure')
        s['modules'] = [m for m in s['modules'] if str(m['index']) != a[1]]
    else:
        raise SystemExit(1)
    temporary = state_file.with_suffix('.tmp')
    temporary.write_text(json.dumps(s))
    temporary.replace(state_file)
