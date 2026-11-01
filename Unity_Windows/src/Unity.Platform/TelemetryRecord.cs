using System.Text.Json;
using System.Text.Json.Nodes;

namespace Unity.Platform;

// This contract is deliberately separate from Unity's phone protocol. Nothing in
// the collector has access to a UnityHost, a DeviceSession, or a transport.
public sealed class TelemetryRecord
{
    public static readonly string[] CoreFields = [
        "device_id", "device_name", "power_profile", "charge_pct", "charging_state", "power_draw_w",
        "network_type", "network_signal_pct", "network_name", "media_title", "media_artist", "media_app",
        "media_playing", "screen_time_total_min", "screen_time_apps", "dnd_state"];
    public static readonly string[] OptionalFields = [
        "processor_model", "processor_physical_cores", "processor_logical_cores", "memory_total_bytes",
        "memory_type", "memory_speed_mt_s", "os_name", "os_version", "os_build", "security_patch_date",
        "system_uptime_sec", "sync_transport", "external_power_connected", "battery_health_pct",
        "battery_cycle_count", "battery_design_capacity_mah", "battery_full_charge_capacity_mah",
        "battery_temp_c", "cpu_temp_c", "chassis_temp_c", "battery_voltage_v", "fan_speed_rpm",
        "thermal_state", "charging_protocol", "charger_input_voltage_v", "negotiated_charge_limit_w",
        "battery_remaining_sec", "time_to_full_sec", "network_transport", "network_signal_dbm",
        "link_tx_mbps", "link_rx_mbps", "download_mbps", "upload_mbps", "latency_ms", "latency_target",
        "network_security", "wifi_frequency_mhz", "wifi_channel", "cellular_band", "voice_transport",
        "voice_codec", "wifi_calling_enabled", "media_position_sec", "media_duration_sec", "audio_codec",
        "audio_bitrate_kbps", "audio_bit_depth", "audio_output_route", "volume_pct", "spatial_audio_enabled",
        "noise_cancellation_enabled", "listening_level_dba", "app_usage", "screen_time_yesterday_min",
        "screen_time_previous_7_day_avg_min", "unlock_count_today", "pickup_count_today",
        "notification_count_today", "focus_window_start_at", "focus_window_end_at"];
    public static readonly string[] BatteryFields = ["charge_pct", "charging_state", "power_draw_w", "battery_health_pct",
        "battery_cycle_count", "battery_design_capacity_mah", "battery_full_charge_capacity_mah", "battery_temp_c",
        "battery_voltage_v", "battery_remaining_sec", "time_to_full_sec", "charging_protocol",
        "charger_input_voltage_v", "negotiated_charge_limit_w"];
    public static readonly string[] NetworkFields = ["network_type", "network_signal_pct", "network_name", "network_transport",
        "network_signal_dbm", "link_tx_mbps", "link_rx_mbps", "download_mbps", "upload_mbps", "network_security",
        "wifi_frequency_mhz", "wifi_channel", "cellular_band", "voice_transport", "voice_codec", "wifi_calling_enabled"];
    public static readonly string[] MediaFields = ["media_title", "media_artist", "media_app", "media_playing", "media_position_sec", "media_duration_sec"];
    public static readonly string[] UsageFields = ["screen_time_total_min", "screen_time_apps", "app_usage", "unlock_count_today",
        "screen_time_yesterday_min", "screen_time_previous_7_day_avg_min"];

    public JsonObject Data { get; } = new();
    private JsonObject Status => (JsonObject)Data["field_status"]!;
    public TelemetryRecord(string uid, DateTimeOffset now, TimeZoneInfo zone)
    {
        Data["schema_version"] = "2.0";
        Data["device_uid"] = uid;
        Data["sample_id"] = Guid.NewGuid().ToString("N");
        Data["captured_at"] = Stamp(now);
        Data["source_timezone"] = ZoneId(zone);
        Data["is_demo"] = false;
        Data["field_status"] = new JsonObject();
        foreach (var field in CoreFields.Concat(OptionalFields)) Unavailable(field);
        foreach (var field in new[] { "battery_present", "network_connected", "media_session_active", "power_measurement_kind",
            "usage_date", "usage_coverage_start_at", "usage_measurement_method", "app_usage_is_complete", "daily_events_coverage_start_at" }) Data[field] = null;
        Set("device_id", "laptop", now, "Unity Windows dashboard panel assignment");
        // There is intentionally no telemetry transport to report.
        Unavailable("sync_transport", "not_applicable");
        Unavailable("pickup_count_today", "not_applicable");
    }
    public void Set<T>(string field, T value, DateTimeOffset at, string source, string status = "reported")
    {
        if (value is null || string.IsNullOrWhiteSpace(source)) throw new ArgumentException("A reading needs a value and source");
        if (value is double d && !double.IsFinite(d) || value is float f && !float.IsFinite(f)) throw new ArgumentException("Non-finite telemetry");
        Data[field] = JsonSerializer.SerializeToNode(value);
        Status[field] = new JsonObject { ["status"] = status, ["observed_at"] = Stamp(at), ["source"] = source };
    }
    public void Unavailable(string field, string status = "unsupported", DateTimeOffset? at = null)
    {
        Data[field] = null;
        Status[field] = new JsonObject { ["status"] = status, ["observed_at"] = at is { } time ? Stamp(time) : null, ["source"] = null };
    }
    public void Unavailable(IEnumerable<string> fields, string status, DateTimeOffset? at = null)
    { foreach (var field in fields) Unavailable(field, status, at); }
    public void CopyFields(TelemetryRecord cached, IEnumerable<string> fields)
    {
        foreach (var field in fields)
        {
            Data[field] = cached.Data[field]?.DeepClone();
            Status[field] = cached.Status[field]?.DeepClone();
        }
    }
    public static string Stamp(DateTimeOffset time) => time.ToUniversalTime().ToString("O");
    public static string ZoneId(TimeZoneInfo zone) => zone.Id == "UTC" ? "Etc/UTC" : zone.HasIanaId ? zone.Id :
        TimeZoneInfo.TryConvertWindowsIdToIanaId(zone.Id, out var id) ? id : throw new InvalidOperationException("IANA timezone unavailable");
    public static string FailureStatus(Exception error) => error is UnauthorizedAccessException ||
        error is System.ComponentModel.Win32Exception { NativeErrorCode: 5 } || error.HResult == unchecked((int)0x80070005)
        ? "permission_denied" : "error";
}

public interface ILocalTelemetrySource : IDisposable
{
    void ReadHardware(TelemetryRecord record, DateTimeOffset now);
    Task ReadCurrentAsync(TelemetryRecord record, DateTimeOffset now, CancellationToken cancellation);
    UsageObservation ReadUsage(DateTimeOffset now);
}
