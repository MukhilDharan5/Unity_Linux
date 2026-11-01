import {uuid,pageIdentity,copy,validateSnapshot} from '../core/model.js';
import {chooseExisting} from '../core/gate.js';
export const componentResults=(status,reason)=>Object.fromEntries(['media','scroll','forms'].map(k=>[k,{status,reason}]));

/** Browser reconciliation is separate from storage and page restoration. */
export class RestoreCoordinator {
  constructor(host,{reconcileMs=5000,readyMs=18000,now=()=>Date.now(),wait=ms=>new Promise(r=>setTimeout(r,ms))}={}) {this.host=host;this.reconcileMs=reconcileMs;this.readyMs=readyMs;this.now=now;this.wait=wait;this.current=null;}
  cancel(reason='User changed context') { if(this.current) {this.current.cancelled=reason;this.host.cancelContent(this.current); } }
  async run(target,{startup=false}={}) {
    if(this.current) return {status:'cancelled',reason:'Another restoration is in progress'};
    if(!validateSnapshot(target)||target.closedAt!==null||!pageIdentity(target.page.url)) return {status:'unsupported',...componentResults('unsupported','No eligible saved context')};
    const op={id:uuid(),target:copy(target),tabId:null,state:'reconciling',cancelled:null,startup,startedAt:this.now()};
    this.current=op;
    this.host.onState(op);
    let result;
    try {
      if(!await this.host.canRestore(target)) throw new Error('Permission, tracking, or container is unavailable');
      if(startup)op.focusGuard=await this.host.initialFocus?.();
      const deadline=this.now()+(startup?this.reconcileMs:0);
      let match;
      do {
        if(op.cancelled) break;
        match=await this.host.match(target);
        if(match.tab||match.ambiguous) break;
        if(this.now()<deadline) await this.wait(Math.min(250,deadline-this.now()));
      } while(this.now()<deadline);
      if(op.cancelled) return result=this.cancelled(op);
      // Final fresh query closes the ordinary late native-session-restore race.
      match=await this.host.match(target);
      if(op.cancelled) return result=this.cancelled(op);
      if(match.ambiguous) return result={status:'partial',...componentResults('unsupported','Multiple existing tabs match; select a saved context in its original tab')};
      if(match.tab) op.tabId=match.tab.id;
      else {
        if(!await this.host.canRestore(target)||op.cancelled) return result=this.cancelled(op);
        op.created=true;
        const tab=await this.host.create(target);
        op.tabId=tab.id;
      }
      op.state='waiting';this.host.onState(op);
      await this.host.bind(op);
      const readyDeadline=this.now()+this.readyMs;
      while(this.now()<readyDeadline) {
        if(op.cancelled) return result=this.cancelled(op);
        const samePage=await this.host.samePage(op);
        if(samePage===null){await this.wait(100);continue;}
        if(!samePage) return result={status:'cancelled',...componentResults('cancelled','Navigation or redirect changed the intended page')};
        const response=await this.host.ready(op);
        if(response) {
          await this.host.focus?.(op);
          op.state='restoring';this.host.onState(op);
          result=await this.host.deliver(op,response);
          return result;
        }
        await this.wait(200);
      }
      return result={status:'partial',...componentResults('unsupported','Page unavailable, restricted, discarded, or not ready within the deadline')};
    } catch {
      return result={status:'failed',...componentResults('failed','Restoration could not access the requested page or container')};
    } finally {
      op.state=result?.status||'failed';this.host.onDone(op,result);this.current=null;
    }
  }
  cancelled(op) {return {status:'cancelled',...componentResults('cancelled',op.cancelled||'Operation cancelled')};}
}

export async function matchBrowserTabs(browser,target) {
  const windows=await browser.windows.getAll({windowTypes:['normal']});
  const windowIds=new Set(windows.filter(w=>!w.incognito).map(w=>w.id));
  const tabs=(await browser.tabs.query({})).filter(t=>windowIds.has(t.windowId));
  const identities=new Map(await Promise.all(tabs.map(async t=>[t.id,await browser.sessions.getTabValue(t.id,'continuityIdentity').catch(()=>null)])));
  return chooseExisting(tabs,target,identities);
}
