@echo off
REM One-click Discord -> launcher updates sync (Windows).
REM Reads tools\discord_sync.json (or the OASIS_DISCORD_TOKEN env var) and pushes the feeds.
cd /d "%~dp0\.."
python tools\discord_sync.py %*
echo.
pause
