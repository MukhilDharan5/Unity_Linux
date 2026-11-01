using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Unity.Core;

namespace Unity.Windows;

public sealed partial class MainWindow
{
    private StackPanel? browserPanel;
    private readonly Stack<string> browserHistory = new();
    private string browserId = "";
    private void BuildFiles()
    {
        PageHeading("Files", "Drop files anywhere in Unity, or choose files below.");
        var send = Ui.Group(page, "Send to device", "All file types are supported, up to 64 MiB per file. Files arrive in Downloads/Unity on Android.");
        var choose = Ui.Button("Choose files…", () => _ = RunAsync(PickFilesAsync), true); featureButtons.Add((choose, "files"));
        Ui.AddRow(send, Ui.Row("Select files", "Each transfer stays on its original authenticated connection.", choose));
        var transfers = Ui.Group(page, "Received files"); Directory.CreateDirectory(Path.Combine(host.Store.Root, "Received"));
        Ui.AddRow(transfers, Ui.Row("Unity received files", "Only complete, SHA-256 verified files are saved. Files are never opened automatically.", Ui.Button("Open folder", OpenFolder)));
        foreach (var file in new DirectoryInfo(Path.Combine(host.Store.Root, "Received")).EnumerateFiles().Where(f => !f.Name.EndsWith(".part")).OrderByDescending(f => f.CreationTimeUtc).Take(12))
            Ui.AddRow(transfers, Ui.Row(file.Name, FormatSize(file.Length)));
        var browse = Ui.Group(page, "Shared phone folder", "Only the folder approved on your phone is visible.");
        var buttons = Ui.Stack(8, true);
        buttons.Children.Add(ActionButton("Browse", "browsing", "list", () => { browserHistory.Clear(); browserId = ""; return Wire.Object(("id", "")); }, completed: RenderBrowser));
        buttons.Children.Add(Ui.Button("Back", () => { if (browserHistory.Count == 0) return; browserId = browserHistory.Pop(); _ = RunAsync(async () => RenderBrowser(await CommandAsync("browsing", "list", Wire.Object(("id", browserId))))); }));
        Ui.AddRow(browse, Ui.Row("Browse phone files", "Enable file browsing and choose a folder on Android.", buttons));
        browserPanel = Ui.Stack(8); page.Children.Add(browserPanel);
    }
    private void RenderBrowser(JsonObject result)
    {
        if (browserPanel == null || currentPage != "Files") return; browserPanel.Children.Clear();
        foreach (var item in (result["entries"] as JsonArray ?? new()).OfType<JsonObject>())
        {
            var id = item.Str("id"); var directory = item.Bool("directory");
            var button = Ui.Button(directory ? "Open" : "Download", () => _ = RunAsync(async () =>
            {
                if (directory) { browserHistory.Push(browserId); browserId = id; RenderBrowser(await CommandAsync("browsing", "list", Wire.Object(("id", id)))); }
                else await CommandAsync("browsing", "get", Wire.Object(("id", id)));
            }));
            featureButtons.Add((button, "browsing")); browserPanel.Children.Add(Ui.Card(Ui.Row(item.Str("name"), directory ? "Folder" : FormatSize(item.Num("size")), button)));
        }
        if (browserPanel.Children.Count == 0) browserPanel.Children.Add(Ui.Text("This folder is empty.", dim: true));
    }
    private void BuildPhotos()
    {
        PageHeading("Photos", "Small previews load first. Download an original when you need it.");
        var refresh = Ui.Button("Refresh photos", () => _ = RunAsync(LoadPhotosAsync)); featureButtons.Add((refresh, "photos")); page.Children.Add(refresh);
        livePanel = Ui.Stack(14); page.Children.Add(livePanel);
        Ui.Empty(livePanel, "\uEB9F", "Your gallery is waiting", "Connect your phone and allow photo sharing, then refresh.");
        if (Can("photos")) _ = RunAsync(LoadPhotosAsync);
    }
    private async Task LoadPhotosAsync()
    {
        var device = selectedId; var panel = livePanel;
        var result = await CommandAsync("photos", "list"); if (device != selectedId || panel != livePanel || currentPage != "Photos") return;
        panel!.Children.Clear(); var items = result["items"] as JsonArray ?? new();
        if (items.Count == 0) { Ui.Empty(panel, "\uEB9F", "No shared photos", "Choose the photos Unity can access in Android permissions."); return; }
        if (!result.Bool("preview_supported")) panel.Children.Add(Ui.Text("Update the Android companion for small photo previews.", dim: true));
        var gallery = new GridView { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = false, HorizontalAlignment = HorizontalAlignment.Stretch };
        panel.Children.Add(gallery);
        foreach (var item in items.OfType<JsonObject>())
        {
            var id = item.Str("id"); Wire.Require(Wire.IsHex(id, 32), "Invalid photo selection");
            var body = Ui.Stack(10); body.Width = 204;
            var image = new Image { Height = 154, Width = 204, Stretch = Stretch.UniformToFill, Source = null };
            var thumbnailFrame = Ui.MediaFrame(image); thumbnailFrame.Height = 154; body.Children.Add(thumbnailFrame);
            var name = Ui.Text(item.Str("name"), 13, true); name.MaxLines = 2; body.Children.Add(name); body.Children.Add(Ui.Text(FormatSize(item.Num("size")), 12, dim: true));
            body.Children.Add(ActionButton("Download original", "photos", "get", () => Wire.Object(("id", id))));
            gallery.Items.Add(Ui.Card(body, 12));
            if (result.Bool("preview_supported"))
            {
                var preview = await CommandAsync("photos", "preview", Wire.Object(("id", id), ("edge", 256)));
                if (device != selectedId || panel != livePanel || currentPage != "Photos") return;
                if (preview["preview"] is JsonObject p) image.Source = await BitmapAsync(Wire.Preview(p));
            }
        }
    }
    private static async Task<BitmapImage> BitmapAsync(byte[] bytes)
    {
        var image = new BitmapImage(); using var memory = new MemoryStream(bytes); using var source = memory.AsRandomAccessStream(); await image.SetSourceAsync(source); return image;
    }
    private static string FormatSize(long n) => n >= 1048576 ? $"{n / 1048576.0:0.0} MiB" : n >= 1024 ? $"{n / 1024.0:0.0} KiB" : $"{n} bytes";
}
