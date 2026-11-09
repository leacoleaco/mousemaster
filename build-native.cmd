@echo off
set "JAVA_HOME=C:\tools\graalvm-25.4.4.1.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call package.cmd native
