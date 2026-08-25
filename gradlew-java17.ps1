$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaHome = Join-Path (Split-Path -Parent $project) '.tools\jdk-17'
if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    throw "Project JDK 17 is missing: $javaHome"
}
$env:JAVA_HOME = $javaHome
$env:GRADLE_USER_HOME = Join-Path $project '.gradle-user-home'
$env:PATH = (Join-Path $javaHome 'bin') + ';' + $env:PATH
& (Join-Path $project 'gradlew.bat') @args
exit $LASTEXITCODE
