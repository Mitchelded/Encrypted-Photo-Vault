@echo off
chcp 65001 > nul
cd /d "%~dp0"
where py >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
 echo Install Python 3 from https://www.python.org/downloads/ and restart.
 pause
 exit /b 1
)
py -m pip install -r requirements.txt
if %ERRORLEVEL% NEQ 0 (
 echo Dependency installation failed.
 pause
 exit /b 1
)
py -m epv.gui
if %ERRORLEVEL% NEQ 0 pause
