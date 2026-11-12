@echo off
setlocal EnableExtensions

rem mousemaster.exe is requireAdministrator. A normal token cannot stop that
rem process (taskkill returns Access Denied) or replace a file it has open.
rem S-1-16-12288 is the high integrity level, present only on an elevated token.
whoami /groups | find "S-1-16-12288" >nul
if not errorlevel 1 goto :run
if /i "%~1"=="/elevated" goto :elevate_failed
goto :elevate

:run
cd /d "%~dp0"
title mousemaster deploy

rem ===== 部署目录：以后只改这一行 =====
set "DEPLOY_DIR=D:\mousemaster"

echo Building native executable...
call "%~dp0build-native.cmd"
if errorlevel 1 (
    echo.
    echo ERROR: build-native.cmd failed.
    goto :fail
)

if not exist "%~dp0target\mousemaster.exe" (
    echo.
    echo ERROR: target\mousemaster.exe was not produced.
    goto :fail
)

call :stop_running
if errorlevel 1 goto :fail

if not exist "%DEPLOY_DIR%\" mkdir "%DEPLOY_DIR%"
if errorlevel 1 (
    echo.
    echo ERROR: cannot create %DEPLOY_DIR%
    goto :fail
)

echo Copying exe, dll, and mousemaster.properties to %DEPLOY_DIR%
copy /Y "%~dp0target\*.exe" "%DEPLOY_DIR%\"
if errorlevel 1 goto :copy_failed
copy /Y "%~dp0target\*.dll" "%DEPLOY_DIR%\"
if errorlevel 1 goto :copy_failed
copy /Y "%~dp0configuration\mousemaster.properties" "%DEPLOY_DIR%\"
if errorlevel 1 goto :copy_failed

echo.
echo Deployed to %DEPLOY_DIR%
explorer "%DEPLOY_DIR%"
exit /b 0

:stop_running
tasklist /FI "IMAGENAME eq mousemaster.exe" | find /I "mousemaster.exe" >nul
if errorlevel 1 (
    echo mousemaster is not running.
    exit /b 0
)
echo Stopping mousemaster.exe ...
taskkill /F /IM mousemaster.exe
if errorlevel 1 (
    echo.
    echo ERROR: could not stop mousemaster.exe.
    echo Close it in Task Manager, then run deploy.cmd again.
    exit /b 1
)
rem The exe stays locked for a moment after the process exits.
timeout /t 1 /nobreak >nul
exit /b 0

:copy_failed
echo.
echo ERROR: copy to %DEPLOY_DIR% failed.
echo mousemaster.exe may still be running and locking the file.

:fail
echo.
pause
exit /b 1

:elevate
echo Requesting administrator privileges...
echo Approve the User Account Control prompt.
echo Build output is shown in the new window.
set "MM_DEPLOY_CMD=%~f0"
set "MM_DEPLOY_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; try { $p = Start-Process -FilePath $env:MM_DEPLOY_CMD -ArgumentList '/elevated' -Verb RunAs -Wait -PassThru -WorkingDirectory $env:MM_DEPLOY_DIR } catch { exit 1223 }; if (-not $p) { exit 1223 }; if ($null -eq $p.ExitCode) { exit 0 }; exit $p.ExitCode"
if "%ERRORLEVEL%"=="1223" goto :elevate_failed
exit /b %ERRORLEVEL%

:elevate_failed
echo.
echo ERROR: administrator privileges were not granted.
echo deploy.cmd cannot stop an elevated mousemaster.exe without them.
pause
exit /b 1
