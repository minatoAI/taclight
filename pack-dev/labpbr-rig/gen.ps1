# gen.ps1 — 生成 TacLight LabPBR 测试台的 _s 贴图(仅 _s,不改原版贴图;开发用,不入发布包)
# LabPBR 1.3: R = perceptual smoothness, G = F0(0-229 线性;230-255 金属), A = 255(ignored,防预乘)
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File gen.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$blockDir = Join-Path $PSScriptRoot 'assets\minecraft\textures\block'
New-Item -ItemType Directory -Force -Path $blockDir | Out-Null

function Save-Spec([string]$name, [System.Drawing.Bitmap]$bmp) {
  $path = Join-Path $blockDir $name
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Output ('WROTE ' + $path)
}
function Ch([float]$v) { return [int][math]::Max(0, [math]::Min(255, [math]::Round($v))) }

# 确定性伪抖动(每 texel 稳定,连拍帧差为零)
function Jit([int]$x, [int]$y, [int]$amp) {
  $h = ($x * 73856093 -bxor $y * 19349663) -band 0xFFFF
  return (($h % (2 * $amp + 1)) - $amp)
}

# ---- stone_bricks:砖面半抛光介电(R≈200,G=30),砖缝粗糙(R≈40,G=12)----
$bmp = New-Object System.Drawing.Bitmap(16, 16)
for ($y = 0; $y -lt 16; $y++) {
  for ($x = 0; $x -lt 16; $x++) {
    $mortar = ($y % 4 -eq 3)
    if (-not $mortar) {
      $row = [math]::Floor($y / 4)
      $mortar = if ($row % 2 -eq 0) { ($x % 8 -eq 7) } else { ($x % 8 -eq 3) }
    }
    if ($mortar) { $r = 40 + (Jit $x $y 8); $g = 12 }
    else         { $r = 200 + (Jit $x $y 12); $g = 30 }
    $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, (Ch $r), (Ch $g), 0))
  }
}
Save-Spec 'stone_bricks_s.png' $bmp

# ---- smooth_stone:均匀半抛光(R=170 → roughness≈0.11 夹到 0.20 护栏,G=20)----
$bmp = New-Object System.Drawing.Bitmap(16, 16)
for ($y = 0; $y -lt 16; $y++) {
  for ($x = 0; $x -lt 16; $x++) {
    $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, (Ch (170 + (Jit $x $y 6))), 20, 0))
  }
}
Save-Spec 'smooth_stone_s.png' $bmp

# ---- iron_block:金属(G=230 = LabPBR 预定义 Iron;本栈简化为 F0=albedo)----
$bmp = New-Object System.Drawing.Bitmap(16, 16)
for ($y = 0; $y -lt 16; $y++) {
  for ($x = 0; $x -lt 16; $x++) {
    $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, (Ch (205 + (Jit $x $y 10))), 230, 0))
  }
}
Save-Spec 'iron_block_s.png' $bmp

Write-Output 'DONE'
