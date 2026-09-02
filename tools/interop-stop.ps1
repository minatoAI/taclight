# Stop ONLY the interop client java process(es).
# Match rule: command line contains 'taclight.mixins' (client args; gradle daemons lack it)
# AND 'wt-interop' (worktree classpath; main-line instances live under taclight\build instead).
# NOTE: do NOT match 'run-interop' — the game dir is passed as a RELATIVE path ('.'), so the
# absolute run-interop path never appears in the command line (2026-09-03 pit, first kill no-op).
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'taclight\.mixins' -and $_.CommandLine -match 'wt-interop' }
if (-not $procs) { Write-Output "no interop client process"; exit 0 }
foreach ($p in $procs) {
    Write-Output ("KILL " + $p.ProcessId)
    Stop-Process -Id $p.ProcessId -Force
}
