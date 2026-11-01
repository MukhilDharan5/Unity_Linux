# Unity wire protocol 1

Transport framing is an unsigned 32-bit network-order byte length followed by one UTF-8 JSON object. Reject zero length, frames above 524,288 bytes, invalid JSON, unknown session message types, invalid identities, and unsupported protocol versions. TLS early data is disabled. Command JSON and operation results are bounded to 65,536 bytes. Phone names are bounded to 128 bytes. IDs are 32 lowercase hexadecimal characters.

Handshake:

1. Android verifies the explicitly entered SHA-256 fingerprint of the Linux certificate during TLS 1.3 establishment.
2. Phone sends `{"type":"hello","version":1,"device_id":"...","name":"...","capabilities":[...]}`.
3. Unknown phones require an open discovery window. Server replies `pair_pending`; a local authorized client must approve within 90 seconds.
4. Server persists a random 32-byte secret and replies `{"type":"pair_result","secret":"64 hex digits"}`. Phone stores it protected by Android Keystore and reconnects.
5. Trusted phones receive `{"type":"challenge","nonce":"64 hex digits"}` and send `{"type":"proof","mac":"64 hex digits"}`.
6. HMAC-SHA256 key is the decoded secret. Message bytes are UTF-8 `unity-v1:DEVICE_ID:NONCE`. Successful validation returns `{"type":"ready"}`.

Never treat an address update as proof of identity. The fingerprint must be compared out of band. Linux's certificate is the desktop identity; each random credential binds the approved phone's identity. No unencrypted application data or password exchange is used.

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
| clipboard | set `{text, automatic?: true}` | automatic text synchronization; received text is applied immediately; automatic events include `automatic: true` to suppress receipt alerts |
| files | client sends path; internal begin/chunk/end/abort | streamed verified transfer |
| photos | list; preview `{id,edge}`; get `{id}` | items with opaque selection IDs, name/size/timestamp/dimensions; preview_supported; bounded JPEG preview; original file transfer |
| urls | open `{url}` | URL event; phone review prompt |
| browsing | list `{id}` (empty means granted root); get `{id}` | entries with opaque provider tokens |
| media | play; pause; next; previous; seek `{position}`; refresh; optional `{session}` targets an exact phone player | Legacy primary title/artist/playing plus optional `origin:"phone"`, bounded `players` with session IDs, album/app_name/duration/position/package/active/actions/artwork |
| desktop_media | Paired phone Request or local Execute: play; pause; next; previous `{session}`; seek `{session,track_id,position}`; refresh | Laptop → phone Event only: `{origin:"desktop",players:[...]}`; never reflected to phone media or MPRIS |
| media_artwork | Negotiation capability; no commands | When available on both peers, enables high-quality artwork events under `media` / `desktop_media` as described below |
| audio | start `{source?:"desktop"/"phone",transport?:"auto"/"lan"/"bluetooth"}`; stop `{}` | Laptop-owned starting/streaming/stopped/failed state; separate authenticated binary PCM stream, see [audio routing](audio-routing.md) |
| ringer | set `{mode}` | normal, vibrate, silent, dnd, off |
| find | ring; stop | `{state:"ringing"\|"stopped"}` events and action results; ringing stops after 30 seconds |
| remote_input | text `{text}`; tap `{x,y}`; swipe `{x,y,end_x,end_y}` | performed/cancelled result; normalized coordinates |
| screen | start `{source: "phone" or "desktop", control?: bool, blank_display?: bool}`; stop `{}`; input/display scoped by `session_id` | dedicated stream, status only in snapshots |
| lock | lock `{}` | native lock acknowledgement |
| hotspot | request_settings | user action required; never confirms active tethering |
| samsung_routines | hotspot_on, hotspot_off, disconnect_bluetooth, disconnect_bluetooth_device | empty payload except device disconnect, which requires exactly an address; fixed/address-scoped phone routine keyword; trigger_sent and confirmed:false, no execution confirmation |
| phone_connectivity | refresh | empty payload; observed phone hotspot/Bluetooth booleans or null, bluetooth_status, complete, reason, bounded connected devices; see [Connectivity](connectivity.md) |

Transfers begin with `{id,phase:"begin",name,size,sha256}`, then `{id,phase:"chunk",data:"base64"}`, then `{id,phase:"end"}`. Phone-to-Linux transfers use `event` messages, including `{id,phase:"abort"}` to remove an interrupted partial file without closing the session. Linux-to-phone transfers use `command` messages with action begin/chunk/end/abort and one operation ID. At most four incoming transfers per device, maximum 64 MiB per file, and 49,152 decoded bytes per chunk. The final result is sent only after Android verifies the bytes and commits the file. Companion 0.1.5 publishes verified files through MediaStore.Downloads into Downloads/Unity, using a pending entry until the copy completes; failed copies delete that entry and return an operation failure. Its success result includes `location:"Downloads/Unity"`. Phone sends hash and transmit a bounded private snapshot of a single URI open, supporting providers with one-shot streams. Names reject separators, dot paths, controls, and oversized names. Neither endpoint executes received files or opens them automatically.

Companion 0.1.6 extends battery events with optional `plugged` boolean, `status` (charging/discharging/full/not_charging/unknown), `voltage_v` (1..10), `current_ma` (-20000..20000, positive into battery), `power_w` (-200..200, positive battery draw/negative charging), `remaining_seconds` (integer 0..2592000), `estimate_kind` (empty/full), `estimate_source` (none/android/charge_counter/battery_trend), and `estimate_state` (available/collecting/unavailable). Numeric sensor fields and remaining_seconds are null when unavailable. A non-null remaining_seconds requires an available state, kind and a non-none source. Older level/charging-only events remain valid. The phone refreshes every 30 seconds while connected. See [battery calculations and limitations](battery-estimates.md).

Since companion 0.1.25, plaintext UDP discovery is removed. Hello can opt in with `"network_info":true` (absent defaults false for legacy clients). After TLS 1.3, device proof and Ready, the server sends `{"type":"event","feature":"connectivity","data":{"version":1,"origin":"desktop","encrypted":true,"tls":"TLSv1.3","lan_endpoints":[{"host":"100.64.32.8","port":1764}],"ports":[17161,1764],"discovery":"authenticated_link_only"}}`. Subsequent address changes are sent over authenticated sessions, including Bluetooth. Endpoint lists are bounded to 16 numeric unicast addresses/ports; names from network updates never trigger DNS lookups. The verified certificate pin and device proof remain mandatory on every candidate endpoint. See [public Wi-Fi](public-wifi.md) for listeners, routing and privacy prompts. Bulk data uses the existing LAN and does not create a new network.

Photo previews and media artwork use `{mime:"image/jpeg",width,height,data:"base64"}`. Photo previews have a maximum edge of 320 pixels and decoded JPEG size of 32 KiB; the daemon validates both declared and JPEG-header dimensions before exposing the image. Photo preview requests default to 256 pixels and accept edges 64..320. Preview results are `{id,preview}`. A photo list advertises `preview_supported:true`; an older companion without this flag retains list/original-download support and the frontend shows an update notice instead of downloading full originals for thumbnails. Previews remain in session memory and are cleared on disconnect. Media artwork is loaded from native-player original URIs or bitmaps under the bounds described below. Paired clients exchange image bytes and never fetch a URL supplied by incoming media messages.

Android thumbnail API: [ContentResolver.loadThumbnail](https://developer.android.com/reference/android/content/ContentResolver#loadThumbnail(android.net.Uri,%20android.util.Size,%20android.os.CancellationSignal)). The GTK layout follows native [libadwaita adaptive layout](https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/adaptive-layouts.html) behavior and honors the user’s font scaling.

Audio is an optional v1 capability introduced in companion 0.1.3 and extended in 0.1.22. Start defaults to `source:"desktop",transport:"auto"`; explicit transport choices require that live link, while automatic prefers LAN. The daemon adds a one-use `token`, `request_id`, actual `transport`, `source`, `format:"s16le"`, and one of two fixed profiles: LAN `rate:48000,channels:2,frames:480`, or Bluetooth `rate:16000,channels:1,frames:160`. Update both apps before using phone capture or the smaller Bluetooth profile. The phone opens a separate pinned TLS connection to the selected endpoint and sends framed `{"type":"audio_hello","version":1,"device_id":"…","token":"64 hex digits"}`. The token is consumed once and requires that live control-session generation and transport. For desktop capture the daemon sends `ready` after its first capture packet; for phone capture it sends `ready` once its playback process opens, and completes the operation only after receiving and writing a full PCM packet. After `ready`, packets are 8-byte big-endian unsigned increasing sequence numbers plus exactly `frames × channels × 2` PCM bytes. There is no length prefix. Phone capture requires fresh local Android capture consent; the token expires with its pending request (120 seconds for approval, 15 seconds for desktop playback). Stream state includes `source` and actual `transport`. Internal stop commands carry `request_id` to avoid stopping a newer stream. See [audio routing](audio-routing.md).

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

See [two-device media and loop protections](media-sync.md) for native discovery, explicit controls, session IDs and replay handling.

The `sound` capability shares device volume separately from players. Events identify native `origin:phone` or `origin:desktop`, with `available`, integer `volume` 0..100, boolean `muted`, `name`, `error`, and an opaque current laptop `output` ID. Commands: `volume {source,volume}`, `mute {source,muted}`, `refresh {source}`; laptop mutations require `output`. Phone desktop Requests only target `source:desktop`. Source events never dispatch setters. See [device-volume behavior](device-volume.md).

Screen main-display handoff (companion 0.1.23): the existing `blank_display:true` option requests a temporary virtual monitor from the portal, then makes it primary and disables the physical laptop output after the phone decodes a visible frame. Screen state adds boolean `phone_primary`; `display_available` stays false until the virtual output is negotiated and its restoration guard is ready. Normal captures never dim the backlight or claim a main-display handoff. The internal capture helper receives `--phone-display` for this mode. The version-1 screen wire format is unchanged. See [display restoration](screen-and-lock.md).

Extended screen display (companion 0.1.24): `screen/start` accepts optional boolean `extend_display`. It is only valid for `source:"desktop"` and is mutually exclusive with `blank_display:true`. Unlike primary handoff, it also supports `control:false`. The daemon adds the requested flag to the internal start command and passes `--extended-display` to its capture helper, which creates a separate virtual monitor while retaining existing outputs and the primary monitor. Confirmed screen state adds boolean `extended_display`; `display_on` stays true, `phone_primary` false, and `display_available` false to block panel-off controls in this mode. Pixel framing, tokens and parent-session binding remain unchanged.

## PAM biometric approval (companion 0.1.26)

A phone advertising `authentication` as `available` may receive a bounded `auth_request` with `id` and a structured `challenge`. It returns `auth_response` with that same `id` and a DER ECDSA signature encoded as lowercase hex; an empty signature declines. Requests/replies belong to the original authenticated device/session generation and never become ordinary remotely invocable commands. A null challenge withdraws a pending request. Only a root Unix peer can initiate the Linux relay, and the separate PAM module validates the exact challenge and root-enrolled public key. See [authentication setup and trust boundary](phone-authentication.md).

## High-quality media artwork (companion 0.1.28)

`media_artwork:available` negotiates an additive protocol-v1 extension. Small player snapshots include an optional lowercase SHA-256 `artwork_id` and a legacy inline preview. The sender may independently send an Event on the originating feature (`media` for phone, `desktop_media` for laptop): `{kind:"artwork",origin,session,track_id,artwork_id,artwork:{mime:"image/jpeg",width,height,data}}`. Images are at most 1024 pixels per edge and 196,608 decoded JPEG bytes. The ID must equal SHA-256 of those JPEG bytes. Declared and encoded dimensions must agree before a client decodes the cover. Every event remains below the existing 512 KiB frame limit.

Cover events prefer the bulk transport and are deduplicated per player/track/content and link generation. Receivers keep at most 32 pending/full covers, tolerating independently ordered transport delivery, and apply a cover only to matching artwork references. Normal timeline refreshes retain full cached artwork. Existing pairing, media opt-in and permission checks still apply. Older peers receive only the existing bounded inline previews. Android’s remote MediaSessions are excluded from phone-native media discovery and never reflected back as phone playback.

Desktop integration in 0.1.31 does not add wire actions. Phone-native `media` sessions are exported as remote desktop MPRIS players; `desktop_media` remains laptop-native and excludes these exports. Native phone seeks may include the current `track_id`, which the Android handler checks before seeking. Local `photos/get` results add a daemon-owned `received_path` only after a hash-verified, matching `transfer_id`; GTK uses that path for asynchronous original-file dragging.
