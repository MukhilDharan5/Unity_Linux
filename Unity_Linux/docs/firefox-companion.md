# Firefox companion

Unity Linux includes the receiving server for the [Session Continuity Firefox extension](../../Unity_Continuity_Companion/firefox-session-continuity/README.md). No paired phone is needed.

1. Build and install the updated daemon and GTK app with the normal [installation commands](../README.md#build-and-install-on-fedora), then restart `unityd.service` and reopen Unity.
2. Load/install the updated Firefox extension and accept its native messaging permission.
3. In Unity **Settings**, turn on **Enable Firefox extension**.
4. In the extension's **Settings & privacy**, turn on **Connect to Unity Linux**.
5. Open Unity's **Firefox** page. Saved URLs, media times and reading progress appear for each connected profile. **Restore** asks Firefox to restore that checkpoint; **Restore last active** uses its active selection. Browser policy and user-input cancellation still apply. Closed contexts cannot be reopened from Unity.

The installer registers `org.unityconnect.firefox` in the standard Firefox native-host directory and installs its Python 3 host alongside `unityd`. Neither switch is enabled by installation. Closing Unity's window leaves the daemon and extension connection running. Standard Linux Firefox is supported; sandboxed Flatpak/Snap installations require separate host/portal integration and are unverified.

Both switches are required. If disconnected, check `systemctl --user status unityd`, the native host path in `~/.mozilla/native-messaging-hosts/org.unityconnect.firefox.json`, and the extension's connection status. Reconnect is attempted automatically every 30 seconds. Reinstalling only GTK does not install the host; install the daemon component or all components. A custom `--state-dir` requires `UNITY_FIREFOX_STATE_DIR` in the Firefox environment.

The connection uses a same-user, mode-0600 Unix socket in Unity's private state directory. It does not open a browser integration network port. Only page summaries leave Firefox; full checkpoints and form values stay in the browser. Summaries are held in daemon memory and cleared on disconnect or disable. Only the enable flag persists. [Protocol, exact data fields and installation paths](../../Unity_Continuity_Companion/firefox-session-continuity/docs/NATIVE-BOUNDARY.md) are documented with the extension.

Headless equivalents:

```sh
unityctl firefox enable
unityctl firefox status
unityctl firefox restore PROFILE_ID CONTEXT_ID
unityctl firefox restore PROFILE_ID
unityctl firefox disable
```

The D-Bus manager exposes `FirefoxStatus() -> s` (JSON, read grant), `SetFirefoxEnabled(b)` and `RestoreFirefoxContext(ss) -> s` (command ID, control grant). Changes use the existing `Manager1.Changed` signal. Command acceptance is distinct from eventual media/scroll restoration; the Firefox page shows the returned note and current restoration status.

Run `dbus-run-session -- python3 tests/firefox_integration.py` after building debug binaries. Extension unit and actual Firefox end-to-end commands are in the extension's testing guide.
