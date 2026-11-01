using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Media;

namespace Unity.Windows;

internal static class Ui
{
    public const double CardRadius = 16, MediaRadius = 12, ControlRadius = 8;
    private static ElementTheme palette;
    private static readonly Dictionary<string, SolidColorBrush> brushes = [];
    public static bool HighContrast { get; set; }
    public static ElementTheme Palette
    {
        get => palette;
        set
        {
            palette = value;
            foreach (var (key, brush) in brushes) brush.Color = Color(key);
        }
    }
    public static global::Windows.UI.Color Color(string key)
    {
        var light = Palette == ElementTheme.Light || (Palette == ElementTheme.Default && Application.Current.RequestedTheme == ApplicationTheme.Light);
        var theme = HighContrast ? "HighContrast" : light ? "Light" : "Default";
        var resources = (ResourceDictionary)Application.Current.Resources.ThemeDictionaries[theme];
        return ((SolidColorBrush)resources[key]).Color;
    }
    public static Brush Brush(string key)
    {
        if (!brushes.TryGetValue(key, out var brush)) brushes[key] = brush = new SolidColorBrush(Color(key));
        return brush;
    }
    public static void ConfigureWindow(Window window)
    {
        window.SystemBackdrop = null;
        window.ExtendsContentIntoTitleBar = true;
        window.AppWindow.TitleBar.PreferredHeightOption = Microsoft.UI.Windowing.TitleBarHeightOption.Tall;
        CaptionColors(window);
    }
    public static void CaptionColors(Window window)
    {
        var title = window.AppWindow.TitleBar;
        title.ButtonBackgroundColor = title.ButtonInactiveBackgroundColor = Colors.Transparent;
        title.ButtonForegroundColor = title.ButtonHoverForegroundColor = title.ButtonPressedForegroundColor = Color("UnityForeground");
        title.ButtonInactiveForegroundColor = Color("UnitySecondary");
        title.ButtonHoverBackgroundColor = Color("UnityCard");
        title.ButtonPressedBackgroundColor = Color("UnityStroke");
    }
    public static TextBlock Text(string text, double size = 14, bool bold = false, bool dim = false)
    {
        var t = new TextBlock
        {
            Text = text,
            FontSize = size,
            TextWrapping = TextWrapping.Wrap,
            VerticalAlignment = VerticalAlignment.Center,
            FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal
        };
        if (dim) t.Foreground = Brush("UnitySecondary"); return t;
    }
    public static StackPanel Stack(double spacing = 12, bool horizontal = false) => new() { Spacing = spacing, Orientation = horizontal ? Orientation.Horizontal : Orientation.Vertical };
    public static Border Card(UIElement child, double padding = 0) => new() { Child = child, Background = Brush("UnityCard"), CornerRadius = new(CardRadius), Padding = new(padding) };
    public static Border MediaFrame(UIElement child)
    {
        var frame = new Border { Child = child, Background = Brush("UnityInset"), CornerRadius = new(MediaRadius) };
        bool initialized = false;
        frame.Loaded += (_, _) =>
        {
            if (initialized) return;
            initialized = true;
            var visual = ElementCompositionPreview.GetElementVisual(frame);
            var geometry = visual.Compositor.CreateRoundedRectangleGeometry();
            geometry.CornerRadius = new((float)MediaRadius);
            void Resize() => geometry.Size = new((float)frame.ActualWidth, (float)frame.ActualHeight);
            Resize(); frame.SizeChanged += (_, _) => Resize();
            visual.Clip = visual.Compositor.CreateGeometricClip(geometry);
        };
        return frame;
    }
    public static Button Button(string label, Action action, bool accent = false)
    {
        var button = new Button { Content = label, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(button, label);
        if (accent) button.Style = (Style)Application.Current.Resources["AccentButtonStyle"];
        button.CornerRadius = new(ControlRadius); button.MinHeight = 40; button.Padding = new(20, 9, 20, 9);
        button.Click += (_, _) => action(); return button;
    }
    public static Grid Row(string title, string subtitle = "", FrameworkElement? suffix = null)
    {
        var grid = new Grid { Padding = new(16, 13, 16, 13), ColumnSpacing = 16, MinHeight = 62 };
        grid.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) }); grid.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
        var text = Stack(4); text.Children.Add(Text(title, 14)); if (subtitle.Length > 0) text.Children.Add(Text(subtitle, 12, dim: true)); grid.Children.Add(text);
        if (suffix != null)
        {
            Grid.SetColumn(suffix, 1); grid.Children.Add(suffix);
        }
        return grid;
    }
    public static StackPanel Group(StackPanel page, string title, string description = "")
    {
        var group = Stack(10); group.Children.Add(Text(title, 15, true)); if (description.Length > 0) group.Children.Add(Text(description, 13, dim: true));
        var rows = Stack(0); group.Children.Add(Card(rows)); page.Children.Add(group); return rows;
    }
    public static void AddRow(StackPanel rows, UIElement row)
    {
        rows.Children.Add(row);
    }
    public static TextBox Entry(string placeholder, double width = double.NaN)
    {
        var entry = new TextBox { PlaceholderText = placeholder, Width = width, MinWidth = 100, MinHeight = 42, CornerRadius = new(ControlRadius), Padding = new(18, 9, 18, 9), VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(entry, placeholder); return entry;
    }
    public static void Heading(StackPanel page, string title, string description = "")
    {
        var heading = Stack(6); heading.Children.Add(Text(title, 20, true)); if (description.Length > 0) heading.Children.Add(Text(description, 14, dim: true)); page.Children.Add(heading);
    }
    public static void Empty(StackPanel page, string glyph, string title, string detail)
    {
        var box = Stack(16); box.Margin = new(24, 64, 24, 64); box.HorizontalAlignment = HorizontalAlignment.Center; box.MaxWidth = 460;
        box.Children.Add(new FontIcon { Glyph = glyph, FontSize = 56, Foreground = Brush("UnitySecondary"), HorizontalAlignment = HorizontalAlignment.Center });
        var t = Text(title, 23, true); t.TextAlignment = TextAlignment.Center; box.Children.Add(t);
        var d = Text(detail, 14, dim: true); d.TextAlignment = TextAlignment.Center; box.Children.Add(d); page.Children.Add(box);
    }
}
