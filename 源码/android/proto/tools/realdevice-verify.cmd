@echo off
rem One-command real-device verification. Args are passed through to realdevice-bench.ps1.
rem Usage: realdevice-verify.cmd -Serial <sn> -Runs 3 -Frames 20
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0realdevice-bench.ps1" %*
set RC=%ERRORLEVEL%
if "%RC%"=="0" (echo [DONE] see OutDir above) else (echo [FAIL] exit=%RC%)
exit /b %RC%
