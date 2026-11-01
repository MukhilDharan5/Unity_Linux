import path from 'node:path';
import {writeFile,mkdir} from 'node:fs/promises';
import {startFirefox,wait} from './marionette.mjs';
const run=await startFirefox({profile:path.resolve('.test-profiles/youtube-'+Date.now()),port:2840});const c=run.client;
let result={version:run.session.capabilities.browserVersion,url:'https://www.youtube.com/watch?v=XEzRZ35urlk',status:'unverified'};
const invoke=body=>c.asyncScript(`const done=arguments[arguments.length-1];(async()=>{const pause=ms=>new Promise(r=>setTimeout(r,ms));const page=async func=>(await browser.scripting.executeScript({target:{tabId:window.youtubeTab},func}))[0].result;${body}})().then(done,e=>done({error:String(e)}));`);
try{
 await c.call('Addon:Install',{path:path.resolve('extension'),temporary:true});await c.context('chrome');const ids=JSON.parse(await c.script('return Services.prefs.getStringPref("extensions.webextensions.uuids");'));await c.context('content');await c.navigate(`moz-extension://${ids['session-continuity@local.invalid']}/ui/options.html`);
 await invoke(`const tab=await browser.tabs.create({url:${JSON.stringify(result.url)},active:true});window.youtubeTab=tab.id;return true;`);
 await wait(15000);
 const availability=await invoke(`return page(()=>({url:location.href,title:document.title,playerId:document.querySelector('ytd-watch-flexy')?.getAttribute('video-id'),ad:!!document.querySelector('.ad-showing,.ad-interrupting'),error:document.querySelector('.ytp-error-content-wrap-reason')?.textContent,media:[...document.querySelectorAll('video')].map(v=>({duration:v.duration,ready:v.readyState,seekable:v.seekable.length,time:v.currentTime}))}));`);
 result.availability=availability;
 if(availability.media?.some(v=>v.ready>=1&&v.duration>756&&v.seekable>0)&&availability.playerId==='XEzRZ35urlk'&&!availability.ad){
  const captured=await invoke(`await page(()=>{const v=document.querySelector('video');v.pause();v.currentTime=755;});await pause(1200);await browser.runtime.sendMessage({type:'SAVE_NOW'});await pause(700);const s=await browser.storage.local.get('sessionContinuity.repository.v1');const saved=Object.values(s['sessionContinuity.repository.v1'].contexts).find(x=>x.records.media.some(m=>m.key==='youtube:XEzRZ35urlk'&&Math.abs(m.time-755)<2));if(!saved)return {error:'YouTube identity did not produce a committed record'};window.savedYoutube=saved.id;await browser.runtime.sendMessage({type:'RESTORE_LAST',id:saved.id});await page(()=>{document.querySelector('video').currentTime=0;});await pause(14000);return {finalMedia:await page(()=>{const v=document.querySelector('video');return {seeking:v.seeking,ready:v.readyState,networkState:v.networkState,error:v.error?.code,watchId:document.querySelector('ytd-watch-flexy')?.getAttribute('video-id'),ad:!!document.querySelector('.ad-showing,.ad-interrupting')};}),record:saved.records.media,position:await page(()=>document.querySelector('video').currentTime),status:(await browser.runtime.sendMessage({type:'STATUS'})).restore};`);
  result.capture=captured;result.status=Math.abs(captured.position-755)<=2&&captured.status?.results?.media?.status==='completed'?'passed':'partial';
 }else{result.blocker=availability.error||availability.error=== ''?'YouTube did not expose playable content':availability.title||availability.error||'Content metadata, seekable range, or reliable video identity unavailable in the isolated headless profile';}
 console.log(JSON.stringify(result,null,2));
}catch(error){result.blocker=error.message;console.log(JSON.stringify(result,null,2));}
finally{await mkdir('test-results',{recursive:true});await writeFile('test-results/firefox-youtube.json',JSON.stringify(result,null,2));await run.stop();}
