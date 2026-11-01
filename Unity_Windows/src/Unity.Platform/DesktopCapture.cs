using System.Runtime.InteropServices;
using Unity.Core;
using Windows.Foundation;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Graphics.DirectX.Direct3D11;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;
using WinRT;

namespace Unity.Platform;

/// <summary>One latest GPU frame, independent of the local capture-excluded black cover.</summary>
public sealed class DesktopCapture : IDisposable
{
    private readonly object gate = new();
    private readonly IDirect3DDevice device;
    private readonly Direct3D11CaptureFramePool pool;
    private readonly GraphicsCaptureSession session;
    private readonly GraphicsCaptureItem item;
    private readonly SemaphoreSlim available = new(0, 1);
    private Direct3D11CaptureFrame? latest;
    private Exception? failure;
    private byte[]? lastJpeg;
    private bool disposed;
    private Windows.Graphics.SizeInt32 size;
    [StructLayout(LayoutKind.Sequential)] private struct Point { public int X, Y; }
    [ComImport, Guid("3628e81b-3cac-4c60-b7f4-23ce0e0c3356"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface CaptureInterop
    {
        void CreateForWindow(nint window, in Guid iid, out nint item);
        void CreateForMonitor(nint monitor, in Guid iid, out nint item);
    }
    [DllImport("user32.dll")] private static extern nint MonitorFromPoint(Point point, uint flags);
    [DllImport("combase.dll", CharSet = CharSet.Unicode)] private static extern int WindowsCreateString(string value, int length, out nint text);
    [DllImport("combase.dll")] private static extern int WindowsDeleteString(nint text);
    [DllImport("combase.dll")] private static extern int RoGetActivationFactory(nint name, in Guid iid, out nint factory);
    [DllImport("d3d11.dll")] private static extern int D3D11CreateDevice(nint adapter, int driver, nint software, uint flags, nint levels, uint levelCount, uint sdk, out nint device, out int level, out nint context);
    [DllImport("d3d11.dll")] private static extern int CreateDirect3D11DeviceFromDXGIDevice(nint dxgi, out nint device);
    private static IDirect3DDevice CreateDevice()
    {
        nint native = 0, context = 0, dxgi = 0, projected = 0;
        try
        {
            Marshal.ThrowExceptionForHR(D3D11CreateDevice(0, 1, 0, 0x20, 0, 0, 7, out native, out _, out context));
            var iid = new Guid("54ec77fa-1377-44e6-8c32-88fd5f44c84c");
            Marshal.ThrowExceptionForHR(Marshal.QueryInterface(native, in iid, out dxgi));
            Marshal.ThrowExceptionForHR(CreateDirect3D11DeviceFromDXGIDevice(dxgi, out projected));
            return MarshalInterface<IDirect3DDevice>.FromAbi(projected);
        }
        finally { foreach (var pointer in new[] { projected, dxgi, context, native }) if (pointer != 0) Marshal.Release(pointer); }
    }
    private static GraphicsCaptureItem PrimaryDisplay()
    {
        const string name = "Windows.Graphics.Capture.GraphicsCaptureItem";
        nint text = 0, factory = 0, capture = 0;
        try
        {
            Marshal.ThrowExceptionForHR(WindowsCreateString(name, name.Length, out text));
            var interopId = typeof(CaptureInterop).GUID;
            Marshal.ThrowExceptionForHR(RoGetActivationFactory(text, in interopId, out factory));
            var interop = (CaptureInterop)Marshal.GetObjectForIUnknown(factory);
            try
            {
                var iid = new Guid("79c3f95b-31f7-4ec2-a464-632ef5d30760");
                interop.CreateForMonitor(MonitorFromPoint(default, 1), in iid, out capture);
                return MarshalInterface<GraphicsCaptureItem>.FromAbi(capture);
            }
            finally { Marshal.ReleaseComObject(interop); }
        }
        finally { if (capture != 0) Marshal.Release(capture); if (factory != 0) Marshal.Release(factory); if (text != 0) WindowsDeleteString(text); }
    }
    public DesktopCapture()
    {
        Wire.Require(GraphicsCaptureSession.IsSupported(), "Windows Graphics Capture is unavailable on this computer");
        device = CreateDevice();
        try
        {
            item = PrimaryDisplay(); size = item.Size;
            pool = Direct3D11CaptureFramePool.CreateFreeThreaded(device, DirectXPixelFormat.B8G8R8A8UIntNormalized, 2, size);
            session = pool.CreateCaptureSession(item);
            pool.FrameArrived += FrameArrived;
            item.Closed += CaptureClosed;
            session.StartCapture();
        }
        catch { session?.Dispose(); pool?.Dispose(); device.Dispose(); throw; }
    }
    private void FrameArrived(Direct3D11CaptureFramePool sender, object args)
    {
        lock (gate)
        {
            if (disposed) return;
            try
            {
                var frame = sender.TryGetNextFrame();
                if (frame == null) return;
                latest?.Dispose(); latest = frame;
            }
            catch (Exception e) { failure = e; }
            if (available.CurrentCount == 0) available.Release();
        }
    }
    private void CaptureClosed(GraphicsCaptureItem sender, object args)
    {
        lock (gate)
        {
            if (disposed) return;
            failure = new IOException("The shared Windows display was disconnected");
            if (available.CurrentCount == 0) available.Release();
        }
    }
    public async Task<byte[]> NextAsync(CancellationToken ct)
    {
        while (true)
        {
            var signaled = await available.WaitAsync(lastJpeg == null ? TimeSpan.FromSeconds(5) : TimeSpan.FromMilliseconds(100), ct).ConfigureAwait(false);
            Direct3D11CaptureFrame? frame;
            lock (gate)
            {
                ObjectDisposedException.ThrowIf(disposed, this);
                if (failure != null) throw new IOException("Windows screen capture ended", failure);
                frame = latest; latest = null;
            }
            if (!signaled && frame == null)
            {
                if (lastJpeg != null) return lastJpeg; // A static desktop need not present new frames.
                throw new IOException("Windows did not supply a desktop frame");
            }
            if (frame == null) continue;
            using (frame)
            {
                var current = frame.ContentSize;
                Wire.Require(current.Width > 0 && current.Height > 0, "Windows display is unavailable");
                if (current.Width != size.Width || current.Height != size.Height)
                {
                    size = current;
                    frame.Dispose();
                    lock (gate) { latest?.Dispose(); latest = null; }
                    pool.Recreate(device, DirectXPixelFormat.B8G8R8A8UIntNormalized, 2, size);
                    continue;
                }
                using var bitmap = await SoftwareBitmap.CreateCopyFromSurfaceAsync(frame.Surface, BitmapAlphaMode.Ignore).AsTask(ct).ConfigureAwait(false);
                var ratio = Math.Min(1, Math.Min(1280.0 / Math.Max(size.Width, size.Height), Math.Sqrt(921600.0 / ((long)size.Width * size.Height))));
                for (float quality = .75f; quality >= .14f; quality -= .15f)
                {
                    using var output = new InMemoryRandomAccessStream();
                    var properties = new BitmapPropertySet { ["ImageQuality"] = new BitmapTypedValue(quality, PropertyType.Single) };
                    var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, output, properties).AsTask(ct).ConfigureAwait(false);
                    encoder.SetSoftwareBitmap(bitmap); encoder.IsThumbnailGenerated = false;
                    encoder.BitmapTransform.ScaledWidth = (uint)Math.Max(1, (int)(size.Width * ratio));
                    encoder.BitmapTransform.ScaledHeight = (uint)Math.Max(1, (int)(size.Height * ratio));
                    await encoder.FlushAsync().AsTask(ct).ConfigureAwait(false);
                    if (output.Size > 262144) continue;
                    using var reader = new DataReader(output.GetInputStreamAt(0));
                    await reader.LoadAsync((uint)output.Size).AsTask(ct).ConfigureAwait(false);
                    var bytes = new byte[(int)output.Size]; reader.ReadBytes(bytes);
                    Wire.Jpeg(bytes, 1280, 921600, 262144); lastJpeg = bytes; return bytes;
                }
                throw new IOException("Desktop image exceeds screen protocol limit");
            }
        }
    }
    public void Dispose()
    {
        lock (gate)
        {
            if (disposed) return; disposed = true;
            pool.FrameArrived -= FrameArrived; item.Closed -= CaptureClosed; latest?.Dispose(); latest = null; lastJpeg = null;
        }
        session.Dispose(); pool.Dispose(); device.Dispose();
    }
}
