# Send commands to the interop file relay (run-interop/taclight-cmds.txt).
# Discipline baked in: ONE command per write with >=900ms spacing (pit 72: same-tick
# batching = server spam kick) and UTF-8 WITHOUT BOM (red line 6: BOM breaks the relay).
# Usage:
#   powershell -File tools/interop-relay.ps1 -Commands @('/taclight scene grass','!shot')
#   empty-string element = pure delay slot (extra wait, no command)
param(
    [string[]]$Commands = @(),
    [int]$DelayMs = 1100,
    [string]$Relay = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if ($Relay -eq "") { $Relay = Join-Path $root "run-interop/taclight-cmds.txt" }
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
foreach ($c in $Commands) {
    Start-Sleep -Milliseconds $DelayMs
    if ($c -eq "") { continue }
    [System.IO.File]::WriteAllText($Relay, $c + "`n", $utf8NoBom)
    Write-Output ("SEND " + $c)
}
