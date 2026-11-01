# Architecture and Firefox API decisions

## Runtime

Manifest V3 uses Firefox's nonpersistent background **event page** through `background.scripts` and `type: module`. There is no service worker. The manifest omits `persistent`: MV3 already defaults to nonpersistent, and actual Firefox 158 warned about an explicit `persistent: false`. All browser event listeners are registered synchronously in `src/background/main.js`, before asynchronous initialization.

`Controller` accepts a logical `StorageAdapter`; `LocalRepository` owns full-checkpoint persistence in `browser.storage.local`. The optional `UnityCompanion` stores only its connection preference/profile ID in a separate local key and mirrors committed summaries over native messaging. Runtime bindings and the protected startup copy use `storage.session`, which survives background suspension but not a full browser restart. Tests replace the repository with an independent in-memory logical adapter. See [the native boundary](NATIVE-BOUNDARY.md).

Background/UI files are native ES modules. Content modules are bundled with esbuild into a classic IIFE because the manifest content-script list does not declare module scripts. No remote module imports or web-accessible resources are needed. A global installation guard avoids duplicate listeners when static injection and permission-recovery injection overlap. Firefox's Xray wrappers require `URLSearchParams.forEach`; spreading its iterator failed in the real browser and is not used.

## Identity and activity

A logical tab UUID is attached through `sessions.setTabValue`. Each distinct route/navigation context has its own generated context UUID. The runtime tab/window numbers are only transient routing references. They do not occur in persisted logical snapshots. Firefox's own session restore can return the tab-attached logical IDs. A duplicated tab inheriting an ID is forked as soon as another live tab owns that identity.

The page identity is origin plus path, meaningful query, and fragment. Known tracking query parameters are removed. Other query/fragment differences remain significant, including YouTube `v` and playlist identity. Unsupported or token-bearing authentication URLs are excluded entirely. Container identity is part of every saved context and matching decision.

`activeId` and `selectedAt` are stored separately from snapshot update times. A save sets the selection only when its sender tab is active in a focused normal window. Background playback updates never select their record. Active-tab/focused-window changes request snapshots and update selection independently.

Policies:

| Event | Selection behavior |
| --- | --- |
| Normal focused eligible tab | Select its logical context; capture meaningful changes |
| Focus leaves Firefox | Retain the last selection; background saves cannot replace it |
| Active page is excluded/restricted/private | Clear automatic selection; never fall back to the newest saved tab |
| Extension's own popup/options | Do not turn extension controls into a page context |
| Explicit tab removal | Mark the bound context closed; clear its selection; never automatically resurrect it |
| Window closing/removal events | Apply the same tab-removal policy when Firefox delivers those events; no shutdown event is assumed |
| Several identical URLs | Prefer exactly one matching saved logical identity; otherwise skip ambiguity |
| Original container unavailable | Fail visibly; never substitute the default container |

There is a bounded native-session-restore race: Firefox may restore additional tabs even after reconciliation finishes. The final fresh query prevents ordinary duplicates, but there is no API to atomically lock Firefox's session-restoration process. A very late restored tab can still produce a duplicate. The extension does not close user tabs to conceal that case.

## Message ordering

Every content instance has a random document token and document start timestamp. The background compares the browser-provided sender `documentId` with `webNavigation.getFrame().documentId`, requires frame 0, the extension ID, a normal nonprivate tab, correct current origin/route/container, and host permission. These authoritative document IDs arrived in Firefox **153**, which determines the minimum supported version.

Bindings include context ID, navigation generation, last accepted sequence number, and policy epoch. A capture must match all four as well as its document token and current URL. Duplicate, older, mismatched, oversized, or invalid messages are rejected. SPA history/fragment events invalidate route bindings, and a lightweight one-second URL comparison covers missed notifications without rescanning the document. A transition A → B → A gets a new context ID, preventing delayed A messages from merging into the later A visit.

Only the extension's exact popup/options URLs can invoke administrative actions. Stored data and all received payloads pass validation. UI output uses `textContent`, never stored HTML. Production code does not log captured values or URLs.

## Persistence

The repository serializes writes, coalesces adjacent same-context saves, and rejects older revisions and outdated policy epochs. Every commit writes a single complete envelope to one storage key, containing valid contexts, settings, and the active pointer. The in-memory committed view changes only after `storage.local.set` resolves. A failed or interrupted application-level write cannot publish an active pointer that refers to half a snapshot. This uses Firefox's storage implementation; it is not a filesystem transaction/fsync guarantee.

Deletion, clear-all, closing, active selection, and policy changes are FIFO barriers. Revocation increments the policy epoch and removes the affected form records in the same repository commit. Already queued old-epoch messages cannot put values back. Site deletion additionally excludes the origin; clear-all disables tracking. Records with corruption are quarantined, while unrelated valid contexts remain usable. A quarantined record attributable to a deleted/revoked origin is removed as a whole. An unsupported envelope is read-only except for explicit clear-all.

## Startup state machine

`initialize → protected target → reconcile → wait for intended document → restore components → completed / partial / cancelled / unsupported / failed`

The first runtime initialization loads and pins the prior committed active snapshot **before** allowing new capture. A two-second first-activation grace protects the initial event ordering. Only `runtime.onStartup` starts automatic restoration. Installation, updates/reload handling, ordinary messages, alarms, and background reactivation do not trigger automatic restoration. A handled marker is persisted in session storage before tab creation, so an event-page wake cannot repeat navigation.

Startup modes are automatic (default), manual, and disabled. Manual mode retains the startup target until manual restoration or genuine user takeover. Captures for that target are held so empty initial state cannot replace it. Failed/cancelled startup targets likewise remain available for a manual retry. Other eligible contexts can continue to save when reconciliation has ended.

Reconciliation queries normal nonprivate tabs and logical IDs for up to five seconds and repeats the query immediately before creating anything. It never navigates an existing tab to a different route. A newly created tab's initial loading `about:blank` is tolerated; a redirect or other navigation cancels restoration. The document connection gets up to 18 seconds, followed by at most 12 seconds of component readiness. Metadata/DOM/layout events drive attempts. Timeouts bound work; they are not guesses that a website will be ready at a fixed delay.

Startup focus is guarded by the initial active blank/new-tab page and a focus/activation counter. If the user or Firefox changes that selection, no tab is activated by the extension. Manual restoration does not change focus.

Media, scroll, and forms report independently. Unsupported empty components do not make an otherwise successful operation fail. A mixed result is partial. Incomplete components remain protected from empty replacement captures until relevant user input. Repeating an identical snapshot/document operation returns its cached result; a seek is applied only once per element attempt and only when needed.

## Page components

- **Media:** at most 16 unique accessible audio/video elements. Stable content attributes or safe source identity; never DOM index. Ambiguous duplicate sources are skipped. Restore waits for metadata and seekable ranges, clamps finite positions to valid intervals, applies rate/mute/volume, pauses before seeking, and attempts `play()` only for a saved playing state. Playback rejection leaves the media paused and produces a partial result. Live/unknown-duration media are not seek-restored.
- **YouTube:** isolated adapter for watch/playlist routes and conservatively identifiable Shorts. It requires both the route video ID and the containing watch/active-Shorts element's `video-id`, and refuses known advertisement states. Replaced players are discovered through the bounded DOM registry. Player/site changes can make this unsupported; no position is transferred on a route-ID mismatch. Actual availability and tests are in the verification matrix.
- **Scroll:** document and at most 15 identifiable nested containers. Save pixels, dimensions, relative progress, and an optional unique anchor/offset. Restoration waits for a short layout quiet interval, prefers an available anchor, uses absolute positions for similar geometry, and falls back to relative progress at the deadline if necessary. Each target is applied once, then verified; later layout shifts are a documented limitation. Wheel/touch/pointer/scroll-key gestures cancel pending scroll work immediately.
- **Forms:** origin approval plus current eligibility and exact document route/container. At most 40 values from up to 300 discovered candidate fields. Only text inputs, textareas, checkboxes, uniquely identifiable radio members/groups, and selects. Password/hidden/file and other input types, contenteditable, authentication/payment contexts, suspect metadata, and recognized secret-like values are excluded. Existing website values, populated radio groups, and edited fields are preserved. There are no synthetic compatibility events, submits, button clicks, or workflow advancement.

DOM discovery processes at most 200 nodes per scheduled task, caps pending roots, and retains bounded element sets. Mutation callbacks process added subtrees, not repeated full-document queries. Scroll/media/form events are delegated. The playback clock collects only known playing media roughly every five seconds; it does not collect every component in every tab. Observers and per-restoration listeners are disconnected on completion/page departure.

## Primary documentation checked on 2026-10-27

- [Mozilla background scripts](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Background_scripts) and [background manifest](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/background): Firefox MV3 event pages and modules.
- [Content scripts](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Content_scripts): isolated content-script environment, module import limitations, restricted domains.
- [runtime.onStartup](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/runtime/onStartup) and [storage.session](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/storage/session): startup lifecycle and volatile runtime storage.
- [sessions.setTabValue](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/sessions/setTabValue), [tabs.create](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/tabs/create): tab metadata, container creation, cookies permission.
- [Host permissions](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/host_permissions): continuous host access and revocation.
- [MDN runtime compatibility data](https://github.com/mdn/browser-compat-data/blob/main/webextensions/api/runtime.json) and [webNavigation compatibility data](https://github.com/mdn/browser-compat-data/blob/main/webextensions/api/webNavigation.json): `documentId` supported from Firefox 153.
- [Firefox data consent](https://extensionworkshop.com/documentation/develop/firefox-builtin-data-consent/): `none` declaration for no data transmission; local processing/storage only.
- [Signing and distribution](https://extensionworkshop.com/documentation/publish/signing-and-distribution-overview/): release signing requirement and isolated development alternatives.
