@echo off
setlocal EnableExtensions

net session >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -Verb RunAs -FilePath '%~f0' -WorkingDirectory '%~dp0'"
    exit /b
)

cd /d "%~dp0"

set "JAVA=%~dp0runtime\bin\javaw.exe"
set "JAR=%~dp0mousemaster-95-jar-with-dependencies.jar"

if not exist "%JAVA%" (
    echo ERROR: runtime not found: %JAVA%
    pause
    exit /b 1
)
if not exist "%JAR%" (
    echo ERROR: JAR not found: %JAR%
    pause
    exit /b 1
)

start "" "%JAVA%" --enable-native-access=ALL-UNNAMED -jar "%JAR%" "--configuration-file=%~dp0mousemaster.properties"
