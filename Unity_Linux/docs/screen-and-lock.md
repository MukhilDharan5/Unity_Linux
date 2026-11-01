# Screen sharing and remote locking

Update both Unity Linux and the Android companion (0.1.23 or later for the new viewer controls). Install the daemon component as well as GTK: it includes `libexec/unity/screen-capture.py` and `display-guard.py`. Restart the updated daemon and reconnect the phone. Existing pairing and settings are retained.

On Android, enable **Features → Remote control → Remote device locking** and **Screen sharing**. Both are off by default. Linux also has per-device feature switches in Settings; disabling either feature blocks phone requests as well as desktop requests.

## Locking

- Linux **Remote Control → Lock phone** locks the paired phone. First use **Activate phone locking** in Android’s Features page and approve the Android device-admin dialog. Unity requests only `force-lock`; it does not request wipe, password changes, or device ownership. Remove the grant in Android’s device-admin settings when desired.
- Android **Overview → Lock computer** locks the computer’s desktop session. Unity calls the desktop ScreenSaver lock service, with GNOME and `loginctl lock-session` fallbacks. A missing or failing desktop lock service produces an error.

Unlock locally on the locked device. Remote unlock is not provided.

## Screen sharing

Either app can initiate either direction:

| Direction | Linux Remote Control | Android Sharing |
| --- | --- | --- |
| Computer → phone | Computer screen → phone: Share | View and control computer screen |
| Phone → computer | Phone screen → computer: View | Share phone screen |

With normal desktop permissions, Linux asks for a monitor plus keyboard/mouse approval through the [RemoteDesktop portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.RemoteDesktop.html). Turn off **Control computer from phone** in Linux for view-only screen/window capture through ScreenCast. Android phone capture always opens the [MediaProjection dialog](https://developer.android.com/media/grow/media-projection), then shows an ongoing foreground-service notification. When Unity is in the background on Android, tap its action notification to approve capture or open the computer viewer. Protected Android content can appear blank.

Linux shows the phone in a separate, resizable GTK viewer. Android fits the computer image to its viewer in portrait or landscape. The viewer follows the orientation sensor even when the phone’s system rotation is locked; use its **Rotate** button to choose landscape/portrait, or hold that button to return to automatic rotation. Rotating the computer viewer keeps the same activity and screen session, releases held pointer/keyboard input, and fits the existing image to the new bounds. Phone rotation and capture-content resizing reuse the approved virtual display and update its surface; no projection consent token is reused for another capture. In the computer viewer, tap to click, drag to move a held mouse button, and use two fingers to scroll. Taps in the black margins are ignored. The keyboard icon opens/closes your selected Android keyboard and sends its committed text, backspace, Enter and navigation keys to the laptop. This direction needs no Android accessibility or Unity keyboard grant. Existing computer-to-phone remote input remains separate.

To use the phone as the computer’s display, choose **Use phone as main display** on Android, or enable the same option before starting from Linux. Unity requests a temporary **virtual monitor** from the ScreenCast/RemoteDesktop portal rather than recording a physical panel that is about to be disabled. Native desktop approval remains in place. Unsupported portals report an error and leave the laptop panel on.

The virtual monitor is identified only after PipeWire has negotiated video and produced a frame. Unity then waits until the phone has decoded a frame in a visible viewer. On GNOME, it applies a temporary [Mutter DisplayConfig](https://github.com/GNOME/mutter/blob/main/data/dbus-interfaces/org.gnome.Mutter.DisplayConfig.xml) layout with the virtual monitor primary and the built-in panel omitted, which disables the physical output. On KDE, it makes the virtual output priority 1 and disables the laptop output using KScreen. This changes the OS monitor topology; it does not lower backlight brightness, cover the screen in black, or globally suspend capture. Other connected outputs are retained where the compositor can preserve their layout. Normal screen sharing without this option keeps using the selected physical screen/window; start a new share with the option enabled to hand over the primary display.

The display icon on the phone and **Laptop display → Turn on/off** on Linux restore or reapply the handoff during the session. The phone reports **Phone is main display** only after the compositor accepts the handoff. Sharing inhibits idle/suspend. The new virtual source requests the laptop’s native pixel dimensions, while the network JPEG remains within the existing 1280-pixel/921,600-pixel limits.

A separate watchdog restores the previous display layout on capture/daemon exit, pipe closure, or five seconds without delivered-frame heartbeats. Its private version-3 journal records the original output layout, primary monitor, modes and scales; GNOME color/underscan settings and KDE output priorities are retained. Recovery works even if the portal’s temporary monitor has already disappeared. Failed restoration retains the journal for retry. Existing brightness/DPMS journals are supported for recovery only; new sessions never use those methods to blank the display. Stopping sharing, closing the viewer, losing its owning connection, disabling screen sharing, or unpairing ends control and restores the laptop output.

Use **Stop screen sharing** on either app, close the receiving viewer, or stop capture through the OS indicator. Capture and the stream also stop on disconnect, permission/feature revocation, replacement connections, unpairing, daemon shutdown, and stalled network writes or capture process failure. Permission denial and missing capture components are reported instead of an empty successful stream.

## Phone as an extended display

With companion **0.1.24+** and the matching Linux update, select **Use phone as extended display** from Android’s Sharing page. On Linux, enable **Remote Control → Screen sharing → Use phone as extended display**, then press **Share**. The main-display and extended-display choices are mutually exclusive; leaving both off uses ordinary screen sharing.

The phone becomes a separate OS monitor to the **right** of the existing desktop. The laptop display stays on, and the current primary monitor remains primary. Move the laptop cursor across the right edge or drag a window onto the phone. The desktop’s ordinary Display settings can arrange the monitors during the session. The phone viewer retains landscape/portrait rotation and touch/keyboard controls when approved.

From Linux, **Control computer from phone** can be turned off while extending: the laptop’s own mouse and keyboard still work across both monitors, and the phone remains a view-only display. Native portal approval is still required. A portal without virtual-monitor support reports an error instead of mirroring the laptop screen.

The physical-panel toggle is disabled during extended mode. Stopping, disconnecting, or closing the viewer removes the temporary monitor and restores the saved layout. The independent guard journals extended layouts as well as primary handoffs, so restoration also runs when the laptop panel has remained on. Extended mode uses the same authenticated Wi-Fi/Bluetooth screen connection and JPEG bounds as the other screen modes.

Companion 0.1.24 validation includes 92 Android tests, 21 display-guard tests, eight portal/GStreamer tests, actual D-Bus/TLS extended-mode propagation and panel-off rejection, and GTK option/exclusivity checks. A native GNOME probe used the production guard to create an active 1920×1080 secondary monitor at `(1920, 0)`, verified that the laptop remained active and primary, received a frame, restored the layout and removed the temporary monitor. Physical-phone image quality, touch behavior and latency still need device validation.

## Starting entirely from the phone on KDE

Install the updated daemon, then explicitly enable Unity’s unattended permission once:

```bash
python3 ~/.local/libexec/unity/setup-screen-sharing.py
```

This uses KDE’s [documented unattended remote-desktop permission](https://github.com/KDE/krdp#cli), scoped to `org.unityconnect.Unity.Screen`. The helper registers this dedicated installed application ID before making portal calls; no permission is granted to generic host applications. The paired phone and both screen-sharing feature policies are still required. A logged-in KDE Wayland session must already be running.

After setup, **View and control computer screen** or **Use phone with laptop display off** starts directly from the phone without a laptop chooser. Existing companion 0.1.13 works with this desktop update. Disable the unattended grant with:

```bash
python3 ~/.local/libexec/unity/setup-screen-sharing.py --disable
```

Other desktop environments retain their native approval flow.

## Transport and dependencies

Screen pixels use a separate pinned TLS 1.3 connection on the existing Unity port, authorized by a random single-use token bound to the active paired control session. No extra firewall port is needed. The binary stream is `u32 big-endian JPEG length` followed by that JPEG. Capture runs at up to 15 fps, preserves aspect ratio, limits the longest edge to 1280 pixels and total pixels to 921,600, and caps each encoded frame at 256 KiB. Buffers keep the latest frame; slow receivers stop after a bounded write timeout. Screens are kept only in memory and are never recorded. Screen streams carry video; laptop audio uses the independent Media audio-routing option.

Linux needs Python/PyGObject, GStreamer introspection, the PipeWire GStreamer source, standard video conversion/scaling/rate and JPEG plugins, PipeWire, and `kscreen-doctor` for KDE output configuration (GNOME uses Mutter DisplayConfig), and a ScreenCast-capable desktop portal backend (normally the GNOME or KDE backend matching the desktop). `scripts/fedora-deps.sh` includes the base capture dependencies. Headless systems or desktop environments without a ScreenCast backend report capture unavailable. The helper runs independently of GTK, so computer-to-phone sharing works with a headless Unity frontend while the desktop session remains available.

`Screen1.GetFrame()` returns the latest phone JPEG only to approved D-Bus readers; `FrameAvailable(sequence)` broadcasts metadata without pixels. Start/stop/input/display use the existing control-authorized Device1 Execute method. Input and display commands also require the current desktop session ID and approved portal input devices. Ordinary state snapshots contain only sharing status and direction, never stream tokens or JPEG bytes.

## Validation

Automated checks cover both TLS directions, both lock command directions, token replay/device mismatch, malformed/oversized frames, D-Bus read/control and operation ownership, permission denial, consent cancellation, toggles, stalled receivers, replacement sessions, revocation, and capture-session closure. The portal helper is tested with real FD passing and the real GStreamer scaler/JPEG encoder using an isolated test source. Additional tests cover normalized mouse coordinates, Unicode/key delivery, stale input sessions, read-only client rejection, display restoration after stop/disconnect/replacement/revocation, and watchdog parent death/missed heartbeats. Android tests cover the keyboard input connection, composing text, black-margin rejection, lock opt-in/admin activation, bounded image decoding, aspect ratios, stale consent and disconnected viewers; GTK and Android layouts are rendered with explicit fixtures.

Physical phone capture, rotation, device locking, desktop compositor behavior, and end-to-end latency still require hardware testing. No ADB device was attached during this implementation.

On 14 October 2026 the real installed KDE/Wayland desktop was checked with unattended permission: capture started without a dialog, the eDP panel was disabled while the temporary output stayed active, a normalized click reached a native GTK test window and further JPEG frames succeeded with the panel off, and closing the helper input restored the panel and removed the virtual output. No screen pixels were saved. Phone-to-laptop network latency and actual phone keyboard gestures remain separate hardware checks.

The current KDE backend delivers ordinary keyboard text, but its NotifyKeyboardKeysym path ignored Tamil/emoji characters in a native GTK check. UTF-8 schema/encoding tests do not establish native Unicode-key delivery. This existing compositor limitation is not a clipboard-based workaround; Unity leaves the user’s clipboard unchanged.

Companion 0.1.23 validation: 17 display-guard checks exercise GNOME/KDE primary selection, real GLib monitor-configuration signatures, idempotent Off, logical scaling, restoration after a missing virtual monitor, failed restoration/retry, and watchdog parent death. Seven real portal/GStreamer tests include virtual sources requested through ordinary approval without a KDE unattended grant, and deferred guard startup after the first negotiated frame. Android checks rotate the live viewer to landscape and back without replacing the session and render `app/build/reports/unity-preview/screen-control-landscape-test.png`. A native GNOME probe created a temporary 1920×1080 virtual monitor, received frames and successfully validated the phone-primary/panel-disabled configuration using Mutter’s **verify-only** method, keeping the physical panel enabled; it removed the monitor afterward. Physical handoff with the updated APK remains a phone/device validation step.
