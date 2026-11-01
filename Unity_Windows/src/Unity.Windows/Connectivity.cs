using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Unity.Core;

namespace Unity.Windows;

public sealed partial class MainWindow
{
    private ToggleSwitch? phoneHotspot;
    private StackPanel? phoneBluetoothDevices;
    private bool refreshingConnectivity, hotspotTriggerPending;
    private readonly HashSet<string> bluetoothTriggersPending = [];

    private void BuildConnectivity()
    {
        PageHeading("Connectivity", "Mobile hotspot and connected Bluetooth devices are reported by your phone. Enable Phone connectivity and Nearby devices in Unity on Android.");
        hotspotTriggerPending = false; bluetoothTriggersPending.Clear();
        var hotspot = Ui.Group(page, "Mobile Hotspot", "Mobile hotspot changes use your Samsung keyword routines. The switch follows the phone’s reported state.");
        phoneHotspot = new ToggleSwitch { OnContent = "On", OffContent = "Off" };
        phoneHotspot.Toggled += (_, _) =>
        {
            if (refreshingConnectivity) return;
            var desired = phoneHotspot.IsOn;
            if (!Can("phone_connectivity") || !Can("samsung_routines") ||
                State["phone_connectivity"] is not JsonObject observed ||
                observed["hotspot_enabled"] is not JsonValue hotspotState || !hotspotState.TryGetValue<bool>(out var actual) || desired == actual) return;
            _ = RunAsync(async () =>
            {
                hotspotTriggerPending = true; RefreshConnectivity();
                try { await CommandAsync("samsung_routines", desired ? "hotspot_on" : "hotspot_off"); }
                finally { hotspotTriggerPending = false; RefreshConnectivity(); }
            });
        };
        Ui.AddRow(hotspot, Ui.Row("Mobile Hotspot", "Reported state; use the explicit on/off buttons below when state is unavailable.", phoneHotspot));
        Ui.AddRow(hotspot, Ui.Row("Update phone connections", "", ActionButton("Refresh", "phone_connectivity", "refresh")));
        phoneBluetoothDevices = Ui.Group(page, "Bluetooth devices connected to your phone", "Turn a connected device off to request disconnection. Create its device-specific Samsung routine first.");
        labels["connectivityState"] = Ui.Text("", 12, dim: true); page.Children.Add(labels["connectivityState"]);
        var sharing = Ui.Group(page, "Phone internet sharing");
        Ui.AddRow(sharing, Ui.Row("Internet sharing settings", "Review tethering settings on your phone.", ActionButton("Review on phone", "hotspot", "request_settings")));
        foreach (var (title, action) in new[] { ("Mobile hotspot on", "hotspot_on"), ("Mobile hotspot off", "hotspot_off") })
            Ui.AddRow(sharing, Ui.Row(title, "Uses your configured Samsung routine; execution is unconfirmed.", ActionButton("Send trigger", "samsung_routines", action)));
    }

    private void RefreshConnectivity()
    {
        if (currentPage != "Connectivity" || phoneHotspot is null || phoneBluetoothDevices is null) return;
        refreshingConnectivity = true;
        try
        {
            var state = Can("phone_connectivity") ? State["phone_connectivity"] as JsonObject ?? new() : new JsonObject();
            var hasHotspot = state["hotspot_enabled"] is JsonValue value && value.TryGetValue<bool>(out _);
            phoneHotspot.IsOn = hasHotspot && state.Bool("hotspot_enabled");
            phoneHotspot.OffContent = hasHotspot ? "Off" : "Unknown";
            phoneHotspot.IsEnabled = Can("phone_connectivity") && Can("samsung_routines") && hasHotspot && !hotspotTriggerPending;
            phoneBluetoothDevices.Children.Clear();
            var devices = state["devices"] as JsonArray ?? new();
            if (devices.Count == 0) Ui.AddRow(phoneBluetoothDevices, Ui.Row("No connected devices reported", state.Str("reason", "Enable Phone connectivity and allow Nearby devices on your phone.")));
            foreach (var device in devices)
            {
                var address = device.Str("address");
                var toggle = new ToggleSwitch { IsOn = true, OnContent = "Connected", OffContent = "Disconnected", IsEnabled = Can("samsung_routines") && !bluetoothTriggersPending.Contains(address) };
                toggle.Toggled += (_, _) =>
                {
                    if (refreshingConnectivity || toggle.IsOn) return;
                    _ = RunAsync(async () =>
                    {
                        bluetoothTriggersPending.Add(address); RefreshConnectivity();
                        try { await CommandAsync("samsung_routines", "disconnect_bluetooth_device", Wire.Object(("address", address))); }
                        finally { bluetoothTriggersPending.Remove(address); RefreshConnectivity(); }
                    });
                };
                ToolTipService.SetToolTip(toggle, "Samsung routine: UNITY_BLUETOOTH_DISCONNECT_" + address.Replace(":", ""));
                Ui.AddRow(phoneBluetoothDevices, Ui.Row(device.Str("name", "Bluetooth device"), address + (bluetoothTriggersPending.Contains(address) ? " · Trigger pending" : " · Reported connected"), toggle));
            }
            labels["connectivityState"].Text = state.Str("reason", "Connect and enable Phone connectivity on your phone.") + " " + activity;
        }
        finally { refreshingConnectivity = false; }
    }
}
