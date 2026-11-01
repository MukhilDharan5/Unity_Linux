param(
    [ValidateSet('Debug','Release')][string]$Configuration = 'Release',
    [switch]$Offline,
    [switch]$Publish,
    [switch]$SelfContained
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:DOTNET_CLI_HOME = Join-Path $projectRoot '.tools/dotnet'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
$env:NUGET_PACKAGES = Join-Path $projectRoot '.tools/packages'
Push-Location $projectRoot
try {
    $restoreArgs = @('restore', 'Unity.slnx', '-m:1', '--nologo')
    if ($Offline) { $restoreArgs += @('--source', (Join-Path $env:USERPROFILE '.nuget/packages'), '-p:NuGetAudit=false') }
    & dotnet @restoreArgs
    if ($LASTEXITCODE -ne 0) { throw 'Dependency restore failed' }
    & dotnet build Unity.slnx -c $Configuration --no-restore -m:1 --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    if ($Publish) {
        $publishArgs = @('publish', 'src/Unity.Windows/Unity.Windows.csproj', '-c', $Configuration, '--no-restore', '-m:1', '-o', 'dist/Unity', '--nologo')
        if ($SelfContained) {
            $publishArgs = $publishArgs | Where-Object { $_ -ne '--no-restore' }
            $publishArgs += @('--self-contained', 'true')
            if ($Offline) { $publishArgs += @('--source', (Join-Path $env:USERPROFILE '.nuget/packages'), '-p:NuGetAudit=false') }
        }
        & dotnet @publishArgs
        if ($LASTEXITCODE -ne 0) { throw 'Publish failed' }
        if (-not (Test-Path -LiteralPath dist/Unity/Unity.pri)) { throw 'Published WinUI resource index is missing' }
        Copy-Item -LiteralPath README.md -Destination dist/Unity/README.md -Force
        Copy-Item -LiteralPath ../LICENSE -Destination dist/Unity/LICENSE -Force
        Write-Host "Launch $(Join-Path $projectRoot 'dist/Unity/Unity.exe')"
    }
}
finally { Pop-Location }
