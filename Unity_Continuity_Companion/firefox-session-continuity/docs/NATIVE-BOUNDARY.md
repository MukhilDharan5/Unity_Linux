# Unity Linux native companion

The extension communicates with the `unityd` receiver in [Unity Linux](../../../Unity_Linux/README.md). Both ends are opt-in: enable **Settings → Enable Firefox extension** in Unity, then **Settings & privacy → Connect to Unity Linux** in the extension. The Linux **Firefox** page shows connected profiles and saved pages, with **Restore** and **Restore last active** buttons. A paired phone is not required.

## Install

Build and install Unity Linux normally. Its daemon installer installs `firefox-host.py` beside `unityd` and registers `org.unityconnect.firefox.json` in `~/.mozilla/native-messaging-hosts/`. System installations using `/usr` or `/usr/local` register in `/usr/lib/mozilla/native-messaging-hosts/`. `--destdir` stages registration as well; `--firefox-manifest-dir` overrides the directory for packaging. The GTK-only installer leaves the daemon and native host alone.

Load the updated extension after installing the host. The `nativeMessaging` permission is required; Firefox may ask for permission when updating an existing installation. Signed distribution still requires Mozilla signing. The manifest permits only `session-continuity@local.invalid`. Standard, non-sandboxed Firefox on Linux is supported. Flatpak/Snap Firefox requires separate host/portal access and has not been validated; moving the manifest alone does not grant access outside its sandbox.

The receiver uses `$XDG_STATE_HOME/unity/firefox.sock`, falling back to `~/.local/state/unity/firefox.sock`. It checks peer UID and uses private directory/socket permissions. The host uses the same paths; a custom daemon `--state-dir` requires `UNITY_FIREFOX_STATE_DIR` in Firefox's launch environment. There is no TCP/HTTP endpoint or open network port for the browser integration.

## Data and lifetime

`LocalRepository` remains the authoritative full-checkpoint store. `StorageAdapter` defines its asynchronous persistence contract; Unity is a live summary mirror, not a replacement persistence adapter. The old unavailable adapter stub has been removed.

Only context IDs, eligible page URLs, save timestamps, closed/active status, media kind/time, document reading progress, a form-record count, tracking status and restore status cross the bridge. Full form values, field locators, media identifiers, browser cookies and document tokens remain in Firefox. URLs can themselves contain private information; site exclusions apply before summaries are sent. Unknown summary fields are rejected by the receiver.

Unity holds summaries only in memory and removes them on native-host disconnect, extension disable, or daemon restart. Disabling the Unity switch immediately clears all summaries and refuses subsequent syncs. The only persisted receiver state is its enable flag (`firefox.json`, mode 0600). Repository commits trigger full summary replacement, so deletions and retention remove entries from Unity too. Missing Unity or a native communication failure does not fail browser saves or change their acknowledgement semantics.

## Protocol v1

Firefox's [`runtime.connectNative`](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/runtime/connectNative) launches the Python host. Its stdio frames follow [Mozilla native messaging](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Native_messaging): native-endian unsigned 32-bit byte length, then UTF-8 JSON. The host forwards those frames over the Unix socket. Frames in both directions are capped at 512 KiB; the receiver allows four simultaneous connections, 100 contexts per profile, and bounded media/URL fields.

1. Extension → daemon: `{type:"hello", version:1, profileId}`. Profile IDs persist in a separate browser-local setting. Duplicate live profile IDs are refused.
2. Daemon → extension: `{type:"hello", version:1, enabled}`. A changed Linux switch sends another hello. No summary is sent before an enabled acknowledgement.
3. Extension → daemon: `{type:"sync", sequence, summary}`. Daemon replies `{type:"synced", sequence}` after validating/replacing its live view. The extension shows a confirmed sync only for its current sequence.
4. Daemon → extension: `{type:"restore", commandId, contextId}`. An empty context ID selects the last active checkpoint. The extension routes this through the existing controller's policy, container, permission, tab-matching and user-input cancellation checks, then returns `{type:"result", commandId, ok, note}`. Acceptance means restoration started; the separate restore status reports its eventual outcome.
5. A Firefox alarm retries connections and sends a ping/current summary every 30 seconds. Native errors/disconnects are displayed in the extension. Reconnection replaces the entire view, and commands are never replayed from disk.

The D-Bus `Manager1` methods `FirefoxStatus`, `SetFirefoxEnabled` and `RestoreFirefoxContext` require the usual read/control access grants. Same-user local programs are within the existing desktop trust boundary; the Unix socket is not a sandbox against another program running under the same UID.

CLI equivalents are `unityctl firefox status`, `unityctl firefox enable`, `unityctl firefox disable`, and `unityctl firefox restore PROFILE [CONTEXT]`. CSV export and cross-device browser sync are not implemented.

## Validation

`tests/unity.test.mjs` checks opt-in, summary minimization, commit/deletion sync, offline saves, reconnects, restore routing and setting-write failures. `Unity_Linux/tests/firefox_integration.py` uses the actual daemon, D-Bus/CLI and Python host to test framing, both control directions, private permissions, malformed/oversized messages, profile isolation, disable/restart and authorization. `tests/firefox/unity.mjs` runs actual Firefox native messaging against an isolated installed daemon and profile; it redirects native-manifest lookup inside only that test browser.
