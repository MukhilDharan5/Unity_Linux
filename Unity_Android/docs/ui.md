# Android companion interface

Companion 0.1.2 uses native Kotlin Android Views with an Adwaita-inspired style layer. It shares Unity Linux's blue accent, app icon, neutral backgrounds, rounded bordered cards, divided preference rows, typography hierarchy, and restrained spacing. Android does not embed GTK or require a GNOME runtime. The production APK has no new UI-library dependencies; Robolectric is a test-only dependency.

![Android companion light and dark widget renders](images/android-ui.png)

These are actual production-widget renders from automated Android UI tests, with no paired computer in the test environment.

The navigation adapts to the available space:

- **Overview** shows actual connection state, the saved computer, connect/disconnect actions, and shortcuts.
- **Sharing** contains file sending, shared-folder selection, clipboard send/apply actions, and URL sharing/opening.
- **Features** groups opt-in switches and Android permission/setup actions by notifications, messages/calls, files/photos, clipboard/links, media, remote control, and internet sharing. Unimplemented Bluetooth transport, call answering, and screen mirroring are identified explicitly.
- **Settings** contains computer address/port/verified TLS fingerprint, pairing removal, system/light/dark appearance, notification exclusions, and app information.

Phones use a bottom view switcher. Wide windows use a sidebar and a content width capped at 720 dp. At narrow widths or larger font settings, the bottom switcher uses icons with accessible names rather than truncated labels. Text uses sp, spacing uses dp, rows grow with wrapped text, interactive controls have at least 48 dp height, and section titles carry accessibility heading semantics. Native window insets keep content clear of system bars, cutouts, and the keyboard.

The interface observes process-local service state events and preference changes. It does not poll the phone or create transport connections. State invalidations are coalesced on the main loop; no exported status receiver exists. It registers observers only while visible, rechecks state on return, and disables sending controls when the transport or required capability is unavailable. The service owns transfers and connection lifetime; leaving the activity does not stop either. Incoming clipboard content is applied only by an explicit foreground action.

Existing preference names, phone identity, Android Keystore credentials, and wire protocol v1 are preserved. Editing the TLS identity now requires confirmation before removing the previous pairing credential. Forgetting the computer also requires confirmation, stops the connection, and explains that the computer's saved trust must be revoked separately. Configuration changes preserve the current page and draft input; recreating the activity does not replay an Android share intent.

Native Android 15 Robolectric tests render the actual production widgets and exercise offline controls, consent, pending clipboard changes, saved drafts, activity recreation, system/keyboard insets, live connection events, and independent service lifetime. PNG captures are generated under `app/build/reports/unity-preview/`. A screenshot named `overview-connected-test` uses an explicit test-only service-state fixture; it is not proof of an authenticated physical phone connection. Test fixtures are not packaged in the production APK.

Validation on 2026-10-12: the debug APK built successfully, all 17 Android tests passed (10 UI tests and 7 protocol/image tests), and Android lint reported zero errors and six warnings. The UI tests also verify that an explicit dark appearance overrides a light system setting. Remaining lint warnings concern target SDK updates, the existing pinned TLS trust manager, backup rules, and intentional synchronous preference writes.

Build and test with the commands in [build.md](build.md). The updated debug APK can be installed over companion 0.1.1 using the same application ID and signing key, retaining pairing and settings. Android feature permissions, OEM background behavior, and real file/notification/media outcomes still require physical-device validation.

Design references: [GNOME UI styling guidance](https://developer.gnome.org/hig/guidelines/ui-styling.html) and [Android window-inset guidance](https://developer.android.com/develop/ui/views/layout/edge-to-edge).
