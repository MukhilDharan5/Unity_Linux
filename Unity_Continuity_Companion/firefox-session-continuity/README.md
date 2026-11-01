# Session Continuity

A Firefox desktop extension that keeps the last active eligible page, finite HTML5 media positions, reading positions, and explicitly approved nonsensitive form values in **Browser Local Storage**.

**Minimum Firefox: 153.0.** Tested versions and exact results are recorded in [verification](docs/VERIFICATION.md). This is a working implementation with bounded website support, not a claim of production readiness or universal restoration.

## Install

- Development: open `about:debugging#/runtime/this-firefox`, select **Load Temporary Add-on**, and choose `extension/manifest.json` or `artifacts/session-continuity-1.1.0-unsigned.xpi`. Allow the HTTP/HTTPS host permissions. Open an ordinary website and use the toolbar popup. Temporary installations are removed on restart; this method cannot validate persistent installation.
- Normal Firefox installation: submit the source/package to Mozilla for signing (listed or unlisted distribution), then install the **signed** XPI using `about:addons` → gear → **Install Add-on From File**. The delivered XPI is unsigned and release Firefox will reject it as a permanent installation. No signing account, credentials, or publication was supplied or used.
- Isolated development restart testing: use the procedure in [INSTALL.md](docs/INSTALL.md). It permits unsigned installation only in a dedicated Developer Edition/Nightly profile. Never change your ordinary profile for these tests.

The stable add-on ID is `session-continuity@local.invalid`. Keep it unchanged across updates to retain the extension's storage namespace.

## Use

Tracking and automatic startup restoration are enabled by default. Form capture is off for every origin until explicitly approved in **Settings & privacy**. The popup shows the current page, successful commit time, media/scroll summaries, form permission, tracking state, and independent restoration outcomes.

Use **Save now** to request a checkpoint. Its timestamp changes only after storage succeeds. **Restore last active** uses the protected startup target if one is pending, otherwise the most recent *active selection*, not the most recently written background-media record. Settings also list individual contexts for manual restoration.

Existing tabs are reused by logical identity and container. Ambiguous same-URL matches are skipped. Startup may select a recovered tab only if the focused window remains on its unchanged start page. Manual restoration keeps tab focus unchanged. User navigation, scroll gestures, editing, or playback controls cancel relevant restoration work.

Site deletion also excludes that origin. Clear-all deletes saved contexts, clears quarantined records, and pauses tracking. Resume tracking explicitly when ready. Revoking form approval erases saved values for that origin.

## Develop and verify

Node.js 20+ and pnpm are development tools only. The installed extension has no runtime dependencies or remote code.

```text
pnpm install --frozen-lockfile --ignore-scripts --node-linker=hoisted
pnpm build
pnpm test
pnpm lint
pnpm test:firefox
pnpm package
```

`FIREFOX_BINARY` may select another Firefox executable for the integration suite. `node tests/firefox/restart.mjs` uses `FIREFOX_TEST_BINARY` or the portable Nightly location described in [testing](docs/TESTING.md). Test scripts create project-local, isolated profiles and only terminate processes they started.

The content source consists of ES modules bundled into one classic content script by esbuild. Background and UI modules load natively. Rebuild after editing content modules; packaging uses the current `extension/` files.

Deliverables:

- `extension/`: loadable extension source and generated content bundle.
- `tests/`: unit tests, browser tests, and deterministic local media/DOM fixtures.
- `artifacts/session-continuity-1.1.0-unsigned.xpi`: unsigned Firefox package.
- `artifacts/session-continuity-1.1.0-source.zip`: source, tests, fixtures, and documentation.
- `artifacts/SHA256SUMS.txt`: package checksums.
- [Architecture and API decisions](docs/ARCHITECTURE.md), [logical schema](docs/SCHEMA.md), [privacy and permissions](docs/PRIVACY.md), [native adapter boundary](docs/NATIVE-BOUNDARY.md), and [verification matrix](docs/VERIFICATION.md).

## Limits that matter

Only accessible top-level HTTP/HTTPS documents are supported. Cross-origin frames, closed shadow roots, browser pages, private browsing, inaccessible/DRM players, arbitrary missing infinite-scroll content, and unknown sensitive fields are not universally recoverable. Known authentication/reset URLs are excluded rather than saved with broken redacted tokens. Secret detection is heuristic and incomplete; do not approve form capture on sensitive sites.

Checkpoints during playback target approximately five seconds; settled scroll/input targets approximately 0.5 seconds with a two-second maximum debounce during normal operation. Background throttling, suspension, unavailable pages, storage failure, or sudden termination can extend these intervals. Crash recovery uses the latest **successfully committed** snapshot; it does not promise zero loss or physical disk durability.

The [Unity Linux companion](docs/NATIVE-BOUNDARY.md) provides a local receiving server. Install/update Unity Linux, enable **Settings → Enable Firefox extension**, then turn on **Connect to Unity Linux** in the extension's settings. Unity's **Firefox** page shows saved-page summaries and can ask Firefox to restore a page. Full checkpoints and approved form values stay in Firefox; Unity holds summaries only while connected. No phone pairing is needed.
