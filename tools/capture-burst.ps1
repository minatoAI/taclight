#Requires -Version 5.1
# capture-burst.ps1 - 连续抓帧(2026-08-30 移动光源调试专用)。
# 进程内循环 CopyFromScreen,单进程只编译一次 Add-Type,实际 ~4-6 fps。
# 用法:powershell -File tools\capture-burst.ps1 -OutDir <dir> -Count 12 -IntervalMs 220 [-Tag run1]
# 输出:<OutDir>\<Tag>_###.png;末行打印 BURST n frames in T s(fps)。
param(
  [Parameter(Mandatory=$true)][string]$OutDir,
  [int]$Count = 12,
  [int]$IntervalMs = 220,
  [string]$Tag = "run"
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class T0Burst {
  public delegate bool EnumProc(IntPtr h, IntPtr lp);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lp);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder sb, int max);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public struct RECT { public int L, T, R, B; }
  public static IntPtr Find() {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, lp) => {
      if (!IsWindowVisible(h)) return true;
      var sb = new StringBuilder(256);
      GetWindowTextW(h, sb, 256);
      string t = sb.ToString();
      if ((t.Contains("Minecraft") || t.Contains("Forge")) && t.Length > 5) { found = h; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }
}
"@
[T0Burst]::SetProcessDPIAware() | Out-Null
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$h = [T0Burst]::Find()
if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; exit 1 }
$r = New-Object T0Burst+RECT
[T0Burst]::GetWindowRect($h, [ref]$r) | Out-Null
$w = $r.R - $r.L; $ht = $r.B - $r.T
$sw = [System.Diagnostics.Stopwatch]::StartNew()
for ($i = 0; $i -lt $Count; $i++) {
  $bmp = New-Object System.Drawing.Bitmap($w, $ht)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($r.L, $r.T, 0, 0, (New-Object System.Drawing.Size($w, $ht)))
  $g.Dispose()
  # BMP(PNG 编码 ~200ms/帧是 fps 瓶颈;BMP 免压缩 ~15ms/帧 → ~8-10fps)
  $bmp.Save((Join-Path $OutDir ('{0}_{1:d3}.bmp' -f $Tag, $i)), [System.Drawing.Imaging.ImageFormat]::MemoryBmp)
  $bmp.Dispose()
  Start-Sleep -Milliseconds $IntervalMs
}
$sw.Stop()
Write-Output ('BURST ' + $Count + ' frames in ' + [math]::Round($sw.Elapsed.TotalSeconds, 1) + 's (fps=' + [math]::Round($Count / $sw.Elapsed.TotalSeconds, 1) + ')')
