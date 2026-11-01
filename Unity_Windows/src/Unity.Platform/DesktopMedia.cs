using System.Text.Json.Nodes;
using Unity.Core;
using Windows.Media.Control;
using NAudio.CoreAudioApi;

namespace Unity.Platform;

public sealed class DesktopMedia : IDisposable
{
    private GlobalSystemMediaTransportControlsSessionManager? manager;
    private readonly Dictionary<string, GlobalSystemMediaTransportControlsSession> sessions = [];
    private readonly Dictionary<string, string> tracks = [];
    private readonly SemaphoreSlim gate = new(1);
    private readonly MMDeviceEnumerator enumerator = new();
    private string? outputDevice;
    private string outputToken = Wire.Id();
    public async Task<JsonObject> PlayersAsync()
    {
        await gate.WaitAsync();
        try
        {
            manager ??= await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var live = manager.GetSessions().Take(16).ToArray();
            foreach (var old in sessions.Where(x => !live.Contains(x.Value)).Select(x => x.Key).ToArray())
            { sessions.Remove(old); foreach (var key in tracks.Keys.Where(k => k.StartsWith(old + ":")).ToArray()) tracks.Remove(key); }
            var players = new JsonArray();
            foreach (var s in live)
            {
                var id = sessions.FirstOrDefault(x => x.Value.Equals(s)).Key;
                if (id == null) { id = Wire.Id(); sessions[id] = s; }
                var props = await s.TryGetMediaPropertiesAsync(); var info = s.GetPlaybackInfo(); var timeline = s.GetTimelineProperties();
                var signature = props.Title + "\n" + props.Artist + "\n" + props.AlbumTitle;
                var trackKey = id + ":" + signature;
                if (!tracks.TryGetValue(trackKey, out var track)) { foreach (var k in tracks.Keys.Where(x => x.StartsWith(id + ":")).ToArray()) tracks.Remove(k); tracks[trackKey] = track = Wire.Id(); }
                var actions = new JsonArray(); var controls = info.Controls;
                if (controls.IsPlayEnabled) actions.Add("play"); if (controls.IsPauseEnabled) actions.Add("pause");
                if (controls.IsNextEnabled) actions.Add("next"); if (controls.IsPreviousEnabled) actions.Add("previous");
                if (controls.IsPlaybackPositionEnabled) actions.Add("seek");
                static string Bound(string text) { while (System.Text.Encoding.UTF8.GetByteCount(text) > 512) text = text[..^1]; return text; }
                var app = Bound(s.SourceAppUserModelId);
                players.Add(Wire.Object(("origin", "desktop"), ("session", id), ("track_id", track), ("title", Bound(props.Title)), ("artist", Bound(props.Artist)),
                    ("album", Bound(props.AlbumTitle)), ("app_name", app), ("package", app), ("playing", info.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing),
                    ("active", true), ("duration", Math.Clamp((long)timeline.EndTime.TotalMilliseconds, 0, 86400000)), ("position", Math.Clamp((long)timeline.Position.TotalMilliseconds, 0, 86400000)),
                    ("actions", actions), ("artwork", null)));
            }
            return Wire.Object(("origin", "desktop"), ("players", players));
        }
        finally { gate.Release(); }
    }
    public async Task<JsonObject> ControlAsync(string action, JsonObject data)
    {
        if (action == "refresh") return await PlayersAsync();
        await gate.WaitAsync();
        try
        {
            Wire.Require(sessions.TryGetValue(data.Str("session"), out var s) && manager!.GetSessions().Contains(s), "The selected Windows player ended; refresh players");
            bool ok;
            switch (action)
            {
                case "play": ok = await s!.TryPlayAsync(); break;
                case "pause": ok = await s!.TryPauseAsync(); break;
                case "next": ok = await s!.TrySkipNextAsync(); break;
                case "previous": ok = await s!.TrySkipPreviousAsync(); break;
                case "seek":
                    var props = await s!.TryGetMediaPropertiesAsync();
                    var signature = props.Title + "\n" + props.Artist + "\n" + props.AlbumTitle;
                    Wire.Require(tracks.GetValueOrDefault(data.Str("session") + ":" + signature) == data.Str("track_id") && data.Str("track_id").Length > 0, "The Windows track changed; refresh before seeking");
                    var pos = data.Num("position", -1); Wire.Require(pos is >= 0 and <= 86400000 && pos <= s.GetTimelineProperties().EndTime.TotalMilliseconds, "Invalid playback position");
                    ok = await s.TryChangePlaybackPositionAsync(TimeSpan.FromMilliseconds(pos).Ticks); break;
                default: throw new NotSupportedException("Unsupported media control");
            }
            Wire.Require(ok, "The Windows media app rejected this control"); return Wire.Object(("state", "submitted_to_desktop_player"));
        }
        finally { gate.Release(); }
    }
    private readonly object volumeGate = new();
    public JsonObject Volume(string action = "refresh", JsonObject? data = null)
    {
        lock (volumeGate)
        {
            try
            {
                using var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                if (outputDevice != device.ID) { outputDevice = device.ID; outputToken = Wire.Id(); }
                var endpoint = device.AudioEndpointVolume;
                if (action != "refresh")
                {
                    Wire.Require(data != null && data.Str("source") == "desktop" && data.Str("output") == outputToken, "Windows output changed; refresh volume");
                    if (action == "volume") { var n = data!.Num("volume", -1); Wire.Require(n is >= 0 and <= 100, "Invalid volume"); endpoint.MasterVolumeLevelScalar = n / 100f; }
                    else if (action == "mute") endpoint.Mute = data!.Bool("muted");
                    else throw new NotSupportedException("Unsupported volume control");
                }
                return Wire.Object(("origin", "desktop"), ("available", true), ("output", outputToken), ("name", device.FriendlyName),
                    ("volume", (int)Math.Round(endpoint.MasterVolumeLevelScalar * 100)), ("muted", endpoint.Mute), ("error", ""));
            }
            catch (Exception e) when (action == "refresh") { return Wire.Object(("origin", "desktop"), ("available", false), ("volume", 0), ("muted", false), ("name", "Windows output"), ("error", e.Message[..Math.Min(512, e.Message.Length)])); }
        }
    }
    public void Dispose() { enumerator.Dispose(); }
}
