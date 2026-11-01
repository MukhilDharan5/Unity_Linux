# Test procedures and commands

Run all commands from `firefox-session-continuity/`. Automated browser tests create disposable project-local profiles. Never replace their profile paths with a user's ordinary profile, and never kill Firefox by process name.

## Deterministic tests

```powershell
node scripts/build.mjs
node --test tests/*.test.mjs
node node_modules/web-ext/bin/web-ext.js lint --source-dir extension --warnings-as-errors --no-config-discovery
node tests/firefox/run.mjs
node tests/firefox/ui.mjs
node scripts/package.mjs
```

The Node suite uses `node:test` and jsdom for isolated DOM logic, plus controlled storage/browser fakes and an independent in-memory adapter. It covers stale sequencing, policy epochs, quota failure, corruption, interrupted writes, deletion barriers, active selection, duplicate URLs, container matching, redirects, cancellation, private-tab rejection, reinitialization, future schemas, and conservative media/form behavior. These are deterministic tests, not evidence of every Firefox/website behavior.

The real Firefox integration suite uses Marionette, Mozilla's browser automation protocol. It installs the actual extension temporarily, serves deterministic HTTP fixtures bound to `127.0.0.1`, and calls the extension's real UI/background/content APIs. It tests multiple HTML5 players, 75% document and nested scrolling, approved/sensitive/populated forms, duplicate URLs, background activity, repeated restoration, trusted WebDriver wheel cancellation, SPA separation, actual containers, revocation, deletion, and clear-all. The wheel test leaves the document at the user's 250 px position; an already-restored nested container produces an honest partial aggregate result.

`tests/fixtures/server.mjs` generates a 900-second, 8-bit mono PCM WAV in memory and supports HTTP range requests. `sample.webm` is a local, silent, solid-color 900-second VP8 test video. No remote media is required by the integration suite. To regenerate that fixture:

```powershell
ffmpeg -hide_banner -loglevel error -f lavfi -i 'color=c=0x185a4d:s=160x90:r=1:d=900' -c:v libvpx -b:v 12k -an -y tests/fixtures/sample.webm
```

The fixture includes two audio players, one video, nested horizontal/vertical scrolling, route buttons, a player replacement button, dynamic fields, sensitive controls, a populated website draft, a submission counter, and delayed chapter insertion via `?delay=1`. To explore manually:

```powershell
node tests/fixtures/server.mjs
```

Open `http://127.0.0.1:8765/fixture?route=a#reading`. The alternate route and a delayed layout are available through `?route=b#reading` and `?route=a&delay=1#reading`.

## Persistent restart and unexpected termination

Build/package first. Set `FIREFOX_TEST_BINARY` to a portable Nightly/Developer Edition executable, or place Mozilla Nightly under `.test-runtime/nightly/firefox/firefox.exe` as in the recorded run.

```powershell
$env:FIREFOX_TEST_BINARY = 'C:\path\to\isolated\nightly\firefox.exe'
node tests/firefox/restart.mjs
```

This harness changes unsigned-install and session-restore preferences only inside its fresh `.test-profiles/restart-*` profiles, installs with `temporary:false`, commits fixture state at 755 seconds/75%, and stops only the browser child it started. It restarts the same profile without reinstalling the add-on and waits for genuine `runtime.onStartup`, not a simulated event. It checks a changed process ID, the correct active URL, exactly one matching target tab, media tolerance ±1.5 seconds, and reading progress tolerance ±3 percentage points.

Scenarios: native session restore on, native session restore off, and forced termination after a confirmed commit. Forced termination uses only the harness-owned PID; no ordinary browser is terminated. `resume_from_crash=false` is a test-profile setting to avoid a native crash recovery dialog masking extension recovery. This does not simulate power failure or physical disk corruption.

## Public YouTube check

```powershell
node tests/firefox/youtube.mjs
```

This optional network test uses a fresh signed-out Firefox profile and Google's public `XEzRZ35urlk` keynote. It checks the route/video ID, ad state, duration and seekable range before checkpointing at 755 seconds. Both final position and the media component's completion must agree for a pass. A `currentTime` of 755 while `seeking` remains true is **partial**, not proof of a completed seek. No login, consent bypass, ad skipping, downloads, or website submission is performed.

For manual watch/playlist/Shorts testing, use a video you can access normally. Pause at 12:35, wait for a successful save, restart using a persistent installation, and inspect both the video ID and position. Test playlist next/previous changes and player replacement. Expected tolerance for a finite seek is ±1.5 seconds (±2 seconds in the network diagnostic); unknown identity/ad states must be skipped. Shorts require an active renderer exposing the expected `video-id`; otherwise unsupported is correct.

## Further manual checks and limits

1. Use a normal browsing session with several windows. Activate a page in a focused window, play media in another tab, then leave Firefox focus. Verify that the background save time changes without changing the last-active context.
2. Close the last selected tab intentionally. Verify that it is marked closed and is not reopened automatically. Browser shutdown must not depend on a final snapshot.
3. Revoke host permission in Firefox's extension UI. Verify the missing-permission status, capture refusal, and removal of affected saved data/form approval. Regrant through the explicit UI control.
4. Open a private window. The extension must not run there and no private context should appear in storage. Automated tests validate the manifest and rejection paths; end-to-end private-window interaction was not part of the real-browser suite.
5. Restore delayed content and scroll while restoration is pending. Verify cancellation. Continue using an infinite-scroll page after the bounded deadline; the extension must not force later scrolling or invent unloaded content.
6. Try a framework-controlled form. Visible DOM values can restore without updating its application model because synthetic compatibility events are intentionally not sent. Do not submit any real workflow as a test.
7. Let the event page unload naturally with no open extension views. Wake it by activating a normal page. No new tab/navigation should occur. Adapter/controller reactivation is deterministic-test covered; natural idle eviction is not claimed as real-browser verified.

Visual checks use `tests/firefox/ui.mjs`. Both UI pages were rendered in real Firefox, screenshots inspected, controls checked for labels, and horizontal overflow checked. This is not a formal screen-reader audit or evidence of every native toolbar popup size.

## Recorded tooling setup

The environment supplied Node.js `v24.22.1` and pnpm `11.25.0`, with no npm command on PATH. These were the dependency commands used (network access required an approved sandbox escalation):

```powershell
& 'C:\Users\mukhi\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\fallback\pnpm.cmd' install --ignore-scripts
& 'C:\Users\mukhi\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\fallback\pnpm.cmd' install --ignore-scripts --node-linker=hoisted --store-dir .pnpm-store
& 'C:\Users\mukhi\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\fallback\pnpm.cmd' add -D web-ext@latest --ignore-scripts --node-linker=hoisted --store-dir .pnpm-store
```

The first install failed on a sandbox symlink; the workspace-local store avoided it. A sandbox-only network attempt failed DNS; the approved retry succeeded. The initially pinned web-ext 8.9.0 incorrectly rejected the current data-consent key; it was replaced with **10.8.0**, pinned in the lockfile. The final linter has zero errors, warnings, and notices. Its separate update-notifier cache warning is not an extension validation finding.

The following browser probe was also run during setup:

```powershell
node tests/firefox/probe.mjs
```

A sandbox-only launch could not connect to Marionette. Approved isolated launches succeeded. Firefox 158 uses `-remote-allow-system-access`; Nightly 160 additionally requires `MOZ_REMOTE_ALLOW_SYSTEM_ACCESS=1`, now scoped to the child process by the harness. One approval-review attempt was interrupted by account usage limits and did not execute; work resumed after the user's continue instruction.

Portable Nightly retrieval used these commands, with the returned SHA-512 checked against Mozilla's checksum entry before use:

```powershell
New-Item -ItemType Directory -Path '.test-runtime' -Force | Out-Null
$ProgressPreference='SilentlyContinue'
Invoke-WebRequest -Uri 'https://archive.mozilla.org/pub/firefox/nightly/latest-mozilla-central/firefox-160.0a1.en-US.win64.zip' -OutFile '.test-runtime\firefox-nightly.zip'
Invoke-WebRequest -Uri 'https://archive.mozilla.org/pub/firefox/nightly/latest-mozilla-central/firefox-160.0a1.en-US.win64.checksums' -OutFile '.test-runtime\firefox-nightly.checksums'
Get-FileHash -LiteralPath '.test-runtime\firefox-nightly.zip' -Algorithm SHA512
Select-String -LiteralPath '.test-runtime\firefox-nightly.checksums' -Pattern 'sha512.*firefox-160.0a1.en-US.win64.zip'
Expand-Archive -LiteralPath '.test-runtime\firefox-nightly.zip' -DestinationPath '.test-runtime\nightly' -Force
```

Observed SHA-512: `5feb89ba5bd6a900de7c798da0a2b1c5eebdd78e471a6c2cb12fc9f37884c9637464f640ae1e7b63783f9e3d8e477dcf9c67e16b5b5c332f3daaaa5e6b4f67f3`. This mutable “latest” URL may disappear; use Mozilla's dated archive or another explicitly selected compatible development build for future tests.

Validation commands above were rerun after relevant repairs. No successful browser test is inferred from a mock, temporary-install restart, or a position value alone. See [VERIFICATION.md](VERIFICATION.md) for results and outstanding limits.

## Unity Linux native connection (1.1.0)

After building the Linux debug binaries, run from the extension directory:

```sh
node --test tests/*.test.mjs
node node_modules/web-ext/bin/web-ext.js lint --source-dir extension --warnings-as-errors
dbus-run-session -- node tests/firefox/unity.mjs
```

The native browser test defaults to `/usr/bin/firefox` (`FIREFOX_BINARY` overrides it). It installs the real host into a temporary prefix, redirects native-manifest lookup inside its isolated browser process, uses a project-local test profile, and starts its own daemon/D-Bus session. It checks capture, native restore of media/scroll, both opt-ins, deletion, disconnect clearing and daemon restart. It never registers a host in your ordinary Firefox profile or home directory.

From `Unity_Linux`, run `dbus-run-session -- python3 tests/firefox_integration.py` and `dbus-run-session -- python3 tests/firefox_ui.py --broadway`. The GTK test starts a temporary Broadway display and headless browser; screenshots are `/tmp/unity-firefox-{wide,narrow,settings}.png`.
