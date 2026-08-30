# session.ps1 — SOP-0 会话骨架(移植自旧项目 t0-run-state 编排模式)
# 流程:preflight(无游戏窗口)→ 归档旧 latest.log → options.txt 强制测量口径
#      → 后台启动 runClient(-PtaclightQuickPlay=<World> 自动进存档)→ 就绪轮询 → 置顶聚焦 → READY
# 用法:powershell -NoProfile -ExecutionPolicy Bypass -File tools\session.ps1 [-World test] [-NoLaunch]
# 约束(旧项目血泪,勿改):只发单击键;窗口必须前台;maxFps:0=VSync 陷阱。
param(
  [string]$World = 'test',
  [switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent (Split-Path -Parent $PSCommandPath)   # taclight/
$RunDir  = Join-Path $Project 'run'
$Tools   = Join-Path (Split-Path -Parent $PSCommandPath) ''
$Drive   = Join-Path $Tools 'drive.ps1'
$Log     = Join-Path $RunDir 'logs\latest.log'
$OptTxt  = Join-Path $RunDir 'options.txt'
$SessionDir = Join-Path $Tools '.session'

function Step([string]$m) { Write-Output ('[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m) }
function Drv([string]$a, [string]$t = '', [string]$o = '') {
  # 空字符串参数经命令行传 -File 会被丢弃/错绑(实测坑),只在非空时追加
  $argList = @('-NoProfile','-ExecutionPolicy','Bypass','-File', $Drive, '-Action', $a)
  if ($t -ne '') { $argList += @('-Text', $t) }
  if ($o -ne '') { $argList += @('-OutFile', $o) }
  $out = & powershell @argList 2>&1
  $line = ($out -join ' | ').Trim()
  Write-Output ('[DRV {0}] {1}' -f $a, $line)
  return $line
}

# 0. preflight:无游戏窗口(有 = 用户在玩,中止)
$w = Drv 'find'
if ($w -notmatch 'WINDOW_NOT_FOUND') { throw ('PREFLIGHT: 已有游戏窗口(' + $w + '),中止以免干扰用户。') }
Step 'PREFLIGHT ok (no game window)'

# 0b. 归档旧日志,使本次 latest.log 唯一化
if (Test-Path $Log) {
  $pre = $Log + '.pre' + (Get-Date -Format 'HHmmss')
  Move-Item -Path $Log -Destination $pre -Force
  Step ('OLDLOG -> ' + $pre)
}

# 1. options.txt 强制测量口径:maxFps=260 + 关 VSync(1.20.1 中 maxFps:0=VSync 锁刷新率)
#    + 关失焦自动暂停(2026-08-29 实测坑:自动化终端抢焦点 → Game Menu 挡镜头)
#    + version 标记必须存在且**行首无 BOM**(坑24,2026-08-30):PS5.1 `Set-Content
#      -Encoding UTF8` 写 BOM → 首行 version:3465 被吃 → MC 当史前格式跑全量
#      datafix → OptionsKeyLwjgl3Fix 抛 NumberFormatException → 整个 options 丢弃
#      全默认,maxFps/vsync/pauseOnLostFocus/resourcePacks 全部从未生效。
#      写入必须用 .NET UTF8Encoding($false) 无 BOM。1.20.1 数据版本 = 3465。
if (Test-Path $OptTxt) {
  $optc = Get-Content $OptTxt -Raw
  if ($optc -notmatch 'version:3465') { $optc = "version:3465`n" + $optc }
  $optc = $optc -replace 'maxFps:[0-9]+', 'maxFps:260'
  if ($optc -match 'enableVsync:[a-z]+') { $optc = $optc -replace 'enableVsync:[a-z]+', 'enableVsync:false' }
  else { $optc = $optc + "`nenableVsync:false" }
  if ($optc -match 'pauseOnLostFocus:[a-z]+') { $optc = $optc -replace 'pauseOnLostFocus:[a-z]+', 'pauseOnLostFocus:false' }
  else { $optc = $optc + "`npauseOnLostFocus:false" }
  [IO.File]::WriteAllText($OptTxt, $optc, [Text.UTF8Encoding]::new($false))
  Step 'OPTIONS version=3465(noBOM) maxFps=260 vsync=false pauseOnLostFocus=false'
}

if ($NoLaunch) { Step 'NOLAUNCH: 只做 preflight/pref,跳过启动'; exit 0 }

# 2. 后台启动 runClient(QuickPlay 自动进存档;控制台重定向供排障)
New-Item -ItemType Directory -Force -Path $SessionDir | Out-Null
$console = Join-Path $SessionDir 'console.log'
$gArgs = @('-NoProfile','-ExecutionPolicy','Bypass','-File', (Join-Path $Project 'gradlew-java17.ps1'), ('-PtaclightQuickPlay=' + $World), 'runClient', '--offline')
Start-Process -FilePath 'powershell' -ArgumentList $gArgs -WorkingDirectory $Project -RedirectStandardOutput $console -RedirectStandardError ($console + '.err') -WindowStyle Hidden
Step ('LAUNCH started (quickPlay=' + $World + ')')

# 3. 就绪等待:游戏窗口存在 + 日志登录标记(最多 7 分钟)
$deadline = (Get-Date).AddMinutes(7)
$ready = $false
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 6
  $w = Drv 'find'
  if ($w -match 'WINDOW_NOT_FOUND') { continue }
  if ((Test-Path $Log) -and (Select-String -Path $Log -Pattern 'logged in with entity id' -Quiet)) { $ready = $true; break }
}
if (-not $ready) { throw 'READY timeout (7min): 检查 tools/.session/console.log' }

# 4. 置顶 + 标题栏点击聚焦(不触碰游戏内容)
Drv 'pin' | Out-Null
Drv 'click' '650,18' | Out-Null
Start-Sleep -Milliseconds 800
Step 'READY 游戏已进世界并聚焦'
