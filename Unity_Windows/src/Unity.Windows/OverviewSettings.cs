using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Unity.Core;

namespace Unity.Windows;

public sealed partial class MainWindow
{
    private StackPanel? pairingRows;
    private void BuildOverview()
    {
        PageHeading("Overview");
        var heading = Ui.Text(Selected?.Name ?? "Your phone, at a glance", 14, dim: true); labels["device"] = heading; page.Children.Add(heading);
        var metrics = new Grid { ColumnSpacing = 14 };
        for (int i = 0; i < 3; i++) metrics.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) });
        int column = 0;
        foreach (var (key, name, glyph) in new[] { ("connection", "Connection", "\uE701"), ("battery", "Battery", "\uE83F"), ("network", "Network", "\uE774") })
        {
            var box = Ui.Stack(10); var header = Ui.Stack(8, true); header.Children.Add(new FontIcon { Glyph = glyph, FontSize = 14 }); header.Children.Add(Ui.Text(name, 14, dim: true)); box.Children.Add(header);
            labels[key] = Ui.Text("Unavailable", 22, true); box.Children.Add(labels[key]);
            labels[key + "Hint"] = Ui.Text("Connect your phone", 12, dim: true); box.Children.Add(labels[key + "Hint"]);
            var card = Ui.Card(box, 20); card.MinHeight = 138; Grid.SetColumn(card, column++); metrics.Children.Add(card);
        }
        page.Children.Add(metrics);
        var quick = Ui.Group(page, "Quick actions");
        var ring = Ui.Button("Ring", () => _ = RunAsync(async () => { await CommandAsync("find", State["find"].Str("state") == "ringing" ? "stop" : "ring"); }));
        ring.Tag = "ring"; featureButtons.Add((ring, "find")); Ui.AddRow(quick, Ui.Row("Find your phone", "Ring for 30 seconds, even when your phone is silent.", ring));
        var url = Ui.Entry("Share a web address"); Ui.AddRow(quick, EntryRow(url, ActionButton("Share", "urls", "open", () => { Wire.Require(Wire.SafeUrl(url.Text), "Enter an http or https web address"); return Wire.Object(("url", url.Text)); })));
        Ui.AddRow(quick, Ui.Row("Automatic clipboard sync", "Clipboard text syncs in both directions while connected. For Android background sending, allow Shizuku access in Unity on your phone; keep your current keyboard."));
        labels["url"] = Ui.Text(State["urls"].Str("url", "No shared URL"), 12, dim: true);
        var received = Ui.Stack(5); received.Children.Add(Ui.Text("Received web page")); received.Children.Add(labels["url"]);
        var open = Ui.Button("Open", () => { var address = State["urls"].Str("url"); if (Wire.SafeUrl(address)) System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(address) { UseShellExecute = true }); });
        Ui.AddRow(quick, ContentRow(received, open));
        var recent = Ui.Group(page, "Recent activity"); labels["activity"] = Ui.Text(activity, 13, dim: true); var activityBox = Ui.Stack(5); activityBox.Children.Add(Ui.Text("You’re all caught up")); activityBox.Children.Add(labels["activity"]); Ui.AddRow(recent, new Border { Child = activityBox, Padding = new(16) });
        if (Selected == null)
        {
            var setup = Ui.Group(page, "Connect your phone", "Use the same Unity companion you already use with Linux.");
            Ui.AddRow(setup, Ui.Row("Pair an Android device", "Open Settings to see this computer’s address and verified fingerprint.", Ui.Button("Get started", () => navigation.SelectedIndex = 8, true)));
        }
    }
    private static Grid EntryRow(TextBox entry, Button button)
    {
        var grid = new Grid { Padding = new(14, 10, 14, 10), ColumnSpacing = 12 };
        grid.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) }); grid.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
        grid.Children.Add(entry); Grid.SetColumn(button, 1); grid.Children.Add(button); return grid;
    }
    private static Grid ContentRow(UIElement body, FrameworkElement suffix)
    {
        var row = new Grid { Padding = new(16), ColumnSpacing = 16 }; row.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) }); row.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
        row.Children.Add(body); Grid.SetColumn(suffix, 1); row.Children.Add(suffix); return row;
    }
    private void RefreshOverview()
    {
        if (!labels.ContainsKey("connection")) return;
        var connected = Selected?.Links.Length > 0;
        labels["device"].Text = Selected?.Name ?? "Your phone, at a glance";
        labels["connection"].Text = connected ? "Connected" : "Not connected";
        labels["connectionHint"].Text = Selected == null ? "Pair a phone in Settings" : !connected ? "Waiting for your phone" : Selected.Links.Length == 2 ? "Bluetooth + Wi-Fi / LAN" : Selected.Links[0] == "bluetooth" ? "Bluetooth" : "Wi-Fi / LAN";
        var battery = State["battery"];
        labels["battery"].Text = battery == null ? "Unavailable" : battery.Num("level") + "%";
        var batteryHint = battery?.Bool("charging") == true ? "Charging" : battery == null ? "Connect your phone" : "On battery";
        if (battery?["power_w"] is { } power) batteryHint += $" · {Math.Abs(power.GetValue<double>()):0.0} W";
        if (battery?["remaining_seconds"] != null) batteryHint += $" · {TimeSpan.FromSeconds(battery.Num("remaining_seconds")).TotalHours:0.0} h remaining";
        labels["batteryHint"].Text = batteryHint;
        var network = State["network"];
        labels["network"].Text = network.Str("kind") switch { "wifi" => "Wi-Fi", "cellular" => "Cellular", "ethernet" => "Ethernet", "none" => "Offline", _ => "Unavailable" };
        labels["networkHint"].Text = network == null ? "Connect your phone" : network.Str("operator") + (network["signal"] != null ? $" · Signal {network.Num("signal")}/4" : "");
        labels["url"].Text = State["urls"].Str("url", "No shared URL"); labels["activity"].Text = activity;
        foreach (var (button, _) in featureButtons) if ((button.Tag as string) == "ring") button.Content = State["find"].Str("state") == "ringing" ? "Stop" : "Ring";
    }
    private void BuildSettings()
    {
        PageHeading("Settings", "Make Unity yours. Your connection stays local.");
        var appearance = Ui.Group(page, "Appearance");
        var theme = new ComboBox { ItemsSource = new[] { "System", "Light", "Dark" }, SelectedIndex = (int)root.RequestedTheme, MinWidth = 140 };
        theme.SelectionChanged += (_, _) => { root.RequestedTheme = (ElementTheme)theme.SelectedIndex; ApplyPalette(); File.WriteAllText(Path.Combine(host.Store.Root, "appearance.txt"), theme.SelectedIndex.ToString()); };
        Ui.AddRow(appearance, Ui.Row("Appearance", "Follow Windows, or choose a light or dark appearance.", theme));
        BuildLocalCollectionSettings();
        var pair = Ui.Group(page, "Pair devices");
        Ui.AddRow(pair, Ui.Row("Open pairing for Wi-Fi or Bluetooth", "New devices must be approved on this computer within 90 seconds.", Ui.Button("Discover", () => { host.OpenPairing(); RefreshPairing(); })));
        var address = string.Join(" · ", UnityHost.LocalAddresses().Select(a => a + ":" + (host.Port == 0 ? Wire.Port : host.Port)));
        Ui.AddRow(pair, Ui.Row("Computer address", address.Length > 0 ? address : "No private LAN address. Connect this computer and phone to the same network."));
        var pin = new TextBox { Text = host.Store.Fingerprint, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, BorderThickness = new(0), Background = null, FontFamily = new("Consolas"), Margin = new(12), Header = "SHA-256 certificate fingerprint" }; Ui.AddRow(pair, pin);
        Ui.AddRow(pair, Ui.Row("Bluetooth", services.Bluetooth.Status));
        Ui.AddRow(pair, Ui.Row("Pairing instructions", "On Android, open Settings → Computer connection. Enter this computer’s address, port and the full fingerprint shown above. Save and connect, then approve the phone here. For Bluetooth, pair in Windows Settings first and choose this computer on Android."));
        pairingRows = Ui.Stack(8); page.Children.Add(pairingRows); RefreshPairing();
        if (Selected != null)
        {
            var access = Ui.Group(page, "Permissions and capabilities", "Phone permissions stay under your control in Android. These switches also control this computer’s access.");
            var disabled = host.Store.Find(selectedId!)?.Disabled ?? [];
            var caps = Selected.Capabilities.OfType<JsonObject>().ToList();
            foreach (var id in new[] { "lock", "screen", "desktop_media", "sound" }) if (caps.All(c => c.Str("id") != id)) caps.Add(Wire.Object(("id", id), ("status", "disabled"), ("reason", "Connect the phone to negotiate this feature")));
            foreach (var cap in caps)
            {
                var id = cap.Str("id"); var toggle = new ToggleSwitch { IsOn = !disabled.Contains(id), OnContent = "", OffContent = "", IsEnabled = !demo, VerticalAlignment = VerticalAlignment.Center };
                toggle.Toggled += (_, _) => _ = RunAsync(async () => await host.SetEnabledAsync(selectedId!, id, toggle.IsOn));
                Ui.AddRow(access, Ui.Row(FeatureName(id), cap.Str("status").Replace('_', ' ') + (cap.Str("reason").Length > 0 ? " · " + cap.Str("reason") : ""), toggle));
            }
            var device = Ui.Group(page, "Selected device");
            Ui.AddRow(device, Ui.Row("Disconnect", "Your phone may reconnect automatically.", Ui.Button("Disconnect", () => host.Disconnect(selectedId!))));
            Ui.AddRow(device, Ui.Row("Remove " + Selected.Name, "Pair again to give this phone access.", Ui.Button("Unpair", () => _ = RunAsync(UnpairAsync))));
        }
        var app = Ui.Group(page, "Unity for Windows");
        Ui.AddRow(app, Ui.Row("Connection service", demo ? "Preview with sample data; network listeners are off." : host.Status));
        Ui.AddRow(app, Ui.Row("Keep connected", "Closing this window keeps Unity in the notification area. Right-click the icon or use Quit to stop it.", Ui.Button("Quit Unity", ExitApp)));
    }
    private void RefreshPairing()
    {
        if (pairingRows == null || currentPage != "Settings") return; pairingRows.Children.Clear();
        pairingRows.Children.Add(Ui.Text(host.PairingOpen ? "Pairing is open. Waiting for your phone…" : "Pairing is closed", 13, dim: true));
        foreach (var pending in host.PendingPairs)
        {
            var buttons = Ui.Stack(8, true); buttons.Children.Add(Ui.Button("Decline", () => host.Approve(pending.Id, false))); buttons.Children.Add(Ui.Button("Approve", () => host.Approve(pending.Id, true), true));
            pairingRows.Children.Add(Ui.Card(Ui.Row(pending.Name, pending.Id + " · " + Math.Max(0, (int)(pending.Expires - DateTimeOffset.UtcNow).TotalSeconds) + " s", buttons)));
        }
    }
    private async Task UnpairAsync()
    {
        if (selectedId == null || demo) return;
        var id = selectedId; var dialog = new ContentDialog { XamlRoot = root.XamlRoot, Title = "Unpair this device?", Content = "The phone will lose access until you pair it again.", PrimaryButtonText = "Unpair", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Close };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary) { host.Unpair(id); pageCache.Clear(); BuildPage(); }
    }
    private static string FeatureName(string id) => id switch { "desktop_media" => "Windows media players", "sound" => "Device volume", "audio" => "Windows audio on phone", "lock" => "Remote locking", "screen" => "Screen sharing", _ => System.Globalization.CultureInfo.CurrentCulture.TextInfo.ToTitleCase(id.Replace('_', ' ')) };
}
