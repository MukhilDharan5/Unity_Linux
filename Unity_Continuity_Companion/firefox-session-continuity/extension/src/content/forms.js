import {sensitiveDescription,sensitiveValue,LIMITS,validForm} from '../core/model.js';
import {locator,locate} from './dom.js';
export function fieldDescriptor(el,policy) {
  if(!policy?.formsAllowed||!el.isConnected||el.disabled||el.readOnly||el.isContentEditable||el.closest('[contenteditable="true"]'))return null;
  const tag=el.localName;let kind;
  if(tag==='textarea')kind='textarea';
  else if(tag==='select')kind=el.multiple?'select-multiple':'select-one';
  else if(tag==='input'&&['text','checkbox','radio'].includes(el.type))kind=el.type;
  else return null;
  const form=el.form;const formLocator=form?locator(form):null;
  if(form&&!formLocator)return null;
  if(form?.querySelector('input[type=password],input[autocomplete*="cc-"],input[autocomplete*="one-time-code"]'))return null;
  const context=el.closest('fieldset')?.querySelector('legend')?.textContent||'';
  const meta=[el.name,el.id,el.getAttribute('autocomplete'),el.getAttribute('aria-label'),el.getAttribute('placeholder'),...[...(el.labels||[])].map(l=>l.textContent),form?.id,form?.name,form?.getAttribute('autocomplete'),context].filter(Boolean).join(' ').slice(0,2000);
  if(sensitiveDescription(meta)||(policy.fieldExclusions||[]).some(x=>meta.toLowerCase().includes(x.toLowerCase())))return null;
  const field=locator(el,form||el.ownerDocument);if(!field)return null;
  if((policy.fieldExclusions||[]).includes(field.value))return null;
  return {field,form:formLocator,kind,meta:meta.slice(0,600)};
}
export function formValue(el) {if(['checkbox','radio'].includes(el.type))return el.checked;if(el.multiple)return [...el.selectedOptions].map(o=>o.value);return el.value;}
export function captureForms(elements,policy) {
  if(!policy.formsAllowed)return [];
  const records=[];
  for(const el of elements) {
    const descriptor=fieldDescriptor(el,policy);if(!descriptor)continue;
    const value=formValue(el);if(sensitiveValue(value))continue;
    const record={...descriptor,value};if(validForm(record))records.push(record);
    if(records.length===LIMITS.forms)break;
  }
  return records;
}
export function pristineForRestore(el,edited) {
  if(edited.has(el))return false;
  if(el.type==='radio'&&el.name){const root=el.form||el.ownerDocument;if([...root.querySelectorAll('input[type="radio"]')].some(other=>other.form===el.form&&other.name===el.name&&(other.checked||other.defaultChecked||edited.has(other))))return false;}
  if(['checkbox','radio'].includes(el.type))return !el.checked&&!el.defaultChecked;
  if(el.multiple)return el.selectedOptions.length===0;
  return el.value==='';
}
export class FormRestore {
  constructor(records,doc,policy,edited) {this.items=records.map(record=>({record,status:'pending'}));this.doc=doc;this.policy=policy;this.edited=edited;}
  cancel() {for(const i of this.items)if(i.status==='pending')i.status='cancelled';}
  tick() {
    for(const item of this.items) {
      if(item.status!=='pending')continue;
      if(!this.policy.formsAllowed){item.status='unsupported';continue;}
      const r=item.record;const form=r.form?locate(this.doc,r.form):null;
      if(r.form&&!form)continue;
      const el=locate(form||this.doc,r.field);if(!el)continue;
      if((el.form||null)!==form||!fieldDescriptor(el,this.policy)||!validForm(r)){item.status='unsupported';continue;}
      if(JSON.stringify(formValue(el))===JSON.stringify(r.value)){item.status='completed';continue;}
      if(!pristineForRestore(el,this.edited)){item.status='cancelled';continue;}
      try {
        if(r.kind==='checkbox'||r.kind==='radio')el.checked=r.value;
        else if(r.kind==='select-multiple'){for(const o of el.options)o.selected=Array.isArray(r.value)&&r.value.includes(o.value);}
        else el.value=r.value;
        item.status=JSON.stringify(formValue(el))===JSON.stringify(r.value)?'completed':'partial';
        // Deliberately no synthetic input/change events: arbitrary handlers can submit workflows.
      } catch {item.status='failed';}
    }
  }
  done(){return this.items.every(i=>i.status!=='pending');}
  result(final=false){
    if(!this.items.length)return {status:'unsupported',reason:'No approved form values saved'};
    if(final)for(const i of this.items)if(i.status==='pending')i.status='unsupported';
    const states=this.items.map(i=>i.status);
    return {status:states.every(s=>s==='completed')?'completed':states.every(s=>s==='cancelled')?'cancelled':states.every(s=>s==='unsupported')?'unsupported':states.includes('pending')?'pending':'partial',restored:states.filter(s=>s==='completed').length,total:states.length,reason:'User edits, populated fields, ambiguous matches, and sensitive fields are preserved'};
  }
}
