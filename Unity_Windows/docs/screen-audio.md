# Laptop screen and audio on your phone

Use the existing Android companion, with Screen sharing and Laptop audio on phone enabled in Features. Connect over the same Wi-Fi / LAN for screen and audio; Bluetooth can remain connected for controls. The Windows update uses the existing protocol and does not require a new APK.

## A black laptop screen with a live phone view

In Windows Unity → Remote Control, select **Blank laptop display while sharing**, then **Share Windows** and approve the share. Open the live computer viewer on Android. Its first decoded, visible frame allows Android to request blanking. You can also start with Android's **Use phone with laptop display off** action and approve the request on Windows.

The Windows implementation covers local displays in black while Windows Graphics Capture keeps the primary display streaming to the phone. The cover is excluded from capture and passes mouse/keyboard input through. The phone's display button and Windows **Blank display / Restore display** change it during sharing. **Ctrl+Alt+Shift+U** restores the laptop locally, and updates the state on the phone. Stop, disconnect, feature revocation, unpair and app exit remove the cover and release the sharing power requests. Only one Windows desktop share can own the displays at a time; view-only shares cannot blank them.

**This is display blanking, not physical panel power-off.** The panel remains powered and Windows keeps rendering. Native testing on this laptop found that a true monitor power-off request freezes capture or wakes the panel. The black-screen mode avoids that failure and does not claim panel power savings. A Windows capture border, the cursor, or system surfaces can still appear locally. Closing the lid or explicitly sleeping Windows can interrupt sharing.

The capture uses a bounded pool of two GPU frames and retains the latest frame. JPEGs retain the existing 1280-pixel longest-edge, 921,600-pixel, 256 KiB and 15 fps protocol limits. Static desktops may reuse their last frame. No screen pixels are written to disk. Local Windows approval remains required for each share; primary-display selection and elevated-window input limits remain unchanged.

The underlying APIs are [Windows Graphics Capture](https://learn.microsoft.com/windows/uwp/audio-video-camera/screen-capture), [capture exclusion](https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-setwindowdisplayaffinity), and scoped [Windows power requests](https://learn.microsoft.com/windows/win32/api/winbase/nf-winbase-powersetrequest).

## Windows audio on Android

Open Windows Unity → Media → **Play on phone**. Set **Phone media volume** and unmute it if needed. Audio routing works separately from screen sharing and mirrors the Windows output; laptop speakers continue playing. **Stop** ends capture and releases Android playback. The Android speaker or wired headphones generally avoid the extra delay of a Bluetooth headset.

WASAPI requests a 20 ms capture buffer with multimedia thread scheduling. A silent render stream on the same output keeps loopback capture alive before music starts and during pauses, without changing the output volume. Capture callbacks assemble complete 10 ms, 48 kHz, stereo 16-bit packets directly. There is no second sender clock or 250 ms PCM FIFO. A four-packet queue drops its oldest packets when the receiver falls behind; packets older than 60 ms are discarded before sending. Audio TCP connections disable Nagle and request a 16 KiB send buffer. A network write stalled for a second ends the stream and reports the failure to Android and Windows.

Default-output changes are checked every 250 ms. Capture and silent rendering move to the new Windows output inside the same authenticated PCM stream, retaining monotonic sequences. The first packet on the new endpoint drops any old partial samples. Output failure is surfaced instead of leaving playback silently marked active.

The existing companion keeps its bounded PCM queue, low-latency AudioTrack request, audio-priority playback thread, temporary-focus handling and LAN Wi-Fi lock. These buffer settings reduce avoidable delay; they are not a measurement of end-to-end phone latency. Wi-Fi, Android hardware, audio focus, and the selected phone output still matter. Physical Android playback and acoustic latency need a real phone check.

The capture behavior follows the pinned [NAudio 3.1 recorder API](https://github.com/naudio/NAudio/blob/v3.1.0/Docs/WasapiRecorder.md).
