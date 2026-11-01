# Samsung Modes and Routines

Companion **0.1.35** posts fixed and device-address-scoped notification keywords for Samsung Modes and Routines. Create the routines once on the phone, then use the **Connectivity** tab on Linux or Windows. The phone feature starts off and requires notification permission. Other Android manufacturers advertise it as unsupported; older companions do not advertise it. See [Connectivity setup](connectivity.md) for Mobile Hotspot controls and connected-device reporting.

## Phone setup

1. In Unity on the Samsung phone, open **Features → Samsung Routines → Set up Samsung Routines**.
2. In Samsung **Modes and Routines**, create the routines you need below. For **If → Notification received**, select the installed **Unity** or **Unity Preview** app and enter the matching keyword. For **Then**, choose the corresponding Samsung action.

| Notification keyword | Samsung action |
| --- | --- |
| `UNITY_HOTSPOT_ON` | Mobile Hotspot → On |
| `UNITY_HOTSPOT_OFF` | Mobile Hotspot → Off |
| `UNITY_BLUETOOTH_DISCONNECT` | Disconnect from a Bluetooth device → select the specific paired device |
| `UNITY_BLUETOOTH_DISCONNECT_112233445566` | Disconnect the device whose address is `11:22:33:44:55:66`; replace this example with your device's address without colons |

3. If Samsung offers actions **when the routine ends**, disable automatic reversal. Unity removes the previous trigger when posting the next one, and each trigger expires after 60 seconds. Removing a trigger must not undo the requested setting.
4. Allow Unity notifications and the **Samsung Routines triggers** category using **Routine notification settings** in Unity. Enable the **Samsung Routines** switch.
5. Connect to the matching updated desktop app and use **Send trigger** for the desired action.

The Bluetooth device is selected in the Samsung routine on the phone. Each connected-device switch uses a separate address-scoped keyword and revalidates that the address is still connected. Enable **Features → Phone connectivity**, allow Nearby devices and open **Connected Bluetooth devices** on the phone to see the keywords. The older generic disconnect action still targets the single device selected in its own Samsung routine. No Bluetooth scan is used.

Samsung supplies the privileged actions; Unity only posts notifications. The result means **trigger sent**, with `confirmed:false`; it does not confirm that a routine exists, ran, changed the hotspot, or disconnected the selected device. The ordinary **Phone internet sharing → Request** settings prompt remains available separately.

Turning off a hotspot or disconnecting the computer's Bluetooth device can remove a Unity transport. Use the other connection where available. Unity's existing reconnect behavior still applies if the selected Bluetooth device is the computer.

## Commands

The existing authenticated v1 command path and desktop feature policy apply:

```sh
unityctl run DEVICE_ID samsung_routines hotspot_on
unityctl run DEVICE_ID samsung_routines hotspot_off
unityctl run DEVICE_ID samsung_routines disconnect_bluetooth
unityctl run DEVICE_ID samsung_routines disconnect_bluetooth_device '{"address":"11:22:33:44:55:66"}'
```

Commands take an empty JSON object except `disconnect_bluetooth_device`, which requires exactly one `address`. Responses contain `state:"trigger_sent"`, the exact `keyword`, `confirmed:false`, and an explanation. Unsupported actions and malformed payloads fail. Identical replayed request IDs use Unity's existing cached result across both transports and do not post again; a new request ID creates a fresh notification even for the same action. Connection notifications use a separate channel and identity.

## Validation

Android Robolectric tests exercise fixed/device-scoped keywords, repeated fresh posts, bounded notification lifetime, untouched connection notifications, opt-in and permission revocation, blocked categories, unsupported manufacturers, setup UI, phone radio/profile/ACL reporting and authenticated cross-transport replay rejection. Linux plugin and D-Bus/TLS integration tests cover action validation, remote capability negotiation, command results, observed-state updates and local feature policy; GTK checks cover button/switch dispatch and capability gating. Actual Samsung routine execution requires a physical Samsung phone and has not been validated by these tests.
