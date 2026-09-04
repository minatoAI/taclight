# Interop injection smoke driver (acceptance 3-4 of the milestone-2 plan §7):
# scene build -> camera goto -> light off shot -> light on shot, all through the file
# relay (programmatic only, zero mouse/keyboard/window grabbing — user mandate 09-02).
# Screenshots come from relay !shot (vanilla Screenshot.grab) into run-interop/screenshots,
# then are copied to semantic names next to the pack evidence.
# Usage:
#   powershell -File tools/interop-smoke.ps1 -Scene grass -Cam grass_low -Tag it32 -User InteropA
#   optional -Extra @('/cmd','!knob') runs right after cam goto (e.g. !diag before shots)
param(
    [string]$Scene = "grass",
    [string]$Cam = "grass_low",
    [string]$Tag = "smoke",
    [string]$User = "InteropA",
    [string[]]$Extra = @(),
    [int]$SettleSec = 12
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$relay = Join-Path $root "run-interop/taclight-cmds.txt"
$shots = Join-Path $root "run-interop/screenshots"
if (-not (Test-Path $shots)) { New-Item -ItemType Directory -Path $shots | Out-Null }
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Send([string]$c) {
    [System.IO.File]::WriteAllText($relay, $c + "`n", $utf8NoBom)
    Write-Output ("SEND " + $c)
    Start-Sleep -Milliseconds 1100
}

function Shot([string]$Name) {
    $before = @(Get-ChildItem $shots -Filter *.png -ErrorAction SilentlyContinue)
    Send "!shot"
    Start-Sleep -Seconds 2
    $after = @(Get-ChildItem $shots -Filter *.png -ErrorAction SilentlyContinue)
    $new = $after | Where-Object { $before -notcontains $_ } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($new) {
        Copy-Item $new.FullName (Join-Path $shots $Name) -Force
        Write-Output ("SHOT " + $Name + " <- " + $new.Name)
    } else {
        Write-Output ("SHOT-FAIL " + $Name + " (no new png)")
    }
}

Write-Output "== smoke begin tag=$Tag scene=$Scene cam=$Cam =="
Send "/taclight scene $Scene"
foreach ($e in $Extra) { Send $e }
Send "/taclight cam goto $Cam"
Send "/taclight light off $User"
Write-Output ("SETTLE " + $SettleSec + "s (chat overlay fade ~10s before baseline shot)")
Start-Sleep -Seconds $SettleSec
Shot ($Tag + "_off.png")
Send "/taclight light on $User"
Start-Sleep -Seconds $SettleSec
Shot ($Tag + "_on.png")
Send "/taclight light off $User"
Write-Output "== smoke done: $shots\\${Tag}_off.png / ${Tag}_on.png =="
