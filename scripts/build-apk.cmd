@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" -RequireFirebase %*
exit /b %ERRORLEVEL%
