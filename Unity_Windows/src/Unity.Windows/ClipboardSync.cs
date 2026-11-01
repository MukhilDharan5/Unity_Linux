using Unity.Core;
using Microsoft.UI.Xaml;
using WinRT.Interop;

namespace Unity.Windows;

public sealed partial class MainWindow
{
    private readonly Dictionary<string, (string Generation, string Text)> clipboardDelivered = [];
    private readonly HashSet<string> clipboardSending = [];
    private readonly DispatcherTimer clipboardRetry = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private NativeClipboard? nativeClipboard;
    private string? clipboardText;
    private (string Device, string Generation, string Text)? clipboardWrite;
    private bool clipboardReadPending, clipboardStopped;

    private void StartClipboardSync()
    {
        try { nativeClipboard = new(WindowNative.GetWindowHandle(this), () => DispatcherQueue.TryEnqueue(ReadClipboard)); }
        catch (Exception e) { App.WriteLog("Clipboard observer unavailable: " + e.GetType().Name); return; }
        host.Changed += ClipboardConnectionChanged;
        clipboardRetry.Tick += (_, _) => SyncClipboard();
        clipboardRetry.Start();
        ReadClipboard();
    }
    private void StopClipboardSync()
    {
        clipboardStopped = true;
        nativeClipboard?.Dispose(); nativeClipboard = null;
        host.Changed -= ClipboardConnectionChanged;
        clipboardRetry.Stop();
        clipboardText = null; clipboardDelivered.Clear(); clipboardWrite = null;
    }
    private void ClipboardConnectionChanged() => DispatcherQueue.TryEnqueue(SyncClipboard);
    private void ReadClipboard()
    {
        if (clipboardStopped || nativeClipboard == null) return;
        clipboardReadPending = !nativeClipboard.TryRead(out var text);
        if (clipboardReadPending) return;
        if (text != clipboardText) { clipboardWrite = null; clipboardText = text; clipboardDelivered.Clear(); }
        SyncClipboard();
    }
    private void ApplyClipboard(string device, string text)
    {
        if (clipboardStopped) return;
        try
        {
            var session = host.Select(device, "clipboard");
            if (clipboardWrite == null && clipboardText == text)
            {
                clipboardDelivered[device] = (session.Generation, text);
                SyncClipboard();
                return;
            }
            clipboardWrite = (device, session.Generation, text);
            SyncClipboard();
        }
        catch (Exception e) { App.WriteLog("Clipboard write unavailable: " + e.GetType().Name); }
    }
    private void SyncClipboard()
    {
        if (clipboardStopped || nativeClipboard == null) return;
        if (clipboardWrite is { } incoming)
        {
            if (!host.Sessions.Any(s => s.DeviceId == incoming.Device && s.Generation == incoming.Generation && s.Allows("clipboard"))) clipboardWrite = null;
            else
            {
                if (!nativeClipboard.TryWrite(incoming.Text)) return;
                if (clipboardText != incoming.Text) clipboardDelivered.Clear();
                clipboardText = incoming.Text;
                clipboardDelivered[incoming.Device] = (incoming.Generation, incoming.Text);
                clipboardWrite = null;
            }
        }
        if (clipboardReadPending) { ReadClipboard(); return; }
        if (clipboardText is not { } text) return;
        var devices = host.Sessions.Where(s => s.Allows("clipboard")).Select(s => s.DeviceId).Distinct().ToHashSet();
        foreach (var id in clipboardDelivered.Keys.Where(id => !devices.Contains(id)).ToArray()) clipboardDelivered.Remove(id);
        foreach (var id in devices)
        {
            DeviceSession session;
            try { session = host.Select(id, "clipboard"); } catch (IOException) { continue; }
            if (clipboardSending.Contains(id) || clipboardDelivered.TryGetValue(id, out var sent) && sent.Text == text && host.Sessions.Any(s => s.DeviceId == id && s.Generation == sent.Generation)) continue;
            clipboardSending.Add(id);
            _ = SendClipboardAsync(session, text);
        }
    }
    private async Task SendClipboardAsync(DeviceSession session, string text)
    {
        try
        {
            await session.CommandAsync("clipboard", "set", Wire.Object(("text", text), ("automatic", true)), timeout: TimeSpan.FromSeconds(5));
            if (!clipboardStopped && clipboardText == text) clipboardDelivered[session.DeviceId] = (session.Generation, text);
        }
        catch (Exception e) { App.WriteLog("Clipboard sync deferred: " + e.GetType().Name); }
        finally { clipboardSending.Remove(session.DeviceId); }
    }
}
