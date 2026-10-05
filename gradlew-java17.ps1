$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
# JDK 17 优先:复用旧项目工具链(可用环境变量 TACLIGHT_JDK17 覆盖)
$candidates = @(
    $env:TACLIGHT_JDK17,
    'E:\dshHome\mc-shader-spotlight-dev-qa\.tools\jdk-17',
    $env:JAVA_HOME
) | Where-Object { $_ }
$javaHome = $null
foreach ($c in $candidates) {
    if ($c -and (Test-Path (Join-Path $c 'bin\java.exe'))) { $javaHome = $c; break }
}
if (-not $javaHome) { throw 'JDK 17 not found. Set TACLIGHT_JDK17 or JAVA_HOME.' }
$env:JAVA_HOME = $javaHome
$env:GRADLE_USER_HOME = Join-Path $project '.gradle-user-home'
$env:PATH = (Join-Path $javaHome 'bin') + ';' + $env:PATH
& (Join-Path $project 'gradlew.bat') @args
exit $LASTEXITCODE
