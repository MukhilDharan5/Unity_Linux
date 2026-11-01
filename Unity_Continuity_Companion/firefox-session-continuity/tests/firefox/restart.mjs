import assert from 'node:assert/strict';
import path from 'node:path';
import {mkdir,writeFile} from 'node:fs/promises';
import {startFirefox,wait} from './marionette.mjs';
import {createFixtureServer} from '../fixtures/server.mjs';
const binary=process.env.FIREFOX_TEST_BINARY||path.resolve('.test-runtime/nightly/firefox/firefox.exe');
const fixture=await createFixtureServer();
const results=[];
let run;
async function options(client){await client.context('chrome');const raw=await client.script('return Services.prefs.getStringPref("extensions.webextensions.uuids");');const uuid=JSON.parse(raw)['session-continuity@local.invalid'];assert.ok(uuid,'Persistent extension ID exists');await client.context('content');
 // Create a separate WebDriver tab to avoid navigating the restored page.
 const created=await client.call('WebDriver:NewWindow',{type:'tab'});await client.call('WebDriver:SwitchToWindow',{handle:created.handle||created.value?.handle});await client.navigate(`moz-extension://${uuid}/ui/options.html`);
}
async function invoke(body){return run.client.asyncScript(`const done=arguments[arguments.length-1];(async()=>{const pause=ms=>new Promise(r=>setTimeout(r,ms));const until=async fn=>{const deadline=Date.now()+24000;while(Date.now()<deadline){const value=await fn();if(value)return value;await pause(100);}throw new Error('Restart condition timed out');};const state=async()=>{const d=await browser.storage.local.get('sessionContinuity.repository.v1');return d['sessionContinuity.repository.v1'];};const page=async(id,func)=>{const r=await browser.scripting.executeScript({target:{tabId:id},func});return r[0].result;};${body}})().then(done,e=>done({testError:String(e),stack:e.stack}));`);}
try{
 for(const mode of [{name:'native-session-on',native:true,crash:false},{name:'native-session-off',native:false,crash:false},{name:'unexpected-termination',native:true,crash:true}]){
  const profile=path.resolve(`.test-profiles/restart-${mode.name}-${Date.now()}`),prefs={'xpinstall.signatures.required':false,'browser.startup.page':mode.native?3:0,'browser.sessionstore.resume_from_crash':false,'browser.sessionstore.interval':1000};
  run=await startFirefox({binary,profile,port:2839,prefs});console.log('Firefox',run.session.capabilities.browserVersion,mode.name);
  await run.client.call('Addon:Install',{path:path.resolve('artifacts/session-continuity-1.1.0-unsigned.xpi'),temporary:false});
  await options(run.client);
  const saved=await invoke(`const a=await browser.tabs.create({url:${JSON.stringify(fixture.url+'/fixture?route=restart-a#reading')},active:false});const b=await browser.tabs.create({url:${JSON.stringify(fixture.url+'/fixture?route=restart-b#reading')},active:true});window.targetTab=b.id;await until(()=>page(b.id,()=>document.getElementById('audio-a')?.readyState>=1).catch(()=>false));await pause(2800);await page(b.id,()=>{document.getElementById('audio-a').currentTime=755;window.scrollTo(0,(document.scrollingElement.scrollHeight-innerHeight)*.75);});await pause(600);await browser.runtime.sendMessage({type:'SAVE_NOW'});const saved=await until(async()=>{const s=await state(),c=s?.contexts[s.activeId];return c?.page.route.includes('restart-b')&&c.records.media.some(m=>m.time===755)?c:null;});await pause(1800);return {id:saved.id,revision:saved.revision,time:saved.records.media[0].time,url:saved.page.url};`);
  assert.ok(!saved.testError,JSON.stringify(saved));const previousPid=run.session.capabilities['moz:processID'];
  if(mode.crash){const child=run.child;run.client.socket.destroy();child.kill();await Promise.race([new Promise(r=>child.once('exit',r)),wait(5000)]);run=null;}else {await run.stop();run=null;}
  await wait(1000);
  run=await startFirefox({binary,profile,port:2839,prefs});
  // Let the genuine runtime.onStartup reconcile and restore without any test-triggered restore call.
  assert.notEqual(run.session.capabilities['moz:processID'],previousPid);
  await wait(16000);await run.client.context('chrome');const activeURL=await run.client.script('return Services.wm.getMostRecentWindow("navigator:browser").gBrowser.selectedBrowser.currentURI.spec;');assert.equal(activeURL,saved.url);await options(run.client);
  const observed=await invoke(`const status=await browser.runtime.sendMessage({type:'STATUS'}),s=await state(),tabs=await browser.tabs.query({});const targets=tabs.filter(t=>t.url===${JSON.stringify(saved.url)});const values=targets.length===1?await page(targets[0].id,()=>({time:document.getElementById('audio-a')?.currentTime,progress:scrollY/(document.scrollingElement.scrollHeight-innerHeight)})):null;return {status:status.restore,latest:s.activeId,saved:s.contexts[${JSON.stringify(saved.id)}],targetCount:targets.length,values};`);
  console.log('RESTART RESULT',JSON.stringify({mode:mode.name,...observed}));
  assert.ok(!observed.testError,JSON.stringify(observed));assert.equal(observed.targetCount,1);assert.ok(Math.abs(observed.values.time-755)<=1.5);assert.ok(Math.abs(observed.values.progress-.75)<=.03);assert.ok(['completed','partial'].includes(observed.status.state));
  results.push({name:mode.name,status:'passed',version:run.session.capabilities.browserVersion,buildID:run.session.capabilities['moz:buildID'],savedRevision:saved.revision,newProcessVerified:true,activeURL,targetCount:observed.targetCount,mediaSeconds:observed.values.time,scrollProgress:observed.values.progress});
  await run.stop();run=null;
 }
}catch(error){results.push({status:'failed',error:error.message});console.error(error);process.exitCode=1;}
finally{if(run)await run.stop();fixture.server.close();await mkdir('test-results',{recursive:true});await writeFile('test-results/firefox-restart.json',JSON.stringify({unsignedOnlyInIsolatedNightlyProfile:true,results},null,2));}
