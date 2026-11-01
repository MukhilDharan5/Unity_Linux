import test from 'node:test';
import assert from 'node:assert/strict';
import {UnityCompanion} from '../extension/src/background/unity.js';
import {LocalRepository} from '../extension/src/storage/repository.js';
import {MemoryArea,snapshot,mediaRecord,scrollData} from './helpers.mjs';

const KEY='sessionContinuity.unity.v1';
const flush=()=>new Promise(resolve=>setImmediate(resolve));
class Event {
  listeners=[];
  addListener(fn){this.listeners.push(fn);}
  emit(message){for(const fn of this.listeners)fn(message);}
}
class Port {
  onMessage=new Event();onDisconnect=new Event();sent=[];
  postMessage(value){this.sent.push(structuredClone(value));}
  disconnect(){this.closed=true;this.onDisconnect.emit();}
}
async function setup() {
  const area=new MemoryArea(),repo=new LocalRepository(area),ports=[],alarms=[];
  await repo.initialize();
  const b={storage:{local:area},runtime:{connectNative:name=>{assert.equal(name,'org.unityconnect.firefox');const port=new Port();ports.push(port);return port;}},alarms:{create:(...args)=>alarms.push(args),clear:async name=>alarms.push(['clear',name])}};
  const calls=[],controller={repo,runtime:{restore:{state:'idle'}},queue:fn=>Promise.resolve().then(fn),ui:async message=>{calls.push(message);return {ok:true,note:'Restoration started'};}};
  const bridge=new UnityCompanion(b,controller);repo.onCommit=()=>bridge.scheduleSync();
  await bridge.initialize();return {area,repo,ports,alarms,calls,controller,bridge};
}

test('Unity connection is opt-in and profile identity survives restarts',async()=>{
  const s=await setup();assert.equal(s.ports.length,0);assert.equal(s.bridge.status().enabled,false);
  const identity=s.bridge.profileId;
  const second=new UnityCompanion(s.bridge.b,s.controller);await second.initialize();assert.equal(second.profileId,identity);
  await s.bridge.setEnabled(true);assert.equal(s.ports.length,1);assert.deepEqual(s.ports[0].sent[0],{type:'hello',version:1,profileId:identity});
});

test('Linux must enable support before summaries leave Firefox; full forms never leave',async()=>{
  const s=await setup(),saved=snapshot();saved.records.media=[mediaRecord];saved.records.scroll=[scrollData];
  // Summary generation uses only counts, even if future records gain more fields.
  await s.repo.save(saved,{active:true});
  s.repo.state.contexts[saved.id].records.forms=[{value:'LOCAL-ONLY-FORM-VALUE'}];
  await s.bridge.setEnabled(true);const port=s.ports[0];
  port.onMessage.emit({type:'hello',version:1,enabled:false});await flush();
  assert.equal(port.sent.length,1);assert.equal(s.bridge.status().serverEnabled,false);
  port.onMessage.emit({type:'hello',version:1,enabled:true});await flush();
  const sync=port.sent.at(-1);assert.equal(sync.type,'sync');assert.equal(sync.summary.activeId,saved.id);
  assert.deepEqual(sync.summary.contexts[0].media,[{kind:'audio',time:755}]);
  assert.equal(sync.summary.contexts[0].scrollProgress,.75);assert.equal(sync.summary.contexts[0].formCount,1);
  assert.ok(!JSON.stringify(sync).includes('LOCAL-ONLY-FORM-VALUE'));assert.ok(!JSON.stringify(sync).includes('documentToken'));
  port.onMessage.emit({type:'synced',sequence:sync.sequence-1});await flush();assert.equal(s.bridge.syncedAt,null);
  port.onMessage.emit({type:'synced',sequence:sync.sequence});await flush();assert.ok(s.bridge.syncedAt);
});

test('deletion replaces the native view and native failures do not reject browser saves',async()=>{
  const s=await setup();await s.bridge.setEnabled(true);const port=s.ports[0];
  port.onMessage.emit({type:'hello',version:1,enabled:true});await flush();
  const saved=snapshot();await s.repo.save(saved,{active:true});await flush();
  assert.equal(port.sent.at(-1).summary.contexts.length,1);
  await s.repo.clear();await flush();assert.deepEqual(port.sent.at(-1).summary.contexts,[]);
  assert.equal(port.sent.at(-1).summary.tracking,false);
  port.error={message:'Daemon stopped'};port.onDisconnect.emit();await flush();
  await s.repo.changePolicy({tracking:true});await s.repo.save(snapshot());
  assert.equal((await s.repo.list()).length,1);assert.equal(s.bridge.status().connected,false);
  await s.bridge.alarm('continuity-unity-reconnect');assert.equal(s.ports.length,2);
  const second=s.ports[1];second.onMessage.emit({type:'hello',version:1,enabled:true});await flush();
  assert.equal(second.sent.at(-1).summary.contexts.length,1);
});

test('desktop restore requests use existing controller checks and are acknowledged',async()=>{
  const s=await setup();await s.bridge.setEnabled(true);const port=s.ports[0];
  port.onMessage.emit({type:'restore',commandId:'command-1',contextId:'context-1'});await flush();assert.equal(s.calls.length,0);
  port.onMessage.emit({type:'hello',version:1,enabled:true});await flush();
  port.onMessage.emit({type:'restore',commandId:'command-1',contextId:'context-1'});await flush();
  assert.deepEqual(s.calls,[{type:'RESTORE_LAST',id:'context-1'}]);
  assert.ok(port.sent.some(m=>m.type==='result'&&m.ok&&m.commandId==='command-1'));
  s.controller.ui=async()=>({ok:false,reason:'Restoration is disabled in settings'});
  port.onMessage.emit({type:'restore',commandId:'command-2',contextId:''});await flush();
  assert.ok(port.sent.some(m=>m.type==='result'&&!m.ok&&m.note.includes('disabled')));
  await s.bridge.setEnabled(false);assert.ok(port.closed);assert.equal(s.bridge.status().connected,false);
  port.onMessage.emit({type:'hello',version:1,enabled:true});await flush();assert.equal(s.bridge.status().connected,false);
  assert.ok(s.alarms.some(a=>a[0]==='clear'));
});

test('failed connection preference writes do not start a native process',async()=>{
  const s=await setup();s.area.fail=true;
  await assert.rejects(s.bridge.setEnabled(true));assert.equal(s.ports.length,0);assert.equal(s.bridge.enabled,false);
});
