# Calls and messages (companion 0.1.12)

By default Unity keeps Android's existing Phone app. An optional, explicitly requested default Phone app integration enables phone speakerphone, earpiece, wired and Bluetooth routing through Android Telecom. The companion uses ordinary phone/contacts/history permissions for dialing and lists; the Linux daemon controls live calls over Bluetooth HFP through oFono. The existing HFP mode needs no ADB access, special ongoing-call permission or default-dialer role. It uses Microsoft Phone Link's calling documentation as a product reference. Unity does not connect to Microsoft's service or use a Phone Link API.

## Set up

1. Update the Linux daemon/client and install `Unity_Android/dist/Unity-Companion-debug.apk` over the previous debug APK to keep pairing.
2. On Android, open **Features → Messages and calls**, enable **Call management**, and select **Set up call management**. Grant the ordinary phone/call-state permissions. Android's usual Phone app handles incoming and active call UI, including when Unity is disconnected. If an earlier Unity version was selected as the Phone app, restore your usual Phone app in Android default-app settings.
3. Grant **Contacts and call history permissions**. Contact search and history have separate permission checks; missing access produces an actionable operation error. Enable **Incoming calls** for legacy phone-state information and **Messages** plus SMS permissions for texting.
4. Configure Linux once with `./scripts/setup-bluetooth-calls.sh` from `Unity_Linux/`. This installs/enables oFono and selects its HFP backend in the current user's WirePlumber configuration. It needs ordinary Linux administrator permission to install/start the system service; it does not touch Android permissions. Setup briefly restarts WirePlumber, so stop computer call audio first. Reconnect the phone in Bluetooth settings and enable **Calls** for this computer.
5. Open Linux **Calls**, select **Choose call phone**, and explicitly choose the paired phone belonging to this Unity connection. The binding is saved privately by Unity device ID. Search a contact, select a recent caller, or enter a number and click **Call**. Incoming alerts offer **Answer/Reject**, even when GTK is closed. Active calls expose **End call**, **Hold/Resume** when supported, **Mute** for the computer microphone once computer audio is active, and a separate in-call **Keypad**. Multiple calls have distinct runtime IDs; stale IDs fail instead of affecting another call. If Android asks which SIM to use, choose it on the phone's call screen.

## Desktop call popup and phone speakerphone

Incoming, outgoing, active and held calls automatically open a compact desktop control window, including when the main app is closed. The installed daemon opens the verified GTK launcher; its normal process-scoped authorization remains in use. GTK explicitly reads the popup command-line option so a closed app opens only the call controls. The alert watcher rescans after missed updates and retrieves the display environment if the daemon started before the desktop session. The popup and Calls page share live elapsed time, answer/decline, hold/resume when supported, end, mute, keypad and an audio menu. The timer starts when answered, continues while held, and stops on call removal. HFP uses the connection time observed by the daemon; Android Telecom supplies its actual connection timestamp. Closing the popup dismisses it for the current calls; **Show call controls** or a notification’s **Call controls** action restores it. New calls reopen it. Disconnect, call end and revoked access close it and remove the timer.

For **Phone speakerphone**, install companion 0.1.12, then open Android Unity **Features → Enable phone speakerphone controls**. Confirm the explanation and choose Unity in Android’s Phone app chooser. This is optional: it changes the default Phone app and gives Unity responsibility for the phone’s incoming/ongoing call screen. The local call screen, incoming notifications and local answer/end/hold/mute/keypad/audio controls remain available without a computer connection or call-sharing opt-in. Restore your previous Phone app under Android **Default apps → Phone app** to return to HFP mode.

With that role enabled, `backend:"android_telecom"` supplies stable call IDs, connection timestamps, mute and available audio routes, and live controls travel over Unity’s authenticated connection. **Phone earpiece**, **Phone speakerphone**, **Phone wired headset** and **Phone Bluetooth headset** use Telecom’s available routes; unavailable options are disabled. API 34+ uses call endpoints; older Android uses call audio routes. **Computer via Bluetooth** targets the saved computer address and starts the existing local output/microphone loopbacks. Switching to a phone route stops computer loopbacks first. Both surfaces offer the same computer output and microphone selectors while computer audio is active. The HFP mode displays an actionable setup hint for phone speakerphone and retains its working phone/computer choices.

Call history is read-only, paginated, and limited to the preceding 90 days. Contacts are paginated by name. Numbers accept ordinary digits, an optional leading `+`, spaces, parentheses and hyphens. Remote USSD/service-code dialing is excluded. Remote emergency calls are rejected using Android's emergency-number detection; use the physical phone. Use Android's usual Phone app for emergency calls and SIM selection.

## Automatic startup, recovery, and popup visibility

The Bluetooth setup script enables oFono at boot, adds its D-Bus activation file, and configures restart after both clean exits and failures. A restart no longer requires selecting the phone again: Unity identifies unpowered HFP modems from their BlueZ path when oFono has not supplied Serial yet, and powers only the saved phone for a connected, enabled Unity call session. Service loss clears stale call IDs and retries automatically.

For an existing installation, the prepared system configuration can be installed with one administrator authentication:

```bash
pkexec /usr/bin/python3 /path/to/Unity_Linux/scripts/setup-ofono-service.py
```

On KDE Plasma, keep the call control window above other windows with:

```bash
python3 ~/.local/libexec/unity/setup-call-popup.py
```

This adds a rule matching Unity's call control window title and application ID. Normal popup dismissal and call-end cleanup still apply.

The popup confirms an empty call state for 2.5 seconds before closing, so brief call-state gaps keep the same window and timer. During that gap it shows “Updating call status” and clears call controls. Returning calls preserve dismissal. Disconnection, access revocation and disabling call control close it immediately. Older property and state replies cannot replace a newer snapshot.

If oFono disappears during a call and the independent phone-state feature still reports `ringing` or `offhook`, Unity keeps the existing popup, timer and desktop notification visible with “Reconnecting Bluetooth call controls”. Controls are disabled until oFono supplies fresh call IDs. Notification updates reuse the existing notification when the caller match is unambiguous. A phone `idle` event clears this recovery display even while oFono is absent; disconnecting or disabling call control also clears it. Recovery never retains authority to execute an old call ID. Computer audio stops during the outage.

## Computer microphone and speaker

Dialing, contacts/history and SMS commands work over authenticated LAN or Unity Bluetooth RFCOMM. Live call state, answer/reject/hangup, hold/resume and DTMF use the selected Bluetooth HFP connection through oFono. Cellular audio requires a **separate Bluetooth HFP connection**; Android does not expose cellular call audio to ordinary PCM capture.

Pair the phone and computer in Bluetooth settings and enable **Calls** for that computer on Android. Save/select the same computer Bluetooth address in Unity's Android Settings. Linux needs BlueZ, PipeWire-Pulse, WirePlumber's oFono HFP backend and `pulseaudio-utils`. A phone must expose an available `audio-gateway` (current PipeWire) or `headset-audio-gateway` (older backends) sound-card profile. oFono describes the local HFP audio card as `Type="handsfree"` because the computer supplies the hands-free role toward the phone. Headphones' `headset-head-unit` profile is not a phone gateway.

During an active call, select **Use computer** in Linux to request the selected phone's HFP audio through oFono. The companion uses public Telecom routing APIs only when the optional Phone role is enabled; HFP mode uses oFono. Use the separate **Call output** and **Call microphone** selectors before starting or during the call. **System default** follows later changes in system sound settings. The refresh button discovers newly connected devices. Unity creates two owned PulseAudio loopbacks: phone HFP source → selected local output, and selected local microphone → phone HFP sink. Unity does not change the system default speaker/microphone. **Use phone** reverses the route. Module IDs, previous card profile and a random ownership tag are journaled privately for recovery; cleanup checks the module's identity before unloading it.

For headphone listening with the laptop microphone, choose the Bluetooth headphones as **Call output** and the laptop input as **Call microphone**. Unity prepares an available A2DP playback profile when the headphones support it. For the headphone microphone, choose the headphones as **Call microphone** too; Unity prepares their hands-free head-unit profile, which can reduce playback quality. Headsets are tracked by Bluetooth address so codec/profile changes can recreate their node names without losing the selection. Only profiles Unity changes are recorded for restoration; an already suitable codec is kept.

While computer audio is active, the daemon checks the audio graph once per second. It rebuilds only changed connections, including changes to system defaults, replaced node IDs, missing loopbacks, and headphone/phone reconnection, while keeping the selected phone address fixed. Explicit selections wait for that device rather than falling back to another microphone or speaker. Phone endpoints and monitor sources are excluded from local selection to prevent feedback. If profile setup creates nodes asynchronously, Unity waits during startup or displays **Waiting for call audio** and retries during an existing route. Either selector remains usable while waiting.

**Mute microphone** disables the local capture loopback, while keeping playback. The muted selection survives reconnection and profile changes, and no capture loopback is created until unmuted. The phone's own mute control remains available. The computer controls mute only the computer microphone; use the normal Phone app to mute audio while talking directly on the phone.

When a call stops, Unity unloads its own streams and restores profiles it changed if the card still has Unity's applied profile. It preserves later profile changes made outside Unity. If a card is disconnected, profile restoration remains in the private journal and retries when that card returns; the call route stays stopped. A crash recovers ownership-tagged loopbacks, including a module loaded immediately before its ID was journaled.

If the phone gateway is absent, enable the HFP hands-free role using the [WirePlumber Bluetooth configuration documentation](https://pipewire.pages.freedesktop.org/wireplumber/daemon/configuration/bluetooth.html), then reconnect the phone. Review this user configuration rather than changing global audio settings automatically:

```ini
# ~/.config/wireplumber/wireplumber.conf.d/51-phone-calls.conf
monitor.bluez.properties = {
  bluez5.roles = [ a2dp_sink a2dp_source bap_sink bap_source hsp_hs hsp_ag hfp_hf hfp_ag ]
  bluez5.hfphsp-backend = "ofono"
}
```

Call end, transfer off Bluetooth, feature revocation, disconnect/unpair and daemon shutdown clean up computer audio. A crash leaves a private recovery journal, processed at the next daemon start. A stopped audio server can defer restoration; restore the server and use `audio_stop` or restart the daemon. A call on the phone is not automatically hung up when Unity disconnects.

## SMS

Messages has a conversation sidebar, per-address replies, a multiline composer and older-message pagination. SMS permission and phone-side opt-in are checked for every operation. Select a default SMS SIM in Android settings before sending. Carrier charges may apply.

A send returns `message_id` and `submitted_to_modem`. Distinct sent and delivery PendingIntents identify each multipart segment. All segments must succeed before `sent` or `delivered` appears. Duplicate or out-of-order reports do not count a segment twice. Failures appear as `failed` or `delivery_failed`; a carrier that never sends a delivery receipt remains unconfirmed. Provider revision events refresh conversations without dropping cached delivery reports. Reports are bounded and session-only, and sending is never automatically replayed across disconnects.

This is SMS, without MMS attachments or RCS. SMS does not require the default SMS role. The default Phone role is requested only through the optional speakerphone setup. Android/OEM/installer/store policy can restrict SMS and call-log access. Unity does not delete phone messages or call history.

## Public API and protocol

`org.unityconnect.Telephony1` retains ListMessages, SendSms, ControlCall and GetSnapshot, and adds ListConversation(address,offset), Dial(number), ListContacts(query,offset), ListRecentCalls(offset), ControlCallById(action,call_id), SendTone(call_id,tone), SetMuted(bool), SetAudioRoute(route), ListAudioDevices, StartDesktopAudio(address), StopDesktopAudio and SelectDesktopAudioDevices(output,microphone). Query methods return owned operation paths like other Unity features. GetSnapshot retains legacy state/number fields and adds call_notifications, call_control, call_audio and sms objects. Read/control grants are enforced before access.

`Device1.Execute` accepts `call_control` actions refresh, dial, answer, reject, hangup, hold, unhold, mute, dtmf, route, contacts, history, audio_devices, audio_start, audio_stop, audio_mute, audio_select, hfp_devices and hfp_select. List commands accept offset (0..100000) and limit (1..100), returning next_offset or null. The daemon handles audio_* and live HFP controls locally. `hfp_devices` lists oFono HFP phones; `hfp_select` takes their `address`, powers up that modem if needed and persists the selected phone. The companion publishes `backend:"bluetooth_hfp"`; its empty refresh snapshot cannot replace live HFP state. Old companions retain their existing call-control path. oFono call object paths receive fresh runtime UUIDs, are revalidated before each action, and are invalidated on call removal or service restart. Multi-call hold/resume is offered only when a single call can be switched independently; unsupported carrier/headset operations report the backend error. `audio_devices` returns `devices` (phone gateways), `outputs`, and `microphones`; local entries have `id`, `name`, and `bluetooth`. `audio_start` accepts `address` and optional `output`/`microphone`, with an empty or omitted local selection following the system default. `audio_select` requires both local selections and changes an existing route without transferring the phone call again. Bluetooth local IDs are `bluetooth:MAC_ADDRESS`; other local IDs are sound-server node names. Local route snapshots include `state` (`active`, `waiting`, or `stopped`), `muted`, `output`, `microphone`, resolved `output_name`/`microphone_name`, and an actionable `error`. `audio_stop` also reports `restoration_pending` when a disconnected card still needs cleanup. The existing `StartDesktopAudio(address)` method remains compatible and starts with system defaults. Calls, contacts and history contain bounded strings; live call IDs are 32 lowercase hexadecimal characters. A call event is `{calls:[{id,state,number,name,connect_time,can_hold,conference}],muted,route,routes}`. A message report event is `{message_id,state,revision}`; a provider change can be `{revision}`. Old companions continue to advertise call_control as unavailable.

Examples (replace DEVICE and CALL_ID):

```sh
unityctl run DEVICE call_control dial '{"number":"+15551234567"}'
unityctl run DEVICE call_control contacts '{"query":"Alex","offset":0}'
unityctl run DEVICE call_control history '{}'
unityctl run DEVICE call_control answer '{"call_id":"CALL_ID"}'
unityctl run DEVICE call_control audio_devices '{}'
unityctl run DEVICE call_control audio_select '{"output":"bluetooth:11:22:33:44:55:66","microphone":"alsa_input.example"}'
unityctl run DEVICE call_control dtmf '{"call_id":"CALL_ID","tone":"#"}'
unityctl run DEVICE sms list '{"address":"+15551234567","offset":80}'
```

## Sources and validation

The optional Phone integration follows [Android InCallService requirements](https://developer.android.com/reference/android/telecom/InCallService) and [call endpoint routing](https://developer.android.com/reference/android/telecom/CallEndpoint). Retrieved 14 October 2026.

Microsoft documents Bluetooth setup, contact/number dialing, recent calls, in-call keypad and mute behavior in [Make and receive phone calls from your PC](https://support.microsoft.com/en-us/windows/apps/make-and-receive-phone-calls-from-your-pc), [Setting up calls](https://support.microsoft.com/en-us/windows/apps/phonelink/setting-up-calls-in-the-phone-link), and [Troubleshooting calls](https://support.microsoft.com/en-us/windows/apps/phonelink/troubleshooting-calls-in-the-phone-link). Implementation uses [Android ordinary call placement](https://developer.android.com/reference/android/telecom/TelecomManager#placeCall(android.net.Uri,%20android.os.Bundle)), [oFono HFP modem interface](https://kernel.googlesource.com/pub/scm/network/ofono/ofono/+/master/doc/modem-api.txt), [oFono call controls](https://kernel.googlesource.com/pub/scm/network/ofono/ofono/+/master/doc/voicecallmanager-api.txt), [SmsManager](https://developer.android.com/reference/android/telephony/SmsManager), and [WirePlumber Bluetooth profiles](https://pipewire.pages.freedesktop.org/wireplumber/daemon/configuration/bluetooth.html). Retrieved 13 October 2026.

Robolectric tests cover opt-in and ordinary permission gating without a dialer role, the absence of privileged call permissions, optional Phone role gating, OS-protected InCallService binding, native speakerphone routing, stale-call rejection and offline local call UI, safe number parsing, call state/capability checks, local optional dial-pad lifecycle, and multipart reports. Rust tests validate schemas and HFP device matching. `tests/telephony_integration.py` tests actual TLS/D-Bus command/result traffic, native alerts, authorization, contacts, tones, report retention, separate audio selectors, independent default changes, A2DP/HFP preparation, delayed nodes, reconnection, mute preservation, feedback rejection, failed-link retry, deferred profile restoration, cancellation, crash recovery and cleanup using an isolated sound-server fixture. `tests/hfp_integration.py` exercises actual TLS/D-Bus with an isolated oFono service: explicit phone binding, signal-driven call state, answering, hangup, hold/resume, DTMF, computer audio/mute, stale IDs, backend failures and feature revocation. `tests/call_popup_ui.py` checks shared timers/actions, speakerphone and Bluetooth routing, synchronized selectors, popup dismissal/reopening and revoked-access cleanup using fixture calls on a virtual desktop. GTK smoke tests render the production Calls page at 1140 and 420 pixels. No physical cellular call, carrier receipt, HFP sound quality, real HFP modem/audio-agent interoperability or OEM behavior has been validated for this version. iPhone support is outside this Android companion implementation.

Additional recovery checks cover oFono disappearance/reappearance, a restarted unpowered modem with no Serial, automatic reconnection without another selection, and preservation of the unselected phone. `tests/call_popup_integration.py` runs the actual staged GTK app through its installed launcher on a virtual KDE desktop, checking cold popup-only startup, actual compositor keepAbove, and call-end closure.

Popup regression checks inject repeated one-second empty/live transitions into the actual staged app and verify the same process and window remain visible. Shared UI tests cover delayed replies, timer preservation, dismissal through a gap, bounded call-end confirmation and immediate consent/disconnection cleanup. Launcher tests verify temporary empty snapshots do not spawn additional clients.

Service recovery tests remove the simulated oFono service for more than three seconds during an ongoing phone call, restore it twice with fresh IDs, and verify uninterrupted popup/notification lifetime, preserved timer, paused controls, resumed actions and old-ID rejection. They also end the call on the phone while oFono is absent and verify cleanup. On the Fedora laptop, oFono 2.19 was observed exiting with “System bus has disconnected” during physical calls while the system broker remained running. The trigger for that external service failure has not yet been reproduced; the recovery fix is tested independently of it. The recovery unit directs standard error to the journal for subsequent diagnosis.
