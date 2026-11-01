import {copy,newSnapshot,pageIdentity,uuid,DEFAULT_SETTINGS} from '../extension/src/core/model.js';
import {StorageAdapter} from '../extension/src/storage/adapter.js';
export class MemoryArea {
  constructor(data={}){this.data=copy(data);this.writes=0;this.fail=false;this.delay=null;}
  async get(key){return key?{[key]:copy(this.data[key])}:copy(this.data);}
  async set(value){this.writes++;if(this.delay)await this.delay;if(this.fail)throw new Error('Quota exceeded');Object.assign(this.data,copy(value));}
}
// A genuine alternative logical adapter, not a simulated native companion.
export class InMemoryAdapter extends StorageAdapter {
  constructor(){super();this.contexts=new Map();this.active=null;this.config=copy(DEFAULT_SETTINGS);}
  async initialize(){}
  async save(s,{active=false,epoch}={}){if(epoch!==undefined&&epoch!==this.config.epoch)throw new Error('epoch');const old=this.contexts.get(s.id);if(!old||s.revision>old.revision)this.contexts.set(s.id,copy(s));if(active)this.active=s.id;return this.load(s.id);}
  async load(id){return copy(this.contexts.get(id)||null);}
  async loadLatestActive(){return this.load(this.active);}
  async list(){return [...this.contexts.values()].map(copy);}
  async delete(id){this.contexts.delete(id);if(this.active===id)this.active=null;}
  async clear(){this.contexts.clear();this.active=null;this.config.tracking=false;this.config.epoch++;}
  async close(id){if(this.contexts.has(id))this.contexts.get(id).closedAt=Date.now();if(this.active===id)this.active=null;}
  async selectActive(id){this.active=this.contexts.has(id)?id:null;}
  async settings(){return copy(this.config);}
  async changePolicy(patch){this.config={...this.config,...patch,epoch:this.config.epoch+1};return copy(this.config);}
}
export const snapshot=(raw='https://example.org/read',extra={})=>({...newSnapshot({logicalTabId:uuid(),container:'firefox-default',page:pageIdentity(raw),documentToken:uuid()}),...extra});
export const mediaRecord={key:'generic:audio:content:episode',kind:'audio',time:755,duration:900,rate:1,paused:true,muted:false,volume:.5};
export const scrollData={target:'document',x:0,y:3000,width:1200,height:4800,viewportWidth:1200,viewportHeight:800,progressX:0,progressY:.75,anchor:null};
export function mockBrowser(){
  const tabs=new Map([[1,{id:1,windowId:1,active:true,url:'https://example.org/read',cookieStoreId:'firefox-default',incognito:false}],[2,{id:2,windowId:1,active:false,url:'https://example.org/read',cookieStoreId:'firefox-default',incognito:false}]]),identities=new Map();
  const b={runtime:{id:'test-extension',getURL:p=>`moz-extension://test/${p}`},storage:{session:new MemoryArea()},alarms:{create(){}},permissions:{contains:async()=>true},windows:{get:async()=>({id:1,type:'normal',focused:true,incognito:false}),getAll:async()=>[{id:1,type:'normal',focused:true,incognito:false}]},tabs:{get:async id=>copy(tabs.get(id)),query:async q=>[...tabs.values()].filter(t=>!q?.active||t.active).map(copy),sendMessage:async()=>({ok:true})},sessions:{getTabValue:async id=>copy(identities.get(id)),setTabValue:async(id,k,v)=>{identities.set(id,copy(v));}},webNavigation:{getFrame:async({tabId})=>({url:tabs.get(tabId).url,documentId:`doc-${tabId}`})},contextualIdentities:{get:async id=>id==='firefox-container-1'?{cookieStoreId:id}:Promise.reject(new Error('missing'))}};
  return {b,tabs,identities,sender:id=>({id:b.runtime.id,tab:tabs.get(id),frameId:0,url:tabs.get(id).url,documentId:`doc-${id}`})};
}
