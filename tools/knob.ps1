# knob.ps1 - TacLight 体感调参输入器:把你输入的旋钮命令写入实例中继文件(无 BOM)。
# 游戏内没有 ! 命令聊天入口(dev 命令树不完整),中继文件是唯一输入通道;本脚本
# 让你在游戏旁边开个终端就能自助调参,不用等 AI 代写。
#
# 用法(在 taclight/ 目录下):
#   交互模式(逐行输入,回车即发): powershell -NoProfile -ExecutionPolicy Bypass -File tools\knob.ps1
#   输入到观察者 B 端:             powershell ... -File tools\knob.ps1 -Side B
#   单发一条(脚本用):            powershell ... -File tools\knob.ps1 -Text "!bright 12"
#
# 旋钮速查(客户端本地,零重启生效,重启清零):
#   !bright <0.5..30>  绝对亮度      无参=status  off=回默认
#   !dist   <4..96>   绝对照距(格)
#   !atten  <0.2..20> 衰减系数 K(越大远处暗得越快)
#   !knee   <0.2..8>  近场软膝 G(越大近处压得越狠;off=关闭)
#   其他: !light 开关手电 / !gun 枪灯 / !diag 诊断 / !shot 截图
param(
  [string]$Side = 'A',
  [string]$Text = ''
)
$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$RunDir = if ($Side -eq 'B') { 'run-observer' } else { 'run' }
$File = Join-Path $Project (Join-Path $RunDir 'taclight-cmds.txt')
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
# 容错:裸旋钮名(忘了 ! 前缀)自动补 ! —— 09-05 实机教训:用户输 "knee 6" 被中继
# 判未知行静默忽略,表现为"调了没反应"。/ 开头(服务端命令)与已带 ! 的不碰。
$KnobWords = '^(bright|dist|atten|knee|light|gun|neon|selflight|lv|diag|bench|shot|reload|voxel|bob|psnap|bsnap|extrap|sweep|rec|mcap|looktrace|tpfb|tproe|back|lan)(\s|$)'

function Send([string]$line) {
  $line = $line.Trim()
  if ($line -notmatch '^(!|/)' -and $line -match $KnobWords) {
    $line = '!' + $line
  }
  # 整文件重写单行(中继读后即清空;写后歇 0.95s 对齐 2Hz 消费与"一条一写"纪律)
  [System.IO.File]::WriteAllText($File, $line + "`n", $Utf8NoBom)
  Write-Output ("sent({0}) -> {1}" -f $Side, $line)
  Start-Sleep -Milliseconds 950
}

if ($Text -ne '') { Send $Text; exit 0 }

Write-Output ("knob({0}) -> {1}" -f $Side, $File)
Write-Output "输入旋钮命令后回车发送(例: !bright 12),Ctrl+C 退出。"
Write-Output "  !bright <0.5..30>   亮度        !dist <4..96>    照距(格)"
Write-Output "  !atten  <0.2..20>   衰减K       !knee <0.2..8>   近场软膝(off=关)"
Write-Output "  无参=status  off=回默认   !light 手电开关   !shot 截图"
while ($true) {
  $line = Read-Host 'knob'
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  Send $line.Trim()
}
