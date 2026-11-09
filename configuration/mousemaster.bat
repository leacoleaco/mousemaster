@echo off
setlocal EnableExtensions

net session >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -Verb RunAs -FilePath '%~f0' -WorkingDirectory '%~dp0'"
    exit /b
)

cd /d "%~dp0"

set "JAVA=%~dp0runtime\bin\javaw.exe"
set "JAR="
for /f "delims=" %%F in ('dir /b /a:-d /o:-d "%~dp0mousemaster-*-jar-with-dependencies.jar" 2^>nul') do (
    if not defined JAR set "JAR=%~dp0%%F"
)

if not exist "%JAVA%" (
    echo ERROR: runtime not found: %JAVA%
    pause
    exit /b 1
)
if not defined JAR (
    echo ERROR: JAR not found: %~dp0mousemaster-*-jar-with-dependencies.jar
    pause
    exit /b 1
)
if not exist "%JAR%" (
    echo ERROR: JAR not found: %JAR%
    pause
    exit /b 1
)

start "" "%JAVA%" --enable-native-access=ALL-UNNAMED -jar "%JAR%" "--configuration-file=%~dp0mousemaster.properties"
