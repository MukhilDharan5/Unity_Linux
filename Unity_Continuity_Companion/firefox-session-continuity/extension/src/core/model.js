export const SCHEMA = 1;
export const LIMITS = Object.freeze({contexts:100, defaultContexts:40, days:90, defaultDays:30, snapshotBytes:65536, totalBytes:3*1024*1024, media:16, scroll:16, forms:40, value:1024, messageBytes:80000});
export const DEFAULT_SETTINGS = Object.freeze({tracking:true, startupMode:'automatic', formOrigins:[], excludedOrigins:[], fieldExclusions:{}, maxContexts:40, maxAgeDays:30, epoch:1});
export const copy = value => structuredClone(value);
export const bytes = value => new TextEncoder().encode(JSON.stringify(value)).length;
export const uuid = () => crypto.randomUUID();
export const validId = value => typeof value === 'string' && /^[a-zA-Z0-9:_-]{1,160}$/.test(value) && !['__proto__','constructor','prototype'].includes(value);
export const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
export const finite = (n, min=0, max=Number.MAX_SAFE_INTEGER) => typeof n === 'number' && Number.isFinite(n) && n>=min && n<=max;
export const shortText = (s, max=512) => typeof s === 'string' && s.length<=max && !/[\u0000-\u0008]/.test(s);

const SECRET_PARAM = /(?:token|password|passwd|secret|credential|auth|session|jwt|signature|api.?key|reset|recovery|code|ticket|saml|assertion)/i;
const SECRET_PATH = /\/(?:login|signin|sign-in|oauth|authorize|callback|reset(?:-password)?|recover(?:y)?|verify|magic-link)(?:\/|$)/i;
const RESTRICTED = new Set(['addons.mozilla.org','accounts.firefox.com','accounts-static.cdn.mozilla.net','discovery.addons.mozilla.org','oauth.accounts.firefox.com','profile.accounts.firefox.com','support.mozilla.org','sync.services.mozilla.com']);
export function pageIdentity(raw) {
  if (!shortText(raw,4096)) return null;
  try {
    const u = new URL(raw);
    if (!['http:','https:'].includes(u.protocol) || u.username || u.password || RESTRICTED.has(u.hostname) || SECRET_PATH.test(u.pathname)) return null;
    // forEach works across Firefox content-script Xrays; spreading the iterator does not.
    const queryKeys=[];u.searchParams.forEach((_value,key)=>queryKeys.push(key));
    if (queryKeys.some(k=>SECRET_PARAM.test(k)) || /(?:token|password|secret|access_token|code|auth|session|jwt)\s*=/i.test(u.hash)) return null;
    // Exclude likely bearer material in path/fragment; ordinary routes and queries remain exact.
    if (/(?:eyJ[A-Za-z0-9_-]{16,}\.|[A-Fa-f0-9]{48,})/.test(u.pathname+u.hash) || /[A-Za-z0-9_-]{80,}/.test(u.pathname+u.search+u.hash)) return null;
    for (const key of queryKeys) if (/^(utm_.+|fbclid|gclid|mc_cid|mc_eid)$/i.test(key)) u.searchParams.delete(key);
    return {url:u.href, origin:u.origin, route:u.pathname+u.search+u.hash};
  } catch { return null; }
}
export function exactOrigin(raw) { const p=pageIdentity(raw); return p && new URL(p.url).origin === raw ? raw : null; }
export function normalizeSettings(value={}) {
  const origins = a => Array.isArray(a) ? [...new Set(a.filter(x=>exactOrigin(x)))].slice(0,200) : [];
  const fieldExclusions={};
  if (isObject(value.fieldExclusions)) for (const [o,fields] of Object.entries(value.fieldExclusions).slice(0,200)) {
    if (exactOrigin(o) && Array.isArray(fields)) fieldExclusions[o]=fields.filter(x=>shortText(x,200)&&x.length).slice(0,100);
  }
  return {tracking:value.tracking!==false, startupMode:['automatic','manual','disabled'].includes(value.startupMode)?value.startupMode:'automatic', formOrigins:origins(value.formOrigins), excludedOrigins:origins(value.excludedOrigins), fieldExclusions, maxContexts:Number.isInteger(value.maxContexts)?Math.max(1,Math.min(100,value.maxContexts)):40, maxAgeDays:Number.isInteger(value.maxAgeDays)?Math.max(1,Math.min(90,value.maxAgeDays)):30, epoch:Number.isSafeInteger(value.epoch)&&value.epoch>0?value.epoch:1};
}
export function allowedPage(raw,container,privateTab,settings) {
  const p=pageIdentity(raw);
  return Boolean(p && !privateTab && container!=='firefox-private' && settings.tracking && !settings.excludedOrigins.includes(p.origin));
}
export function validLocator(l) {
  return isObject(l) && ['id','name','data'].includes(l.by) && shortText(l.value,200) && l.value.length>0 && shortText(l.tag,20) && /^[a-z]+$/.test(l.tag) && (l.option===undefined||shortText(l.option,200));
}
function validMedia(r) {
  return isObject(r) && shortText(r.key,1000) && /^(generic|youtube):/.test(r.key) && ['audio','video'].includes(r.kind) && finite(r.time,0,8640000) && (r.duration===null||finite(r.duration,0,8640000)) && finite(r.rate,0.25,4) && typeof r.paused==='boolean' && typeof r.muted==='boolean' && finite(r.volume,0,1);
}
function validScroll(r) {
  return isObject(r) && (r.target==='document'||validLocator(r.target)) && ['x','y','width','height','viewportWidth','viewportHeight'].every(k=>finite(r[k],0,1e9)) && finite(r.progressX,0,1) && finite(r.progressY,0,1) && (r.anchor===null || (isObject(r.anchor)&&validLocator(r.anchor.locator)&&finite(r.anchor.offset,-1e6,1e6)));
}
export function validForm(r) {
  if(!isObject(r)||!validLocator(r.field)||(r.form!==null&&!validLocator(r.form))||!shortText(r.meta,600)||sensitiveDescription([r.meta,r.field.value,r.form?.value].filter(Boolean).join(' ')))return false;
  const typed=['checkbox','radio'].includes(r.kind)?typeof r.value==='boolean':r.kind==='select-multiple'?Array.isArray(r.value)&&r.value.length<=20&&r.value.every(v=>shortText(v,200)):['text','textarea','select-one'].includes(r.kind)&&shortText(r.value,LIMITS.value);
  return !!typed&&!sensitiveValue(r.value);
}
export function validateComponents(value, partial=false) {
  if (!isObject(value) || Object.keys(value).some(k=>!['media','scroll','forms'].includes(k))) return false;
  return [['media',LIMITS.media,validMedia],['scroll',LIMITS.scroll,validScroll],['forms',LIMITS.forms,validForm]].every(([k,limit,valid])=>partial&&value[k]===undefined || Array.isArray(value[k])&&value[k].length<=limit&&value[k].every(valid));
}
export function validateSnapshot(s) {
  if (!isObject(s) || s.schemaVersion!==SCHEMA || !validId(s.id)||!validId(s.logicalTabId)||!Number.isSafeInteger(s.revision)||s.revision<1||!validId(s.documentToken)||!Number.isSafeInteger(s.generation)||s.generation<0) return false;
  const p=pageIdentity(s.page?.url);
  return !!p && p.url===s.page.url && p.origin===s.page.origin && p.route===s.page.route && shortText(s.container,100) && /^firefox-(default|container-\d+)$/.test(s.container) && finite(s.createdAt) && finite(s.updatedAt) && (s.closedAt===null||finite(s.closedAt)) && validateComponents(s.records) && bytes(s)<=LIMITS.snapshotBytes;
}
export function migrateSnapshot(s) {
  // v1 is the first released schema. Unknown older/future versions are quarantined, never guessed.
  return validateSnapshot(s)?copy(s):null;
}
export function newSnapshot({id=uuid(),logicalTabId,container,page,documentToken,generation=0},now=Date.now()) {
  return {schemaVersion:SCHEMA,id,logicalTabId,container,page,documentToken,generation,revision:1,createdAt:now,updatedAt:now,closedAt:null,records:{media:[],scroll:[],forms:[]}};
}

const SENSITIVE = /pass(?:word|phrase|wd)?|pwd|pin(?:\b|code)|one.?time|\botp\b|\b2fa\b|\bmfa\b|auth|secret|token|credential|api.?key|private.?key|recovery|seed|mnemonic|wallet|credit|debit|card|\bcvv\b|\bcvc\b|\bcc-|iban|bank|routing|account.?number|social.?security|\bssn\b|security.?code|verification|verify|payment|checkout|sign.?in|log.?in/i;
export function sensitiveDescription(s) { return typeof s!=='string'||SENSITIVE.test(s); }
export function sensitiveValue(v) {
  if (typeof v==='boolean') return false;
  const s=Array.isArray(v)?v.join(' '):String(v);
  return /-----BEGIN|\b(?:sk|pk|ghp|gho|xoxb|xoxp)[-_][A-Za-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.|\b(?:\d[ -]?){13,19}\b|\b\d{4,8}\b|\b[0-9a-f]{32,}\b/i.test(s) || s.trim().split(/\s+/).length>=12 && /(?:seed|recovery|private|secret)/i.test(s);
}
