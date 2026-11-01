using System.Globalization;
using System.Text.Json.Nodes;

namespace Unity.Platform;

public sealed record UsageObservation(DateTimeOffset At, bool SessionKnown, bool Locked, bool DisplayOn,
    double IdleSeconds, string? AppId, string? AppName);
public sealed class UsageDay
{
    public string Date { get; set; } = "";
    public string Zone { get; set; } = "";
    public DateTimeOffset CoverageStart { get; set; }
    public DateTimeOffset ObservedThrough { get; set; }
    public double Seconds { get; set; }
    public int Unlocks { get; set; }
    public bool Complete { get; set; }
    public bool Attributed { get; set; } = true;
    public Dictionary<string, UsageApp> Apps { get; set; } = [];
}
public sealed class UsageApp
{
    public string Name { get; set; } = "";
    public double Seconds { get; set; }
}

// One interval belongs to one foreground app. Long sampling gaps (including
// suspend) are never counted as use. Daily aggregate state stays in memory.
public sealed class TelemetryUsage
{
    public const int IdleCutoffSeconds = 60;
    public const string Method = "Unity 5-second foreground sampling in the current Windows session; WTS lock state, " +
        "session display power and GetLastInputInfo; 60-second idle cutoff; both interval endpoints must be active; " +
        "gaps over 7 seconds excluded; only periods while Unity collection is enabled are covered; daily counters restart with Unity";
    public List<UsageDay> Days { get; }
    private UsageObservation? previous;
    private UsageDay? current;
    private string? previousZone;
    private TimeZoneInfo currentZone = TimeZoneInfo.Utc;
    public DateTimeOffset? ObservedAt => previous?.At;
    public void MarkGap() { if (current != null) current.Complete = false; }
    public TelemetryUsage(IEnumerable<UsageDay>? saved = null)
    {
        Days = saved?.TakeLast(8).ToList() ?? [];
        // Restart never bridges the period when Unity was not running.
    }
    public void Observe(UsageObservation observation, TimeZoneInfo zone)
    {
        var zoneId = TelemetryRecord.ZoneId(zone);
        if (previous?.At == observation.At && previousZone == zoneId) return;
        currentZone = zone;
        var day = LocalDate(observation.At, zone);
        var elapsed = previous is { } before ? (observation.At - before.At).TotalSeconds : 0;
        var continuous = previous != null && previousZone == zoneId && elapsed is > 0 and <= 7;
        if (current == null || current.Date != day || current.Zone != zoneId)
        {
            if (current != null && !continuous) current.Complete = false;
            var old = current;
            current = Days.LastOrDefault(d => d.Date == day && d.Zone == zoneId);
            if (current == null)
            {
                var midnight = new DateTimeOffset(TimeZoneInfo.ConvertTime(observation.At, zone).Date, zone.GetUtcOffset(observation.At)).ToUniversalTime();
                current = new() { Date = day, Zone = zoneId, CoverageStart = continuous && old != null ? midnight : observation.At,
                    ObservedThrough = observation.At, Complete = continuous && old != null };
                Days.Add(current);
                if (continuous && previous != null && previous.At < midnight)
                {
                    Credit(old!, previous, observation, (midnight - previous.At).TotalSeconds);
                    old!.ObservedThrough = midnight;
                    elapsed = (observation.At - midnight).TotalSeconds;
                }
            }
            else current.Complete = false;
        }
        if (!continuous) current.Complete = false;
        if (continuous && previous != null)
        {
            Credit(current, previous, observation, elapsed);
            if (previous.SessionKnown && previous.Locked && observation.SessionKnown && !observation.Locked) current.Unlocks++;
        }
        if (!observation.SessionKnown) current.Complete = false;
        current.ObservedThrough = observation.At;
        previous = observation; previousZone = zoneId;
        while (Days.Count > 8) Days.RemoveAt(0);
    }
    private static void Credit(UsageDay day, UsageObservation before, UsageObservation after, double seconds)
    {
        static bool Active(UsageObservation o) => o.SessionKnown && !o.Locked && o.DisplayOn && o.IdleSeconds is >= 0 and < IdleCutoffSeconds;
        if (seconds <= 0 || !Active(before) || !Active(after)) return;
        day.Seconds += seconds;
        if (before.AppId == null || before.AppName == null) { day.Attributed = false; return; }
        if (!day.Apps.TryGetValue(before.AppId, out var app))
        {
            if (day.Apps.Count >= 512) { day.Attributed = false; return; }
            day.Apps[before.AppId] = app = new() { Name = before.AppName };
        }
        app.Seconds += seconds;
    }
    public void Apply(TelemetryRecord record)
    {
        if (current == null || previous == null || !previous.SessionKnown)
        { record.Unavailable(TelemetryRecord.UsageFields, "error", previous?.At); return; }
        var now = current.ObservedThrough;
        record.Data["usage_date"] = current.Date;
        record.Data["usage_coverage_start_at"] = TelemetryRecord.Stamp(current.CoverageStart);
        record.Data["usage_measurement_method"] = Method;
        record.Data["daily_events_coverage_start_at"] = TelemetryRecord.Stamp(current.CoverageStart);
        record.Data["app_usage_is_complete"] = current.Complete && current.Attributed;
        record.Set("screen_time_total_min", (int)(current.Seconds / 60), now, Method, "derived");
        record.Set("unlock_count_today", current.Unlocks, now, "Observed WTS locked-to-unlocked transitions; " + Method, "derived");
        var apps = new JsonArray();
        foreach (var (id, app) in current.Apps.OrderByDescending(a => a.Value.Seconds).ThenBy(a => a.Key))
        {
            var minutes = (int)(app.Seconds / 60);
            if (minutes > 0) apps.Add(new JsonObject { ["app_id"] = id, ["app_name"] = app.Name, ["minutes"] = minutes });
        }
        record.Set("app_usage", apps, now, Method, "derived");
        // Preserve exact names in app_usage. The legacy format cannot represent
        // ':' or ';', so omit such names rather than silently changing them.
        var legacy = current.Apps.Values.GroupBy(a => a.Name).Select(g => (Name: g.Key, Minutes: (int)(g.Sum(a => a.Seconds) / 60)))
            .Where(a => a.Minutes > 0 && !a.Name.Contains(':') && !a.Name.Contains(';')).OrderBy(a => a.Name);
        record.Set("screen_time_apps", string.Join(';', legacy.Select(a => $"{a.Name}:{a.Minutes}")), now,
            Method + "; duplicate names merged before flooring; names with legacy delimiters omitted, exact names in app_usage", "derived");
        var date = DateOnly.ParseExact(current.Date, "yyyy-MM-dd", CultureInfo.InvariantCulture);
        var yesterday = CompleteDay(date.AddDays(-1));
        if (yesterday != null) record.Set("screen_time_yesterday_min", (int)(yesterday.Seconds / 60), yesterday.ObservedThrough, Method + "; complete previous local day", "derived");
        var seven = Enumerable.Range(1, 7).Select(i => CompleteDay(date.AddDays(-i))).ToArray();
        if (seven.All(d => d != null)) record.Set("screen_time_previous_7_day_avg_min", seven.Average(d => Math.Floor(d!.Seconds / 60)),
            seven.Max(d => d!.ObservedThrough), Method + "; mean of seven complete previous local days", "derived");
        // An uninterrupted current day is complete only through its observation
        // time. It becomes a full-day baseline after an observed midnight; a
        // noon checkpoint followed by exit must never masquerade as yesterday.
        UsageDay? CompleteDay(DateOnly d) => Days.LastOrDefault(x => x.Zone == current.Zone &&
            x.Date == d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) && x.Complete &&
            DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(x.ObservedThrough, currentZone).DateTime) > d);
    }
    public static string LocalDate(DateTimeOffset time, TimeZoneInfo zone) => TimeZoneInfo.ConvertTime(time, zone).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
}
