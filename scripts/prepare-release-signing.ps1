param([Parameter(Mandatory=$true)][string]$JavaRoot)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$secretDirectory = Join-Path $repoRoot '.secrets'
$keyFile = Join-Path $secretDirectory 'brugmonitor-release.jks'
$propertiesFile = Join-Path $repoRoot 'android\keystore.properties'
if ((Test-Path -LiteralPath $keyFile) -and (Test-Path -LiteralPath $propertiesFile)) { return }
if ((Test-Path -LiteralPath $keyFile) -or (Test-Path -LiteralPath $propertiesFile)) {
    throw 'Release-sleutel of keystore.properties ontbreekt. Herstel beide uit je back-up; maak geen vervangende sleutel.'
}
New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
$randomBytes = New-Object byte[] 32
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
$password = [Convert]::ToBase64String($randomBytes)
$env:BRUGMONITOR_SIGNING_PASSWORD = $password
try {
    & (Join-Path $JavaRoot 'bin\keytool.exe') -genkeypair -noprompt -keystore $keyFile -storetype JKS -alias brugmonitor -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Brugmonitor' -storepass:env BRUGMONITOR_SIGNING_PASSWORD -keypass:env BRUGMONITOR_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Release-sleutel aanmaken mislukt.' }
    $properties = "storeFile=../.secrets/brugmonitor-release.jks`nstorePassword=$password`nkeyAlias=brugmonitor`nkeyPassword=$password`n"
    [System.IO.File]::WriteAllText($propertiesFile, $properties, [System.Text.UTF8Encoding]::new($false))
    Write-Host 'Vaste release-sleutel aangemaakt. Bewaar .secrets/brugmonitor-release.jks en android/keystore.properties samen in een veilige back-up.'
} finally { Remove-Item Env:BRUGMONITOR_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
