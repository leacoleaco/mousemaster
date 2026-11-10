@echo off
setlocal EnableExtensions
cd /d "%~dp0"
chcp 65001 >nul
title mousemaster dev

rem Compile, then run configuration\mousemaster.properties in this console.
rem java.exe stays attached to this window. Do not switch this to javaw.exe or start:
rem closing the window must stop the process.
rem Extra arguments are forwarded, for example:
rem   start_dev.cmd --log-level=trace
rem   start_dev.cmd --multiple-instances-allowed=true

set "DOUBLE_CLICK=0"
echo %cmdcmdline% | find /i "%~f0" >nul && set "DOUBLE_CLICK=1"

rem Classes are Java 25. The shell JAVA_HOME is often a JDK 21, which cannot run them.
call :resolve_jdk
if errorlevel 1 goto :fail
echo Using JDK: %JAVA_HOME%

set "CONFIG=%~dp0configuration\mousemaster.properties"
if not exist "%CONFIG%" (
    echo ERROR: configuration file not found:
    echo   %CONFIG%
    goto :fail
)

echo Building mousemaster ^(skip tests, skip native version info^)...
call mvnw.cmd -Dmaven.test.skip=true -Dexec.skip=true package
if errorlevel 1 goto :build_failed

set "JAR="
for /f "delims=" %%F in ('dir /b /a:-d /o:-d "target\mousemaster-*-jar-with-dependencies.jar" 2^>nul') do (
    if not defined JAR set "JAR=%~dp0target\%%F"
)
if not defined JAR (
    echo ERROR: fat jar not found: target\mousemaster-*-jar-with-dependencies.jar
    goto :fail
)

cd /d "%~dp0"
title mousemaster dev
echo.
echo JAR:  %JAR%
echo Conf: %CONFIG%
echo Close this window to exit mousemaster.
net session >nul 2>&1
if errorlevel 1 echo NOTE: not running as administrator. Right-click this script and choose Run as administrator if overlays must cover elevated windows.
echo.

"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED -Dmousemaster.keepConsole=true -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%" "--configuration-file=%CONFIG%" %*
set "EXITCODE=%ERRORLEVEL%"
echo.
echo mousemaster exited with code %EXITCODE%.
if not "%EXITCODE%"=="0" goto :pause_exit
if "%DOUBLE_CLICK%"=="1" pause
exit /b 0

:build_failed
set "EXITCODE=%ERRORLEVEL%"
echo.
echo Build failed with code %EXITCODE%.
goto :pause_exit

:fail
set "EXITCODE=1"
echo.

:pause_exit
pause
exit /b %EXITCODE%

:resolve_jdk
set "DEV_JAVA="
if defined JAVA_HOME call :use_if_jdk25 "%JAVA_HOME%"
if not defined DEV_JAVA if exist "C:\tools\graalvm-25.4.4.1.1\bin\java.exe" call :use_if_jdk25 "C:\tools\graalvm-25.4.4.1.1"
if not defined DEV_JAVA for /d %%D in ("C:\tools\graalvm-25*") do if not defined DEV_JAVA call :use_if_jdk25 "%%~D"
if not defined DEV_JAVA (
    echo ERROR: JDK 25 not found.
    echo Set JAVA_HOME to a JDK 25, or install it at C:\tools\graalvm-25.4.4.1.1
    if defined JAVA_HOME echo Current JAVA_HOME: %JAVA_HOME%
    exit /b 1
)
if defined JAVA_HOME if /i not "%DEV_JAVA%"=="%JAVA_HOME%" (
    echo JAVA_HOME is not JDK 25: %JAVA_HOME%
    echo Using %DEV_JAVA% for this run.
)
set "JAVA_HOME=%DEV_JAVA%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
exit /b 0

:use_if_jdk25
set "CANDIDATE=%~1"
if not exist "%CANDIDATE%\bin\java.exe" exit /b 0
set "MAJOR="
set "VERFILE=%TEMP%\mousemaster-java-version.txt"
"%CANDIDATE%\bin\java.exe" -version >"%VERFILE%" 2>&1
for /f "usebackq tokens=3" %%V in ("%VERFILE%") do (
    if not defined MAJOR set "MAJOR=%%~V"
)
del "%VERFILE%" >nul 2>&1
if not defined MAJOR exit /b 0
for /f "tokens=1 delims=." %%M in ("%MAJOR%") do set "MAJOR=%%M"
if "%MAJOR%"=="" exit /b 0
set /a "MAJOR_NUM=%MAJOR%" 2>nul
if errorlevel 1 exit /b 0
if %MAJOR_NUM% GEQ 25 set "DEV_JAVA=%CANDIDATE%"
exit /b 0
