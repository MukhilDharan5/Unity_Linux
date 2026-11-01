using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using Unity.Core;

namespace Unity.Platform;

public static class NativeDesktop
{
    private static readonly object inputGate = new();
    private static readonly HashSet<uint> pressedButtons = [];
    [DllImport("user32.dll")] public static extern bool LockWorkStation();
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern uint SendInput(uint count, [In] Input[] input, int size);
    [StructLayout(LayoutKind.Sequential)] private struct Input { public uint Type; public InputUnion Value; }
    [StructLayout(LayoutKind.Explicit)] private struct InputUnion { [FieldOffset(0)] public Mouse Mouse; [FieldOffset(0)] public Keyboard Keyboard; }
    [StructLayout(LayoutKind.Sequential)] private struct Mouse { public int X, Y; public uint Data, Flags, Time; public UIntPtr Extra; }
    [StructLayout(LayoutKind.Sequential)] private struct Keyboard { public ushort Key, Scan; public uint Flags, Time; public UIntPtr Extra; }
    private static void Send(params Input[] inputs) => Wire.Require(SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>()) == inputs.Length, "Windows blocked remote input; elevated windows cannot be controlled");
    public static byte[] Capture()
    {
        int w = GetSystemMetrics(0), h = GetSystemMetrics(1);
        Wire.Require(w > 0 && h > 0, "No desktop display");
        var ratio = Math.Min(1, Math.Min(1280.0 / Math.Max(w, h), Math.Sqrt(921600.0 / ((long)w * h))));
        using var full = new Bitmap(w, h, PixelFormat.Format24bppRgb);
        using (var g = Graphics.FromImage(full)) g.CopyFromScreen(0, 0, 0, 0, new Size(w, h));
        using var small = new Bitmap(full, new Size(Math.Max(1, (int)(w * ratio)), Math.Max(1, (int)(h * ratio))));
        var codec = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
        for (long quality = 75; quality >= 15; quality -= 15)
        {
            using var memory = new MemoryStream(); using var parameters = new EncoderParameters(1);
            parameters.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, quality);
            small.Save(memory, codec, parameters); if (memory.Length <= 262144) return memory.ToArray();
        }
        throw new IOException("Desktop image exceeds screen protocol limit");
    }
    public static void InputEvent(JsonObject data)
    {
        var kind = data.Str("kind");
        if (kind is "pointer" or "motion" or "tap")
        {
            var x = data["x"]!.GetValue<double>(); var y = data["y"]!.GetValue<double>();
            Wire.Require(double.IsFinite(x) && double.IsFinite(y) && x is >= 0 and <= 1 && y is >= 0 and <= 1, "Invalid coordinates");
            SetCursorPos((int)(x * (GetSystemMetrics(0) - 1)), (int)(y * (GetSystemMetrics(1) - 1)));
            if (kind == "motion") return;
            if (kind == "tap") { Send(new() { Value = new() { Mouse = new() { Flags = 2 } } }, new() { Value = new() { Mouse = new() { Flags = 4 } } }); return; }
            var button = data.Str("button", "left"); Wire.Require(button is "left" or "right" or "middle", "Invalid pointer button");
            uint flag = button == "right" ? (data.Bool("pressed") ? 8u : 16u) : button == "middle" ? (data.Bool("pressed") ? 32u : 64u) : (data.Bool("pressed") ? 2u : 4u);
            lock (inputGate)
            {
                Send(new Input { Value = new() { Mouse = new() { Flags = flag } } });
                uint releaseFlag = button == "right" ? 16u : button == "middle" ? 64u : 4u;
                if (data.Bool("pressed")) pressedButtons.Add(releaseFlag); else pressedButtons.Remove(releaseFlag);
            }
            return;
        }
        if (kind == "scroll")
        {
            foreach (var (key, flag) in new[] { ("dy", 0x0800u), ("dx", 0x1000u) })
            { var value = data[key]?.GetValue<double>() ?? 0; Wire.Require(double.IsFinite(value) && Math.Abs(value) <= 20, "Invalid scroll"); if (value != 0) Send(new Input { Value = new() { Mouse = new() { Flags = flag, Data = unchecked((uint)(int)(value * 120)) } } }); }
            return;
        }
        if (kind == "text")
        {
            var text = data.Str("text"); Wire.Text(text, 256);
            foreach (var c in text) Send(new() { Type = 1, Value = new() { Keyboard = new() { Scan = c, Flags = 4 } } }, new() { Type = 1, Value = new() { Keyboard = new() { Scan = c, Flags = 6 } } });
            return;
        }
        if (kind == "key")
        {
            var keys = new Dictionary<string, ushort> { ["enter"] = 13, ["backspace"] = 8, ["tab"] = 9, ["escape"] = 27, ["delete"] = 46, ["left"] = 37, ["up"] = 38, ["right"] = 39, ["down"] = 40, ["home"] = 36, ["end"] = 35, ["page_up"] = 33, ["page_down"] = 34, ["space"] = 32 };
            var requestedKey = data.Str("key");
            if (requestedKey.Length == 1 && char.IsAsciiLetterOrDigit(requestedKey[0])) keys[requestedKey] = char.ToUpperInvariant(requestedKey[0]);
            Wire.Require(keys.TryGetValue(requestedKey, out var vk), "Unsupported key");
            var repeat = data.Num("repeat", 1); Wire.Require(repeat is >= 1 and <= 32, "Invalid repeat");
            var modifiers = data["modifiers"] as JsonArray ?? new(); Wire.Require(modifiers.Count <= 4, "Too many modifiers");
            var modifierKeys = new Dictionary<string, ushort> { ["ctrl"] = 17, ["alt"] = 18, ["shift"] = 16, ["super"] = 91 };
            var held = new List<ushort>();
            try
            {
                foreach (var modifier in modifiers) { Wire.Require(modifierKeys.TryGetValue(modifier!.GetValue<string>(), out var key) && !held.Contains(key), "Invalid modifier"); Send(new Input { Type = 1, Value = new() { Keyboard = new() { Key = key } } }); held.Add(key); }
                for (int i = 0; i < repeat; i++) Send(new() { Type = 1, Value = new() { Keyboard = new() { Key = vk } } }, new() { Type = 1, Value = new() { Keyboard = new() { Key = vk, Flags = 2 } } });
            }
            finally { foreach (var key in held.AsEnumerable().Reverse()) Send(new Input { Type = 1, Value = new() { Keyboard = new() { Key = key, Flags = 2 } } }); }
            return;
        }
        throw new NotSupportedException("Unsupported remote input");
    }
    public static void ReleaseButtons()
    {
        lock (inputGate)
        {
            if (pressedButtons.Count == 0) return;
            var flags = pressedButtons.Aggregate(0u, (a, b) => a | b); pressedButtons.Clear();
            try { Send(new Input { Value = new() { Mouse = new() { Flags = flags } } }); } catch { }
        }
    }
}
