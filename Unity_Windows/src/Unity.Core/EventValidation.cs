using System.Text.Json.Nodes;

namespace Unity.Core;

public static class EventValidation
{
    public static void Validate(string feature, JsonObject data)
    {
        Wire.Text(data.ToJsonString(), Wire.MaxText);
        switch (feature)
        {
            case "phone_connectivity":
                foreach (var key in new[] { "hotspot_enabled", "bluetooth_enabled" })
                    Wire.Require(data.ContainsKey(key) && (data[key] is null || data[key] is JsonValue radio && radio.TryGetValue<bool>(out _)), "Invalid phone radio state");
                Wire.Require(data["complete"] is JsonValue complete && complete.TryGetValue<bool>(out _), "Missing Bluetooth coverage flag");
                Wire.Require(data.Str("bluetooth_status") is "available" or "partial" or "off" or "permission_required" or "unsupported" or "unavailable" or "disabled", "Invalid Bluetooth status");
                Wire.Text(data.Str("reason"), 512);
                Wire.Require(data["devices"] is JsonArray, "Missing phone Bluetooth devices");
                var devices = (JsonArray)data["devices"]!;
                Wire.Require(devices.Count <= 64 && (devices.Count == 0 || data.Bool("bluetooth_enabled") && data.Str("bluetooth_status") is "available" or "partial"), "Invalid connected Bluetooth devices");
                var addresses = new HashSet<string>();
                foreach (var device in devices)
                {
                    var address = device.Str("address");
                    Wire.Require(address.Length == 17 && address == address.ToUpperInvariant() && address.Where((_, index) => index % 3 == 2).All(c => c == ':') && address.Where((_, index) => index % 3 != 2).All(Uri.IsHexDigit) && addresses.Add(address), "Invalid or duplicate Bluetooth address");
                    Wire.Text(device.Str("name"), 256);
                    Wire.Require(device?["profiles"] is JsonArray profiles && profiles.Count <= 8 && profiles.All(p => p?.GetValue<string>() is "ACL" or "Audio" or "Calls" or "Hearing aid" or "HID" or "LE Audio" or "BLE" or "Unity"), "Invalid Bluetooth profiles");
                }
                break;
            case "battery":
                Wire.Require(data.Num("level", -1) is >= 0 and <= 100 && data["charging"] is JsonValue, "Invalid battery"); _ = data.Bool("charging");
                foreach (var (field, min, max) in new (string, double, double)[] { ("voltage_v", 1, 10), ("current_ma", -20000, 20000), ("power_w", -200, 200), ("remaining_seconds", 0, 2592000) })
                    if (data[field] is { } value) { var number = value.GetValue<double>(); Wire.Require(double.IsFinite(number) && number >= min && number <= max, "Invalid battery telemetry"); }
                break;
            case "network":
                Wire.Require(data.Str("kind") is "wifi" or "cellular" or "ethernet" or "none" or "other", "Invalid network kind");
                if (data["signal"] != null) Wire.Require(data.Num("signal") is >= 0 and <= 4, "Invalid network signal");
                Wire.Text(data.Str("operator"), 512); _ = data.Bool("internet"); break;
            case "urls": Wire.Require(Wire.SafeUrl(data.Str("url")), "Invalid web address"); break;
            case "clipboard": Wire.Text(data.Str("text"), Wire.MaxText - 256); break;
            case "find": Wire.Require(data.Str("state") is "ringing" or "stopped", "Invalid find state"); break;
            case "sound":
                Wire.Require(data.Str("origin") == "phone" && data.Num("volume", -1) is >= 0 and <= 100, "Invalid phone sound");
                _ = data.Bool("available"); _ = data.Bool("muted"); Wire.Text(data.Str("name"), 512); Wire.Text(data.Str("error"), 512); break;
            case "media":
                Wire.Require(data.Str("origin", "phone") == "phone", "Media origin must be phone");
                if (data["players"] is JsonArray players)
                {
                    Wire.Require(players.Count <= 16, "Too many media players"); var ids = new HashSet<string>();
                    foreach (var p in players)
                    {
                        Wire.Require(p != null && p.Str("origin") == "phone" && Wire.IsHex(p.Str("session"), 32) && ids.Add(p.Str("session")), "Invalid player session");
                        foreach (var key in new[] { "title", "artist", "album", "app_name", "package" }) Wire.Text(p.Str(key), 512);
                        Wire.Require(p.Num("position") is >= 0 and <= 86400000 && p.Num("duration") is >= 0 and <= 86400000, "Invalid player time");
                        if (p!["artwork"] is JsonObject art) Wire.Preview(art);
                    }
                }
                break;
            case "notifications":
                Wire.Text(data.Str("id"), 512); Wire.Require(data.Str("id").Length > 0, "Missing notification ID");
                foreach (var key in new[] { "title", "text", "app", "app_name" }) Wire.Text(data.Str(key), key == "text" ? 16384 : 1024);
                Wire.Require(data["actions"] is not JsonArray actions || actions.Count <= 16, "Too many notification actions"); break;
            case "call_control":
                if (data["calls"] is JsonArray calls) { Wire.Require(calls.Count <= 32, "Too many calls"); foreach (var c in calls) Wire.Require(c != null && Wire.IsHex(c.Str("id"), 32), "Invalid call ID"); }
                break;
        }
    }
}
