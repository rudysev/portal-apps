@echo off
REM Windows double-click entry point. Double-click to add or change your Gemini API key.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Key
echo.
pause
