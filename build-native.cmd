@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "JAVA_HOME=C:\tools\graalvm-25.4.4.1.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem Version info is compiled here. Maven's exec plugin has failed to spawn
rem powershell.exe with access denied partway through a full package.
for /f "tokens=3 delims=<>" %%V in ('findstr /c:"<version>" pom.xml') do (
  set "APP_VERSION=%%V"
  goto :version_found
)
echo ERROR: project version not found in pom.xml
exit /b 1

:version_found
if not exist "%~dp0target" mkdir "%~dp0target"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\versioninfo\compile.ps1" -version %APP_VERSION% -outputFile "%~dp0target\mousemaster.res"
if errorlevel 1 exit /b %ERRORLEVEL%
call mvnw.cmd -Pnative package -DskipTests -Dexec.skip=true
if errorlevel 1 exit /b %ERRORLEVEL%
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\versioninfo\finalize-exe.ps1" -exe "%~dp0target\mousemaster.exe"
if errorlevel 1 exit /b %ERRORLEVEL%

explorer /select,"%~dp0target\mousemaster.exe"
exit /b 0
