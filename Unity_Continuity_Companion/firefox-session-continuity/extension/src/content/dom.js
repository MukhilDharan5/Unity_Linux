import {shortText,validLocator} from '../core/model.js';
export function locate(root,l) {
  if(!validLocator(l))return null;
  const attr={id:'id',name:'name',data:'data-continuity-id'}[l.by];
  try {
    const matches=[...root.querySelectorAll(`${l.tag}[${attr}="${CSS.escape(l.value)}"]`)].filter(el=>l.option===undefined||el.value===l.option);
    return matches.length===1?matches[0]:null;
  } catch {return null;}
}
export function locator(el,root=el.ownerDocument) {
  if(!el?.tagName)return null;
  for(const [by,attr] of [['data','data-continuity-id'],['id','id'],['name','name']]) {
    const value=el.getAttribute(attr);if(!value||!shortText(value,200))continue;
    const l={by,value,tag:el.localName};
    if(el.type==='radio'&&by==='name'&&shortText(el.value,200))l.option=el.value;
    if(locate(root,l)===el)return l;
  }
  return null;
}
export const sameLocator=(a,b)=>JSON.stringify(a)===JSON.stringify(b);

/** Incremental discovery: at most 200 nodes/task; bounded queue and retained sets. */
export class Registry {
  constructor(doc,onChange) {this.doc=doc;this.onChange=onChange;this.media=new Set();this.fields=new Set();this.roots=[];this.walker=null;this.timer=null;this.observer=null;}
  start() {
    if(this.observer)return;
    this.observer=new MutationObserver(records=>{
      for(const r of records.slice(0,100))for(let i=0;i<Math.min(100,r.addedNodes.length);i++)this.add(r.addedNodes[i]);
      this.prune();this.onChange();
    });
    this.observer.observe(this.doc,{childList:true,subtree:true});this.add(this.doc);
  }
  add(root) {if(!root||this.roots.includes(root)||this.roots.length>=200)return;this.roots.push(root);this.schedule();}
  schedule() {if(this.timer===null)this.timer=setTimeout(()=>{this.timer=null;this.drain();},0);}
  drain() {
    let budget=200;
    while(budget-->0) {
      if(!this.walker) {const root=this.roots.shift();if(!root)break;this.inspect(root);this.walker=this.doc.createTreeWalker(root,1);}
      const n=this.walker.nextNode();if(n)this.inspect(n);else this.walker=null;
    }
    this.prune();this.onChange();if(this.walker||this.roots.length)this.schedule();
  }
  inspect(el) {if(el.matches?.('video,audio')&&this.media.size<16)this.media.add(el);if(el.matches?.('input,textarea,select')&&this.fields.size<300)this.fields.add(el);}
  prune() {for(const set of [this.media,this.fields])for(const el of set)if(!el.isConnected)set.delete(el);}
  stop() {this.observer?.disconnect();this.observer=null;clearTimeout(this.timer);this.timer=null;this.roots=[];this.walker=null;this.media.clear();this.fields.clear();}
}

export function debounceBounded(fn,delay,maxWait) {
  let short=null,long=null;
  const flush=()=>{clearTimeout(short);clearTimeout(long);short=long=null;return fn();};
  const run=()=>{clearTimeout(short);short=setTimeout(flush,delay);if(long===null)long=setTimeout(flush,maxWait);};
  run.flush=flush;run.cancel=()=>{clearTimeout(short);clearTimeout(long);short=long=null;};return run;
}
