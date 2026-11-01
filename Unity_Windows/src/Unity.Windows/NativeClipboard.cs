using System.Runtime.InteropServices;
using System.Text;
using Unity.Core;

namespace Unity.Windows;

/// <summary>Desktop clipboard access also works while the WinUI window is hidden in the tray.</summary>
internal sealed class NativeClipboard : IDisposable
{
    private const uint UnicodeText = 13;
    private readonly IntPtr window;
    private readonly SubclassProc callback;
    private readonly Action changed;
    public NativeClipboard(IntPtr window, Action changed)
    {
        this.window = window; this.changed = changed; callback = WindowProc;
        if (!SetWindowSubclass(window, callback, 74, 0)) throw new System.ComponentModel.Win32Exception();
        if (!AddClipboardFormatListener(window))
        {
            RemoveWindowSubclass(window, callback, 74);
            throw new System.ComponentModel.Win32Exception();
        }
    }
    private IntPtr WindowProc(IntPtr h, uint message, UIntPtr w, IntPtr l, UIntPtr id, UIntPtr reference)
    {
        if (message == 0x031D) changed(); // WM_CLIPBOARDUPDATE
        return DefSubclassProc(h, message, w, l);
    }
    public bool TryRead(out string? text)
    {
        text = null;
        if (!OpenClipboard(window)) return false;
        try
        {
            if (!IsClipboardFormatAvailable(UnicodeText)) return true;
            var handle = GetClipboardData(UnicodeText);
            if (handle == IntPtr.Zero) return false;
            var bytes = GlobalSize(handle).ToUInt64();
            if (bytes < 2) return true;
            var pointer = GlobalLock(handle);
            if (pointer == IntPtr.Zero) return false;
            try
            {
                var chars = new char[(int)Math.Min(bytes / 2, (ulong)Wire.MaxText + 1)];
                Marshal.Copy(pointer, chars, 0, chars.Length);
                var end = Array.IndexOf(chars, '\0');
                if (end < 0) return true;
                var value = new string(chars, 0, end);
                if (Encoding.UTF8.GetByteCount(value) <= Wire.MaxText - 256) text = value;
                return true;
            }
            finally { GlobalUnlock(handle); }
        }
        finally { CloseClipboard(); }
    }
    public bool TryWrite(string text)
    {
        if (!OpenClipboard(window)) return false;
        IntPtr handle = IntPtr.Zero;
        try
        {
            var bytes = Encoding.Unicode.GetBytes(text + '\0');
            handle = GlobalAlloc(0x0002, (UIntPtr)bytes.Length);
            if (handle == IntPtr.Zero) return false;
            var pointer = GlobalLock(handle);
            if (pointer == IntPtr.Zero) return false;
            try { Marshal.Copy(bytes, 0, pointer, bytes.Length); }
            finally { GlobalUnlock(handle); }
            if (!EmptyClipboard() || SetClipboardData(UnicodeText, handle) == IntPtr.Zero) return false;
            handle = IntPtr.Zero; // Windows owns the allocation after SetClipboardData succeeds.
            return true;
        }
        finally { if (handle != IntPtr.Zero) GlobalFree(handle); CloseClipboard(); }
    }
    public void Dispose() { RemoveClipboardFormatListener(window); RemoveWindowSubclass(window, callback, 74); }
    private delegate IntPtr SubclassProc(IntPtr h, uint m, UIntPtr w, IntPtr l, UIntPtr id, UIntPtr reference);
    [DllImport("comctl32.dll")] private static extern bool SetWindowSubclass(IntPtr h, SubclassProc proc, UIntPtr id, UIntPtr reference);
    [DllImport("comctl32.dll")] private static extern bool RemoveWindowSubclass(IntPtr h, SubclassProc proc, UIntPtr id);
    [DllImport("comctl32.dll")] private static extern IntPtr DefSubclassProc(IntPtr h, uint m, UIntPtr w, IntPtr l);
    [DllImport("user32.dll")] private static extern bool AddClipboardFormatListener(IntPtr h);
    [DllImport("user32.dll")] private static extern bool RemoveClipboardFormatListener(IntPtr h);
    [DllImport("user32.dll")] private static extern bool OpenClipboard(IntPtr h);
    [DllImport("user32.dll")] private static extern bool CloseClipboard();
    [DllImport("user32.dll")] private static extern bool EmptyClipboard();
    [DllImport("user32.dll")] private static extern bool IsClipboardFormatAvailable(uint format);
    [DllImport("user32.dll")] private static extern IntPtr GetClipboardData(uint format);
    [DllImport("user32.dll")] private static extern IntPtr SetClipboardData(uint format, IntPtr data);
    [DllImport("kernel32.dll")] private static extern UIntPtr GlobalSize(IntPtr handle);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalAlloc(uint flags, UIntPtr bytes);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalLock(IntPtr handle);
    [DllImport("kernel32.dll")] private static extern bool GlobalUnlock(IntPtr handle);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalFree(IntPtr handle);
}
