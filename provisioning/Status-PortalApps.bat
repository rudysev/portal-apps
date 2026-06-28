@echo off
REM Windows double-click entry point. Double-click to check whether both apps are installed.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Status
echo.
pause
