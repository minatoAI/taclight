# TacLight interop third-instance launcher (Plan C milestone 2, worktree wt-interop).
# Usage (from anywhere):
#   powershell -File tools/interop-session.ps1 [-Pack "iterationT 3.2.0"] [-Shaders on|off] [-User InteropA] [-World interop]
# - updates run-interop/config/oculus.properties (shaderPack / enableShaders) if asked,
# - then launches runClientInterop from worktree root (gradlew-interop.cmd lives there; wrong cwd = instant fail).
# Boot log: run-interop/boot.log (overwrite each launch; check latest.log for runtime evidence).
param(
    [string]$Pack = "",
    [ValidateSet("", "on", "off")][string]$Shaders = "",
    [string]$User = "InteropA",
    [string]$World = "interop"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Test-Path (Join-Path $root "gradlew-interop.cmd"))) {
    throw "gradlew-interop.cmd not found under $root (untracked wrapper, see pit 61)"
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$cfg = Join-Path $root "run-interop/config/oculus.properties"
if (($Pack -ne "" -or $Shaders -ne "") -and (Test-Path $cfg)) {
    $lines = [System.IO.File]::ReadAllLines($cfg)
    $out = foreach ($l in $lines) {
        if ($Pack -ne "" -and $l -like "shaderPack=*") { "shaderPack=$Pack" }
        elseif ($Shaders -ne "" -and $l -like "enableShaders=*") { "enableShaders=$Shaders" }
        else { $l }
    }
    [System.IO.File]::WriteAllLines($cfg, $out, $utf8NoBom)
    Write-Output ("CFG shaderPack Pack='$Pack' Shaders='$Shaders'")
}
Write-Output "LAUNCH runClientInterop User=$User World=$World"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
cmd /c "gradlew-interop.cmd runClientInterop -PtaclightUser=$User -PtaclightQuickPlay=$World --offline" *> (Join-Path $root "run-interop/boot-$stamp.log")
Write-Output "EXIT boot-$stamp.log"