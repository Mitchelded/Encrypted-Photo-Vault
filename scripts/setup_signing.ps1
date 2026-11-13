# Create one PRIVATE persistent signing key and configure GitHub Secrets.
# Requires JDK 17+ (keytool) and GitHub CLI (gh auth login).
param([string]$Repository = "Mitchelded/Encrypted-Photo-Vault")
$ErrorActionPreference = "Stop"
$keyDir = Join-Path $env:USERPROFILE ".epv-signing"
$keyPath = Join-Path $keyDir "epv-signing.p12"
$passwordPath = Join-Path $keyDir "signing-password.txt"
$alias = "epv"
New-Item -ItemType Directory -Path $keyDir -Force | Out-Null
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw "Install JDK 17+ and add keytool.exe to PATH."
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "Install GitHub CLI (gh) and run gh auth login."
}
& gh auth status
if ($LASTEXITCODE -ne 0) { throw "Run gh auth login first" }
if ((Test-Path $keyPath) -xor (Test-Path $passwordPath)) {
    throw "Only one of the signing key/password files exists. Restore both from your backup."
}
if (!(Test-Path $keyPath)) {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $secretBytes = New-Object byte[] 32
    try { $rng.GetBytes($secretBytes) } finally { $rng.Dispose() }
    $password = [Convert]::ToBase64String($secretBytes)
    & keytool -genkeypair -alias $alias -keyalg RSA -keysize 3072 -validity 3650 -keystore $keyPath -storetype PKCS12 -storepass $password -keypass $password -dname "CN=Encrypted Photo Vault,O=Mitchelded,C=US" -noprompt
    if ($LASTEXITCODE -ne 0) { throw "Key generation failed" }
    [System.IO.File]::WriteAllText($passwordPath, $password)
} else {
    $password = [System.IO.File]::ReadAllText($passwordPath).Trim()
    & keytool -list -keystore $keyPath -storepass $password -alias $alias | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Existing signing key invalid" }
}
$encoded = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($keyPath))
& gh secret set EPV_SIGNING_KEYSTORE_B64 --repo $Repository --body $encoded
if ($LASTEXITCODE -ne 0) { throw "Cannot upload signing keystore secret" }
& gh secret set EPV_SIGNING_PASSWORD --repo $Repository --body $password
if ($LASTEXITCODE -ne 0) { throw "Cannot upload signing password secret" }
& gh secret set EPV_SIGNING_ALIAS --repo $Repository --body $alias
if ($LASTEXITCODE -ne 0) { throw "Cannot upload signing alias" }

Write-Host "Permanent signing key configured for both Android and Vector apps."
Write-Host "Back up this PRIVATE folder offline: $keyDir"
Write-Host "Do NOT commit/share the .p12 file or its password."
Write-Host "Old APK signatures differ; first back up app data and reinstall once."
