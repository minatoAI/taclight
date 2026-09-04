@echo off
set "JAVA_HOME=E:\dshHome\mc-shader-spotlight-dev-qa\.tools\jdk-17"
set "GRADLE_USER_HOME=E:\dshHome\mc-mod-spotlight-attachment\taclight\.gradle-user-home"
call "%~dp0gradlew.bat" %*
