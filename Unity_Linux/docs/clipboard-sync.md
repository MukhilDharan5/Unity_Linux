# Automatic clipboard sync

Update the Linux daemon/GTK app or Windows app and Android companion **0.1.33** together. Clipboard text automatically follows changes in either direction over the paired encrypted connection. There are no send, receive or apply buttons. Clipboard starts enabled on Android; an explicitly saved disabled setting remains disabled. Either device's Clipboard feature switch stops sharing.

Received text is applied directly, including when the Android activity or Linux window is closed. Automatic changes do not create received-text notifications. Unicode, embedded and trailing newlines, and empty text are preserved. Text is limited to 65,280 UTF-8 bytes; file/URI clipboards and oversized text are not sent as text. Gallery file-copy remains separate. Clipboard contents are not written to diagnostics.

## Android background access

Android 10+ only permits clipboard reads by the focused app, the selected keyboard, or a privileged system app. A foreground connection service alone does not grant background clipboard reads. Unity automatically sends when it can read, and checks again on window focus, unlock and reconnect. Incoming computer text can be written in the background without a tap.

For background phone-to-computer sending **without changing your keyboard**:

1. Install [Shizuku](https://shizuku.rikka.app/download/) version 13 or newer and follow its [startup guide](https://shizuku.rikka.app/guide/setup/). Android 11+ supports wireless debugging setup on the phone; Android 10 needs a computer or root.
2. Connect Unity to your paired computer and keep **Features → Clipboard** enabled.
3. Open **Sharing → Background clipboard access → Set up / Allow** and allow Unity's Shizuku request. If Shizuku is not running, the action opens Shizuku or its download page. Return to Unity after starting it and tap **Set up / Allow** again.
4. Wait for the background-access status to say it is active, then copy text in either device's apps. Unity sends and applies text automatically; there is no per-copy action and your selected keyboard stays unchanged.

Non-root Shizuku startup must be repeated after a phone reboot. Unity reconnects automatically when Shizuku is running and authorized again. If access is denied or lost, status explains the setup needed; incoming computer text still applies and foreground sending remains available. Locked-device and OEM policies can still deny reads; Unity refreshes on unlock.

The authorized reader registers native clipboard change callbacks in a Shizuku shell/root user service, with no clipboard polling interval. It handles only single-item text and never executes arbitrary shell commands. The app checks authorization before accepting a callback and discards observations older than an incoming clipboard write. Disabling Clipboard or disconnecting stops the reader. No default-keyboard or secure-setting changes are made by this setup.

See Android's [clipboard access restrictions](https://source.android.com/docs/whatsnew/android-10-release) and the [platform clipboard implementation](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/clipboard/ClipboardService.java).

## Desktop behavior

Linux owns synchronization in `unityd`; the GTK window is only a status view. Install `wl-clipboard` on Wayland, Python 3 and the existing GTK/PyGObject runtime. Wayland uses `wl-paste --watch` where data-control is supported. On GNOME, it automatically falls back to a windowless GTK helper using XWayland clipboard events; no GTK app window is needed. If that helper is unavailable, `xclip` on X11 provides a 100 ms polling fallback. A pure Wayland session without data-control or XWayland cannot provide background reads through these backends. Missing clipboard helpers or a missing graphical session are retried automatically. Incoming clipboard writes run separately from transport and notification processing, with retries after a temporary helper failure.

Windows uses native clipboard change events while Unity is running, including in the notification area. Quit stops synchronization. Its clipboard access and physical timing need validation on Windows.

Duplicate text is suppressed so remote writes do not echo back. Linux and Windows send to each eligible paired device once and synchronize the current clipboard after reconnect; Linux retries unacknowledged sends. A newer local or remote clipboard replaces older pending text. Disabling sharing or revoking pairing prevents later writes from a pending remote update. Simultaneous independent copies resolve by observed arrival order; this is not a cross-device clipboard history.

Changes are sent as soon as platform events and the connection allow; zero latency is not guaranteed. Automated tests use isolated peers and clipboard helpers. Actual Shizuku authorization/recovery, phone background delivery, OEM service restrictions and radio latency still require physical-device validation.
