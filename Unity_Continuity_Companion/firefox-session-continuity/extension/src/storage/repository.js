import {StorageAdapter} from './adapter.js';
import {SCHEMA,LIMITS,DEFAULT_SETTINGS,copy,bytes,normalizeSettings,migrateSnapshot,validateSnapshot} from '../core/model.js';
export const STORE_KEY='sessionContinuity.repository.v1';
const empty=()=>({schemaVersion:SCHEMA,commit:0,settings:copy(DEFAULT_SETTINGS),activeId:null,selectedAt:null,contexts:{}});

/** One key contains a complete commit. Only replace the in-memory view after set resolves. */
export class LocalRepository extends StorageAdapter {
  name='Browser Local Storage';
  constructor(area,{now=()=>Date.now()}={}) { super(); this.area=area; this.now=now; this.tail=Promise.resolve(); this.pending=new Map(); this.warnings=[]; this.readOnly=false; }
  async initialize() {
    if (this.ready) return this.ready;
    this.ready=(async()=>{
      const raw=(await this.area.get(STORE_KEY))[STORE_KEY];
      this.state=empty();
      if (!raw) return;
      if (raw.schemaVersion!==SCHEMA) { this.readOnly=true; this.warnings.push('Unsupported repository schema; data preserved, writes disabled.'); return; }
      if (!raw.contexts || typeof raw.contexts!=='object' || Array.isArray(raw.contexts)) { this.readOnly=true; this.warnings.push('Repository envelope is damaged; data preserved.'); return; }
      this.state.settings=normalizeSettings(raw.settings);
      this.state.commit=Number.isSafeInteger(raw.commit)?raw.commit:0;
      for (const [id,record] of Object.entries(raw.contexts)) {
        const valid=migrateSnapshot(record);
        if (valid && valid.id===id) this.state.contexts[id]=valid;
        else this.warnings.push('An invalid or unsupported context was quarantined.');
      }
      this.quarantine=Object.fromEntries(Object.entries(raw.contexts).filter(([id])=>!this.state.contexts[id]));
      this.state.activeId=this.state.contexts[raw.activeId]?.closedAt===null ? raw.activeId : null;
      this.state.selectedAt=Number.isFinite(raw.selectedAt)?raw.selectedAt:null;
    })();
    return this.ready;
  }
  enqueue(change,{clearQuarantine=false,allowReadOnly=false}={}) {
    const work=this.tail.then(async()=>{
      await this.initialize();
      if (this.readOnly&&!allowReadOnly) throw new Error('Storage is read-only because its schema is unsupported or damaged.');
      const next=copy(this.state);
      const quarantine=clearQuarantine?{}:copy(this.quarantine||{});
      const result=await change(next,quarantine);
      if (result?.noop) return result.value;
      next.commit++;
      this.prune(next);
      const persisted={...next,contexts:{...quarantine,...next.contexts}};
      if (bytes(persisted)>LIMITS.totalBytes) throw new Error('Local storage budget exceeded; remove saved sites or reduce retention.');
      await this.area.set({[STORE_KEY]:persisted});
      this.state=next;
      this.quarantine=quarantine;
      this.onCommit?.();
      if(allowReadOnly){this.readOnly=false;this.warnings=[];}
      return result;
    });
    this.tail=work.catch(()=>{});
    return work;
  }
  prune(state) {
    const age=this.now()-state.settings.maxAgeDays*86400000;
    for (const [id,s] of Object.entries(state.contexts)) if (s.updatedAt<age) delete state.contexts[id];
    const sorted=Object.values(state.contexts).sort((a,b)=>b.updatedAt-a.updatedAt);
    for (const s of sorted.slice(state.settings.maxContexts)) if (s.id!==state.activeId) delete state.contexts[s.id];
    // Enforce cap even when the protected active record was outside the newest subset.
    while (Object.keys(state.contexts).length>state.settings.maxContexts) {
      const candidate=Object.values(state.contexts).filter(s=>s.id!==state.activeId).sort((a,b)=>a.updatedAt-b.updatedAt)[0];
      if (!candidate) break;
      delete state.contexts[candidate.id];
    }
    if (!state.contexts[state.activeId]) state.activeId=null;
  }
  save(snapshot,{active=false,epoch}={}) {
    if (!validateSnapshot(snapshot)) return Promise.reject(new Error('Invalid snapshot'));
    // Coalesce only adjacent saves of the same context. Barriers flush pending jobs into FIFO order.
    const key=snapshot.id;
    let slot=this.pending.get(key);
    if (slot && slot.epoch===epoch) {
      if (snapshot.revision>slot.snapshot.revision) slot.snapshot=copy(snapshot);
      slot.active ||= active;
      return slot.promise;
    }
    slot={snapshot:copy(snapshot),active,epoch};
    slot.promise=this.enqueue(next=>{
      this.pending.delete(key);
      const s=slot.snapshot;
      if (slot.epoch!==undefined&&slot.epoch!==next.settings.epoch) throw new Error('Capture policy changed');
      if (!next.settings.tracking||next.settings.excludedOrigins.includes(s.page.origin)) throw new Error('Tracking is disabled for this page');
      const existing=next.contexts[s.id];
      if (existing && existing.revision>=s.revision) return {noop:true,value:copy(existing)};
      if (!next.settings.formOrigins.includes(s.page.origin)) s.records.forms=[];
      next.contexts[s.id]=s;
      if (slot.active) {next.activeId=s.id;next.selectedAt=this.now();}
      return copy(s);
    });
    this.pending.set(key,slot);
    return slot.promise;
  }
  barrier(fn,options) { this.pending.clear(); return this.enqueue(fn,options); }
  async load(id) { await this.initialize();await this.tail; const s=this.state.contexts[id]; return s&&s.updatedAt>=this.now()-this.state.settings.maxAgeDays*86400000?copy(s):null; }
  async loadLatestActive() { await this.initialize();await this.tail; return this.load(this.state.activeId); }
  async list() { await this.initialize();await this.tail; return Object.values(this.state.contexts).filter(s=>s.updatedAt>=this.now()-this.state.settings.maxAgeDays*86400000).sort((a,b)=>b.updatedAt-a.updatedAt).map(copy); }
  async settings() { await this.initialize();await this.tail; return copy(this.state.settings); }
  maintain() { return this.barrier(()=>{}); }
  selectActive(id) { return this.barrier(s=>{s.activeId=s.contexts[id]?.closedAt===null?id:null;s.selectedAt=this.now();}); }
  close(id) { return this.barrier(s=>{if(s.contexts[id]) {s.contexts[id].closedAt=this.now();s.contexts[id].revision++;} if(s.activeId===id)s.activeId=null;}); }
  delete(id) { return this.barrier((s,q)=>{delete s.contexts[id];delete q[id];if(s.activeId===id)s.activeId=null;s.settings.epoch++;}); }
  clear() { return this.barrier(s=>{s.contexts={};s.activeId=null;s.settings.epoch++;s.settings.tracking=false;},{clearQuarantine:true,allowReadOnly:true}); }
  changePolicy(settings,{deleteOrigin=null,purgeForms=null}={}) {
    return this.barrier((s,quarantine)=>{
      const previous=s.settings;
      s.settings=normalizeSettings({...previous,...settings,epoch:previous.epoch+1});
      for(const [id,c] of Object.entries(s.contexts)) {
        if (c.page.origin===deleteOrigin||s.settings.excludedOrigins.includes(c.page.origin)) {delete s.contexts[id];if(s.activeId===id)s.activeId=null;continue;}
        const previousForms=c.records.forms.length;
        if (!s.settings.formOrigins.includes(c.page.origin)||c.page.origin===purgeForms) c.records.forms=[];
        const exclusions=s.settings.fieldExclusions[c.page.origin]||[];
        c.records.forms=c.records.forms.filter(f=>!exclusions.some(x=>f.field.value===x||f.meta.toLowerCase().includes(x.toLowerCase())));
        if(previousForms!==c.records.forms.length)c.revision++;
      }
      for(const [id,c] of Object.entries(quarantine)){const origin=c?.page?.origin;if(origin&&(origin===deleteOrigin||origin===purgeForms||s.settings.excludedOrigins.includes(origin)||previous.formOrigins.includes(origin)&&!s.settings.formOrigins.includes(origin)||JSON.stringify(previous.fieldExclusions[origin])!==JSON.stringify(s.settings.fieldExclusions[origin])))delete quarantine[id];}
      return copy(s.settings);
    });
  }
}
