# mp-session.ps1 — M5 多人旁观测试会话(LAN 拓扑:玩家A 主机 + 观察者B)
# 为什么不用 dev 专用服:oculus/embeddium 是纯客户端 mod 且在 runtimeOnly,
# dev runServer 必崩(dist);改用 A 开 LAN(/publish),B 观察者直连 —— 双端都是客户端。
# 用法:powershell -NoProfile -ExecutionPolicy Bypass -File tools\mp-session.ps1
# 约定(项目纪律):进程输出重定向到文件,禁止管道;JDK 走 gradlew-java17.ps1。
# 日志:tools/.session/{A,observer}.log;两个实例的日志/run 目录天然隔离。
param(
  [string]$World = 'test'      # A 进的调试存档(需 Cheats 开,publish 要权限)
)
$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent (Split-Path -Parent $PSCommandPath)   # taclight/
$Tools   = Join-Path $Project 'tools'
$SessionDir = Join-Path $Tools '.session'
$ALog   = Join-Path $SessionDir 'A.log'
$ALogE  = Join-Path $SessionDir 'A.log.err'
$OLog   = Join-Path $SessionDir 'observer.log'
$OLogE  = Join-Path $SessionDir 'observer.log.err'
$ALatest = Join-Path $Project 'run\logs\latest.log'
$OLatest = Join-Path $Project 'run-observer\logs\latest.log'
$RelayA  = Join-Path $Project 'run\taclight-cmds.txt'
$Gradle = Join-Path $Project 'gradlew-java17.cmd'

function Step([string]$m) { Write-Output ('[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m) }
function Drv([string]$a, [string]$t = '', [string]$o = '') {
  $argList = @('-NoProfile','-ExecutionPolicy','Bypass','-File', (Join-Path $Tools 'drive.ps1'), '-Action', $a)
  if ($t -ne '') { $argList += @('-Text', $t) }
  if ($o -ne '') { $argList += @('-OutFile', $o) }
  $out = & powershell @argList 2>&1
  return (($out -join ' | ').Trim())
}

# 0. 观察者目录预置(低配 options + config/oculus.properties 选包;幂等)
# 坑位:Oculus 读 config/oculus.properties;全新 run-observer 无该文件 = B 端静默无光影包。
# 输出重定向到文件(坑12:禁止管道);同步调用 + LASTEXITCODE 判定(PS5.1 Start-Process.ExitCode 为 null 会误判失败,08-31 踩坑)。
New-Item -ItemType Directory -Force -Path $SessionDir | Out-Null
$PrepLog = Join-Path $SessionDir 'mp-setup.log'
Step 'PREP taclightMpSetup (observer options/oculus config) ...'
$prepCommand = ('"{0}" -p "{1}" taclightMpSetup > "{2}" 2>&1' -f $Gradle, $Project, $PrepLog)
& cmd.exe /d /c $prepCommand
if ($LASTEXITCODE -ne 0) { throw ('taclightMpSetup failed: see ' + $PrepLog) }
Step 'PREP done'

# 0b. 陈旧游戏进程清场(2026-09-03 LAN 连接超时根因):残留 runClient 实例 =
# 旧 LAN 服占着过期端口 + B 连上去超时("无法连接至服务器 连接超时"),兼多开抢资源。
# 只杀本项目 runClient(含 Observer);排除并行任务实例(wt-interop/run-interop/
# Interop 用户名)——纪律:互不相干,drive.ps1 亦已排除其窗口。
# 安全阀:只杀启动超过 10 分钟的(用户手工刚启动的实例不受影响,此前 17:54 全杀
# 曾可能误伤用户手工测试,教训入坑位册)。
$staleCutoff = (Get-Date).AddMinutes(-10)
$stale = Get-CimInstance Win32_Process -Filter "name='java.exe' or name='javaw.exe'" | Where-Object {
  $_.CommandLine -match 'runClient' -and $_.CommandLine -notmatch 'wt-interop|run-interop|Interop' -and $_.CreationDate -lt $staleCutoff
}
foreach ($s in $stale) {
  Stop-Process -Id $s.ProcessId -Force -ErrorAction SilentlyContinue
  Step ('STALE-KILL pid=' + $s.ProcessId)
}
if ($stale) { Start-Sleep -Seconds 5 }

# 1. 玩家 A:复用 session.ps1(preflight/options/QuickPlay/READY 全套)
Step 'PLAYER-A launching via session.ps1 ...'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Tools 'session.ps1') -World $World
if ($LASTEXITCODE -ne 0) { throw ('PLAYER-A session failed with exit code ' + $LASTEXITCODE) }
Step 'PLAYER-A READY'

# 2. A 开 LAN(/publish;test 存档 Cheats 开)
# 坑29:PS5.1 `Set-Content -Encoding UTF8` 带 BOM,中继首行会变成 "?/publish" 被拒
# (17:36 实机踩坑)—— 中继文件必须无 BOM 写入,与 options.txt 无 BOM 纪律同族。
[System.IO.File]::WriteAllText($RelayA, "/publish`r`n")
Step 'RELAY /publish sent'

# 3. 解析端口(A 日志,最多 60s)
$port = $null
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline -and -not $port) {
  Start-Sleep -Seconds 3
  if (Test-Path $ALatest) {
    $hit = Select-String -Path $ALatest -Pattern 'Started serving on (\d{4,5})|[Pp]ort (\d{4,5})' | Select-Object -Last 1
    if ($hit) { $port = @($hit.Matches[0].Groups | Select-Object -Skip 1 | ForEach-Object { $_.Value } | Where-Object { $_ })[0] }
  }
}
if (-not $port) { throw 'LAN port not found in A log(确认 /publish 成功、Cheats 开启)' }
Step ('LAN port = ' + $port)

# 4. 观察者 B(独立 run-observer 目录;低配 options 已由 taclightMpSetup 预置)
New-Item -ItemType Directory -Force -Path $SessionDir | Out-Null
$g = @('-p', $Project,
       ('-PtaclightJoin=127.0.0.1:' + $port), '-PtaclightUser=ObserverB', 'runClientObserver')
$obs = Start-Process -FilePath $Gradle -ArgumentList $g -WorkingDirectory $Project `
        -RedirectStandardOutput $OLog -RedirectStandardError $OLogE -WindowStyle Hidden -PassThru
Step ('OBSERVER launching (pid=' + $obs.Id + ')')

# 5. 等 B 登录进服(最多 7 分钟)
$deadline = (Get-Date).AddMinutes(7)
$ok = $false
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 6
  if ((Test-Path $OLatest) -and (Select-String -Path $OLatest -Pattern 'LIGHT-SYNC-ACK' -Quiet)) { $ok = $true; break }
  if ($obs.HasExited) { throw 'OBSERVER exited early: 检查 tools/.session/observer.log' }
}
if (-not $ok) { throw 'OBSERVER READY timeout (7min): 检查 tools/.session/observer.log' }
Step 'OBSERVER READY (已进服)'

Step 'MP-SESSION(LAN) 就绪。下一步建议:'
Step '  A: run\taclight-cmds.txt     写 /taclight kit 然后开灯(L 键)'
Step '  B: run-observer\taclight-cmds.txt 写 /gamemode spectator 切旁观,飞到 A 侧面/背面'
Step '  B 端应能看到 A 的手电锥体;开关灯用 /taclight light <on|off|toggle>'
Step '  截图注意:两窗口标题相同,drive.ps1 shot 需先 pin 目标窗口或手动激活'
