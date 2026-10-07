param([switch]$RequireFirebase, [ValidateSet('Debug', 'Release')][string]$BuildType = 'Debug')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$firebaseConfig = Join-Path $repoRoot 'android\app\google-services.json'
if ($RequireFirebase -and -not (Test-Path -LiteralPath $firebaseConfig)) { throw 'Download eerst google-services.json voor nl.brugmonitor.app en plaats het in android/app.' }
if (Test-Path -LiteralPath $firebaseConfig) {
    $config = Get-Content -LiteralPath $firebaseConfig -Raw | ConvertFrom-Json
    $packages = @($config.client | ForEach-Object { $_.client_info.android_client_info.package_name })
    if ('nl.brugmonitor.app' -notin $packages) { throw 'google-services.json hoort niet bij nl.brugmonitor.app. Download het bestand voor de Brugmonitor Android-app.' }
    Write-Host 'Firebase-status en pushmeldingen worden meegenomen.'
} else { Write-Warning 'Firebase-config ontbreekt: deze APK toont alleen de installatiemelding. Gebruik -RequireFirebase voor de definitieve app.' }
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\sdk' }
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platforms\android-36'))) { throw 'Android SDK platform 36 ontbreekt.' }
$javaCandidates = @($env:JAVA_HOME, 'C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot', 'C:\Program Files\Java\jdk-17')
$javaRoot = $javaCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) } | Select-Object -First 1
if (-not $javaRoot) { throw 'Java 17 of nieuwer ontbreekt. Stel JAVA_HOME in.' }
$env:JAVA_HOME = $javaRoot
if ($BuildType -eq 'Release') {
    & (Join-Path $PSScriptRoot 'prepare-release-signing.ps1') -JavaRoot $javaRoot
}
$variant = $BuildType.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $repoRoot 'android\local.properties') -Value ('sdk.dir=' + $sdk.Replace('\', '/').Replace(':', '\:')) -Encoding ASCII
Push-Location (Join-Path $repoRoot 'android')
try {
    & (Join-Path $javaRoot 'bin\java.exe') -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain --no-daemon "assemble$BuildType" "lint$BuildType"
    if ($LASTEXITCODE -ne 0) { throw 'Android build of lint mislukt.' }
    $dist = Join-Path $repoRoot 'dist'
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    Copy-Item -LiteralPath ("app\build\outputs\apk\$variant\app-$variant.apk") -Destination (Join-Path $dist ("Brugmonitor-$variant.apk")) -Force
    Write-Host ('APK gereed: ' + (Join-Path $dist ("Brugmonitor-$variant.apk")))
} finally { Pop-Location }
