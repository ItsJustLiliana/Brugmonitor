param([Parameter(Mandatory = $true)][ValidateSet('GOOGLE_SERVICES_JSON', 'ANDROID_DEBUG_KEYSTORE_BASE64')][string]$Name)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if ($Name -eq 'GOOGLE_SERVICES_JSON') {
    $path = Join-Path $repoRoot 'android\app\google-services.json'
    $value = Get-Content -LiteralPath $path -Raw
    $null = $value | ConvertFrom-Json
} else {
    $path = Join-Path $env:USERPROFILE '.android\debug.keystore'
    $value = [Convert]::ToBase64String([IO.File]::ReadAllBytes($path))
}
Set-Clipboard -Value $value
Write-Host "$Name staat op het klembord. Plak dit als repository secret in GitHub; de waarde wordt niet afgedrukt."
