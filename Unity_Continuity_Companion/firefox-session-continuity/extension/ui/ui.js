const $=id=>document.getElementById(id);
let state=null,filled=false;
const send=message=>browser.runtime.sendMessage(message);
const text=(id,value)=>{if($(id))$(id).textContent=value;};
const time=value=>value?new Date(value).toLocaleString():'No committed snapshot';
const seconds=value=>`${Math.floor(value/60)}:${String(Math.floor(value%60)).padStart(2,'0')}`;
function origin(raw){try{const u=new URL(raw.trim());if(!['http:','https:'].includes(u.protocol)||u.username||u.password||u.pathname!=='/'||u.search||u.hash)throw 0;return u.origin;}catch{throw new Error('Enter an exact HTTP or HTTPS origin, such as https://example.com.');}}
async function action(message){try{const result=await send(message);if(!result.ok)throw new Error(result.reason||'Operation failed');text('notice',result.note||'Saved.');await refresh();}catch(e){text('notice',e.message);}}
function on(id,fn){$(id)?.addEventListener('click',fn);}
function settings(patch){return action({type:'SETTINGS',patch});}
async function refresh(){
  try{
    state=await send({type:'STATUS'});if(!state.ok)throw new Error(state.reason);
    text('unity-status',state.unity?.reason||'Unity Linux: disabled');
    if($('unity-enabled')&&document.activeElement!==$('unity-enabled'))$('unity-enabled').checked=state.unity?.enabled===true;
    const s=state.current;
    text('tracking-status',state.trackingStatus);text('current-page',state.page?.url||'This page cannot be captured');text('saved-at',time(s?.updatedAt));
    text('media-summary',s?.records.media.length?s.records.media.map(m=>`${m.kind} · ${seconds(m.time)}`).join(', '):'No supported media saved');
    const scroll=s?.records.scroll.find(r=>r.target==='document');text('scroll-summary',scroll?`${Math.round(scroll.progressY*100)}% · ${Math.round(scroll.y)} px`:'Not saved yet');
    text('form-summary',state.settings.formOrigins.includes(state.page?.origin)?'Approved for this origin':'Not approved');
    text('last-active',state.latest?`Last active: ${state.latest.page.url}`:'No saved active page');
    text('toggle-tracking',state.settings.tracking?'Pause tracking':'Resume tracking');
    text('error',[state.error,...state.warnings].filter(Boolean).join(' '));
    $('grant-host').hidden=!state.page||state.hasHost;
    $('save-now').disabled=state.trackingStatus!=='Tracking';$('restore-last').disabled=!state.latest||state.settings.startupMode==='disabled';
    text('restore-state',state.restore?.state||'idle');
    const results=$('restore-results');results.replaceChildren();
    for(const name of ['media','scroll','forms']){const result=state.restore?.results?.[name];if(!result)continue;const li=document.createElement('li');li.textContent=`${name}: ${result.status}${result.reason?' — '+result.reason:''}`;results.append(li);}
    if($('tracking')){
      if(document.activeElement!==$('tracking'))$('tracking').checked=state.settings.tracking;
      if(document.activeElement!==$('startup-mode'))$('startup-mode').value=state.settings.startupMode;
      if(!filled){$('excluded-origins').value=state.settings.excludedOrigins.join('\n');$('max-contexts').value=state.settings.maxContexts;$('max-age').value=state.settings.maxAgeDays;filled=true;}
      const origins=$('form-origins');origins.replaceChildren();for(const o of state.settings.formOrigins){const li=document.createElement('li');li.textContent=o;origins.append(li);}
      const contexts=$('contexts');contexts.replaceChildren();for(const c of state.contexts){const li=document.createElement('li'),body=document.createElement('div'),p=document.createElement('p'),detail=document.createElement('p');p.textContent=c.page.url;detail.className='small';detail.textContent=`${c.container} · ${time(c.updatedAt)} · ${c.closedAt?'closed':'open context'} · ${c.id.slice(0,8)}`;body.append(p,detail);li.append(body);if(!c.closedAt){const b=document.createElement('button');b.className='secondary';b.textContent='Restore';b.addEventListener('click',()=>action({type:'RESTORE_LAST',id:c.id}));li.append(b);}contexts.append(li);}
      if(!state.contexts.length){const li=document.createElement('li');li.textContent='No saved contexts yet.';contexts.append(li);}
    }
  }catch{text('error','Cannot reach the extension. Reload this view.');}
}
on('save-now',()=>action({type:'SAVE_NOW'}));on('restore-last',()=>action({type:'RESTORE_LAST'}));
on('options',()=>browser.runtime.openOptionsPage());on('toggle-tracking',()=>settings({tracking:!state.settings.tracking}));
on('grant-host',async()=>{try{const u=new URL(state.page.url);const granted=await browser.permissions.request({origins:[`${u.protocol}//${u.hostname}/*`]});text('notice',granted?'Access granted. Reload the page if it was already open.':'Access was not granted.');await refresh();}catch{text('notice','Firefox did not grant site access.');}});
$('tracking')?.addEventListener('change',e=>settings({tracking:e.target.checked}));
$('unity-enabled')?.addEventListener('change',e=>action({type:'UNITY_CONNECTION',enabled:e.target.checked}));
$('startup-mode')?.addEventListener('change',e=>settings({startupMode:e.target.value}));
on('approve-forms',()=>{try{const o=origin($('form-origin').value);settings({formOrigins:[...new Set([...state.settings.formOrigins,o])]});}catch(e){text('notice',e.message);}});
on('revoke-forms',()=>{try{const o=origin($('form-origin').value);settings({formOrigins:state.settings.formOrigins.filter(x=>x!==o)});}catch(e){text('notice',e.message);}});
$('form-origin')?.addEventListener('change',()=>{try{$('field-exclusions').value=(state.settings.fieldExclusions[origin($('form-origin').value)]||[]).join('\n');}catch{}});
on('save-fields',()=>{try{const o=origin($('form-origin').value);settings({fieldExclusions:{...state.settings.fieldExclusions,[o]:$('field-exclusions').value.split('\n').map(x=>x.trim()).filter(Boolean)}});}catch(e){text('notice',e.message);}});
on('save-exclusions',()=>{try{settings({excludedOrigins:$('excluded-origins').value.split('\n').map(x=>x.trim()).filter(Boolean).map(origin)});}catch(e){text('notice',e.message);}});
on('save-retention',()=>settings({maxContexts:Number($('max-contexts').value),maxAgeDays:Number($('max-age').value)}));
on('delete-site',()=>{try{action({type:'DELETE_SITE',origin:origin($('delete-origin').value)});}catch(e){text('notice',e.message);}});
on('clear-all',()=>action({type:'CLEAR_ALL'}));
refresh();setInterval(refresh,2000);
