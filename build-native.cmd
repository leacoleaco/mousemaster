@echo off
set "JAVA_HOME=C:\tools\graalvm-25.4.4.1.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call package.cmd native
if errorlevel 1 exit /b %ERRORLEVEL%
explorer /select,"%~dp0target\mousemaster.exe"
exit /b 0
