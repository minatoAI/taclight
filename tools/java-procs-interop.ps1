# 列出含 taclight.mixins 的 java 进程: PID + 完整命令行尾部(判 worktree 归属)
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'"
foreach ($p in $procs) {
    $c = $p.CommandLine
    if ($c -match 'taclight\.mixins') {
        $tag = 'OTHER'
        if ($c -match 'wt-interop') { $tag = 'INTEROP' }
        elseif ($c -match 'mc-mod-spotlight-attachment\\taclight\\') { $tag = 'MAINLINE' }
        $tail = $c
        if ($tail.Length -gt 500) { $tail = $tail.Substring($tail.Length - 500) }
        $tail = $tail -replace '\s+', ' '
        Write-Output ("PID=" + $p.ProcessId + " TAG=" + $tag + " TAIL=" + $tail)
    }
}
Write-Output "DONE"
