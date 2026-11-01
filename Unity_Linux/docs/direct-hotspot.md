# Bluetooth and a direct phone hotspot

Unity can use the phone's own Wi-Fi hotspot as its LAN connection while keeping Bluetooth connected. A separate router and internet access are not required for Unity's local features. Use companion **0.1.36** and the matching Linux daemon/service update. Existing pairing, certificate pins, feature choices and phone permissions are retained.

## Connect

1. Keep the phone and computer paired in Bluetooth settings. In Unity on Android choose **Settings → Computer connection → Bluetooth + Wi-Fi / LAN**, select the paired computer and save/connect.
2. Turn on the phone's Mobile Hotspot and join it from the laptop's Wi-Fi menu using its normal password. Unity does not need Wi-Fi Direct, a router, a cloud service or a new Unity pairing.
3. The authenticated Bluetooth link shares the laptop's new hotspot address and listening ports. The old saved home/campus address can remain as a fallback.
4. Wait for Unity to show **LAN + Bluetooth connected**. Controls use Bluetooth; files, photos, browsing, audio and screen sharing use the hotspot's Wi-Fi connection. Native calls/HFP and phone-side permissions keep their normal requirements.
5. Use Unity's ordinary controls, Orbit menus or AppIndicator actions. Switching the underlying network does not require choosing a special feature mode. Automatic audio routing prefers Wi-Fi stereo while keeping Bluetooth available.

An Android hotspot's DHCP subnet may change each time it starts. Unity learns that address again over the paired, pinned TLS connection. Android selects the matching local/hotspot network even when cellular data or a different Wi-Fi is the internet default. On older/OEM releases which expose only the hotspot interface, Unity binds the socket to its matching local source address. The control connection and its audio/screen side channels use the same selected route. An active VPN remains respected.

## Moving between a router and the hotspot

The desktop retires a LAN session when its local IP disappears, preventing new work from using a TCP connection left on the old network. Bluetooth remains connected. The phone replaces a LAN connection when its authenticated desktop endpoint is withdrawn, and cancels obsolete connection attempts when the network changes. Other healthy interfaces and Bluetooth sessions remain independent.

A transfer or stream already in progress stays bound to its original authenticated session. If that path disappears, restart the interrupted operation after reconnection; Unity does not move partial files or replay commands on another connection. New operations can use Bluetooth while Wi-Fi reconnects. Bluetooth has less bandwidth, so the combined hotspot mode is recommended for audio, screens and large files.

## Diagnose

From the laptop:

```sh
unityctl diagnose
unityctl status DEVICE_ID
```

`diagnose.network.lan_endpoints` should contain the laptop's current hotspot IPv4 address and listening ports. The device's transport state should report `kind: "both"`, `control: "bluetooth"` and `bulk: "lan"`. Android's **Settings → Network privacy** shows the endpoint or the LAN error.

The Linux user service must allow `AF_NETLINK` in `RestrictAddressFamilies`: `getifaddrs` uses route netlink to enumerate addresses. Without it, the daemon can accept a saved LAN connection but advertise an empty address list, preventing automatic hotspot handover. The installed service now includes that address family. Reload the user manager and restart Unity after updating its unit:

```sh
systemctl --user daemon-reload
systemctl --user restart unityd.service
```

The laptop's firewall must permit an advertised Unity TCP port on the hotspot Wi-Fi connection. Unity listens on 17161 and, for the default bind configuration, one free port in 1714–1764. It does not change firewall policy or hotspot credentials. Keep the phone's hotspot enabled and disable its idle auto-off option if the phone otherwise turns it off during use.

## Validation

Automated tests cover Android local-only hotspot networks without internet, simultaneous upstream Wi-Fi/cellular networks, changing subnets, Android 10/14 source-address fallback, VPN preservation, endpoint prioritization, stale-connection replacement and independent Bluetooth recovery. Linux tests cover advertised-address bounds, physical-interface priority, retiring only the removed LAN address, and simultaneous authenticated LAN/RFCOMM files, audio and screens. Real phone validation is recorded in the testing guide.
