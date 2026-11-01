import {uuid,validId} from '../core/model.js';

const KEY='sessionContinuity.unity.v1';
const HOST='org.unityconnect.firefox';
const ALARM='continuity-unity-reconnect';

/** Optional live summary mirror. Browser Local Storage remains authoritative. */
export class UnityCompanion {
  constructor(browser,controller) {
    this.b=browser;this.controller=controller;this.port=null;this.enabled=false;
    this.serverEnabled=false;this.connected=false;this.sequence=0;this.syncedAt=null;
    this.reason='Disabled';this.pending=false;
  }
  async initialize() {
    if(this.ready)return this.ready;
    this.ready=(async()=>{
      const config=(await this.b.storage.local.get(KEY))[KEY]||{};
      this.profileId=validId(config.profileId)?config.profileId:uuid();
      this.enabled=config.enabled===true;
      await this.b.storage.local.set({[KEY]:{profileId:this.profileId,enabled:this.enabled}});
      if(this.enabled)this.connect();
    })();return this.ready;
  }
  status() {return {enabled:this.enabled,connected:this.connected,serverEnabled:this.serverEnabled,reason:this.reason,syncedAt:this.syncedAt};}
  async setEnabled(enabled) {
    if(typeof enabled!=='boolean')throw new Error('Invalid Unity connection setting');
    await this.initialize();
    await this.b.storage.local.set({[KEY]:{profileId:this.profileId,enabled}});
    this.enabled=enabled;
    if(enabled)this.connect();
    else {
      const port=this.port;this.port=null;port?.disconnect();
      this.connected=this.serverEnabled=false;this.syncedAt=null;this.reason='Disabled';
      await this.b.alarms.clear(ALARM);
    }
  }
  connect() {
    if(!this.enabled||this.port)return;
    this.reason='Connecting to Unity Linux';
    this.b.alarms.create(ALARM,{periodInMinutes:0.5});
    try {
      const port=this.b.runtime.connectNative(HOST);this.port=port;
      port.onMessage.addListener(message=>this.controller.queue(()=>this.receive(port,message)).catch(()=>{
        this.reason='Unity communication failed';this.drop(port);
      }));
      port.onDisconnect.addListener(()=>{
        if(this.port!==port)return;
        this.port=null;this.connected=this.serverEnabled=false;this.syncedAt=null;
        this.reason=port.error?.message||'Unity Linux is unavailable. Install or start it, then enable the Firefox extension in Settings.';
      });
      port.postMessage({type:'hello',version:1,profileId:this.profileId});
    } catch {
      this.port=null;this.connected=this.serverEnabled=false;
      this.reason='Install or start Unity Linux to connect.';
    }
  }
  drop(port) {if(this.port===port){this.port=null;this.connected=this.serverEnabled=false;this.syncedAt=null;}port.disconnect();}
  async alarm(name) {
    if(name!==ALARM||!this.enabled)return;
    await this.initialize();
    if(!this.port)this.connect();
    else this.port.postMessage({type:'ping'});
    if(this.connected&&this.serverEnabled)await this.sync();
  }
  scheduleSync() {
    if(this.pending)return;this.pending=true;
    this.controller.queue(async()=>{this.pending=false;await this.sync();}).catch(()=>{});
  }
  async sync() {
    if(!this.enabled||!this.connected||!this.serverEnabled||!this.port)return;
    const repo=this.controller.repo;
    const contexts=await repo.list(),active=await repo.loadLatestActive(),settings=await repo.settings();
    const summary={tracking:settings.tracking,activeId:active?.id||null,restoreState:this.controller.runtime.restore?.state||'idle',contexts:contexts.map(s=>({
      id:s.id,url:s.page.url,updatedAt:s.updatedAt,closed:s.closedAt!==null,
      media:s.records.media.map(m=>({kind:m.kind,time:m.time})),
      scrollProgress:s.records.scroll.find(r=>r.target==='document')?.progressY??null,
      formCount:s.records.forms.length,
    }))};
    this.port.postMessage({type:'sync',sequence:++this.sequence,summary});
  }
  async receive(port,message) {
    if(port!==this.port||!this.enabled||!message||typeof message!=='object')return;
    if(message.type==='hello') {
      if(message.version!==1||typeof message.enabled!=='boolean')throw new Error('Unsupported Unity bridge');
      this.connected=true;this.serverEnabled=message.enabled;this.syncedAt=null;
      this.reason=message.enabled?'Connected to Unity Linux':'Enable the Firefox extension in Unity Linux → Settings';
      if(message.enabled)await this.sync();
    } else if(message.type==='synced') {
      if(message.sequence===this.sequence){this.syncedAt=Date.now();this.reason='Connected to Unity Linux';}
    } else if(message.type==='restore'&&this.serverEnabled) {
      if(!validId(message.commandId)||typeof message.contextId!=='string'||message.contextId&&!validId(message.contextId))return;
      // The same checks and user-input cancellation apply to desktop requests.
      const result=await this.controller.ui({type:'RESTORE_LAST',...(message.contextId?{id:message.contextId}:{})});
      port.postMessage({type:'result',commandId:message.commandId,ok:result.ok===true,note:result.note||result.reason||'Firefox replied'});
      await this.sync();
    }
  }
}
