using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Text.Json.Nodes;
using Unity.Core;

namespace Unity.Platform;

public sealed class DesktopServices : IAsyncDisposable
{
    private sealed class Route(DeviceSession parent, string id, string feature, string source, bool control) : IDisposable
    {
        public readonly object Gate = new();
        public DeviceSession Parent = parent;
        public string Id = id, Feature = feature, Source = source;
        public bool Control = control;
        public long Sequence = -1;
        public IDesktopDisplay? Display;
        public string DisplayError = "";
        public string? StreamToken;
        public Action<string>? RemoveToken;
        public bool Closed;
        public CancellationTokenSource Life = CancellationTokenSource.CreateLinkedTokenSource(parent.Token);
        public TaskCompletionSource Started = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public void Dispose()
        {
            lock (Gate)
            {
                if (Closed) return; Closed = true;
                Life.Cancel(); Started.TrySetCanceled();
                if (StreamToken != null) RemoveToken?.Invoke(StreamToken);
                Display?.Dispose(); Display = null;
            }
        }
    }
    private readonly UnityHost host;
    public DesktopMedia Media { get; } = new();
    public BluetoothServer Bluetooth { get; }
    private readonly ConcurrentDictionary<string, Route> routes = [];
    private readonly object streamGate = new();
    private readonly CancellationTokenSource life = new();
    private readonly Task publishTask;
    public Func<string, string, Task<bool>> ConfirmShare { get; set; } = (_, _) => Task.FromResult(false);
    public event Action<string, byte[]>? ScreenFrame;
    public Func<byte[]>? CaptureFrame { get; set; }
    public Func<IDesktopDisplay> DisplayFactory { get; set; } = () => new DisplayBlanking();
    public Func<Stream, Action, CancellationToken, Task> StreamAudio { get; set; } = AudioStream.RunAsync;
    public DesktopServices(UnityHost host)
    {
        this.host = host; Bluetooth = new(host);
        host.RequestHandler = RequestAsync; host.DesktopCapabilities = Capabilities;
        host.Changed += PolicyChanged;
        host.OperationResult += OperationResult;
        publishTask = Task.Run(PublishAsync);
    }
    private void OperationResult(DeviceSession session, string id, bool ok, string error)
    {
        if (ok) return;
        foreach (var (key, route) in routes.Where(x => x.Value.Parent == session && x.Value.Id == id))
        {
            if (routes.TryRemove(new KeyValuePair<string, Route>(key, route)))
            {
                route.Started.TrySetException(new IOException(error)); route.Dispose();
                host.PublishLocalState(session.DeviceId, route.Feature, Wire.Object(("state", "failed"), ("source", route.Source), ("error", error)));
            }
        }
    }
    private void PolicyChanged()
    {
        foreach (var (key, route) in routes)
            if (!route.Parent.Connected || !route.Parent.Allows(route.Feature) || host.Store.Find(route.Parent.DeviceId)?.Disabled.Contains(route.Feature) != false)
                if (routes.TryRemove(new KeyValuePair<string, Route>(key, route))) route.Dispose();
    }
    private JsonArray Capabilities(string id)
    {
        var phone = host.Store.Find(id); var result = new JsonArray();
        foreach (var feature in new[] { "lock", "screen", "desktop_media", "sound" })
            result.Add(Wire.Object(("id", feature), ("status", phone?.Disabled.Contains(feature) != false ? "disabled" : "available"),
                ("reason", feature == "screen" ? "Approve each Windows screen share in Unity. Share the primary display; blank local displays after opening the phone viewer." : "")));
        return result;
    }
    private async Task PublishAsync()
    {
        var sent = new Dictionary<string, string>();
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(2));
        try
        {
            while (await timer.WaitForNextTickAsync(life.Token))
            {
                var sessions = host.Sessions;
                if (sessions.Length == 0) { sent.Clear(); continue; }
                JsonObject? players = null;
                try { players = await Media.PlayersAsync(); } catch (Exception e) { host.Report("Windows media: " + e.Message); }
                var sound = Media.Volume();
                foreach (var s in sessions)
                {
                    try
                    {
                        foreach (var (feature, value) in new[] { ("desktop_media", players), ("sound", sound) })
                        {
                            if (value == null || !s.Allows(feature)) continue;
                            var key = s.Generation + feature; var wire = value.ToJsonString();
                            if (sent.GetValueOrDefault(key) == wire) continue;
                            await s.SendEventAsync(feature, value); sent[key] = wire;
                            host.PublishLocalState(s.DeviceId, feature == "sound" ? "desktop_sound" : "desktop_media", value);
                        }
                    }
                    catch (Exception e) { host.Report(e.Message); }
                }
                foreach (var k in sent.Keys.Where(k => !sessions.Any(s => k.StartsWith(s.Generation))).ToArray()) sent.Remove(k);
            }
        }
        catch (OperationCanceledException) { }
    }
    private async Task<JsonObject> RequestAsync(DeviceSession session, string id, string feature, string action, JsonObject data)
    {
        switch (feature)
        {
            case "lock": Wire.Require(action == "lock" && NativeDesktop.LockWorkStation(), "Windows could not lock the workstation"); return Wire.Object(("state", "locked"));
            case "desktop_media": return await Media.ControlAsync(action, data);
            case "sound":
                Wire.Require(data.Str("source") == "desktop", "Desktop requests can only control desktop volume");
                var sound = Media.Volume(action, data); await session.SendEventAsync("sound", sound); return Wire.Object(("sound", sound));
            case "screen":
                if (action == "start")
                {
                    Wire.Require(data.Str("source") == "desktop", "Phone requests may only share the Windows screen");
                    return await StartAsync(session, "screen", "desktop", data.Bool("control", true), id, data.Bool("blank_display"));
                }
                if (action == "stop") return await StopAsync(session.DeviceId, "screen");
                var route = routes.GetValueOrDefault(session.DeviceId + ":screen");
                Wire.Require(route != null && route.Parent == session && route.Source == "desktop" && route.Control && route.Started.Task.IsCompletedSuccessfully && data.Str("session_id") == route.Id, "No matching approved screen-control session");
                if (action == "input")
                {
                    lock (route!.Gate)
                    {
                        Wire.Require(!route.Closed, "Screen sharing ended");
                        var seq = data.Num("seq", -1); Wire.Require(seq >= 0 && seq > route.Sequence, "Stale screen input");
                        route.Sequence = seq; NativeDesktop.InputEvent(data);
                    }
                    return Wire.Object(("state", "performed"));
                }
                Wire.Require(action == "display", "Unsupported screen action");
                return await SetDisplayAsync(route!.Parent.DeviceId, data.Bool("on", true), session, data.Str("session_id"));
            default: throw new NotSupportedException("Unsupported desktop feature");
        }
    }
    public async Task<JsonObject> StartAsync(DeviceSession session, string feature, string source = "desktop", bool control = true, string? requestId = null, bool blankDisplay = false)
    {
        Wire.Require(feature is "screen" or "audio" && (feature != "screen" || source is "phone" or "desktop"), "Invalid stream");
        Wire.Require(session.Allows(feature), "Enable this feature on both devices");
        Wire.Require(!blankDisplay || (feature == "screen" && source == "desktop" && control), "Display-off sharing requires mouse and keyboard control");
        var key = session.DeviceId + ":" + feature;
        Wire.Require(!routes.ContainsKey(key), "Stop the active stream first");
        if (feature == "screen" && source == "desktop")
            Wire.Require(await ConfirmShare(session.Name, control ? "Share the primary Windows display and allow this phone to use the mouse and keyboard?" : "Share the primary Windows display with this phone?"), "Windows screen sharing declined");
        Wire.Require(session.Connected && session.Allows(feature), "Stream connection or permission changed");
        var id = requestId ?? Wire.Id(); var route = new Route(session, id, feature, source, control) { RemoveToken = host.RemoveStream };
        lock (streamGate)
        {
            Wire.Require(!life.IsCancellationRequested, "Unity is shutting down");
            Wire.Require(feature != "screen" || source != "desktop" || !routes.Values.Any(r => r.Feature == "screen" && r.Source == "desktop"), "Stop the current Windows screen share before sharing with another phone");
            Wire.Require(routes.TryAdd(key, route), "A stream is already starting");
        }
        string? token = null;
        try
        {
            token = host.ReserveStream(session, feature == "audio" ? "audio_hello" : "screen_hello", async (stream, ct) =>
            {
                using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, route.Life.Token);
                using var close = linked.Token.Register(() => stream.Dispose());
                try
                {
                    void Started()
                    {
                        if (!route.Started.TrySetResult()) return;
                        if (feature == "screen") session.CompleteStreamStart(id, Wire.Object(("state", "streaming"), ("source", source)));
                        host.PublishLocalState(session.DeviceId, feature, Wire.Object(("state", "streaming"), ("source", source)));
                    }
                    if (feature == "audio") await StreamAudio(stream, Started, linked.Token);
                    else await ScreenAsync(route, stream, Started, linked.Token);
                }
                catch (Exception e)
                {
                    if (!linked.IsCancellationRequested)
                    {
                        route.Started.TrySetException(e);
                        host.PublishLocalState(session.DeviceId, feature, Wire.Object(("state", "failed"), ("source", source), ("error", e.Message)));
                        _ = StopPhoneAsync(route, e.Message);
                    }
                }
                finally
                {
                    routes.TryRemove(new KeyValuePair<string, Route>(key, route)); route.Dispose();
                    if (feature == "screen" && source == "desktop") NativeDesktop.ReleaseButtons();
                }
            });
            lock (route.Gate)
            {
                if (route.Closed) { host.RemoveStream(token); throw new IOException("Stream connection or permission changed"); }
                route.StreamToken = token;
            }
            host.PublishLocalState(session.DeviceId, feature, Wire.Object(("state", "starting"), ("source", source)));
            JsonObject data = feature == "audio" ? Wire.Object(("token", token), ("request_id", id), ("rate", 48000), ("channels", 2), ("frames", 480), ("format", "s16le")) :
                Wire.Object(("token", token), ("request_id", id), ("source", source), ("control", source == "desktop" && control), ("blank_display", blankDisplay), ("format", "jpeg"), ("max_edge", 1280), ("max_pixels", 921600), ("max_bytes", 262144), ("fps", 15));
            var command = session.CommandAsync(feature, "start", data, id, TimeSpan.FromSeconds(120), route.Life.Token);
            // ScreenSharing.kt returns null on start. Only a real first JPEG completes it.
            await Task.WhenAll(command, route.Started.Task.WaitAsync(TimeSpan.FromSeconds(120), route.Life.Token));
            if (feature == "screen" && source == "desktop") await SendScreenStateAsync(route);
            return Wire.Object(("state", "streaming"), ("source", source));
        }
        catch
        {
            routes.TryRemove(new KeyValuePair<string, Route>(key, route)); route.Dispose(); if (token != null) host.RemoveStream(token);
            if (host.Devices().FirstOrDefault(d => d.Id == session.DeviceId)?.State[feature].Str("state") != "failed")
                host.PublishLocalState(session.DeviceId, feature, Wire.Object(("state", "stopped"), ("source", source)));
            await StopPhoneAsync(route, ""); throw;
        }
    }
    private static async Task StopPhoneAsync(Route route, string error)
    {
        try { await route.Parent.CommandAsync(route.Feature, "stop", Wire.Object(("error", error.Length > 512 ? error[..512] : error)), timeout: TimeSpan.FromSeconds(3)); }
        catch { /* The original connection may already be gone. */ }
    }
    private JsonObject ScreenState(Route route)
    {
        lock (route.Gate) return Wire.Object(("state", "streaming"), ("source", route.Source), ("request_id", route.Id),
            ("input_enabled", route.Control), ("display_available", !route.Closed && route.Control && route.Display != null),
            ("display_on", route.Display?.IsOn ?? true), ("display_mode", route.Display?.Mode ?? "unavailable"), ("display_error", route.DisplayError));
    }
    private async Task SendScreenStateAsync(Route route)
    {
        JsonObject state;
        lock (route.Gate)
        {
            Wire.Require(!route.Closed, "Screen sharing ended");
            state = ScreenState(route); host.PublishLocalState(route.Parent.DeviceId, "screen", state);
        }
        await route.Parent.CommandAsync("screen", "state", state, ct: route.Life.Token);
    }
    public async Task<JsonObject> SetDisplayAsync(string deviceId, bool on, DeviceSession? parent = null, string? sessionId = null)
    {
        var route = routes.GetValueOrDefault(deviceId + ":screen");
        Wire.Require(route != null && route.Source == "desktop" && route.Control && route.Started.Task.IsCompletedSuccessfully &&
            (parent == null || (route.Parent == parent && route.Id == sessionId)), "No matching approved screen-control session");
        try
        {
            await Task.Run(() =>
            {
                lock (route!.Gate)
                {
                    Wire.Require(!route.Closed && route.Parent.Connected && route.Parent.Allows("screen"), "Screen sharing ended");
                    Wire.Require(route.Display != null, route.DisplayError.Length > 0 ? route.DisplayError : "Display power control is unavailable");
                    route.Display!.Set(on); route.DisplayError = "";
                }
            });
        }
        catch (Exception e)
        {
            lock (route!.Gate) route.DisplayError = e.Message;
            if (!route.Closed) await SendScreenStateAsync(route);
            throw;
        }
        await SendScreenStateAsync(route!); return ScreenState(route!);
    }
    public async Task<JsonObject> StopAsync(string deviceId, string feature)
    {
        if (routes.TryRemove(deviceId + ":" + feature, out var route))
        {
            route.Dispose();
            if (feature == "screen") NativeDesktop.ReleaseButtons();
            await StopPhoneAsync(route, "");
        }
        host.PublishLocalState(deviceId, feature, Wire.Object(("state", "stopped")));
        return Wire.Object(("state", "stopped"));
    }
    private async Task ScreenAsync(Route route, Stream stream, Action started, CancellationToken ct)
    {
        using var capture = route.Source == "desktop" && CaptureFrame == null ? new DesktopCapture() : null;
        Task<byte[]> Next() => capture?.NextAsync(ct) ?? Task.FromResult(CaptureFrame!());
        byte[]? initial = route.Source == "desktop" ? await Next() : null;
        if (route.Source == "desktop" && route.Control)
            lock (route.Gate)
            {
                Wire.Require(!route.Closed, "Screen sharing ended");
                try
                {
                    route.Display = DisplayFactory();
                    route.Display.Restored = () => _ = DisplayRestoredAsync(route);
                }
                catch (Exception e) { route.DisplayError = e.Message; }
            }
        await Wire.WriteAsync(stream, Wire.Object(("type", "ready")), ct);
        var header = new byte[4];
        if (route.Source == "desktop")
        {
            using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(67));
            do
            {
                var bytes = initial ?? await Next(); initial = null;
                Wire.Jpeg(bytes, 1280, 921600, 262144);
                BinaryPrimitives.WriteInt32BigEndian(header, bytes.Length);
                using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct); deadline.CancelAfter(TimeSpan.FromSeconds(2));
                await stream.WriteAsync(header, deadline.Token); await stream.WriteAsync(bytes, deadline.Token); await stream.FlushAsync(deadline.Token); started();
            } while (await timer.WaitForNextTickAsync(ct));
        }
        else
        {
            while (!ct.IsCancellationRequested)
            {
                using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct); deadline.CancelAfter(TimeSpan.FromSeconds(15));
                await stream.ReadExactlyAsync(header, deadline.Token); var size = BinaryPrimitives.ReadUInt32BigEndian(header);
                Wire.Require(size is >= 4 and <= 262144, "Invalid screen frame length"); var bytes = new byte[(int)size];
                await stream.ReadExactlyAsync(bytes, deadline.Token); Wire.Jpeg(bytes, 1280, 921600, 262144);
                ScreenFrame?.Invoke(route.Parent.DeviceId, bytes); started();
            }
        }
    }
    private async Task DisplayRestoredAsync(Route route)
    {
        try { if (!route.Closed) await SendScreenStateAsync(route); }
        catch (Exception e) { if (!route.Closed) host.Report("Display state: " + e.Message); }
    }
    public async ValueTask DisposeAsync()
    {
        host.Changed -= PolicyChanged; host.OperationResult -= OperationResult; life.Cancel();
        foreach (var route in routes.Values) route.Dispose(); routes.Clear(); NativeDesktop.ReleaseButtons();
        Bluetooth.Dispose(); await publishTask; Media.Dispose(); life.Dispose();
    }
}
