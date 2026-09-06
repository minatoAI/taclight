# knob.ps1 - TacLight 体感调参输入器:把你输入的旋钮命令写入实例中继文件(无 BOM)。
# 游戏内没有 ! 命令聊天入口(dev 命令树不完整),中继文件是唯一输入通道;本脚本
# 让你在游戏旁边开个终端就能自助调参,不用等 AI 代写。
#
# 用法(推荐:双击 tools\knob.bat 弹专用调参窗口,免去 PowerShell 启动/引号问题):
#   交互模式(逐行输入,回车即发): powershell -NoProfile -ExecutionPolicy Bypass -File tools\knob.ps1
#   输入到观察者 B 端:             powershell ... -File tools\knob.ps1 -Side B
#   单发一条(脚本用):            powershell ... -File tools\knob.ps1 -Text "!bright 12"
#                                  或 cmd 直发: tools\knob.bat knee 8(自动补 !)
# 注意:本脚本必须在它自己的 "knob:" 提示符下输入;在 PowerShell 命令提示符下输
# "!knee 8" 会被当成"要运行的程序"而报"无法识别"(09-05 用户实机踩坑)。
#
# 旋钮速查(客户端本地,零重启生效,重启清零):
#   !bright <0.5..30>  绝对亮度      无参=status  off=回默认
#   !dist   <4..96>   绝对照距(格)
#   !atten  <0.2..20> 衰减系数 K(越大远处暗得越快)
#   !knee   <0.2..8>  近场软膝 G(越大近处压得越狠;off=关闭)
#   !beam   <0..1>    体积光束密度(0=关光束)
#   !scat   <0..0.9>  轴向底亮份额(0=纯侧面丁达尔,正面最暗;off=回0.04)
#   !beamcap <0.25..8> 重叠软上限倍率(小=压眩光狠;off=回1)
#   !cone   <2..45>   外锥半角度数(小=接近平行光;off=回默认8)
#   !occl    on/off     遮挡距离表(默认开;off=回逐采样DDA,慢但逐格精确)
#   !tm      on/off     体积光时间复用(默认开;off=回64步全新鲜)
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
$KnobWords = '^(bright|beamonly|beam|beamcap|cone|occl|tm|scat|dist|atten|knee|light|gun|neon|selflight|lv|diag|bench|shot|reload|voxel|bob|psnap|bsnap|extrap|sweep|rec|mcap|looktrace|tpfb|tproe|back|lan)(\s|$)'

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
Write-Output "  !beam   <0..1>      体积光密度(0=关光束,off=回默认0.05)"
Write-Output "  !scat   <0..0.9>    轴向底亮(0=纯侧面丁达尔,正面最暗,off=回默认0.04)"
Write-Output "  !beamcap <0.25..8>  重叠软上限倍率(小=压眩光狠,off=回默认1)"
Write-Output "  !cone   <2..45>     外锥半角度数(小=接近平行光,off=回默认8)"
Write-Output "  !beamonly on/off    只看光束(关掉表面照明,单独看体积光形态)"
Write-Output "  !tm on/off          体积光时间复用(默认开;off=回64步全新鲜,A/B 对照)"
Write-Output "  无参=status  off=回默认   !light 手电开关   !shot 截图"
while ($true) {
  $line = Read-Host 'knob'
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  Send $line.Trim()
}
