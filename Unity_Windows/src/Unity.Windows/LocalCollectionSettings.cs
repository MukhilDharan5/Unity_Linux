using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Unity.Windows;

public sealed partial class MainWindow
{
    private ToggleSwitch? localCollectionToggle;
    private bool changingLocalCollection, refreshingLocalCollection;
    private void BuildLocalCollectionSettings()
    {
        var group = Ui.Group(page, "Local device data", "Optional collection for this Windows PC. Off until you approve it. Nothing collected here is sent to a phone, dashboard, or server.");
        var toggle = new ToggleSwitch { IsOn = !demo && localCollection.Enabled, OnContent = "", OffContent = "",
            IsEnabled = !demo && !changingLocalCollection, VerticalAlignment = VerticalAlignment.Center };
        localCollectionToggle = toggle;
        AutomationProperties.SetName(toggle, "Collect local device data");
        toggle.Toggled += async (_, _) =>
        {
            if (changingLocalCollection || refreshingLocalCollection || demo) return;
            var enable = toggle.IsOn;
            changingLocalCollection = true; toggle.IsEnabled = false;
            try
            {
                if (enable && !localCollection.Enabled)
                {
                    var dialog = new ContentDialog
                    {
                        XamlRoot = root.XamlRoot, Title = "Allow local device data collection?",
                        Content = "Unity will collect this PC’s name and hardware/Windows details, battery and power readings, network name and diagnostics, media titles and artists, audio output and volume, and daily foreground-app usage and observed unlock counts. Unsupported or inaccessible fields are marked unavailable.\n\n" +
                            "Unity polls every 5 seconds. Usage tracking records app names and active time while the session is unlocked and its display is on, with a 60-second idle cutoff. Daily counters cover this Unity session and restart with the app. It does not record window titles, keystrokes, screen images, or audio.\n\n" +
                            "Records are saved as a readable CSV in Unity’s local data folder. Samples older than 30 minutes are removed while Unity runs and when collection resumes. No upload, dashboard connection, or phone sharing is enabled. Collection continues in the notification area and resumes when Unity starts while this setting is on.\n\n" +
                            "Turn this off at any time to stop collection and delete the collected measurements.",
                        PrimaryButtonText = "Allow collection", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Close
                    };
                    if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
                }
                await localCollection.SetEnabledAsync(enable);
            }
            catch (Exception)
            {
                ShowNotice("Local collection setting could not be saved", "Check access to Unity’s local data folder. Collection is " +
                    (localCollection.Enabled ? "enabled." : "stopped; saved measurements may need to be removed from the local data folder."), true);
            }
            finally { changingLocalCollection = false; RefreshLocalCollection(); }
        };
        Ui.AddRow(group, Ui.Row("Collect local device data", "Save a local CSV every 5 seconds. Keep the last 30 minutes. Turning this off stops collection and deletes the CSV.", toggle));
        Ui.AddRow(group, Ui.Row("Local CSV file", localCollection.CsvPath));
        labels["localCollection"] = Ui.Text(demo ? "Preview: collection is disabled." : localCollection.Status, 12, dim: true);
        Ui.AddRow(group, new Border { Child = labels["localCollection"], Padding = new(16, 0, 16, 14) });
    }
    private void RefreshLocalCollection()
    {
        if (labels.TryGetValue("localCollection", out var text)) text.Text = demo ? "Preview: collection is disabled." : localCollection.Status;
        if (currentPage == "Settings" && localCollectionToggle != null && !changingLocalCollection)
        {
            refreshingLocalCollection = true;
            try { localCollectionToggle.IsOn = !demo && localCollection.Enabled; localCollectionToggle.IsEnabled = !demo; }
            finally { refreshingLocalCollection = false; }
        }
    }
}
