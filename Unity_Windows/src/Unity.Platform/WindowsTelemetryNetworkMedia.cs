using System.ComponentModel;
using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Text;
using NAudio.CoreAudioApi;
using Windows.Media.Control;
using Windows.Networking.Connectivity;

namespace Unity.Platform;

public sealed partial class WindowsTelemetrySource
{
    private (string Id, long Received, long Sent, long Tick, DateTimeOffset At)? traffic;
    private GlobalSystemMediaTransportControlsSessionManager? mediaManager;
    private MMDeviceEnumerator? audioEnumerator;
    private readonly object mediaActivityGate = new();
    private readonly Dictionary<GlobalSystemMediaTransportControlsSession, DateTimeOffset> mediaActivity = [];

    private void ReadNetwork(TelemetryRecord r, DateTimeOffset now)
    {
        var profile = NetworkInformation.GetInternetConnectionProfile();
        const string profileSource = "NetworkInformation.GetInternetConnectionProfile (Windows selected internet-route profile)";
        if (profile == null || profile.GetNetworkConnectivityLevel() == NetworkConnectivityLevel.None)
        {
            r.Data["network_connected"] = false;
            r.Unavailable(TelemetryRecord.NetworkFields, "not_applicable", now);
            r.Set("network_type", "Offline", now, profileSource);
            r.Set("network_transport", "none", now, profileSource); traffic = null; return;
        }
        r.Data["network_connected"] = true;
        var adapter = profile.NetworkAdapter;
        var network = adapter == null ? null : NetworkInterface.GetAllNetworkInterfaces()
            .FirstOrDefault(n => Guid.TryParse(n.Id, out var id) && id == adapter.NetworkAdapterId);
        var wifi = profile.IsWlanConnectionProfile; var cellular = profile.IsWwanConnectionProfile;
        string? transport = wifi ? "wifi" : cellular ? "cellular" : network?.NetworkInterfaceType is
            NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet or NetworkInterfaceType.FastEthernetFx or
            NetworkInterfaceType.FastEthernetT ? "ethernet" : null;
        if (transport != null) r.Set("network_transport", transport, now, profileSource + "; selected adapter type");
        r.Set("network_type", wifi ? "Wi-Fi" : cellular ? "Cellular" : transport == "ethernet" ? "Ethernet" : "Other", now, profileSource + "; selected adapter type");
        r.Unavailable("network_signal_pct", wifi || cellular ? "unsupported" : "not_applicable", now);
        r.Unavailable("network_signal_dbm", wifi || cellular ? "unsupported" : "not_applicable", now);
        if (wifi || cellular)
        {
            Try(r, ["network_signal_pct"], now, () =>
            {
                var bars = profile.GetSignalBars();
                if (bars is <= 5) r.Set("network_signal_pct", bars.Value * 20, now, "ConnectionProfile.GetSignalBars (0-5) * 20; normalized UI radio quality, not throughput", "derived");
            });
        }
        if (!wifi)
        {
            var name = cellular ? profile.ProfileName : network?.Name;
            if (!string.IsNullOrWhiteSpace(name)) r.Set("network_name", name, now, profileSource + "; profile/interface label");
        }
        if (cellular)
        {
            var dataClass = profile.WwanConnectionProfileDetails.GetCurrentDataClass();
            r.Set("network_type", dataClass == WwanDataClass.LteAdvanced ? "LTE" : dataClass == WwanDataClass.None ? "Cellular" : dataClass.ToString(), now, "WwanConnectionProfileDetails.GetCurrentDataClass");
        }
        if (wifi)
        {
            Try(r, ["network_name"], now, () =>
            {
                var ssid = profile.WlanConnectionProfileDetails.GetConnectedSsid();
                if (!string.IsNullOrEmpty(ssid)) r.Set("network_name", ssid, now, "WlanConnectionProfileDetails.GetConnectedSsid");
            });
            Try(r, ["network_security"], now, () => r.Set("network_security", profile.NetworkSecuritySettings.NetworkAuthenticationType.ToString(), now, "ConnectionProfile.NetworkSecuritySettings.NetworkAuthenticationType"));
            if (adapter != null) ReadWlan(r, now, adapter.NetworkAdapterId);
        }
        if (network != null)
        {
            Try(r, ["download_mbps", "upload_mbps"], now, () =>
            {
                var stats = network.GetIPStatistics(); var tick = Stopwatch.GetTimestamp();
                if (traffic is { } last && last.Id == network.Id)
                {
                    var seconds = Stopwatch.GetElapsedTime(last.Tick, tick).TotalSeconds;
                    if (seconds is > 0 and <= 15 && stats.BytesReceived >= last.Received && stats.BytesSent >= last.Sent)
                    {
                        var source = $"Selected interface IP byte-counter delta * 8 / elapsed seconds / 1000000; passive interface traffic, not an internet speed test; {TelemetryRecord.Stamp(last.At)} to {TelemetryRecord.Stamp(now)}";
                        r.Set("download_mbps", (stats.BytesReceived - last.Received) * 8.0 / seconds / 1_000_000, now, source, "derived");
                        r.Set("upload_mbps", (stats.BytesSent - last.Sent) * 8.0 / seconds / 1_000_000, now, source, "derived");
                    }
                }
                traffic = (network.Id, stats.BytesReceived, stats.BytesSent, tick, now);
            });
        }
        else traffic = null;
        // No pings, speed tests, scans, geolocation requests or remote probes.
    }
    private static void ReadWlan(TelemetryRecord r, DateTimeOffset now, Guid id)
    {
        var result = WlanOpenHandle(2, IntPtr.Zero, out _, out var client);
        if (result != 0)
        {
            if (result == 5)
                foreach (var field in new[] { "network_name", "network_signal_pct", "network_signal_dbm", "link_tx_mbps", "link_rx_mbps", "wifi_channel", "wifi_frequency_mhz" }.Where(f => r.Data[f] == null))
                    r.Unavailable(field, "permission_denied", now);
            return;
        }
        try
        {
            Try(r, ["network_name", "link_tx_mbps", "link_rx_mbps"], now, () =>
            {
                var connection = WlanValue(client, id, 7);
                if (connection == null || connection.Length < Marshal.SizeOf<WlanConnection>()) return;
                var value = FromBytes<WlanConnection>(connection);
                if (value.State != 1) return;
                var a = value.Association;
                if (a.Ssid.Length <= 32 && a.Ssid.Length > 0)
                {
                    // Only replace a WinRT SSID when bytes decode losslessly.
                    try { r.Set("network_name", new UTF8Encoding(false, true).GetString(a.Ssid.Bytes, 0, (int)a.Ssid.Length), now, "WlanQueryInterface current_connection DOT11_SSID UTF-8"); }
                    catch (DecoderFallbackException) { }
                }
                if (a.Quality <= 100) r.Set("network_signal_pct", (int)a.Quality, now, "WLAN_ASSOCIATION_ATTRIBUTES.wlanSignalQuality (documented 0-100 radio quality)");
                if (a.RxRate > 0) r.Set("link_rx_mbps", a.RxRate / 1000.0, now, "WLAN_ASSOCIATION_ATTRIBUTES.ulRxRate kbit/s / 1000; negotiated receive link rate", "derived");
                if (a.TxRate > 0) r.Set("link_tx_mbps", a.TxRate / 1000.0, now, "WLAN_ASSOCIATION_ATTRIBUTES.ulTxRate kbit/s / 1000; negotiated transmit link rate", "derived");
                var generation = a.Phy switch { 7 => "Wi-Fi 4", 8 => "Wi-Fi 5", 10 => "Wi-Fi 6", 11 => "Wi-Fi 7", _ => null };
                if (generation != null) r.Set("network_type", generation, now, "WLAN_ASSOCIATION_ATTRIBUTES.dot11PhyType documented 802.11 generation");
            });
            Try(r, ["wifi_channel"], now, () =>
            {
                var bytes = WlanValue(client, id, 8);
                if (bytes is { Length: >= 4 } && BitConverter.ToUInt32(bytes) is > 0 and <= 233)
                    r.Set("wifi_channel", BitConverter.ToUInt32(bytes), now, "WlanQueryInterface channel_number");
            });
            Try(r, ["network_signal_dbm"], now, () =>
            {
                var bytes = WlanValue(client, id, 0x10000102);
                if (bytes is { Length: >= 4 } && BitConverter.ToInt32(bytes) is >= -127 and < 0)
                    r.Set("network_signal_dbm", BitConverter.ToInt32(bytes), now, "WlanQueryInterface rssi, received signal strength in dBm");
            });
            Try(r, ["wifi_frequency_mhz"], now, () =>
            {
                // Windows 11 24H2+ exposes actual channel-center frequency. A
                // multi-link connection has no single frequency; leave it null.
                var bytes = WlanValue(client, id, 19);
                if (bytes is { Length: >= 40 } && BitConverter.ToUInt32(bytes, 20) == 1 && BitConverter.ToUInt32(bytes, 28) > 0)
                    r.Set("wifi_frequency_mhz", BitConverter.ToUInt32(bytes, 28), now, "WlanQueryInterface realtime_connection_quality, single link ulChannelCenterFrequencyMhz");
            });
        }
        finally { WlanCloseHandle(client, IntPtr.Zero); }
    }
    private static byte[]? WlanValue(IntPtr client, Guid id, uint opcode)
    {
        var result = WlanQueryInterface(client, ref id, opcode, IntPtr.Zero, out var size, out var pointer, out _);
        if (result != 0)
        { if (result == 5) throw new Win32Exception(5); return null; }
        try
        {
            if (size > 65536) return null;
            var bytes = new byte[size]; Marshal.Copy(pointer, bytes, 0, bytes.Length); return bytes;
        }
        finally { WlanFreeMemory(pointer); }
    }
    private async Task ReadMediaAsync(TelemetryRecord r, DateTimeOffset now, CancellationToken cancellation)
    {
        mediaManager ??= await GlobalSystemMediaTransportControlsSessionManager.RequestAsync().AsTask(cancellation).WaitAsync(TimeSpan.FromSeconds(3), cancellation);
        var sessions = mediaManager.GetSessions();
        lock (mediaActivityGate)
        {
            foreach (var ended in mediaActivity.Keys.Where(s => !sessions.Contains(s)).ToArray())
            { ended.PlaybackInfoChanged -= MediaActivityChanged; ended.MediaPropertiesChanged -= MediaActivityChanged; mediaActivity.Remove(ended); }
            foreach (var session in sessions.Where(s => !mediaActivity.ContainsKey(s)))
            {
                mediaActivity[session] = DateTimeOffset.MinValue;
                session.PlaybackInfoChanged += MediaActivityChanged; session.MediaPropertiesChanged += MediaActivityChanged;
            }
        }
        if (sessions.Count == 0)
        {
            r.Data["media_session_active"] = false;
            r.Unavailable(TelemetryRecord.MediaFields, "no_session", now);
            r.Set("media_playing", "Stopped", now, "GSMTC session manager: no sessions"); return;
        }
        var current = mediaManager.GetCurrentSession();
        var selected = sessions.OrderByDescending(s => s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
            .ThenByDescending(MediaActivityAt).ThenByDescending(s => s.Equals(current)).First();
        var properties = await selected.TryGetMediaPropertiesAsync().AsTask(cancellation).WaitAsync(TimeSpan.FromSeconds(3), cancellation);
        var playback = selected.GetPlaybackInfo(); var timeline = selected.GetTimelineProperties();
        r.Data["media_session_active"] = true;
        const string source = "Windows GSMTC, playing session first, otherwise latest observed playback/metadata change or timeline update; current session breaks ties";
        if (!string.IsNullOrEmpty(properties.Title)) r.Set("media_title", properties.Title, now, source + "; Title");
        if (!string.IsNullOrEmpty(properties.Artist)) r.Set("media_artist", properties.Artist, now, source + "; Artist");
        if (!string.IsNullOrEmpty(selected.SourceAppUserModelId)) r.Set("media_app", selected.SourceAppUserModelId, now, source + "; SourceAppUserModelId");
        var state = playback.PlaybackStatus switch
        {
            GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing => "Playing",
            GlobalSystemMediaTransportControlsSessionPlaybackStatus.Paused => "Paused",
            GlobalSystemMediaTransportControlsSessionPlaybackStatus.Stopped => "Stopped",
            _ => null
        };
        if (state != null) r.Set("media_playing", state, now, source + "; PlaybackStatus");
        else r.Unavailable("media_playing", "error", now);
        // A cached timeline is not a fresh observation of playback position.
        var observed = timeline.LastUpdatedTime;
        if (observed > DateTimeOffset.UnixEpoch && observed <= now)
        {
            if (timeline.Position >= TimeSpan.Zero) r.Set("media_position_sec", timeline.Position.TotalSeconds, observed, source + "; cached timeline Position, original LastUpdatedTime");
            if (timeline.EndTime > timeline.StartTime && timeline.StartTime == TimeSpan.Zero)
                r.Set("media_duration_sec", timeline.EndTime.TotalSeconds, observed, source + "; timeline EndTime with zero StartTime, original LastUpdatedTime");
        }
    }
    private void MediaActivityChanged(GlobalSystemMediaTransportControlsSession session, object args)
    { lock (mediaActivityGate) if (mediaActivity.ContainsKey(session)) mediaActivity[session] = DateTimeOffset.UtcNow; }
    private DateTimeOffset MediaActivityAt(GlobalSystemMediaTransportControlsSession session)
    {
        var timeline = session.GetTimelineProperties().LastUpdatedTime;
        lock (mediaActivityGate) return mediaActivity.GetValueOrDefault(session) > timeline ? mediaActivity[session] : timeline;
    }
    private void DisposeMediaActivity()
    {
        lock (mediaActivityGate)
        {
            foreach (var session in mediaActivity.Keys)
            { session.PlaybackInfoChanged -= MediaActivityChanged; session.MediaPropertiesChanged -= MediaActivityChanged; }
            mediaActivity.Clear();
        }
    }
    private void ReadAudio(TelemetryRecord r, DateTimeOffset now)
    {
        audioEnumerator ??= new();
        using var output = audioEnumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        r.Set("audio_output_route", output.FriendlyName, now, "Windows Core Audio default multimedia render endpoint FriendlyName");
        var volume = output.AudioEndpointVolume.MasterVolumeLevelScalar;
        if (float.IsFinite(volume) && volume is >= 0 and <= 1)
            r.Set("volume_pct", volume * 100.0, now, "Core Audio endpoint MasterVolumeLevelScalar * 100", "derived");
    }
    [StructLayout(LayoutKind.Sequential)] private struct Ssid { public uint Length; [MarshalAs(UnmanagedType.ByValArray, SizeConst = 32)] public byte[] Bytes; }
    [StructLayout(LayoutKind.Sequential)] private struct WlanAssociation { public Ssid Ssid; public uint BssType; [MarshalAs(UnmanagedType.ByValArray, SizeConst = 6)] public byte[] Bssid; public uint Phy, PhyIndex, Quality, RxRate, TxRate; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct WlanConnection { public uint State, Mode; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string Profile; public WlanAssociation Association; public uint SecurityEnabled, OneXEnabled, Authentication, Cipher; }
    [DllImport("wlanapi.dll")] private static extern uint WlanOpenHandle(uint version, IntPtr reserved, out uint negotiated, out IntPtr handle);
    [DllImport("wlanapi.dll")] private static extern uint WlanCloseHandle(IntPtr client, IntPtr reserved);
    [DllImport("wlanapi.dll")] private static extern uint WlanQueryInterface(IntPtr client, ref Guid id, uint opcode, IntPtr reserved, out uint size, out IntPtr data, out uint valueType);
    [DllImport("wlanapi.dll")] private static extern void WlanFreeMemory(IntPtr data);
}
