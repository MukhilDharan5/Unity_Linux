using System.Collections.Concurrent;
using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace Unity.Platform;

public interface IDesktopDisplay : IDisposable
{
    bool IsOn { get; }
    string Mode { get; }
    Action? Restored { get; set; }
    void Set(bool on);
}

/// <summary>A local black cover excluded from capture. Windows keeps rendering for the phone.</summary>
public sealed class DisplayBlanking : IDesktopDisplay
{
    private readonly SafeFileHandle power;
    private readonly Thread worker;
    private readonly WindowProcedure procedure;
    private readonly TaskCompletionSource ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly ConcurrentQueue<(bool On, TaskCompletionSource Done)> pending = new();
    private nint window;
    private int disposed;
    private volatile bool on = true;
    private bool hotkey, displayRequired;
    public bool IsOn => on;
    public string Mode => "blank";
    public Action? Restored { get; set; }
    private const uint ApplyMessage = 0x8001;
    [StructLayout(LayoutKind.Sequential)] private struct Reason { public uint Version, Flags; public nint Text; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WindowClass
    {
        public uint Style; public WindowProcedure Procedure; public int ClassExtra, WindowExtra;
        public nint Instance, Icon, Cursor, Background;
        [MarshalAs(UnmanagedType.LPWStr)] public string? Menu;
        [MarshalAs(UnmanagedType.LPWStr)] public string Name;
    }
    [StructLayout(LayoutKind.Sequential)] private struct Message { public nint Window; public uint Id; public nuint WParam; public nint LParam; public uint Time; public int X, Y; public uint Private; }
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] private delegate nint WindowProcedure(nint window, uint message, nuint wParam, nint lParam);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern SafeFileHandle PowerCreateRequest(ref Reason reason);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool PowerSetRequest(SafeFileHandle handle, int type);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool PowerClearRequest(SafeFileHandle handle, int type);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern ushort RegisterClass(ref WindowClass cls);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool UnregisterClass(string name, nint instance);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern nint CreateWindowEx(uint extendedStyle, string className, string title, uint style, int x, int y, int width, int height, nint parent, nint menu, nint instance, nint parameter);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern nint DefWindowProc(nint window, uint message, nuint wParam, nint lParam);
    [DllImport("user32.dll")] private static extern bool DestroyWindow(nint window);
    [DllImport("user32.dll")] private static extern bool ShowWindow(nint window, int command);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool SetWindowDisplayAffinity(nint window, uint affinity);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool SetLayeredWindowAttributes(nint window, uint color, byte alpha, uint flags);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool SetWindowPos(nint window, nint after, int x, int y, int width, int height, uint flags);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
    [DllImport("gdi32.dll")] private static extern nint GetStockObject(int index);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool RegisterHotKey(nint window, int id, uint modifiers, uint key);
    [DllImport("user32.dll")] private static extern bool UnregisterHotKey(nint window, int id);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool PostMessage(nint window, uint message, nuint wParam, nint lParam);
    [DllImport("user32.dll")] private static extern int GetMessage(out Message message, nint window, uint minimum, uint maximum);
    [DllImport("user32.dll")] private static extern bool TranslateMessage(in Message message);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern nint DispatchMessage(in Message message);
    [DllImport("user32.dll")] private static extern void PostQuitMessage(int code);
    private static void Require(bool result, string message) { if (!result) throw new Win32Exception(Marshal.GetLastWin32Error(), message); }
    public DisplayBlanking()
    {
        var text = Marshal.StringToHGlobalUni("Unity is sharing the Windows screen with your phone");
        try { var reason = new Reason { Flags = 1, Text = text }; power = PowerCreateRequest(ref reason); }
        finally { Marshal.FreeHGlobal(text); }
        if (power.IsInvalid || !PowerSetRequest(power, 1)) { power.Dispose(); throw new Win32Exception(Marshal.GetLastWin32Error(), "Windows could not keep screen sharing awake"); }
        procedure = ProcessMessage;
        worker = new Thread(Run) { IsBackground = true, Name = "Unity display blanking" };
        worker.Start();
        try { ready.Task.WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult(); }
        catch { Dispose(); throw; }
    }
    private void Apply(bool value)
    {
        if (value)
        {
            ShowWindow(window, 0); on = true;
            if (hotkey) { UnregisterHotKey(window, 1); hotkey = false; }
            if (displayRequired) { PowerClearRequest(power, 0); displayRequired = false; }
            return;
        }
        try
        {
            if (!hotkey) { Require(RegisterHotKey(window, 1, 0x4007, 0x55), "The restore shortcut Ctrl+Alt+Shift+U is already in use"); hotkey = true; }
            if (!displayRequired) { Require(PowerSetRequest(power, 0), "Windows could not keep the shared desktop rendering"); displayRequired = true; }
            Require(SetWindowPos(window, -1, GetSystemMetrics(76), GetSystemMetrics(77), GetSystemMetrics(78), GetSystemMetrics(79), 0x0050), "Windows could not blank the local displays");
            on = false;
        }
        catch { Apply(true); throw; }
    }
    private nint ProcessMessage(nint handle, uint message, nuint wParam, nint lParam)
    {
        if (message == ApplyMessage)
        {
            while (pending.TryDequeue(out var change))
            {
                try { Apply(change.On); change.Done.TrySetResult(); }
                catch (Exception e) { change.Done.TrySetException(e); }
            }
            return 0;
        }
        if (message == 0x0312 && wParam == 1)
        {
            Apply(true); ThreadPool.QueueUserWorkItem(_ => Restored?.Invoke()); return 0;
        }
        if (message == 0x007e && !on) { try { Apply(false); } catch { Apply(true); ThreadPool.QueueUserWorkItem(_ => Restored?.Invoke()); } return 0; }
        if (message == 0x0010) { Apply(true); DestroyWindow(handle); return 0; }
        if (message == 0x0002) { PostQuitMessage(0); return 0; }
        return DefWindowProc(handle, message, wParam, lParam);
    }
    private void Run()
    {
        var name = "UnityBlank-" + Guid.NewGuid().ToString("N");
        try
        {
            var cls = new WindowClass { Procedure = procedure, Background = GetStockObject(4), Name = name };
            Require(RegisterClass(ref cls) != 0, "Windows display blanking is unavailable");
            // Topmost, layered, click-through, tool window, never activated.
            window = CreateWindowEx(0x080800a8, name, "Unity · Laptop display blanked", 0x80000000, 0, 0, 0, 0, 0, 0, 0, 0);
            Require(window != 0, "Windows could not create the display cover");
            Require(SetLayeredWindowAttributes(window, 0, 255, 2), "Windows display composition is unavailable");
            Require(SetWindowDisplayAffinity(window, 0x11), "Windows could not exclude the display cover from capture");
            ready.TrySetResult();
            while (GetMessage(out var message, 0, 0, 0) > 0) { TranslateMessage(in message); DispatchMessage(in message); }
        }
        catch (Exception e) { ready.TrySetException(e); }
        finally
        {
            Apply(true); if (window != 0) DestroyWindow(window); UnregisterClass(name, 0);
            PowerClearRequest(power, 1); power.Dispose();
            while (pending.TryDequeue(out var change)) change.Done.TrySetException(new IOException("Screen sharing ended"));
            GC.KeepAlive(procedure);
        }
    }
    public void Set(bool value)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref disposed) != 0, this);
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        pending.Enqueue((value, done)); Require(PostMessage(window, ApplyMessage, 0, 0), "Windows display control is unavailable");
        done.Task.WaitAsync(TimeSpan.FromSeconds(3)).GetAwaiter().GetResult();
    }
    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0) return;
        if (window != 0) PostMessage(window, 0x0010, 0, 0);
        if (Thread.CurrentThread != worker) worker.Join(TimeSpan.FromSeconds(3));
    }
}
