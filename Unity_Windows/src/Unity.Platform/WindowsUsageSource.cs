using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace Unity.Platform;

internal sealed class WindowsUsageSource : IDisposable
{
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] private delegate uint PowerCallback(IntPtr context, uint type, IntPtr setting);
    [StructLayout(LayoutKind.Sequential)] private struct Subscription { public IntPtr Callback, Context; }
    [StructLayout(LayoutKind.Sequential)] private struct LastInput { public uint Size, Tick; }
    private readonly PowerCallback callback;
    private IntPtr registration;
    private int displayState = -1;
    private readonly Dictionary<string, string> appNames = [];
    public WindowsUsageSource()
    {
        callback = (_, type, data) =>
        {
            // POWERBROADCAST_SETTING: GUID, DWORD length, DWORD value.
            if (type == 0x8013 && data != IntPtr.Zero && Marshal.ReadInt32(data, 16) == 4)
                Volatile.Write(ref displayState, Marshal.ReadInt32(data, 20));
            return 0;
        };
        var setting = new Guid("2b84c20e-ad23-4ddf-93db-05ffbd7efca5"); // GUID_SESSION_DISPLAY_STATUS
        var subscription = new Subscription { Callback = Marshal.GetFunctionPointerForDelegate(callback) };
        var result = PowerSettingRegisterNotification(ref setting, 2, ref subscription, out registration);
        if (result != 0) registration = IntPtr.Zero; // Unknown display state must not count as screen use.
    }
    public UsageObservation Read(DateTimeOffset now)
    {
        bool known = false, locked = false;
        // WTSINFOEX: level DWORD + alignment, then session id/state/flags.
        if (WTSQuerySessionInformation(IntPtr.Zero, -1, 25, out var info, out var bytes))
        {
            try
            {
                if (bytes >= 20 && Marshal.ReadInt32(info) == 1)
                {
                    var state = Marshal.ReadInt32(info, 12); var flags = Marshal.ReadInt32(info, 16);
                    known = flags is 0 or 1; locked = flags == 0 || state != 0;
                }
            }
            finally { WTSFreeMemory(info); }
        }
        var input = new LastInput { Size = (uint)Marshal.SizeOf<LastInput>() };
        var inputKnown = GetLastInputInfo(ref input);
        var idle = inputKnown ? unchecked((uint)Environment.TickCount - input.Tick) / 1000.0 : -1;
        var display = Volatile.Read(ref displayState);
        known &= inputKnown && display >= 0;
        string? id = null, name = null;
        if (known && !locked && display is 1 or 2 && idle < TelemetryUsage.IdleCutoffSeconds)
        {
            var foreground = GetForegroundWindow();
            if (foreground != IntPtr.Zero)
            {
                GetWindowThreadProcessId(foreground, out var pid);
                using var process = OpenProcess(0x1000, false, pid); // PROCESS_QUERY_LIMITED_INFORMATION
                if (!process.IsInvalid)
                {
                    var path = new StringBuilder(32768); uint length = (uint)path.Capacity;
                    if (QueryFullProcessImageName(process, 0, path, ref length))
                    {
                        var image = path.ToString();
                        id = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(image.ToUpperInvariant())));
                        if (!appNames.TryGetValue(id, out name))
                        {
                            name = Path.GetFileNameWithoutExtension(image);
                            try { var description = FileVersionInfo.GetVersionInfo(image).FileDescription; if (!string.IsNullOrWhiteSpace(description)) name = description; }
                            catch (Exception e) when (e is IOException or UnauthorizedAccessException or Win32Exception) { }
                            if (appNames.Count >= 512) appNames.Clear();
                            appNames[id] = name;
                        }
                    }
                }
            }
        }
        return new(now, known, locked, display is 1 or 2, idle, id, name);
    }
    public void Dispose()
    {
        if (registration != IntPtr.Zero) { PowerSettingUnregisterNotification(registration); registration = IntPtr.Zero; }
        GC.KeepAlive(callback);
    }
    [DllImport("powrprof.dll")] private static extern uint PowerSettingRegisterNotification(ref Guid setting, uint flags, ref Subscription recipient, out IntPtr handle);
    [DllImport("powrprof.dll")] private static extern uint PowerSettingUnregisterNotification(IntPtr handle);
    [DllImport("wtsapi32.dll", CharSet = CharSet.Unicode, EntryPoint = "WTSQuerySessionInformationW")] private static extern bool WTSQuerySessionInformation(IntPtr server, int session, int infoClass, out IntPtr data, out uint bytes);
    [DllImport("wtsapi32.dll")] private static extern void WTSFreeMemory(IntPtr data);
    [DllImport("user32.dll")] private static extern bool GetLastInputInfo(ref LastInput input);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern Microsoft.Win32.SafeHandles.SafeProcessHandle OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool QueryFullProcessImageName(Microsoft.Win32.SafeHandles.SafeProcessHandle process, uint flags, StringBuilder path, ref uint length);
}
