using System.Text.Json.Nodes;
using Unity.Core;

namespace Unity.Windows;

public sealed partial class MainWindow
{
    private static DeviceView DemoDevice()
    {
        var caps = new JsonArray(); foreach (var feature in UnityHost.PhoneFeatures) caps.Add(Wire.Object(("id", feature), ("status", "available"), ("reason", "Preview with sample data")));
        var state = Wire.Object(("battery", Wire.Object(("level", 76), ("charging", true), ("power_w", -4.2))),
            ("network", Wire.Object(("kind", "wifi"), ("operator", "Home network"), ("signal", 4), ("internet", true))),
            ("sound", Wire.Object(("origin", "phone"), ("available", true), ("volume", 65), ("muted", false), ("name", "Phone media"))),
            ("notifications", Wire.Object(("example", Wire.Object(("id", "example"), ("app_name", "Calendar"), ("title", "An afternoon to explore"), ("text", "Your weekend plans are ready."), ("timestamp", 1792929600000), ("actions", new JsonArray()))))),
            ("media", Wire.Object(("origin", "phone"), ("players", new JsonArray(Wire.Object(("origin", "phone"), ("session", new string('a', 32)), ("title", "Evening Light"), ("artist", "The Coastline"), ("album", "A place to return"),
                ("app_name", "Music"), ("playing", true), ("duration", 241000), ("position", 84000), ("actions", new JsonArray("play", "pause", "next", "previous", "seek"))))))));
        return new(new string('d', 32), "Test phone", ["lan", "bluetooth"], caps, state);
    }
    private static JsonObject DemoMessages() => Wire.Object(("messages", new JsonArray(
        Wire.Object(("id", "1"), ("address", "+1 555 0100"), ("body", "See you this afternoon!"), ("timestamp", 1792929600000), ("direction", "incoming")),
        Wire.Object(("id", "2"), ("address", "+1 555 0101"), ("body", "The photos look great."), ("timestamp", 1792929600000), ("direction", "outgoing")))), ("next_offset", null));
}
