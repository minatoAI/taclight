#Requires -Version 5.1
# tools 自检(已知答案测试):griddiff / clusterprobe / stylemetrics + imgdiff 单元测试。
# 原则:合成图 + 精确已知差异,断言工具输出与手算一致 —— 权威字符串 TOOLS-SELFTEST PASS/FAIL。
# 用法:powershell -File selftest.ps1 [-WorkDir 临时目录](gradle 任务 taclightToolsSelftest 调用)
param([string]$WorkDir = "$env:TEMP\taclight-selftest")
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$script:fail = 0

function Assert([bool]$cond, [string]$what) {
  if ($cond) { Write-Output ("  ok  " + $what) }
  else { Write-Output ("  FAIL " + $what); $script:fail++ }
}

function New-SolidPng([string]$path, [int]$w, [int]$h, [int]$r, [int]$g, [int]$b) {
  $bmp = New-Object System.Drawing.Bitmap($w, $h)
  $gfx = [System.Drawing.Graphics]::FromImage($bmp)
  $gfx.Clear([System.Drawing.Color]::FromArgb(255, $r, $g, $b))
  $gfx.Dispose()
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
}

function New-ScenePng([string]$path, [int]$w, [int]$h, [int]$r, [int]$g, [int]$b,
                      [int]$rx, [int]$ry, [int]$rw, [int]$rh, [int]$ex, [int]$ey, [int]$er) {
  # 灰底 + 红矩形(rx,ry,rw,rh) + 绿圆(圆心 ex,ey 半径 er)
  $bmp = New-Object System.Drawing.Bitmap($w, $h)
  $gfx = [System.Drawing.Graphics]::FromImage($bmp)
  $gfx.Clear([System.Drawing.Color]::FromArgb(255, $r, $g, $b))
  $redBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 255, 0, 0))
  $greenBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 0, 255, 0))
  $gfx.FillRectangle($redBrush, $rx, $ry, $rw, $rh)
  $gfx.FillEllipse($greenBrush, ($ex - $er), ($ey - $er), (2 * $er), (2 * $er))
  $gfx.Dispose(); $redBrush.Dispose(); $greenBrush.Dispose()
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
}

if (Test-Path $WorkDir) { Remove-Item -Recurse -Force $WorkDir }
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $here
try {
  Write-Output '== [1] imgdiff.test.js (node 单元) =='
  $nodeOut = & node imgdiff.test.js 2>&1
  Assert ($LASTEXITCODE -eq 0) ("node imgdiff.test.js 退出码 0: " + ($nodeOut | Select-Object -Last 1))

  Write-Output '== [2] 合成测试图 =='
  $pngA = Join-Path $WorkDir 'A.png'
  $pngB = Join-Path $WorkDir 'B.png'
  $pngWhite = Join-Path $WorkDir 'white.png'
  $pngRed = Join-Path $WorkDir 'red255.png'
  $pngGray = Join-Path $WorkDir 'gray128.png'
  $pngDark = Join-Path $WorkDir 'dark20.png'
  New-SolidPng $pngA 256 192 64 64 64
  New-ScenePng $pngB 256 192 64 64 64 40 24 50 48 180 120 30
  New-SolidPng $pngWhite 64 64 255 255 255
  New-SolidPng $pngRed 64 64 255 0 0
  New-SolidPng $pngGray 64 64 128 128 128
  New-SolidPng $pngDark 64 64 20 20 20
  Assert (Test-Path $pngB) '合成图落盘'

  Write-Output '== [3] imgdiff CLI(A vs B) =='
  $heat = Join-Path $WorkDir 'heat.png'
  $out = & node imgdiff.js $pngA $pngB -o $heat --threshold 8 --json 2>&1
  $d = ($out | Select-Object -Last 1) | ConvertFrom-Json
  Assert ($d.changed -ge 5200) ("imgdiff changed>=5200(矩形2400+圆2827), 实得 " + $d.changed)
  Assert ($d.bbox[0] -le 40 -and $d.bbox[1] -le 24 -and $d.bbox[2] -ge 209 -and $d.bbox[3] -ge 149) 'imgdiff bbox 覆盖矩形+圆'
  Assert (Test-Path $heat) '热图落盘'

  Write-Output '== [4] griddiff(8x8 网格) =='
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File griddiff.ps1 -A $pngA -B $pngB -Cells 8 -Threshold 10 -Json 2>&1
  $g = ($out | Select-Object -Last 1) | ConvertFrom-Json
  $hitRect = $false; $hitEllipse = $false
  foreach ($c in $g.flagged) {
    if ($c.c -eq 2 -and $c.r -eq 2) { $hitRect = $true }    # 红矩形中心 (65,48)
    if ($c.c -eq 5 -and $c.r -eq 5) { $hitEllipse = $true } # 绿圆中心 (180,120)
  }
  Assert $hitRect 'griddiff 标记红矩形所在格(c2,r2)'
  Assert $hitEllipse 'griddiff 标记绿圆所在格(c5,r5)'
  Assert ($g.flagged.Count -ge 2) ("griddiff 标记格>=2, 实得 " + $g.flagged.Count)

  Write-Output '== [5] clusterprobe(色块簇) =='
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File clusterprobe.ps1 -Image $pngB -Color green -MinArea 100 -Json 2>&1
  $cp = ($out | Select-Object -Last 1) | ConvertFrom-Json
  Assert ($cp.count -eq 1) ("clusterprobe green 簇数=1, 实得 " + $cp.count)
  $c0 = $cp.clusters[0]
  Assert ([math]::Abs($c0.cx - 180) -le 3 -and [math]::Abs($c0.cy - 120) -le 3) ("绿簇圆心(180,120)±3, 实得 (" + $c0.cx + "," + $c0.cy + ")")
  $areaOk = [math]::Abs($c0.area - 2827) -le 500   # πr^2=2827,抗锯齿容差
  Assert $areaOk ("绿簇面积≈2827±500, 实得 " + $c0.area)
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File clusterprobe.ps1 -Image $pngB -Color red -MinArea 100 -Json 2>&1
  $cp = ($out | Select-Object -Last 1) | ConvertFrom-Json
  Assert ($cp.count -eq 1) ("clusterprobe red 簇数=1, 实得 " + $cp.count)
  $c0 = $cp.clusters[0]
  Assert ([math]::Abs($c0.cx - 65) -le 3 -and [math]::Abs($c0.cy - 48) -le 3) ("红簇中心(65,48)±3, 实得 (" + $c0.cx + "," + $c0.cy + ")")
  Assert ([math]::Abs($c0.area - 2400) -le 300) ("红簇面积 2400±300, 实得 " + $c0.area)

  Write-Output '== [6] stylemetrics(已知答案) =='
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File stylemetrics.ps1 -Images $pngWhite -Json 2>&1
  $m = (($out | Select-Object -Last 1) | ConvertFrom-Json)[0]
  Assert ([math]::Abs($m.SAT) -lt 1e-6 -and [math]::Abs($m.SHADOW) -lt 1e-6 -and [math]::Abs($m.CONTRAST) -lt 1e-6 -and [math]::Abs($m.TEMPAXIS) -lt 1e-6) '纯白: SAT/SHADOW/CONTRAST/TEMPAXIS 全 0'
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File stylemetrics.ps1 -Images $pngRed -Json 2>&1
  $m = (($out | Select-Object -Last 1) | ConvertFrom-Json)[0]
  Assert ([math]::Abs($m.SAT - 1.0) -lt 1e-6) '纯红: SAT=1'
  Assert ([math]::Abs($m.SHADOW - 1.0) -lt 1e-6) '纯红: SHADOW=1(Rec.709 luma=0.2126<0.25)'
  Assert ([math]::Abs($m.TEMPAXIS + 4.7037) -lt 0.01) ("纯红: TEMPAXIS=-4.7037, 实得 " + $m.TEMPAXIS)
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File stylemetrics.ps1 -Images "$pngGray,$pngDark" -Json 2>&1
  $ms = ($out | Select-Object -Last 1) | ConvertFrom-Json
  Assert ($ms.Count -eq 2) '多图输入输出 2 条'
  Assert ([math]::Abs($ms[0].SHADOW) -lt 1e-6) '灰128: SHADOW=0(0.502>=0.25)'
  Assert ([math]::Abs($ms[1].SHADOW - 1.0) -lt 1e-6) '暗20: SHADOW=1(0.078<0.25)'

  Write-Output '== [7] acceptance(亮斑质心/对准/右手侧,已知答案) =='
  function New-PatchPng([string]$path, [int]$w, [int]$h, [int]$rx, [int]$ry, [int]$rw, [int]$rh) {
    # 暗底 + 白矩形(模拟光斑)
    $bmp = New-Object System.Drawing.Bitmap($w, $h)
    $gfx = [System.Drawing.Graphics]::FromImage($bmp)
    $gfx.Clear([System.Drawing.Color]::FromArgb(255, 20, 20, 20))
    $brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 255, 255, 255))
    $gfx.FillRectangle($brush, $rx, $ry, $rw, $rh)
    $gfx.Dispose(); $brush.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
  }
  $accOk = Join-Path $WorkDir 'acc_ok.png'      # 光斑中心 (140,95) → cx=0.547 cy=0.495
  $accRight = Join-Path $WorkDir 'acc_right.png' # 同位置(自比对基准)
  $accShift = Join-Path $WorkDir 'acc_shift.png' # 光斑右移 40px → cx≈0.703
  $accLeft = Join-Path $WorkDir 'acc_left.png'   # 光斑中心 x=70 → cx≈0.273(左手侧)
  New-PatchPng $accOk 256 192 120 75 40 40
  New-PatchPng $accRight 256 192 120 75 40 40
  New-PatchPng $accShift 256 192 160 75 40 40
  New-PatchPng $accLeft 256 192 50 75 40 40
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File acceptance.ps1 centroid -A $accOk 2>&1
  $c = ($out | Select-Object -Last 1) | ConvertFrom-Json
  Assert ([math]::Abs($c.cx - 0.547) -le 0.01 -and [math]::Abs($c.cy - 0.495) -le 0.01) ("acceptance centroid 质心(0.547,0.495)±0.01, 实得 (" + $c.cx + "," + $c.cy + ")")
  Assert ([math]::Abs($c.frac - (1600.0/(256.0*192.0*0.76))) -lt 0.01) ("acceptance centroid frac≈0.034(分析窗内), 实得 " + $c.frac)
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File acceptance.ps1 align -A $accOk -B $accRight -TolX 0.05 -TolY 0.08 2>&1
  Assert ($LASTEXITCODE -eq 0 -and ($out | Select-Object -First 1) -eq 'TACLIGHT-ACCEPT PASS') 'acceptance align 同图自比 = PASS'
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File acceptance.ps1 align -A $accOk -B $accShift -TolX 0.05 -TolY 0.08 2>&1
  Assert ($LASTEXITCODE -eq 1 -and ($out | Select-Object -First 1) -eq 'TACLIGHT-ACCEPT FAIL') 'acceptance align 右移40px(0.156>0.05) = FAIL'
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File acceptance.ps1 side -A $accOk 2>&1
  Assert ($LASTEXITCODE -eq 0) 'acceptance side 右手带内(0.547∈[0.49,0.58]) = PASS'
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File acceptance.ps1 side -A $accLeft 2>&1
  Assert ($LASTEXITCODE -eq 1) 'acceptance side 左手侧(0.273) = FAIL(手性契约)'
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File acceptance.ps1 side -A $pngDark 2>&1
  Assert ($LASTEXITCODE -eq 1) 'acceptance side 无亮区 = FAIL(防空判绿灯护栏)'
}
finally {
  Pop-Location
}

if ($script:fail -eq 0) {
  Write-Output 'TOOLS-SELFTEST PASS'
  exit 0
} else {
  Write-Output ("TOOLS-SELFTEST FAIL(" + $script:fail + ")")
  exit 1
}
