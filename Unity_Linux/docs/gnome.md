# GNOME Wayland

Unity uses GNOME's desktop portals and native notification service. Install the Linux update and companion 0.1.19 together, keeping existing application data and pairing.

The main sidebar and content headers use [Adw.ToolbarView](https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/class.ToolbarView.html) with `ToolbarStyle.FLAT`. Header bars are transparent over their pane's native theme background, removing the horizontal header/content separators in both light and dark appearances. The vertical navigation divider remains native.

## Global menu and top-bar indicator

Unity exports a native GTK4 menu and actions for [Orbit Global Menu](https://github.com/Unmade760/orbit-global-menu). Enable Orbit and install its `global-menu` daemon, then focus Unity to see **File**, **Phone**, **Media**, **Screen**, **View**, **Settings** and **Help** in the panel. The window's menu button provides the same controls. Native Wayland and X11 work without toolkit environment overrides or shortcut mappings.

Enable GNOME's **AppIndicator and KStatusNotifierItem Support** extension (`appindicatorsupport@rgcjonas.gmail.com`) to show Unity's symbolic icon in the top bar. The compact popup keeps **Open Unity**, **Find Phone/Stop Ringing** and **Send Files** at the top, with **Share**, **Phone**, **Media and Audio**, **Screen Sharing**, **Open Page** and **Settings** submenus. Unity publishes this using Gio/D-Bus; no GTK3 AppIndicator library is required.

Both menus expose the same actions:

- Send files or a link, start a new message, browse phone files, open the last received file, or open Photos, Notifications and Calls.
- Find or lock the phone, choose Ring/Vibrate/Silent/DND, request hotspot on/off, disconnect an exact phone-reported Bluetooth device, switch the selected phone and refresh connections.
- Choose a phone or laptop player, play/pause/skip, mute either device or adjust its volume by 10%, stream audio in either direction, stop streaming, and choose Automatic/Wi-Fi/Bluetooth audio transport.
- View the phone screen, share the computer for viewing or control, use the phone as an extra or main display, stop sharing, and restore or turn off the laptop display when supported by the active session. Existing capture/remote-input consent still applies.
- Toggle supported phone features and the Firefox companion, choose System/Light/Dark appearance, keep running when the window closes, and show or hide the indicator's phone-status rows.

Enabled and checked states follow the daemon's reports and current permissions. Hotspot, ringer, mute and feature requests do not fake successful state changes. Appearance, audio connection, close-to-tray and status visibility are saved in `~/.config/unity/desktop.json`; the same controls are available in the app's Settings/Media pages.

With **Keep Running When Window Closes** enabled (the default), closing a regular Unity window keeps it available through the indicator while an indicator host is registered. **Quit Unity** or **Ctrl+Q** exits the frontend. The independent daemon continues running. Without a host, the window closes normally; if the host disappears while Unity is hidden, its window reappears. Turning off close-to-tray while hidden also reveals the window. Call-only launches retain their existing lifetime. Launch Unity once after login to get its indicator; installing the client does not enable automatic startup.

Update only the frontend from `Unity_Linux/` with `python3 scripts/install.py --component gtk`, then quit and reopen Unity. Keep the existing daemon and CLI installed; no Android update or pairing reset is needed.

## Clipboard

Install `wl-clipboard` (or `xclip` on X11). The daemon applies incoming text and monitors desktop changes automatically, including while Unity's window is closed. Wayland data-control is used where supported. GNOME automatically falls back to windowless GTK/XWayland clipboard events; keep XWayland and the existing GTK/PyGObject runtime available. A session without either data-control or XWayland cannot provide background reads.

Android applies received text automatically. Background phone-to-computer sending uses Shizuku access while keeping the current keyboard. Without background access, Android permits sending only while Unity has focus. See [automatic clipboard setup and limits](clipboard-sync.md).

## Screen sharing

Install the GNOME portal backend, PipeWire GStreamer source and JPEG/video plugins. Choose **View and control computer screen** on the phone and approve a display and mouse/keyboard access in GNOME's chooser. A live GNOME 50 Wayland capture with keyboard/mouse approval produced a valid JPEG on 28 October 2026; phone rendering and physical input still require a connected phone to validate.

The daemon retrieves the graphical environment from the user service manager if it started before the desktop session. KDE's unattended permission and physical display-off mirror are KDE-specific; leave the display-off option disabled on GNOME. Ordinary approved screen sharing is independent of that option.

## Notifications and call controls

Enable phone notification-listener access and Unity mirroring on Android, plus Unity banners in GNOME Settings → Notifications. New phone notifications create native desktop alerts. Existing notification-list snapshots remain quiet. Desktop notification clicks forward GNOME's activation token to the verified GTK launcher so their target window can receive focus.

Bluetooth call control requires oFono and WirePlumber configuration on GNOME as well as KDE. Run from `Unity_Linux/`:

```sh
./scripts/setup-bluetooth-calls.sh
```

This command requests administrator authentication to install/enable oFono and briefly restarts WirePlumber. Stop computer call audio before running it. Reconnect the phone in Bluetooth settings, enable its Calls profile and choose that phone in Unity → Calls. Enable Android Call management and its normal permissions. Live HFP call state drives the call-control popup; without the backend, those controls cannot operate. GNOME manages foreground focus and window stacking; the KDE **keep above** setup script does not apply to GNOME.

## Reconnection

Companion 0.1.19 responds to network changes and Bluetooth power-on, cancels pending backoff when a radio returns and keeps at most one connector per radio. Losing a LAN connection leaves Bluetooth intact and vice versa. Interrupted files retain their original connection and require an explicit retry. A rejected feature update is discarded without immediately terminating either authenticated transport; repeated invalid updates still close the offending session.

Automated checks cover isolated TLS/BlueZ links and Android coordinator recovery. Long-term Wi-Fi/Bluetooth stability, OEM power management and real incoming calls remain physical-device checks.

Portal API reference: [Remote Desktop](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.RemoteDesktop.html).
