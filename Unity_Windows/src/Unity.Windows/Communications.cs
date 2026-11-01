using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Unity.Core;

namespace Unity.Windows;

public sealed partial class MainWindow
{
    private TextBox? messageAddress, messageBody;
    private StackPanel? messagesPanel, callPanel;
    private string? conversation;
    private bool loadingMessages;
    private void BuildNotifications()
    {
        PageHeading("Notifications", "Respond to the notifications shared by your phone.");
        page.Children.Add(ActionButton("Refresh", "notifications", "list")); livePanel = Ui.Stack(14); page.Children.Add(livePanel); RenderNotifications();
    }
    private void RenderNotifications()
    {
        if (livePanel == null || currentPage != "Notifications") return;
        var data = State["notifications"] as JsonObject ?? new(); var signature = data.ToJsonString();
        if ((livePanel.Tag as string) == signature) return; livePanel.Tag = signature; livePanel.Children.Clear();
        if (data.Count == 0) { Ui.Empty(livePanel, "\uE7F4", "All caught up", "Notifications from your phone will appear here. Enable notification sharing and Android notification access first."); return; }
        foreach (var n in data.Select(x => x.Value).OfType<JsonObject>().OrderByDescending(x => x.Num("timestamp")))
        {
            var id = n.Str("id"); var body = Ui.Stack(10); body.Children.Add(Ui.Text(n.Str("app_name", n.Str("app")), 12, dim: true));
            body.Children.Add(Ui.Text(n.Str("title"), 17, true)); var text = Ui.Text(n.Str("text")); text.IsTextSelectionEnabled = true; body.Children.Add(text);
            var actions = Ui.Stack(8); var controls = Ui.Stack(8, true);
            controls.Children.Add(ActionButton("Dismiss", "notifications", "dismiss", () => Wire.Object(("id", id))));
            foreach (var action in (n["actions"] as JsonArray ?? new()).OfType<JsonObject>())
            {
                var index = action.Str("id");
                if (action.Bool("reply"))
                {
                    var reply = Ui.Entry("Reply to " + n.Str("app_name", "notification")); actions.Children.Add(EntryRow(reply,
                        ActionButton("Reply", "notification_actions", "reply", () => Wire.Object(("id", id), ("action", index), ("text", reply.Text)))));
                }
                else controls.Children.Add(ActionButton(action.Str("title", "Open"), "notification_actions", "invoke", () => Wire.Object(("id", id), ("action", index))));
            }
            body.Children.Add(controls); body.Children.Add(actions); livePanel.Children.Add(Ui.Card(body, 20));
        }
    }
    private void BuildMessages()
    {
        PageHeading("Messages", "Your phone sends SMS using its mobile plan.");
        var refresh = Ui.Button("Refresh messages", () => _ = RunAsync(LoadMessagesAsync)); featureButtons.Add((refresh, "sms")); page.Children.Add(refresh);
        var compose = Ui.Group(page, "New message");
        messageAddress = Ui.Entry("Phone number"); messageAddress.Text = conversation ?? "";
        Ui.AddRow(compose, new Border { Child = messageAddress, Padding = new(14, 10, 14, 10) });
        messageBody = Ui.Entry("Write a message"); messageBody.AcceptsReturn = true; messageBody.TextWrapping = TextWrapping.Wrap; messageBody.MinHeight = 80; messageBody.Padding = new(18, 14, 18, 14);
        Ui.AddRow(compose, EntryRow(messageBody, ActionButton("Send SMS", "sms", "send", () =>
        {
            Wire.Require(messageAddress.Text.Length > 0 && messageBody.Text.Length > 0, "Enter a phone number and a message");
            return Wire.Object(("address", messageAddress.Text), ("body", messageBody.Text));
        }, true, result => { messageBody.Text = ""; ShowNotice("SMS submitted", "Waiting for the phone’s carrier sent and delivery reports."); _ = RunAsync(LoadMessagesAsync); })));
        messagesPanel = Ui.Stack(12); page.Children.Add(messagesPanel);
        if (demo) pageCache["sms"] = DemoMessages();
        RenderMessages(); if (Can("sms") && !pageCache.ContainsKey("sms")) _ = RunAsync(LoadMessagesAsync);
    }
    private async Task LoadMessagesAsync()
    {
        if (loadingMessages) return; loadingMessages = true;
        var device = selectedId; var address = conversation;
        try
        {
            var data = Wire.Object(("offset", 0), ("limit", 80)); if (address != null) data["address"] = address;
            var result = await CommandAsync("sms", "list", data);
            if (device != selectedId || conversation != address) return;
            pageCache["sms"] = result; RenderMessages();
        }
        finally { loadingMessages = false; }
    }
    private void RenderMessages()
    {
        if (messagesPanel == null || currentPage != "Messages") return; messagesPanel.Children.Clear();
        if (conversation != null) messagesPanel.Children.Add(Ui.Button("All conversations", () => { conversation = null; _ = RunAsync(LoadMessagesAsync); }));
        var data = pageCache.GetValueOrDefault("sms"); var messages = (data?["messages"] as JsonArray ?? new()).OfType<JsonObject>().ToArray();
        if (messages.Length == 0) { Ui.Empty(messagesPanel, "\uE8BD", "Choose a conversation", "Enable Messages and SMS permissions on your phone, then refresh."); return; }
        if (conversation == null)
        {
            foreach (var group in messages.GroupBy(m => m.Str("address")))
            {
                var address = group.Key; var latest = group.First();
                messagesPanel.Children.Add(Ui.Card(Ui.Row(address, latest.Str("body"), Ui.Button("Open", () => { conversation = address; if (messageAddress != null) messageAddress.Text = address; _ = RunAsync(LoadMessagesAsync); }))));
            }
        }
        else
        {
            foreach (var m in messages.OrderBy(m => m.Num("timestamp")))
            {
                var box = Ui.Stack(6); var incoming = m.Str("direction") == "incoming";
                box.Children.Add(Ui.Text(incoming ? m.Str("address") : "You", 12, true, true));
                var body = Ui.Text(m.Str("body")); body.IsTextSelectionEnabled = true; box.Children.Add(body); box.Children.Add(Ui.Text(FormatDate(m.Num("timestamp")), 11, dim: true));
                var card = Ui.Card(box, 16); card.MaxWidth = 580; card.HorizontalAlignment = incoming ? HorizontalAlignment.Left : HorizontalAlignment.Right; messagesPanel.Children.Add(card);
            }
        }
        if (data?["next_offset"] != null)
        {
            var offset = data.Num("next_offset"); messagesPanel.Children.Add(Ui.Button("Load older messages", () => _ = RunAsync(async () =>
            {
                var device = selectedId; var address = conversation; var request = Wire.Object(("offset", offset), ("limit", 80)); if (address != null) request["address"] = address;
                var more = await CommandAsync("sms", "list", request);
                if (device != selectedId || address != conversation) return;
                var existing = (JsonArray)data["messages"]!; foreach (var item in more["messages"] as JsonArray ?? new()) existing.Add(item?.DeepClone()); data["next_offset"] = more["next_offset"]?.DeepClone(); RenderMessages();
            })));
        }
    }
    private void BuildCalls()
    {
        PageHeading("Calls", "Dial from your computer and keep your phone close.");
        var dial = Ui.Group(page, "Place a call"); var number = Ui.Entry("Phone number"); Ui.AddRow(dial, EntryRow(number, ActionButton("Call", "call_control", "dial", () => Wire.Object(("number", number.Text)), true)));
        Ui.AddRow(dial, Ui.Row("Phone call controls", "For live answer, hold, mute and routing over Unity, enable the optional Unity Phone app role on Android. Windows HFP call audio is not implemented."));
        callPanel = Ui.Stack(12); page.Children.Add(callPanel); RenderCalls();
        var contacts = Ui.Group(page, "Contacts and recent calls"); var query = Ui.Entry("Search contacts"); var results = Ui.Stack(8);
        Ui.AddRow(contacts, EntryRow(query, ActionButton("Search", "call_control", "contacts", () => Wire.Object(("query", query.Text), ("offset", 0)), completed: r => RenderContactResults(results, r, "contacts", "contacts", Wire.Object(("query", query.Text))))));
        Ui.AddRow(contacts, Ui.Row("Recent calls", "Read-only call history from your phone.", ActionButton("Load", "call_control", "history", () => Wire.Object(("offset", 0)), completed: r => RenderContactResults(results, r, "calls", "history", new()))));
        page.Children.Add(results);
    }
    private void RenderContactResults(StackPanel panel, JsonObject data, string key, string action, JsonObject request, bool append = false)
    {
        if (!append) panel.Children.Clear();
        foreach (var c in (data[key] as JsonArray ?? new()).OfType<JsonObject>())
        {
            var number = c.Str("number"); panel.Children.Add(Ui.Card(Ui.Row(c.Str("name", number), number, ActionButton("Call", "call_control", "dial", () => Wire.Object(("number", number))))));
        }
        if (data["next_offset"] != null)
        {
            var next = (JsonObject)request.DeepClone(); next["offset"] = data.Num("next_offset");
            Button? more = null; more = ActionButton("Load more", "call_control", action, () => next, completed: r => { panel.Children.Remove(more!); RenderContactResults(panel, r, key, action, request, true); }); panel.Children.Add(more);
        }
    }
    private void RenderCalls()
    {
        if (callPanel == null || currentPage != "Calls") return;
        var state = State["call_control"] as JsonObject ?? new(); var calls = state["calls"] as JsonArray ?? new();
        var signature = state.ToJsonString(); if ((callPanel.Tag as string) == signature)
        {
            foreach (var timer in callPanel.Children.OfType<Border>().Select(x => x.Child).OfType<StackPanel>().SelectMany(x => x.Children).OfType<TextBlock>().Where(x => x.Tag is long)) timer.Text = CallTime((long)timer.Tag);
            return;
        }
        callPanel.Tag = signature; callPanel.Children.Clear();
        if (calls.Count == 0)
        {
            var legacy = State["call_notifications"];
            Ui.Empty(callPanel, "\uE717", legacy.Str("state") == "ringing" ? "Incoming call" : "No active call", legacy.Str("number", "Calls will appear here when your phone shares call state.")); return;
        }
        var native = state.Str("backend") == "android_telecom";
        foreach (var call in calls.OfType<JsonObject>())
        {
            var body = Ui.Stack(10); var id = call.Str("id"); var status = call.Str("state");
            body.Children.Add(Ui.Text(call.Str("name", call.Str("number", "Unknown caller")), 22, true));
            body.Children.Add(Ui.Text(status.Replace('_', ' '), 13, dim: true));
            if (call.Num("connect_time") > 0) { var timer = Ui.Text(CallTime(call.Num("connect_time")), 17); timer.Tag = call.Num("connect_time"); body.Children.Add(timer); }
            var actions = Ui.Stack(8, true);
            foreach (var (title, action) in status is "incoming" or "ringing" ? new[] { ("Answer", "answer"), ("Decline", "reject") } : new[] { ("End call", "hangup"), (status == "held" ? "Resume" : "Hold", status == "held" ? "unhold" : "hold") })
            {
                var button = ActionButton(title, "call_control", action, () => Wire.Object(("call_id", id))); button.IsEnabled &= native; if (!native) button.Tag = "unsupported"; actions.Children.Add(button);
            }
            body.Children.Add(actions);
            if (native)
            {
                var tones = Ui.Entry("Keypad tone (0–9, *, #)"); tones.MaxLength = 1; body.Children.Add(EntryRow(tones, ActionButton("Send tone", "call_control", "dtmf", () => Wire.Object(("call_id", id), ("tone", tones.Text)))));
                body.Children.Add(ActionButton(state.Bool("muted") ? "Unmute" : "Mute", "call_control", "mute", () => Wire.Object(("muted", !state.Bool("muted")))));
                var routes = Ui.Stack(8, true);
                foreach (var route in state["routes"] as JsonArray ?? new())
                {
                    var name = route is JsonObject ? route.Str("id") : route?.GetValue<string>() ?? "";
                    if (name.Length > 0) routes.Children.Add(ActionButton(name.Replace('_', ' '), "call_control", "route", () => Wire.Object(("route", name))));
                }
                body.Children.Add(routes);
            }
            else body.Children.Add(Ui.Text("Enable the Unity Phone app role on Android for live controls. Windows Bluetooth hands-free controls are unavailable.", 13, dim: true));
            callPanel.Children.Add(Ui.Card(body, 20));
        }
    }
    private static string CallTime(long time) { var elapsed = TimeSpan.FromMilliseconds(Math.Max(0, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - time)); return elapsed.TotalHours >= 1 ? elapsed.ToString(@"h\:mm\:ss") : elapsed.ToString(@"m\:ss"); }
    private static string FormatDate(long time) { try { return DateTimeOffset.FromUnixTimeMilliseconds(time).LocalDateTime.ToString("g"); } catch { return ""; } }
}
