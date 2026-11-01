param([switch]$Launch)
$ErrorActionPreference = 'Stop'
$productId = 'org.unityconnect.Windows'
$projectRoot = Split-Path -Parent $PSScriptRoot
$publishRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'dist\Unity'))
$installRoot = [IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Programs\UnityWindows'))
$markerPath = Join-Path $installRoot '.unity-install.json'
$registryPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\UnityWindows'
$shortcutName = 'Unity Phone Companion.lnk'
$startMenuRoot = Join-Path ([Environment]::GetFolderPath('Programs')) 'Unity Phone Companion'
$desktopShortcut = Join-Path ([Environment]::GetFolderPath('Desktop')) $shortcutName

if ([Environment]::OSVersion.Version.Build -lt 22000) { throw 'Unity requires Windows 11.' }
foreach ($required in @('Unity.exe','Unity.dll','Unity.pri','coreclr.dll','hostfxr.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $publishRoot $required) -PathType Leaf)) {
        throw "Missing standalone build file: $required. Run scripts/build.ps1 -Publish -SelfContained first."
    }
}
if ($installRoot -like '*\Packages\sandbox.*') { throw 'Run installation from a normal Windows user session, outside the execution sandbox.' }
if (Test-Path -LiteralPath $installRoot) {
    if (-not (Test-Path -LiteralPath $markerPath)) { throw "An unmanaged folder already exists at $installRoot" }
    $existing = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
    if ($existing.ProductId -ne $productId) { throw 'The install folder belongs to another application.' }
    if ((Get-Item -LiteralPath $installRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'The install folder must not be a link.' }
}
$version = (Get-Item -LiteralPath (Join-Path $publishRoot 'Unity.exe')).VersionInfo.ProductVersion.Split('+')[0]
$folderName = 'app-' + $version + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$applicationRoot = Join-Path $installRoot $folderName
New-Item -ItemType Directory -Path $applicationRoot -Force | Out-Null

$sourceFiles = @(Get-ChildItem -LiteralPath $publishRoot -Recurse -File)
foreach ($item in Get-ChildItem -LiteralPath $publishRoot -Force) {
    Copy-Item -LiteralPath $item.FullName -Destination $applicationRoot -Recurse -Force
}
$manifestFiles = @()
foreach ($file in $sourceFiles) {
    $relative = $file.FullName.Substring($publishRoot.Length).TrimStart('\')
    $installedFile = Join-Path $applicationRoot $relative
    $sourceHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $installedFile -Algorithm SHA256).Hash -ne $sourceHash) { throw "Install verification failed: $relative" }
    $manifestFiles += [pscustomobject]@{ Path=$relative; Sha256=$sourceHash; Length=$file.Length }
}
$executable = Join-Path $applicationRoot 'Unity.exe'
$uninstaller = Join-Path $installRoot 'Uninstall.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'uninstall.ps1') -Destination $uninstaller -Force

$shell = New-Object -ComObject WScript.Shell
New-Item -ItemType Directory -Path $startMenuRoot -Force | Out-Null
$startMenuShortcut = Join-Path $startMenuRoot $shortcutName
foreach ($shortcutPath in @($startMenuShortcut, $desktopShortcut)) {
    if (Test-Path -LiteralPath $shortcutPath) {
        $oldShortcut = $shell.CreateShortcut($shortcutPath)
        if (-not $oldShortcut.TargetPath.StartsWith($installRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "An unrelated shortcut exists: $shortcutPath" }
    }
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $executable
    $shortcut.WorkingDirectory = $applicationRoot
    $shortcut.Description = 'Unity - local Android phone companion'
    $shortcut.IconLocation = $executable + ',0'
    $shortcut.Save()
}

$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$uninstallCommand = '"' + $powershell + '" -NoProfile -ExecutionPolicy Bypass -File "' + $uninstaller + '"'
New-Item -Path $registryPath -Force | Out-Null
foreach ($entry in @{
    DisplayName='Unity Phone Companion'; DisplayVersion=$version; Publisher='Unity Connect';
    InstallLocation=$installRoot; DisplayIcon=($executable + ',0'); UninstallString=$uninstallCommand
}.GetEnumerator()) { New-ItemProperty -LiteralPath $registryPath -Name $entry.Key -Value $entry.Value -PropertyType String -Force | Out-Null }
foreach ($name in @('NoModify','NoRepair')) { New-ItemProperty -LiteralPath $registryPath -Name $name -Value 1 -PropertyType DWord -Force | Out-Null }
$estimatedSize = [int][Math]::Ceiling(($sourceFiles | Measure-Object -Property Length -Sum).Sum / 1KB)
New-ItemProperty -LiteralPath $registryPath -Name EstimatedSize -Value $estimatedSize -PropertyType DWord -Force | Out-Null

[pscustomobject]@{
    ProductId=$productId; Version=$version; InstalledAt=[DateTimeOffset]::Now.ToString('O');
    ApplicationFolder=$folderName; Executable=$executable; Files=$manifestFiles;
    Shortcuts=@($startMenuShortcut,$desktopShortcut)
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $markerPath -Encoding UTF8

Write-Output "Installed Unity $version to $applicationRoot"
Write-Output "Verified $($manifestFiles.Count) files with SHA-256."
Write-Output "Start menu: $startMenuShortcut"
Write-Output "Desktop: $desktopShortcut"
if ($Launch) { Start-Process -FilePath $executable -WorkingDirectory $applicationRoot -WindowStyle Normal }
