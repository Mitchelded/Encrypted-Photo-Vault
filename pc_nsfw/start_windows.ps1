# Start from repository root. Install Python 3.12 and Android adb first.
$ErrorActionPreference = "Stop"
if (-not (Test-Path ".venv-epv/Scripts/python.exe")) {
    py -3.12 -m venv .venv-epv
    & ".venv-epv/Scripts/python.exe" -m pip install -r pc_nsfw/requirements.txt
}
adb devices
adb reverse tcp:8765 tcp:8765
& ".venv-epv/Scripts/python.exe" pc_nsfw/epv_server.py --workers 4
