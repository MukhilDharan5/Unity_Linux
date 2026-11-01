import assert from 'node:assert/strict';
import path from 'node:path';
import {mkdir,writeFile} from 'node:fs/promises';
import {startFirefox,wait} from './marionette.mjs';
import {createFixtureServer} from '../fixtures/server.mjs';
const fixture=await createFixtureServer();
const run=await startFirefox({binary:process.env.FIREFOX_BINARY||undefined,profile:path.resolve('.test-profiles/integration-'+Date.now()),port:2838});
const results=[];
const c=run.client;
const invoke=body=>c.asyncScript(`const done=arguments[arguments.length-1];(async()=>{
 const pause=ms=>new Promise(r=>setTimeout(r,ms));
 const until=async fn=>{const end=Date.now()+20000;while(Date.now()<end){const v=await fn();if(v)return v;await pause(100);}throw new Error('Fixture condition timed out');};
 const state=async()=>{const d=await browser.storage.local.get('sessionContinuity.repository.v1');return d['sessionContinuity.repository.v1'];};
 const page=async(tabId,func,args=[])=>{const r=await browser.scripting.executeScript({target:{tabId},func,args});return r[0].result;};
 ${body}
 })().then(done,e=>done({testError:String(e),stack:e.stack}));`);
async function check(name,fn){try{await fn();results.push({name,status:'passed'});console.log('PASS',name);}catch(error){results.push({name,status:'failed',error:error.message});console.log('FAIL',name,error.message);throw error;}}
try{
 console.log('Firefox',run.session.capabilities.browserVersion);
 await c.call('Addon:Install',{path:path.resolve('extension'),temporary:true});
 await c.context('chrome');const ids=await c.script('return Services.prefs.getStringPref("extensions.webextensions.uuids");');const uuid=JSON.parse(ids)['session-continuity@local.invalid'];await c.context('content');await c.navigate(`moz-extension://${uuid}/ui/options.html`);
 await check('Extension installs and options UI loads with Browser Local Storage',async()=>{assert.deepEqual(await c.script('return [document.title, typeof browser];'),['Session Continuity · Settings','object']);const s=await invoke('return browser.runtime.sendMessage({type:"STATUS"});');assert.equal(s.backend,'Browser Local Storage');assert.equal(s.nativeAvailable,false);});
 await check('Actual content script captures three finite players and 75% scroll',async()=>{
  const result=await invoke(`
    const tab=await browser.tabs.create({url:${JSON.stringify(fixture.url+'/fixture?route=a#reading')},active:true});window.fixtureTab=tab.id;
    await until(()=>page(tab.id,()=>document.getElementById('audio-a')?.readyState>=1&&document.getElementById('video-a')?.readyState>=1).catch(()=>false));
    await pause(2800);
    await page(tab.id,()=>{document.getElementById('audio-a').currentTime=755;document.getElementById('audio-b').currentTime=123;document.getElementById('video-a').currentTime=42;const n=document.getElementById('nested');n.scrollTo(170,1200);window.scrollTo(0,(document.scrollingElement.scrollHeight-innerHeight)*.75);});
    await pause(600);await browser.runtime.sendMessage({type:'SAVE_NOW'});
    const saved=await until(async()=>{const s=await state();return Object.values(s?.contexts||{}).find(x=>x.records.media.length===3&&Math.abs(x.records.media[0].time-755)<2&&x.records.scroll[0]?.progressY>.73);});
    window.savedId=saved.id;return saved;
  `);assert.ok(!result.testError,JSON.stringify(result));assert.equal(result.records.media.length,3);assert.ok(Math.abs(result.records.scroll[0].progressY-.75)<.02);assert.equal(result.records.forms.length,0);
 });
 await check('Approved forms captured; password/OTP/payment/recovery excluded',async()=>{
  const r=await invoke(`await browser.runtime.sendMessage({type:'SETTINGS',patch:{formOrigins:[${JSON.stringify(fixture.url)}]}});await pause(300);
   await page(window.fixtureTab,()=>{document.getElementById('notes').value='A harmless reading draft';document.getElementById('remember').checked=true;document.querySelector('input[name=color][value=green]').checked=true;document.getElementById('theme').value='dark';document.getElementById('password').value='Never capture me';document.getElementById('otp').value='123456';});
   await browser.runtime.sendMessage({type:'SAVE_NOW'});return until(async()=>{const s=(await state()).contexts[window.savedId];return s.records.forms.some(f=>f.field.value==='notes'&&f.value)?s:null;});`);
  assert.ok(!r.testError,JSON.stringify(r));assert.ok(r.records.forms.some(f=>f.value==='A harmless reading draft'));assert.ok(!r.records.forms.some(f=>['password','otp','payment','seed','hidden'].includes(f.field.value)));
 });
 await check('Duplicate URLs create independent logical records',async()=>{
  const r=await invoke(`const t=await browser.tabs.create({url:${JSON.stringify(fixture.url+'/fixture?route=a#reading')},active:false});window.duplicateTab=t.id;await pause(3000);const s=await state();return Object.values(s.contexts).filter(x=>x.page.url===${JSON.stringify(fixture.url+'/fixture?route=a#reading')}).map(x=>({id:x.id,tab:x.logicalTabId}));`);assert.equal(r.length,2);assert.notEqual(r[0].id,r[1].id);assert.notEqual(r[0].tab,r[1].tab);
 });
 await check('Background media checkpoints do not steal last-active selection',async()=>{const r=await invoke(`const before=(await state()).activeId;await page(window.duplicateTab,()=>{document.getElementById('audio-a').currentTime=99;});await pause(1200);return {before,after:(await state()).activeId,savedId:window.savedId};`);assert.equal(r.before,r.savedId);assert.equal(r.after,r.savedId);});
 await check('Manual restore reuses the logically identified tab and restores components',async()=>{
  const r=await invoke(`
   const saved=(await state()).contexts[window.savedId];window.frozenSaved=saved;
   // Freeze captures by starting restore before changing the fixture via the test harness.
   const start=await browser.runtime.sendMessage({type:'RESTORE_LAST',id:saved.id});
   await page(window.fixtureTab,()=>{document.getElementById('audio-a').currentTime=0;document.getElementById('audio-b').currentTime=0;document.getElementById('video-a').currentTime=0;document.getElementById('notes').value='';document.getElementById('remember').checked=false;document.querySelector('input[name=color][value=green]').checked=false;document.getElementById('theme').value='';window.scrollTo(0,0);document.getElementById('nested').scrollTo(0,0);});
   const status=await until(async()=>{const s=await browser.runtime.sendMessage({type:'STATUS'});return s.restore?.results?s:null;});
   const values=await page(window.fixtureTab,()=>({a:document.getElementById('audio-a').currentTime,b:document.getElementById('audio-b').currentTime,v:document.getElementById('video-a').currentTime,notes:document.getElementById('notes').value,populated:document.getElementById('populated').value,scroll:scrollY/(document.scrollingElement.scrollHeight-innerHeight),nested:document.getElementById('nested').scrollTop,submitted:Number(document.body.dataset.submitted)}));
   return {start,status:status.restore,values,tabs:(await browser.tabs.query({})).length};
  `);assert.ok(!r.testError,JSON.stringify(r));console.log('RESTORE',JSON.stringify(r));assert.ok(Math.abs(r.values.a-755)<=1.5);assert.ok(Math.abs(r.values.b-123)<=1.5);assert.ok(Math.abs(r.values.v-42)<=1.5);assert.ok(Math.abs(r.values.scroll-.75)<.03);assert.equal(r.values.notes,'A harmless reading draft');assert.equal(r.values.populated,'Website supplied text');assert.equal(r.values.submitted,0);assert.equal(r.tabs,3);
 });
 await check('Repeated restoration causes no additional seeks or duplicate tabs',async()=>{const r=await invoke(`await page(window.fixtureTab,()=>{document.body.dataset.repeatSeeks='0';document.querySelectorAll('audio,video').forEach(el=>el.addEventListener('seeking',()=>{document.body.dataset.repeatSeeks=String(Number(document.body.dataset.repeatSeeks)+1);}));});await browser.runtime.sendMessage({type:'RESTORE_LAST',id:window.savedId});await pause(1500);return {seeks:await page(window.fixtureTab,()=>Number(document.body.dataset.repeatSeeks)),tabs:(await browser.tabs.query({})).length};`);assert.equal(r.seeks,0);assert.equal(r.tabs,3);});
 await check('Trusted user wheel input cancels pending reading restoration',async()=>{
  await invoke(`await browser.runtime.sendMessage({type:'SETTINGS',patch:{tracking:false}});await page(window.fixtureTab,()=>{document.body.dataset.originalFixture='true';document.getElementById('chapters').replaceChildren();window.scrollTo(0,0);});await browser.runtime.sendMessage({type:'SETTINGS',patch:{tracking:true}});await browser.runtime.sendMessage({type:'RESTORE_LAST',id:window.savedId});await pause(500);return true;`);
  const all=await c.call('WebDriver:GetWindowHandles'),handles=all.value||all;const original=await c.call('WebDriver:GetWindowHandle');
  for(const handle of handles){await c.call('WebDriver:SwitchToWindow',{handle});if(await c.script('return document.body?.dataset.originalFixture === "true";'))break;}
  await c.call('WebDriver:PerformActions',{actions:[{type:'wheel',id:'reading-wheel',actions:[{type:'scroll',origin:'viewport',x:300,y:300,deltaX:0,deltaY:250,duration:100}]}]});
  await c.call('WebDriver:SwitchToWindow',{handle:original.value||original});
  const result=await invoke(`return until(async()=>{const s=await browser.runtime.sendMessage({type:'STATUS'});return s.restore?.results?s.restore.results:null;});`);
  assert.equal(result.scroll.status,'partial');assert.equal(result.scroll.cancelled,1);assert.equal(result.media.status,'completed');
  const y=await invoke(`await pause(1500);return page(window.fixtureTab,()=>scrollY);`);assert.ok(Math.abs(y-250)<10);
 });
 await check('SPA navigation cannot mix routes or player identities',async()=>{const r=await invoke(`await page(window.fixtureTab,()=>{document.getElementById('route-b').click();});await pause(1500);await browser.tabs.update(window.fixtureTab,{active:true});await browser.runtime.sendMessage({type:'SAVE_NOW'});return until(async()=>{const s=await state();const next=Object.values(s.contexts).find(x=>x.page.route.includes('route=b'));return next?.records.media.length?{next,old:s.contexts[window.savedId]}:null;});`);assert.ok(!r.testError,JSON.stringify(r));assert.notEqual(r.next.id,r.old.id);assert.ok(r.next.records.media.some(m=>m.key.endsWith('episode-new')&&m.time<1));assert.ok(r.old.records.media.some(m=>m.key.endsWith('episode-a')&&Math.abs(m.time-755)<2));});
 await check('Real Firefox containers keep same-URL records separate',async()=>{const r=await invoke(`const container=await browser.contextualIdentities.create({name:'Continuity test',color:'blue',icon:'circle'});await browser.tabs.create({url:${JSON.stringify(fixture.url+'/fixture?route=a#reading')},cookieStoreId:container.cookieStoreId,active:false});await pause(3000);const saved=Object.values((await state()).contexts).find(s=>s.container===container.cookieStoreId);return {container:container.cookieStoreId,saved,originalId:window.savedId};`);assert.ok(r.saved);assert.equal(r.saved.container,r.container);assert.notEqual(r.saved.id,r.originalId);});
 await check('Form revocation purges saved values and stops subsequent captures',async()=>{const r=await invoke(`await browser.runtime.sendMessage({type:'SETTINGS',patch:{formOrigins:[]}});await browser.runtime.sendMessage({type:'SAVE_NOW'});await pause(500);return Object.values((await state()).contexts).map(s=>s.records.forms.length);`);assert.ok(r.every(n=>n===0));});
 await check('Site deletion fences capture and clear-all pauses tracking',async()=>{const r=await invoke(`await browser.runtime.sendMessage({type:'DELETE_SITE',origin:${JSON.stringify(fixture.url)}});await browser.tabs.sendMessage(window.fixtureTab,{type:'SNAPSHOT'});await pause(600);const siteCount=Object.keys((await state()).contexts).length;await browser.runtime.sendMessage({type:'CLEAR_ALL'});return {siteCount,tracking:(await state()).settings.tracking};`);assert.equal(r.siteCount,0);assert.equal(r.tracking,false);});
}catch(error){console.error(error.message);console.log('DIAGNOSTIC',JSON.stringify(await invoke(`return {permissions:await browser.permissions.getAll(),frame:await browser.webNavigation.getFrame({tabId:window.fixtureTab,frameId:0}),connect:await browser.tabs.sendMessage(window.fixtureTab,{type:'CONNECT'}).catch(e=>String(e)),storage:await state(),runtime:await browser.storage.session.get(null),page:window.fixtureTab?await page(window.fixtureTab,()=>({ready:document.readyState,players:[...document.querySelectorAll('video,audio')].map(e=>({id:e.id,ready:e.readyState,error:e.error?.code,time:e.currentTime,duration:e.duration})),scroll:scrollY})):null};`).catch(e=>({error:String(e)}))));await c.context('chrome');console.log('BROWSER ERRORS',await c.script('return Services.console.getMessageArray().map(x=>x.message).filter(x=>/continuity|moz-extension/.test(x)).slice(-15);'));console.log('FIREFOX LOG',run.logs().slice(-1500));process.exitCode=1;}
finally{await mkdir('test-results',{recursive:true});await writeFile('test-results/firefox-integration.json',JSON.stringify({version:run.session.capabilities.browserVersion,buildID:run.session.capabilities['moz:buildID'],profile:run.profile,temporaryInstall:true,results},null,2));await run.stop();fixture.server.close();}
