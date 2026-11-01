using System.Buffers.Binary;
using System.Threading.Channels;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using Unity.Core;

namespace Unity.Platform;

/// <summary>Assembles capture callbacks without padding partial samples or blocking WASAPI.</summary>
public sealed class AudioPackets
{
    public const int PcmBytes = 1920, PacketBytes = PcmBytes + 8, Capacity = 4, MaxAgeMs = 60;
    public sealed record Packet(byte[] Bytes, long CapturedAt);
    private readonly Channel<Packet> packets = Channel.CreateBounded<Packet>(new BoundedChannelOptions(Capacity)
    { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true, SingleWriter = true, AllowSynchronousContinuations = false });
    private byte[] pending = new byte[PacketBytes];
    private int filled;
    private ulong sequence;
    private long pendingAt;
    public void Add(ReadOnlySpan<byte> pcm, long now, bool discontinuity = false)
    {
        // Never join a pre-stall fragment to new audio after a capture discontinuity.
        if (discontinuity || (filled > 0 && now - pendingAt > MaxAgeMs)) filled = 0;
        while (!pcm.IsEmpty)
        {
            if (filled == 0) pendingAt = now;
            var count = Math.Min(PcmBytes - filled, pcm.Length);
            pcm[..count].CopyTo(pending.AsSpan(8 + filled)); filled += count; pcm = pcm[count..];
            if (filled != PcmBytes) continue;
            BinaryPrimitives.WriteUInt64BigEndian(pending, sequence++);
            packets.Writer.TryWrite(new(pending, pendingAt)); pending = new byte[PacketBytes]; filled = 0;
        }
    }
    public bool TryRead(long now, out Packet packet)
    {
        while (packets.Reader.TryRead(out var next))
            if (now - next.CapturedAt <= MaxAgeMs) { packet = next; return true; }
        packet = null!; return false;
    }
    public void Complete(Exception? error = null) => packets.Writer.TryComplete(error);
    public async ValueTask<Packet> ReadAsync(CancellationToken ct)
    {
        while (await packets.Reader.WaitToReadAsync(ct).ConfigureAwait(false))
            if (TryRead(Environment.TickCount64, out var packet)) return packet;
        throw new IOException("Windows audio capture stopped");
    }
}

public static class AudioStream
{
    // Keeping a silent render stream open makes loopback callbacks continue when no
    // app is playing. It changes neither endpoint volume nor other apps' sessions.
    private sealed class Silence : IWaveProvider
    {
        public WaveFormat WaveFormat { get; } = new(48000, 16, 2);
        public int Read(Span<byte> buffer) { buffer.Clear(); return buffer.Length; }
    }
    public static async Task RunAsync(Stream stream, Action started, CancellationToken ct)
    {
        var packets = new AudioPackets();
        using var captureLife = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var capture = Task.Run(() => CaptureAsync(packets, captureLife.Token));
        try { await SendAsync(stream, packets, started, captureLife.Token).ConfigureAwait(false); }
        finally { captureLife.Cancel(); await capture.ConfigureAwait(false); }
    }
    private static async Task CaptureAsync(AudioPackets packets, CancellationToken ct)
    {
        try
        {
            using var devices = new MMDeviceEnumerator();
            while (!ct.IsCancellationRequested)
            {
                using var endpoint = devices.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                await using var capture = new WasapiRecorderBuilder().WithDevice(endpoint).WithLoopbackCapture()
                    .WithFormat(new WaveFormat(48000, 16, 2)).WithBufferLength(20).WithMmcssThreadPriority().Build();
                await using var keepAlive = new WasapiPlayerBuilder().WithDevice(endpoint).WithLatency(20).WithMmcssThreadPriority().Build();
                var format = capture.WaveFormat;
                Wire.Require(format.SampleRate == 48000 && format.Channels == 2 && format.BitsPerSample == 16 && format.Encoding == WaveFormatEncoding.Pcm,
                    "Windows output cannot supply 48 kHz stereo PCM");
                bool reset = true;
                var stopped = new TaskCompletionSource<Exception>(TaskCreationOptions.RunContinuationsAsynchronously);
                capture.DataAvailable += (bytes, flags, _, _) =>
                {
                    packets.Add(bytes, Environment.TickCount64, reset || (flags & AudioClientBufferFlags.DataDiscontinuity) != 0); reset = false;
                };
                capture.RecordingStopped += (_, e) => stopped.TrySetResult(e.Exception ?? new IOException("Windows audio output stopped"));
                keepAlive.PlaybackStopped += (_, e) => { if (e.Exception != null) stopped.TrySetResult(e.Exception); };
                keepAlive.Init(new Silence());
                capture.StartRecording(); keepAlive.Play();
                try
                {
                    while (true)
                    {
                        // A loopback client is fixed to an endpoint. Follow output changes
                        // inside the same authenticated stream, without resetting sequence.
                        await Task.WhenAny(Task.Delay(250, ct), stopped.Task).ConfigureAwait(false);
                        ct.ThrowIfCancellationRequested();
                        using var current = devices.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                        if (current.ID != endpoint.ID) break;
                        if (stopped.Task.IsCompleted) throw await stopped.Task.ConfigureAwait(false);
                    }
                }
                finally { capture.StopRecording(); keepAlive.Stop(); }
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
        catch (Exception e) { packets.Complete(new IOException("Windows audio capture failed: " + e.Message, e)); }
        finally { packets.Complete(); }
    }
    public static async Task SendAsync(Stream stream, AudioPackets packets, Action started, CancellationToken ct)
    {
        // Ready is backed by a complete captured packet, including real silence.
        var packet = await packets.ReadAsync(ct).AsTask().WaitAsync(TimeSpan.FromSeconds(10), ct).ConfigureAwait(false);
        await Wire.WriteAsync(stream, Wire.Object(("type", "ready")), ct).ConfigureAwait(false);
        bool first = true;
        while (true)
        {
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct); deadline.CancelAfter(TimeSpan.FromSeconds(1));
            await stream.WriteAsync(packet.Bytes, deadline.Token).ConfigureAwait(false);
            await stream.FlushAsync(deadline.Token).ConfigureAwait(false);
            if (first) { first = false; started(); }
            // The audio engine supplies timing. A second 10 ms timer drifts and
            // retains old sound when the sender or Wi-Fi misses a tick.
            packet = await packets.ReadAsync(ct).AsTask().WaitAsync(TimeSpan.FromSeconds(3), ct).ConfigureAwait(false);
        }
    }
}
