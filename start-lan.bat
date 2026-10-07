@echo off
setlocal
cd /d "%~dp0"
set BRUGMONITOR_HOST=0.0.0.0
python app.py
pause
