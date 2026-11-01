using System.Text.Json.Nodes;

namespace Unity.Platform;

public sealed class LocalTelemetryCollection : IAsyncDisposable
{
    private readonly TelemetryStorage storage;
    private readonly Func<ILocalTelemetrySource> sourceFactory;
    private readonly TimeProvider time;
    private readonly SemaphoreSlim gate = new(1);
    private readonly object stateGate = new();
    private TelemetryConsent consent;
    private CancellationTokenSource? runCancellation;
    private Task? worker;
    private Task? retentionWorker;
    private JsonObject? latest;
    private string? lastError;
    private bool disposed;
    public bool Enabled { get { lock (stateGate) return consent.Enabled; } }
    public string CsvPath => storage.CsvPath;
    public JsonObject? Latest { get { lock (stateGate) return latest?.DeepClone().AsObject(); } }
    public string Status
    {
        get
        {
            lock (stateGate) return lastError ?? (!consent.Enabled ? "Off. No local device data is being collected." :
                latest == null ? "Starting local CSV collection…" : "Saving to CSV every 5 seconds · Keeps the last 30 minutes · Last snapshot: " +
                DateTimeOffset.Parse(latest["captured_at"]!.GetValue<string>()).ToLocalTime().ToString("T"));
        }
    }
    public LocalTelemetryCollection(string stateRoot, Func<ILocalTelemetrySource>? sourceFactory = null, TimeProvider? timeProvider = null)
    {
        storage = new(stateRoot); consent = storage.LoadConsent();
        this.sourceFactory = sourceFactory ?? (() => new WindowsTelemetrySource());
        time = timeProvider ?? TimeProvider.System;
    }
    // Called at startup, honoring only the versioned approval saved by Settings.
    public async Task InitializeAsync()
    {
        await gate.WaitAsync();
        try
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (Enabled && worker == null) Start();
            else if (!Enabled) storage.Clear();
        }
        finally { gate.Release(); }
    }
    public async Task SetEnabledAsync(bool enabled)
    {
        await gate.WaitAsync();
        try
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            var next = consent with
            {
                Enabled = enabled,
                ApprovedAt = enabled ? time.GetUtcNow() : consent.ApprovedAt,
                DeviceUid = consent.DeviceUid.Length == 0 ? Guid.NewGuid().ToString("N") : consent.DeviceUid
            };
            if (enabled)
            {
                storage.SaveConsent(next);
                lock (stateGate) { consent = next; lastError = null; }
                if (worker == null) Start();
            }
            else
            {
                // Revoke in memory and stop first, even if writing the preference
                // or deleting the local CSV subsequently fails.
                lock (stateGate) { consent = next; latest = null; lastError = null; }
                await StopAsync();
                storage.Revoke(next);
            }
        }
        finally { gate.Release(); }
    }
    private void Start()
    {
        runCancellation = new();
        worker = Task.Run(() => CollectAsync(runCancellation.Token));
        retentionWorker = Task.Run(() => PruneAsync(runCancellation.Token));
    }
    private async Task CollectAsync(CancellationToken cancellation)
    {
        try
        {
            // No OS handles, callbacks, media requests or saved metrics are read
            // until the approval gate above has been passed.
            cancellation.ThrowIfCancellationRequested();
            storage.Prune(time.GetUtcNow());
            cancellation.ThrowIfCancellationRequested();
            using var source = sourceFactory();
            cancellation.ThrowIfCancellationRequested();
            // Daily counters are session-local; only the rolling CSV is saved.
            var usage = new TelemetryUsage();
            var hardware = new TelemetryRecord(consent.DeviceUid, time.GetUtcNow(), time.LocalTimeZone);
            source.ReadHardware(hardware, time.GetUtcNow());
            long? lastPoll = null;
            using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5), time);
            do
            {
                cancellation.ThrowIfCancellationRequested();
                if (ReferenceEquals(time, TimeProvider.System)) TimeZoneInfo.ClearCachedData();
                var now = time.GetUtcNow(); var tick = time.GetTimestamp(); var zone = time.LocalTimeZone;
                if (lastPoll != null && time.GetElapsedTime(lastPoll.Value, tick) < TimeSpan.FromSeconds(5)) continue;
                lastPoll = tick;
                UsageObservation observed;
                try { observed = source.ReadUsage(now); }
                catch (Exception) { observed = new(now, false, false, false, -1, null, null); }
                if (usage.ObservedAt > now) usage.MarkGap();
                else usage.Observe(observed, zone);
                var record = new TelemetryRecord(consent.DeviceUid, now, zone);
                record.CopyFields(hardware, ["device_name", "processor_model", "processor_physical_cores", "processor_logical_cores",
                    "memory_total_bytes", "memory_type", "memory_speed_mt_s", "os_name", "os_version", "os_build", "security_patch_date"]);
                await source.ReadCurrentAsync(record, now, cancellation);
                usage.Apply(record);
                cancellation.ThrowIfCancellationRequested();
                record.Data["captured_at"] = TelemetryRecord.Stamp(time.GetUtcNow());
                string? writeError = null;
                try { storage.Save(record.Data, time.GetUtcNow()); }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    // Readers such as Excel may temporarily lock the CSV. Keep
                    // polling and retry the bounded pending samples next tick.
                    writeError = "Local CSV could not be updated. Collection continues; saving will retry every 5 seconds.";
                }
                lock (stateGate) { if (!Enabled) return; latest = record.Data; lastError = writeError; }
            } while (await timer.WaitForNextTickAsync(cancellation));
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        catch (Exception)
        {
            lock (stateGate) lastError = "Local collection could not continue. Turn it off and on to retry; no data is sent.";
        }
    }
    private async Task PruneAsync(CancellationToken cancellation)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5), time);
        try
        {
            do
            {
                cancellation.ThrowIfCancellationRequested();
                try
                {
                    var now = time.GetUtcNow();
                    storage.Prune(now);
                    lock (stateGate)
                        if (latest != null && DateTimeOffset.Parse(latest["captured_at"]!.GetValue<string>()) < now - TelemetryStorage.Retention) latest = null;
                }
                catch (Exception)
                {
                    lock (stateGate) lastError = "Local CSV cleanup could not finish. Check access to Unity’s data folder; no data is sent.";
                }
            } while (await timer.WaitForNextTickAsync(cancellation));
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
    }
    private async Task StopAsync()
    {
        runCancellation?.Cancel();
        if (worker != null) await worker;
        if (retentionWorker != null) await retentionWorker;
        worker = null; retentionWorker = null; runCancellation?.Dispose(); runCancellation = null;
        lock (stateGate) latest = null;
    }
    public async ValueTask DisposeAsync()
    {
        await gate.WaitAsync();
        try { disposed = true; await StopAsync(); }
        finally { gate.Release(); }
    }
}
