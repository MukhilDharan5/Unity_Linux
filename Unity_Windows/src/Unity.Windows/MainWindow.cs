using System.Collections.ObjectModel;
using System.Text.Json.Nodes;
using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Unity.Core;
using Unity.Platform;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Unity.Windows;

public sealed partial class MainWindow : Window
{
    private readonly UnityHost host;
    private readonly DesktopServices services;
    private readonly LocalTelemetryCollection localCollection;
    private readonly bool demo;
    private readonly Grid root = new();
    private readonly ListView navigation = new() { SelectionMode = ListViewSelectionMode.Single, Background = null, Margin = new(12, 14, 12, 8) };
    private readonly ComboBox devices = new() { HorizontalAlignment = HorizontalAlignment.Stretch, Margin = new(16, 8, 16, 0) };
    private readonly TextBlock pageTitle = Ui.Text("Overview", 20, true);
    private readonly InfoBar notice = new() { IsClosable = true, CornerRadius = new(Ui.CardRadius), Margin = new(20, 8, 20, 0) };
    private readonly ScrollViewer scroll = new() { HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    private readonly StackPanel page = Ui.Stack(26);
    private readonly ThemeSettings themeSettings;
    private Border? navigationSurface, contentSurface;
    private Grid? shellTitleBar;
    private const double SidebarWidth = 260;
    private readonly Dictionary<string, TextBlock> labels = [];
    private readonly List<(Button Button, string Feature)> featureButtons = [];
    private StackPanel? livePanel;
    private DeviceView[] deviceViews = [];
    private string? selectedId;
    private string currentPage = "Overview", activity = "Transfers and actions will appear here.";
    private bool refreshingDevices, exiting, refreshQueued;
    private TrayIcon? tray;
    private readonly DispatcherTimer clock = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly HashSet<string> announced = [];
    private readonly Dictionary<string, JsonObject> pageCache = [];
    private Window? screenWindow;
    private string? screenDeviceId;
    private Image? screenImage;
    private byte[]? nextScreen;
    private bool renderingScreen;
    private DeviceView? Selected => deviceViews.FirstOrDefault(d => d.Id == selectedId);
    private JsonObject State => Selected?.State ?? new();
    private static readonly (string Title, string Glyph)[] Pages = [("Overview", "\uE80A"), ("Notifications", "\uE7F4"), ("Messages", "\uE8BD"), ("Calls", "\uE717"), ("Files", "\uE8B7"), ("Photos", "\uEB9F"), ("Connectivity", "\uE701"), ("Media", "\uE8D6"), ("Remote Control", "\uE765"), ("Settings", "\uE713")];
    public MainWindow(IdentityStore store, bool demo)
    {
        this.demo = demo; host = new(store); services = new(host); localCollection = new(store.Root);
        var themeFile = Path.Combine(store.Root, "appearance.txt");
        if (File.Exists(themeFile) && int.TryParse(File.ReadAllText(themeFile), out var theme) && theme is >= 0 and <= 2) root.RequestedTheme = (ElementTheme)theme;
        themeSettings = ThemeSettings.CreateForWindowId(AppWindow.Id);
        Ui.HighContrast = themeSettings.HighContrast;
        Ui.Palette = root.RequestedTheme;
        Title = demo ? "Unity · Preview" : "Unity";
        AppWindow.Resize(new(1200, 850));
        Ui.ConfigureWindow(this);
        root.Background = Ui.Brush("UnityBackground");
        // Only two full-height opaque surfaces. The title bar belongs to the
        // same columns as navigation and content, with no top/bottom color band.
        root.RowDefinitions.Add(new() { Height = new(48) }); root.RowDefinitions.Add(new() { Height = new(1, GridUnitType.Star) });
        root.ColumnDefinitions.Add(new() { Width = new(SidebarWidth) }); root.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) });
        navigationSurface = new Border { Background = Ui.Brush("UnitySidebar") }; Grid.SetRowSpan(navigationSurface, 2); root.Children.Add(navigationSurface);
        contentSurface = new Border { Background = Ui.Brush("UnityBackground") }; Grid.SetColumn(contentSurface, 1); Grid.SetRowSpan(contentSurface, 2); root.Children.Add(contentSurface);
        var titleBar = new Grid { Background = new SolidColorBrush(Colors.Transparent) };
        shellTitleBar = titleBar;
        titleBar.ColumnDefinitions.Add(new() { Width = new(SidebarWidth) }); titleBar.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) });
        var brand = Ui.Text("Unity", 15, true); brand.Margin = new(16, 0, 16, 0); brand.TextAlignment = TextAlignment.Center; brand.IsHitTestVisible = false; titleBar.Children.Add(brand);
        pageTitle.TextWrapping = TextWrapping.NoWrap; pageTitle.TextTrimming = TextTrimming.CharacterEllipsis; pageTitle.IsHitTestVisible = false;
        pageTitle.TextAlignment = TextAlignment.Center;
        pageTitle.HorizontalAlignment = HorizontalAlignment.Stretch; Grid.SetColumn(pageTitle, 1); titleBar.Children.Add(pageTitle);
        Grid.SetColumnSpan(titleBar, 2); root.Children.Add(titleBar); SetTitleBar(titleBar);
        var sidebar = new Grid();
        sidebar.RowDefinitions.Add(new() { Height = GridLength.Auto }); sidebar.RowDefinitions.Add(new() { Height = new(1, GridUnitType.Star) }); sidebar.RowDefinitions.Add(new() { Height = GridLength.Auto });
        sidebar.Children.Add(devices); Grid.SetRow(navigation, 1); sidebar.Children.Add(navigation);
        foreach (var (title, glyph) in Pages)
        {
            var row = Ui.Stack(14, true); row.Children.Add(new FontIcon { Glyph = glyph, FontSize = 16, Width = 20 }); row.Children.Add(Ui.Text(title, 15));
            navigation.Items.Add(new ListViewItem { Content = row, Tag = title, Padding = new(14, 11, 14, 11), MinHeight = 46, Margin = new(0, 3, 0, 3), CornerRadius = new(Ui.ControlRadius), HorizontalContentAlignment = HorizontalAlignment.Stretch });
        }
        var footer = Ui.Text(demo ? "Preview · sample data" : "Local. Private. Connected.", 12, dim: true); footer.Margin = new(18); Grid.SetRow(footer, 2); sidebar.Children.Add(footer);
        Grid.SetRow(sidebar, 1); root.Children.Add(sidebar);
        var main = new Grid(); main.RowDefinitions.Add(new() { Height = GridLength.Auto }); main.RowDefinitions.Add(new() { Height = new(1, GridUnitType.Star) });
        main.Children.Add(notice); Grid.SetRow(scroll, 1); main.Children.Add(scroll); Grid.SetColumn(main, 1); Grid.SetRow(main, 1); root.Children.Add(main);
        page.Margin = new(32, 12, 32, 32); page.MaxWidth = 1104; page.HorizontalAlignment = HorizontalAlignment.Left; scroll.Content = page;
        scroll.SizeChanged += (_, e) => page.Width = Math.Clamp(e.NewSize.Width - 64, 0, 1104);
        Content = root;
        ApplyPalette();
        root.ActualThemeChanged += (_, _) => ApplyPalette();
        themeSettings.Changed += ThemeSettingsChanged;
        root.SizeChanged += (_, _) => UpdateTitleBarInsets();
        AppWindow.Changed += (_, _) => UpdateTitleBarInsets();
        navigation.SelectionChanged += (_, _) => { if (navigation.SelectedItem is ListViewItem item) Navigate((string)item.Tag); };
        devices.SelectionChanged += (_, _) =>
        {
            if (refreshingDevices) return; screenWindow?.Close(); selectedId = devices.SelectedIndex >= 0 && devices.SelectedIndex < deviceViews.Length ? deviceViews[devices.SelectedIndex].Id : null; pageCache.Clear(); BuildPage();
        };
        host.Changed += QueueRefresh;
        host.Log += App.WriteLog;
        host.Received += OnReceived;
        services.ScreenFrame += (id, bytes) => { if (id != screenDeviceId) return; nextScreen = bytes; DispatcherQueue.TryEnqueue(RenderScreen); };
        services.ConfirmShare = ConfirmShareAsync;
        AppWindow.Closing += (_, e) => { if (!exiting && tray != null) { e.Cancel = true; AppWindow.Hide(); } };
        Closed += async (_, _) => { if (!demo) StopClipboardSync(); themeSettings.Changed -= ThemeSettingsChanged; clock.Stop(); tray?.Dispose(); await localCollection.DisposeAsync(); await services.DisposeAsync(); await host.DisposeAsync(); Application.Current.Exit(); };
        clock.Tick += (_, _) => { if (currentPage == "Calls") RenderCalls(); if (currentPage == "Settings") { RefreshPairing(); RefreshLocalCollection(); } };
        clock.Start();
        navigation.SelectedIndex = 0;
        if (!demo)
        {
            StartClipboardSync();
            try { host.Start(); } catch (Exception e) { ShowNotice("Could not start Unity", e.Message, true); }
            _ = StartBluetoothAsync();
        }
        RefreshDevices();
        root.Loaded += (_, _) =>
        {
            App.WriteLog("UI loaded");
            ApplyPalette(); UpdateTitleBarInsets();
            root.XamlRoot.Changed += (_, _) => UpdateTitleBarInsets();
            try { tray = new TrayIcon(WindowNative.GetWindowHandle(this), () => { AppWindow.Show(); Activate(); }, ExitApp); } catch (Exception e) { App.WriteLog(e.Message); }
            App.WriteLog("UI ready");
            if (!demo) _ = RunAsync(localCollection.InitializeAsync);
        };
        root.AllowDrop = true;
        root.DragOver += (_, e) => { e.AcceptedOperation = DataPackageOperation.Copy; };
        root.Drop += async (_, e) =>
        {
            if (!e.DataView.Contains(StandardDataFormats.StorageItems)) return; var files = await e.DataView.GetStorageItemsAsync();
            foreach (var file in files.OfType<global::Windows.Storage.StorageFile>()) await RunAsync(() => SendFileAsync(file.Path));
        };
    }
    private async Task StartBluetoothAsync() { await services.Bluetooth.StartAsync(); QueueRefresh(); }
    private void QueueRefresh()
    {
        if (refreshQueued) return; refreshQueued = true;
        DispatcherQueue.TryEnqueue(() => { refreshQueued = false; RefreshDevices(); RefreshLive(); });
    }
    private void RefreshDevices()
    {
        var oldSelection = Selected;
        deviceViews = demo ? [DemoDevice()] : host.Devices();
        var before = selectedId; selectedId = deviceViews.Any(d => d.Id == selectedId) ? selectedId : deviceViews.FirstOrDefault()?.Id;
        refreshingDevices = true;
        var signature = string.Join("|", deviceViews.Select(d => d.Id + d.Name));
        if ((devices.Tag as string) != signature) { devices.ItemsSource = deviceViews.Select(d => d.Name).ToArray(); devices.Tag = signature; }
        devices.PlaceholderText = "No paired devices"; devices.SelectedIndex = Array.FindIndex(deviceViews, d => d.Id == selectedId); refreshingDevices = false;
        var capabilitiesChanged = oldSelection?.Capabilities.ToJsonString() != Selected?.Capabilities.ToJsonString();
        var disconnected = !demo && oldSelection?.Links.Length > 0 && (Selected?.Links.Length ?? 0) == 0;
        if (before != selectedId || capabilitiesChanged || disconnected)
        {
            pageCache.Clear(); conversation = null;
            if (disconnected) { screenWindow?.Close(); nextScreen = null; }
            BuildPage();
        }
        foreach (var (button, feature) in featureButtons) button.IsEnabled = Can(feature) && (button.Tag as string) != "unsupported";
    }
    private bool Can(string feature) => !demo && Selected?.Links.Length > 0 && Selected.Capabilities.Any(c => c.Str("id") == feature && c.Str("status") is "available" or "user_action_required") && host.Store.Find(selectedId!)?.Disabled.Contains(feature) == false;
    private void Navigate(string title) { currentPage = title; scroll.ChangeView(null, 0, null); BuildPage(); }
    private void PageHeading(string title, string description = "")
    {
        pageTitle.Text = title;
        if (description.Length > 0) page.Children.Add(Ui.Text(description, 14, dim: true));
    }
    private void BuildPage()
    {
        page.Children.Clear(); labels.Clear(); featureButtons.Clear(); livePanel = null;
        pageTitle.Text = currentPage;
        switch (currentPage)
        {
            case "Overview": BuildOverview(); break;
            case "Notifications": BuildNotifications(); break;
            case "Messages": BuildMessages(); break;
            case "Calls": BuildCalls(); break;
            case "Files": BuildFiles(); break;
            case "Photos": BuildPhotos(); break;
            case "Media": BuildMedia(); break;
            case "Connectivity": BuildConnectivity(); break;
            case "Remote Control": BuildRemote(); break;
            case "Settings": BuildSettings(); break;
        }
        RefreshLive();
    }
    private void RefreshLive()
    {
        foreach (var (button, feature) in featureButtons) button.IsEnabled = Can(feature) && (button.Tag as string) != "unsupported";
        switch (currentPage)
        {
            case "Overview": RefreshOverview(); break;
            case "Notifications": RenderNotifications(); break;
            case "Calls": RenderCalls(); break;
            case "Media": RefreshMediaStatus(); break;
            case "Connectivity": RefreshConnectivity(); break;
            case "Remote Control": RefreshScreenStatus(); break;
            case "Settings": RefreshPairing(); break;
        }
    }
    private Button ActionButton(string text, string feature, string action, Func<JsonObject>? data = null, bool accent = false, Action<JsonObject>? completed = null)
    {
        var button = Ui.Button(text, () => _ = RunAsync(async () => { var result = await CommandAsync(feature, action, data?.Invoke()); completed?.Invoke(result); }), accent);
        featureButtons.Add((button, feature)); button.IsEnabled = Can(feature); return button;
    }
    private async Task<JsonObject> CommandAsync(string feature, string action, JsonObject? data = null)
    {
        if (demo) throw new IOException("Preview uses sample data. Launch Unity normally to connect your phone.");
        var session = host.Select(selectedId ?? throw new IOException("Pair your phone in Settings first"), feature);
        var result = await session.CommandAsync(feature, action, data);
        activity = result.Str("reason", result.Str("state", "Completed")).Replace('_', ' '); RefreshLive(); return result;
    }
    private async Task RunAsync(Func<Task> work)
    {
        try { await work(); }
        catch (Exception e) { ShowNotice("Action could not finish", e is TimeoutException ? "The phone did not respond in time. Check the connection and phone permissions." : e.Message, true); }
    }
    private void ShowNotice(string title, string message, bool error = false)
    {
        notice.Title = title; notice.Message = message; notice.Severity = error ? InfoBarSeverity.Error : InfoBarSeverity.Informational; notice.IsOpen = true;
    }
    private async Task<bool> ConfirmShareAsync(string name, string message)
    {
        var source = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        DispatcherQueue.TryEnqueue(async () =>
        {
            try
            {
                AppWindow.Show(); Activate(); var dialog = new ContentDialog { XamlRoot = root.XamlRoot, Title = "Share screen with " + name + "?", Content = message, PrimaryButtonText = "Share screen", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Close };
                source.TrySetResult(await dialog.ShowAsync() == ContentDialogResult.Primary);
            }
            catch (Exception) { source.TrySetResult(false); }
        });
        return await source.Task.WaitAsync(TimeSpan.FromSeconds(90));
    }
    private void OnReceived(string id, string feature, JsonObject data)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (feature == "phone_connectivity") return;
            if (feature == "clipboard") { ApplyClipboard(id, data.Str("text")); return; }
            var title = feature switch { "files" => "File received", "urls" => "Web address received", "clipboard" => "Text received", "sms" => "Phone message updated", "notifications" => data.Str("app_name", "Phone notification"), "call_notifications" or "call_control" => "Phone call", _ => "Unity" };
            var incomingSms = feature == "sms" ? data["incoming"] as JsonObject : null;
            var text = incomingSms?.Str("body") ?? data.Str("name", data.Str("title", data.Str("body", data.Str("text", data.Str("url", data.Str("number"))))));
            var key = id + feature + (feature == "notifications" ? data.Str("id") : data.ToJsonString());
            if (announced.Count > 512) announced.Clear();
            var shouldAlert = feature switch
            {
                "notifications" => data.Bool("alert", true),
                "sms" => incomingSms != null,
                "call_control" => (data["calls"] as JsonArray ?? new()).Any(c => c.Str("state") is "incoming" or "ringing"),
                "call_notifications" => data.Str("state") == "ringing",
                _ => true
            };
            if (shouldAlert && !data.Bool("removed") && announced.Add(key)) tray?.Notify(title, text.Length > 180 ? text[..180] : text);
            if (feature is "files" or "urls" or "clipboard") { activity = title + ": " + text; ShowNotice(title, text); }
            if (feature == "sms" && currentPage == "Messages" && selectedId == id && Can("sms")) _ = LoadMessagesAsync();
        });
    }
    private void ExitApp() { exiting = true; Close(); }
    private void ApplyPalette()
    {
        Ui.HighContrast = themeSettings.HighContrast;
        Ui.Palette = root.RequestedTheme == ElementTheme.Default ? root.ActualTheme : root.RequestedTheme;
        Ui.CaptionColors(this);
        if (screenWindow != null)
        {
            if (screenWindow.Content is FrameworkElement content) content.RequestedTheme = root.RequestedTheme;
            Ui.CaptionColors(screenWindow);
        }
    }
    private void ThemeSettingsChanged(ThemeSettings sender, object args) => DispatcherQueue.TryEnqueue(ApplyPalette);
    private void UpdateTitleBarInsets()
    {
        if (shellTitleBar == null) return;
        var scale = root.XamlRoot?.RasterizationScale ?? 1;
        // Reserve the caption-button width on both sides, keeping the title
        // centered in the complete content pane instead of shifting it left.
        var inset = 16 + AppWindow.TitleBar.RightInset / scale;
        pageTitle.Margin = new(inset, 0, inset, 0);
    }
    private async Task SendFileAsync(string path) { await TransferReceiver.SendAsync(host.Select(selectedId ?? "", "files"), path); activity = "Sent " + Path.GetFileName(path); ShowNotice("File sent", activity); }
    private async Task PickFilesAsync()
    {
        var picker = new FileOpenPicker(); InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this)); picker.FileTypeFilter.Add("*");
        var selected = await picker.PickMultipleFilesAsync(); foreach (var file in selected) await SendFileAsync(file.Path);
    }
    private void OpenFolder() => System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe", Path.Combine(host.Store.Root, "Received")) { UseShellExecute = true });
}
