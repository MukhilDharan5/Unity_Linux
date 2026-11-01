# Logical schema v1

A **session** is one resumable page context, not the complete set of browser windows. One session contains multiple typed component records. JSON serialization is authoritative; the extension does not write CSV.

```json
{
  "schemaVersion": 1,
  "id": "generated-context-uuid",
  "logicalTabId": "generated-logical-tab-uuid",
  "container": "firefox-default",
  "page": {
    "url": "https://example.org/article?edition=2#reading",
    "origin": "https://example.org",
    "route": "/article?edition=2#reading"
  },
  "documentToken": "generated-document-uuid",
  "generation": 1,
  "revision": 7,
  "createdAt": 1793068200000,
  "updatedAt": 1793068215000,
  "closedAt": null,
  "records": { "media": [], "scroll": [], "forms": [] }
}
```

The containing repository envelope has `schemaVersion`, monotonic `commit`, normalized `settings`, `activeId`, `selectedAt`, and `contexts` keyed by context UUID. `activeId` is a logical ID or null. `selectedAt` describes selection, not the most recent background write.

| Field family | Meaning and units |
| --- | --- |
| Time stamps | Integer/finite UTC Unix epoch milliseconds; UI formats locally |
| Revision | Positive safe integer, strictly increasing on content changes/redaction/close; older writes rejected |
| Generation | Nonnegative safe integer identifying a document/route binding |
| Runtime sequence | Increasing integer per content binding, carried by messages, not a cross-restart identity |
| Policy epoch | Positive safe integer, advanced on policy/deletion changes to reject stale messages |
| Media time/duration | Seconds; finite duration or explicit null for unknown/live |
| Media rate | Playback multiplier in [0.25, 4] |
| Volume | Number in [0, 1]; paused/muted are booleans |
| Scroll offsets/dimensions | CSS pixels, nonnegative and finite |
| Scroll progress | Number in [0, 1] over the available scrollable range, not document height |
| Anchor offset | Signed CSS-pixel displacement from viewport/container top |
| Form value | String, boolean, or bounded array of select values, determined by control kind |

Media records contain `key`, `kind`, `time`, `duration`, `rate`, `paused`, `muted`, `volume`. Generic keys contain an explicit stable content ID or validated safe source identity. YouTube keys are `youtube:<videoId>` and require a second DOM identity check on both capture and restoration.

Scroll records contain `target` (`document` or a locator), `x`, `y`, `width`, `height`, `viewportWidth`, `viewportHeight`, `progressX`, `progressY`, `anchor` (null or `{locator, offset}`). Locators use an ID, name, or `data-continuity-id` plus tag. Radio-name locators include the option value. Lookup must resolve exactly one element; no positional selector is generated.

Form records contain `form` (null only for unowned controls), `field`, `kind`, bounded eligibility `meta`, and `value`. The enclosing session binds every record to the exact origin, route and container. On restore, the current DOM form and all privacy checks are evaluated again. Missing controls remain pending only until the operation deadline.

No NaN, Infinity, undefined fields, executable expressions, runtime tab/window IDs, or cyclic values belong in stored sessions. Optional absence is explicit null for duration, anchor, closed time, and form ownership. Component arrays may be empty. Messages can contain an incremental subset of the three arrays; omitted arrays retain their previous committed records.

## Bounds

| Resource | Default | Hard limit |
| --- | --- | --- |
| Context count | 40 | 100 |
| Retention age | 30 days | 90 days |
| Snapshot size | — | 65,536 UTF-8 JSON bytes |
| Repository budget | — | 3 MiB serialized envelope, including quarantine |
| Message size | — | 80,000 UTF-8 JSON bytes |
| Media records/context | — | 16 |
| Scroll records/context | — | 16 including document |
| Form records/context | — | 40 |
| Text form value | — | 1,024 UTF-16 code units, plus snapshot byte budget |
| Multiple-select value | — | 20 strings, each at most 200 code units |
| Origin lists | — | 200 entries |
| Field exclusions/origin | — | 100 entries, each at most 200 code units |

Out-of-bound input is rejected or conservatively omitted at capture, not truncated into a potentially misleading restored value. Settings are normalized to documented ranges. Retention is applied on writes and a five-minute maintenance alarm; expired records are also filtered on reads. Oldest contexts are pruned while preserving the selected context when possible. An expired selected context is not exempt from age limits. Quota/budget failure leaves the prior complete commit intact and displays a save failure.

## Validation, corruption, migrations

`src/core/model.js` is the validation authority. It validates finite numeric ranges, identifiers, containers, URLs, component types, values, and JSON byte budgets. Prototype-reserved logical IDs are rejected. An invalid individual context is quarantined; valid unrelated contexts remain accessible. Quarantine is not shown as a resumable context. Known affected-origin records are removed during privacy deletion/revocation even when malformed; clear-all removes the remaining quarantine.

v1 is the first released schema. `migrateSnapshot` explicitly accepts and copies valid v1 records. There is no invented v0 format. Unknown older and future context versions are quarantined without speculative conversion. An unknown/future repository envelope is preserved read-only; explicit clear-all is the recovery escape hatch. A future release must add a tested, deterministic old-version-to-new-version migration that commits the new envelope only after complete validation. It must not silently overwrite future schemas.

## Eventual CSV serialization concerns (not implemented)

A later, separately specified companion would need multiple record types/rows per logical session, stable session/record keys, schema versions, revision ordering, and explicit units/null conventions. Nested locators, arrays, and identities require a documented JSON-in-cell encoding or normalized tables; flattening them casually loses information.

The format must specify UTF-8/Unicode normalization policy, commas, quotes, CR/LF and multiline text, quote doubling, escape rules, import/export round trips, and migration behavior. CSV quoting is not spreadsheet formula protection. Values beginning with `=`, `+`, `-`, `@`, tab, or control characters can become formulas in spreadsheet programs; a future design must separate safe spreadsheet display from lossless canonical data and define protective encoding explicitly. Do not invent such a format from the current stub.
