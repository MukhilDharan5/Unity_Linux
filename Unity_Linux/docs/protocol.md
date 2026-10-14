# Unity wire protocol 1

Transport framing is an unsigned 32-bit network-order byte length followed by one UTF-8 JSON object. Reject zero length, frames above 524,288 bytes, invalid JSON, unknown session message types, invalid identities, and unsupported protocol versions. TLS early data is disabled. Command JSON and operation results are bounded to 65,536 bytes. Phone names are bounded to 128 bytes. IDs are 32 lowercase hexadecimal characters.

Handshake:

1. Android verifies the explicitly entered SHA-256 fingerprint of the Linux certificate during TLS 1.3 establishment.
2. Phone sends `{"type":"hello","version":1,"device_id":"...","name":"...","capabilities":[...]}`.
3. Unknown phones require an open discovery window. Server replies `pair_pending`; a local authorized client must approve within 90 seconds.
4. Server persists a random 32-byte secret and replies `{"type":"pair_result","secret":"64 hex digits"}`. Phone stores it protected by Android Keystore and reconnects.
5. Trusted phones receive `{"type":"challenge","nonce":"64 hex digits"}` and send `{"type":"proof","mac":"64 hex digits"}`.
6. HMAC-SHA256 key is the decoded secret. Message bytes are UTF-8 `unity-v1:DEVICE_ID:NONCE`. Successful validation returns `{"type":"ready"}`.

Never treat discovery replies as proof of identity. The fingerprint must be compared out of band. Linux's certificate is the desktop identity; each random credential binds the approved phone's identity. No unencrypted application data or password exchange is used.

Session messages:

```json
{"type":"event","feature":"battery","data":{"level":73,"charging":true}}
{"type":"command","id":"32 hex digits","feature":"find","action":"ring","data":{}}
{"type":"result","id":"32 hex digits","ok":true,"data":{"state":"ringing"},"error":""}
{"type":"capabilities","capabilities":[{"id":"battery","status":"available","reason":""}]}
{"type":"ping"}
{"type":"pong"}
```

Capability statuses: `available`, `permission_required`, `user_action_required`, `unsupported`, `disabled`. Linux intersects phone advertisements with its own feature implementation and local toggles. A phone cannot enable a Linux feature that has no implementation. Android rechecks permissions and consent for every command.

| Feature | Commands | Events/results |
| --- | --- | --- |
| pairing, reconnect | Manager methods; not remote commands | lifecycle |
| battery | refresh | level 0..100; charging boolean; optional battery power and time telemetry below |
| network | refresh | kind; signal null or 0..4; operator; internet |
| connectivity | status via Device | authenticated LAN or Bluetooth RFCOMM capability |
| notifications | list; dismiss `{id}` | id; app; optional app_name/timestamp; title; text; actions; removed |
| notification_actions | invoke `{id,action}`; reply `{id,action,text}` | submission result |
| sms | list with optional `{address}`; send `{address,body}` | up to 80 messages within a 58,000-byte budget; id/address/body/timestamp/direction/type/thread_id; revision event on SMS provider changes; modem submission acknowledgement |
| call_notifications | incoming events | state; number, possibly empty |
| call_control | answer; hangup are defined but unsupported | no fake success |
| clipboard | set `{text}` | explicit foreground clipboard sharing |
| files | client sends path; internal begin/chunk/end/abort | streamed verified transfer |
| photos | list; preview `{id,edge}`; get `{id}` | items with opaque selection IDs, name/size/timestamp/dimensions; preview_supported; bounded JPEG preview; original file transfer |
| urls | open `{url}` | URL event; phone review prompt |
| browsing | list `{id}` (empty means granted root); get `{id}` | entries with opaque provider tokens |
| media | play; pause; next; previous; seek `{position}`; refresh | title/artist/playing; optional album/app_name/duration/position/package/active/actions/artwork |
| audio | start `{}`; stop `{}` | Laptop-owned starting/streaming/stopped/failed state; separate authenticated binary PCM stream, see [audio routing](audio-routing.md) |
| ringer | set `{mode}` | normal, vibrate, silent, dnd, off |
| find | ring; stop | action result; ringing stops after 30 seconds |
| remote_input | text `{text}`; tap `{x,y}`; swipe `{x,y,end_x,end_y}` | performed/cancelled result; normalized coordinates |
| screen | start `{source: "phone" or "desktop", control?: bool, blank_display?: bool}`; stop `{}`; input/display scoped by `session_id` | dedicated stream, status only in snapshots |
| lock | lock `{}` | native lock acknowledgement |
| hotspot | request_settings | user action required; never confirms active tethering |

Transfers begin with `{id,phase:"begin",name,size,sha256}`, then `{id,phase:"chunk",data:"base64"}`, then `{id,phase:"end"}`. Phone-to-Linux transfers use `event` messages, including `{id,phase:"abort"}` to remove an interrupted partial file without closing the session. Linux-to-phone transfers use `command` messages with action begin/chunk/end/abort and one operation ID. At most four incoming transfers per device, maximum 64 MiB per file, and 49,152 decoded bytes per chunk. The final result is sent only after Android verifies the bytes and commits the file. Companion 0.1.5 publishes verified files through MediaStore.Downloads into Downloads/Unity, using a pending entry until the copy completes; failed copies delete that entry and return an operation failure. Its success result includes `location:"Downloads/Unity"`. Phone sends hash and transmit a bounded private snapshot of a single URI open, supporting providers with one-shot streams. Names reject separators, dot paths, controls, and oversized names. Neither endpoint executes received files or opens them automatically.

Companion 0.1.6 extends battery events with optional `plugged` boolean, `status` (charging/discharging/full/not_charging/unknown), `voltage_v` (1..10), `current_ma` (-20000..20000, positive into battery), `power_w` (-200..200, positive battery draw/negative charging), `remaining_seconds` (integer 0..2592000), `estimate_kind` (empty/full), `estimate_source` (none/android/charge_counter/battery_trend), and `estimate_state` (available/collecting/unavailable). Numeric sensor fields and remaining_seconds are null when unavailable. A non-null remaining_seconds requires an available state, kind and a non-none source. Older level/charging-only events remain valid. The phone refreshes every 30 seconds while connected. See [battery calculations and limitations](battery-estimates.md).

Explicit LAN discovery: while pairing is open, send `UNITY_DISCOVER_V1` to UDP 17162. Reply supplies version, name, TCP port, and an **untrusted** fingerprint. Replies are rate-limited per source address. The Android UI currently uses manual LAN-address entry; it does not yet consume discovery broadcasts. Bulk data uses the existing LAN and does not create a new network.

Photo previews and media artwork use `{mime:"image/jpeg",width,height,data:"base64"}`. Maximum edge is 320 pixels and decoded JPEG size is 32 KiB; the daemon validates both declared and JPEG-header dimensions before exposing the image. Photo preview requests default to 256 pixels and accept edges 64..320. Preview results are `{id,preview}`. A photo list advertises `preview_supported:true`; an older companion without this flag retains list/original-download support and the frontend shows an update notice instead of downloading full originals for thumbnails. Previews remain in session memory and are cleared on disconnect. Media artwork is taken from local MediaSession bitmap metadata or accessible content URIs; the client does not fetch external artwork URLs.

Android thumbnail API: [ContentResolver.loadThumbnail](https://developer.android.com/reference/android/content/ContentResolver#loadThumbnail(android.net.Uri,%20android.util.Size,%20android.os.CancellationSignal)). The GTK layout follows native [libadwaita adaptive layout](https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/adaptive-layouts.html) behavior and honors the user’s font scaling.

Audio is an optional v1 capability introduced in companion 0.1.3. The daemon adds `token`, `request_id`, `rate:48000`, `channels:2`, `frames:480`, and `format:"s16le"` to its internal audio-start command. The phone opens a separate pinned TLS connection to the same endpoint and sends a normally framed `{"type":"audio_hello","version":1,"device_id":"…","token":"64 hex digits"}`. The daemon requires a pending request on that live control-session generation, consumes the token once, and replies with framed `ready` only after capture produces PCM. Subsequent bytes are fixed binary packets: 8-byte big-endian unsigned sequence, then 1,920 PCM bytes, with no length prefix. The stream carries laptop-to-phone audio only and terminates with the route. Unknown or unrequested audio connections are rejected; older companions never advertise the capability and retain existing control interoperability.

## Desktop requests and screen streams (companion 0.1.4+)

Phones advertising the new `lock` capability receive a desktop `capabilities` message after Ready, and on desktop policy changes. Old companions retain their existing control flow. A paired phone may send `{"type":"request","id":"32 hex digits","feature":"lock","action":"lock","data":{}}` or a screen start/stop request. Only lock and screen actions are accepted; schema, paired-session generation, phone opt-in and desktop per-device policy are checked. Replies use Result. Concurrent requests are bounded. Lock on Device1 sends the same action to Android. Phone capture uses `{"source":"phone"}`. Desktop capture accepts optional boolean `control` (defaults true) and `blank_display` (defaults false); blanking requires control. Public callers cannot supply tokens, codecs, paths or shell commands.

Companion 0.1.13 adds `screen/input` requests with `session_id` equal to the active start operation/request_id and increasing `seq`. Input kinds are pointer (normalized x/y, button, pressed), motion, tap, scroll (dx/dy), text (up to 256 UTF-8 bytes), and key (allowlisted key, modifiers, bounded repeat). `screen/display` takes `{session_id,on:bool}`. Both are restricted to the current authenticated generation and portal-approved desktop share. The daemon sends a `screen/state` command with request_id, input_enabled, display_available, display_on and display_error; no stream token appears in these updates. The phone requests initial blanking only after its visible viewer decodes a frame.

A screen start reserves one share per phone and delivers a random 256-bit token through the authenticated control session, with request_id, source, jpeg format, max_edge=1280, max_pixels=921600, max_bytes=262144 and fps=15. The phone connects using pinned TLS and sends `screen_hello` with version=1, device_id and token. The token expires with the 120-second consent/start window and can be used once, only by its parent paired session. Ready is a normal JSON frame; subsequent sender bytes are u32 big-endian JPEG length plus JPEG. For desktop capture, Ready follows the first real captured frame. The phone sends bytes for source=phone and receives bytes for source=desktop. Invalid JPEG dimensions/lengths close the stream. Control/heartbeat/file traffic stays independent.

Capture frames and stream credentials never appear in Device1 snapshots or broadcast D-Bus signals. Screen1.GetFrame is read-authorized; FrameAvailable carries only sequence metadata. Stop closes the side connection, capture and portal/projection resources. Disable, disconnect, unpairing and parent-session replacement invalidate pending tokens and discard cached frames. Screen status is starting/streaming/stopped/failed with source and any error. Start completes after the first bounded frame is received/sent; permission or capture failure fails it.

## Bluetooth streams

[Bluetooth RFCOMM](bluetooth.md) uses the same TLS handshake, framed control messages and binary audio/screen formats as LAN, with three separate service UUIDs. The control profile accepts Hello; audio and screen profiles accept only their corresponding side-stream Hello and one-use token. The daemon adds `state.transport.kind` (`lan`, `bluetooth`, or `both`), `links` (live transport names), and `control` / `bulk` (the currently selected routes) to connected-device snapshots. Peers cannot overwrite this daemon-owned state. `PairingInfo` and `Diagnostics` add a `bluetooth` object with status, adapters (address/name/powered), service UUID, or an unavailable reason.

Companion 0.1.8 maintains concurrent independently authenticated Bluetooth and LAN sessions. Each heartbeat/Pong and command Result stays on its originating session. Capability/policy changes reach both connections; small events prefer Bluetooth while files, photos, browsing, audio and screens prefer LAN. Operations, transfer chunks and one-use side-stream tokens stay bound to their original session generation and transport. Reconnecting another link does not invalidate them. A lost session fails its work without replay; new work uses the surviving route.

## Telephony (companion 0.1.12)

The optional `call_control` capability now uses ordinary Android dialing/list permissions and Linux oFono HFP live-call controls. A `backend:"bluetooth_hfp"` call event selects that backend without overwriting its live snapshot; older companion events retain the legacy path. Runtime call IDs, call snapshots, bounded contact/history pages, call commands, local HFP audio actions and SMS multipart reports are documented in [telephony](telephony.md). Protocol v1 and old-companion unsupported behavior remain compatible. SMS `list` adds optional offset/limit and next_offset; old peers may omit the cursor.

The optional Android Phone role advertises `backend:"android_telecom"` using the same call-control schema. Its calls and route/mute state are authoritative Android Telecom snapshots; HFP mode retains `backend:"bluetooth_hfp"`. Live native call commands are forwarded over the authenticated companion transport instead of being intercepted by the oFono backend. `connect_time` is milliseconds since Unix epoch (zero before connection); the client ticks locally without polling the phone every second.
