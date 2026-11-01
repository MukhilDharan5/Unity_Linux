using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using Unity.Core;
using Unity.Platform;

internal static partial class Program
{
    private static int passed;
    private static void Check(bool condition, string name) { if (!condition) throw new Exception("FAIL " + name); Console.WriteLine("PASS " + name); passed++; }
    private static async Task Reject(Func<Task> action, string name) { try { await action(); } catch (Exception) { Check(true, name); return; } throw new Exception("FAIL expected rejection: " + name); }
    private static async Task Until(Func<bool> condition) { using var cts = new CancellationTokenSource(5000); while (!condition()) await Task.Delay(20, cts.Token); }
    private static readonly JsonArray Caps = new(UnityHost.PhoneFeatures.Select(f => (JsonNode)Wire.Object(("id", f), ("status", "available"), ("reason", ""))).ToArray());
    private static JsonObject Hello(string id) => Wire.Object(("type", "hello"), ("version", 1), ("device_id", id), ("name", "Android test phone"), ("capabilities", Caps));
    public static async Task<int> Main(string[] args)
    {
        var root = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../..", ".tools", "test-state-" + Wire.Id()));
        try
        {
            if (args.SequenceEqual(new[] { "--probe-platform" }))
            {
                await using var probeHost = new UnityHost(new IdentityStore(root));
                await using var platform = new DesktopServices(probeHost);
                Console.WriteLine("Windows volume: " + platform.Media.Volume().ToJsonString());
                Console.WriteLine("Windows players: " + (await platform.Media.PlayersAsync()).ToJsonString());
                await platform.Bluetooth.StartAsync(); Console.WriteLine("Bluetooth: " + platform.Bluetooth.Status); return 0;
            }
            if (args.SequenceEqual(new[] { "--probe-audio" }))
            {
                using var bytes = new MemoryStream(); using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(15));
                var watch = Stopwatch.StartNew();
                try { await AudioStream.RunAsync(bytes, () => { Console.WriteLine($"Native audio ready in {watch.ElapsedMilliseconds} ms"); stop.CancelAfter(1500); }, stop.Token); } catch (OperationCanceledException) when (bytes.Length > 0) { }
                bytes.Position = 0; Check((await Wire.ReadAsync(bytes)).Str("type") == "ready", "native WASAPI starts after captured PCM");
                var remaining = bytes.Length - bytes.Position; Check(remaining >= 1928 && remaining % 1928 == 0, "native WASAPI emits fixed Android PCM packets");
                var packet = new byte[1928]; long previous = -1; int count = 0;
                while (bytes.Position < bytes.Length) { await bytes.ReadExactlyAsync(packet); var seq = BinaryPrimitives.ReadInt64BigEndian(packet); if (seq <= previous) throw new Exception("Native PCM sequence regressed"); previous = seq; count++; }
                Check(count >= 50, "native PCM stays live across silent output");
                Console.WriteLine("Audio probe keeps all bytes in memory and discards them on exit."); return 0;
            }
            if (args.SequenceEqual(new[] { "--probe-display" }))
            {
                using var display = new DisplayBlanking();
                using var marker = new DisplayMarker();
                using var capture = new DesktopCapture();
                using var captureTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
                byte[] Frame() => capture.NextAsync(captureTimeout.Token).GetAwaiter().GetResult();
                byte[] ChangedFrame(byte[] previous)
                {
                    var watch = Stopwatch.StartNew();
                    while (watch.Elapsed < TimeSpan.FromSeconds(3))
                    {
                        var next = Frame();
                        if (!previous.SequenceEqual(next)) return next;
                        Thread.Sleep(50);
                    }
                    throw new IOException("The desktop did not present a changed marker frame within three seconds");
                }
                try
                {
                    var before = Frame(); marker.Change(); var after = ChangedFrame(before);
                    Check(!before.SequenceEqual(after), "native marker changes are visible with display on");
                    display.Set(false); Thread.Sleep(500);
                    Check(!display.IsOn && display.Mode == "blank", "Windows local display is blanked");
                    var first = Frame(); Wire.Jpeg(first, 1280, 921600, 262144);
                    marker.Change();
                    var next = ChangedFrame(first); Wire.Jpeg(next, 1280, 921600, 262144);
                    Check(first.Length > 1024 && next.Length > 1024 && !first.SequenceEqual(next), "Windows Graphics Capture updates content behind the black display cover");
                    marker.MovePointer(); Thread.Sleep(400);
                    Check(!display.IsOn, "remote input keeps the local display blanked");
                    NativeDesktop.InputEvent(Wire.Object(("kind", "key"), ("key", "u"), ("modifiers", new JsonArray("ctrl", "alt", "shift")))); Thread.Sleep(300);
                    Check(display.IsOn, "Ctrl+Alt+Shift+U restores the laptop locally");
                }
                finally { display.Set(true); }
                Check(display.IsOn, "Windows confirms local display restoration"); return 0;
            }
            if (args.SequenceEqual(new[] { "--telemetry-only" }))
            {
                Directory.CreateDirectory(root); await TelemetryAsync(root);
                Console.WriteLine($"{passed} local collection checks passed (fixture data only)."); return 0;
            }
            if (args.Length is not (0 or 3)) throw new ArgumentException("Expected either no arguments or JAVA ANDROID_PEER_JAR JSON_JAR");
            Directory.CreateDirectory(root); await UnitAsync(root);
            await TelemetryAsync(root);
            await IntegrationAsync(root);
            await FeatureStateAsync(root);
            await AudioAsync(root);
            await ScreenAsync(root);
            if (args.Length == 3) await KotlinAsync(root, args[0], args[1], args[2]);
            Console.WriteLine($"{passed} checks passed."); return 0;
        }
        catch (Exception e) { Console.Error.WriteLine(e); return 1; }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
    private static async Task ScreenAsync(string root)
    {
        await using var host = new UnityHost(new IdentityStore(Path.Combine(root, "screen"))); host.Start(0, false);
        var id = Wire.Id(); var secret = Wire.Secret(); host.Store.Trust(new(id, "Screen peer", secret, []));
        var displays = new List<TestDisplay>();
        await using var desktop = new DesktopServices(host)
        {
            ConfirmShare = (_, _) => Task.FromResult(true),
            CaptureFrame = () => File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "preview.jpg")),
            DisplayFactory = () => { var display = new TestDisplay(); displays.Add(display); return display; }
        };
        using var peer = await Peer.Login(host.Port, host.Store.Fingerprint, id, secret); await Until(() => host.Sessions.Length == 1);
        var session = host.Sessions[0]; var start = desktop.StartAsync(session, "screen", "phone");
        var request = await peer.ReadCommand(); Check(request.Str("action") == "start" && request.Data().Str("source") == "phone", "phone screen command matches Android ScreenRequest");
        var jpeg = File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "preview.jpg")); var validated = Wire.Jpeg(jpeg, 1280, 921600, 262144);
        Check(validated.Width > 0 && validated.Height > 0, "bounded screen JPEG dimensions");
        using (var side = await Peer.Open(host.Port, host.Store.Fingerprint))
        {
            await side.Send(Wire.Object(("type", "screen_hello"), ("version", 1), ("device_id", id), ("token", request.Data().Str("token"))));
            Check((await side.Read()).Str("type") == "ready", "screen side-channel ready");
            await side.SendBinary(jpeg); await start.WaitAsync(TimeSpan.FromSeconds(5));
            Check(host.Devices()[0].State["screen"].Str("state") == "streaming", "first JPEG completes Android screen start without result");
            var stop = desktop.StopAsync(id, "screen"); var stopRequest = await peer.ReadCommand(); await peer.Result(stopRequest.Str("id"), Wire.Object(("state", "stopped"))); await stop;
            Check(host.Devices()[0].State["screen"].Str("state") == "stopped", "screen stop cleans up side stream");
        }
        start = desktop.StartAsync(session, "screen", "desktop", true, blankDisplay: true); request = await peer.ReadCommand();
        Check(request.Data().Bool("blank_display") && displays.All(d => d.IsOn), "display-off intent waits for the Android visible viewer");
        using (var side = await Peer.Open(host.Port, host.Store.Fingerprint))
        {
            await side.Send(Wire.Object(("type", "screen_hello"), ("version", 1), ("device_id", id), ("token", request.Data().Str("token"))));
            Check((await side.Read()).Str("type") == "ready", "desktop screen ready after capture"); var frame = await side.ReadBinary();
            Check(frame.SequenceEqual(jpeg), "desktop JPEG uses Android binary frame format");
            var state = await peer.ReadCommand(); Check(state.Str("action") == "state" && state.Data().Bool("input_enabled") && state.Data().Bool("display_available") && state.Data().Bool("display_on"), "Windows advertises session-scoped display control after first frame");
            await peer.Result(state.Str("id"), Wire.Object(("state", "received"))); await start.WaitAsync(TimeSpan.FromSeconds(5));
            await Reject(() => host.RequestHandler(session, Wire.Id(), "screen", "display", Wire.Object(("session_id", Wire.Id()), ("on", false))), "stale screen session cannot blank the laptop");
            var off = host.RequestHandler(session, Wire.Id(), "screen", "display", Wire.Object(("session_id", request.Str("id")), ("on", false)));
            state = await peer.ReadCommand(); Check(!state.Data().Bool("display_on"), "phone display request turns off the approved laptop session");
            await peer.Result(state.Str("id"), Wire.Object(("state", "received"))); await off;
            Check(displays[^1].Changes == 1 && !displays[^1].IsOn, "display off keeps the sharing route alive");
            await peer.Send(Wire.Object(("type", "result"), ("id", request.Str("id")), ("ok", false), ("error", "Phone viewer closed"), ("data", new JsonObject())));
            await Until(() => host.Devices()[0].State["screen"].Str("state") == "failed"); Check(true, "late Android screen failure stops an established stream");
            Check(displays[^1].Disposed && displays[^1].IsOn, "late viewer failure restores display power");
        }
        await Reject(() => desktop.SetDisplayAsync(id, false), "display control unavailable outside sharing");
        async Task<Peer> Begin(bool control = true)
        {
            var opening = desktop.StartAsync(session, "screen", "desktop", control);
            var command = await peer.ReadCommand(); var side = await Peer.Open(host.Port, host.Store.Fingerprint);
            await side.Send(Wire.Object(("type", "screen_hello"), ("version", 1), ("device_id", id), ("token", command.Data().Str("token"))));
            await side.Read(); await side.ReadBinary(); var status = await peer.ReadCommand();
            await peer.Result(status.Str("id"), Wire.Object(("state", "received"))); await opening;
            return side;
        }
        async Task Blank()
        {
            var blank = desktop.SetDisplayAsync(id, false); var status = await peer.ReadCommand();
            await peer.Result(status.Str("id"), Wire.Object(("state", "received"))); await blank;
        }
        using (var side = await Begin(false))
        {
            Check(!host.Devices()[0].State["screen"].Bool("display_available"), "view-only sharing cannot blank local displays");
            await Reject(() => desktop.SetDisplayAsync(id, false), "view-only route rejects display changes");
            var stopping = desktop.StopAsync(id, "screen"); var stop = await peer.ReadCommand(); await peer.Result(stop.Str("id"), Wire.Object(("state", "stopped"))); await stopping;
        }
        using (var side = await Begin())
        {
            await Blank(); var display = displays[^1];
            await host.SetEnabledAsync(id, "screen", false);
            Check(display.Disposed && display.IsOn, "revoking screen permission restores the laptop display");
            await host.SetEnabledAsync(id, "screen", true);
        }
        using (var side = await Begin())
        {
            await Blank(); var display = displays[^1];
            var stopping = desktop.StopAsync(id, "screen"); var stop = await peer.ReadCommand();
            Check(display.Disposed && display.IsOn, "explicit stop restores the display before waiting for Android");
            await peer.Result(stop.Str("id"), Wire.Object(("state", "stopped"))); await stopping;
        }
        using (var side = await Begin())
        {
            await Blank(); var display = displays[^1]; peer.Dispose();
            await Until(() => display.Disposed); Check(display.IsOn, "original control connection loss restores the display");
        }
        host.Unpair(id);
    }
    private sealed class TestDisplay : IDesktopDisplay
    {
        public bool IsOn { get; private set; } = true;
        public string Mode => "blank";
        public Action? Restored { get; set; }
        public int Changes; public bool Disposed;
        public void Set(bool on) { ObjectDisposedException.ThrowIf(Disposed, this); Changes++; IsOn = on; }
        public void Dispose() { Disposed = true; IsOn = true; }
    }
    private sealed class DisplayMarker : IDisposable
    {
        [StructLayout(LayoutKind.Sequential)] private struct Point { public int X, Y; }
        [StructLayout(LayoutKind.Sequential)] private struct Message { public nint Window; public uint Id; public nuint WParam; public nint LParam; public uint Time; public int X, Y; public uint Private; }
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern nint CreateWindowEx(uint ex, string cls, string text, uint style, int x, int y, int w, int h, nint parent, nint menu, nint instance, nint param);
        [DllImport("user32.dll")] private static extern bool UpdateWindow(nint window);
        [DllImport("user32.dll", SetLastError = true)] private static extern bool SetWindowPos(nint window, nint after, int x, int y, int width, int height, uint flags);
        [DllImport("user32.dll")] private static extern bool IsWindowVisible(nint window);
        [DllImport("user32.dll")] private static extern bool DestroyWindow(nint window);
        [DllImport("user32.dll")] private static extern bool IsWindow(nint window);
        [DllImport("user32.dll")] private static extern bool PostMessage(nint window, uint message, nuint wParam, nint lParam);
        [DllImport("user32.dll")] private static extern int GetMessage(out Message message, nint window, uint minimum, uint maximum);
        [DllImport("user32.dll")] private static extern bool TranslateMessage(in Message message);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern nint DispatchMessage(in Message message);
        [DllImport("user32.dll")] private static extern bool GetCursorPos(out Point point);
        private readonly nint window;
        private readonly Thread worker;
        public DisplayMarker()
        {
            var ready = new TaskCompletionSource<nint>(TaskCreationOptions.RunContinuationsAsynchronously);
            worker = new Thread(() =>
            {
                nint marker = 0;
                try
                {
                    marker = CreateWindowEx(0x08000008, "STATIC", "Unity display test marker", 0x90000006, 20, 20, 400, 100, 0, 0, 0, 0);
                    if (marker == 0) throw new IOException("Native display test marker unavailable");
                    if (!SetWindowPos(marker, -1, 20, 20, 400, 100, 0x0050) || !IsWindowVisible(marker)) throw new IOException("Native display test marker could not be shown");
                    UpdateWindow(marker); ready.TrySetResult(marker);
                    while (IsWindow(marker) && GetMessage(out var message, 0, 0, 0) > 0) { TranslateMessage(in message); DispatchMessage(in message); }
                }
                catch (Exception e) { ready.TrySetException(e); }
                finally { if (marker != 0 && IsWindow(marker)) DestroyWindow(marker); }
            }) { IsBackground = true, Name = "Unity capture test marker" };
            worker.Start(); window = ready.Task.WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
        }
        private int changes;
        public void Change()
        {
            var alternate = ++changes % 2 == 1;
            if (!SetWindowPos(window, 0, alternate ? 440 : 20, 20, 400, 100, 0x0014)) throw new IOException("Native marker update failed");
            UpdateWindow(window);
        }
        public void MovePointer()
        {
            if (GetCursorPos(out var p))
                NativeDesktop.InputEvent(Wire.Object(("kind", "motion"), ("x", Math.Clamp(p.X / (double)Math.Max(1, GetSystemMetrics(0) - 1), 0, 1)), ("y", Math.Clamp(p.Y / (double)Math.Max(1, GetSystemMetrics(1) - 1), 0, 1))));
        }
        [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
        public void Dispose() { PostMessage(window, 0x0010, 0, 0); worker.Join(TimeSpan.FromSeconds(2)); }
    }
    private static async Task AudioAsync(string root)
    {
        var pcm = RandomNumberGenerator.GetBytes(AudioPackets.PcmBytes * 2);
        var packets = new AudioPackets();
        foreach (var part in pcm.Chunk(28)) packets.Add(part, 0);
        Check(packets.TryRead(0, out var p0) && packets.TryRead(0, out var p1) && p0.Bytes.AsSpan(8).SequenceEqual(pcm.AsSpan(0, 1920)) && p1.Bytes.AsSpan(8).SequenceEqual(pcm.AsSpan(1920)), "PCM callback fragments preserve samples across packet boundaries");
        packets = new(); for (int i = 0; i < 20; i++) { var data = new byte[1920]; data.AsSpan().Fill((byte)i); packets.Add(data, 0); }
        var retained = new List<AudioPackets.Packet>(); while (packets.TryRead(0, out var p)) retained.Add(p);
        Check(retained.Count == AudioPackets.Capacity && retained[0].Bytes[8] == 16 && retained[^1].Bytes[8] == 19 && BinaryPrimitives.ReadUInt64BigEndian(retained[0].Bytes) == 16, "slow Wi-Fi drops oldest audio and preserves newest capture sequences");
        packets.Add(new byte[1920], 0); Check(!packets.TryRead(61, out _), "aged audio is discarded before network writes");
        packets.Add(new byte[100], 0); packets.Add(pcm.AsSpan(0, 1920), 100);
        Check(packets.TryRead(100, out var fresh) && fresh.Bytes.AsSpan(8).SequenceEqual(pcm.AsSpan(0, 1920)), "capture stalls discard partial old samples");
        packets.Add(new byte[100], 100); packets.Add(pcm.AsSpan(0, 1920), 100, true);
        Check(packets.TryRead(100, out fresh) && fresh.Bytes.AsSpan(8).SequenceEqual(pcm.AsSpan(0, 1920)), "WASAPI discontinuity cannot splice stale PCM");
        await using var host = new UnityHost(new IdentityStore(Path.Combine(root, "audio"))); host.Start(0, false);
        var id = Wire.Id(); var secret = Wire.Secret(); host.Store.Trust(new(id, "Audio peer", secret, []));
        var live = new AudioPackets();
        await using var desktop = new DesktopServices(host) { StreamAudio = (stream, started, ct) => AudioStream.SendAsync(stream, live, started, ct) };
        using var peer = await Peer.Login(host.Port, host.Store.Fingerprint, id, secret); await Until(() => host.Sessions.Length == 1);
        var session = host.Sessions[0]; var start = desktop.StartAsync(session, "audio"); var request = await peer.ReadCommand();
        Check(request.Data().Num("rate") == 48000 && request.Data().Num("frames") == 480 && request.Data().Str("format") == "s16le", "audio startup matches existing Android PCM contract");
        using var side = await Peer.Open(host.Port, host.Store.Fingerprint);
        await side.Send(Wire.Object(("type", "audio_hello"), ("version", 1), ("device_id", id), ("token", request.Data().Str("token"))));
        live.Add(pcm.AsSpan(0, 1920), Environment.TickCount64);
        Check((await side.Read()).Str("type") == "ready", "audio side stream ready follows a captured packet");
        var wire = await side.ReadAudio(); Check(wire.AsSpan(8).SequenceEqual(pcm.AsSpan(0, 1920)), "TLS audio carries exact captured PCM bytes");
        await peer.Result(request.Str("id"), Wire.Object(("state", "receiving"))); await start.WaitAsync(TimeSpan.FromSeconds(5));
        live.Complete(new IOException("Test output disconnected"));
        var stop = await peer.ReadCommand(); Check(stop.Str("feature") == "audio" && stop.Str("action") == "stop", "capture failure immediately tells Android to release playback");
        await peer.Result(stop.Str("id"), Wire.Object(("state", "stopped")));
        await Until(() => host.Devices()[0].State["audio"].Str("state") == "failed");
        Check(host.Devices()[0].State["audio"].Str("error").Contains("Test output disconnected"), "capture failure remains visible on Windows");
        host.Unpair(id);
    }
    private static async Task UnitAsync(string root)
    {
        Check(Wire.Proof(string.Concat(Enumerable.Repeat("01", 32)), "0123456789abcdef0123456789abcdef", string.Concat(Enumerable.Repeat("02", 32))) == "132b32a231e9d461c7946c07250e35cc38aaf226f6b9a29b2c58268b8b727272", "Android ProtocolTest HMAC vector");
        Check(!Wire.IsHex(new string('A', 32), 32) && !Wire.IsHex("../phone", 32), "strict lowercase identities");
        foreach (var name in new[] { "../a", "a\\b", "CON.txt", "NUL", "LPT1.txt", "a:stream", "a.", "a ", "..", "/a", "a\n" }) Check(!Wire.SafeName(name), "reject file name " + name.Replace('\n', '_'));
        Check(Wire.SafeName("résumé 📱.pdf") && !Wire.SafeName(new string('界', 100)), "Unicode filename byte bound");
        Check(Wire.SafeUrl("https://example.org/path") && !Wire.SafeUrl("file:///C:/Windows") && !Wire.SafeUrl("javascript:alert(1)"), "web URL allowlist");
        foreach (uint size in new[] { 0u, (uint)Wire.MaxFrame + 1, uint.MaxValue })
        { var bytes = new byte[4]; BinaryPrimitives.WriteUInt32BigEndian(bytes, size); await Reject(() => Wire.ReadAsync(new MemoryStream(bytes)), "invalid frame " + size); }
        await Reject(() => Wire.ReadAsync(new MemoryStream([0, 0, 0, 3, (byte)'{'])), "truncated frame");
        await Reject(() => Wire.ReadAsync(new MemoryStream([0, 0, 0, 1, 255])), "invalid UTF-8");
        var fragmented = new SlowReadStream(new MemoryStream([0, 0, 0, 2, (byte)'{', (byte)'}']));
        Check((await Wire.ReadAsync(fragmented)).Count == 0, "fragmented length and payload");
        await Reject(() => Task.Run(() => Wire.Hello(Hello("bad"))), "hello identity validation");
        var duplicate = Hello(Wire.Id()); ((JsonArray)duplicate["capabilities"]!).Add(Caps[0]!.DeepClone());
        await Reject(() => Task.Run(() => Wire.Hello(duplicate)), "duplicate capability rejection");
        await Reject(() => Task.Run(() => EventValidation.Validate("battery", Wire.Object(("level", 101), ("charging", false)))), "battery bounds");
        await Reject(() => Task.Run(() => EventValidation.Validate("sound", Wire.Object(("origin", "desktop"), ("volume", 50)))), "no desktop sound reflection");
        var store = new IdentityStore(Path.Combine(root, "identity")); var pin = store.Fingerprint; var secret = Wire.Secret();
        store.Trust(new(new string('a', 32), "Test", secret, []));
        var disk = File.ReadAllBytes(Path.Combine(store.Root, "identity.dat")); Check(!Encoding.UTF8.GetString(disk).Contains(secret), "DPAPI protects pairing secrets");
        var reload = new IdentityStore(store.Root); Check(reload.Fingerprint == pin && reload.Find(new string('a', 32))?.Secret == secret, "certificate and trust survive restart");
        reload.Certificate.Dispose(); store.Certificate.Dispose();
        var received = Path.Combine(root, "files"); using var transfers = new TransferReceiver(received); string? destination = null; transfers.Received += p => destination = p;
        var id = Wire.Id(); var content = Encoding.UTF8.GetBytes("Android → Windows 📱"); var hash = Convert.ToHexStringLower(SHA256.HashData(content));
        transfers.Receive(Wire.Object(("id", id), ("phase", "begin"), ("name", "hello.txt"), ("size", content.Length), ("sha256", hash)));
        transfers.Receive(Wire.Object(("id", id), ("phase", "chunk"), ("data", Convert.ToBase64String(content)))); transfers.Receive(Wire.Object(("id", id), ("phase", "end")));
        Check(destination != null && File.ReadAllBytes(destination).SequenceEqual(content), "verified received bytes");
        id = Wire.Id(); transfers.Receive(Wire.Object(("id", id), ("phase", "begin"), ("name", "bad.txt"), ("size", 1), ("sha256", hash)));
        transfers.Receive(Wire.Object(("id", id), ("phase", "chunk"), ("data", "AA==")));
        await Reject(() => Task.Run(() => transfers.Receive(Wire.Object(("id", id), ("phase", "end")))), "bad file digest rejected");
        Check(!Directory.EnumerateFiles(received, "*.part").Any(), "failed transfers remove partial files");
        id = Wire.Id(); transfers.Receive(Wire.Object(("id", id), ("phase", "begin"), ("name", "abort.txt"), ("size", 0), ("sha256", hash)));
        transfers.Receive(Wire.Object(("id", id), ("phase", "abort"))); Check(!Directory.EnumerateFiles(received, "*.part").Any(), "transfer abort cleans quarantine");
        Check(UnityHost.IsLocal(IPAddress.Parse("192.168.1.1")) && !UnityHost.IsLocal(IPAddress.Parse("8.8.8.8")) && UnityHost.IsLocal(IPAddress.Parse("::1")), "private network address policy");
    }
    private static async Task IntegrationAsync(string root)
    {
        await using var host = new UnityHost(new IdentityStore(Path.Combine(root, "host"))); host.Log += m => Console.WriteLine("  server: " + m); host.Start(0, false);
        var id = Wire.Id(); string secret;
        using (var peer = await Peer.Open(host.Port, host.Store.Fingerprint))
        {
            await peer.Send(Hello(id)); await Reject(() => peer.Read(), "unknown phone rejected while pairing closed");
        }
        host.OpenPairing();
        using (var peer = await Peer.Open(host.Port, host.Store.Fingerprint))
        {
            await peer.Send(Hello(id)); Check((await peer.Read()).Str("type") == "pair_pending", "pairing requires local approval");
            Check(host.Store.Find(id) == null, "pending pairing cannot self-approve");
            host.Approve(id, true); var result = await peer.Read(); secret = result.Str("secret"); Check(Wire.IsHex(secret, 64), "random 256-bit pairing credential");
        }
        using (var bad = await Peer.Open(host.Port, host.Store.Fingerprint))
        {
            await bad.Send(Hello(id)); var challenge = await bad.Read(); await bad.Send(Wire.Object(("type", "proof"), ("mac", Wire.Proof(Wire.Secret(), id, challenge.Str("nonce")))));
            await Reject(() => bad.Read(), "wrong secret rejected");
        }
        await Reject(async () => { using var bad = await Peer.Open(host.Port, new string('0', 64)); }, "changed certificate pin rejected");
        using var lan = await Peer.Login(host.Port, host.Store.Fingerprint, id, secret);
        await Until(() => host.Sessions.Length == 1); var original = host.Select(id, "files");
        await lan.Send(Wire.Object(("type", "event"), ("feature", "battery"), ("data", Wire.Object(("level", 73), ("charging", true)))));
        await Until(() => host.Devices()[0].State["battery"]?.Num("level") == 73); Check(true, "Android event updates live state");
        await lan.Send(Wire.Object(("type", "ping"))); Check((await lan.Read()).Str("type") == "pong", "same-session heartbeat");
        var command = original.CommandAsync("find", "ring"); var request = await lan.Read();
        Check(request.Str("type") == "command" && request.Str("feature") == "find" && Wire.IsHex(request.Str("id"), 32), "Android command schema");
        await lan.Result(request.Str("id"), Wire.Object(("state", "ringing"))); Check((await command).Str("state") == "ringing", "operation receives phone result");
        var alternate = new TcpListener(IPAddress.Loopback, 0); alternate.Start();
        var accept = Task.Run(async () => { using var c = await alternate.AcceptTcpClientAsync(); await host.AcceptStreamAsync(c.GetStream(), "bluetooth", "hello"); });
        using var bluetooth = await Peer.Login(((IPEndPoint)alternate.LocalEndpoint).Port, host.Store.Fingerprint, id, secret);
        await Until(() => host.Sessions.Length == 2);
        Check(host.Select(id, "find").Kind == "bluetooth" && host.Select(id, "files") == original, "Bluetooth control and LAN bulk routing");
        var onLan = original.CommandAsync("find", "stop"); var onLanRequest = await lan.Read();
        await bluetooth.Result(onLanRequest.Str("id"), Wire.Object(("state", "wrong link"))); await Task.Delay(80); Check(!onLan.IsCompleted, "results cannot cross session generations");
        await lan.Result(onLanRequest.Str("id"), Wire.Object(("state", "stopped"))); await onLan;
        int handled = 0; host.DesktopCapabilities = _ => new JsonArray(Wire.Object(("id", "sound"), ("status", "available"), ("reason", "")));
        host.RequestHandler = (_, _, _, _, _) => { Interlocked.Increment(ref handled); return Task.FromResult(Wire.Object(("state", "applied"))); };
        var replay = Wire.Object(("type", "request"), ("id", Wire.Id()), ("feature", "sound"), ("action", "volume"), ("data", Wire.Object(("source", "desktop"), ("volume", 50))));
        await lan.Send(replay); await lan.Read(); await lan.Send(replay); await lan.Read(); Check(handled == 1, "desktop request replay has no duplicate side effect");
        var token = host.ReserveStream(original, "audio_hello", async (stream, ct) => { await Wire.WriteAsync(stream, Wire.Object(("type", "ready")), ct); });
        using (var side = await Peer.Open(host.Port, host.Store.Fingerprint)) { await side.Send(Wire.Object(("type", "audio_hello"), ("version", 1), ("device_id", id), ("token", token))); Check((await side.Read()).Str("type") == "ready", "one-use side stream token"); }
        using (var side = await Peer.Open(host.Port, host.Store.Fingerprint)) { await side.Send(Wire.Object(("type", "audio_hello"), ("version", 1), ("device_id", id), ("token", token))); await Reject(() => side.Read(), "stream token replay rejected"); }
        var filePath = Path.Combine(root, "large.bin"); var bytes = RandomNumberGenerator.GetBytes(150000); File.WriteAllBytes(filePath, bytes);
        var sendFile = TransferReceiver.SendAsync(original, filePath); using var received = new MemoryStream(); string? fileId = null; string? digest = null;
        while (true)
        {
            var part = await lan.Read(); Check(part.Str("feature") == "files", "transfer message feature"); fileId ??= part.Str("id"); Check(part.Str("id") == fileId, "file chunks keep operation ID");
            if (part.Str("action") == "begin") digest = part.Data().Str("sha256");
            if (part.Str("action") == "chunk") received.Write(Convert.FromBase64String(part.Data().Str("data")));
            if (part.Str("action") == "end") { await lan.Result(fileId, Wire.Object(("state", "received"))); break; }
        }
        await sendFile; Check(bytes.SequenceEqual(received.ToArray()) && digest == Convert.ToHexStringLower(SHA256.HashData(bytes)), "multi-chunk Windows-to-Android transfer integrity");
        var pending = original.CommandAsync("find", "ring"); await lan.Read(); lan.Dispose();
        await Reject(async () => await pending, "disconnect fails original pending work");
        await Until(() => host.Sessions.Length == 1); Check(host.Select(id, "files").Kind == "bluetooth" && host.Devices()[0].State["battery"] != null, "new work fails over and surviving link retains state");
        host.Unpair(id); await Until(() => host.Sessions.Length == 0); Check(host.Devices().Length == 0, "unpair closes sessions and removes credentials");
        bluetooth.Dispose(); alternate.Stop(); await accept;
    }
    private static async Task FeatureStateAsync(string root)
    {
        await using var host = new UnityHost(new IdentityStore(Path.Combine(root, "features")));
        var id = Wire.Id(); var secret = Wire.Secret(); host.Store.Trust(new(id, "Feature test phone", secret, [])); host.Start(0, false);
        using var peer = await Peer.Login(host.Port, host.Store.Fingerprint, id, secret);
        await Until(() => host.Sessions.Length == 1); var session = host.Sessions[0];
        var received = new System.Collections.Concurrent.ConcurrentQueue<(string Feature, JsonObject Data)>();
        host.Received += (_, feature, data) => received.Enqueue((feature, (JsonObject)data.DeepClone()));
        async Task Applied()
        {
            await peer.Send(Wire.Object(("type", "ping")));
            Check((await peer.Read()).Str("type") == "pong", "feature session remains responsive");
        }
        async Task Event(string feature, JsonObject data)
        {
            await peer.Send(Wire.Object(("type", "event"), ("feature", feature), ("data", data)));
        }
        var incoming = Wire.Object(("id", "42"), ("address", "+15550123"), ("body", "Phone → Windows 📱"), ("timestamp", 1000));
        var player = Wire.Object(("origin", "phone"), ("session", Wire.Id()), ("title", "Phone track"), ("artist", "Artist"), ("position", 1000), ("duration", 60000), ("actions", new JsonArray("play", "pause", "seek")));
        var events = new (string Feature, JsonObject Data)[]
        {
            ("network", Wire.Object(("kind", "wifi"), ("signal", 4), ("internet", true))),
            ("urls", Wire.Object(("url", "https://example.org/shared"))),
            ("clipboard", Wire.Object(("text", "Shared text 📱"))),
            ("find", Wire.Object(("state", "ringing"))),
            ("sound", Wire.Object(("origin", "phone"), ("available", true), ("volume", 35), ("muted", false))),
            ("media", Wire.Object(("origin", "phone"), ("players", new JsonArray(player)))),
            ("sms", Wire.Object(("revision", 1), ("incoming", incoming))),
            ("call_control", Wire.Object(("backend", "android_telecom"), ("calls", new JsonArray(Wire.Object(("id", Wire.Id()), ("state", "incoming"), ("number", "+15550123")))))),
            ("call_notifications", Wire.Object(("state", "ringing"), ("number", "+15550123"))),
            ("ringer", Wire.Object(("mode", "vibrate")))
        };
        foreach (var (feature, data) in events) await Event(feature, data);
        await Applied();
        var snapshot = host.Devices()[0].State;
        foreach (var (feature, data) in events) Check(JsonNode.DeepEquals(snapshot[feature], data), feature + " event retains the Android payload");
        Check(received.Any(x => x.Feature == "sms" && x.Data["incoming"] is JsonObject sms && sms.Str("body") == incoming.Str("body")), "incoming SMS uses the Android single-message object for alerts");
        snapshot["clipboard"]!["text"] = "Edited view";
        Check(host.Devices()[0].State["clipboard"].Str("text") == "Shared text 📱", "device snapshots cannot mutate live feature state");
        await Event("notifications", Wire.Object(("id", "one"), ("title", "First"), ("text", "Original")));
        await Event("notifications", Wire.Object(("id", "two"), ("title", "Second"), ("text", "Retained")));
        await Event("notifications", Wire.Object(("id", "one"), ("title", "Updated"), ("text", "Replacement")));
        await Applied();
        var notices = host.Devices()[0].State["notifications"]!.AsObject();
        Check(notices.Count == 2 && notices["one"].Str("title") == "Updated" && notices["two"].Str("text") == "Retained", "notification updates replace only their matching item");
        await Event("notifications", Wire.Object(("id", "one"), ("removed", true))); await Applied();
        notices = host.Devices()[0].State["notifications"]!.AsObject();
        Check(notices.Count == 1 && notices.ContainsKey("two"), "notification dismissal preserves other notifications");
        await host.SetEnabledAsync(id, "notifications", false); await peer.Read();
        Check(host.Devices()[0].State["notifications"] == null && !session.Allows("notifications", true), "Windows permission revocation clears shared notification data");
        await Reject(() => session.CommandAsync("notifications", "list"), "disabled features cannot submit phone commands");
        var capabilities = (JsonArray)Caps.DeepClone();
        capabilities.First(c => c.Str("id") == "sound")!["status"] = "permission_required";
        capabilities.First(c => c.Str("id") == "find")!["status"] = "user_action_required";
        await peer.Send(Wire.Object(("type", "capabilities"), ("capabilities", capabilities))); await Applied();
        Check(host.Devices()[0].State["sound"] == null && !session.Allows("sound", true), "Android permission loss clears stale phone volume and blocks controls");
        Check(!session.Allows("find") && session.Allows("find", true), "user-action features require an explicit user command");
        var command = session.CommandAsync("find", "ring"); var request = await peer.ReadCommand();
        await peer.Result(request.Str("id"), Wire.Object(("state", "ringing")));
        Check((await command).Str("state") == "ringing", "user-action-required commands can finish on their original connection");
        command = session.CommandAsync("sms", "send", Wire.Object(("address", "+15550123"), ("body", "Test")));
        request = await peer.ReadCommand();
        await peer.Send(Wire.Object(("type", "result"), ("id", request.Str("id")), ("ok", false), ("error", "Phone SMS permission denied"), ("data", new JsonObject())));
        await Reject(() => command, "phone feature failure reaches the waiting operation");
        await Applied();
    }
    private static async Task KotlinAsync(string root, string java, string jar, string json)
    {
        await using var host = new UnityHost(new IdentityStore(Path.Combine(root, "kotlin"))); host.Start(0, false); host.OpenPairing();
        var id = Wire.Id(); host.Changed += () => { foreach (var p in host.PendingPairs) if (p.Id == id) host.Approve(p.Id, true); };
        Task<string> Run(string secret)
        {
            var info = new ProcessStartInfo(java) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true };
            foreach (var arg in new[] { "-cp", jar + Path.PathSeparator + json, "org.unityconnect.companion.AndroidPeerKt", host.Port.ToString(), host.Store.Fingerprint, id, secret }) info.ArgumentList.Add(arg);
            return RunProcess(info);
        }
        var pair = await Run("pair"); Check(pair.StartsWith("PAIRED:"), "actual Kotlin Protocol.kt + PinnedTls.kt pairing"); var secret = pair.Trim()[7..];
        var run = Run(secret); await Until(() => host.Sessions.Length == 1); var session = host.Sessions[0];
        var result = await session.CommandAsync("find", "ring"); Check(result.Str("state") == "ringing", "actual Kotlin framed command and response");
        var path = Path.Combine(root, "kotlin-transfer.txt"); File.WriteAllText(path, "Windows → Android 📱\n" + new string('x', 100000));
        await TransferReceiver.SendAsync(session, path);
        var output = await run; Check(output.Contains("INTEROP_OK"), "Kotlin pinned TLS 1.3, HMAC, Unicode and multi-chunk file receive");
        await Until(() => Directory.Exists(Path.Combine(host.Store.Root, "Received")) && Directory.EnumerateFiles(Path.Combine(host.Store.Root, "Received"), "*-android.txt").Any());
        Check(true, "Kotlin Android-to-Windows verified file event");
    }
    private static async Task<string> RunProcess(ProcessStartInfo info)
    {
        using var process = Process.Start(info)!; var stdout = process.StandardOutput.ReadToEndAsync(); var stderr = process.StandardError.ReadToEndAsync();
        await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(30)); var output = await stdout; var error = await stderr;
        if (process.ExitCode != 0) throw new Exception("Kotlin peer failed: " + error + output); return output;
    }
    private sealed class Peer(TcpClient client, SslStream stream) : IDisposable
    {
        public static async Task<Peer> Open(int port, string fingerprint)
        {
            var client = new TcpClient(); await client.ConnectAsync(IPAddress.Loopback, port);
            var tls = new SslStream(client.GetStream(), false, (_, cert, _, _) => cert != null && Convert.ToHexStringLower(SHA256.HashData(cert.GetRawCertData())) == fingerprint);
            try { await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = "Unity Windows", EnabledSslProtocols = SslProtocols.Tls13 }); return new(client, tls); }
            catch { tls.Dispose(); client.Dispose(); throw; }
        }
        public static async Task<Peer> Login(int port, string pin, string id, string secret)
        {
            var peer = await Open(port, pin); await peer.Send(Hello(id)); var challenge = await peer.Read();
            var mac = Convert.ToHexStringLower(HMACSHA256.HashData(Convert.FromHexString(secret), Encoding.UTF8.GetBytes($"unity-v1:{id}:{challenge.Str("nonce")}")));
            await peer.Send(Wire.Object(("type", "proof"), ("mac", mac)));
            Check((await peer.Read()).Str("type") == "ready", "authenticated TLS ready"); Check((await peer.Read()).Str("type") == "capabilities", "desktop capabilities after ready"); return peer;
        }
        public async Task Send(JsonObject value)
        {
            var bytes = Encoding.UTF8.GetBytes(value.ToJsonString()); var header = new byte[4]; BinaryPrimitives.WriteInt32BigEndian(header, bytes.Length);
            await stream.WriteAsync(header); await stream.WriteAsync(bytes); await stream.FlushAsync();
        }
        public async Task<JsonObject> Read()
        {
            using var ct = new CancellationTokenSource(5000); var header = new byte[4]; await stream.ReadExactlyAsync(header, ct.Token);
            var length = BinaryPrimitives.ReadInt32BigEndian(header); if (length is <= 0 or > 524288) throw new Exception("Invalid peer frame");
            var bytes = new byte[length]; await stream.ReadExactlyAsync(bytes, ct.Token); return JsonNode.Parse(bytes)!.AsObject();
        }
        public Task Result(string id, JsonObject data) => Send(Wire.Object(("type", "result"), ("id", id), ("ok", true), ("error", ""), ("data", data)));
        public async Task<JsonObject> ReadCommand() { JsonObject value; do { value = await Read(); } while (value.Str("type") != "command"); return value; }
        public async Task SendBinary(byte[] bytes) { byte[] length = new byte[4]; BinaryPrimitives.WriteInt32BigEndian(length, bytes.Length); await stream.WriteAsync(length); await stream.WriteAsync(bytes); await stream.FlushAsync(); }
        public async Task<byte[]> ReadBinary() { using var ct = new CancellationTokenSource(5000); var header = new byte[4]; await stream.ReadExactlyAsync(header, ct.Token); var n = BinaryPrimitives.ReadInt32BigEndian(header); if (n is <= 0 or > 262144) throw new Exception("Invalid JPEG packet"); var bytes = new byte[n]; await stream.ReadExactlyAsync(bytes, ct.Token); return bytes; }
        public async Task<byte[]> ReadAudio() { using var ct = new CancellationTokenSource(5000); var bytes = new byte[AudioPackets.PacketBytes]; await stream.ReadExactlyAsync(bytes, ct.Token); return bytes; }
        public void Dispose() { stream.Dispose(); client.Dispose(); }
    }
    private sealed class SlowReadStream(Stream source) : Stream
    {
        public override bool CanRead => true; public override bool CanSeek => false; public override bool CanWrite => false;
        public override long Length => source.Length; public override long Position { get => source.Position; set => throw new NotSupportedException(); }
        public override int Read(byte[] b, int o, int c) => source.Read(b, o, Math.Min(c, 1));
        public override ValueTask<int> ReadAsync(Memory<byte> b, CancellationToken ct = default) => source.ReadAsync(b[..Math.Min(b.Length, 1)], ct);
        public override void Flush() { }
        public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException(); public override void SetLength(long l) => throw new NotSupportedException(); public override void Write(byte[] b, int o, int c) => throw new NotSupportedException();
    }
}
