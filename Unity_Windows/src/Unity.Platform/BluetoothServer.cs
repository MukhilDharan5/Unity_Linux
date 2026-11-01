using Unity.Core;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace Unity.Platform;

public sealed class BluetoothServer(UnityHost host) : IDisposable
{
    private readonly List<(RfcommServiceProvider Provider, StreamSocketListener Listener)> services = [];
    private readonly CancellationTokenSource life = new();
    public string Status { get; private set; } = "Not started";
    public async Task StartAsync()
    {
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter == null || !adapter.IsClassicSupported) throw new IOException("No Bluetooth Classic adapter");
            for (int index = 1; index <= 3; index++)
            {
                var type = index == 1 ? "hello" : index == 2 ? "audio_hello" : "screen_hello";
                var uuid = Guid.Parse($"ab049870-6fb6-4d30-9b3c-19077027100{index}");
                var provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(uuid));
                var listener = new StreamSocketListener();
                services.Add((provider, listener));
                listener.ConnectionReceived += async (_, args) =>
                {
                    try { using var socket = args.Socket; using var duplex = new Duplex(socket.InputStream.AsStreamForRead(), socket.OutputStream.AsStreamForWrite());
                        await host.AcceptStreamAsync(duplex, "bluetooth", type, life.Token); }
                    catch (Exception e) { host.Report("Bluetooth: " + e.Message); }
                };
                await listener.BindServiceNameAsync(provider.ServiceId.AsString(), SocketProtectionLevel.BluetoothEncryptionWithAuthentication);
                using var writer = new DataWriter(); writer.WriteByte(0x25); var name = "Unity " + (index == 1 ? "Control" : index == 2 ? "Audio" : "Screen");
                writer.WriteByte((byte)name.Length); writer.WriteString(name); provider.SdpRawAttributes[0x100] = writer.DetachBuffer();
                provider.StartAdvertising(listener, false);
            }
            Status = string.Join(":", BitConverter.GetBytes(adapter.BluetoothAddress).Take(6).Reverse().Select(b => b.ToString("X2")));
        }
        catch (Exception e) { DisposeServices(); Status = "Unavailable: " + e.Message; host.Report("Bluetooth " + Status); }
    }
    private void DisposeServices() { foreach (var (provider, listener) in services) { try { provider.StopAdvertising(); } catch { } listener.Dispose(); } services.Clear(); }
    public void Dispose() { life.Cancel(); DisposeServices(); life.Dispose(); }
    private sealed class Duplex(Stream input, Stream output) : Stream
    {
        public override bool CanRead => true; public override bool CanWrite => true; public override bool CanSeek => false;
        public override long Length => throw new NotSupportedException(); public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override int Read(byte[] b, int o, int c) => input.Read(b, o, c);
        public override ValueTask<int> ReadAsync(Memory<byte> b, CancellationToken ct = default) => input.ReadAsync(b, ct);
        public override void Write(byte[] b, int o, int c) => output.Write(b, o, c);
        public override ValueTask WriteAsync(ReadOnlyMemory<byte> b, CancellationToken ct = default) => output.WriteAsync(b, ct);
        public override void Flush() => output.Flush(); public override Task FlushAsync(CancellationToken ct) => output.FlushAsync(ct);
        public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException(); public override void SetLength(long n) => throw new NotSupportedException();
        protected override void Dispose(bool disposing) { if (disposing) { input.Dispose(); output.Dispose(); } base.Dispose(disposing); }
    }
}
