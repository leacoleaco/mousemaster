@echo off
set "JAVA_HOME=C:\tools\graalvm-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call package.cmd native
