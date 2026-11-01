using System.Collections.Concurrent;
using System.Text.Json.Nodes;

namespace Unity.Core;

public sealed class DeviceSession : IDisposable
{
    private readonly Stream stream;
    private readonly SemaphoreSlim writer = new(1);
    private readonly ConcurrentDictionary<string, TaskCompletionSource<JsonObject>> pending = [];
    private readonly ConcurrentDictionary<string, (string Fingerprint, Task<JsonObject> Reply)> requests = [];
    private readonly CancellationTokenSource life;
    private readonly UnityHost host;
    private int disposed;
    private JsonArray capabilities;
    public string DeviceId { get; }
    public string Name { get; }
    public string Kind { get; }
    public string Generation { get; } = Wire.Id();
    public CancellationToken Token => life.Token;
    public bool Connected => !life.IsCancellationRequested;
    public JsonArray Capabilities => (JsonArray)capabilities.DeepClone();
    public TransferReceiver Files { get; }
    internal DeviceSession(UnityHost host, Stream stream, JsonObject hello, string kind, CancellationToken token)
    {
        this.host = host; this.stream = stream; Kind = kind;
        DeviceId = hello.Str("device_id"); Name = hello.Str("name");
        capabilities = Wire.Capabilities(hello["capabilities"]);
        life = CancellationTokenSource.CreateLinkedTokenSource(token);
        Files = new(Path.Combine(host.Store.Root, "Received"));
        Files.Received += path => host.OnReceived(DeviceId, "files", Wire.Object(("path", path), ("name", Path.GetFileName(path))));
    }
    public bool Allows(string feature, bool userAction = false) => host.Store.Find(DeviceId) is { } phone && !phone.Disabled.Contains(feature) &&
        capabilities.Any(c => c.Str("id") == feature && (c.Str("status") == "available" || (userAction && c.Str("status") == "user_action_required")));
    public async Task SendAsync(JsonObject message, CancellationToken ct = default)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(Token, ct);
        linked.CancelAfter(TimeSpan.FromSeconds(15));
        await writer.WaitAsync(linked.Token);
        try { await Wire.WriteAsync(stream, message, linked.Token); }
        catch { Dispose(); throw; }
        finally { writer.Release(); }
    }
    public Task SendEventAsync(string feature, JsonObject data) => SendAsync(Wire.Object(("type", "event"), ("feature", feature), ("data", data)));
    public async Task<JsonObject> CommandAsync(string feature, string action, JsonObject? data = null, string? id = null, TimeSpan? timeout = null, CancellationToken ct = default)
    {
        Wire.Require(Allows(feature, true), $"Enable {feature.Replace('_', ' ')} and its permissions on your phone and in Unity Settings");
        data ??= new(); Wire.Text(data.ToJsonString(), Wire.MaxText - 256);
        id ??= Wire.Id();
        var source = Register(id);
        try
        {
            await SendAsync(Wire.Object(("type", "command"), ("id", id), ("feature", feature), ("action", action), ("data", data)), ct);
            return await source.Task.WaitAsync(timeout ?? TimeSpan.FromSeconds(30), ct);
        }
        finally { pending.TryRemove(id, out _); }
    }
    internal async Task<JsonObject> FileCommandAsync(string id, Func<Func<string, JsonObject, Task>, Task> transfer, CancellationToken ct)
    {
        Wire.Require(Allows("files"), "Enable file sharing on your phone");
        var source = Register(id);
        try
        {
            await transfer((action, data) => {
                if (source.Task.IsCompleted) return Task.FromException(new IOException("Phone rejected the transfer"));
                return SendAsync(Wire.Object(("type", "command"), ("id", id), ("feature", "files"), ("action", action), ("data", data)), ct);
            });
            return await source.Task.WaitAsync(TimeSpan.FromSeconds(120), ct);
        }
        finally { pending.TryRemove(id, out _); }
    }
    private TaskCompletionSource<JsonObject> Register(string id)
    {
        Wire.Require(Connected && pending.Count < 64 && Wire.IsHex(id, 32), "Disconnected or too many operations");
        var source = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        Wire.Require(pending.TryAdd(id, source), "Duplicate operation ID"); return source;
    }
    public void CompleteStreamStart(string id, JsonObject data)
    {
        if (pending.TryRemove(id, out var completion)) completion.TrySetResult(data);
    }
    internal async Task RunAsync()
    {
        var heartbeat = HeartbeatAsync();
        try
        {
            await SendAsync(Wire.Object(("type", "capabilities"), ("capabilities", host.DesktopCapabilities(DeviceId))));
            host.NotifyChanged();
            while (!Token.IsCancellationRequested)
            {
                using var read = CancellationTokenSource.CreateLinkedTokenSource(Token); read.CancelAfter(TimeSpan.FromSeconds(90));
                var message = await Wire.ReadAsync(stream, read.Token);
                switch (message.Str("type"))
                {
                    case "ping": await SendAsync(Wire.Object(("type", "pong"))); break;
                    case "pong": break;
                    case "capabilities":
                        capabilities = Wire.Capabilities(message["capabilities"]);
                        host.PruneState(this); host.NotifyChanged(); break;
                    case "event":
                        var feature = message.Str("feature");
                        Wire.Require(UnityHost.PhoneFeatures.Contains(feature) && Allows(feature, true), "Unnegotiated event feature");
                        var data = message.Data();
                        if (feature == "files") Files.Receive(data);
                        else { EventValidation.Validate(feature, data); host.UpdateState(this, feature, data); }
                        break;
                    case "result":
                        var id = message.Str("id"); Wire.Require(Wire.IsHex(id, 32), "Invalid result ID");
                        Wire.Text(message.ToJsonString(), Wire.MaxText); Wire.Text(message.Str("error"), 512);
                        host.OnOperationResult(this, id, message.Bool("ok"), message.Str("error"));
                        if (pending.TryRemove(id, out var completion))
                        {
                            if (message.Bool("ok")) completion.TrySetResult((JsonObject)message.Data().DeepClone());
                            else completion.TrySetException(new IOException(message.Str("error", "Phone operation failed")));
                        }
                        break;
                    case "request": DispatchRequest(message); break;
                    default: throw new InvalidDataException("Unexpected session message");
                }
            }
        }
        finally { Dispose(); await heartbeat; }
    }
    private void DispatchRequest(JsonObject message)
    {
        var id = message.Str("id"); var feature = message.Str("feature");
        Wire.Require(Wire.IsHex(id, 32) && feature is "lock" or "screen" or "desktop_media" or "sound", "Invalid desktop request");
        Wire.Text(message.ToJsonString(), Wire.MaxText);
        var fingerprint = message.ToJsonString();
        if (requests.TryGetValue(id, out var previous))
        {
            Wire.Require(previous.Fingerprint == fingerprint, "Replay changed contents");
            _ = ReplyAsync(id, previous.Reply); return;
        }
        Wire.Require(requests.Count(x => !x.Value.Reply.IsCompleted) < 8, "Too many desktop requests");
        if (requests.Count >= 256) foreach (var old in requests.Where(x => x.Value.Reply.IsCompleted).Take(128)) requests.TryRemove(old.Key, out _);
        var task = HandleRequestAsync(message);
        requests[id] = (fingerprint, task); _ = ReplyAsync(id, task);
    }
    private async Task<JsonObject> HandleRequestAsync(JsonObject message)
    {
        try
        {
            var feature = message.Str("feature");
            Wire.Require(Allows(feature) && host.DesktopCapabilities(DeviceId).Any(c => c.Str("id") == feature && c.Str("status") == "available"), "Enable this computer feature in Settings");
            var result = await host.RequestHandler(this, message.Str("id"), feature, message.Str("action"), message.Data());
            return Wire.Object(("ok", true), ("data", result), ("error", ""));
        }
        catch (Exception e) { return Wire.Object(("ok", false), ("data", new JsonObject()), ("error", e.Message.Length > 512 ? e.Message[..512] : e.Message)); }
    }
    private async Task ReplyAsync(string id, Task<JsonObject> task)
    {
        try { var reply = (JsonObject)(await task).DeepClone(); reply["type"] = "result"; reply["id"] = id; await SendAsync(reply); }
        catch (Exception) { /* session shutdown cancels outstanding replies */ }
    }
    private async Task HeartbeatAsync()
    {
        try { using var timer = new PeriodicTimer(TimeSpan.FromSeconds(30)); while (await timer.WaitForNextTickAsync(Token)) await SendAsync(Wire.Object(("type", "ping"))); }
        catch (Exception) { Dispose(); }
    }
    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0) return;
        life.Cancel(); stream.Dispose(); Files.Dispose();
        foreach (var p in pending.Values) p.TrySetException(new IOException("The original connection disconnected; retry the operation"));
        pending.Clear();
    }
}
