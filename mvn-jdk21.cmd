@echo off
setlocal
set "JAVA_HOME=%USERPROFILE%\.jdks\ms-21.0.11"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%~dp0mvnw.cmd" %*
exit /b %ERRORLEVEL%
