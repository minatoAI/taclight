@echo off
setlocal
set "PROJECT=%~dp0"
set "JAVA_HOME=%PROJECT%..\.tools\jdk-17"
set "GRADLE_USER_HOME=%PROJECT%.gradle-user-home"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Project JDK 17 is missing: %JAVA_HOME%
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%PROJECT%gradlew.bat" %*
exit /b %ERRORLEVEL%
