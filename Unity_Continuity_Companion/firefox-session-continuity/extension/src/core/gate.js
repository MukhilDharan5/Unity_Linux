import {bytes,LIMITS,validId,validateComponents,pageIdentity,isObject,finite} from './model.js';
export function validMessage(m) {
  try { return isObject(m)&&typeof m.type==='string'&&bytes(m)<=LIMITS.messageBytes; } catch { return false; }
}
export function acceptCapture(m,binding,epoch) {
  return validMessage(m) && m.type==='CAPTURE' && binding && m.documentToken===binding.documentToken && m.generation===binding.generation && m.epoch===epoch && m.contextId===binding.contextId && pageIdentity(m.url)?.url===binding.url && Number.isSafeInteger(m.sequence) && m.sequence>binding.sequence && validateComponents(m.records,true);
}
export function validHello(m) {
  return validMessage(m)&&m.type==='HELLO'&&validId(m.documentToken)&&finite(m.documentStartedAt,1,Date.now()+60000)&&!!pageIdentity(m.url);
}
export function chooseExisting(tabs,target,identities=new Map()) {
  const candidates=tabs.filter(t=>!t.incognito&&t.cookieStoreId===target.container&&pageIdentity(t.url)?.url===target.page.url);
  const identified=candidates.filter(t=>identities.get(t.id)?.contextId===target.id&&identities.get(t.id)?.logicalTabId===target.logicalTabId);
  if (identified.length===1) return {tab:identified[0],ambiguous:false};
  if (identified.length>1) return {tab:null,ambiguous:true};
  // Never commandeer a tab already bound to another saved context, even for the same URL.
  const unbound=candidates.filter(t=>!identities.get(t.id)?.contextId);
  if(candidates.length===1&&unbound.length===1) return {tab:unbound[0],ambiguous:false};
  return {tab:null,ambiguous:candidates.length>0};
}
