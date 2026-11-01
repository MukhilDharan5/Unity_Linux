using System.Runtime.InteropServices;

namespace Unity.Windows;

internal sealed class TrayIcon : IDisposable
{
    private readonly IntPtr window;
    private readonly Action show, quit;
    private readonly SubclassProc callback;
    private const uint CallbackMessage = 0x8000 + 73;
    private NotifyData data;
    public TrayIcon(IntPtr window, Action show, Action quit)
    {
        this.window = window; this.show = show; this.quit = quit; callback = WindowProc;
        if (!SetWindowSubclass(window, callback, 73, 0)) throw new System.ComponentModel.Win32Exception();
        data = new() { Size = (uint)Marshal.SizeOf<NotifyData>(), Window = window, Id = 1, Flags = 1 | 2 | 4,
            Callback = CallbackMessage, Icon = LoadIcon(IntPtr.Zero, (IntPtr)32512), Tip = "Unity · Phone companion", Info = "", InfoTitle = "" };
        if (!Shell_NotifyIcon(0, ref data)) { RemoveWindowSubclass(window, callback, 73); throw new IOException("Could not create the Unity notification-area icon"); }
    }
    public void Notify(string title, string message)
    {
        data.Flags = 16; data.InfoTitle = title[..Math.Min(title.Length, 63)]; data.Info = message[..Math.Min(message.Length, 255)]; data.InfoFlags = 1; Shell_NotifyIcon(1, ref data);
    }
    private IntPtr WindowProc(IntPtr h, uint message, UIntPtr w, IntPtr l, UIntPtr id, UIntPtr reference)
    {
        if (message == CallbackMessage)
        {
            if (l.ToInt64() is 0x0202 or 0x0203 or 0x0405) show();
            if (l.ToInt64() == 0x0205)
            {
                var menu = CreatePopupMenu();
                try { AppendMenu(menu, 0, 1, "Open Unity"); AppendMenu(menu, 0, 2, "Quit Unity"); GetCursorPos(out var point); SetForegroundWindow(window);
                    var selected = TrackPopupMenu(menu, 0x0100 | 0x0002, point.X, point.Y, 0, window, IntPtr.Zero); if (selected == 1) show(); if (selected == 2) quit(); }
                finally { DestroyMenu(menu); }
            }
        }
        return DefSubclassProc(h, message, w, l);
    }
    public void Dispose() { Shell_NotifyIcon(2, ref data); RemoveWindowSubclass(window, callback, 73); }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct NotifyData
    {
        public uint Size; public IntPtr Window; public uint Id, Flags, Callback; public IntPtr Icon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Tip;
        public uint State, StateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string Info;
        public uint Timeout;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string InfoTitle;
        public uint InfoFlags; public Guid Guid; public IntPtr BalloonIcon;
    }
    private delegate IntPtr SubclassProc(IntPtr h, uint m, UIntPtr w, IntPtr l, UIntPtr id, UIntPtr data);
    [StructLayout(LayoutKind.Sequential)] private struct Point { public int X, Y; }
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)] private static extern bool Shell_NotifyIcon(uint action, ref NotifyData data);
    [DllImport("comctl32.dll")] private static extern bool SetWindowSubclass(IntPtr h, SubclassProc proc, UIntPtr id, UIntPtr data);
    [DllImport("comctl32.dll")] private static extern bool RemoveWindowSubclass(IntPtr h, SubclassProc proc, UIntPtr id);
    [DllImport("comctl32.dll")] private static extern IntPtr DefSubclassProc(IntPtr h, uint m, UIntPtr w, IntPtr l);
    [DllImport("user32.dll")] private static extern IntPtr LoadIcon(IntPtr instance, IntPtr id);
    [DllImport("user32.dll")] private static extern IntPtr CreatePopupMenu();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool AppendMenu(IntPtr menu, uint flags, uint id, string title);
    [DllImport("user32.dll")] private static extern uint TrackPopupMenu(IntPtr menu, uint flags, int x, int y, int reserved, IntPtr window, IntPtr rect);
    [DllImport("user32.dll")] private static extern bool DestroyMenu(IntPtr menu);
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out Point point);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr window);
}
