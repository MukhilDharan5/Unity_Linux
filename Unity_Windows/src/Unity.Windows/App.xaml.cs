using Microsoft.UI.Xaml;
using Unity.Core;

namespace Unity.Windows;

public partial class App : Application
{
    private MainWindow? window;
    private Mutex? singleton;
    public static string StateRoot { get; private set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UnityWindows");
    public App() { InitializeComponent(); UnhandledException += (_, e) => { WriteLog(e.Exception.ToString()); }; }
    public static void WriteLog(string message)
    {
        try { Directory.CreateDirectory(StateRoot); File.AppendAllText(Path.Combine(StateRoot, "unity.log"), $"{DateTimeOffset.Now:O} {message}\n"); } catch { }
    }
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var arguments = Environment.GetCommandLineArgs(); var state = Array.IndexOf(arguments, "--state-dir");
        if (state >= 0 && state + 1 < arguments.Length) StateRoot = Path.GetFullPath(arguments[state + 1]);
        var demo = arguments.Contains("--demo");
        if (demo && state < 0) StateRoot = Path.Combine(StateRoot, "Demo");
        var mutexName = "Local\\UnityWindows-" + Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(StateRoot)))[..16];
        singleton = new Mutex(true, mutexName, out var first);
        if (!first) { Exit(); return; }
        try { window = new MainWindow(new IdentityStore(StateRoot), demo); WriteLog("UI constructed"); window.Activate(); WriteLog("UI activated"); }
        catch (Exception e) { WriteLog(e.ToString()); throw; }
    }
}
