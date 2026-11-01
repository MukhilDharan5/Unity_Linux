# Phone connectivity

Companion **0.1.35** and the matching desktop apps add a **Connectivity** tab. It controls the phone's **Mobile Hotspot** and shows the names, addresses and profiles of Bluetooth devices currently reported connected by Android. The desktop receives these snapshots over Unity's existing authenticated connection; it does not scan for nearby devices or substitute the laptop's own Bluetooth list.

## Setup

1. Update the Android companion and the desktop app together.
2. On Android, enable **Features → Phone connectivity → Phone connectivity**. This sharing starts off because connected-device names and addresses are private device data.
3. Choose **Bluetooth device permission** and allow **Nearby devices**. Hotspot state can still be shared without this Bluetooth permission. Bluetooth-only connection mode also requires that permission.
4. Open the desktop **Connectivity** tab. **Refresh** requests a fresh phone snapshot. Changes are also pushed after access-point, Bluetooth/profile and ACL events; the connected service reconciles state every ten seconds without a radio scan.
5. For remote changes on Samsung, enable **Features → Samsung Routines** and create the notification-keyword routines below. Allow Unity notifications and the **Samsung Routines triggers** category.

## Mobile Hotspot switch

Create two Samsung **If → Notification received** routines for the installed **Unity** or **Unity Preview** app:

| Keyword | Then |
| --- | --- |
| `UNITY_HOTSPOT_ON` | Mobile Hotspot → On |
| `UNITY_HOTSPOT_OFF` | Mobile Hotspot → Off |

Disable action reversal when each routine ends, if Samsung offers it. Samsung performs the configured hotspot action. The switch displays observed access-point state and changes only when the phone sends an updated snapshot. Sending a keyword alone does not change that displayed state.

Hotspot state comes from Android's access-point state broadcast, separate from ordinary Wi-Fi connectivity. Some Android builds do not expose this system broadcast, and transitional/failed states remain unknown. When state is unavailable, the tab keeps explicit **On** and **Off** buttons usable. It never infers that the hotspot is off from the phone's Wi-Fi connection. See the [Android access-point broadcast definition](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android16-qpr2-release/framework/java/android/net/wifi/WifiManager.java).

Turning the hotspot off can interrupt a laptop connection using it. Keep the authenticated Bluetooth link connected if you want to send another request after disabling the hotspot. An observed enabled access point does not confirm internet access or carrier tethering authorization.

## Disconnect a specific Bluetooth device

Each connected-device row has its own switch. Turning it off sends a keyword scoped to that exact Bluetooth address. For example, `11:22:33:44:55:66` uses:

```text
UNITY_BLUETOOTH_DISCONNECT_112233445566
```

On the phone, open **Features → Phone connectivity → Connected Bluetooth devices** to see names, addresses and the exact keywords. Create a Samsung Notification received routine for each device you want to control. In **Then → Disconnect from a Bluetooth device**, select the same device as the address in the keyword. The desktop row tooltip also contains its keyword.

Unity checks that the selected address is still reported connected before posting the trigger. A stale row cannot silently target another device. The row remains connected until the phone reports disconnection; the UI does not claim that a routine exists or ran. Reconnection is left to Android and the device; these switches do not send Bluetooth connect requests. The older generic `UNITY_BLUETOOTH_DISCONNECT` routine remains available separately for compatibility.

## Bluetooth reporting limits and privacy

The companion collects connected A2DP, headset, hearing-aid, HID-device, LE Audio and BLE/GATT profiles, live ACL link broadcasts, and the active Unity Bluetooth transport. It merges repeated reports by address and excludes devices that are merely paired. Android may not expose some existing connection types to ordinary apps, so reports carry `complete:false`; an empty report means no connected devices were reported. The UI identifies denied permission, Bluetooth off, unsupported hardware and partial state.

Disabling Phone connectivity, revoking Bluetooth permission, disconnecting/unpairing the phone, or revoking desktop access clears the desktop device list. Profile proxies, broadcast receivers and reconciliation callbacks are released when watching stops. All state remains on the paired devices using the existing encrypted transports.

## Protocol and CLI

`phone_connectivity.refresh` accepts `{}`. Its result and pushed `phone_connectivity` events contain `hotspot_enabled` and `bluetooth_enabled` (boolean or null), `bluetooth_status`, `complete`, `reason`, and up to 64 devices with bounded `address`, `name`, and `profiles`. The desktop rejects malformed addresses, duplicates, oversized names/profiles and device lists reported without Bluetooth access.

```sh
unityctl run DEVICE_ID phone_connectivity refresh
unityctl run DEVICE_ID samsung_routines hotspot_on
unityctl run DEVICE_ID samsung_routines hotspot_off
unityctl run DEVICE_ID samsung_routines disconnect_bluetooth_device '{"address":"11:22:33:44:55:66"}'
```

Routine results remain `state:"trigger_sent"` and `confirmed:false`. Physical Samsung execution and Windows compilation require device/platform validation; automated checks use isolated radios, phone peers and desktop widgets.
