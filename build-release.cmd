@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-apk.ps1" -RequireFirebase -BuildType Release %*
exit /b %ERRORLEVEL%
