@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem ===== 部署目录：与 deploy.cmd 保持一致 =====
set "DEPLOY_DIR=D:\mousemaster"
set "SRC=%~dp0configuration\mousemaster.properties"

if not exist "%SRC%" (
    echo.
    echo ERROR: configuration file not found:
    echo   %SRC%
    goto :fail
)

if not exist "%DEPLOY_DIR%\" mkdir "%DEPLOY_DIR%"
if errorlevel 1 (
    echo.
    echo ERROR: cannot create %DEPLOY_DIR%
    goto :fail
)

echo Copying mousemaster.properties to %DEPLOY_DIR%
copy /Y "%SRC%" "%DEPLOY_DIR%\"
if errorlevel 1 (
    echo.
    echo ERROR: copy to %DEPLOY_DIR% failed.
    goto :fail
)

echo.
echo Copied to %DEPLOY_DIR%\mousemaster.properties
exit /b 0

:fail
echo.
pause
exit /b 1
