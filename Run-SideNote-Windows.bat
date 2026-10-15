@echo off
title SideNote Desktop Launcher
cd /d "%~dp0"

if exist "%LOCALAPPDATA%\SideNote\sidenote-windows.exe" (
    start "" "%LOCALAPPDATA%\SideNote\sidenote-windows.exe"
    exit
)

if exist "SideNote-Windows-Setup.exe" (
    start "" "SideNote-Windows-Setup.exe"
    exit
)

echo [SideNote] App not found. Please run SideNote-Windows-Setup.exe to install.
pause
