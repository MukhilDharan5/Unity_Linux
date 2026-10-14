#!/usr/bin/env bash
# Configure the Linux HFP backend. Android needs only normal pairing/permissions.
set -euo pipefail
UNITY_CALL_SUDO=(sudo)
if [[ "${1:-}" == --non-interactive ]]; then UNITY_CALL_SUDO+=(--non-interactive); shift; fi
if [[ $# != 0 ]]; then printf 'Usage: %s [--non-interactive]\n' "$0" >&2; exit 2; fi
UNITY_CALL_STATE="${XDG_STATE_HOME:-$HOME/.local/state}/unity/call-audio.json"
if [[ -f "$UNITY_CALL_STATE" ]]; then
    printf 'Stop computer call audio before configuring the Bluetooth backend.\n' >&2
    exit 1
fi
if ! rpm -q ofono >/dev/null 2>&1; then
    "${UNITY_CALL_SUDO[@]}" dnf install --assumeyes ofono
fi
# oFono can exit successfully when its D-Bus connection is lost. Recover
# both clean exits and failures, and keep the standard D-Bus activation path.
"${UNITY_CALL_SUDO[@]}" install -d -m 755 /etc/systemd/system/ofono.service.d /usr/share/dbus-1/system-services
"${UNITY_CALL_SUDO[@]}" install -m 644 "$(dirname -- "$0")/../packaging/systemd/ofono-recovery.conf" /etc/systemd/system/ofono.service.d/50-unity-recovery.conf
"${UNITY_CALL_SUDO[@]}" install -m 644 "$(dirname -- "$0")/../packaging/dbus/org.ofono.service" /usr/share/dbus-1/system-services/org.ofono.service
"${UNITY_CALL_SUDO[@]}" systemctl daemon-reload
"${UNITY_CALL_SUDO[@]}" systemctl reset-failed ofono.service
UNITY_CALL_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/wireplumber/wireplumber.conf.d/70-unity-phone-calls.conf"
UNITY_CALL_CONTENT='# Unity Bluetooth phone call control (oFono HFP; no Android dialer role).
monitor.bluez.properties = {
  bluez5.hfphsp-backend = "ofono"
}
'
if [[ -e "$UNITY_CALL_CONFIG" ]] && [[ "$(cat "$UNITY_CALL_CONFIG")" != "${UNITY_CALL_CONTENT%$'\n'}" ]]; then
    printf 'Existing %s differs; review that configuration before setup.\n' "$UNITY_CALL_CONFIG" >&2
    exit 1
fi
UNITY_CALL_CREATED=false
if [[ ! -e "$UNITY_CALL_CONFIG" ]]; then
    mkdir -p -- "$(dirname -- "$UNITY_CALL_CONFIG")"
    printf '%s' "$UNITY_CALL_CONTENT" > "$UNITY_CALL_CONFIG"
    UNITY_CALL_CREATED=true
fi
UNITY_CALL_RUNNING=false
if systemctl --user is-active --quiet wireplumber.service; then UNITY_CALL_RUNNING=true; fi
restore_audio() {
    local unity_call_result=$?
    trap - EXIT
    if [[ $unity_call_result != 0 ]] && [[ "$UNITY_CALL_CREATED" == true ]]; then rm -- "$UNITY_CALL_CONFIG"; fi
    if [[ "$UNITY_CALL_RUNNING" == true ]]; then systemctl --user start wireplumber.service; fi
    exit "$unity_call_result"
}
trap restore_audio EXIT
# Release the native HFP registration before oFono registers the same role.
if [[ "$UNITY_CALL_RUNNING" == true ]]; then systemctl --user stop wireplumber.service; fi
"${UNITY_CALL_SUDO[@]}" systemctl enable --now ofono.service
if [[ "$UNITY_CALL_RUNNING" == true ]]; then systemctl --user start wireplumber.service; fi
printf 'Bluetooth call backend configured. Reconnect the phone in Bluetooth settings, enable Calls for this computer, and choose it in Unity → Calls.\n'
