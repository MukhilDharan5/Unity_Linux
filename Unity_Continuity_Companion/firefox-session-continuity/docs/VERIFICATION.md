# Verification and feature matrix

## 1.1.0 Unity Linux update — 28 October 2026

Firefox 158.0.1 on Linux was tested with the actual extension, installed Python native host and Rust daemon in an isolated profile/D-Bus session. The end-to-end test passed native opt-in, page/media/reading summary capture, native-requested restoration to the saved video time and scroll position, disabled-state erasure, daemon restart/reconnect, site deletion and extension disconnect. The GTK wide/narrow/settings layouts and actual restore/toggle controls passed on an isolated Broadway display. No normal browser profile or host registration was changed by tests.

The extension unit suites and web-ext lint passed with zero extension findings. Linux debug/release builds, workspace Clippy/unit tests, native bridge integration, existing daemon integration, architecture/installer and installed-launcher tests passed. Full form values remain in Firefox; the Linux receiver holds page summaries in memory. Standard Linux Firefox was exercised; Flatpak/Snap host access remains unverified. Signed distribution still requires Mozilla signing.

The remaining matrix below records the original 1.0.0 validation and its website/restoration limits; the native-companion row has been updated for 1.1.0.

Test date: **2026-10-27** (user timezone: Asia/Calcutta). Source/package version: **1.0.0**. Minimum declared Firefox version: **153.0**, selected from Mozilla's document-ID compatibility data. Firefox 153 itself was **not** installed/tested.

Actual browser versions:

| Browser | Build ID | Installation used |
| --- | --- | --- |
| Firefox desktop 158.0.1, Windows, headless | 20261019185856 | Actual extension, temporary install, isolated profiles |
| Firefox Nightly 160.0a1, Windows, headless | 20261022084508 | Actual unsigned XPI, persistent install, isolated profiles with explicitly scoped development preference |

Development tools: Node.js 24.22.1, pnpm 11.25.0, esbuild 0.25.12, jsdom 26.1.0, web-ext 10.8.0. Exact commands, setup failures/repairs, and reproducible procedures are in [TESTING.md](TESTING.md).

## Results

- **55 deterministic tests passed** using `node --test tests/*.test.mjs`.
- **12 real Firefox integration checks passed**, including trusted user-wheel cancellation and real containers.
- Persistent restart tests passed with native Firefox session restore **on and off**; a forced-termination recovery test also passed. Each recovered exactly one matching target, with media at **755 seconds** and document progress **0.75**. The last active of two different fixture routes was the recovery target.
- Extension lint: **0 errors, 0 warnings, 0 notices**. The tool's update-notifier cache warning is unrelated to extension validation.
- Popup/options rendered in Firefox: labeled controls and no horizontal overflow in the tested layout; screenshots visually inspected. [Options screenshot](screenshots/options.png), [popup document screenshot](screenshots/popup.png). The latter renders the popup document in a browser tab, not the native toolbar panel.
- Live YouTube was **partial**: the correct video ID and a committed 755-second record were observed, and `currentTime` returned to 755. However, `seeking` remained true, `readyState` was 1, and `networkState` was 2 after the bounded window. A fully completed seek/playback was not verified. The code reports an attempted but unfinished seek as partial, never completed. The exact cause of YouTube's media loading stall was not established.

## Feature/test matrix

“Verified” below always names the scope. It does not imply universal website support.

| Feature | Classification | Evidence / limit |
| --- | --- | --- |
| MV3 event page, native background/UI modules, bundled content script | Implemented and verified | Real Firefox installation/capture; lint; Firefox Xray compatibility repair |
| Durable local storage, serialized/coalesced writes, revision ordering | Implemented and verified | Deterministic storage tests; persistent real-browser restart |
| Active selection distinct from background save recency | Implemented and verified | Real duplicate/background fixtures; controller tests |
| Same-URL tab separation | Implemented and verified | Two real tabs get distinct context/logical IDs |
| Container separation | Implemented and verified | Real Firefox container fixture and deterministic matching tests |
| Missing container refusal | Implemented and verified in deterministic tests | Real unavailable-container recovery not exercised |
| Startup restore with native session restore enabled/disabled | Implemented and verified | Persistent Nightly profiles; correct active route; exactly one target |
| Unexpected termination recovery | Implemented and verified | Terminated only owned isolated Firefox PID after a committed checkpoint; recovered 755 s / 75% |
| Startup initial-capture protection | Implemented and verified | Race tests and actual restart; blank initial tab and native form events handled |
| Background context reactivation / interrupted restore | Implemented and verified in deterministic tests | Session bindings/order retained; no automatic repeat; natural idle eviction not tested end to end |
| Manual/disabled startup modes | Implemented and verified in deterministic tests | UI selects modes; no automatic restoration in controller tests |
| Generic audio/video, multiple players | Implemented and verified | Real local audio A=755 s, audio B=123 s, video=42 s; tolerance ±1.5 s |
| Dynamic discovery and recreated player handling | Implemented; partly verified | Registry/removal/readiness unit fixtures; browser fixture offers replacement control; real replacement restoration not asserted |
| YouTube watch page identity/capture | Implemented and verified on one public video | `youtube:XEzRZ35urlk`, duration 6763.141 s, checkpoint 755 s; no ad state observed |
| Completed live YouTube seek/playback | Partial | Correct requested position; player remained seeking/loading within the deadline |
| YouTube playlists, SPA video identity, advertisement guard | Implemented and verified in deterministic DOM tests | Live playlist transitions/ads not exercised |
| YouTube Shorts | Partial | Only active renderer with matching exposed video ID; deterministic test passes; live Shorts unverified |
| Playback resumption/autoplay refusal | Implemented and verified in deterministic tests | Rejected play leaves paused; successful real autoplay across restart not tested |
| Document/nested scroll | Implemented and verified | 75% document; nested y=1200 and horizontal offset=170 on real fixture |
| User scroll cancellation | Implemented and verified | Trusted WebDriver wheel gesture cancels pending document restoration; user remains at 250 px |
| Progressive/delayed layout | Partial | Bounded event-driven logic and deterministic tests; cannot prevent arbitrary layout shifts after success |
| Infinite scroll beyond available content | Unsupported | Missing content is not fetched/reconstructed |
| Approved ordinary forms / sensitive exclusion | Implemented and verified | Real text/checkbox/radio/select fixture; unapproved records empty; password/OTP/payment/recovery omitted |
| Existing website values / edited fields | Implemented and verified | Real populated field retained; zero submissions; edited-field/radio-group deterministic tests |
| Form revocation and deletion barriers | Implemented and verified | Real purge and stop; queued epoch/deletion tests; failed privacy write blocks capture in the current browser session |
| Host permission revocation | Implemented; partly verified | Deterministic current-permission rejection; real Firefox permission-removal UI not exercised |
| Private browsing exclusion | Implemented; partly verified | Manifest not_allowed and deterministic sender rejection; private-window UI not exercised |
| Corruption, future schemas, quota failure | Implemented and verified in deterministic tests | Unrelated valid data retained, future envelope read-only, explicit clear recovery, committed target unchanged on failed write |
| Repeated restoration | Implemented and verified | Real second restore generated zero additional seeks and no new tabs |
| Redirect cancellation | Implemented and verified in deterministic tests | No navigation back/loop; public redirect end-to-end behavior not separately exercised |
| Popup/options accessibility | Implemented; partly verified | Native controls, labels, focus outlines, status/error semantics, visual inspection; no screen-reader audit |
| Unity Linux native companion | Implemented; see [native validation](NATIVE-BOUNDARY.md#validation) | Actual daemon/native-host tests and extension tests cover opt-in, bidirectional controls, reconnect and erasure; CSV export remains unsupported |
| Signed normal-release installation | Unverified / unavailable artifact | Delivered package unsigned; no AMO account/signing/publication performed |
| Cross-origin frames, closed shadow DOM, DRM/inaccessible players, arbitrary secrets | Unsupported or inherently limited | No claim of universal access or secret detection |

## Evidence and remaining procedures

Machine-readable browser reports are retained under `docs/evidence/` and generated under `test-results/` by the runners. Their content is synthetic fixture data or the public YouTube test. `artifacts/SHA256SUMS.txt` identifies the current deliverable packages.

For remaining end-to-end checks, follow the corresponding steps in [TESTING.md](TESTING.md). No unavailable validation is silently treated as a pass. In particular, obtaining a stable decoded YouTube frame after seeking, live advertisements/Shorts, idle event-page eviction, actual permission-removal/private-window UI, a screen-reader audit, and AMO signing require further validation.

Crash checkpoints are normal-operation targets, not a promise of zero data loss, physical fsync durability, or recovery after filesystem corruption. Form and URL heuristics cannot recognize every secret. Unloaded infinite content, late native tab restoration, changing website player markup, and framework-controlled forms remain genuine constraints.
