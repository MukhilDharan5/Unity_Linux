param([switch]$Offline, [switch]$AndroidInterop, [switch]$ProbePlatform)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:DOTNET_CLI_HOME = Join-Path $projectRoot '.tools/dotnet'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:NUGET_PACKAGES = Join-Path $projectRoot '.tools/packages'
Push-Location $projectRoot
try {
    $restoreArgs = @('restore', 'tests/Unity.Tests/Unity.Tests.csproj', '-m:1', '--nologo')
    if ($Offline) { $restoreArgs += @('--source', (Join-Path $env:USERPROFILE '.nuget/packages'), '-p:NuGetAudit=false') }
    & dotnet @restoreArgs
    if ($LASTEXITCODE -ne 0) { throw 'Test restore failed' }
    & dotnet build tests/Unity.Tests/Unity.Tests.csproj --no-restore -m:1 --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Test build failed' }
    $testArguments = @()
    if ($ProbePlatform) { $testArguments = @('--probe-platform') }
    if ($AndroidInterop) {
        $studio = Join-Path ${env:ProgramFiles} 'Android/Android Studio'
        $java = Join-Path $studio 'jbr/bin/java.exe'
        $compiler = Join-Path $studio 'plugins/Kotlin/kotlinc/bin/kotlinc.bat'
        if (-not (Test-Path -LiteralPath $compiler)) { throw 'Android Studio with its Kotlin compiler is required for -AndroidInterop' }
        New-Item -ItemType Directory -Force -Path .tools/interop | Out-Null
        if (-not (Test-Path -LiteralPath .tools/interop/json.jar)) {
            if ($Offline) { throw 'Run once without -Offline to download the org.json test dependency' }
            Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile .tools/interop/json.jar
        }
        $env:JAVA_HOME = Join-Path $studio 'jbr'
        & $compiler ../Unity_Android/app/src/main/java/org/unityconnect/companion/Protocol.kt ../Unity_Android/app/src/main/java/org/unityconnect/companion/PinnedTls.kt tests/AndroidPeer.kt -cp .tools/interop/json.jar -include-runtime -d .tools/interop/android-peer.jar
        if ($LASTEXITCODE -ne 0) { throw 'Android protocol peer compilation failed' }
        $testArguments = @($java, (Join-Path $projectRoot '.tools/interop/android-peer.jar'), (Join-Path $projectRoot '.tools/interop/json.jar'))
    }
    & dotnet tests/Unity.Tests/bin/Debug/net10.0-windows10.0.26100.0/Unity.Tests.dll @testArguments
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
}
finally { Pop-Location }
