using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Runtime.InteropServices;
using System.Globalization;
using Microsoft.VisualBasic.FileIO;
using Unity.Core;
using Unity.Platform;

internal static partial class Program
{
    private static async Task TelemetryAsync(string root)
    {
        var start = new DateTimeOffset(2026, 10, 27, 12, 0, 0, TimeSpan.Zero);
        var time = new CollectionTime(start);
        var path = Path.Combine(root, "local-collection");
        int factories = 0;
        FixtureTelemetry? source = null;
        ILocalTelemetrySource Factory() { factories++; return source = new(); }
        await using (var collector = new LocalTelemetryCollection(path, Factory, time))
        {
            Check(!collector.Enabled && collector.Latest == null && factories == 0 && !Directory.Exists(path), "local collection constructor is inert and defaults off");
            await collector.InitializeAsync(); time.Advance(TimeSpan.FromHours(1));
            Check(factories == 0 && !Directory.Exists(path), "startup without consent opens no sources and creates no collection files");
            await collector.SetEnabledAsync(true);
            await Until(() => collector.Latest != null);
            var first = collector.Latest!;
            var captured = first.Str("captured_at"); var uid = first.Str("device_uid");
            Check(factories == 1 && collector.Enabled && source!.HardwareReads == 1, "approval starts one collector and reads static hardware once");
            await collector.InitializeAsync();
            Check(factories == 1, "startup initialization is idempotent after approval");
            Check(first.Str("schema_version") == "2.0" && first.Str("device_id") == "laptop" && !first.Bool("is_demo") &&
                Guid.TryParseExact(uid, "N", out _) && first.Str("source_timezone") == "Etc/UTC", "snapshot has a stable app identity, UTC capture and IANA source timezone");
            Check(TelemetryRecord.CoreFields.Concat(TelemetryRecord.OptionalFields).All(f => first.ContainsKey(f) && first["field_status"]?[f] != null), "all 16 core and every documented optional field have a value or explicit status");
            Check(first.Num("charge_pct", -1) == 0 && first["field_status"]?["charge_pct"].Str("status") == "reported" &&
                first["dnd_state"] == null && first["field_status"]?["dnd_state"].Str("status") == "unsupported" &&
                first["field_status"]?["network_name"].Str("status") == "permission_denied", "zero, unsupported and denied readings stay distinct");
            Check(first["power_draw_w"] == null && first["power_measurement_kind"] == null && first["sync_transport"] == null &&
                first["field_status"]?["sync_transport"].Str("status") == "not_applicable", "no fabricated power readings or telemetry transport");
            Check(first["field_status"]!.AsObject().All(p => first[p.Key] == null ||
                p.Value.Str("source").Length > 0 && DateTimeOffset.TryParse(p.Value.Str("observed_at"), out _)), "every real reading identifies its source and observation time");
            first["device_name"] = "mutated copy";
            Check(collector.Latest!.Str("device_name") == "Fixture PC", "callers cannot mutate the collector's current snapshot");
            var csvPath = collector.CsvPath;
            Check(File.ReadAllText(csvPath).Contains("private fixture track") && !File.Exists(Path.Combine(path, "local-collection.dat")),
                "approved data is stored in a readable local CSV without a separate checkpoint");
            var initialUsageReads = source!.UsageReads; var initialCurrentReads = source.CurrentReads;
            time.Advance(TimeSpan.FromSeconds(1));
            Check(source.UsageReads == initialUsageReads && source.CurrentReads == initialCurrentReads, "native usage and core sources are not polled again before five seconds");
            time.Advance(TimeSpan.FromSeconds(4));
            await Until(() => collector.Latest!.Str("captured_at") != captured);
            var second = collector.Latest!;
            Check(second.Str("device_uid") == uid && second.Str("sample_id") != first.Str("sample_id"), "device UID stays stable and each snapshot gets a new sample ID");
            Check(second["field_status"]?["processor_model"].Str("observed_at") == captured &&
                second["field_status"]?["screen_time_total_min"].Str("observed_at") == second.Str("captured_at") &&
                second["field_status"]?["charge_pct"].Str("observed_at") == second.Str("captured_at"), "usage and core readings refresh every five seconds while cached hardware keeps its original timestamp");
            Check(CsvRows(csvPath).Count == 2 && source.CurrentReads == 2 && source.UsageReads == 2, "each five-second poll appends one complete CSV sample");
            time.Advance(TimeSpan.FromSeconds(55));
            await Until(() => collector.Latest!["field_status"]?["screen_time_total_min"].Str("observed_at") != captured);
            Check(collector.Latest!["field_status"]?["screen_time_total_min"].Str("observed_at") == collector.Latest!.Str("captured_at"), "a delayed poll refreshes usage without fabricating missed samples");
        }
        Check(source!.Disposed, "exit releases all collection sources");
        var priorUid = JsonNode.Parse(File.ReadAllText(Path.Combine(path, "local-collection-consent.json")))!.Str("DeviceUid");
        await using (var resumed = new LocalTelemetryCollection(path, Factory, time))
        {
            Check(resumed.Enabled && factories == 1, "saved approval is recognized without eagerly opening sources");
            await resumed.InitializeAsync(); await Until(() => resumed.Latest != null);
            Check(resumed.Latest!.Str("device_uid") == priorUid && factories == 2, "approved startup resumes local collection with the same device UID");
            await resumed.SetEnabledAsync(false);
            var reads = source!.UsageReads; time.Advance(TimeSpan.FromMinutes(5));
            Check(!resumed.Enabled && resumed.Latest == null && source.Disposed && source.UsageReads == reads &&
                !File.Exists(resumed.CsvPath) && !File.Exists(resumed.CsvPath + ".tmp") && !File.Exists(Path.Combine(path, "local-collection.dat")), "revocation stops polling and cleanup, clears memory and deletes the CSV and legacy checkpoint");
        }
        await using (var disabled = new LocalTelemetryCollection(path, Factory, time))
        {
            await disabled.InitializeAsync();
            Check(!disabled.Enabled && factories == 2, "revocation remains off across restart");
        }
        var consentPath = Path.Combine(path, "local-collection-consent.json");
        foreach (var invalid in new[] { "broken json", "{}", "{\"Enabled\":true}",
            JsonSerializer.Serialize(new { ConsentVersion = 99, SchemaVersion = "2.0", Enabled = true, ApprovedAt = start, DeviceUid = Guid.NewGuid().ToString("N") }) })
        {
            File.WriteAllText(consentPath, invalid);
            await using var invalidCollector = new LocalTelemetryCollection(path, Factory, time);
            await invalidCollector.InitializeAsync();
            Check(!invalidCollector.Enabled && factories == 2, "missing, damaged or incompatible consent fails closed");
        }
        var slow = new FixtureTelemetry { BlockCurrent = true };
        var slowPath = Path.Combine(root, "collection-revocation-race");
        await using (var collector = new LocalTelemetryCollection(slowPath, () => slow, time))
        {
            await collector.SetEnabledAsync(true); await Until(() => slow.CurrentReads > 0);
            var reads = slow.UsageReads; time.Advance(TimeSpan.FromSeconds(1));
            Check(slow.CurrentReads == 1 && slow.UsageReads == reads, "a blocked poll cannot start overlapping native reads");
            await collector.SetEnabledAsync(false).WaitAsync(TimeSpan.FromSeconds(2));
            Check(slow.Disposed && collector.Latest == null && !File.Exists(collector.CsvPath), "revocation cancels an in-flight read before it can write a CSV sample");
        }
        var record = new TelemetryRecord(Guid.NewGuid().ToString("N"), start, TimeZoneInfo.Utc);
        record.Set("network_connected", false, start, "fixture offline");
        record.Set("screen_time_apps", "", start, "fixture known empty app list");
        Check(record.Data["network_connected"]!.GetValue<bool>() == false && record.Data.Str("screen_time_apps") == "" &&
            record.Data["field_status"]?["screen_time_apps"].Str("status") == "reported", "known false and known empty text remain real readings");
        await Reject(() => { record.Set("power_draw_w", double.NaN, start, "fixture"); return Task.CompletedTask; }, "non-finite measurements are rejected");
        await using (var stillDisabled = new LocalTelemetryCollection(path, Factory, time))
            Check(!stillDisabled.Enabled, "collection never grants itself approval during validation");
        UsageChecks(start);
        BatteryConversionChecks(start);
        CsvStorageChecks(root, start);
        await CsvRetentionAsync(root, start);
    }
    private static List<Dictionary<string, string>> CsvRows(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var reader = new StreamReader(stream, new UTF8Encoding(false, true));
        using var parser = new TextFieldParser(reader) { HasFieldsEnclosedInQuotes = true, TrimWhiteSpace = false };
        parser.SetDelimiters(",");
        var header = parser.ReadFields() ?? throw new Exception("CSV has no header");
        var result = new List<Dictionary<string, string>>();
        while (!parser.EndOfData)
        {
            var cells = parser.ReadFields() ?? throw new Exception("CSV record missing");
            if (cells.Length != header.Length) throw new Exception("CSV record has the wrong cell count");
            result.Add(header.Select((field, index) => (field, cells[index])).ToDictionary(p => p.field, p => p.Item2));
        }
        return result;
    }
    private static void CsvStorageChecks(string root, DateTimeOffset start)
    {
        var directory = Path.Combine(root, "csv-roundtrip"); var storage = new TelemetryStorage(directory);
        var first = new TelemetryRecord("fixture", start, TimeZoneInfo.Utc);
        const string title = "  Café, \"quoted\"\r\nCRLF\nLF\rCR  ";
        first.Set("media_title", title, start, "fixture media source");
        first.Set("power_draw_w", 12.5, start, "fixture battery rate"); first.Data["power_measurement_kind"] = "battery_discharge";
        first.Set("screen_time_apps", "", start, "fixture known empty usage");
        first.Set("network_connected", false, start, "fixture disconnected");
        first.Set("app_usage", new JsonArray(new JsonObject { ["app_id"] = "app1", ["app_name"] = "Exact:name;[with] \"quotes\"", ["minutes"] = 1 }), start, "fixture usage");
        var originalCulture = CultureInfo.CurrentCulture;
        try { CultureInfo.CurrentCulture = CultureInfo.GetCultureInfo("fr-FR"); storage.Save(first.Data, start); }
        finally { CultureInfo.CurrentCulture = originalCulture; }
        var raw = File.ReadAllText(storage.CsvPath);
        var row = CsvRows(storage.CsvPath).Single();
        Check(raw.StartsWith(string.Join(',', TelemetryRecord.CoreFields) + ",schema_version,") && row.Count == TelemetryStorage.Columns.Length,
            "CSV starts with all sixteen canonical core columns and includes every optional field and metadata column");
        Check(row["media_title"] == title && raw.Contains("\"\"quoted\"\""), "CSV preserves Unicode, whitespace, commas, quotes and each embedded newline style");
        Check(row["power_draw_w"] == "12.5" && row["network_connected"] == "false", "CSV numbers and booleans are culture independent");
        var statuses = JsonNode.Parse(row["field_status"])!;
        Check(row["dnd_state"] == "" && statuses["dnd_state"].Str("status") == "unsupported" &&
            row["screen_time_apps"] == "" && statuses["screen_time_apps"].Str("status") == "reported", "CSV status JSON distinguishes an unavailable metric from known empty text");
        Check(JsonNode.Parse(row["app_usage"])!.AsArray()[0].Str("app_name") == "Exact:name;[with] \"quotes\"" &&
            row["sample_id"] == first.Data.Str("sample_id"), "structured app names and stable sample IDs survive CSV storage");
        storage.Save(first.Data, start.AddSeconds(1));
        Check(CsvRows(storage.CsvPath).Count == 1, "saving an existing sample ID does not duplicate a CSV row");
        var second = new TelemetryRecord("fixture", start.AddSeconds(5), TimeZoneInfo.Utc);
        var reloaded = new TelemetryStorage(directory); reloaded.Save(second.Data, start.AddSeconds(5));
        Check(CsvRows(storage.CsvPath).Count == 2 && CsvRows(storage.CsvPath)[0]["media_title"] == title,
            "restart preserves recent CSV samples and exact multiline field values");
        reloaded.Prune(start.AddMinutes(30));
        Check(CsvRows(storage.CsvPath).Count == 2, "samples exactly thirty minutes old remain in the rolling window");
        reloaded.Prune(start.AddMinutes(30).AddTicks(1));
        Check(CsvRows(storage.CsvPath).Count == 1 && CsvRows(storage.CsvPath)[0]["sample_id"] == second.Data.Str("sample_id"),
            "retention removes only samples older than thirty minutes");
        var startupPrune = new TelemetryStorage(directory); startupPrune.Prune(start.AddMinutes(31));
        Check(CsvRows(storage.CsvPath).Count == 0, "startup prunes expired CSV rows before any new native collection");
        File.WriteAllText(Path.Combine(directory, "local-collection.dat"), "old fixture checkpoint");
        File.WriteAllText(Path.Combine(directory, "local-collection.dat.tmp"), "old fixture checkpoint");
        File.WriteAllText(storage.CsvPath + ".tmp", "abandoned fixture CSV write");
        startupPrune.Prune(start.AddMinutes(31));
        Check(!File.Exists(Path.Combine(directory, "local-collection.dat")) && !File.Exists(Path.Combine(directory, "local-collection.dat.tmp")) &&
            !File.Exists(storage.CsvPath + ".tmp"), "migration and cleanup remove old checkpoints and abandoned CSV temporary data");
        Check(Directory.GetFiles(directory).Select(Path.GetFileName).SequenceEqual(new[] { "local-collection.csv" }), "CSV retention leaves no separate long-lived measurement history");
        var retryDirectory = Path.Combine(root, "csv-cleanup-retry"); var retry = new TelemetryStorage(retryDirectory);
        retry.Save(first.Data, start);
        bool failed = false;
        using (var locked = new FileStream(retry.CsvPath, FileMode.Open, FileAccess.Read, FileShare.None))
        {
            try { retry.Prune(start.AddMinutes(31)); }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { failed = true; }
        }
        retry.Prune(start.AddMinutes(31));
        Check(failed && CsvRows(retry.CsvPath).Count == 0, "cleanup retries an atomic rewrite after a temporary CSV file lock is released");
    }
    private static async Task CsvRetentionAsync(string root, DateTimeOffset start)
    {
        var time = new CollectionTime(start); var source = new FixtureTelemetry();
        await using var collector = new LocalTelemetryCollection(Path.Combine(root, "csv-retention"), () => source, time);
        await collector.SetEnabledAsync(true); await Until(() => collector.Latest != null);
        using (var locked = new FileStream(collector.CsvPath, FileMode.Open, FileAccess.Read, FileShare.None))
        {
            time.Advance(TimeSpan.FromSeconds(5));
            await Until(() => source.CurrentReads == 2 && collector.Status.Contains("could"));
        }
        time.Advance(TimeSpan.FromSeconds(5));
        await Until(() => CsvRows(collector.CsvPath).Count == 3);
        Check(source.CurrentReads == 3, "polling continues during a temporary file lock and pending CSV samples are saved after release");
        source.BlockCurrent = true;
        time.Advance(TimeSpan.FromSeconds(5)); await Until(() => source.CurrentReads == 4);
        time.Advance(TimeSpan.FromMinutes(31));
        await Until(() => collector.Latest == null && CsvRows(collector.CsvPath).Count == 0);
        Check(source.CurrentReads == 4 && CsvRows(collector.CsvPath).Count == 0,
            "independent cleanup removes expired CSV samples even while a native poll is blocked");
        await collector.SetEnabledAsync(false);
        Check(!File.Exists(collector.CsvPath), "opting out deletes even an empty retained CSV and stops cleanup");
    }
    private static void BatteryConversionChecks(DateTimeOffset now)
    {
        TelemetryRecord Empty() => new("fixture", now, TimeZoneInfo.Utc);
        var unknown = Empty();
        WindowsTelemetrySource.ApplySystemPowerStatus(unknown, new() { AcLine = 255, Flags = 255, Percent = 255, Lifetime = uint.MaxValue }, now);
        Check(unknown.Data["battery_present"] == null && unknown.Data["external_power_connected"] == null &&
            unknown.Data["charge_pct"] == null && unknown.Data["battery_remaining_sec"] == null, "Windows unknown battery sentinels never become zero or false");
        var absent = Empty();
        var readDevices = WindowsTelemetrySource.ApplySystemPowerStatus(absent, new() { AcLine = 1, Flags = 128, Percent = 255, Lifetime = uint.MaxValue }, now);
        Check(!readDevices && absent.Data["battery_present"]!.GetValue<bool>() == false && absent.Data.Bool("external_power_connected") &&
            TelemetryRecord.BatteryFields.All(f => absent.Data[f] == null && absent.Data["field_status"]?[f].Str("status") == "not_applicable"), "a battery-free PC has no fabricated battery measurements");
        var info = new WindowsTelemetrySource.BatteryInfo { Capabilities = 0x80000000, Design = 50000, Full = 52000, Cycles = 40 };
        var state = new WindowsTelemetrySource.BatteryStatus { PowerState = 2, Capacity = 26000, Voltage = 12000, Rate = -12345 };
        var discharging = Empty(); WindowsTelemetrySource.ApplySingleBattery(discharging, info, state, now);
        Check(discharging.Data.Str("charging_state") == "Discharging" && discharging.Data.Num("charge_pct") == 50 &&
            Math.Abs(discharging.Data["power_draw_w"]!.GetValue<double>() - 12.345) < 0.0001 && discharging.Data.Str("power_measurement_kind") == "battery_discharge" &&
            discharging.Data["battery_voltage_v"]!.GetValue<double>() == 12 && discharging.Data["battery_health_pct"]!.GetValue<double>() == 104,
            "battery conversions preserve units, discharge direction and health above one hundred percent");
        var notCharging = Empty(); state.PowerState = 1; state.Rate = int.MinValue;
        WindowsTelemetrySource.ApplySingleBattery(notCharging, info, state, now);
        Check(notCharging.Data.Str("charging_state") == "Not Charging" && notCharging.Data["power_draw_w"] == null && notCharging.Data["power_measurement_kind"] == null,
            "plugged-in is distinct from charging and an unknown power rate stays unavailable");
        var charging = Empty(); state.PowerState = 5; state.Rate = 20000;
        WindowsTelemetrySource.ApplySingleBattery(charging, info, state, now);
        Check(charging.Data.Str("charging_state") == "Charging" && charging.Data.Str("power_measurement_kind") == "battery_charge" &&
            charging.Data["power_draw_w"]!.GetValue<double>() == 20 && charging.Data["battery_design_capacity_mah"] == null && charging.Data["battery_full_charge_capacity_mah"] == null,
            "net battery charging power is labeled correctly and mWh capacities are never relabeled mAh");
        var relative = Empty(); info.Capabilities |= 0x40000000;
        WindowsTelemetrySource.ApplySingleBattery(relative, info, state, now);
        Check(relative.Data["power_draw_w"] == null && relative.Data["power_measurement_kind"] == null && relative.Data.Num("charge_pct") == 50,
            "relative battery rates cannot be mistaken for measured watts");
        var full = Empty(); state.PowerState = 1; state.Capacity = info.Full;
        WindowsTelemetrySource.ApplySingleBattery(full, info, state, now);
        Check(full.Data.Str("charging_state") == "Full", "a known full battery is distinct from charging and not charging");
        Check(Marshal.SizeOf<WindowsTelemetrySource.PowerStatus>() == 12 && Marshal.SizeOf<WindowsTelemetrySource.BatteryInfo>() == 36 &&
            Marshal.SizeOf<WindowsTelemetrySource.BatteryStatus>() == 16, "native power and battery layouts match the Windows SDK");
        var system = Environment.GetFolderPath(Environment.SpecialFolder.System);
        foreach (var (library, exports) in new[] { ("powrprof.dll", new[] { "PowerGetEffectiveOverlayScheme", "PowerSettingRegisterNotification", "PowerSettingUnregisterNotification" }),
            ("wlanapi.dll", new[] { "WlanOpenHandle", "WlanQueryInterface", "WlanFreeMemory" }) })
        {
            var handle = NativeLibrary.Load(Path.Combine(system, library));
            try { Check(exports.All(name => NativeLibrary.TryGetExport(handle, name, out _)), "read-only native entry points exist in " + library + " (not invoked)"); }
            finally { NativeLibrary.Free(handle); }
        }
    }
    private static void UsageChecks(DateTimeOffset start)
    {
        static UsageObservation Active(DateTimeOffset at, string id = "app1", string name = "App") => new(at, true, false, true, 0, id, name);
        static TelemetryRecord Apply(TelemetryUsage usage, DateTimeOffset at) { var r = new TelemetryRecord("fixture", at, TimeZoneInfo.Utc); usage.Apply(r); return r; }
        var usage = new TelemetryUsage();
        var fiveSecondUsage = new TelemetryUsage();
        for (int i = 0; i <= 60; i += 5) fiveSecondUsage.Observe(Active(start.AddSeconds(i)), TimeZoneInfo.Utc);
        Check(Apply(fiveSecondUsage, start.AddMinutes(1)).Data.Num("screen_time_total_min") == 1,
            "five-second foreground polls accumulate actual active minutes");
        for (int i = 0; i <= 120; i++) usage.Observe(Active(start.AddSeconds(i)), TimeZoneInfo.Utc);
        var record = Apply(usage, start.AddSeconds(120));
        Check(record.Data.Num("screen_time_total_min") == 2 && record.Data["app_usage"]!.AsArray()[0].Num("minutes") == 2 &&
            record.Data.Str("usage_date") == "2026-10-27" && record.Data.Str("usage_coverage_start_at") == TelemetryRecord.Stamp(start) &&
            record.Data["app_usage_is_complete"]!.GetValue<bool>() == false, "usage measures active foreground minutes and honestly declares a partial first day");
        var before = usage.Days[0].Seconds;
        for (int i = 121; i <= 124; i++) usage.Observe(Active(start.AddSeconds(i)) with { Locked = true }, TimeZoneInfo.Utc);
        for (int i = 125; i <= 128; i++) usage.Observe(Active(start.AddSeconds(i)) with { DisplayOn = false }, TimeZoneInfo.Utc);
        for (int i = 129; i <= 132; i++) usage.Observe(Active(start.AddSeconds(i)) with { IdleSeconds = 60 }, TimeZoneInfo.Utc);
        for (int i = 133; i <= 136; i++) usage.Observe(Active(start.AddSeconds(i)) with { SessionKnown = false }, TimeZoneInfo.Utc);
        usage.Observe(Active(start.AddHours(2)), TimeZoneInfo.Utc);
        Check(usage.Days[0].Seconds == before, "locked, display-off, idle, inaccessible-session and suspend gaps do not count as usage");
        usage.Observe(Active(start.AddHours(2).AddSeconds(1)) with { Locked = true }, TimeZoneInfo.Utc);
        usage.Observe(Active(start.AddHours(2).AddSeconds(2)), TimeZoneInfo.Utc);
        Check(Apply(usage, start).Data.Num("unlock_count_today") == 2, "only observed locked-to-unlocked transitions count as unlocks");
        var apps = new TelemetryUsage();
        for (int i = 0; i <= 121; i++) apps.Observe(Active(start.AddSeconds(i), i < 61 ? "id-a" : "id-b", "Same app"), TimeZoneInfo.Utc);
        var merged = Apply(apps, start).Data;
        Check(merged.Str("screen_time_apps") == "Same app:2" && merged["app_usage"]!.AsArray().Count == 2 &&
            merged["app_usage"]!.AsArray().Sum(a => a.Num("minutes")) <= merged.Num("screen_time_total_min"), "legacy names merge before flooring while structured usage retains stable app IDs");
        var exact = new TelemetryUsage();
        for (int i = 0; i <= 60; i++) exact.Observe(Active(start.AddSeconds(i), "exact", "Name:with;delimiters"), TimeZoneInfo.Utc);
        var exactRecord = Apply(exact, start).Data;
        Check(exactRecord.Str("screen_time_apps") == "" && exactRecord["app_usage"]!.AsArray()[0].Str("app_name") == "Name:with;delimiters", "structured app names remain exact when legacy delimiters cannot represent them");
        var midnight = new DateTimeOffset(2026, 10, 28, 0, 0, 0, TimeSpan.Zero);
        var rollover = new TelemetryUsage();
        for (int i = -60; i <= 60; i++) rollover.Observe(Active(midnight.AddSeconds(i)), TimeZoneInfo.Utc);
        Check(rollover.Days.Count == 2 && rollover.Days[0].Seconds == 60 && rollover.Days[1].Seconds == 60 &&
            rollover.Days[1].CoverageStart == midnight && rollover.Days[1].Complete, "midnight splits an active interval into the correct source-local days");
        Check(Apply(rollover, midnight).Data["screen_time_yesterday_min"] == null &&
            Apply(rollover, midnight).Data["screen_time_previous_7_day_avg_min"] == null, "partial or missing baseline days never become zero or a seven-day average");
        var restarted = new TelemetryUsage(rollover.Days);
        restarted.Observe(Active(midnight.AddHours(1)), TimeZoneInfo.Utc);
        Check(!restarted.Days.Last().Complete && restarted.Days.Last().Seconds == 60, "restart retains aggregates while marking the unobserved gap incomplete");
        var local = new TelemetryUsage();
        var india = TimeZoneInfo.FindSystemTimeZoneById("India Standard Time");
        local.Observe(Active(new DateTimeOffset(2026, 10, 27, 20, 0, 0, TimeSpan.Zero)), india);
        var localRecord = new TelemetryRecord("fixture", start, india); local.Apply(localRecord);
        Check(localRecord.Data.Str("usage_date") == "2026-10-28" && localRecord.Data.Str("source_timezone") == "Asia/Calcutta", "usage day and IANA timezone come from the source rather than UTC or a viewer");
        var complete = Enumerable.Range(1, 7).Select(i => new UsageDay { Date = DateOnly.FromDateTime(start.Date).AddDays(-i).ToString("yyyy-MM-dd"), Zone = "Etc/UTC",
            CoverageStart = start.AddDays(-i).AddHours(-12), ObservedThrough = start.AddDays(-i + 1).AddHours(-12), Complete = true, Seconds = i * 60 }).ToList();
        var history = new TelemetryUsage(complete); history.Observe(Active(start), TimeZoneInfo.Utc);
        var historyRecord = Apply(history, start).Data;
        Check(historyRecord.Num("screen_time_yesterday_min") == 1 && historyRecord.Num("screen_time_previous_7_day_avg_min") == 4, "daily baselines use seven actual complete days with the same source timezone");
        var interrupted = new TelemetryUsage([new UsageDay { Date = "2026-10-26", Zone = "Etc/UTC", CoverageStart = start.AddDays(-1).AddHours(-12),
            ObservedThrough = start.AddDays(-1), Complete = true, Seconds = 7200 }]);
        interrupted.Observe(Active(start), TimeZoneInfo.Utc);
        Check(Apply(interrupted, start).Data["screen_time_yesterday_min"] == null, "exiting before midnight cannot turn a partial checkpoint into a complete-day baseline");
        var bounded = new TelemetryUsage();
        for (int i = 0; i < 20; i++) bounded.Observe(Active(start.AddDays(i)), TimeZoneInfo.Utc);
        Check(bounded.Days.Count == 8, "usage history is bounded to eight source days");
    }
    private sealed class FixtureTelemetry : ILocalTelemetrySource
    {
        public int HardwareReads, CurrentReads, UsageReads;
        public bool Disposed, BlockCurrent;
        public void ReadHardware(TelemetryRecord r, DateTimeOffset now)
        {
            Interlocked.Increment(ref HardwareReads);
            r.Set("device_name", "Fixture PC", now, "fixture"); r.Set("processor_model", "Fixture CPU", now, "fixture");
        }
        public async Task ReadCurrentAsync(TelemetryRecord r, DateTimeOffset now, CancellationToken cancellation)
        {
            Interlocked.Increment(ref CurrentReads);
            if (BlockCurrent) await Task.Delay(Timeout.InfiniteTimeSpan, cancellation);
            r.Data["battery_present"] = true; r.Set("charge_pct", 0, now, "fixture empty battery");
            r.Data["network_connected"] = true; r.Set("network_type", "Wi-Fi", now, "fixture");
            r.Unavailable("network_name", "permission_denied", now);
            r.Data["media_session_active"] = true; r.Set("media_title", "private fixture track", now, "fixture selected session");
        }
        public UsageObservation ReadUsage(DateTimeOffset now)
        { Interlocked.Increment(ref UsageReads); return new(now, true, false, true, 0, "fixture-app", "Fixture App"); }
        public void Dispose() => Disposed = true;
    }
    private sealed class CollectionTime : TimeProvider
    {
        private readonly object gate = new();
        private readonly DateTimeOffset initial;
        private DateTimeOffset now;
        private readonly List<CollectionTimer> timers = [];
        public CollectionTime(DateTimeOffset initial) { this.initial = initial; now = initial; }
        public override DateTimeOffset GetUtcNow() { lock (gate) return now; }
        public override long GetTimestamp() { lock (gate) return (now - initial).Ticks; }
        public override long TimestampFrequency => TimeSpan.TicksPerSecond;
        public override TimeZoneInfo LocalTimeZone => TimeZoneInfo.Utc;
        public override ITimer CreateTimer(TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period)
        {
            lock (gate) { var timer = new CollectionTimer(this, callback, state, dueTime, period); timers.Add(timer); return timer; }
        }
        public void Advance(TimeSpan amount)
        {
            List<(TimerCallback Callback, object? State)> callbacks = [];
            lock (gate)
            {
                now += amount;
                foreach (var timer in timers)
                    if (!timer.Disposed && timer.Next <= now)
                    {
                        callbacks.Add((timer.Callback, timer.State));
                        timer.Next = timer.Period > TimeSpan.Zero ? now + timer.Period : DateTimeOffset.MaxValue;
                    }
            }
            foreach (var (callback, state) in callbacks) callback(state);
        }
        private sealed class CollectionTimer : ITimer
        {
            public TimerCallback Callback; public object? State; public TimeSpan Period; public DateTimeOffset Next; public bool Disposed;
            private readonly CollectionTime clock;
            public CollectionTimer(CollectionTime clock, TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period)
            { this.clock = clock; Callback = callback; State = state; Period = period; Next = clock.now + dueTime; }
            public bool Change(TimeSpan dueTime, TimeSpan period)
            { lock (clock.gate) { if (Disposed) return false; Period = period; Next = dueTime == Timeout.InfiniteTimeSpan ? DateTimeOffset.MaxValue : clock.now + dueTime; return true; } }
            public void Dispose() { lock (clock.gate) Disposed = true; }
            public ValueTask DisposeAsync() { Dispose(); return ValueTask.CompletedTask; }
        }
    }
}
