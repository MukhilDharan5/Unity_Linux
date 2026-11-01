# Phone and laptop audio

Install the matching Linux update and Android companion **0.1.22+**. On Android, enable **Features → Media → Phone and laptop audio**. On Linux, open **Media → Audio routing**, select **Audio connection**, then turn on either **Play laptop audio on this phone** or **Play phone audio on this laptop**. Stop the current stream before changing direction or transport.

The connection choices are **Automatic (prefer Wi-Fi)**, **Wi-Fi / LAN · stereo**, and **Bluetooth · mono**. Keep Android's **Settings → Connection method → Bluetooth + Wi-Fi / LAN** selected to maintain both connections. Choosing an audio connection does not disconnect the other link or change where ordinary controls and file transfers travel. An explicit unavailable connection reports an error; it never silently falls back. Automatic chooses Wi-Fi for a new stream, or Bluetooth when Wi-Fi is disconnected. Losing the stream's parent connection stops it; start again to use another link.

Linux needs `pactl`, `parec`, and `pacat` from `pulseaudio-utils`, with PipeWire-Pulse or PulseAudio running in the user's session. No root privileges or additional listening port are needed. Audio is independent of notification-listener permission. There is one audio stream at a time; restarting either device does not resume capture automatically.

## Laptop to phone

Starting creates a temporary null sink, makes it the laptop's default output, moves existing application streams there, and records its monitor. This redirects laptop playback to the phone. The phone's normal media volume and selected output apply. If playback is silent, check **Device volume → Phone media volume**, mute, and the selected phone output. Unity preserves volume settings.

Apps that explicitly select another laptop output can bypass the default; choose **Unity Phone** in their settings. Stopping, disconnecting, disabling audio, unpairing, capture/playback errors, or shutting down restores laptop output. Original output devices are remembered; new streams return to the old default. A user-selected default made during routing is preserved. Unplugged outputs use another available output. A private `audio-route.json` journal supports crash recovery; a failed restoration retains the journal and reports an error.

Phone playback synchronously establishes its media-playback foreground service before requesting Android audio focus. It starts AudioTrack before blocking writes, tolerates sequence gaps, and pauses/discards packets during temporary focus loss. Permanent focus loss stops the stream and reports its reason on the original control connection. Stops include the original request ID so a delayed stop cannot close a newer stream.

## Phone to laptop

Starting displays an approval notification on the phone and opens the consent flow when Unity is visible. Android asks for **Record audio** permission and its system capture approval for each session. This uses `AudioPlaybackCaptureConfiguration` with `AudioRecord` to capture permitted media/game playback; it never selects a microphone source and does not transmit screen images. Approval times out after 115 seconds; the laptop switch can cancel while approval is pending. The phone has a persistent **Sharing phone audio → Stop** notification while capturing.

Only apps that permit playback capture are audible. Calls, protected content, and apps that block capture cannot be forwarded. Android may continue playing through the phone's own output as well. These platform limits are described in [Android's playback capture documentation](https://developer.android.com/media/platform/av-capture).

Linux plays the received PCM through `pacat` on its normal current output without changing the default or creating a null sink. Capture approval rejection/revocation, network loss, feature disable, unpairing, output failure, Stop, and service shutdown close the stream and release the recorder, projection and Wi-Fi lock. Unity's own phone audio is excluded from capture.

## Stream format and buffering

Both directions use a separate pinned TLS 1.3 connection over the chosen transport: the existing TCP port on LAN, or the dedicated audio RFCOMM UUID on Bluetooth. A random one-use token delivered through that exact paired control-session generation authorizes the stream; a token from one transport cannot be used on the other. The connection deadline is 15 seconds for laptop playback or 120 seconds while awaiting phone capture approval.

The daemon sends `source`, actual `transport`, `rate`, `channels`, `frames`, `format:"s16le"`, `request_id`, and `token` in its internal start command. After framed JSON `ready`, packets contain an unsigned 64-bit big-endian increasing sequence number followed by exactly `frames × channels × 2` PCM bytes. There is no per-packet JSON or base64.

| Connection | PCM | Packet duration | PCM bytes per packet | Payload bandwidth |
| --- | --- | --- | --- | --- |
| Wi-Fi / LAN | 48 kHz stereo, signed 16-bit little-endian | 10 ms / 480 frames | 1,920 | 1.536 Mb/s |
| Bluetooth Classic RFCOMM | 16 kHz mono, signed 16-bit little-endian | 10 ms / 160 frames | 320 | 256 kb/s |

The previous Bluetooth path attempted the same 1.536 Mb/s stereo stream as LAN. The lower Bluetooth bandwidth leaves more room for radio scheduling and controls; it reduces sound quality. These are format budgets, not measured physical throughput or latency. BLE audio is not implemented.

Laptop capture retains only its latest packet and bounds stalled writes to one second. Android playback keeps six packets, discards packets over 100 ms old locally, requests a 40 ms AudioTrack buffer, and uses audio thread priority. Phone capture keeps three latest packets and aborts a stalled send after 1.5 seconds. A best-effort low-latency Wi-Fi lock is held only during LAN streaming and released on stop. Android hardware, Wi-Fi power policy, conversion, output buffers and radio conditions still affect delay.

## Verification

`tests/audio_integration.py` and its Bluetooth variant exercise real D-Bus/TLS and isolated sound tools: both directions, PCM integrity, wrong/replayed tokens, sequence/truncation rejection, approval cancellation, capture errors, output restoration, disable/revoke/disconnect, replacement sessions and crash recovery. `tests/audio_transport_integration.py` exercises all four direction/transport combinations while both connections remain alive, rejects cross-transport tokens, and verifies automatic Bluetooth fallback for new starts after LAN loss. Bluetooth uses the real BlueZ file-descriptor contract with a simulated radio, not physical throughput.

Android tests cover format bounds, packet framing, playback gaps/focus/errors, pending phone consent, denial on the original link, and stale stop rejection. GTK audio/media tests cover explicit choices, direction gating, cancellation during approval, silent-volume hints, error states and rendering without commands.

Companion 0.1.17 previously had a 30-second physical LAN playback check. The new 0.1.22 bidirectional and selectable-transport changes still need physical phone playback/capture and latency checks; no phone was attached through ADB during this change.
