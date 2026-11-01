import {pageIdentity,shortText,LIMITS} from '../core/model.js';
import {isYouTube,youtubeIdentity} from './youtube.js';
export function mediaIdentity(el,url) {
  if(isYouTube(url))return youtubeIdentity(el,url);
  const explicit=el.getAttribute('data-continuity-id')||el.getAttribute('data-content-id');
  if(explicit&&shortText(explicit,200)&&/^[\w. :/-]+$/.test(explicit))return `generic:${el.localName}:content:${explicit}`;
  const source=el.currentSrc||el.getAttribute('src')||el.querySelector('source[src]')?.getAttribute('src');
  if(!source)return null;
  let absolute;try{absolute=new URL(source,url).href;}catch{return null;}
  const p=pageIdentity(absolute);
  // Blob/data/MSE and signed stream URLs need an explicit stable content identifier.
  return p?`generic:${el.localName}:src:${p.url}`:null;
}
export function captureMedia(elements,url) {
  const pairs=[...elements].filter(e=>e.isConnected).map(el=>({el,key:mediaIdentity(el,url)}));
  return pairs.filter(p=>p.key&&pairs.filter(q=>q.key===p.key).length===1).slice(0,LIMITS.media).flatMap(({el,key})=>{
    if(!Number.isFinite(el.currentTime)||el.currentTime<0||!Number.isFinite(el.playbackRate))return [];
    return [{key,kind:el.localName,time:el.currentTime,duration:Number.isFinite(el.duration)?el.duration:null,rate:Math.max(.25,Math.min(4,el.playbackRate)),paused:el.paused,muted:el.muted,volume:el.volume}];
  });
}
export function seekTarget(record,duration,ranges) {
  if(record.duration===null||!Number.isFinite(duration)||duration<=0||!ranges.length)return null;
  const desired=Math.min(Math.max(record.time,0),Math.max(0,duration-0.1));
  const choices=[];
  for(let i=0;i<ranges.length;i++){const start=ranges.start(i),end=Math.min(ranges.end(i),duration);if(Number.isFinite(start)&&Number.isFinite(end)&&end>start)choices.push(Math.max(start,Math.min(desired,Math.max(start,end-.05))));}
  return choices.sort((a,b)=>Math.abs(a-desired)-Math.abs(b-desired))[0]??null;
}
export class MediaRestore {
  constructor(records,registry,url) {this.items=records.map(record=>({record,status:'pending',element:null,applied:false}));this.registry=registry;this.url=url;this.cancelled=false;}
  cancel() {this.cancelled=true;for(const i of this.items){if(i.playPending){i.element?.pause();i.status='cancelled';}else if(i.status==='pending')i.status='cancelled';}}
  tick() {
    if(this.cancelled)return;
    for(const item of this.items) {
      if(item.status!=='pending')continue;
      const {record}=item;
      if(record.duration===null){item.status='unsupported';continue;}
      const candidates=[...this.registry.media].filter(el=>el.isConnected&&mediaIdentity(el,this.url)===record.key);
      if(candidates.length!==1)continue;
      const el=candidates[0];
      if(item.element&&item.element!==el){item.status='partial';continue;}
      if(el.readyState<1)continue;
      const desired=seekTarget(record,el.duration,el.seekable);if(desired===null)continue;
      if(!item.applied) {
        item.element=el;item.applied=true;item.target=desired;
        try {el.pause();el.playbackRate=record.rate;el.muted=record.muted;el.volume=record.volume;if(Math.abs(el.currentTime-desired)>.5)el.currentTime=desired;}
        catch {item.status='failed';continue;}
      }
      if(!el.seeking&&Math.abs(el.currentTime-item.target)<=1.5) {
        item.status=Math.abs(item.target-record.time)>1.5?'partial':'completed';
        if(!record.paused&&!this.cancelled) {
          item.playPending=true;
          try {Promise.resolve(el.play()).catch(()=>{if(!this.cancelled){el.pause();item.status='partial';}}).finally(()=>{item.playPending=false;});}
          catch {el.pause();item.status='partial';item.playPending=false;}
        }
      }
    }
  }
  result(final=false) {
    if(!this.items.length)return {status:'unsupported',reason:'No supported media was saved'};
    if(final)for(const i of this.items){if(i.status==='pending')i.status=i.applied?'partial':'unsupported';if(i.playPending)i.status='partial';}
    const statuses=this.items.map(i=>i.status);
    return {status:statuses.every(s=>s==='completed')?'completed':statuses.every(s=>s==='cancelled')?'cancelled':statuses.every(s=>s==='unsupported')?'unsupported':statuses.includes('pending')?'pending':'partial',restored:statuses.filter(s=>s==='completed').length,attempted:this.items.filter(i=>i.applied).length,total:statuses.length,reason:'Finite media only. Partial includes seeking/playback that did not finish before the deadline; unmatched identity, ads, and live streams are skipped.'};
  }
  done() {return this.items.every(i=>i.status!=='pending'&&!i.playPending);}
}
