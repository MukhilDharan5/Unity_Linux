# Public D-Bus API v1

Bus: **session bus**. Name: `org.unityconnect`. Root: `/org/unityconnect/Manager`. API interfaces end in `1`; compatible additions preserve these interfaces, breaking changes require a new numbered interface. The daemon publishes standard `Introspectable`, `Peer`, and `Properties` interfaces through zbus. Generated introspection XML is in `docs/api/`.

Device paths: `/org/unityconnect/devices/d_<32-hex-ID>`. Operation paths: `/org/unityconnect/operations/o_<32-hex-ID>`. Returned device enumeration uses D-Bus object paths; feature methods currently return operation paths as strings. Operations are owned by the connection that created them. Keep that connection alive while waiting for the result.

Phone contents are accessible only to granted clients. Request access with `RequestAccess("My client", ["read", "control"])`. Show the returned unique bus name to the user. An operator uses the trusted `unityctl authorize :1.N` executable to approve it. A grant applies only to that connection. A read grant exposes private snapshots and results; control permits pairing, revocation, feature toggles, and remote commands. Request only the scopes needed. `GetAccessStatus()` reports only the calling connection's request/approval/scopes, without exposing other clients. Listen for `Changed` and recheck approval before accessing private state. An identical/subset request preserves approval; a new label or added scope needs approval again.

The installed GTK launcher is the trusted CLI's `launch-gtk` command. It starts only the installed GTK script and approves requests only when the bus-reported UID/PID match its live child. Third-party clients cannot obtain approval by using the same client label or Python interpreter. The launcher subscribes to `AccessRequested` before spawning; daemon owner changes let the live child request access again. Closing the child exits the launcher without affecting the daemon. Direct Python launches use a user-confirmed CLI approval in the app. Linux cannot isolate fully malicious unsandboxed processes running as the same user; see the security model.

## Manager1

| Member | Inputs | Result / behavior |
| --- | --- | --- |
| ApiVersion property | — | u32 = 1 |
| ListDevices | — | array of object paths; redacted public metadata |
| StartDiscovery | u32 seconds | open explicit pairing window, 1..120 |
| PairingInfo | — | JSON: fingerprint, port, pending id/name pairs; requires control |
| ApprovePairing | string device_id, bool approve | approve/reject pending phone |
| Unpair | string device_id | revoke credential and close live session |
| RequestAccess | string name, array<string> scopes | requesting unique bus name |
| GetAccessStatus | — | JSON `{requested,approved,scopes}` for caller only; no grant required |
| ListAccessRequests | — | JSON; trusted CLI only |
| ApproveAccess | string bus_name, bool approve | trusted CLI only |
| Diagnostics | — | JSON without phone contents; requires read |
| ListOperations | — | JSON operations owned by caller; requires read |
| Changed signal | string object_path | resnapshot changed device/operation; no private payload |
| AccessRequested signal | string client | new/repeated access request; unique bus name only, no private payload |

## Device1

Public properties: `Id` and `Name` strings, `Connected` boolean, `CapabilitiesJson` JSON array of `{id,status,reason}`. Property invalidation is emitted through `PropertiesChanged`. `StateChanged()` signals changed private state without disclosing its contents.

`GetState()` → JSON `{id,name,connected,capabilities,state,disabled,last_error}` requires read and a live session. Cached state is cleared on disconnect; disconnected reads return an error. `Execute(feature,action,payload_json)` → operation path requires control. This stable dispatch method also covers ringer, find, and hotspot commands. `Disconnect()` cancels the current connection (phone auto-reconnect may establish another). `SetFeatureEnabled(feature,bool)` persists local feature policy. Device traffic cannot bypass disabled features.

## Feature interfaces on each device

| Interface | Methods |
| --- | --- |
| Notification1 | GetSnapshot; Dismiss(id); Reply(id,action,text); Invoke(id,action) |
| Clipboard1 | GetText (JSON `{text}` or null); SetText(text) |
| Transfer1 | SendFile(absolute_local_path); GetSnapshot |
| Media1 | Control(action,u64 position_ms); GetSnapshot |
| Telephony1 | ListMessages; SendSms(address,body); ControlCall(action, currently Unsupported); GetSnapshot |
| RemoteInput1 | Text(text); Gesture(action,double x,y,end_x,end_y) |
| Sharing1 | ShareUrl(url); ListPhotos; GetPhotoPreview(id,u32 edge); ListFiles(id); GetFile(feature,id), feature photos or browsing |

All interfaces have prefix `org.unityconnect.`. Getter snapshots require read; mutations require control. Commands return an operation path. Availability is determined by capabilities and Android permission state, not by the mere presence of an interface in introspection. `completed` indicates the reported operation result; always inspect `result.state`. A review prompt may complete with `user_action_required`, and SMS submission does not indicate delivery.

## Operation1

`GetState()` → JSON `{id,device,feature,action,status,progress,result,error}`. Status is `running`, `completed`, `failed`, or `cancelled`; progress is 0..100. `Changed()` signals new state. Subscribe before the first snapshot to avoid a missed completion. `Cancel()` cancels local waiting/streaming. Commands already sent to the phone may have taken effect; cancellation cannot undo them. Only the creating D-Bus connection may read/cancel an operation, even when another client also has read/control grants.

Errors use `org.unityconnect.Error.NotAuthorized`, `InvalidArgument`, `Disconnected`, `Unsupported`, or `Failed`. Parameter decoding also uses standard D-Bus errors. Avoid matching English error messages; use the error name. Unexpected wire errors close the affected phone session, fail its active operations, and clear live state.

## Example third-party client

```python
from gi.repository import Gio, GLib
bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
def call(path, interface, method, signature='()', args=()):
    return bus.call_sync('org.unityconnect', path, interface, method,
        GLib.Variant(signature, args), None, Gio.DBusCallFlags.NONE, 10000, None).unpack()
client_name = call('/org/unityconnect/Manager', 'org.unityconnect.Manager1',
                   'RequestAccess', '(sas)', ('My widget', ['read']))[0]
print('Ask the user to run: unityctl authorize', client_name)
# Keep this process/connection alive; continue after user approval.
paths = call('/org/unityconnect/Manager', 'org.unityconnect.Manager1', 'ListDevices')[0]
# GetState on a chosen path after approval; subscribe to StateChanged for updates.
```

This synchronous example is appropriate for a small terminal script. GUI clients should use asynchronous D-Bus calls as Unity GTK does. No phone transport library, GTK dependency, or internal core crate is needed to build a KDE widget, GNOME extension, or terminal client.

`Sharing1.GetPhotoPreview(id,edge)` returns an owned operation path. Its completed result contains `{id,preview:{mime,width,height,data}}` with a bounded base64 JPEG. It requires the same read/control grant and photo capability as original retrieval. All artwork and preview bytes travel through the authorized API; GTK never opens a phone connection. `Device1.Execute("sms","list",{"address":"..."})` retrieves a selected conversation using a provider-bound query. `sms.revision` events invalidate the open Messages view without polling. Media snapshots optionally include album, app_name, duration, active, supported actions, and artwork; clients must tolerate absent fields from an older companion.
