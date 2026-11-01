import {pageIdentity,uuid,validateSnapshot,LIMITS,copy} from '../core/model.js';
import {Registry,debounceBounded} from './dom.js';
import {captureMedia,MediaRestore} from './media.js';
import {scrollRecord,ScrollRestore} from './scroll.js';
import {captureForms,FormRestore} from './forms.js';

// Firefox content scripts are classic scripts. The development build bundles these ES modules.
if(!globalThis.__sessionContinuity) {
  globalThis.__sessionContinuity=true;
  const documentToken=uuid(),documentStartedAt=performance.timeOrigin;
  let url=pageIdentity(location.href)?.url,policy=null,sequence=0,connectPromise=null,operation=null,closed=false,connectingAgain=false;
  const edited=new WeakSet(),nested=new Set(),completed=new Map();
  let touched={scroll:0,media:0,forms:0};
  let guarded={media:false,scroll:false,forms:false};
  const activity=new AbortController();
  let lastGesture=0;
  const safeSend=m=>browser.runtime.sendMessage(m).catch(()=>({ok:false,reason:'Extension unavailable'}));
  const registry=new Registry(document,()=>{operation?.changed();});
  const captureSettled=debounceBounded(()=>capture(['scroll','forms']),500,2000);
  const captureMediaSettled=debounceBounded(()=>capture(['media']),150,1000);
  const connect=async()=>{
    if(closed)return;
    if(connectPromise){connectingAgain=true;return connectPromise;}
    connectPromise=(async()=>{
      const next=pageIdentity(location.href)?.url;
      if(!next){policy=null;operation?.cancelAll('Page is ineligible');return;}
      if(url!==next){url=next;policy=null;sequence=0;nested.clear();touched={scroll:0,media:0,forms:0};guarded={media:false,scroll:false,forms:false};operation?.cancelAll('Route changed');}
      const requestUrl=url;
      const response=await safeSend({type:'HELLO',url,documentToken,documentStartedAt});
      if(requestUrl!==pageIdentity(location.href)?.url)return;
      if(response.ok){policy=response;registry.start();}
      else {policy=null;operation?.cancelAll('Permission or page eligibility changed');captureSettled.cancel();captureMediaSettled.cancel();registry.stop();}
    })();
    try {await connectPromise;}finally{connectPromise=null;if(connectingAgain){connectingAgain=false;queueMicrotask(connect);}}
  };
  async function capture(parts=['media','scroll','forms']) {
    if(closed||!url||operation)return {ok:false};
    if(pageIdentity(location.href)?.url!==url){await connect();return {ok:false};}
    if(!policy){await connect();if(!policy)return {ok:false};}
    const records={};
    if(parts.includes('media')&&!guarded.media)records.media=captureMedia(registry.media,url);
    if(parts.includes('scroll')&&!guarded.scroll) {
      const main=scrollRecord(document);records.scroll=main?[main]:[];
      for(const el of nested){if(!el.isConnected){nested.delete(el);continue;}const r=scrollRecord(document,el);if(r)records.scroll.push(r);if(records.scroll.length>=LIMITS.scroll)break;}
    }
    if(parts.includes('forms')&&!guarded.forms)records.forms=captureForms(registry.fields,policy);
    if(!Object.keys(records).length)return {ok:false};
    const response=await safeSend({type:'CAPTURE',url,documentToken,generation:policy.generation,contextId:policy.contextId,epoch:policy.epoch,sequence:++sequence,records});
    if(response.reconnect)await connect();
    return response;
  }
  function userActivity(component,event) {
    if(!event.isTrusted)return;
    touched[component]=Date.now();guarded[component]=false;operation?.cancelComponent(component);
    if(url)safeSend({type:'USER_ACTIVITY',documentToken,url});
  }
  const formEvent=e=>{if(!e.isTrusted)return;if(e.inputType||Date.now()-lastGesture<2000){edited.add(e.target);userActivity('forms',e);}captureSettled();};
  document.addEventListener('input',formEvent,{capture:true,signal:activity.signal});
  document.addEventListener('change',formEvent,{capture:true,signal:activity.signal});
  document.addEventListener('scroll',e=>{const el=e.target;if(el?.nodeType===1&&el!==document.scrollingElement&&nested.size<LIMITS.scroll-1)nested.add(el);if(!operation)captureSettled();},{capture:true,passive:true,signal:activity.signal});
  for(const type of ['wheel','touchstart'])document.addEventListener(type,e=>userActivity('scroll',e),{capture:true,passive:true,signal:activity.signal});
  document.addEventListener('pointerdown',e=>{if(e.isTrusted)lastGesture=Date.now();userActivity('scroll',e);if(registry.media.size&&!e.target.matches?.('input,textarea,select'))userActivity('media',e);},{capture:true,passive:true,signal:activity.signal});
  document.addEventListener('keydown',e=>{
    if(e.isTrusted)lastGesture=Date.now();
    if(['ArrowDown','ArrowUp','PageDown','PageUp','Home','End',' '].includes(e.key)&&!e.target.matches?.('input,textarea,select'))userActivity('scroll',e);
    if(registry.media.size&&!e.target.matches?.('input,textarea,select')&&[' ','k','K','m','M','j','J','l','L','ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(e.key))userActivity('media',e);
  },{capture:true,signal:activity.signal});
  for(const type of ['loadedmetadata','durationchange','canplay','progress','seeked','playing','pause','ratechange','volumechange','ended','emptied'])document.addEventListener(type,e=>{
    if(!e.target.matches?.('video,audio'))return;registry.inspect(e.target);operation?.changed();
    if(!operation)captureMediaSettled();
  },{capture:true,signal:activity.signal});
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)connect().then(()=>capture());else capture();},{signal:activity.signal});
  document.addEventListener('DOMContentLoaded',()=>{registry.add(document.documentElement);connect().then(()=>capture());},{once:true,signal:activity.signal});
  window.addEventListener('popstate',()=>connect(),{signal:activity.signal});
  window.addEventListener('hashchange',()=>connect(),{signal:activity.signal});
  window.addEventListener('pagehide',event=>{capture();closed=true;operation?.cancelAll('Document left');registry.stop();captureSettled.cancel();captureMediaSettled.cancel();if(!event.persisted){activity.abort();clearInterval(routeTimer);clearInterval(mediaTimer);browser.runtime.onMessage.removeListener(handleMessage);}},{signal:activity.signal});
  window.addEventListener('pageshow',()=>{closed=false;connect().then(()=>capture());},{signal:activity.signal});

  // Only a URL comparison each second; full capture is never run for every tab on this clock.
  const routeTimer=setInterval(()=>{if(!closed&&pageIdentity(location.href)?.url!==url)connect();},1000);
  const mediaTimer=setInterval(()=>{if(!closed&&[...registry.media].some(el=>el.isConnected&&!el.paused))capture(['media']);},5000);
  // A held initial snapshot is retried without treating a wake as browser startup.
  setTimeout(()=>{if(!closed)connect().then(()=>capture());},2500);

  function restore(message) {
    const snapshot=message.snapshot;
    const unsupported=reason=>({status:'unsupported',...Object.fromEntries(['media','scroll','forms'].map(k=>[k,{status:'unsupported',reason}]))});
    if(!policy||!validateSnapshot(snapshot)||snapshot.page.url!==url||pageIdentity(location.href)?.url!==url||message.documentToken!==documentToken||message.generation!==policy.generation||message.epoch!==policy.epoch)return Promise.resolve(unsupported('Document, route, or permission changed'));
    const key=`${snapshot.id}:${snapshot.revision}:${policy.generation}`;
    if(completed.has(key))return Promise.resolve(copy(completed.get(key)));
    if(operation)return Promise.resolve(unsupported('A restoration is already running'));
    captureSettled.cancel();captureMediaSettled.cancel();
    return new Promise(resolve=>{
      const parts={media:new MediaRestore(snapshot.records.media,registry,url),scroll:new ScrollRestore(snapshot.records.scroll,document),forms:new FormRestore(snapshot.records.forms,document,policy,edited)};
      let frame=null,layoutTimer=null,finished=false,layoutReady=false;
      const local=new AbortController();
      function finish(final=false) {
        if(finished)return;
        if(final)parts.media.tick();
        if(final&&layoutReady)parts.scroll.tick(true);
        const results=Object.fromEntries(Object.entries(parts).map(([k,p])=>[k,p.result(true)]));
        const statuses=Object.entries(results).filter(([k])=>snapshot.records[k].length>0).map(([,r])=>r.status);
        const status=!statuses.length||statuses.every(s=>s==='unsupported')?'unsupported':statuses.every(s=>s==='completed')?'completed':statuses.every(s=>s==='cancelled')?'cancelled':statuses.every(s=>s==='failed')?'failed':'partial';
        finished=true;clearTimeout(deadline);clearTimeout(layoutTimer);clearTimeout(frame);resize?.disconnect();local.abort();
        for(const k of Object.keys(parts))guarded[k]=snapshot.records[k].length>0&&results[k].status!=='completed'&&touched[k]<(message.operationStartedAt||Date.now());
        const result={status,...results};completed.set(key,result);while(completed.size>20)completed.delete(completed.keys().next().value);
        operation=null;resolve(result);
        // Allow the background completion handler to release its protection before capturing again.
        setTimeout(()=>capture(),250);
      }
      function tick(){frame=null;if(finished)return;if(pageIdentity(location.href)?.url!==url){op.cancelAll('Route changed');return;}
        parts.media.tick();parts.forms.tick();if(layoutReady&&document.readyState!=='loading')parts.scroll.tick();
        if(Object.values(parts).every(p=>p.done()))finish();
      }
      // Event-driven coalescing also works in hidden tabs, whose animation frames may be suspended.
      function schedule(){if(frame===null&&!finished)frame=setTimeout(tick,16);}
      function changed(){layoutReady=false;clearTimeout(layoutTimer);layoutTimer=setTimeout(()=>{layoutReady=true;schedule();},200);schedule();}
      const op={id:message.operationId,changed,cancelComponent:k=>{parts[k]?.cancel();schedule();},cancelAll:()=>{for(const p of Object.values(parts))p.cancel();finish();}};
      const resize=typeof ResizeObserver!=='undefined'?new ResizeObserver(changed):null;
      if(document.documentElement)resize?.observe(document.documentElement);
      document.addEventListener('load',changed,{capture:true,signal:local.signal});
      document.addEventListener('DOMContentLoaded',changed,{signal:local.signal});
      const deadline=setTimeout(()=>{layoutReady=true;finish(true);},12000);
      operation=op;for(const [k,value] of Object.entries(touched))if(value>=(message.operationStartedAt||Date.now()))parts[k].cancel();changed();
    });
  }
  const handleMessage=message=>{
    if(message?.type==='RESTORE')return restore(message);
    if(message?.type==='CANCEL_RESTORE'){if(!message.operationId||message.operationId===operation?.id)operation?.cancelAll();return Promise.resolve({ok:true});}
    if(['CONNECT','ROUTE_CHANGED','POLICY_CHANGED'].includes(message?.type)) {
      if(message.type==='POLICY_CHANGED'){policy=null;operation?.cancelAll();captureSettled.cancel();captureMediaSettled.cancel();}
      queueMicrotask(()=>connect());return Promise.resolve({ok:true});
    }
    if(message?.type==='SNAPSHOT'){queueMicrotask(()=>connect().then(()=>capture()));return Promise.resolve({ok:true});}
    return undefined;
  };
  browser.runtime.onMessage.addListener(handleMessage);
  connect();
}
