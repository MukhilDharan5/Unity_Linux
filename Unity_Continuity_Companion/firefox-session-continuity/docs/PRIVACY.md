# Privacy and permissions

All snapshots remain in the Firefox profile's **Browser Local Storage**. There is no telemetry, analytics, cloud backend, remote code, data upload, or cross-device sync. Navigating to a saved page naturally requests that page from its website; the extension does not transmit stored form records or use a separate network service. Local storage is not a promise of encryption or protection from another program with profile access.

## Manifest permissions

| Permission | Why it is requested |
| --- | --- |
| `storage` | Durable local snapshots/settings and volatile session bindings |
| `tabs` | Current page URL, active tab, matching existing tabs, and navigation status |
| `sessions` | Attach generated logical identities to tabs so Firefox session restore can return them |
| `webNavigation` | Document IDs, committed navigation, SPA history and fragment changes; stale-document rejection |
| `cookies` | Firefox requires this to create tabs in their original `cookieStoreId`; the extension never reads or writes cookie contents |
| `contextualIdentities` | Check whether the original container still exists; no silent fallback |
| `scripting` | Inject the local content bundle into already-open eligible tabs after installation/host permission changes |
| `alarms` | Bounded periodic retention maintenance that can wake an event page |
| `nativeMessaging` | Explicitly enabled communication with the installed local Unity Linux host |
| `http://*/*`, `https://*/*` | Continuous automatic capture and restoration on ordinary approved hosts; `activeTab` alone would not permit this |

`incognito: not_allowed` is enforced by Firefox, with additional runtime private-tab checks. There is no `unlimitedStorage`, downloads, webRequest, clipboard, or notification permission. The data-consent manifest declares `none` because processing stays on this computer; there is no remote data collection.

When both the extension and Unity Linux connection switches are enabled, eligible page URLs, media times, reading progress and status summaries are sent to the installed local Unity daemon. Full checkpoints and approved form values remain in Firefox. Unity holds summaries only in memory while connected; disabling either side clears them. [Native companion details](NATIVE-BOUNDARY.md) explain the exact fields, transport, permissions and lifetime. The connection is off by default.

Host access may be revoked through Firefox. The UI shows missing access, and its permission button requests access only during a user gesture. Every incoming capture and restoration rechecks current access. Revocation invalidates queued epochs, cancels restore work, purges affected contexts, and removes their form approval. Restricted Mozilla/enterprise pages may remain inaccessible despite a broad host grant.

## Forms

Approval is exact-origin, including port. It is not a wildcard approval for subdomains or other containers/routes; each snapshot retains its exact container and route. Site exclusion always takes precedence. Field exclusions match field IDs/names and label/context phrases. Revoke approval to stop capture/restoration and erase the origin's saved form values; exclude/delete the site to remove all its saved contexts.

Capture uses input types, autocomplete, field IDs/names, labels, form metadata, fieldset legends, surrounding form password/payment controls, and recognized secret-like value patterns. Passwords, hidden/file fields, authentication/OTP/payment credentials, private keys, and recovery/seed fields identified by these checks are omitted. Contenteditable and unsupported types are omitted. Detection cannot identify every secret, unusual language, obfuscated metadata, or sensitive free text. Do not approve sites where you enter confidential data; use field/site exclusions proactively.

Forms are never submitted. Existing populated values and user edits are preserved. Radio groups with a website/user selection are not overwritten. No generic `input`/`change` compatibility events are dispatched; framework-controlled state can therefore differ from the visible restored DOM. This limitation is deliberate: synthetic events can trigger network actions, submission, or multi-step workflows.

Restored values become visible to the original page's scripts. The site's own behavior remains outside the extension's control. If a privacy change cannot be committed, capture is blocked for the current browser session (including event-page wakes), and the UI asks you to retry. An uncommitted erasure is not claimed to have succeeded; a full browser restart still loads the last durable policy.

## Other privacy boundaries

Known credential-bearing URLs, userinfo credentials, authentication/reset paths, token-like query keys/fragments, and recognizable opaque bearer material are excluded. Ordinary meaningful query/fragment data can still contain private information not recognized by these heuristics. Site exclusions are the reliable control when URLs themselves are sensitive. Temporary/token-bearing media URLs are not used as identities; blob/MSE players need a stable content identifier.

Do not share the Firefox profile or raw repository when it contains real browsing data. Test output in this project uses local synthetic fixtures or a signed-out public YouTube page only. Captured real data is not written to production logs. Administrative success is reported only after storage changes commit; if storage fails, the interface reports failure rather than claiming that erasure succeeded.
