#!/usr/bin/python3
"""Isolated pactl output-volume contract; never opens the desktop sound server."""
import fcntl,json,os,sys,time
from pathlib import Path
file=Path(os.environ['UNITY_TEST_SOUND'])
with file.with_suffix('.lock').open('w') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 state=json.loads(file.read_text());args=sys.argv[1:]
 if state.get('unavailable'):sys.stderr.write('Test sound server unavailable');sys.exit(1)
 if args==['get-default-sink']:print(state['default'])
 elif args==['--format=json','list','sinks']:
  print(json.dumps([{'index':int(index),'name':sink['name'],'description':sink['description'],'mute':sink['muted'],'volume':{'left':{'value':round(sink['volume']/100*65536)},'right':{'value':round(sink['volume']/100*65536)}}} for index,sink in state['sinks'].items()]))
 elif args[0]=='set-sink-volume':
  assert args[1] in state['sinks'];assert args[2].endswith('%')
  percent=int(args[2][:-1]);assert 0<=percent<=100
  state['sinks'][args[1]]['volume']=percent;state['sets'].append(args)
 elif args[0]=='set-sink-mute':
  assert args[1] in state['sinks'];assert args[2] in ['0','1']
  state['sinks'][args[1]]['muted']=args[2]=='1';state['sets'].append(args)
 else:raise AssertionError(args)
 if args[0].startswith('set-'):
  temp=file.with_name(file.name+f'.{os.getpid()}.tmp');temp.write_text(json.dumps(state));temp.replace(file)
