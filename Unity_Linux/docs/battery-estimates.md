# Phone battery power and time estimates

Install companion 0.1.6 or later and update the Linux daemon and GTK client. Overview shows two compact cards in one horizontal row: LAN, Bluetooth or LAN + Bluetooth connection status, and battery percentage with power draw in watts. The cards size to their text. While charging, the power reading shows net charging watts. Time estimates are available through the daemon API. No additional Android permission or root access is needed.

The companion reads Android BatteryManager current and remaining charge properties, together with battery voltage from ACTION_BATTERY_CHANGED. It publishes on battery broadcasts and every 30 seconds while connected and the battery feature is enabled. Sampling stops on disconnect or feature disable. Linux validates the optional readings and exposes them through the existing battery snapshot, Device1 state and `unityctl battery DEVICE`.

Power is calculated as `-current_microamps × voltage_millivolts / 1,000,000,000` watts. Positive transmitted watts indicate battery discharge; negative watts indicate net charging. The UI displays the magnitude with the appropriate label. These readings describe energy flowing into or out of the battery, rather than wall-charger power or total phone consumption while plugged in.

Discharge time uses `remaining_charge_microamp_hours / discharge_current_microamps × 3600` seconds. The current estimate prefers Android’s average reading and smooths valid samples over two minutes, falling back to instantaneous current when necessary. Unsupported properties, invalid units/ranges, inconsistent unplugged current signs, and near-zero current do not produce a guessed runtime. No phone model or fixed battery capacity is assumed.

For charging, Android’s `computeChargeTimeRemaining()` estimate takes priority. If unavailable, Unity estimates missing charge from the reported remaining charge and battery percentage, then divides by recent charging current. This fallback is approximate: charging tapers near full, and phone workload and battery calibration change the result.

When charge/current readings are unavailable, Unity can use a battery percentage trend after at least ten minutes and two percentage points of consistent movement. It keeps at most two hours of level history. Charger/status changes, sampling gaps above two minutes, clock resets and unexpected level reversals discard history. The UI shows “Estimating time remaining…” while collecting a usable trend, and “Time estimate unavailable” for paused or unknown states. It never substitutes a nominal capacity to invent watts.

Telemetry fields are optional, so older companions remain compatible. Unity asks users of an older companion to update for the added readings. Sensor support depends on the phone’s fuel gauge and Android implementation. Estimates represent recent usage, and can change when apps, screen brightness, radios or charging conditions change.

Android API references: [BatteryManager current and charge properties](https://developer.android.com/reference/android/os/BatteryManager), [computeChargeTimeRemaining](https://developer.android.com/reference/android/os/BatteryManager#computeChargeTimeRemaining()).

Validation covers unit conversions, signed power, current smoothing, charging priority/fallback, missing sensors, percentage trends, zero-current/full/paused states, charger and history resets, periodic refresh and disconnect cleanup, D-Bus/TLS snapshot propagation, older peers, invalid payloads, and wide/narrow GTK rendering. Physical fuel-gauge accuracy still requires checking the updated companion on a phone.
