param([switch]$Demo)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$executable = Join-Path $projectRoot 'dist/Unity/Unity.exe'
if (-not (Test-Path -LiteralPath $executable)) { & (Join-Path $PSScriptRoot 'build.ps1') -Publish }
$appArguments = @()
if ($Demo) { $appArguments += '--demo' }
if ($appArguments.Count) { Start-Process -FilePath $executable -ArgumentList $appArguments -WindowStyle Normal }
else { Start-Process -FilePath $executable -WindowStyle Normal }
