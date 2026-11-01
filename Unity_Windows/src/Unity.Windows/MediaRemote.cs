using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Unity.Core;

namespace Unity.Windows;

public sealed partial class MainWindow
{
    private ComboBox? playerPicker;
    private StackPanel? playerCard;
    private JsonObject[] players = [];
    private readonly Dictionary<string, (Slider Slider, TextBlock Label, Button Mute)> volumes = [];
    private bool updatingVolume;
    private readonly HashSet<string> editingVolumes = [];
    private string? mediaSignature;
    private JsonObject desktopPlayers = new();
    private bool blankLaptopDisplay;
    private Button? displayToggle;
    private void BuildMedia()
    {
        PageHeading("Media", "Listen, watch, and control playback on either device.");
        var audio = Ui.Group(page, "Audio routing");
        var controls = Ui.Stack(8, true);
        var start = Ui.Button("Play on phone", () => _ = RunAsync(async () => { await services.StartAsync(host.Select(selectedId ?? "", "audio"), "audio"); })); featureButtons.Add((start, "audio")); controls.Children.Add(start);
        controls.Children.Add(Ui.Button("Stop", () => _ = RunAsync(async () => { await services.StopAsync(selectedId ?? "", "audio"); })));
        Ui.AddRow(audio, Ui.Row("Play Windows audio on this phone", "Streams the Windows output over Wi-Fi / LAN when available. Set Phone media volume below; Windows speakers keep playing.", controls));
        labels["audioState"] = Ui.Text("No audio stream", 12, dim: true); labels["audioState"].Margin = new(16, 0, 16, 14); audio.Children.Add(labels["audioState"]);
        var volume = Ui.Group(page, "Device volume"); volumes.Clear();
        foreach (var source in new[] { "desktop", "phone" })
        {
            var body = Ui.Stack(6); var label = Ui.Text("Unavailable", 12, dim: true);
            var mute = Ui.Button("Mute", () => _ = RunAsync(async () =>
            {
                var snapshot = source == "desktop" ? services.Media.Volume() : State["sound"] as JsonObject ?? new();
                var request = Wire.Object(("source", source), ("muted", !snapshot.Bool("muted")), ("output", snapshot.Str("output")));
                if (source == "desktop") services.Media.Volume("mute", request); else await CommandAsync("sound", "mute", request); RefreshMediaStatus();
            }));
            body.Children.Add(ContentRow(Ui.Text(source == "desktop" ? "Windows output volume" : "Phone media volume"), mute));
            var slider = new Slider { Minimum = 0, Maximum = 100, StepFrequency = 1, Margin = new(16, 0, 16, 0) };
            slider.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((_, _) => editingVolumes.Add(source)), true);
            slider.ValueChanged += (_, e) => { if (!updatingVolume) label.Text = $"{e.NewValue:0}%"; };
            async void Commit(object sender, object args)
            {
                if (updatingVolume) return;
                var volumeValue = (int)Math.Round(slider.Value); editingVolumes.Remove(source);
                await RunAsync(async () =>
                {
                    var request = Wire.Object(("source", source), ("volume", volumeValue));
                    if (source == "desktop") { request["output"] = services.Media.Volume().Str("output"); services.Media.Volume("volume", request); }
                    else await CommandAsync("sound", "volume", request);
                });
            }
            slider.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler(Commit), true);
            slider.PointerCanceled += (_, _) => editingVolumes.Remove(source);
            slider.KeyUp += Commit; body.Children.Add(slider); label.Margin = new(16, 0, 16, 12); body.Children.Add(label); Ui.AddRow(volume, body);
            volumes[source] = (slider, label, mute);
        }
        Ui.Heading(page, "Now playing", "Choose a player on your computer or phone.");
        var pick = Ui.Group(page, "Players"); playerPicker = new ComboBox { MinWidth = 230, HorizontalAlignment = HorizontalAlignment.Stretch };
        playerPicker.SelectionChanged += (_, _) => RenderPlayer();
        Ui.AddRow(pick, ContentRow(Ui.Text("Player"), playerPicker));
        var refresh = Ui.Button("Refresh players", () => _ = RunAsync(LoadPlayersAsync)); page.Children.Add(refresh);
        playerCard = Ui.Stack(18); var card = Ui.Card(playerCard, 24); card.MaxWidth = 680; card.HorizontalAlignment = HorizontalAlignment.Stretch; page.Children.Add(card);
        _ = RunAsync(LoadPlayersAsync); RefreshMediaStatus();
    }
    private async Task LoadPlayersAsync()
    {
        var device = selectedId;
        if (Can("media")) await CommandAsync("media", "refresh");
        var desktop = demo ? new JsonObject() : await services.Media.PlayersAsync();
        if (device != selectedId || currentPage != "Media") return;
        desktopPlayers = desktop; UpdatePlayerChoices();
    }
    private void UpdatePlayerChoices()
    {
        if (playerPicker == null || currentPage != "Media") return;
        var previous = playerPicker.SelectedIndex >= 0 && playerPicker.SelectedIndex < players.Length ? players[playerPicker.SelectedIndex].Str("origin") + players[playerPicker.SelectedIndex].Str("session") : "";
        var phone = State["media"] as JsonObject ?? new();
        var desktop = State["desktop_media"] as JsonObject ?? desktopPlayers;
        var signature = phone.ToJsonString() + desktop.ToJsonString(); if (signature == mediaSignature && playerPicker.Items.Count > 0) return; mediaSignature = signature;
        players = (phone["players"] as JsonArray ?? new()).OfType<JsonObject>().Concat((desktop["players"] as JsonArray ?? new()).OfType<JsonObject>()).ToArray();
        if (players.Length == 0 && phone.Str("title").Length > 0) { var legacy = (JsonObject)phone.DeepClone(); legacy["origin"] = "phone"; players = [legacy]; }
        var choices = players.Select(p => (p.Str("origin") == "desktop" ? "Windows · " : "Phone · ") + p.Str("app_name", p.Str("title", "Media"))).ToArray();
        playerPicker.ItemsSource = choices; var selected = Array.FindIndex(players, p => p.Str("origin") + p.Str("session") == previous);
        playerPicker.SelectedIndex = selected >= 0 ? selected : choices.Length > 0 ? 0 : -1; RenderPlayer();
    }
    private void RenderPlayer()
    {
        if (playerCard == null || currentPage != "Media") return; playerCard.Children.Clear();
        var index = playerPicker?.SelectedIndex ?? -1;
        if (index < 0 || index >= players.Length) { Ui.Empty(playerCard, "\uE8D6", "Nothing playing", "Start playback on your computer or phone, then refresh players."); return; }
        var player = players[index]; var desktop = player.Str("origin") == "desktop";
        var origin = Ui.Text(desktop ? "Windows media" : "Phone media", 12, dim: true); playerCard.Children.Add(origin);
        var image = new Image { Width = 220, Height = 220, Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Center };
        if (player["artwork"] is JsonObject artwork) _ = RunAsync(async () => image.Source = await BitmapAsync(Wire.Preview(artwork)));
        else playerCard.Children.Add(new FontIcon { Glyph = "\uE8D6", FontSize = 72, Height = 140, Foreground = Ui.Brush("UnitySecondary"), HorizontalAlignment = HorizontalAlignment.Center });
        if (player["artwork"] != null)
        {
            var artworkFrame = Ui.MediaFrame(image); artworkFrame.Width = artworkFrame.Height = 220; artworkFrame.HorizontalAlignment = HorizontalAlignment.Center; playerCard.Children.Add(artworkFrame);
        }
        foreach (var (text, size, bold) in new[] { (player.Str("title", "Nothing playing"), 28, true), (player.Str("artist"), 16, false), (player.Str("album"), 13, false) })
        { var label = Ui.Text(text, size, bold, !bold); label.TextAlignment = TextAlignment.Center; label.HorizontalAlignment = HorizontalAlignment.Center; playerCard.Children.Add(label); }
        var actions = (player["actions"] as JsonArray)?.Select(x => x!.GetValue<string>()).ToHashSet() ?? new HashSet<string> { "play", "pause", "next", "previous", "seek" };
        var seek = new Slider { Minimum = 0, Maximum = Math.Max(1, player.Num("duration")), Value = player.Num("position"), IsEnabled = actions.Contains("seek") && !demo };
        async Task Control(string action, JsonObject? extras = null)
        {
            var data = extras ?? new(); if (player.Str("session").Length > 0) data["session"] = player.Str("session");
            if (desktop) { data["track_id"] = player.Str("track_id"); await services.Media.ControlAsync(action, data); }
            else await CommandAsync("media", action, data);
            await Task.Delay(250); await LoadPlayersAsync();
        }
        seek.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler((_, _) => _ = RunAsync(() => Control("seek", Wire.Object(("position", (long)seek.Value))))), true);
        seek.KeyUp += (_, _) => _ = RunAsync(() => Control("seek", Wire.Object(("position", (long)seek.Value)))); playerCard.Children.Add(seek);
        var time = Ui.Text(FormatTime(player.Num("position")) + " / " + FormatTime(player.Num("duration")), 12, dim: true); playerCard.Children.Add(time);
        var transport = Ui.Stack(14, true); transport.HorizontalAlignment = HorizontalAlignment.Center;
        foreach (var (title, action) in new[] { ("Previous", "previous"), (player.Bool("playing") ? "Pause" : "Play", player.Bool("playing") ? "pause" : "play"), ("Next", "next") })
        { var button = Ui.Button(title, () => _ = RunAsync(() => Control(action)), action is "play" or "pause"); button.IsEnabled = actions.Contains(action) && !demo && (desktop || Can("media")); transport.Children.Add(button); }
        playerCard.Children.Add(transport);
    }
    private void RefreshMediaStatus()
    {
        if (currentPage != "Media") return;
        UpdatePlayerChoices();
        if (labels.TryGetValue("audioState", out var audio))
        {
            audio.Text = State["audio"].Str("state", "No audio stream").Replace('_', ' ') + " " + State["audio"].Str("error");
            if (State["audio"].Str("state") == "streaming" && State["sound"].Bool("available") && (State["sound"].Bool("muted") || State["sound"].Num("volume") == 0))
                audio.Text += " · Raise Phone media volume or unmute it to hear playback.";
        }
        updatingVolume = true;
        try
        {
            foreach (var (source, widgets) in volumes)
            {
                var value = source == "desktop" ? services.Media.Volume() : State["sound"] as JsonObject ?? new();
                var available = value.Bool("available") && !demo && (source == "desktop" || Can("sound"));
                widgets.Slider.IsEnabled = widgets.Mute.IsEnabled = available;
                if (!editingVolumes.Contains(source))
                {
                    widgets.Slider.Value = value.Num("volume");
                    widgets.Label.Text = value.Bool("available") ? $"{value.Num("volume")}% · {value.Str("name")}" : "Unavailable · " + value.Str("error", "Enable Media and sound on your phone");
                }
                widgets.Mute.Content = value.Bool("muted") ? "Unmute" : "Mute";
            }
        }
        finally { updatingVolume = false; }
    }
    private static string FormatTime(long ms) => TimeSpan.FromMilliseconds(Math.Max(0, ms)).ToString(@"m\:ss");
    private void BuildRemote()
    {
        PageHeading("Remote Control", "Share a screen, type on your phone, or lock either device.");
        var screen = Ui.Group(page, "Screen sharing", "Each device approves its own screen capture. Windows shares the primary display.");
        var controls = Ui.Stack(8, true);
        var phone = Ui.Button("View phone", () => _ = RunAsync(async () => { OpenScreenViewer(); await services.StartAsync(host.Select(selectedId ?? "", "screen"), "screen", "phone"); })); featureButtons.Add((phone, "screen")); controls.Children.Add(phone);
        var desktop = Ui.Button("Share Windows", () => _ = RunAsync(async () => await services.StartAsync(host.Select(selectedId ?? "", "screen"), "screen", "desktop", true, blankDisplay: blankLaptopDisplay))); featureButtons.Add((desktop, "screen")); controls.Children.Add(desktop);
        controls.Children.Add(Ui.Button("Stop", () => _ = RunAsync(async () => { await services.StopAsync(selectedId ?? "", "screen"); screenWindow?.Close(); })));
        Ui.AddRow(screen, Ui.Row("Screens", "Enable Screen sharing in both apps. Approve a phone capture on Android.", controls));
        var blank = new CheckBox { Content = "Blank laptop display while sharing", IsChecked = blankLaptopDisplay, IsEnabled = !demo };
        blank.Checked += (_, _) => blankLaptopDisplay = true; blank.Unchecked += (_, _) => blankLaptopDisplay = false;
        Ui.AddRow(screen, Ui.Row("Use your phone with a black laptop screen", "Open the live phone viewer first. Local displays are covered in black while the phone view stays live. This keeps the panel powered on.", blank));
        displayToggle = Ui.Button("Blank display", () => _ = RunAsync(async () => await services.SetDisplayAsync(selectedId ?? "", !State["screen"].Bool("display_on", true))));
        Ui.AddRow(screen, Ui.Row("Laptop display", "Restore with the phone’s display button or Ctrl+Alt+Shift+U on the laptop. Stopping or disconnecting also restores every display.", displayToggle));
        labels["screenState"] = Ui.Text("No screen share", 12, dim: true); labels["screenState"].Margin = new(16, 0, 16, 12); screen.Children.Add(labels["screenState"]);
        var locking = Ui.Group(page, "Device locking"); Ui.AddRow(locking, Ui.Row("Lock phone", "Requires Android’s device administrator permission.", ActionButton("Lock", "lock", "lock")));
        Ui.AddRow(locking, Ui.Row("Lock this computer from phone", "Allow Remote locking in Unity Settings to let your paired phone lock Windows."));
        var input = Ui.Group(page, "Keyboard and touch", "Select Unity keyboard on Android for typing. Enable Unity accessibility for touch.");
        var text = Ui.Entry("Type on your phone"); Ui.AddRow(input, EntryRow(text, ActionButton("Send text", "remote_input", "text", () => Wire.Object(("text", text.Text)))));
        var pad = new Border { Height = 220, Background = Ui.Brush("UnityInset"), CornerRadius = new(Ui.CardRadius), Margin = new(14), Child = Ui.Text("Tap or drag to control your phone", 14, dim: true) };
        ((TextBlock)pad.Child).HorizontalAlignment = HorizontalAlignment.Center;
        global::Windows.Foundation.Point? origin = null;
        pad.PointerPressed += (_, e) => { origin = e.GetCurrentPoint(pad).Position; pad.CapturePointer(e.Pointer); };
        pad.PointerReleased += (_, e) =>
        {
            var end = e.GetCurrentPoint(pad).Position; pad.ReleasePointerCapture(e.Pointer); if (origin is not { } start) return; origin = null;
            var swipe = Math.Abs(start.X - end.X) + Math.Abs(start.Y - end.Y) > 8;
            _ = RunAsync(async () => await CommandAsync("remote_input", swipe ? "swipe" : "tap", Wire.Object(("x", Math.Clamp(start.X / pad.ActualWidth, 0, 1)), ("y", Math.Clamp(start.Y / pad.ActualHeight, 0, 1)), ("end_x", Math.Clamp(end.X / pad.ActualWidth, 0, 1)), ("end_y", Math.Clamp(end.Y / pad.ActualHeight, 0, 1)))));
        };
        Ui.AddRow(input, pad);
        var phoneSettings = Ui.Group(page, "Phone sound and internet sharing");
        foreach (var (title, mode) in new[] { ("Normal", "normal"), ("Vibrate", "vibrate"), ("Silent", "silent"), ("Do not disturb", "dnd"), ("DND off", "off") })
            Ui.AddRow(phoneSettings, Ui.Row(title, "", ActionButton("Set", "ringer", "set", () => Wire.Object(("mode", mode)))));
    }
    private void RefreshScreenStatus()
    {
        var screen = State["screen"];
        if (labels.TryGetValue("screenState", out var status))
            status.Text = screen.Str("state", "No screen share").Replace('_', ' ') +
                (screen.Str("state") == "streaming" && screen.Str("source") == "desktop" ? (screen.Bool("display_on", true) ? " · Laptop display on" : " · Laptop display blanked") : "") +
                " " + screen.Str("error") + " " + screen.Str("display_error");
        if (displayToggle != null)
        {
            displayToggle.Content = screen.Bool("display_on", true) ? "Blank display" : "Restore display";
            displayToggle.IsEnabled = Can("screen") && screen.Str("state") == "streaming" && screen.Str("source") == "desktop" && screen.Bool("display_available");
        }
    }
    private void OpenScreenViewer()
    {
        if (screenWindow != null) { screenWindow.Activate(); return; }
        screenDeviceId = selectedId;
        screenWindow = new Window { Title = "Unity · Phone screen" }; screenWindow.AppWindow.Resize(new(440, 800));
        Ui.ConfigureWindow(screenWindow);
        var content = new Grid { Background = Ui.Brush("UnityBackground"), RequestedTheme = root.RequestedTheme };
        content.RowDefinitions.Add(new() { Height = new(48) }); content.RowDefinitions.Add(new() { Height = GridLength.Auto }); content.RowDefinitions.Add(new() { Height = new(1, GridUnitType.Star) });
        var titleBar = new Grid { Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
        var title = Ui.Text("Phone screen", 16, true); title.TextWrapping = TextWrapping.NoWrap; title.TextTrimming = TextTrimming.CharacterEllipsis;
        title.Margin = new(20, 0, 152, 0); title.IsHitTestVisible = false; titleBar.Children.Add(title); content.Children.Add(titleBar);
        screenWindow.SetTitleBar(titleBar);
        var stop = Ui.Button("Stop sharing", () => screenWindow?.Close()); stop.Margin = new(16, 8, 16, 12); Grid.SetRow(stop, 1); content.Children.Add(stop);
        screenImage = new Image { Stretch = Stretch.Uniform };
        var screenFrame = Ui.MediaFrame(screenImage); screenFrame.Margin = new(16, 0, 16, 16); Grid.SetRow(screenFrame, 2); content.Children.Add(screenFrame); screenWindow.Content = content;
        content.SizeChanged += (_, _) => { if (screenWindow != null) title.Margin = new(20, 0, 16 + screenWindow.AppWindow.TitleBar.RightInset / (content.XamlRoot?.RasterizationScale ?? 1), 0); };
        var id = selectedId; screenWindow.Closed += (_, _) => { screenWindow = null; screenDeviceId = null; screenImage = null; nextScreen = null; _ = RunAsync(async () => await services.StopAsync(id ?? "", "screen")); }; screenWindow.Activate();
    }
    private async void RenderScreen()
    {
        if (renderingScreen || screenImage == null || nextScreen == null) return; renderingScreen = true;
        try { while (screenImage != null && nextScreen is { } frame) { nextScreen = null; screenImage.Source = await BitmapAsync(frame); } }
        catch (Exception e) { App.WriteLog("Screen viewer: " + e.Message); }
        finally { renderingScreen = false; }
    }
}
