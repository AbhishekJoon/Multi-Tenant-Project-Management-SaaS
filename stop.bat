@echo off
setlocal
title TeamFlow - Stop
cd /d "%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-local.ps1" -KeepDatabase
if errorlevel 1 (
    echo.
    echo TeamFlow could not stop. Check the error above.
    pause
    exit /b 1
)

echo.
echo TeamFlow has stopped. Database data is retained.
pause
exit /b 0
