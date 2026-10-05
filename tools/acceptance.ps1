#Requires -Version 5.1
# acceptance.ps1 · 照明边界判定(预期效果边界的可执行版,2026-08-29)
#   centroid  亮斑加权质心(Rec.709 luma 阈值,分析窗排除 UI 带)→ 单行 JSON
#   align     两图亮斑质心偏差 ≤ 容差(体积光↔表面光 / bloom↔光源 同轴判定)
#   side      亮斑质心水平位置 ∈ [中心+MinX, 中心+MaxX](右手性契约:手电在右手,
#             光斑必须落在准星右侧容差带内,整体偏左 = 手性 bug 复发)
# 原则:无亮区(frac < MinFrac)= FAIL —— 禁止空判定绿灯。
# 权威字符串 TACLIGHT-ACCEPT PASS/FAIL;已知答案自测见 selftest.ps1 §7。
# 用法:
#   powershell -File acceptance.ps1 centroid -A shot.png [-LumaTh 110]
#   powershell -File acceptance.ps1 align   -A final.png -B dbg4.png [-TolX 0.05 -TolY 0.08]
#                                             [-LumaThA x -LumaThB y](两腿各自阈值,默认回落 LumaTh;
#                                              W3 新配方:A=DBG8 表面光饱和核心 253,B=DBG4 光束 110)
#   powershell -File acceptance.ps1 side    -A final.png [-MinX -0.01 -MaxX 0.08]
param([Parameter(Mandatory=$true)][string]$Mode,
      [string]$A, [string]$B,
      [double]$LumaTh = 110,
      [double]$LumaThA = -1, [double]$LumaThB = -1,
      [double]$Top = 0.08, [double]$Bottom = 0.16,
      [double]$TolX = 0.05, [double]$TolY = 0.08,
      [double]$MinX = -0.01, [double]$MaxX = 0.08,
      [double]$MinFrac = 0.004)
# LumaThA/B(2026-08-30):align 两腿可各用阈值(默认回落 LumaTh)。动机:坑26 后
# W3 两腿分别量"表面光饱和核心"(DBG8 ×6 显示,LumaTh 253)与"光束整体"(DBG4,LumaTh 110),
# 单一阈值无法同时适配两个缓冲的值域。
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function Get-Centroid([string]$path, [double]$th = 0) {
  if ($th -le 0) { $th = $LumaTh }
  if (-not (Test-Path $path)) { throw ("image not found: " + $path) }
  $bmp = New-Object System.Drawing.Bitmap($path)
  $w = $bmp.Width; $h = $bmp.Height
  $rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
  $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $bytes = New-Object byte[] ($data.Stride * $h)
  [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
  $bmp.UnlockBits($data)
  $bmp.Dispose()
  $y0 = [int]($h * $Top); $y1 = [int]($h * (1.0 - $Bottom))
  $sw = 0.0; $sx = 0.0; $sy = 0.0
  for ($y = $y0; $y -lt $y1; $y++) {
    $row = $y * $data.Stride
    for ($x = 0; $x -lt $w; $x++) {
      $i = $row + $x * 4
      $luma = 0.2126 * $bytes[$i + 2] + 0.7152 * $bytes[$i + 1] + 0.0722 * $bytes[$i]
      if ($luma -gt $th) {
        $wgt = $luma - $th
        $sw += $wgt; $sx += $wgt * $x; $sy += $wgt * $y
      }
    }
  }
  if ($sw -le 0.0) {
    return @{ ok = $false; w = $w; h = $h; cx = 0.0; cy = 0.0; frac = 0.0 }
  }
  return @{ ok = $true; w = $w; h = $h;
            cx = ($sx / $sw) / $w; cy = ($sy / $sw) / $h; frac = -1.0 }
}

function Get-Frac([string]$path, [double]$th = 0) {
  if ($th -le 0) { $th = $LumaTh }
  # 亮像素占比(与质心同阈值;单独扫一遍,避免为 frac 保留全图 second pass 于主路径)
  $bmp = New-Object System.Drawing.Bitmap($path)
  $w = $bmp.Width; $h = $bmp.Height
  $rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
  $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $bytes = New-Object byte[] ($data.Stride * $h)
  [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
  $bmp.UnlockBits($data)
  $bmp.Dispose()
  $y0 = [int]($h * $Top); $y1 = [int]($h * (1.0 - $Bottom))
  $lit = 0; $total = 0
  for ($y = $y0; $y -lt $y1; $y++) {
    $row = $y * $data.Stride
    for ($x = 0; $x -lt $w; $x++) {
      $i = $row + $x * 4
      $luma = 0.2126 * $bytes[$i + 2] + 0.7152 * $bytes[$i + 1] + 0.0722 * $bytes[$i]
      $total++
      if ($luma -gt $th) { $lit++ }
    }
  }
  if ($total -le 0) { return 0.0 }
  return ($lit / $total)
}

function To-Json([hashtable]$o) {
  $parts = @()
  foreach ($k in $o.Keys) {
    $v = $o[$k]
    if ($v -is [double]) { $s = ('{0:0.0000}' -f $v) }
    elseif ($v -is [int] -or $v -is [long]) { $s = ('{0}' -f $v) }
    else { $s = ('"' + (($v -replace '\\', '\\\\') -replace '"', '\"') + '"') }
    $parts += ('"' + $k + '":' + $s)
  }
  return '{' + ($parts -join ',') + '}'
}

if ($Mode -eq 'centroid') {
  $c = Get-Centroid $A
  $c.frac = Get-Frac $A
  $out = @{ check = 'centroid'; image = $A; w = $c.w; h = $c.h;
            cx = [double]$c.cx; cy = [double]$c.cy; frac = [double]$c.frac }
  Write-Output (To-Json $out)
  if (-not $c.ok -or $c.frac -lt $MinFrac) { exit 1 } else { exit 0 }
}
elseif ($Mode -eq 'align') {
  if ($LumaThA -le 0) { $LumaThA = $LumaTh }
  if ($LumaThB -le 0) { $LumaThB = $LumaTh }
  $ca = Get-Centroid $A $LumaThA; $fa = Get-Frac $A $LumaThA
  $cb = Get-Centroid $B $LumaThB; $fb = Get-Frac $B $LumaThB
  $dx = $ca.cx - $cb.cx; $dy = $ca.cy - $cb.cy
  $pass = $ca.ok -and $cb.ok -and $fa -ge $MinFrac -and $fb -ge $MinFrac `
          -and ([math]::Abs($dx) -le $TolX) -and ([math]::Abs($dy) -le $TolY)
  $verdict = 'FAIL'; if ($pass) { $verdict = 'PASS' }
  Write-Output ('TACLIGHT-ACCEPT ' + $verdict)
  $out = @{ check = 'align'; a = $A; b = $B; ax = [double]$ca.cx; ay = [double]$ca.cy;
            bx = [double]$cb.cx; by = [double]$cb.cy; dx = [double]$dx; dy = [double]$dy;
            fracA = [double]$fa; fracB = [double]$fb; tolX = [double]$TolX; tolY = [double]$TolY;
            thA = [double]$LumaThA; thB = [double]$LumaThB; verdict = $verdict }
  Write-Output (To-Json $out)
  if ($pass) { exit 0 } else { exit 1 }
}
elseif ($Mode -eq 'side') {
  $c = Get-Centroid $A; $f = Get-Frac $A
  $lo = 0.5 + $MinX; $hi = 0.5 + $MaxX
  $pass = $c.ok -and $f -ge $MinFrac -and ($c.cx -ge $lo) -and ($c.cx -le $hi)
  $verdict = 'FAIL'; if ($pass) { $verdict = 'PASS' }
  Write-Output ('TACLIGHT-ACCEPT ' + $verdict)
  $out = @{ check = 'side'; image = $A; cx = [double]$c.cx; cy = [double]$c.cy;
            frac = [double]$f; band = ('[' + $lo + ',' + $hi + ']'); verdict = $verdict }
  Write-Output (To-Json $out)
  if ($pass) { exit 0 } else { exit 1 }
}
else {
  Write-Output ('UNKNOWN MODE: ' + $Mode)
  exit 2
}
