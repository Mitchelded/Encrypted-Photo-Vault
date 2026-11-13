# Runs from any location after extracting the PC classifier ZIP.
# Requires Windows 11, Python 3.12 (x64), Android Platform Tools (adb).
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSCommandPath
$Venv = Join-Path $Root ".venv"
$Python = Join-Path $Venv "Scripts\python.exe"
if (-not (Test-Path $Python)) {
    py -3.12 -m venv $Venv
}
& $Python -c "import ai_edge_litert, PIL, numpy" 2>$null
if ($LASTEXITCODE -ne 0) {
    & $Python -m pip install -r (Join-Path $Root "requirements.txt")
    if ($LASTEXITCODE -ne 0) { throw "Python dependencies could not be installed" }
}
Write-Host "Connect and unlock your Android device (USB debugging must be enabled)."
adb devices
adb reverse tcp:8765 tcp:8765
& $Python (Join-Path $Root "epv_server.py") --workers 4
