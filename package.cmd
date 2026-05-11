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
call mvnw.cmd -Pnative package -DskipTests
exit /b %ERRORLEVEL%
