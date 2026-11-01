# Loading, signing, and restart testing

## Temporary development loading

1. Build with `pnpm build`, or use the included generated bundle.
2. In Firefox desktop 153+, open `about:debugging#/runtime/this-firefox`.
3. Select **Load Temporary Add-on** and open `extension/manifest.json` (or the unsigned XPI).
4. Grant ordinary HTTP/HTTPS host access. Use the toolbar button and options page.
5. Reload the add-on after rebuilding. Reloading/installing does **not** trigger startup restoration.

Temporary add-ons are removed when Firefox restarts. Their storage/installation lifecycle does not establish persistent restart correctness. Do not interpret a successful temporary-loading test as a signed-installation test.

## Persistent normal installation

Release and Beta Firefox require Mozilla signing. Submit the package and readable source through Mozilla Add-ons, using either listed distribution or unlisted/self-distribution signing. Keep `session-continuity@local.invalid` unchanged. Install the resulting signed XPI through `about:addons` → gear → **Install Add-on From File**.

The artifact delivered here is explicitly **unsigned**. No AMO submission/signing was performed, because no signing credentials or authorization to publish were supplied. Signing acceptance and marketplace review are unverified. See [Mozilla's signing documentation](https://extensionworkshop.com/documentation/publish/signing-and-distribution-overview/).

## Persistent isolated development testing

Use a portable Firefox Nightly or Developer Edition and a fresh profile inside this project's `.test-profiles/`. Do not reuse an ordinary profile, change the system default browser, or terminate any ordinary Firefox process.

The provided harness starts `-no-remote -headless -profile <dedicated path> -marionette`. Its automation system-access flag/environment variable is scoped to that child process. `tests/firefox/restart.mjs` explicitly writes `xpinstall.signatures.required=false` **only to the dedicated Nightly test profile**, then installs the XPI with `temporary:false`. This is a development-only procedure. Release Firefox does not become a valid persistent unsigned test platform merely by changing that preference.

The harness uses its exact owned child process for unexpected termination and never searches for/kills Firefox by process name. All test profile paths are checked to reside beneath `.test-profiles/`. It records the browser version, build, and outcomes. Portable Nightly remains uninstalled in `.test-runtime/` and is excluded from source/package deliverables.

The tests used Mozilla's portable Firefox 160.0a1 archive and checked its SHA-512 against Mozilla's checksum file. The downloaded browser, sandbox/runtime caches, dependency store, and synthetic browser profiles are not extension runtime dependencies.

After testing, these project-local test directories may be removed when no test process is using them. The extension does not modify another Firefox profile or the user's global browser security preferences.
