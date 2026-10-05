@echo off
setlocal
set "PROJECT=%~dp0"
set "JAVA_HOME=%TACLIGHT_JDK17%"
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=E:\dshHome\mc-shader-spotlight-dev-qa\.tools\jdk-17"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [TacLight] JDK 17 not found. Set TACLIGHT_JDK17.
    exit /b 1
)
set "GRADLE_USER_HOME=%PROJECT%.gradle-user-home"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%PROJECT%gradlew.bat" %*
exit /b %ERRORLEVEL%
