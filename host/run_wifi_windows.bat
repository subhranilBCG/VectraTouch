@echo off
chcp 65001 >nul
title VectraTouch Wi-Fi Companion
cls
echo ============================================================
echo   VectraTouch -- Wi-Fi Companion Launcher
echo ============================================================
echo.

set PYTHON_CMD=
where python >nul 2>nul
if %ERRORLEVEL% equ 0 (
    set PYTHON_CMD=python
) else (
    where py >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        set PYTHON_CMD=py
    )
)

if "%PYTHON_CMD%"=="" (
    echo [!] Python was not found in your PATH.
    echo     Please install Python 3 from https://www.python.org/
    pause
    exit /b 1
)

cd /d "%~dp0"
%PYTHON_CMD% vectra_usb_host.py --wifi %*

echo.
echo ============================================================
echo   VectraTouch Wi-Fi session ended.
echo ============================================================
echo Press any key to close this window...
pause >nul
