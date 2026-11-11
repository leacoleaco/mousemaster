@echo off
setlocal EnableExtensions
cd /d "%~dp0"

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
    echo If it is running as administrator, run this script as administrator.
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
