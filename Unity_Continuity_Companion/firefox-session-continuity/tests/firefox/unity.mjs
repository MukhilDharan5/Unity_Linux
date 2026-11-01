// Linux end-to-end native messaging. Run under dbus-run-session after building unityd/unityctl.
// Native manifests are redirected inside this isolated Firefox process only.
import assert from 'node:assert/strict';
import {execFileSync,spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {startFirefox,until,wait} from './marionette.mjs';
import {createFixtureServer} from '../fixtures/server.mjs';

const linux=path.resolve('../../Unity_Linux');
const temp=await mkdtemp(path.join(os.tmpdir(),'unity-firefox-browser-'));
const prefix=path.join(temp,'prefix'),native=path.join(temp,'native'),state=path.join(temp,'state');
let daemon,run,fixture;
let daemonLogs='';
const cli=(...args)=>execFileSync(path.join(prefix,'bin/unityctl'),['firefox',...args],{encoding:'utf8',timeout:10000}).trim();
const nativeStatus=()=>JSON.parse(cli('status'));
async function startDaemon(){
  daemon=spawn(path.join(prefix,'libexec/unity/unityd'),['--state-dir',state,'--bind','127.0.0.1','--port','0'],{stdio:['ignore','pipe','pipe']});
  daemon.stdout.on('data',d=>{daemonLogs+=d;});daemon.stderr.on('data',d=>{daemonLogs+=d;});
  // Check the bus itself before invoking unityctl, so a restart cannot activate
  // a separately installed production service on the isolated test bus.
  await until(()=>execFileSync('gdbus',['call','--session','--dest','org.freedesktop.DBus','--object-path','/org/freedesktop/DBus','--method','org.freedesktop.DBus.NameHasOwner','org.unityconnect'],{encoding:'utf8'}).includes('true'));
  await until(()=>{try{return nativeStatus();}catch{return false;}});
}
async function stopDaemon(){if(daemon?.exitCode===null){daemon.kill('SIGTERM');await new Promise(resolve=>daemon.once('exit',resolve));}}
try {
  execFileSync('python3',[path.join(linux,'scripts/install.py'),'--debug','--prefix',prefix,'--firefox-manifest-dir',path.join(native,'native-messaging-hosts')],{stdio:'pipe'});
  await startDaemon();fixture=await createFixtureServer();
  process.env.UNITY_FIREFOX_STATE_DIR=state;
  run=await startFirefox({binary:process.env.FIREFOX_BINARY||'/usr/bin/firefox',profile:path.resolve('.test-profiles/unity-'+Date.now()),port:2848});
  const c=run.client;
  await c.context('chrome');
  await c.script(`const root=Cc['@mozilla.org/file/local;1'].createInstance(Ci.nsIFile);root.initWithPath(arguments[0]);Services.dirsvc.set('XREUserNativeManifests',root);return true;`,[native]);
  await c.call('Addon:Install',{path:path.resolve(process.env.UNITY_FIREFOX_XPI||'extension'),temporary:true});
  const ids=JSON.parse(await c.script('return Services.prefs.getStringPref("extensions.webextensions.uuids");'));
  await c.context('content');await c.navigate(`moz-extension://${ids['session-continuity@local.invalid']}/ui/options.html`);
  const invoke=body=>c.asyncScript(`const done=arguments[arguments.length-1];(async()=>{${body}})().then(done,e=>done({testError:String(e)}));`);
  const extensionStatus=()=>invoke('return browser.runtime.sendMessage({type:"STATUS"});');
  assert.equal((await extensionStatus()).unity.enabled,false);
  assert.deepEqual(await invoke('return browser.runtime.sendMessage({type:"UNITY_CONNECTION",enabled:true});'),{ok:true});
  await until(async()=>(await extensionStatus()).unity.connected);
  assert.equal((await extensionStatus()).unity.serverEnabled,false);
  cli('enable');await until(async()=>(await extensionStatus()).unity.syncedAt);
  console.log('PASS: real Firefox connectNative handshake and Linux opt-in');
  const tab=await invoke(`const tab=await browser.tabs.create({url:${JSON.stringify(fixture.url+'/fixture?route=a#reading')},active:true});window.unityTab=tab.id;return tab.id;`);
  await until(async()=>invoke(`try{const [r]=await browser.scripting.executeScript({target:{tabId:${tab}},func:()=>document.getElementById('video-a')?.readyState>=1});return r.result;}catch{return false;}`));
  await wait(2600);
  await invoke(`await browser.scripting.executeScript({target:{tabId:${tab}},func:()=>{document.getElementById('video-a').currentTime=42;window.scrollTo(0,(document.scrollingElement.scrollHeight-innerHeight)*.75);}});return true;`);
  const page=await until(()=>nativeStatus().profiles.flatMap(p=>p.summary.contexts||[]).find(p=>p.media.some(m=>m.kind==='video'&&Math.abs(m.time-42)<1)&&p.scrollProgress>.73));
  const profile=nativeStatus().profiles[0].id;
  console.log('PASS: actual page capture reaches unityd with media and reading summaries');
  await invoke(`await browser.runtime.sendMessage({type:'SETTINGS',patch:{tracking:false}});await browser.scripting.executeScript({target:{tabId:${tab}},func:()=>{document.getElementById('video-a').currentTime=0;window.scrollTo(0,0);}});await browser.runtime.sendMessage({type:'SETTINGS',patch:{tracking:true}});return true;`);
  const command=cli('restore',profile,page.id);
  await until(()=>nativeStatus().profiles[0]?.last_result?.commandId===command&&nativeStatus().profiles[0]?.last_result?.note==='Restoration started. Existing tabs retain their focus.');
  const result=await until(async()=>{const s=await extensionStatus();return ['completed','partial'].includes(s.restore?.state)&&s.restore;});
  assert.ok(['completed','partial'].includes(result.state),JSON.stringify(result));
  const restored=await invoke(`const [r]=await browser.scripting.executeScript({target:{tabId:${tab}},func:()=>({time:document.getElementById('video-a').currentTime,progress:scrollY/(document.scrollingElement.scrollHeight-innerHeight)})});return r.result;`);
  assert.ok(Math.abs(restored.time-42)<1,JSON.stringify(restored));assert.ok(Math.abs(restored.progress-.75)<.03,JSON.stringify(restored));
  console.log('PASS: Unity desktop request restores real Firefox media and scroll positions');
  cli('disable');await until(async()=>!(await extensionStatus()).unity.serverEnabled);
  assert.deepEqual(nativeStatus().profiles[0].summary,{});
  cli('enable');await until(()=>nativeStatus().profiles[0]?.summary?.contexts?.length);
  await stopDaemon();await until(async()=>!(await extensionStatus()).unity.connected);
  await startDaemon();
  await invoke(`await browser.alarms.create('continuity-unity-reconnect',{when:Date.now()+200});return true;`);
  await until(async()=>(await extensionStatus()).unity.syncedAt);
  assert.ok(nativeStatus().profiles[0].summary.contexts.length);
  console.log('PASS: disabled-state erasure and reconnect after a real daemon restart');
  await invoke(`return browser.runtime.sendMessage({type:'DELETE_SITE',origin:${JSON.stringify(fixture.url)}});`);
  await until(()=>nativeStatus().profiles[0]?.summary?.contexts?.length===0);
  await invoke(`return browser.runtime.sendMessage({type:'UNITY_CONNECTION',enabled:false});`);
  await until(()=>nativeStatus().profiles.length===0);
  console.log('PASS: deletion synchronization and extension-side disconnect erasure');
  console.log('Firefox',run.session.capabilities.browserVersion);
} catch(error) {
  console.error(error.stack);console.error(daemonLogs.slice(-2000));if(run)console.error(run.logs().slice(-2000));process.exitCode=1;
} finally {
  await run?.stop();fixture?.server.close();await stopDaemon();await rm(temp,{recursive:true,force:true});
}
