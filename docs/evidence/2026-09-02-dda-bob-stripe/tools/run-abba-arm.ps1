param(
  [int]$ProcId,
  [string]$Label,
  [string]$Bob,
  [string]$Voxel
)
$ErrorActionPreference = 'Stop'
$repo = 'E:\dshHome\mc-mod-spotlight-attachment\taclight'
$relayA = Join-Path $repo 'run\taclight-cmds.txt'
$relayB = Join-Path $repo 'run-observer\taclight-cmds.txt'
$walk = Join-Path $repo 'tools\.session\postkey-walk.ps1'
$utf8 = [Text.UTF8Encoding]::new($false)

[IO.File]::WriteAllText($relayB, "!rec off`r`n!bob $Bob`r`n!voxel $Voxel`r`n", $utf8)
Start-Sleep -Seconds 2
[IO.File]::WriteAllText($relayA, "/tp ObserverB 2004.0 121.0 3.5 222 10`r`n", $utf8)
Start-Sleep -Seconds 3
[IO.File]::WriteAllText($relayB, "!rec on`r`n", $utf8)
Start-Sleep -Seconds 2
& powershell -NoProfile -ExecutionPolicy Bypass -File $walk -ProcId $ProcId -Seq 'W1200 P400 S1200 P1400'
Start-Sleep -Seconds 4

$root = Join-Path $repo 'run-observer\mcap'
$run = Get-ChildItem -LiteralPath $root -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$session = Get-ChildItem -LiteralPath $run.FullName -Directory | Sort-Object Name | Select-Object -Last 1
$footer = Get-Content -LiteralPath (Join-Path $session.FullName 'frames.csv') -Tail 1
if ($footer -notmatch '^# END ') { throw "session not closed: $($session.FullName)" }
Write-Output "ARM=$Label BOB=$Bob VOXEL=$Voxel SESSION=$($session.FullName) FOOTER=$footer"
