# Phone fingerprint for sudo and laptop login

Companion Preview **0.1.29** fixes request-specific notification opening for the opt-in native PAM authenticator introduced in 0.1.26. Authentication uses the existing encrypted Wi-Fi and Bluetooth connections. Each attempt needs a fresh phone biometric; being connected or having an unlocked phone does not approve a request.

## One-time setup

1. Install `Unity_Android/dist/Unity-Preview-0.1.29-debug.apk`, connect to the paired laptop, and allow Unity notifications so requests can be reviewed.
2. In the phone's **Features**, enable **Laptop fingerprint approval**, then open **Set up fingerprint approval**. Choose **Create and export credential** and authenticate with your phone fingerprint. Save `Unity-fingerprint-credential.json`, then send that file to the laptop. The screen shows the complete credential fingerprint; it is selectable for copying.
3. On Linux, review the setup plan. Replace the credential path with the exported file:

   ```sh
   python3 Unity_Linux/scripts/setup-phone-auth.py --user "$USER" \
     --enrollment "$HOME/Downloads/Unity-fingerprint-credential.json"
   ```

4. Run the same setup with administrator authorization. The helper asks you to enter the full credential fingerprint shown on the **phone** and then runs an isolated `unity-pam-test` request. Tap the Unity notification and approve it with your fingerprint. Existing sudo/login rules are changed only after this proof succeeds:

   ```sh
   sudo python3 Unity_Linux/scripts/setup-phone-auth.py --user "$USER" \
     --enrollment "$HOME/Downloads/Unity-fingerprint-credential.json" --apply
   ```

   Use `--services sudo,sudo-i` to enable only sudo. The default selects existing `sudo`, `sudo-i`, `login`, `gdm-password` and `polkit-1` services. It edits these explicit service files, preserving their password, account and session rules, without modifying authselect's generated `system-auth` or `password-auth` templates. Customized/symlinked service files require manual review; the helper refuses to overwrite them. It backs up all changed files under `/var/lib/unity-pam/backups/` and restores applied PAM changes if setup fails.

   The installed helper is also available at `~/.local/libexec/unity/setup-phone-auth.py`, alongside the built PAM module. For a custom daemon state location, pass `--desktop-state PATH`. The phone export must match that desktop's TLS identity.

## Everyday use

The updated native PAM module shows the usual **Password:** prompt immediately, together with a hint that an empty Enter selects phone fingerprint. Sudo may display its standard `[sudo] password for …:` wording instead for normal sudo/login services. Type your laptop password normally; libpam keeps its hidden-input token and the unchanged system password stack verifies it, without a phone request or a second password prompt. To choose the extra phone option, leave the field empty and press Enter. Only then does Unity send a phone notification naming the computer, account and service. Tap it to open that exact request directly, review the account/service, then choose **Approve with fingerprint**. You can also reopen a pending request from **Features → Review fingerprint request**. The request notification remains available while approval is pending; expired requests and biometric failures display an explanation instead of silently returning to the main app. The Android biometric prompt performs the signing operation. **Use laptop password**, cancellation, invalid credentials, connection loss or timeout return control to the existing PAM stack with a fresh password prompt. An empty selection is cleared before phone authentication and cannot count as a blank password. Preexisting password tokens are left alone, and silent/noninteractive attempts do not start phone approval. The explicit `gdm-fingerprint` service and isolated enrollment proof retain their dedicated fingerprint flow. The normal account authorization and sudoers rules still apply. Sudo's normal timestamp cache can suppress authentication; a manual `sudo -k` followed by `sudo true` forces a fresh test.

For an existing enrollment, update only the root-owned module after building Linux:

```sh
sudo python3 Unity_Linux/scripts/setup-phone-auth.py --update-module --apply
```

This backs up and atomically replaces `/usr/local/lib/security/pam_unity.so`, retaining all enrollment, password/account/session rules and feature settings. It does not require exporting or enrolling the phone again. The operating system requires administrator authorization to replace the native module. New authentication attempts use the update immediately; no daemon or phone restart is needed.

Setup starts/enables the existing Unity user service. When login services are selected, it also enables systemd user lingering so Unity starts before desktop login. This requires the user's home and a Wi-Fi or paired Bluetooth route to be available before login. A locked encrypted home, unavailable network, stopped phone app or phone awaiting its first unlock after reboot leaves the laptop password available. This does not unlock disk encryption or the GNOME keyring; those may still need their passwords. Keep a working password and an existing terminal available while testing initial login integration.

Android's platform prompt accepts enrolled **Class 3 strong biometrics**, which includes fingerprint and, on supported phones, strong face authentication. Phone PIN/pattern/password fallback and weak face recognition cannot authorize this key. The private key must be protected by secure hardware, with hardware-enforced biometric authentication on every signature. New biometric enrollment invalidates the key. Use **Reset fingerprint credential**, export a new credential and repeat administrator enrollment if the phone's biometrics change. Resetting/removing the phone alone never modifies Linux's root-owned enrollment.

Disable **Laptop fingerprint approval** on the phone to stop approvals immediately. Remove the Linux enrollment with:

```sh
sudo python3 Unity_Linux/scripts/setup-phone-auth.py --user "$USER" --remove --apply
```

The helper removes the PAM rules when no enrolled accounts remain. User-service linger is left unchanged on deliberate removal because other user services can depend on it; manage that separately with `loginctl` if needed. Setup does not disable SELinux or install broad policy exceptions. On an enforcing system, test each selected login service and review any denial using that system's normal policy tools.

## Trust boundary and checks

The root-owned native module (`/usr/local/lib/security/pam_unity.so`) creates a 256-bit random challenge containing the computer ID, TLS identity, account/UID, PAM service, terminal, requesting user, phone ID and expiry. It accepts only an ECDSA P-256/SHA-256 signature that matches `/etc/unity/pam/users/UID.json`. The root-owned enrollment directory/file ownership and permissions are checked, symlink enrollment files are rejected, root/nonregular accounts and remote PAM hosts are excluded, and only explicitly enrolled services are accepted.

The unprivileged Unity daemon relays requests through a mode-0600 Unix socket. Only a root Unix peer can request a relay. PAM checks the broker's UID, bounds frame sizes and total wait time, and independently verifies the signature. A user-daemon reply saying “approved” has no authority. The phone binds each prompt/reply to the original paired connection, discards late or duplicate requests, and withdraws approval when that link or PAM request ends. No laptop password or fingerprint image/template is transmitted. A malicious local relay can suppress or misdescribe a prompt, so approve only operations you initiated and verify the displayed account and service; it cannot replace the root-enrolled key or forge a valid fresh signature.

Android notification/lifecycle tests cover direct entry with Unity already open, saved-state restoration, warm-activity delivery, duplicate taps, stale notification identity, timeout/service loss, visible biometric errors, the in-app pending-request fallback, and a full CryptoObject signature from the notification-opened review. Automated tests also cover cross-language ECDSA transcripts, tampered context/nonce/key/expiry rejection, absolute read deadlines, original-session response ownership, PAM cancellation and link loss, Android CryptoObject signatures, missing-crypto rejection, enrollment/reset policy, signed-export validation and native PAM fallback with private test configurations. Real phone hardware, the administrator enrollment step and actual GDM/console login still require physical validation. The build does not claim these have been exercised by unit tests.

Platform references: [Android biometric authentication](https://developer.android.com/identity/sign-in/biometric-auth), [per-operation CryptoObject authentication](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.CryptoObject), [systemd user lingering](https://www.freedesktop.org/software/systemd/man/latest/loginctl.html), [PAM/authselect profiles](https://github.com/authselect/authselect/blob/master/src/man/authselect-profiles.5.adoc).

Notification routing follows [Android notification activity guidance](https://developer.android.com/develop/ui/views/notifications/navigation) and [immutable PendingIntent identity](https://developer.android.com/reference/android/app/PendingIntent). The phone update retains the existing enrollment and wire protocol.

`tests/pam_password_prompt.py` uses actual libpam and the native module in private user/mount namespaces. Fixture password verification checks hidden input, single-prompt token forwarding, incorrect-password rejection, fresh password fallback after an empty choice, preserved existing tokens and silent mode. The host PAM files, password database, enrollment and runtime socket are not modified. Token ownership/forwarding follows [Linux-PAM’s pam_get_authtok implementation](https://github.com/linux-pam/linux-pam/blob/master/libpam/pam_get_authtok.c); the normal password module uses [the same token API](https://github.com/linux-pam/linux-pam/blob/master/modules/pam_unix/pam_unix_auth.c).
