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
| battery | refresh | level 0..100; charging boolean |
| network | refresh | kind; signal null or 0..4; operator; internet |
| connectivity | status via Device | authenticated LAN capability |
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
| ringer | set `{mode}` | normal, vibrate, silent, dnd, off |
| find | ring; stop | action result; ringing stops after 30 seconds |
| remote_input | text `{text}`; tap `{x,y}`; swipe `{x,y,end_x,end_y}` | performed/cancelled result; normalized coordinates |
| screen | start; stop defined but unsupported | no fake frames |
| hotspot | request_settings | user action required; never confirms active tethering |

Transfers begin with `{id,phase:"begin",name,size,sha256}`, then `{id,phase:"chunk",data:"base64"}`, then `{id,phase:"end"}`. Phone-to-Linux transfers use `event` messages. Linux-to-phone transfers use `command` messages with action begin/chunk/end and one operation ID. At most four incoming transfers per device, maximum 64 MiB per file, and 49,152 decoded bytes per chunk. The final result is sent only after Android verifies the bytes and commits the file. Names reject separators, dot paths, controls, and oversized names. Neither endpoint executes received files or opens them automatically.

Explicit LAN discovery: while pairing is open, send `UNITY_DISCOVER_V1` to UDP 17162. Reply supplies version, name, TCP port, and an **untrusted** fingerprint. Replies are rate-limited per source address. The Android UI currently uses manual LAN-address entry; it does not yet consume discovery broadcasts. Bulk data uses the existing LAN and does not create a new network.

Photo previews and media artwork use `{mime:"image/jpeg",width,height,data:"base64"}`. Maximum edge is 320 pixels and decoded JPEG size is 32 KiB; the daemon validates both declared and JPEG-header dimensions before exposing the image. Photo preview requests default to 256 pixels and accept edges 64..320. Preview results are `{id,preview}`. A photo list advertises `preview_supported:true`; an older companion without this flag retains list/original-download support and the frontend shows an update notice instead of downloading full originals for thumbnails. Previews remain in session memory and are cleared on disconnect. Media artwork is taken from local MediaSession bitmap metadata or accessible content URIs; the client does not fetch external artwork URLs.

Android thumbnail API: [ContentResolver.loadThumbnail](https://developer.android.com/reference/android/content/ContentResolver#loadThumbnail(android.net.Uri,%20android.util.Size,%20android.os.CancellationSignal)). The GTK layout follows native [libadwaita adaptive layout](https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/adaptive-layouts.html) behavior and honors the user’s font scaling.
