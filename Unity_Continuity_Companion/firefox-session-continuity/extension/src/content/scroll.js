import {locator,locate} from './dom.js';
export function scrollRecord(doc,element=null) {
  const win=doc.defaultView;const el=element||doc.scrollingElement;if(!el)return null;
  const target=element?locator(el):'document';if(!target)return null;
  const viewportWidth=element?el.clientWidth:win.innerWidth,viewportHeight=element?el.clientHeight:win.innerHeight;
  const x=element?el.scrollLeft:win.scrollX,y=element?el.scrollTop:win.scrollY;
  const width=el.scrollWidth,height=el.scrollHeight;
  const rect=element?el.getBoundingClientRect():{left:0,top:0};
  let anchor=null;
  const hit=doc.elementFromPoint?.(Math.max(1,rect.left+20),Math.max(1,rect.top+60));
  const candidate=hit?.closest('[id],[data-continuity-id]');
  const l=candidate&&candidate!==el&&(element?el.contains(candidate):true)?locator(candidate):null;
  if(l)anchor={locator:l,offset:candidate.getBoundingClientRect().top-rect.top};
  return {target,x:Math.max(0,x),y:Math.max(0,y),width,height,viewportWidth,viewportHeight,progressX:width>viewportWidth?Math.min(1,Math.max(0,x/(width-viewportWidth))):0,progressY:height>viewportHeight?Math.min(1,Math.max(0,y/(height-viewportHeight))):0,anchor};
}
export function scrollDestination(record,metrics,{anchorY=null,final=false}={}) {
  if(anchorY!==null)return {x:record.x,y:Math.max(0,anchorY),approximate:false};
  const maxY=Math.max(0,metrics.height-metrics.viewportHeight),maxX=Math.max(0,metrics.width-metrics.viewportWidth);
  const similar=Math.abs(metrics.height-record.height)<=Math.max(40,record.height*.08)&&Math.abs(metrics.viewportHeight-record.viewportHeight)<100;
  if(!similar&&metrics.height<record.height*.9&&!final)return null;
  return {x:similar?Math.min(record.x,maxX):record.progressX*maxX,y:similar?Math.min(record.y,maxY):record.progressY*maxY,approximate:!similar};
}
export class ScrollRestore {
  constructor(records,doc) {this.items=records.map(record=>({record,status:'pending'}));this.doc=doc;}
  cancel() {for(const i of this.items)if(i.status==='pending')i.status='cancelled';}
  tick(final=false) {
    for(const item of this.items) {
      if(item.status!=='pending'||item.applied)continue;
      const r=item.record;const nested=r.target!=='document';const el=nested?locate(this.doc,r.target):this.doc.scrollingElement;
      if(!el)continue;
      const current=scrollRecord(this.doc,nested?el:null);if(!current)continue;
      const anchor=r.anchor&&locate(this.doc,r.anchor.locator);
      const validAnchor=anchor&&(!nested||el.contains(anchor));
      const anchorY=validAnchor?current.y+anchor.getBoundingClientRect().top-(nested?el.getBoundingClientRect().top:0)-r.anchor.offset:null;
      const target=scrollDestination(r,current,{anchorY,final});if(!target)continue;
      item.applied=true;
      try {
        if(nested)el.scrollTo({left:target.x,top:target.y,behavior:'instant'});else this.doc.defaultView.scrollTo({left:target.x,top:target.y,behavior:'instant'});
        const actual=nested?el.scrollTop:this.doc.defaultView.scrollY;
        item.status=Math.abs(actual-target.y)<=5&&!target.approximate?'completed':'partial';
      }catch{item.status='failed';}
    }
  }
  done(){return this.items.every(i=>i.status!=='pending');}
  result(final=false){
    if(!this.items.length)return {status:'unsupported',reason:'No reading position saved'};
    if(final)for(const i of this.items)if(i.status==='pending')i.status='unsupported';
    const states=this.items.map(i=>i.status);
    return {status:states.every(s=>s==='completed')?'completed':states.every(s=>s==='cancelled')?'cancelled':states.every(s=>s==='unsupported')?'unsupported':states.includes('pending')?'pending':'partial',restored:states.filter(s=>s==='completed'||s==='partial').length,cancelled:states.filter(s=>s==='cancelled').length,total:states.length,reason:'Anchors first; relative fallback is approximate. Missing infinite-scroll content is not reconstructed.'};
  }
}
