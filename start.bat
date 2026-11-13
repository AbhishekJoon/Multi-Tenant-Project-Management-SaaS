@echo off
setlocal
title TeamFlow - Start
cd /d "%~dp0"

echo Starting TeamFlow locally with Supabase...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-local.ps1" -Database Supabase -ReuseRunning
if errorlevel 1 (
    echo.
    echo TeamFlow could not start. Check the error above.
    echo If an old session is recorded, run stop.bat before trying again.
    pause
    exit /b 1
)

echo.
echo Open http://localhost:5173 in your browser.
echo The app keeps running after you close this window.
echo Run stop.bat to stop it.
pause
exit /b 0
