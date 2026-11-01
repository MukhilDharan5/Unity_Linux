(() => {
  // extension/src/core/model.js
  var SCHEMA = 1;
  var LIMITS = Object.freeze({ contexts: 100, defaultContexts: 40, days: 90, defaultDays: 30, snapshotBytes: 65536, totalBytes: 3 * 1024 * 1024, media: 16, scroll: 16, forms: 40, value: 1024, messageBytes: 8e4 });
  var DEFAULT_SETTINGS = Object.freeze({ tracking: true, startupMode: "automatic", formOrigins: [], excludedOrigins: [], fieldExclusions: {}, maxContexts: 40, maxAgeDays: 30, epoch: 1 });
  var copy = (value) => structuredClone(value);
  var bytes = (value) => new TextEncoder().encode(JSON.stringify(value)).length;
  var uuid = () => crypto.randomUUID();
  var validId = (value) => typeof value === "string" && /^[a-zA-Z0-9:_-]{1,160}$/.test(value) && !["__proto__", "constructor", "prototype"].includes(value);
  var isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
  var finite = (n, min = 0, max = Number.MAX_SAFE_INTEGER) => typeof n === "number" && Number.isFinite(n) && n >= min && n <= max;
  var shortText = (s, max = 512) => typeof s === "string" && s.length <= max && !/[\u0000-\u0008]/.test(s);
  var SECRET_PARAM = /(?:token|password|passwd|secret|credential|auth|session|jwt|signature|api.?key|reset|recovery|code|ticket|saml|assertion)/i;
  var SECRET_PATH = /\/(?:login|signin|sign-in|oauth|authorize|callback|reset(?:-password)?|recover(?:y)?|verify|magic-link)(?:\/|$)/i;
  var RESTRICTED = /* @__PURE__ */ new Set(["addons.mozilla.org", "accounts.firefox.com", "accounts-static.cdn.mozilla.net", "discovery.addons.mozilla.org", "oauth.accounts.firefox.com", "profile.accounts.firefox.com", "support.mozilla.org", "sync.services.mozilla.com"]);
  function pageIdentity(raw) {
    if (!shortText(raw, 4096)) return null;
    try {
      const u = new URL(raw);
      if (!["http:", "https:"].includes(u.protocol) || u.username || u.password || RESTRICTED.has(u.hostname) || SECRET_PATH.test(u.pathname)) return null;
      const queryKeys = [];
      u.searchParams.forEach((_value, key) => queryKeys.push(key));
      if (queryKeys.some((k) => SECRET_PARAM.test(k)) || /(?:token|password|secret|access_token|code|auth|session|jwt)\s*=/i.test(u.hash)) return null;
      if (/(?:eyJ[A-Za-z0-9_-]{16,}\.|[A-Fa-f0-9]{48,})/.test(u.pathname + u.hash) || /[A-Za-z0-9_-]{80,}/.test(u.pathname + u.search + u.hash)) return null;
      for (const key of queryKeys) if (/^(utm_.+|fbclid|gclid|mc_cid|mc_eid)$/i.test(key)) u.searchParams.delete(key);
      return { url: u.href, origin: u.origin, route: u.pathname + u.search + u.hash };
    } catch {
      return null;
    }
  }
  function validLocator(l) {
    return isObject(l) && ["id", "name", "data"].includes(l.by) && shortText(l.value, 200) && l.value.length > 0 && shortText(l.tag, 20) && /^[a-z]+$/.test(l.tag) && (l.option === void 0 || shortText(l.option, 200));
  }
  function validMedia(r) {
    return isObject(r) && shortText(r.key, 1e3) && /^(generic|youtube):/.test(r.key) && ["audio", "video"].includes(r.kind) && finite(r.time, 0, 864e4) && (r.duration === null || finite(r.duration, 0, 864e4)) && finite(r.rate, 0.25, 4) && typeof r.paused === "boolean" && typeof r.muted === "boolean" && finite(r.volume, 0, 1);
  }
  function validScroll(r) {
    return isObject(r) && (r.target === "document" || validLocator(r.target)) && ["x", "y", "width", "height", "viewportWidth", "viewportHeight"].every((k) => finite(r[k], 0, 1e9)) && finite(r.progressX, 0, 1) && finite(r.progressY, 0, 1) && (r.anchor === null || isObject(r.anchor) && validLocator(r.anchor.locator) && finite(r.anchor.offset, -1e6, 1e6));
  }
  function validForm(r) {
    if (!isObject(r) || !validLocator(r.field) || r.form !== null && !validLocator(r.form) || !shortText(r.meta, 600) || sensitiveDescription([r.meta, r.field.value, r.form?.value].filter(Boolean).join(" "))) return false;
    const typed = ["checkbox", "radio"].includes(r.kind) ? typeof r.value === "boolean" : r.kind === "select-multiple" ? Array.isArray(r.value) && r.value.length <= 20 && r.value.every((v) => shortText(v, 200)) : ["text", "textarea", "select-one"].includes(r.kind) && shortText(r.value, LIMITS.value);
    return !!typed && !sensitiveValue(r.value);
  }
  function validateComponents(value, partial = false) {
    if (!isObject(value) || Object.keys(value).some((k) => !["media", "scroll", "forms"].includes(k))) return false;
    return [["media", LIMITS.media, validMedia], ["scroll", LIMITS.scroll, validScroll], ["forms", LIMITS.forms, validForm]].every(([k, limit, valid]) => partial && value[k] === void 0 || Array.isArray(value[k]) && value[k].length <= limit && value[k].every(valid));
  }
  function validateSnapshot(s) {
    if (!isObject(s) || s.schemaVersion !== SCHEMA || !validId(s.id) || !validId(s.logicalTabId) || !Number.isSafeInteger(s.revision) || s.revision < 1 || !validId(s.documentToken) || !Number.isSafeInteger(s.generation) || s.generation < 0) return false;
    const p = pageIdentity(s.page?.url);
    return !!p && p.url === s.page.url && p.origin === s.page.origin && p.route === s.page.route && shortText(s.container, 100) && /^firefox-(default|container-\d+)$/.test(s.container) && finite(s.createdAt) && finite(s.updatedAt) && (s.closedAt === null || finite(s.closedAt)) && validateComponents(s.records) && bytes(s) <= LIMITS.snapshotBytes;
  }
  var SENSITIVE = /pass(?:word|phrase|wd)?|pwd|pin(?:\b|code)|one.?time|\botp\b|\b2fa\b|\bmfa\b|auth|secret|token|credential|api.?key|private.?key|recovery|seed|mnemonic|wallet|credit|debit|card|\bcvv\b|\bcvc\b|\bcc-|iban|bank|routing|account.?number|social.?security|\bssn\b|security.?code|verification|verify|payment|checkout|sign.?in|log.?in/i;
  function sensitiveDescription(s) {
    return typeof s !== "string" || SENSITIVE.test(s);
  }
  function sensitiveValue(v) {
    if (typeof v === "boolean") return false;
    const s = Array.isArray(v) ? v.join(" ") : String(v);
    return /-----BEGIN|\b(?:sk|pk|ghp|gho|xoxb|xoxp)[-_][A-Za-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.|\b(?:\d[ -]?){13,19}\b|\b\d{4,8}\b|\b[0-9a-f]{32,}\b/i.test(s) || s.trim().split(/\s+/).length >= 12 && /(?:seed|recovery|private|secret)/i.test(s);
  }

  // extension/src/content/dom.js
  function locate(root, l) {
    if (!validLocator(l)) return null;
    const attr = { id: "id", name: "name", data: "data-continuity-id" }[l.by];
    try {
      const matches = [...root.querySelectorAll(`${l.tag}[${attr}="${CSS.escape(l.value)}"]`)].filter((el) => l.option === void 0 || el.value === l.option);
      return matches.length === 1 ? matches[0] : null;
    } catch {
      return null;
    }
  }
  function locator(el, root = el.ownerDocument) {
    if (!el?.tagName) return null;
    for (const [by, attr] of [["data", "data-continuity-id"], ["id", "id"], ["name", "name"]]) {
      const value = el.getAttribute(attr);
      if (!value || !shortText(value, 200)) continue;
      const l = { by, value, tag: el.localName };
      if (el.type === "radio" && by === "name" && shortText(el.value, 200)) l.option = el.value;
      if (locate(root, l) === el) return l;
    }
    return null;
  }
  var Registry = class {
    constructor(doc, onChange) {
      this.doc = doc;
      this.onChange = onChange;
      this.media = /* @__PURE__ */ new Set();
      this.fields = /* @__PURE__ */ new Set();
      this.roots = [];
      this.walker = null;
      this.timer = null;
      this.observer = null;
    }
    start() {
      if (this.observer) return;
      this.observer = new MutationObserver((records) => {
        for (const r of records.slice(0, 100)) for (let i = 0; i < Math.min(100, r.addedNodes.length); i++) this.add(r.addedNodes[i]);
        this.prune();
        this.onChange();
      });
      this.observer.observe(this.doc, { childList: true, subtree: true });
      this.add(this.doc);
    }
    add(root) {
      if (!root || this.roots.includes(root) || this.roots.length >= 200) return;
      this.roots.push(root);
      this.schedule();
    }
    schedule() {
      if (this.timer === null) this.timer = setTimeout(() => {
        this.timer = null;
        this.drain();
      }, 0);
    }
    drain() {
      let budget = 200;
      while (budget-- > 0) {
        if (!this.walker) {
          const root = this.roots.shift();
          if (!root) break;
          this.inspect(root);
          this.walker = this.doc.createTreeWalker(root, 1);
        }
        const n = this.walker.nextNode();
        if (n) this.inspect(n);
        else this.walker = null;
      }
      this.prune();
      this.onChange();
      if (this.walker || this.roots.length) this.schedule();
    }
    inspect(el) {
      if (el.matches?.("video,audio") && this.media.size < 16) this.media.add(el);
      if (el.matches?.("input,textarea,select") && this.fields.size < 300) this.fields.add(el);
    }
    prune() {
      for (const set of [this.media, this.fields]) for (const el of set) if (!el.isConnected) set.delete(el);
    }
    stop() {
      this.observer?.disconnect();
      this.observer = null;
      clearTimeout(this.timer);
      this.timer = null;
      this.roots = [];
      this.walker = null;
      this.media.clear();
      this.fields.clear();
    }
  };
  function debounceBounded(fn, delay, maxWait) {
    let short = null, long = null;
    const flush = () => {
      clearTimeout(short);
      clearTimeout(long);
      short = long = null;
      return fn();
    };
    const run = () => {
      clearTimeout(short);
      short = setTimeout(flush, delay);
      if (long === null) long = setTimeout(flush, maxWait);
    };
    run.flush = flush;
    run.cancel = () => {
      clearTimeout(short);
      clearTimeout(long);
      short = long = null;
    };
    return run;
  }

  // extension/src/content/youtube.js
  function youtubeVideoId(raw) {
    try {
      const u = new URL(raw);
      if (!["www.youtube.com", "youtube.com", "m.youtube.com"].includes(u.hostname)) return null;
      const id = u.pathname === "/watch" ? u.searchParams.get("v") : u.pathname.match(/^\/shorts\/([\w-]{11})(?:\/|$)/)?.[1];
      return /^[\w-]{11}$/.test(id || "") ? id : null;
    } catch {
      return null;
    }
  }
  function isYouTube(raw) {
    try {
      return /(^|\.)youtube\.com$/.test(new URL(raw).hostname);
    } catch {
      return false;
    }
  }
  function youtubeIdentity(media, raw) {
    const id = youtubeVideoId(raw);
    if (!id || media.localName !== "video") return null;
    const doc = media.ownerDocument;
    const player = media.closest("#movie_player,.html5-video-player");
    if (!player || player.matches(".ad-showing,.ad-interrupting")) return null;
    for (const overlay of player.querySelectorAll(".ytp-ad-player-overlay")) if (!overlay.hidden && overlay.getClientRects().length && doc.defaultView.getComputedStyle(overlay).visibility !== "hidden") return null;
    const watch = media.closest("ytd-watch-flexy[video-id]");
    const shorts = media.closest("ytd-reel-video-renderer[is-active][video-id]");
    const declared = watch?.getAttribute("video-id") || shorts?.getAttribute("video-id");
    if (declared !== id) return null;
    if (!watch && !shorts) return null;
    if (doc.querySelector(".ytp-live-badge:not([disabled])") && media.duration === Infinity) return null;
    return `youtube:${id}`;
  }

  // extension/src/content/media.js
  function mediaIdentity(el, url) {
    if (isYouTube(url)) return youtubeIdentity(el, url);
    const explicit = el.getAttribute("data-continuity-id") || el.getAttribute("data-content-id");
    if (explicit && shortText(explicit, 200) && /^[\w. :/-]+$/.test(explicit)) return `generic:${el.localName}:content:${explicit}`;
    const source = el.currentSrc || el.getAttribute("src") || el.querySelector("source[src]")?.getAttribute("src");
    if (!source) return null;
    let absolute;
    try {
      absolute = new URL(source, url).href;
    } catch {
      return null;
    }
    const p = pageIdentity(absolute);
    return p ? `generic:${el.localName}:src:${p.url}` : null;
  }
  function captureMedia(elements, url) {
    const pairs = [...elements].filter((e) => e.isConnected).map((el) => ({ el, key: mediaIdentity(el, url) }));
    return pairs.filter((p) => p.key && pairs.filter((q) => q.key === p.key).length === 1).slice(0, LIMITS.media).flatMap(({ el, key }) => {
      if (!Number.isFinite(el.currentTime) || el.currentTime < 0 || !Number.isFinite(el.playbackRate)) return [];
      return [{ key, kind: el.localName, time: el.currentTime, duration: Number.isFinite(el.duration) ? el.duration : null, rate: Math.max(0.25, Math.min(4, el.playbackRate)), paused: el.paused, muted: el.muted, volume: el.volume }];
    });
  }
  function seekTarget(record, duration, ranges) {
    if (record.duration === null || !Number.isFinite(duration) || duration <= 0 || !ranges.length) return null;
    const desired = Math.min(Math.max(record.time, 0), Math.max(0, duration - 0.1));
    const choices = [];
    for (let i = 0; i < ranges.length; i++) {
      const start = ranges.start(i), end = Math.min(ranges.end(i), duration);
      if (Number.isFinite(start) && Number.isFinite(end) && end > start) choices.push(Math.max(start, Math.min(desired, Math.max(start, end - 0.05))));
    }
    return choices.sort((a, b) => Math.abs(a - desired) - Math.abs(b - desired))[0] ?? null;
  }
  var MediaRestore = class {
    constructor(records, registry, url) {
      this.items = records.map((record) => ({ record, status: "pending", element: null, applied: false }));
      this.registry = registry;
      this.url = url;
      this.cancelled = false;
    }
    cancel() {
      this.cancelled = true;
      for (const i of this.items) {
        if (i.playPending) {
          i.element?.pause();
          i.status = "cancelled";
        } else if (i.status === "pending") i.status = "cancelled";
      }
    }
    tick() {
      if (this.cancelled) return;
      for (const item of this.items) {
        if (item.status !== "pending") continue;
        const { record } = item;
        if (record.duration === null) {
          item.status = "unsupported";
          continue;
        }
        const candidates = [...this.registry.media].filter((el2) => el2.isConnected && mediaIdentity(el2, this.url) === record.key);
        if (candidates.length !== 1) continue;
        const el = candidates[0];
        if (item.element && item.element !== el) {
          item.status = "partial";
          continue;
        }
        if (el.readyState < 1) continue;
        const desired = seekTarget(record, el.duration, el.seekable);
        if (desired === null) continue;
        if (!item.applied) {
          item.element = el;
          item.applied = true;
          item.target = desired;
          try {
            el.pause();
            el.playbackRate = record.rate;
            el.muted = record.muted;
            el.volume = record.volume;
            if (Math.abs(el.currentTime - desired) > 0.5) el.currentTime = desired;
          } catch {
            item.status = "failed";
            continue;
          }
        }
        if (!el.seeking && Math.abs(el.currentTime - item.target) <= 1.5) {
          item.status = Math.abs(item.target - record.time) > 1.5 ? "partial" : "completed";
          if (!record.paused && !this.cancelled) {
            item.playPending = true;
            try {
              Promise.resolve(el.play()).catch(() => {
                if (!this.cancelled) {
                  el.pause();
                  item.status = "partial";
                }
              }).finally(() => {
                item.playPending = false;
              });
            } catch {
              el.pause();
              item.status = "partial";
              item.playPending = false;
            }
          }
        }
      }
    }
    result(final = false) {
      if (!this.items.length) return { status: "unsupported", reason: "No supported media was saved" };
      if (final) for (const i of this.items) {
        if (i.status === "pending") i.status = i.applied ? "partial" : "unsupported";
        if (i.playPending) i.status = "partial";
      }
      const statuses = this.items.map((i) => i.status);
      return { status: statuses.every((s) => s === "completed") ? "completed" : statuses.every((s) => s === "cancelled") ? "cancelled" : statuses.every((s) => s === "unsupported") ? "unsupported" : statuses.includes("pending") ? "pending" : "partial", restored: statuses.filter((s) => s === "completed").length, attempted: this.items.filter((i) => i.applied).length, total: statuses.length, reason: "Finite media only. Partial includes seeking/playback that did not finish before the deadline; unmatched identity, ads, and live streams are skipped." };
    }
    done() {
      return this.items.every((i) => i.status !== "pending" && !i.playPending);
    }
  };

  // extension/src/content/scroll.js
  function scrollRecord(doc, element = null) {
    const win = doc.defaultView;
    const el = element || doc.scrollingElement;
    if (!el) return null;
    const target = element ? locator(el) : "document";
    if (!target) return null;
    const viewportWidth = element ? el.clientWidth : win.innerWidth, viewportHeight = element ? el.clientHeight : win.innerHeight;
    const x = element ? el.scrollLeft : win.scrollX, y = element ? el.scrollTop : win.scrollY;
    const width = el.scrollWidth, height = el.scrollHeight;
    const rect = element ? el.getBoundingClientRect() : { left: 0, top: 0 };
    let anchor = null;
    const hit = doc.elementFromPoint?.(Math.max(1, rect.left + 20), Math.max(1, rect.top + 60));
    const candidate = hit?.closest("[id],[data-continuity-id]");
    const l = candidate && candidate !== el && (element ? el.contains(candidate) : true) ? locator(candidate) : null;
    if (l) anchor = { locator: l, offset: candidate.getBoundingClientRect().top - rect.top };
    return { target, x: Math.max(0, x), y: Math.max(0, y), width, height, viewportWidth, viewportHeight, progressX: width > viewportWidth ? Math.min(1, Math.max(0, x / (width - viewportWidth))) : 0, progressY: height > viewportHeight ? Math.min(1, Math.max(0, y / (height - viewportHeight))) : 0, anchor };
  }
  function scrollDestination(record, metrics, { anchorY = null, final = false } = {}) {
    if (anchorY !== null) return { x: record.x, y: Math.max(0, anchorY), approximate: false };
    const maxY = Math.max(0, metrics.height - metrics.viewportHeight), maxX = Math.max(0, metrics.width - metrics.viewportWidth);
    const similar = Math.abs(metrics.height - record.height) <= Math.max(40, record.height * 0.08) && Math.abs(metrics.viewportHeight - record.viewportHeight) < 100;
    if (!similar && metrics.height < record.height * 0.9 && !final) return null;
    return { x: similar ? Math.min(record.x, maxX) : record.progressX * maxX, y: similar ? Math.min(record.y, maxY) : record.progressY * maxY, approximate: !similar };
  }
  var ScrollRestore = class {
    constructor(records, doc) {
      this.items = records.map((record) => ({ record, status: "pending" }));
      this.doc = doc;
    }
    cancel() {
      for (const i of this.items) if (i.status === "pending") i.status = "cancelled";
    }
    tick(final = false) {
      for (const item of this.items) {
        if (item.status !== "pending" || item.applied) continue;
        const r = item.record;
        const nested = r.target !== "document";
        const el = nested ? locate(this.doc, r.target) : this.doc.scrollingElement;
        if (!el) continue;
        const current = scrollRecord(this.doc, nested ? el : null);
        if (!current) continue;
        const anchor = r.anchor && locate(this.doc, r.anchor.locator);
        const validAnchor = anchor && (!nested || el.contains(anchor));
        const anchorY = validAnchor ? current.y + anchor.getBoundingClientRect().top - (nested ? el.getBoundingClientRect().top : 0) - r.anchor.offset : null;
        const target = scrollDestination(r, current, { anchorY, final });
        if (!target) continue;
        item.applied = true;
        try {
          if (nested) el.scrollTo({ left: target.x, top: target.y, behavior: "instant" });
          else this.doc.defaultView.scrollTo({ left: target.x, top: target.y, behavior: "instant" });
          const actual = nested ? el.scrollTop : this.doc.defaultView.scrollY;
          item.status = Math.abs(actual - target.y) <= 5 && !target.approximate ? "completed" : "partial";
        } catch {
          item.status = "failed";
        }
      }
    }
    done() {
      return this.items.every((i) => i.status !== "pending");
    }
    result(final = false) {
      if (!this.items.length) return { status: "unsupported", reason: "No reading position saved" };
      if (final) {
        for (const i of this.items) if (i.status === "pending") i.status = "unsupported";
      }
      const states = this.items.map((i) => i.status);
      return { status: states.every((s) => s === "completed") ? "completed" : states.every((s) => s === "cancelled") ? "cancelled" : states.every((s) => s === "unsupported") ? "unsupported" : states.includes("pending") ? "pending" : "partial", restored: states.filter((s) => s === "completed" || s === "partial").length, cancelled: states.filter((s) => s === "cancelled").length, total: states.length, reason: "Anchors first; relative fallback is approximate. Missing infinite-scroll content is not reconstructed." };
    }
  };

  // extension/src/content/forms.js
  function fieldDescriptor(el, policy) {
    if (!policy?.formsAllowed || !el.isConnected || el.disabled || el.readOnly || el.isContentEditable || el.closest('[contenteditable="true"]')) return null;
    const tag = el.localName;
    let kind;
    if (tag === "textarea") kind = "textarea";
    else if (tag === "select") kind = el.multiple ? "select-multiple" : "select-one";
    else if (tag === "input" && ["text", "checkbox", "radio"].includes(el.type)) kind = el.type;
    else return null;
    const form = el.form;
    const formLocator = form ? locator(form) : null;
    if (form && !formLocator) return null;
    if (form?.querySelector('input[type=password],input[autocomplete*="cc-"],input[autocomplete*="one-time-code"]')) return null;
    const context = el.closest("fieldset")?.querySelector("legend")?.textContent || "";
    const meta = [el.name, el.id, el.getAttribute("autocomplete"), el.getAttribute("aria-label"), el.getAttribute("placeholder"), ...[...el.labels || []].map((l) => l.textContent), form?.id, form?.name, form?.getAttribute("autocomplete"), context].filter(Boolean).join(" ").slice(0, 2e3);
    if (sensitiveDescription(meta) || (policy.fieldExclusions || []).some((x) => meta.toLowerCase().includes(x.toLowerCase()))) return null;
    const field = locator(el, form || el.ownerDocument);
    if (!field) return null;
    if ((policy.fieldExclusions || []).includes(field.value)) return null;
    return { field, form: formLocator, kind, meta: meta.slice(0, 600) };
  }
  function formValue(el) {
    if (["checkbox", "radio"].includes(el.type)) return el.checked;
    if (el.multiple) return [...el.selectedOptions].map((o) => o.value);
    return el.value;
  }
  function captureForms(elements, policy) {
    if (!policy.formsAllowed) return [];
    const records = [];
    for (const el of elements) {
      const descriptor = fieldDescriptor(el, policy);
      if (!descriptor) continue;
      const value = formValue(el);
      if (sensitiveValue(value)) continue;
      const record = { ...descriptor, value };
      if (validForm(record)) records.push(record);
      if (records.length === LIMITS.forms) break;
    }
    return records;
  }
  function pristineForRestore(el, edited) {
    if (edited.has(el)) return false;
    if (el.type === "radio" && el.name) {
      const root = el.form || el.ownerDocument;
      if ([...root.querySelectorAll('input[type="radio"]')].some((other) => other.form === el.form && other.name === el.name && (other.checked || other.defaultChecked || edited.has(other)))) return false;
    }
    if (["checkbox", "radio"].includes(el.type)) return !el.checked && !el.defaultChecked;
    if (el.multiple) return el.selectedOptions.length === 0;
    return el.value === "";
  }
  var FormRestore = class {
    constructor(records, doc, policy, edited) {
      this.items = records.map((record) => ({ record, status: "pending" }));
      this.doc = doc;
      this.policy = policy;
      this.edited = edited;
    }
    cancel() {
      for (const i of this.items) if (i.status === "pending") i.status = "cancelled";
    }
    tick() {
      for (const item of this.items) {
        if (item.status !== "pending") continue;
        if (!this.policy.formsAllowed) {
          item.status = "unsupported";
          continue;
        }
        const r = item.record;
        const form = r.form ? locate(this.doc, r.form) : null;
        if (r.form && !form) continue;
        const el = locate(form || this.doc, r.field);
        if (!el) continue;
        if ((el.form || null) !== form || !fieldDescriptor(el, this.policy) || !validForm(r)) {
          item.status = "unsupported";
          continue;
        }
        if (JSON.stringify(formValue(el)) === JSON.stringify(r.value)) {
          item.status = "completed";
          continue;
        }
        if (!pristineForRestore(el, this.edited)) {
          item.status = "cancelled";
          continue;
        }
        try {
          if (r.kind === "checkbox" || r.kind === "radio") el.checked = r.value;
          else if (r.kind === "select-multiple") {
            for (const o of el.options) o.selected = Array.isArray(r.value) && r.value.includes(o.value);
          } else el.value = r.value;
          item.status = JSON.stringify(formValue(el)) === JSON.stringify(r.value) ? "completed" : "partial";
        } catch {
          item.status = "failed";
        }
      }
    }
    done() {
      return this.items.every((i) => i.status !== "pending");
    }
    result(final = false) {
      if (!this.items.length) return { status: "unsupported", reason: "No approved form values saved" };
      if (final) {
        for (const i of this.items) if (i.status === "pending") i.status = "unsupported";
      }
      const states = this.items.map((i) => i.status);
      return { status: states.every((s) => s === "completed") ? "completed" : states.every((s) => s === "cancelled") ? "cancelled" : states.every((s) => s === "unsupported") ? "unsupported" : states.includes("pending") ? "pending" : "partial", restored: states.filter((s) => s === "completed").length, total: states.length, reason: "User edits, populated fields, ambiguous matches, and sensitive fields are preserved" };
    }
  };

  // extension/src/content/main.js
  if (!globalThis.__sessionContinuity) {
    let userActivity = function(component, event) {
      if (!event.isTrusted) return;
      touched[component] = Date.now();
      guarded[component] = false;
      operation?.cancelComponent(component);
      if (url) safeSend({ type: "USER_ACTIVITY", documentToken, url });
    }, restore = function(message) {
      const snapshot = message.snapshot;
      const unsupported = (reason) => ({ status: "unsupported", ...Object.fromEntries(["media", "scroll", "forms"].map((k) => [k, { status: "unsupported", reason }])) });
      if (!policy || !validateSnapshot(snapshot) || snapshot.page.url !== url || pageIdentity(location.href)?.url !== url || message.documentToken !== documentToken || message.generation !== policy.generation || message.epoch !== policy.epoch) return Promise.resolve(unsupported("Document, route, or permission changed"));
      const key = `${snapshot.id}:${snapshot.revision}:${policy.generation}`;
      if (completed.has(key)) return Promise.resolve(copy(completed.get(key)));
      if (operation) return Promise.resolve(unsupported("A restoration is already running"));
      captureSettled.cancel();
      captureMediaSettled.cancel();
      return new Promise((resolve) => {
        const parts = { media: new MediaRestore(snapshot.records.media, registry, url), scroll: new ScrollRestore(snapshot.records.scroll, document), forms: new FormRestore(snapshot.records.forms, document, policy, edited) };
        let frame = null, layoutTimer = null, finished = false, layoutReady = false;
        const local = new AbortController();
        function finish(final = false) {
          if (finished) return;
          if (final) parts.media.tick();
          if (final && layoutReady) parts.scroll.tick(true);
          const results = Object.fromEntries(Object.entries(parts).map(([k, p]) => [k, p.result(true)]));
          const statuses = Object.entries(results).filter(([k]) => snapshot.records[k].length > 0).map(([, r]) => r.status);
          const status = !statuses.length || statuses.every((s) => s === "unsupported") ? "unsupported" : statuses.every((s) => s === "completed") ? "completed" : statuses.every((s) => s === "cancelled") ? "cancelled" : statuses.every((s) => s === "failed") ? "failed" : "partial";
          finished = true;
          clearTimeout(deadline);
          clearTimeout(layoutTimer);
          clearTimeout(frame);
          resize?.disconnect();
          local.abort();
          for (const k of Object.keys(parts)) guarded[k] = snapshot.records[k].length > 0 && results[k].status !== "completed" && touched[k] < (message.operationStartedAt || Date.now());
          const result = { status, ...results };
          completed.set(key, result);
          while (completed.size > 20) completed.delete(completed.keys().next().value);
          operation = null;
          resolve(result);
          setTimeout(() => capture(), 250);
        }
        function tick() {
          frame = null;
          if (finished) return;
          if (pageIdentity(location.href)?.url !== url) {
            op.cancelAll("Route changed");
            return;
          }
          parts.media.tick();
          parts.forms.tick();
          if (layoutReady && document.readyState !== "loading") parts.scroll.tick();
          if (Object.values(parts).every((p) => p.done())) finish();
        }
        function schedule() {
          if (frame === null && !finished) frame = setTimeout(tick, 16);
        }
        function changed() {
          layoutReady = false;
          clearTimeout(layoutTimer);
          layoutTimer = setTimeout(() => {
            layoutReady = true;
            schedule();
          }, 200);
          schedule();
        }
        const op = { id: message.operationId, changed, cancelComponent: (k) => {
          parts[k]?.cancel();
          schedule();
        }, cancelAll: () => {
          for (const p of Object.values(parts)) p.cancel();
          finish();
        } };
        const resize = typeof ResizeObserver !== "undefined" ? new ResizeObserver(changed) : null;
        if (document.documentElement) resize?.observe(document.documentElement);
        document.addEventListener("load", changed, { capture: true, signal: local.signal });
        document.addEventListener("DOMContentLoaded", changed, { signal: local.signal });
        const deadline = setTimeout(() => {
          layoutReady = true;
          finish(true);
        }, 12e3);
        operation = op;
        for (const [k, value] of Object.entries(touched)) if (value >= (message.operationStartedAt || Date.now())) parts[k].cancel();
        changed();
      });
    };
    globalThis.__sessionContinuity = true;
    const documentToken = uuid(), documentStartedAt = performance.timeOrigin;
    let url = pageIdentity(location.href)?.url, policy = null, sequence = 0, connectPromise = null, operation = null, closed = false, connectingAgain = false;
    const edited = /* @__PURE__ */ new WeakSet(), nested = /* @__PURE__ */ new Set(), completed = /* @__PURE__ */ new Map();
    let touched = { scroll: 0, media: 0, forms: 0 };
    let guarded = { media: false, scroll: false, forms: false };
    const activity = new AbortController();
    let lastGesture = 0;
    const safeSend = (m) => browser.runtime.sendMessage(m).catch(() => ({ ok: false, reason: "Extension unavailable" }));
    const registry = new Registry(document, () => {
      operation?.changed();
    });
    const captureSettled = debounceBounded(() => capture(["scroll", "forms"]), 500, 2e3);
    const captureMediaSettled = debounceBounded(() => capture(["media"]), 150, 1e3);
    const connect = async () => {
      if (closed) return;
      if (connectPromise) {
        connectingAgain = true;
        return connectPromise;
      }
      connectPromise = (async () => {
        const next = pageIdentity(location.href)?.url;
        if (!next) {
          policy = null;
          operation?.cancelAll("Page is ineligible");
          return;
        }
        if (url !== next) {
          url = next;
          policy = null;
          sequence = 0;
          nested.clear();
          touched = { scroll: 0, media: 0, forms: 0 };
          guarded = { media: false, scroll: false, forms: false };
          operation?.cancelAll("Route changed");
        }
        const requestUrl = url;
        const response = await safeSend({ type: "HELLO", url, documentToken, documentStartedAt });
        if (requestUrl !== pageIdentity(location.href)?.url) return;
        if (response.ok) {
          policy = response;
          registry.start();
        } else {
          policy = null;
          operation?.cancelAll("Permission or page eligibility changed");
          captureSettled.cancel();
          captureMediaSettled.cancel();
          registry.stop();
        }
      })();
      try {
        await connectPromise;
      } finally {
        connectPromise = null;
        if (connectingAgain) {
          connectingAgain = false;
          queueMicrotask(connect);
        }
      }
    };
    async function capture(parts = ["media", "scroll", "forms"]) {
      if (closed || !url || operation) return { ok: false };
      if (pageIdentity(location.href)?.url !== url) {
        await connect();
        return { ok: false };
      }
      if (!policy) {
        await connect();
        if (!policy) return { ok: false };
      }
      const records = {};
      if (parts.includes("media") && !guarded.media) records.media = captureMedia(registry.media, url);
      if (parts.includes("scroll") && !guarded.scroll) {
        const main = scrollRecord(document);
        records.scroll = main ? [main] : [];
        for (const el of nested) {
          if (!el.isConnected) {
            nested.delete(el);
            continue;
          }
          const r = scrollRecord(document, el);
          if (r) records.scroll.push(r);
          if (records.scroll.length >= LIMITS.scroll) break;
        }
      }
      if (parts.includes("forms") && !guarded.forms) records.forms = captureForms(registry.fields, policy);
      if (!Object.keys(records).length) return { ok: false };
      const response = await safeSend({ type: "CAPTURE", url, documentToken, generation: policy.generation, contextId: policy.contextId, epoch: policy.epoch, sequence: ++sequence, records });
      if (response.reconnect) await connect();
      return response;
    }
    const formEvent = (e) => {
      if (!e.isTrusted) return;
      if (e.inputType || Date.now() - lastGesture < 2e3) {
        edited.add(e.target);
        userActivity("forms", e);
      }
      captureSettled();
    };
    document.addEventListener("input", formEvent, { capture: true, signal: activity.signal });
    document.addEventListener("change", formEvent, { capture: true, signal: activity.signal });
    document.addEventListener("scroll", (e) => {
      const el = e.target;
      if (el?.nodeType === 1 && el !== document.scrollingElement && nested.size < LIMITS.scroll - 1) nested.add(el);
      if (!operation) captureSettled();
    }, { capture: true, passive: true, signal: activity.signal });
    for (const type of ["wheel", "touchstart"]) document.addEventListener(type, (e) => userActivity("scroll", e), { capture: true, passive: true, signal: activity.signal });
    document.addEventListener("pointerdown", (e) => {
      if (e.isTrusted) lastGesture = Date.now();
      userActivity("scroll", e);
      if (registry.media.size && !e.target.matches?.("input,textarea,select")) userActivity("media", e);
    }, { capture: true, passive: true, signal: activity.signal });
    document.addEventListener("keydown", (e) => {
      if (e.isTrusted) lastGesture = Date.now();
      if (["ArrowDown", "ArrowUp", "PageDown", "PageUp", "Home", "End", " "].includes(e.key) && !e.target.matches?.("input,textarea,select")) userActivity("scroll", e);
      if (registry.media.size && !e.target.matches?.("input,textarea,select") && [" ", "k", "K", "m", "M", "j", "J", "l", "L", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)) userActivity("media", e);
    }, { capture: true, signal: activity.signal });
    for (const type of ["loadedmetadata", "durationchange", "canplay", "progress", "seeked", "playing", "pause", "ratechange", "volumechange", "ended", "emptied"]) document.addEventListener(type, (e) => {
      if (!e.target.matches?.("video,audio")) return;
      registry.inspect(e.target);
      operation?.changed();
      if (!operation) captureMediaSettled();
    }, { capture: true, signal: activity.signal });
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden) connect().then(() => capture());
      else capture();
    }, { signal: activity.signal });
    document.addEventListener("DOMContentLoaded", () => {
      registry.add(document.documentElement);
      connect().then(() => capture());
    }, { once: true, signal: activity.signal });
    window.addEventListener("popstate", () => connect(), { signal: activity.signal });
    window.addEventListener("hashchange", () => connect(), { signal: activity.signal });
    window.addEventListener("pagehide", (event) => {
      capture();
      closed = true;
      operation?.cancelAll("Document left");
      registry.stop();
      captureSettled.cancel();
      captureMediaSettled.cancel();
      if (!event.persisted) {
        activity.abort();
        clearInterval(routeTimer);
        clearInterval(mediaTimer);
        browser.runtime.onMessage.removeListener(handleMessage);
      }
    }, { signal: activity.signal });
    window.addEventListener("pageshow", () => {
      closed = false;
      connect().then(() => capture());
    }, { signal: activity.signal });
    const routeTimer = setInterval(() => {
      if (!closed && pageIdentity(location.href)?.url !== url) connect();
    }, 1e3);
    const mediaTimer = setInterval(() => {
      if (!closed && [...registry.media].some((el) => el.isConnected && !el.paused)) capture(["media"]);
    }, 5e3);
    setTimeout(() => {
      if (!closed) connect().then(() => capture());
    }, 2500);
    const handleMessage = (message) => {
      if (message?.type === "RESTORE") return restore(message);
      if (message?.type === "CANCEL_RESTORE") {
        if (!message.operationId || message.operationId === operation?.id) operation?.cancelAll();
        return Promise.resolve({ ok: true });
      }
      if (["CONNECT", "ROUTE_CHANGED", "POLICY_CHANGED"].includes(message?.type)) {
        if (message.type === "POLICY_CHANGED") {
          policy = null;
          operation?.cancelAll();
          captureSettled.cancel();
          captureMediaSettled.cancel();
        }
        queueMicrotask(() => connect());
        return Promise.resolve({ ok: true });
      }
      if (message?.type === "SNAPSHOT") {
        queueMicrotask(() => connect().then(() => capture()));
        return Promise.resolve({ ok: true });
      }
      return void 0;
    };
    browser.runtime.onMessage.addListener(handleMessage);
    connect();
  }
})();
