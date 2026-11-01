import {uuid,copy,pageIdentity,allowedPage,newSnapshot,validId,validForm,normalizeSettings} from '../core/model.js';
import {validMessage,validHello,acceptCapture} from '../core/gate.js';
import {RestoreCoordinator,matchBrowserTabs,componentResults} from './restore.js';

const RUNTIME_KEY='continuity.runtime.v1';
export class Controller {
  constructor(browser,repository) {
    this.b=browser;this.repo=repository;this.serial=Promise.resolve();this.runtimeTail=Promise.resolve();this.bindings={};this.nav={};this.startupRequested=false;this.error=null;this.focusSerial=0;
    this.restorer=new RestoreCoordinator({
      match:target=>matchBrowserTabs(this.b,target),
      canRestore:target=>this.canRestore(target),
      initialFocus:async()=>{const windows=await this.b.windows.getAll({windowTypes:['normal']});const w=windows.find(w=>w.focused&&!w.incognito);if(!w)return null;const tabs=await this.b.tabs.query({active:true,windowId:w.id});const t=tabs[0];return t&&['about:blank','about:newtab','about:home'].includes(t.url)?{tabId:t.id,windowId:w.id,url:t.url,serial:this.focusSerial}:null;},
      focus:async op=>{const g=op.focusGuard;if(!op.startup||!g||g.serial!==this.focusSerial)return;const w=await this.b.windows.get(g.windowId);const tabs=await this.b.tabs.query({active:true,windowId:g.windowId});if(w.focused&&tabs[0]?.id===g.tabId&&tabs[0]?.url===g.url)await this.b.tabs.update(op.tabId,{active:true});},
      create:async target=>{
        const windows=await this.b.windows.getAll({windowTypes:['normal']});
        const window=windows.find(w=>!w.incognito&&w.focused)||windows.find(w=>!w.incognito);
        if(!window) throw new Error('No normal window');
        return this.b.tabs.create({windowId:window.id,url:target.page.url,cookieStoreId:target.container,active:false});
      },
      bind:op=>this.queue(()=>this.bindRestore(op)),
      samePage:async op=> {const t=await this.b.tabs.get(op.tabId).catch(()=>null);if(t&&op.created&&!op.seenDocument&&t.url==='about:blank'&&t.status==='loading')return null;const same=!!t&&!t.incognito&&t.cookieStoreId===op.target.container&&pageIdentity(t.url)?.url===op.target.page.url;if(same)op.seenDocument=true;return same;},
      ready:async op=>{
        await this.b.tabs.sendMessage(op.tabId,{type:'CONNECT'},{frameId:0}).catch(()=>null);
        const binding=this.bindings[op.tabId];
        return binding&&binding.url===op.target.page.url&&binding.contextId===op.target.id?copy(binding):null;
      },
      deliver:async(op,binding)=>{
        const fingerprint=`${op.target.id}:${op.target.revision}:${binding.documentToken}:${binding.generation}`;
        if(this.runtime.restored?.[fingerprint]) return this.runtime.restored[fingerprint];
        const result=await this.b.tabs.sendMessage(op.tabId,{type:'RESTORE',operationId:op.id,operationStartedAt:op.startedAt,snapshot:op.target,documentToken:binding.documentToken,generation:binding.generation,epoch:this.settings.epoch},{frameId:0});
        const valid=this.validResult(result)?result:{status:'failed',...componentResults('failed','Invalid page response')};
        this.runtime.restored={...this.runtime.restored,[fingerprint]:valid};
        const keys=Object.keys(this.runtime.restored);for(const k of keys.slice(0,-30))delete this.runtime.restored[k];
        await this.persistRuntime();return valid;
      },
      cancelContent:op=>{if(op.tabId)this.b.tabs.sendMessage(op.tabId,{type:'CANCEL_RESTORE',operationId:op.id},{frameId:0}).catch(()=>{});},
      onState:op=>{this.runtime.restore={state:op.state,tabId:op.tabId};this.persistRuntime().catch(()=>{});},
      onDone:(op,result)=>{this.queue(async()=>{this.runtime.restore={state:result?.status||'failed',results:result};if(['completed','partial'].includes(result?.status))this.runtime.protected=null;this.runtime.holdUntil=0;await this.persistRuntime();await this.notifyPolicy();}).catch(()=>{});}
    });
  }
  queue(fn) {
    const next=this.serial.then(()=>this.initialize()).then(fn);
    this.serial=next.catch(()=>{});return next;
  }
  async initialize() {
    if(this.init) return this.init;
    this.init=(async()=>{
      await this.repo.initialize();this.settings=await this.repo.settings();
      const saved=(await this.b.storage.session.get(RUNTIME_KEY))[RUNTIME_KEY];
      if(saved?.version===1) {this.runtime=saved;this.bindings=saved.bindings||{};this.nav=saved.nav||{};}
      else {
        // Pin the old commit before any capture/selection can write. Only onStartup may auto-restore it.
        this.runtime={version:1,bindings:{},nav:{},protected:await this.repo.loadLatestActive(),holdUntil:Date.now()+2000,startupHandled:false,restored:{},restore:{state:'idle'}};
      }
      if(['reconciling','waiting','restoring'].includes(this.runtime.restore?.state)){
        const interruptedTab=this.runtime.restore.tabId;
        this.runtime.restore={state:'cancelled',results:{status:'cancelled',...componentResults('cancelled','Background context restarted; saved target is available for manual retry')}};this.runtime.holdUntil=0;
        if(interruptedTab)this.b.tabs.sendMessage(interruptedTab,{type:'CANCEL_RESTORE'},{frameId:0}).catch(()=>{});
      }
      await this.persistRuntime();
      this.b.alarms.create('continuity-maintenance',{periodInMinutes:5});
    })();return this.init;
  }
  persistRuntime() {const snapshot=copy({...this.runtime,bindings:this.bindings,nav:this.nav});const next=this.runtimeTail.then(()=>this.b.storage.session.set({[RUNTIME_KEY]:snapshot}));this.runtimeTail=next.catch(()=>{});next.then(()=>this.companion?.scheduleSync()).catch(()=>{});return next;}
  holding() {return !!this.restorer.current || this.startupRequested&&!this.runtime.startupHandled || Date.now()<this.runtime.holdUntil;}
  async startup() {
    await this.initialize();
    if(this.runtime.startupHandled)return;
    this.runtime.startupHandled=true;
    const target=this.runtime.protected;
    if(this.settings.startupMode==='automatic'&&this.settings.tracking&&target) {
      // Mark handled before starting. A restarted event page never repeats navigation.
      await this.persistRuntime();
      return this.restorer.run(target,{startup:true});
    }
    this.runtime.holdUntil=0;
    this.runtime.restore={state:this.settings.startupMode==='manual'?'manual':'disabled'};
    await this.persistRuntime();
    await this.notifyPolicy();
  }
  async installed() {await this.initialize();this.runtime.holdUntil=0;this.runtime.startupHandled=true;await this.persistRuntime();await this.notifyPolicy();}
  async hasHost(url) {const p=pageIdentity(url);return !!p&&await this.b.permissions.contains({origins:[`${new URL(p.url).protocol}//${new URL(p.url).hostname}/*`]}).catch(()=>false);}
  async eligible(tab) {return !this.runtime.policyBlocked&&allowedPage(tab.url,tab.cookieStoreId,tab.incognito,this.settings)&&await this.hasHost(tab.url)&&(await this.b.windows.get(tab.windowId)).type==='normal';}
  async canRestore(target) {
    if(this.runtime.policyBlocked||!allowedPage(target.page.url,target.container,false,this.settings)||!await this.hasHost(target.page.url))return false;
    if(target.container==='firefox-default')return true;
    return !!await this.b.contextualIdentities.get(target.container).catch(()=>null);
  }
  async focused(tab) {const w=await this.b.windows.get(tab.windowId);return tab.active&&w.focused&&w.type==='normal'&&!w.incognito;}
  policy(page) {return {epoch:this.settings.epoch,tracking:this.settings.tracking,formsAllowed:this.settings.formOrigins.includes(page.origin)&&!this.settings.excludedOrigins.includes(page.origin),fieldExclusions:this.settings.fieldExclusions[page.origin]||[],hold:this.holding()};}
  async senderTab(sender,m) {
    if(sender.id!==this.b.runtime.id||sender.frameId!==0||!sender.tab||sender.tab.incognito)return null;
    const tab=await this.b.tabs.get(sender.tab.id).catch(()=>null);
    if(!tab||!await this.eligible(tab))return null;
    const page=pageIdentity(m.url);
    if(!page||page.url!==pageIdentity(tab.url)?.url||pageIdentity(sender.url)?.origin!==page.origin)return null;
    const frame=await this.b.webNavigation.getFrame({tabId:tab.id,frameId:0}).catch(()=>null);
    if(!frame||frame.errorOccurred||pageIdentity(frame.url)?.url!==page.url||!sender.documentId||sender.documentId!==frame.documentId)return null;
    return tab;
  }
  async hello(m,sender) {
    if(!validHello(m))return {ok:false,reason:'Invalid document handshake'};
    const tab=await this.senderTab(sender,m);if(!tab)return {ok:false,reason:'Page excluded or host permission unavailable'};
    const old=this.bindings[tab.id];const navigation=this.nav[tab.id];
    if(!sender.documentId&&old&&m.documentToken!==old.documentToken&&m.documentStartedAt<old.documentStartedAt)return {ok:false,reason:'Old document'};
    const page=pageIdentity(m.url);
    let identity=await this.b.sessions.getTabValue(tab.id,'continuityIdentity').catch(()=>null);
    if(!validId(identity?.logicalTabId))identity={logicalTabId:uuid(),contextId:null,url:null};
    if(Object.entries(this.bindings).some(([id,b])=>Number(id)!==tab.id&&b.logicalTabId===identity.logicalTabId))identity={logicalTabId:uuid(),contextId:null,url:null};
    if(identity.url!==page.url||!validId(identity.contextId))identity={logicalTabId:identity.logicalTabId,contextId:uuid(),url:page.url};
    if(this.restorer.current?.tabId===tab.id&&this.restorer.current.target.page.url===page.url)identity={logicalTabId:this.restorer.current.target.logicalTabId,contextId:this.restorer.current.target.id,url:page.url};
    const same=old?.documentToken===m.documentToken&&old.url===page.url&&old.contextId===identity.contextId;
    const binding={...identity,documentToken:m.documentToken,documentStartedAt:m.documentStartedAt,browserDocumentId:sender.documentId||null,generation:same?old.generation:(old?.generation||0)+1,sequence:same?old.sequence:0,container:tab.cookieStoreId,epoch:this.settings.epoch};
    this.bindings[tab.id]=binding;
    await this.b.sessions.setTabValue(tab.id,'continuityIdentity',identity);
    await this.persistRuntime();
    return {ok:true,contextId:binding.contextId,logicalTabId:binding.logicalTabId,generation:binding.generation,...this.policy(page)};
  }
  async capture(m,sender) {
    const tab=await this.senderTab(sender,m);const binding=tab&&this.bindings[tab.id];
    if(!tab||!acceptCapture(m,binding,this.settings.epoch))return {ok:false,reason:'Stale or ineligible capture',reconnect:true};
    if(this.holding())return {ok:false,reason:'Restoration protects the saved context',held:true};
    if(this.runtime.protected?.id===binding.contextId&&['manual','cancelled','failed','unsupported'].includes(this.runtime.restore?.state))return {ok:false,reason:'Previous context is protected until restoration or user input',held:true};
    if(binding.browserDocumentId&&sender.documentId&&binding.browserDocumentId!==sender.documentId)return {ok:false,reason:'Document changed'};
    binding.sequence=m.sequence;
    const existing=await this.repo.load(binding.contextId);
    const snapshot=existing||newSnapshot({id:binding.contextId,logicalTabId:binding.logicalTabId,container:binding.container,page:pageIdentity(m.url),documentToken:m.documentToken,generation:binding.generation});
    snapshot.closedAt=null;snapshot.documentToken=m.documentToken;snapshot.generation=binding.generation;snapshot.updatedAt=Date.now();snapshot.revision=(existing?.revision||0)+1;
    for(const k of ['media','scroll','forms']) if(m.records[k]!==undefined)snapshot.records[k]=copy(m.records[k]);
    const origin=snapshot.page.origin;
    if(!this.settings.formOrigins.includes(origin))snapshot.records.forms=[];
    const exclusions=this.settings.fieldExclusions[origin]||[];
    snapshot.records.forms=snapshot.records.forms.filter(f=>validForm(f)&&!exclusions.some(x=>x===f.field.value||f.meta.toLowerCase().includes(x.toLowerCase())));
    const active=await this.focused(tab);
    // Active metadata is set by actual focused activity, never by a background checkpoint.
    try {
      const saved=await this.repo.save(snapshot,{active,epoch:m.epoch});
      await this.persistRuntime();this.error=null;
      return {ok:true,savedAt:saved.updatedAt,revision:saved.revision};
    } catch {this.error='Save failed. The previous committed snapshot remains available. Check storage limits.';return {ok:false,reason:this.error};}
  }
  async select(tabId) {
    if(this.holding())return;
    const tab=await this.b.tabs.get(tabId).catch(()=>null);if(!tab||!await this.focused(tab))return;
    if(tab.url?.startsWith(this.b.runtime.getURL('ui/')))return;
    if(!await this.eligible(tab)){await this.repo.selectActive(null);return;}
    const binding=this.bindings[tabId];
    await this.repo.selectActive(binding?.contextId||null);
    await this.b.tabs.sendMessage(tabId,{type:'SNAPSHOT'},{frameId:0}).catch(()=>{});
  }
  async navigation(details,kind) {
    if(details.frameId!==0)return;
    const old=this.bindings[details.tabId];
    const nextPage=pageIdentity(details.url);
    if(kind==='commit') {
      this.nav[details.tabId]={startedAt:details.timeStamp,url:nextPage?.url||null};
    }
    if(this.restorer.current?.tabId===details.tabId&&nextPage?.url!==this.restorer.current.target.page.url&&!(this.restorer.current.created&&!this.restorer.current.seenDocument&&details.url==='about:blank'))this.restorer.cancel('Navigation changed the route');
    if(old&&old.url!==nextPage?.url){delete this.bindings[details.tabId];const tab=await this.b.tabs.get(details.tabId).catch(()=>null);if(!this.holding()&&tab&&await this.focused(tab))await this.repo.selectActive(null);}
    await this.persistRuntime();
    await this.b.tabs.sendMessage(details.tabId,{type:'ROUTE_CHANGED'},{frameId:0}).catch(()=>{});
  }
  async removed(id) {
    const binding=this.bindings[id];delete this.bindings[id];delete this.nav[id];
    if(binding)await this.repo.close(binding.contextId);
    if(this.runtime.protected?.id===binding?.contextId)this.runtime.protected=null;
    if(this.restorer.current?.tabId===id)this.restorer.cancel('The tab was closed');
    await this.persistRuntime();
  }
  async bindRestore(op) {
    await this.b.sessions.setTabValue(op.tabId,'continuityIdentity',{logicalTabId:op.target.logicalTabId,contextId:op.target.id,url:op.target.page.url});
    const binding=this.bindings[op.tabId];if(binding){binding.contextId=op.target.id;binding.logicalTabId=op.target.logicalTabId;}
    await this.persistRuntime();
  }
  async notifyPolicy() {
    const tabs=await this.b.tabs.query({});
    await Promise.allSettled(tabs.filter(t=>!t.incognito&&pageIdentity(t.url)).map(async t=>{
      try {await this.b.tabs.sendMessage(t.id,{type:'POLICY_CHANGED'},{frameId:0});}
      catch {if(await this.eligible(t))await this.b.scripting.executeScript({target:{tabId:t.id},files:['content.bundle.js']});}
    }));
  }
  async permissionsChanged(removed=false) {
    if(this.runtime.policyBlocked)return;
    this.restorer.cancel('Host permissions changed');
    const list=await this.repo.list();
    const denied=[];for(const origin of new Set(list.map(s=>s.page.origin)))if(!await this.hasHost(origin))denied.push(origin);
    // Revocation also revokes form consent, purges values, and invalidates queued captures.
    this.settings=await this.repo.changePolicy({formOrigins:this.settings.formOrigins.filter(o=>!denied.includes(o))});
    this.runtime.protected=null;this.runtime.holdUntil=0;
    if(removed)for(const s of list)if(denied.includes(s.page.origin))await this.repo.delete(s.id);
    this.settings=await this.repo.settings();
    await this.persistRuntime();await this.notifyPolicy();
  }
  async privacyChange(change) {
    this.runtime.policyBlocked=true;await this.persistRuntime();await this.notifyPolicy();
    try {
      await change();this.settings=await this.repo.settings();this.runtime.policyBlocked=false;this.runtime.protected=null;this.runtime.holdUntil=0;this.error=null;
      await this.persistRuntime();await this.notifyPolicy();
    } catch(error){this.error='Privacy change could not be committed. Tracking is blocked for this browser session; retry the control. Erasure has not been confirmed.';await this.persistRuntime();throw error;}
  }
  async status() {
    const tabs=await this.b.tabs.query({active:true,currentWindow:true});const tab=tabs[0];
    const page=tab&&!tab.incognito?pageIdentity(tab.url):null;
    const binding=tab&&this.bindings[tab.id];const current=binding?await this.repo.load(binding.contextId):null;
    const latest=this.runtime.protected||await this.repo.loadLatestActive();
    const permitted=page&&await this.hasHost(page.url);
    return {ok:true,backend:'Browser Local Storage',nativeAvailable:!!this.companion?.connected,unity:this.companion?.status(),settings:this.settings,page,tabId:tab&&!tab.incognito?tab.id:null,current,latest,contexts:await this.repo.list(),trackingStatus:this.runtime.policyBlocked?'Blocked — retry the privacy control':!this.settings.tracking?'Paused':!page?'Unsupported or private page':this.settings.excludedOrigins.includes(page.origin)?'Site excluded':!permitted?'Host permission missing':!binding?'Waiting for page access — reload this page if needed':'Tracking',hasHost:!!permitted,restore:this.runtime.restore,error:this.error,warnings:this.repo.warnings||[]};
  }
  validResult(r) {return r&&['completed','partial','cancelled','unsupported','failed'].includes(r.status)&&['media','scroll','forms'].every(k=>r[k]&&['completed','partial','cancelled','unsupported','failed'].includes(r[k].status));}
  async ui(m) {
    if(m.type==='STATUS')return this.status();
    if(m.type==='UNITY_CONNECTION'&&this.companion) {
      await this.companion.setEnabled(m.enabled);return {ok:true};
    }
    if(m.type==='SAVE_NOW') {
      const tabs=await this.b.tabs.query({active:true,currentWindow:true});const tab=tabs[0];
      if(!tab||!await this.eligible(tab))return {ok:false,reason:'Current page is unavailable or excluded'};
      // Do not await a content CAPTURE reply while holding the message serialization queue.
      this.b.tabs.sendMessage(tab.id,{type:'SNAPSHOT'},{frameId:0}).catch(()=>{this.error='Reload the page to enable capture.';});
      return {ok:true,note:'Snapshot requested. The save time changes only after storage commits.'};
    }
    if(m.type==='RESTORE_LAST') {
      if(this.settings.startupMode==='disabled')return {ok:false,reason:'Restoration is disabled in settings'};
      const target=m.id?await this.repo.load(m.id):this.runtime.protected||await this.repo.loadLatestActive();
      if(!target)return {ok:false,reason:'No saved active context'};
      if(this.restorer.current)return {ok:false,reason:'Restoration is already running'};
      this.runtime.protected=copy(target);await this.persistRuntime();
      this.restorer.run(target).catch(()=>{});return {ok:true,note:'Restoration started. Existing tabs retain their focus.'};
    }
    if(m.type==='SETTINGS') {
      const patch=m.patch||{};
      if(Object.keys(patch).some(k=>!['tracking','startupMode','maxContexts','maxAgeDays','excludedOrigins','formOrigins','fieldExclusions'].includes(k)))return {ok:false,reason:'Invalid settings'};
      this.restorer.cancel('Settings changed');
      await this.privacyChange(()=>this.repo.changePolicy(patch));
      return {ok:true};
    }
    if(m.type==='DELETE_SITE') {
      const p=pageIdentity(m.origin);if(!p||p.origin!==m.origin)return {ok:false,reason:'Invalid origin'};
      this.restorer.cancel('Site data deleted');
      await this.privacyChange(()=>this.repo.changePolicy({excludedOrigins:[...this.settings.excludedOrigins,m.origin],formOrigins:this.settings.formOrigins.filter(o=>o!==m.origin)},{deleteOrigin:m.origin}));return {ok:true};
    }
    if(m.type==='CLEAR_ALL') {
      this.restorer.cancel('All saved data cleared');await this.privacyChange(()=>this.repo.clear());this.runtime.restored={};await this.persistRuntime();return {ok:true};
    }
    return {ok:false,reason:'Unknown action'};
  }
  async message(m,sender) {
    if(!validMessage(m)||sender.id!==this.b.runtime.id)return {ok:false,reason:'Invalid message'};
    if(['HELLO','CAPTURE','USER_ACTIVITY'].includes(m.type)) {
      if(m.type==='HELLO')return this.hello(m,sender);
      if(m.type==='CAPTURE')return this.capture(m,sender);
      const binding=this.bindings[sender.tab?.id];
      if(sender.frameId===0&&binding?.documentToken===m.documentToken&&m.url===binding.url&&!this.restorer.current&&this.runtime.protected?.id===binding.contextId){this.runtime.protected=null;await this.persistRuntime();}
      if(sender.frameId===0&&binding?.documentToken===m.documentToken&&m.url===binding.url&&this.restorer.current?.startup&&binding.contextId!==this.restorer.current.target.id&&this.restorer.current.tabId!==sender.tab.id){const tab=await this.b.tabs.get(sender.tab.id).catch(()=>null);if(tab&&await this.focused(tab))this.restorer.cancel('User moved to another page');}
      return {ok:true};
    }
    const uiURLs=['ui/popup.html','ui/options.html'].map(p=>this.b.runtime.getURL(p));
    if(!uiURLs.includes(sender.url)||sender.tab?.incognito)return {ok:false,reason:'Only extension controls may perform this action'};
    return this.ui(m);
  }
}
