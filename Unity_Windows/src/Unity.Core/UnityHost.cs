using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Unity.Core;

public sealed record PairRequest(string Id, string Name, DateTimeOffset Expires);
public sealed record DeviceView(string Id, string Name, string[] Links, JsonArray Capabilities, JsonObject State);

public sealed class UnityHost : IAsyncDisposable
{
    public static readonly HashSet<string> PhoneFeatures = ["battery", "network", "notifications", "notification_actions", "sms", "call_notifications", "call_control", "clipboard", "files", "photos", "urls", "browsing", "media", "sound", "ringer", "find", "remote_input", "lock", "hotspot", "samsung_routines", "phone_connectivity", "audio", "screen"];
    private readonly CancellationTokenSource life = new();
    private readonly ConcurrentDictionary<string, DeviceSession> sessions = [];
    private readonly ConcurrentDictionary<string, JsonObject> states = [];
    private readonly ConcurrentDictionary<string, (PairRequest Request, TaskCompletionSource<bool> Approval)> pairing = [];
    private readonly ConcurrentDictionary<string, (DeviceSession Parent, string Type, DateTimeOffset Expires, Func<Stream, CancellationToken, Task> Use)> sideStreams = [];
    private readonly ConcurrentDictionary<string, Task> connections = [];
    private readonly ConcurrentDictionary<string, DateTimeOffset> discoveryRate = [];
    private readonly SemaphoreSlim handshakeSlots = new(32);
    private TcpListener? listener;
    private UdpClient? discovery;
    private Task? acceptTask, discoveryTask;
    private DateTimeOffset pairingUntil;
    private readonly object stateGate = new();
    public IdentityStore Store { get; }
    public int Port { get; private set; }
    public string Status { get; private set; } = "Starting";
    public event Action? Changed;
    public event Action<string, string, JsonObject>? Received;
    public event Action<DeviceSession>? Connected;
    public event Action<DeviceSession, string, bool, string>? OperationResult;
    public event Action<string>? Log;
    public Func<DeviceSession, string, string, string, JsonObject, Task<JsonObject>> RequestHandler { get; set; } = (_, _, _, _, _) => Task.FromException<JsonObject>(new NotSupportedException("This Windows feature is unavailable"));
    public Func<string, JsonArray> DesktopCapabilities { get; set; } = _ => new JsonArray();
    public bool PairingOpen => DateTimeOffset.UtcNow < pairingUntil;
    public PairRequest[] PendingPairs => pairing.Values.Select(p => p.Request).ToArray();
    public UnityHost(IdentityStore store) { Store = store; }
    public static string[] LocalAddresses() => NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up)
        .SelectMany(n => n.GetIPProperties().UnicastAddresses).Select(a => a.Address)
        .Where(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a) && IsLocal(a)).Select(a => a.ToString()).Distinct().ToArray();
    public static bool IsLocal(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (IPAddress.IsLoopback(address)) return true;
        var b = address.GetAddressBytes();
        return address.AddressFamily == AddressFamily.InterNetwork ? b[0] == 10 || (b[0] == 172 && b[1] is >= 16 and <= 31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254) : address.IsIPv6LinkLocal || (b[0] & 0xFE) == 0xFC;
    }
    public void Start(int port = Wire.Port, bool enableDiscovery = true)
    {
        try
        {
            listener = new TcpListener(IPAddress.Any, port); listener.Start(32); Port = ((IPEndPoint)listener.LocalEndpoint).Port;
            acceptTask = AcceptAsync();
            if (enableDiscovery) { discovery = new UdpClient(Wire.DiscoveryPort); discoveryTask = DiscoverAsync(); }
            Status = $"Listening on port {Port}"; NotifyChanged();
        }
        catch { listener?.Stop(); discovery?.Dispose(); throw; }
    }
    public void OpenPairing() { pairingUntil = DateTimeOffset.UtcNow.AddSeconds(90); NotifyChanged(); _ = ClosePairingLaterAsync(pairingUntil); }
    private async Task ClosePairingLaterAsync(DateTimeOffset until) { try { await Task.Delay(TimeSpan.FromSeconds(90), life.Token); if (pairingUntil == until) NotifyChanged(); } catch (OperationCanceledException) { } }
    public void Approve(string id, bool approved) { if (pairing.TryGetValue(id, out var p)) p.Approval.TrySetResult(approved); }
    public void Unpair(string id)
    {
        Store.Revoke(id); foreach (var s in sessions.Values.Where(s => s.DeviceId == id)) s.Dispose();
        lock (stateGate) states.TryRemove(id, out _); NotifyChanged();
    }
    public void Disconnect(string id) { foreach (var s in sessions.Values.Where(s => s.DeviceId == id)) s.Dispose(); }
    public async Task SetEnabledAsync(string id, string feature, bool enabled)
    {
        Store.SetEnabled(id, feature, enabled);
        lock (stateGate) if (states.TryGetValue(id, out var state) && !enabled) state.Remove(feature);
        if (!enabled && feature is "audio" or "screen") CancelSideStreams(id);
        foreach (var session in sessions.Values.Where(s => s.DeviceId == id && s.Connected))
            await session.SendAsync(Wire.Object(("type", "capabilities"), ("capabilities", DesktopCapabilities(id))));
        NotifyChanged();
    }
    public DeviceSession Select(string id, string feature)
    {
        var bulk = feature is "files" or "photos" or "browsing" or "audio" or "screen";
        var live = sessions.Values.Where(s => s.DeviceId == id && s.Connected && s.Allows(feature, true)).ToArray();
        return live.FirstOrDefault(s => s.Kind == (bulk ? "lan" : "bluetooth")) ?? live.FirstOrDefault() ?? throw new IOException("Connect your phone and enable this feature first");
    }
    public DeviceSession[] Sessions => sessions.Values.Where(s => s.Connected).ToArray();
    public DeviceView[] Devices()
    {
        lock (stateGate) return Store.Phones.Select(p =>
        {
            var live = Sessions.Where(s => s.DeviceId == p.Id).ToArray();
            return new DeviceView(p.Id, p.Name, live.Select(s => s.Kind).ToArray(), live.FirstOrDefault()?.Capabilities ?? new(),
                states.TryGetValue(p.Id, out var state) ? (JsonObject)state.DeepClone() : new());
        }).ToArray();
    }
    public string ReserveStream(DeviceSession parent, string type, Func<Stream, CancellationToken, Task> use)
    {
        Wire.Require(parent.Connected && type is "audio_hello" or "screen_hello", "Invalid side stream");
        foreach (var old in sideStreams.Where(x => x.Value.Expires < DateTimeOffset.UtcNow || !x.Value.Parent.Connected)) sideStreams.TryRemove(old.Key, out _);
        Wire.Require(sideStreams.Count < 16, "Too many side streams"); var token = Wire.Secret();
        sideStreams[token] = (parent, type, DateTimeOffset.UtcNow.AddSeconds(120), use); return token;
    }
    public void RemoveStream(string token) => sideStreams.TryRemove(token, out _);
    private void CancelSideStreams(string id) { foreach (var s in sideStreams.Where(s => s.Value.Parent.DeviceId == id)) sideStreams.TryRemove(s.Key, out _); }
    internal void NotifyChanged() => Changed?.Invoke();
    internal void OnOperationResult(DeviceSession session, string id, bool ok, string error) => OperationResult?.Invoke(session, id, ok, error);
    internal void OnReceived(string id, string feature, JsonObject data) { Received?.Invoke(id, feature, data); NotifyChanged(); }
    public void PublishLocalState(string id, string feature, JsonObject data)
    {
        lock (stateGate) { var state = states.GetOrAdd(id, _ => new()); state[feature] = data.DeepClone(); }
        NotifyChanged();
    }
    internal void UpdateState(DeviceSession session, string feature, JsonObject data)
    {
        lock (stateGate)
        {
            var state = states.GetOrAdd(session.DeviceId, _ => new());
            if (feature == "notifications")
            {
                var notices = state[feature] as JsonObject;
                if (notices == null) state[feature] = notices = new();
                var id = data.Str("id");
                if (data.Bool("removed")) notices.Remove(id);
                else { if (notices.Count >= 200 && !notices.ContainsKey(id)) notices.Remove(notices.First().Key); notices[id] = data.DeepClone(); }
            }
            else state[feature] = data.DeepClone();
        }
        if (feature is "urls" or "clipboard" or "notifications" or "sms" or "call_notifications" or "call_control") OnReceived(session.DeviceId, feature, data);
        else NotifyChanged();
    }
    internal void PruneState(DeviceSession session)
    {
        lock (stateGate) if (states.TryGetValue(session.DeviceId, out var state))
            foreach (var key in state.Select(x => x.Key).Where(key => PhoneFeatures.Contains(key) && !session.Allows(key, true)).ToArray()) state.Remove(key);
    }
    private async Task AcceptAsync()
    {
        try
        {
            while (!life.IsCancellationRequested)
            {
                var client = await listener!.AcceptTcpClientAsync(life.Token);
                if (!IsLocal(((IPEndPoint)client.Client.RemoteEndPoint!).Address) || !handshakeSlots.Wait(0)) { client.Dispose(); continue; }
                client.NoDelay = true;
                TrackConnection(async () =>
                {
                    try
                    {
                        using (client) await AcceptStreamAsync(client.GetStream(), "lan", null, life.Token,
                    type => { if (type == "audio_hello") client.SendBufferSize = 16 * 1024; });
                    }
                    finally { handshakeSlots.Release(); }
                });
            }
        }
        catch (Exception e) when (life.IsCancellationRequested || e is SocketException) { if (!life.IsCancellationRequested) Report(e.Message); }
    }
    private void TrackConnection(Func<Task> task)
    {
        var id = Wire.Id(); var run = task(); connections[id] = run;
        _ = run.ContinueWith(_ => connections.TryRemove(id, out var ignored), TaskScheduler.Default);
    }
    public async Task AcceptStreamAsync(Stream raw, string kind, string? expectedType = null, CancellationToken ct = default, Action<string>? configureStream = null)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, life.Token);
        await using var tls = new SslStream(raw, false);
        DeviceSession? session = null;
        try
        {
            using var handshake = CancellationTokenSource.CreateLinkedTokenSource(linked.Token); handshake.CancelAfter(TimeSpan.FromSeconds(15));
            await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
            {
                ServerCertificate = Store.Certificate,
                EnabledSslProtocols = SslProtocols.Tls13,
                ClientCertificateRequired = false,
                AllowRenegotiation = false,
                AllowTlsResume = false
            }, handshake.Token);
            var hello = await Wire.ReadAsync(tls, handshake.Token);
            if (expectedType != null) Wire.Require(hello.Str("type") == expectedType, "Wrong Bluetooth stream service");
            if (hello.Str("type") is "audio_hello" or "screen_hello")
            {
                Wire.Require(hello.Num("version") == 1 && Wire.IsHex(hello.Str("device_id"), 32) && Wire.IsHex(hello.Str("token"), 64), "Invalid side-stream hello");
                Wire.Require(sideStreams.TryGetValue(hello.Str("token"), out var side) && side.Parent.DeviceId == hello.Str("device_id") &&
                    side.Type == hello.Str("type") && side.Parent.Kind == kind && side.Expires > DateTimeOffset.UtcNow && side.Parent.Connected, "Unrequested or stale side stream");
                Wire.Require(sideStreams.TryRemove(hello.Str("token"), out _), "Stream token already used");
                // Bound only audio's TCP backlog; transfers retain their normal
                // throughput. Capture drops old PCM while this buffer drains.
                configureStream?.Invoke(side.Type);
                using var streamLife = CancellationTokenSource.CreateLinkedTokenSource(linked.Token, side.Parent.Token);
                await side.Use(tls, streamLife.Token); return;
            }
            Wire.Require(hello.Str("type") == "hello", "Expected hello"); Wire.Hello(hello);
            var id = hello.Str("device_id"); var phone = Store.Find(id);
            if (phone == null)
            {
                Wire.Require(PairingOpen && pairing.Count < 8, "Open pairing on your computer first");
                var request = new PairRequest(id, hello.Str("name"), DateTimeOffset.UtcNow.AddSeconds(90));
                var approval = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
                Wire.Require(pairing.TryAdd(id, (request, approval)), "Phone pairing is already pending");
                try
                {
                    await Wire.WriteAsync(tls, Wire.Object(("type", "pair_pending"), ("device_id", id)), linked.Token); NotifyChanged();
                    Wire.Require(await approval.Task.WaitAsync(TimeSpan.FromSeconds(90), linked.Token), "Pairing declined");
                    var secret = Wire.Secret();
                    Store.Trust(new(id, hello.Str("name"), secret, ["lock", "screen"]));
                    await Wire.WriteAsync(tls, Wire.Object(("type", "pair_result"), ("secret", secret)), linked.Token);
                }
                finally { pairing.TryRemove(id, out _); NotifyChanged(); }
                return;
            }
            var nonce = Wire.Secret();
            await Wire.WriteAsync(tls, Wire.Object(("type", "challenge"), ("nonce", nonce)), handshake.Token);
            var proof = await Wire.ReadAsync(tls, handshake.Token);
            Wire.Require(proof.Str("type") == "proof" && Wire.IsHex(proof.Str("mac"), 64), "Invalid proof");
            Wire.Require(CryptographicOperations.FixedTimeEquals(Convert.FromHexString(proof.Str("mac")), Convert.FromHexString(Wire.Proof(phone.Secret, id, nonce))), "Authentication failed");
            Wire.Require(Store.Find(id)?.Secret == phone.Secret, "Pairing revoked");
            session = new(this, tls, hello, kind, linked.Token);
            var key = id + ":" + kind;
            sessions.AddOrUpdate(key, session, (_, old) => { old.Dispose(); return session; });
            await Wire.WriteAsync(tls, Wire.Object(("type", "ready")), handshake.Token);
            Connected?.Invoke(session);
            await session.RunAsync();
        }
        catch (Exception e) when (e is not OutOfMemoryException) { if (!life.IsCancellationRequested) Report(e.Message); }
        finally
        {
            if (session != null)
            {
                session.Dispose(); sessions.TryRemove(new KeyValuePair<string, DeviceSession>(session.DeviceId + ":" + kind, session));
                CancelExpiredStreams(session);
                lock (stateGate) if (!Sessions.Any(s => s.DeviceId == session.DeviceId)) states.TryRemove(session.DeviceId, out _);
                NotifyChanged();
            }
        }
    }
    private void CancelExpiredStreams(DeviceSession session) { foreach (var x in sideStreams.Where(x => x.Value.Parent == session)) sideStreams.TryRemove(x.Key, out _); }
    private async Task DiscoverAsync()
    {
        try
        {
            while (!life.IsCancellationRequested)
            {
                var packet = await discovery!.ReceiveAsync(life.Token);
                if (!PairingOpen || !IsLocal(packet.RemoteEndPoint.Address) || !packet.Buffer.AsSpan().SequenceEqual("UNITY_DISCOVER_V1"u8)) continue;
                var key = packet.RemoteEndPoint.Address.ToString(); var now = DateTimeOffset.UtcNow;
                if (discoveryRate.TryGetValue(key, out var last) && (now - last).TotalSeconds < 1) continue;
                if (discoveryRate.Count >= 256) discoveryRate.Clear(); discoveryRate[key] = now;
                var response = Wire.Object(("version", 1), ("name", Environment.MachineName), ("port", Port), ("fingerprint", Store.Fingerprint));
                await discovery.SendAsync(Encoding.UTF8.GetBytes(response.ToJsonString()), packet.RemoteEndPoint, life.Token);
            }
        }
        catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException or SocketException) { }
    }
    public void Report(string message) => Log?.Invoke(message);
    public async ValueTask DisposeAsync()
    {
        life.Cancel(); listener?.Stop(); discovery?.Dispose();
        foreach (var p in pairing.Values) p.Approval.TrySetResult(false);
        foreach (var s in sessions.Values) s.Dispose();
        var tasks = connections.Values.Concat(new[] { acceptTask, discoveryTask }.OfType<Task>()).ToArray();
        try { await Task.WhenAll(tasks).WaitAsync(TimeSpan.FromSeconds(5)); } catch (Exception) { }
        Store.Certificate.Dispose(); life.Dispose();
    }
}
