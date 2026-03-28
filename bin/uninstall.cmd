@echo off
setlocal enabledelayedexpansion

:: =============================================
::  Solon Code Uninstaller (Windows)
::  Supports both User and System level uninstall
:: =============================================

echo.
echo ============================================
echo    Solon Code Uninstaller
echo ============================================
echo.

:: Get install directory
set "INSTALL_DIR=%~dp0"
if "%INSTALL_DIR:~-1%"=="\" set "INSTALL_DIR=%INSTALL_DIR:~0,-1%"

:: Detect if running as admin
net session >nul 2>&1
set "IS_ADMIN=0"
if %errorLevel% equ 0 set "IS_ADMIN=1"

if %IS_ADMIN% equ 1 (
    echo [Info] Running with Administrator privileges
) else (
    echo [Info] Running without Administrator privileges
    echo        Will uninstall from User scope only
)

echo.

:: Confirm uninstall
set /p CONFIRM="Uninstall Solon Code from: %INSTALL_DIR% ? (Y/N): "
if /i not "%CONFIRM%"=="Y" (
    echo Cancelled.
    pause
    exit /b 0
)

:: ============================================
::  Remove from PATH (both User and System)
:: ============================================
echo.
echo [1/4] Removing from PATH...

:: Use PowerShell to handle PATH (avoids 1024-char limit of setx)
:: Remove from User PATH
powershell -NoProfile -Command ^
    "$p=[Environment]::GetEnvironmentVariable('Path','User');" ^
    "$p=$p -replace '[;]*[^^;]*soloncode[^^;]*[;]*','';" ^
    "$p=$p -replace ';;',';';" ^
    "$p=$p.TrimStart(';').TrimEnd(';');" ^
    "if($p){[Environment]::SetEnvironmentVariable('Path',$p,'User')}" ^
    >nul 2>&1
echo       Cleaned User PATH

:: Remove from System PATH (if admin)
if %IS_ADMIN% equ 1 (
    powershell -NoProfile -Command ^
        "$p=[Environment]::GetEnvironmentVariable('Path','Machine');" ^
        "$p=$p -replace '[;]*[^^;]*soloncode[^^;]*[;]*','';" ^
        "$p=$p -replace ';;',';';" ^
        "$p=$p.TrimStart(';').TrimEnd(';');" ^
        "if($p){[Environment]::SetEnvironmentVariable('Path',$p,'Machine')}" ^
        >nul 2>&1
    echo       Cleaned System PATH
)

:: ============================================
::  Remove environment variables
:: ============================================
echo.
echo [2/4] Removing environment variables...

:: User level
reg delete "HKCU\Environment" /v SOLONCODE_HOME /f >nul 2>&1
echo       Removed User SOLONCODE_HOME

:: System level (if admin)
if %IS_ADMIN% equ 1 (
    reg delete "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v SOLONCODE_HOME /f >nul 2>&1
    echo       Removed System SOLONCODE_HOME
)

:: ============================================
::  Remove ProgramData launcher directory
:: ============================================
echo.
echo [3/4] Removing launcher directory...

if exist "C:\ProgramData\soloncode" (
    rmdir /s /q "C:\ProgramData\soloncode" 2>nul
    if exist "C:\ProgramData\soloncode" (
        echo       [Note] Could not remove C:\ProgramData\soloncode ^(need admin^?
    ) else (
        echo       Removed C:\ProgramData\soloncode
    )
) else (
    echo       No ProgramData launcher found
)

:: ============================================
::  Remove launcher scripts from install dir
:: ============================================
echo.
echo [4/4] Cleaning launcher scripts...

:: Remove generated launcher scripts (keep the uninstaller itself)
if exist "%INSTALL_DIR%\soloncode.cmd" (
    del /q "%INSTALL_DIR%\soloncode.cmd" 2>nul
    echo       Removed soloncode.cmd
)

if exist "%INSTALL_DIR%\soloncode.ps1" (
    del /q "%INSTALL_DIR%\soloncode.ps1" 2>nul
    echo       Removed soloncode.ps1
)

if exist "%INSTALL_DIR%\soloncode" (
    del /q "%INSTALL_DIR%\soloncode" 2>nul
    echo       Removed soloncode ^(Git Bash^)
)

:: ============================================
::  Complete
:: ============================================
echo.
echo ============================================
echo    Uninstall Complete!
echo ============================================
echo.
echo   Note: Config and session data preserved at:
echo         %%USERPROFILE%%\.soloncode\
echo   To fully remove, manually delete that directory.
echo.
echo   [Tip] Restart your terminal for PATH changes to take effect.
echo.
pause