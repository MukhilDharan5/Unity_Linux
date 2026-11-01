$ErrorActionPreference = 'Stop'
$expectedRoot = [IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Programs\UnityWindows')).TrimEnd('\')
$installRoot = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
if (-not $installRoot.Equals($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Run the installed uninstaller from the UnityWindows installation folder.' }
$markerPath = Join-Path $installRoot '.unity-install.json'
if (-not (Test-Path -LiteralPath $markerPath)) { throw 'Unity installation marker is missing; no files were removed.' }
$marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
if ($marker.ProductId -ne 'org.unityconnect.Windows') { throw 'Invalid Unity installation marker.' }
if ((Get-Item -LiteralPath $installRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'The install folder must not be a link.' }
if (@(Get-ChildItem -LiteralPath $installRoot -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw 'The install folder contains links; no files were removed.' }

Get-Process Unity -ErrorAction SilentlyContinue | Where-Object {
    $_.Path -and $_.Path.StartsWith($installRoot + '\', [StringComparison]::OrdinalIgnoreCase)
} | Stop-Process

$shell = New-Object -ComObject WScript.Shell
$startMenuRoot = Join-Path ([Environment]::GetFolderPath('Programs')) 'Unity Phone Companion'
$shortcutPaths = @(
    (Join-Path $startMenuRoot 'Unity Phone Companion.lnk'),
    (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Unity Phone Companion.lnk')
)
foreach ($path in $shortcutPaths) {
    if (Test-Path -LiteralPath $path) {
        $shortcut = $shell.CreateShortcut($path)
        if ($shortcut.TargetPath.StartsWith($installRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { Remove-Item -LiteralPath $path -Force }
    }
}
if ((Test-Path -LiteralPath $startMenuRoot) -and @(Get-ChildItem -LiteralPath $startMenuRoot -Force).Count -eq 0) { Remove-Item -LiteralPath $startMenuRoot }
$registryPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\UnityWindows'
$registered = Get-ItemProperty -LiteralPath $registryPath -ErrorAction SilentlyContinue
if ($registered -and $registered.InstallLocation -eq $installRoot) { Remove-Item -LiteralPath $registryPath -Recurse -Force }

# The absolute target is checked against the fixed per-user application folder above.
# Pairing, settings, and received files live separately and are preserved.
Remove-Item -LiteralPath $installRoot -Recurse -Force
Write-Output 'Unity Phone Companion was uninstalled. Your pairing, settings and received files were preserved.'
