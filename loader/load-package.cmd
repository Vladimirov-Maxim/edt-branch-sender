@echo off
rem Load a change package into a 1C infobase. Run without parameters for help.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0load-package.ps1" %*
exit /b %ERRORLEVEL%
