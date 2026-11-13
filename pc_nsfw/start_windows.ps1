# EPV Windows USB classifier setup (PowerShell 5.1+).
# Works from an extracted ZIP and from the GitHub repository.
# Prefers active Python 3.12-3.14 (including Conda), and falls back to Conda.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSCommandPath
$Venv = Join-Path $Root ".venv"
$VenvPython = Join-Path $Venv "Scripts\python.exe"
$CondaEnv = Join-Path $Root ".conda-epv"
$CondaPython = Join-Path $CondaEnv "python.exe"

function Test-UsablePython([string]$Executable) {
    if (-not $Executable) { return $false }
    if (-not (Test-Path $Executable) -and -not (Get-Command $Executable -ErrorAction SilentlyContinue)) {
        return $false
    }
    try {
        & $Executable -c "import sys, struct; sys.exit(0 if (3, 12) <= sys.version_info[:2] <= (3, 14) and struct.calcsize('P') == 8 else 1)" 2>$null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    }
}

function Run-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw ("Command failed: " + $Executable + " " + ($Arguments -join " "))
    }
}

$Python = $null
if (Test-UsablePython $VenvPython) {
    $Python = $VenvPython
} elseif (Test-UsablePython $CondaPython) {
    $Python = $CondaPython
} elseif (Test-Path $VenvPython) {
    throw "Existing .venv Python is incompatible. Remove only this script's .venv folder and retry."
} else {
    # The py launcher does not discover many Conda installations, so check
    # active 'python' BEFORE attempting 'py -3.12'.
    $Candidate = $null
    $active = Get-Command python -ErrorAction SilentlyContinue
    if ($active -and (Test-UsablePython $active.Source)) {
        $Candidate = $active.Source
    } else {
        foreach ($version in @("3.14", "3.13", "3.12")) {
            try {
                $result = & py "-$version" -c "import sys; print(sys.executable)" 2>$null
                if ($LASTEXITCODE -eq 0 -and $result -and (Test-UsablePython $result.Trim())) {
                    $Candidate = $result.Trim()
                    break
                }
            } catch { }
        }
    }

    if ($Candidate) {
        Write-Host "Using installed Python: $Candidate"
        Run-Checked $Candidate @("-m", "venv", $Venv)
        if (-not (Test-UsablePython $VenvPython)) {
            throw "Python venv creation failed. Check your Python installation."
        }
        $Python = $VenvPython
    } else {
        # On Windows with Anaconda/Miniconda, Conda can create an isolated
        # Python 3.12 environment without changing the base environment.
        $Conda = Get-Command conda -ErrorAction SilentlyContinue
        if (-not $Conda) {
            throw "Python 3.12-3.14 x64 not found. Install it from python.org or install Conda; then rerun."
        }
        Write-Host "No compatible Python found. Creating a private Conda Python 3.12 environment..."
        & conda create --prefix $CondaEnv python=3.12 -y
        if ($LASTEXITCODE -ne 0 -or -not (Test-UsablePython $CondaPython)) {
            throw "Conda could not create Python 3.12. Try: conda create -n epv-usb python=3.12 -y"
        }
        $Python = $CondaPython
    }
}

Write-Host "EPV Python: $Python"
Run-Checked $Python @("-m", "pip", "install", "--disable-pip-version-check", "-r", (Join-Path $Root "requirements.txt"))

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    throw "Android Platform Tools (adb) are not on PATH. Install adb, reopen PowerShell, and retry."
}
$Devices = & adb devices
Write-Host ($Devices -join [Environment]::NewLine)
if ($LASTEXITCODE -ne 0 -or -not ($Devices | Where-Object { $_ -match '^\S+\s+device(\s|$)' })) {
    throw "No authorized Android device. Unlock phone, enable USB debugging and accept ADB authorization."
}
& adb reverse tcp:8765 tcp:8765
if ($LASTEXITCODE -ne 0) {
    throw "adb reverse failed. Check USB and device authorization."
}
Write-Host "Connected. Starting PC inference server (4 workers)."
& $Python (Join-Path $Root "epv_server.py") --workers 6
if ($LASTEXITCODE -ne 0) {
    throw "EPV inference server stopped with an error."
}
