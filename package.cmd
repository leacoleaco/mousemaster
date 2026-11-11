@echo off
setlocal EnableExtensions
cd /d "%~dp0"

if /i "%~1"=="native" goto :native
if /i "%~1"=="jar" goto :jar
if "%~1"=="" goto :jar

echo Usage:
echo   %~nx0           Build fat JAR ^(mvnw clean package^)
echo   %~nx0 jar       Same as above
echo   %~nx0 native    Build mousemaster.exe ^(requires GraalVM JDK 21 on JAVA_HOME^)
exit /b 1

:jar
call mvnw.cmd clean package
exit /b %ERRORLEVEL%

:native
if not defined JAVA_HOME (
  echo ERROR: Set JAVA_HOME to GraalVM JDK 21 before native build.
  exit /b 1
)
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
exit /b %ERRORLEVEL%
