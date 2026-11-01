using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;
using Microsoft.Win32.SafeHandles;

namespace Unity.Platform;

public sealed partial class WindowsTelemetrySource : ILocalTelemetrySource
{
    private readonly WindowsUsageSource usage = new();
    public UsageObservation ReadUsage(DateTimeOffset now) => usage.Read(now);
    public void ReadHardware(TelemetryRecord r, DateTimeOffset now)
    {
        r.Set("device_name", Environment.MachineName, now, "Windows computer name");
        Try(r, ["processor_model"], now, () =>
        {
            using var cpu = Registry.LocalMachine.OpenSubKey(@"HARDWARE\DESCRIPTION\System\CentralProcessor\0");
            if (cpu?.GetValue("ProcessorNameString") is string model && !string.IsNullOrWhiteSpace(model))
                r.Set("processor_model", model.Trim(), now, @"HKLM\HARDWARE\DESCRIPTION\System\CentralProcessor\0\ProcessorNameString");
        });
        Try(r, ["processor_physical_cores", "processor_logical_cores"], now, () =>
        {
            var logical = GetActiveProcessorCount(0xffff);
            if (logical is > 0 and < uint.MaxValue) r.Set("processor_logical_cores", logical, now, "GetActiveProcessorCount(ALL_PROCESSOR_GROUPS)");
            uint size = 0; GetLogicalProcessorInformationEx(0, IntPtr.Zero, ref size);
            if (size is 0 or > 1048576) return;
            var data = Marshal.AllocHGlobal((int)size);
            try
            {
                if (!GetLogicalProcessorInformationEx(0, data, ref size)) throw new Win32Exception(Marshal.GetLastWin32Error());
                var count = 0;
                for (int offset = 0; offset + 8 <= size;)
                {
                    var length = Marshal.ReadInt32(data, offset + 4);
                    if (length < 8 || offset + length > size) throw new InvalidDataException("Invalid processor topology");
                    if (Marshal.ReadInt32(data, offset) == 0) count++;
                    offset += length;
                }
                if (count > 0) r.Set("processor_physical_cores", count, now, "GetLogicalProcessorInformationEx(RelationProcessorCore)");
            }
            finally { Marshal.FreeHGlobal(data); }
        });
        Try(r, ["memory_total_bytes"], now, () =>
        {
            var memory = new MemoryStatus { Length = (uint)Marshal.SizeOf<MemoryStatus>() };
            if (!GlobalMemoryStatusEx(ref memory)) throw new Win32Exception(Marshal.GetLastWin32Error());
            r.Set("memory_total_bytes", memory.TotalPhysical, now, "GlobalMemoryStatusEx.ullTotalPhys (OS-reported usable physical memory, bytes)");
        });
        Try(r, ["memory_type", "memory_speed_mt_s"], now, () => ReadMemoryFirmware(r, now));
        Try(r, ["os_name", "os_version", "os_build"], now, () =>
        {
            using var os = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows NT\CurrentVersion");
            var version = new OsVersion { Size = (uint)Marshal.SizeOf<OsVersion>(), ServicePack = "" };
            if (RtlGetVersion(ref version) != 0) throw new InvalidOperationException("Windows version unavailable");
            r.Set("os_name", version.Build >= 22000 ? "Windows 11" : "Windows", now, "RtlGetVersion Windows build");
            var display = os?.GetValue("DisplayVersion") as string;
            r.Set("os_version", display ?? $"{version.Major}.{version.Minor}", now, "Windows CurrentVersion.DisplayVersion / RtlGetVersion");
            var revision = os?.GetValue("UBR");
            r.Set("os_build", revision is int ubr ? $"{version.Build}.{ubr}" : version.Build.ToString(), now, "RtlGetVersion + Windows CurrentVersion.UBR");
        });
    }
    public async Task ReadCurrentAsync(TelemetryRecord r, DateTimeOffset now, CancellationToken cancellation)
    {
        r.Set("system_uptime_sec", GetTickCount64() / 1000.0, now, "GetTickCount64 (time since Windows boot, includes sleep)");
        Try(r, ["power_profile"], now, () => ReadPowerProfile(r, now));
        Try(r, TelemetryRecord.BatteryFields, now, () => ReadBattery(r, now));
        Try(r, TelemetryRecord.NetworkFields, now, () => ReadNetwork(r, now));
        cancellation.ThrowIfCancellationRequested();
        try { await ReadMediaAsync(r, now, cancellation); }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { throw; }
        catch (Exception e) { r.Data["media_session_active"] = null; r.Unavailable(TelemetryRecord.MediaFields, TelemetryRecord.FailureStatus(e), now); }
        Try(r, ["audio_output_route", "volume_pct"], now, () => ReadAudio(r, now));
        // Windows has no supported public query for the actual Windows 11 DND
        // switch. Notification suppression/Focus activity are different states.
        // Leave it and non-exposed/vendor-only sensors explicitly unsupported.
    }
    private static void Try(TelemetryRecord r, IEnumerable<string> fields, DateTimeOffset now, Action read)
    {
        try { read(); }
        catch (Exception e)
        {
            // A denied optional sensor must not discard an OS aggregate or
            // another independently successful reading from the same category.
            foreach (var field in fields.Where(f => r.Data[f] == null)) r.Unavailable(field, TelemetryRecord.FailureStatus(e), now);
        }
    }
    private static void ReadPowerProfile(TelemetryRecord r, DateTimeOffset now)
    {
        // Effective overlay is the Windows power-mode selection, not merely the
        // underlying (often always Balanced) legacy plan.
        var result = PowerGetEffectiveOverlayScheme(out var overlay);
        if (result == 0 && overlay != Guid.Empty)
        {
            var label = overlay.ToString() switch
            {
                "961cc777-2547-4f9d-8174-7d86181b8a7a" => "Best power efficiency",
                "3af9b8d9-7c97-431d-ad78-34a8bfea439f" => "Better performance",
                "ded574b5-45a0-4f42-8737-46345c09c238" => "Best performance",
                _ => null
            };
            if (label != null) { r.Set("power_profile", label, now, "PowerGetEffectiveOverlayScheme, documented Windows overlay GUID"); return; }
            // A custom overlay has no trustworthy generic label.
            return;
        }
        if (result != 0) throw new Win32Exception((int)result);
        result = PowerGetActiveScheme(IntPtr.Zero, out var scheme);
        if (result != 0) throw new Win32Exception((int)result);
        try
        {
            uint length = 0; PowerReadFriendlyName(IntPtr.Zero, scheme, IntPtr.Zero, IntPtr.Zero, null, ref length);
            if (length is 0 or > 65536) return;
            var buffer = new byte[length];
            result = PowerReadFriendlyName(IntPtr.Zero, scheme, IntPtr.Zero, IntPtr.Zero, buffer, ref length);
            if (result != 0) throw new Win32Exception((int)result);
            var name = Encoding.Unicode.GetString(buffer).TrimEnd('\0');
            if (name.Length > 0) r.Set("power_profile", name, now, "PowerGetEffectiveOverlayScheme=no overlay; PowerGetActiveScheme + PowerReadFriendlyName");
        }
        finally { LocalFree(scheme); }
    }
    private static void ReadBattery(TelemetryRecord r, DateTimeOffset now)
    {
        if (!GetSystemPowerStatus(out var status)) throw new Win32Exception(Marshal.GetLastWin32Error());
        if (!ApplySystemPowerStatus(r, status, now)) return;
        ReadBatteryDevices(r, now);
    }
    internal static bool ApplySystemPowerStatus(TelemetryRecord r, PowerStatus status, DateTimeOffset now)
    {
        if (status.AcLine is 0 or 1) r.Set("external_power_connected", status.AcLine == 1, now, "GetSystemPowerStatus.ACLineStatus");
        if (status.Flags != 255)
        {
            var present = (status.Flags & 128) == 0; r.Data["battery_present"] = present;
            if (!present) { r.Unavailable(TelemetryRecord.BatteryFields, "not_applicable", now); return false; }
            if (status.Percent <= 100) r.Set("charge_pct", (int)status.Percent, now, "GetSystemPowerStatus.BatteryLifePercent");
            if ((status.Flags & 8) != 0) r.Set("charging_state", "Charging", now, "GetSystemPowerStatus.BatteryFlag CHARGING");
        }
        if (status.Lifetime != uint.MaxValue) r.Set("battery_remaining_sec", status.Lifetime, now, "GetSystemPowerStatus.BatteryLifeTime", "estimated");
        return true;
    }
    private static void ReadBatteryDevices(TelemetryRecord r, DateTimeOffset now)
    {
        var guid = new Guid("72631e54-78a4-11d0-bcf7-00aa00b7b32a"); // GUID_DEVCLASS_BATTERY
        var devices = SetupDiGetClassDevs(ref guid, null, IntPtr.Zero, 0x12); // PRESENT | DEVICEINTERFACE
        if (devices == new IntPtr(-1)) throw new Win32Exception(Marshal.GetLastWin32Error());
        try
        {
            var batteries = new List<(BatteryInfo Info, BatteryStatus Status, SafeFileHandle Handle, uint Tag)>();
            try
            {
                for (uint index = 0; index < 16; index++)
                {
                    var device = new DeviceInterface { Size = (uint)Marshal.SizeOf<DeviceInterface>() };
                    if (!SetupDiEnumDeviceInterfaces(devices, IntPtr.Zero, ref guid, index, ref device))
                    {
                        var error = Marshal.GetLastWin32Error(); if (error != 259) throw new Win32Exception(error); break;
                    }
                    SetupDiGetDeviceInterfaceDetail(devices, ref device, IntPtr.Zero, 0, out var size, IntPtr.Zero);
                    if (size is < 6 or > 65536) continue;
                    var detail = Marshal.AllocHGlobal((int)size);
                    try
                    {
                        Marshal.WriteInt32(detail, IntPtr.Size == 8 ? 8 : 6);
                        if (!SetupDiGetDeviceInterfaceDetail(devices, ref device, detail, size, out _, IntPtr.Zero)) continue;
                        var path = Marshal.PtrToStringUni(detail + 4)!;
                        var handle = CreateFile(path, 0x80000000, 3, IntPtr.Zero, 3, 0, IntPtr.Zero);
                        if (handle.IsInvalid)
                        {
                            var error = Marshal.GetLastWin32Error(); handle.Dispose();
                            if (error == 5) throw new Win32Exception(error);
                            continue;
                        }
                        bool keep = false;
                        try
                        {
                            var tagBytes = Ioctl(handle, 0x294040, new byte[4], 4);
                            if (tagBytes == null) continue;
                            var tag = BitConverter.ToUInt32(tagBytes);
                            if (tag == 0) continue;
                            var infoBytes = QueryBattery(handle, tag, 0, 36);
                            if (infoBytes == null) continue;
                            var batteryInfo = FromBytes<BatteryInfo>(infoBytes);
                            if ((batteryInfo.Capabilities & 0x80000000) == 0 || (batteryInfo.Capabilities & 0x20000000) != 0) continue;
                            var wait = new byte[20]; BitConverter.GetBytes(tag).CopyTo(wait, 0);
                            var stateBytes = Ioctl(handle, 0x29404c, wait, 16);
                            if (stateBytes == null) continue;
                            batteries.Add((batteryInfo, FromBytes<BatteryStatus>(stateBytes), handle, tag)); keep = true;
                        }
                        finally { if (!keep) handle.Dispose(); }
                    }
                    finally { Marshal.FreeHGlobal(detail); }
                }
                // Do not pretend one battery's sensor/rate represents a machine
                // with multiple system batteries. The OS aggregate above remains.
                if (batteries.Count != 1) return;
                var b = batteries[0]; r.Data["battery_present"] = true;
                ApplySingleBattery(r, b.Info, b.Status, now);
                var temperature = QueryBattery(b.Handle, b.Tag, 2, 4);
                if (temperature != null)
                {
                    var kelvinTenths = BitConverter.ToUInt32(temperature);
                    if (kelvinTenths is > 0 and < uint.MaxValue) r.Set("battery_temp_c", kelvinTenths / 10.0 - 273.15, now, "BatteryTemperature tenths Kelvin / 10 - 273.15", "derived");
                }
                // Driver capacities are mWh (or relative), never mislabeled mAh.
            }
            finally { foreach (var battery in batteries) battery.Handle.Dispose(); }
        }
        finally { SetupDiDestroyDeviceInfoList(devices); }
    }
    internal static void ApplySingleBattery(TelemetryRecord r, BatteryInfo info, BatteryStatus state, DateTimeOffset now)
    {
        if (info.Full != uint.MaxValue && info.Full > 0 && state.Capacity != uint.MaxValue && state.Capacity <= info.Full)
            r.Set("charge_pct", (int)(100UL * state.Capacity / info.Full), now, "BATTERY_STATUS.Capacity / BATTERY_INFORMATION.FullChargedCapacity * 100, floor", "derived");
        var charging = (state.PowerState & 4) != 0; var discharging = (state.PowerState & 2) != 0;
        if (!(charging && discharging))
            r.Set("charging_state", charging ? "Charging" : discharging ? "Discharging" :
                info.Full is > 0 and < uint.MaxValue && state.Capacity == info.Full ? "Full" : "Not Charging", now, "IOCTL_BATTERY_QUERY_STATUS flags and full-charge capacity");
        if (info.Design is > 0 and < uint.MaxValue && info.Full != uint.MaxValue)
            r.Set("battery_health_pct", 100.0 * info.Full / info.Design, now, "BATTERY_INFORMATION.FullChargedCapacity / DesignedCapacity * 100 (same driver units)", "derived");
        // The driver defines 0 as unknown cycle count, not zero cycles.
        if (info.Cycles is > 0 and < uint.MaxValue) r.Set("battery_cycle_count", info.Cycles, now, "BATTERY_INFORMATION.CycleCount");
        if (state.Voltage != uint.MaxValue) r.Set("battery_voltage_v", state.Voltage / 1000.0, now, "BATTERY_STATUS.Voltage millivolts / 1000", "derived");
        if ((info.Capabilities & 0x40000000) == 0 && state.Rate != int.MinValue &&
            (charging && !discharging && state.Rate >= 0 || discharging && !charging && state.Rate <= 0))
        {
            r.Set("power_draw_w", Math.Abs((double)state.Rate) / 1000, now, "BATTERY_STATUS.Rate absolute milliwatts / 1000; absolute-capacity battery", "derived");
            r.Data["power_measurement_kind"] = charging ? "battery_charge" : "battery_discharge";
        }
    }
    private static byte[]? QueryBattery(SafeFileHandle handle, uint tag, uint level, int size)
    {
        var query = new byte[12]; BitConverter.GetBytes(tag).CopyTo(query, 0); BitConverter.GetBytes(level).CopyTo(query, 4);
        return Ioctl(handle, 0x294044, query, size);
    }
    private static byte[]? Ioctl(SafeFileHandle handle, uint code, byte[] input, int size)
    {
        var output = new byte[size];
        if (DeviceIoControl(handle, code, input, input.Length, output, size, out var returned, IntPtr.Zero) && returned >= size) return output;
        var error = Marshal.GetLastWin32Error();
        if (error == 5) throw new Win32Exception(error);
        return null;
    }
    private static T FromBytes<T>(byte[] bytes) where T : struct
    {
        var pin = GCHandle.Alloc(bytes, GCHandleType.Pinned);
        try { return Marshal.PtrToStructure<T>(pin.AddrOfPinnedObject()); }
        finally { pin.Free(); }
    }
    private static void ReadMemoryFirmware(TelemetryRecord r, DateTimeOffset now)
    {
        const uint rsmb = 0x52534d42;
        var size = GetSystemFirmwareTable(rsmb, 0, null, 0);
        if (size is < 8 or > 1048576) return;
        var buffer = new byte[size];
        if (GetSystemFirmwareTable(rsmb, 0, buffer, size) != size) return;
        var types = new HashSet<string>(); var speeds = new List<uint>(); bool allSpeeds = true, allTypes = true;
        var transferRateUnits = buffer[1] > 3 || buffer[1] == 3 && buffer[2] >= 1; // SMBIOS 3.1+ specifies MT/s.
        for (int offset = 8; offset + 4 <= buffer.Length;)
        {
            int type = buffer[offset], length = buffer[offset + 1];
            if (length < 4 || offset + length > buffer.Length) break;
            if (type == 17 && length >= 0x1c && BitConverter.ToUInt16(buffer, offset + 0x0c) != 0)
            {
                var name = buffer[offset + 0x12] switch { 0x12 => "DDR", 0x13 => "DDR2", 0x18 => "DDR3", 0x1a => "DDR4", 0x1b => "LPDDR", 0x1c => "LPDDR2", 0x1d => "LPDDR3", 0x1e => "LPDDR4", 0x22 => "DDR5", 0x23 => "LPDDR5", _ => null };
                if (name != null) types.Add(name); else allTypes = false;
                uint speed = length >= 0x22 ? BitConverter.ToUInt16(buffer, offset + 0x20) : 0u; // configured speed, MT/s
                if (speed == 65535 && length >= 0x5c) speed = BitConverter.ToUInt32(buffer, offset + 0x58);
                if (speed is > 0 and < 65535) speeds.Add(speed); else allSpeeds = false;
            }
            int next = offset + length;
            while (next + 1 < buffer.Length && (buffer[next] != 0 || buffer[next + 1] != 0)) next++;
            offset = next + 2;
            if (type == 127) break;
        }
        if (allTypes && types.Count > 0) r.Set("memory_type", string.Join(" / ", types.Order()), now, "GetSystemFirmwareTable RSMB, SMBIOS type 17 MemoryType");
        if (transferRateUnits && allSpeeds && speeds.Count > 0 && speeds.Distinct().Count() == 1) r.Set("memory_speed_mt_s", speeds[0], now, "SMBIOS 3.1+ type 17 ConfiguredMemorySpeed (MT/s, not MHz)");
    }
    public void Dispose() { usage.Dispose(); DisposeMediaActivity(); audioEnumerator?.Dispose(); }

    [StructLayout(LayoutKind.Sequential)] private struct MemoryStatus { public uint Length, Load; public ulong TotalPhysical, AvailablePhysical, TotalPageFile, AvailablePageFile, TotalVirtual, AvailableVirtual, AvailableExtendedVirtual; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct OsVersion { public uint Size, Major, Minor, Build, Platform; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string ServicePack; }
    [StructLayout(LayoutKind.Sequential)] internal struct PowerStatus { public byte AcLine, Flags, Percent, Saver; public uint Lifetime, FullLifetime; }
    [StructLayout(LayoutKind.Sequential)] private struct DeviceInterface { public uint Size; public Guid Guid; public uint Flags; public IntPtr Reserved; }
    [StructLayout(LayoutKind.Sequential)] internal struct BatteryInfo { public uint Capabilities, TechnologyAndReserved, Chemistry, Design, Full, Alert1, Alert2, Bias, Cycles; }
    [StructLayout(LayoutKind.Sequential)] internal struct BatteryStatus { public uint PowerState, Capacity, Voltage; public int Rate; }
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GlobalMemoryStatusEx(ref MemoryStatus status);
    [DllImport("kernel32.dll")] private static extern uint GetActiveProcessorCount(ushort group);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetLogicalProcessorInformationEx(int relationship, IntPtr data, ref uint length);
    [DllImport("kernel32.dll")] private static extern ulong GetTickCount64();
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint GetSystemFirmwareTable(uint provider, uint id, byte[]? buffer, uint length);
    [DllImport("ntdll.dll", CharSet = CharSet.Unicode)] private static extern int RtlGetVersion(ref OsVersion version);
    [DllImport("powrprof.dll")] private static extern uint PowerGetEffectiveOverlayScheme(out Guid scheme);
    [DllImport("powrprof.dll")] private static extern uint PowerGetActiveScheme(IntPtr root, out IntPtr scheme);
    [DllImport("powrprof.dll")] private static extern uint PowerReadFriendlyName(IntPtr root, IntPtr scheme, IntPtr subgroup, IntPtr setting, byte[]? buffer, ref uint size);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetSystemPowerStatus(out PowerStatus status);
    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr SetupDiGetClassDevs(ref Guid guid, string? enumerator, IntPtr parent, uint flags);
    [DllImport("setupapi.dll", SetLastError = true)] private static extern bool SetupDiEnumDeviceInterfaces(IntPtr devices, IntPtr device, ref Guid guid, uint index, ref DeviceInterface data);
    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool SetupDiGetDeviceInterfaceDetail(IntPtr devices, ref DeviceInterface data, IntPtr detail, uint length, out uint required, IntPtr deviceInfo);
    [DllImport("setupapi.dll")] private static extern bool SetupDiDestroyDeviceInfoList(IntPtr devices);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern SafeFileHandle CreateFile(string path, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool DeviceIoControl(SafeFileHandle handle, uint code, byte[] input, int inputSize, byte[] output, int outputSize, out int returned, IntPtr overlapped);
}
