using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.VisualBasic.FileIO;

namespace Unity.Platform;

internal sealed record TelemetryConsent(int ConsentVersion, string SchemaVersion, bool Enabled, DateTimeOffset? ApprovedAt, string DeviceUid);
internal sealed record TelemetryCsvSample(DateTimeOffset CapturedAt, string[] Cells);
internal sealed class TelemetryStorage(string root)
{
    public const int ConsentVersion = 1;
    public static readonly TimeSpan Retention = TimeSpan.FromMinutes(30);
    public const int MaximumSamples = 361;
    private const int MaximumCsvBytes = 32 * 1024 * 1024;
    public static readonly string[] Columns = [.. TelemetryRecord.CoreFields,
        "schema_version", "device_uid", "sample_id", "captured_at", "source_timezone", "is_demo",
        "battery_present", "network_connected", "media_session_active", "power_measurement_kind",
        "usage_date", "usage_coverage_start_at", "usage_measurement_method", "app_usage_is_complete", "daily_events_coverage_start_at",
        .. TelemetryRecord.OptionalFields, "field_status"];
    private static readonly int CapturedAtIndex = Array.IndexOf(Columns, "captured_at");
    private static readonly int SampleIdIndex = Array.IndexOf(Columns, "sample_id");
    private readonly object gate = new();
    private readonly List<TelemetryCsvSample> samples = [];
    private bool loaded, dirty;
    private string ConsentPath => Path.Combine(root, "local-collection-consent.json");
    public string CsvPath => Path.Combine(root, "local-collection.csv");
    public TelemetryConsent LoadConsent()
    {
        try
        {
            if (File.Exists(ConsentPath) && new FileInfo(ConsentPath).Length <= 4096)
            {
                var consent = JsonSerializer.Deserialize<TelemetryConsent>(File.ReadAllBytes(ConsentPath));
                if (consent is { ConsentVersion: ConsentVersion, SchemaVersion: "2.0", ApprovedAt: not null } &&
                    Guid.TryParseExact(consent.DeviceUid, "N", out _)) return consent;
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException) { }
        return new(ConsentVersion, "2.0", false, null, "");
    }
    public void SaveConsent(TelemetryConsent consent) => AtomicWrite(ConsentPath, JsonSerializer.SerializeToUtf8Bytes(consent));
    public void Revoke(TelemetryConsent consent)
    {
        try { SaveConsent(consent); }
        catch
        {
            try { DeleteFile(ConsentPath); }
            finally { Clear(); }
            throw;
        }
        Clear();
    }
    public void Save(JsonObject snapshot, DateTimeOffset now)
    {
        lock (gate)
        {
            dirty |= Load(now);
            var cells = Columns.Select(field => Cell(snapshot[field])).ToArray();
            if (!ParseTime(cells[CapturedAtIndex], out var captured) || captured > now || captured < now - Retention)
                throw new InvalidDataException("Snapshot has an invalid capture time");
            if (cells[SampleIdIndex].Length == 0) throw new InvalidDataException("Snapshot has no sample ID");
            samples.RemoveAll(s => s.Cells[SampleIdIndex] == cells[SampleIdIndex]);
            samples.Add(new(captured, cells));
            Trim(now);
            dirty = true;
            WriteCsv();
        }
    }
    public void Prune(DateTimeOffset now)
    {
        lock (gate)
        {
            dirty |= Load(now);
            dirty |= Trim(now);
            if (dirty && File.Exists(CsvPath)) WriteCsv();
        }
    }
    private bool Load(DateTimeOffset now)
    {
        // The previous eight-day checkpoint must not survive the CSV migration.
        DeleteFile(Path.Combine(root, "local-collection.dat"));
        DeleteFile(Path.Combine(root, "local-collection.dat.tmp"));
        DeleteFile(CsvPath + ".tmp");
        if (loaded) return false;
        samples.Clear();
        if (!File.Exists(CsvPath)) { loaded = true; return false; }
        if (new FileInfo(CsvPath).Length > MaximumCsvBytes) throw new InvalidDataException("Local CSV exceeds its size limit");
        bool changed = false;
        using var stream = new FileStream(CsvPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var reader = new StreamReader(stream, new UTF8Encoding(false, true), detectEncodingFromByteOrderMarks: true);
        using var parser = new TextFieldParser(reader) { TextFieldType = FieldType.Delimited, HasFieldsEnclosedInQuotes = true, TrimWhiteSpace = false };
        parser.SetDelimiters(",");
        if (parser.ReadFields() is not { } header || !header.SequenceEqual(Columns)) throw new InvalidDataException("Unrecognized local CSV header");
        while (!parser.EndOfData)
        {
            var cells = parser.ReadFields();
            if (cells == null || cells.Length != Columns.Length || !ParseTime(cells[CapturedAtIndex], out var captured))
                throw new InvalidDataException("Invalid local CSV record");
            if (captured < now - Retention || captured > now) { changed = true; continue; }
            samples.Add(new(captured, cells));
        }
        loaded = true;
        return Trim(now) || changed;
    }
    private bool Trim(DateTimeOffset now)
    {
        var removed = samples.RemoveAll(s => s.CapturedAt < now - Retention || s.CapturedAt > now);
        samples.Sort((a, b) => a.CapturedAt.CompareTo(b.CapturedAt));
        if (samples.Count > MaximumSamples)
        {
            var extra = samples.Count - MaximumSamples;
            samples.RemoveRange(0, extra); removed += extra;
        }
        return removed > 0;
    }
    private void WriteCsv()
    {
        var text = new StringBuilder(string.Join(',', Columns)).Append("\r\n");
        foreach (var sample in samples)
        {
            text.AppendJoin(',', sample.Cells.Select(Quote)).Append("\r\n");
            if (text.Length > MaximumCsvBytes) throw new InvalidDataException("Local CSV exceeds its size limit");
        }
        var bytes = new UTF8Encoding(false).GetBytes(text.ToString());
        if (bytes.Length > MaximumCsvBytes) throw new InvalidDataException("Local CSV exceeds its size limit");
        AtomicWrite(CsvPath, bytes);
        dirty = false;
    }
    private static string Cell(JsonNode? value) => value is JsonValue scalar && scalar.TryGetValue<string>(out var text) ? text : value?.ToJsonString() ?? "";
    private static string Quote(string value) => value.IndexOfAny([',', '"', '\r', '\n']) >= 0 ? '"' + value.Replace("\"", "\"\"") + '"' : value;
    private static bool ParseTime(string value, out DateTimeOffset time) => DateTimeOffset.TryParseExact(value, "O", CultureInfo.InvariantCulture, DateTimeStyles.None, out time);
    public void Clear()
    {
        lock (gate)
        {
            samples.Clear(); loaded = false; dirty = false;
            foreach (var name in new[] { "local-collection.csv", "local-collection.csv.tmp", "local-collection.dat", "local-collection.dat.tmp" })
                DeleteFile(Path.Combine(root, name));
        }
    }
    private static void AtomicWrite(string path, byte[] bytes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        try { File.WriteAllBytes(path + ".tmp", bytes); File.Move(path + ".tmp", path, true); }
        finally { DeleteFile(path + ".tmp"); }
    }
    private static void DeleteFile(string path)
    {
        try { File.Delete(path); }
        catch (DirectoryNotFoundException) { }
    }
}
