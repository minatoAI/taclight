# drive.ps1 — 程序化调试驱动(移植自 mc-tarkov-like-shader-dev/qa/run-dev/t0-automation/t0-drive.ps1,本机已验证;红线:只发单击键,组合键注入不可行)
#
# ⚠ 后台纪律(2026-09-02 用户明令,坑72):自动化只允许用不碰前台的通道 ——
#   postkey / holdkey / postf3r / postchars / postchat / shot / find(纯 PostMessage 或屏幕拷贝)。
#   present / press / chat / f3r / rawkey / rawkey2 / quit 会动真实鼠标并 SetForegroundWindow
#   抢焦点 —— 仅限操作者明确要求"把窗口提到前台"时手工使用,自动化禁用。
param(
  [Parameter(Mandatory=$true)][string]$Action,
  [string]$Text = "",
  [string]$OutFile = "",
  [int]$Dur = 1000,
  [int]$ProcId = 0
)
# -ProcId(2026-08-30,M5 双客户端):按进程 PID 选窗(双实例窗口标题同为 ^Minecraft,
# 标题匹配不确定选中谁)。PID 取法:java 命令行含 --quickPlaySingleplayer=A / ObserverB=B。
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class DPI { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }
"@
[DPI]::SetProcessDPIAware() | Out-Null
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class T0Win {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lp);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder sb, int max);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public extern static void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern bool PostMessageW(IntPtr h, uint msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageW(IntPtr h, uint msg, IntPtr wp, IntPtr lp);
  public delegate bool EnumProc(IntPtr h, IntPtr lp);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L,T,R,B; }
}
"@
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class T0Send {
  [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public UIntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public UIntPtr dwExtraInfo; }
  [StructLayout(LayoutKind.Sequential)] public struct HARDWAREINPUT { public uint uMsg; public ushort wParamL; public ushort wParamH; }
  [StructLayout(LayoutKind.Explicit)] public struct INPUTUNION {
    [FieldOffset(0)] public MOUSEINPUT mi;
    [FieldOffset(0)] public KEYBDINPUT ki;
    [FieldOffset(0)] public HARDWAREINPUT hi;
  }
  [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public INPUTUNION u; }
  [DllImport("user32.dll", SetLastError = true)] public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
  public static uint SendSeq(int[][] seq) {
    INPUT[] inp = new INPUT[seq.Length];
    for (int i = 0; i < seq.Length; i++) {
      inp[i].type = 1;
      inp[i].u.ki.wVk = (ushort)seq[i][0];
      inp[i].u.ki.wScan = (ushort)seq[i][1];
      inp[i].u.ki.dwFlags = (uint)seq[i][2];
    }
    return SendInput((uint)seq.Length, inp, Marshal.SizeOf(typeof(INPUT)));
  }
  public static uint SendKey(int vk, int scan, int flagsDown) {
    return SendSeq(new int[][] { new int[] { vk, scan, flagsDown }, new int[] { vk, scan, flagsDown | 2 } });
  }
}
"@

function Find-McWindow() {
  $script:mc = [IntPtr]::Zero
  $script:gameMats = @('^Minecraft')
  $cb = [T0Win+EnumProc]{ param($h,$lp)
    if ([T0Win]::IsWindowVisible($h)) {
      if ($ProcId -gt 0) {
        $wpid = [uint32]0
        [T0Win]::GetWindowThreadProcessId($h, [ref]$wpid) | Out-Null
        if ($wpid -eq [uint32]$ProcId) { $script:mc = $h }
      } else {
        $sb = New-Object System.Text.StringBuilder 256
        [T0Win]::GetWindowTextW($h, $sb, 256) | Out-Null
        $t = $sb.ToString()
        $ok = $false
        foreach ($m in $script:gameMats) { if ($t -match $m) { $ok = $true; break } }
        if ($ok) { $script:mc = $h }
      }
    }
    return $true
  }
  [T0Win]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null
  return $script:mc
}

function Present-Mc() {
  $h = Find-McWindow
  if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; return $null }
  [T0Win]::ShowWindow($h, 9) | Out-Null
  # 坑30(08-30,M5 双客户端):前台锁下 SetForegroundWindow 对非前台进程会被静默拒绝
  # (B 窗口提不上来,截图/按键全落空)—— 先发一次 ALT down/up 解锁前台限制(经典手法),
  # 再 SetForegroundWindow;调用后用返回的 fg 与目标句柄比对验证,勿默认成功。
  [T0Win]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 60
  [T0Win]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
  [T0Win]::SetForegroundWindow($h) | Out-Null
  Start-Sleep -Milliseconds 500
  return $h
}

function Send-Key([string]$keys) {
  try { [System.Windows.Forms.SendKeys]::SendWait($keys) } catch { Write-Output ('SENDKEYS_ERR:' + $_.Exception.Message) }
}

function Send-Chars([string]$text) {
  $safe = $text.Replace('+','{+}').Replace('^','{^}').Replace('%','{%}').Replace('~','{~}').Replace('(','{(}').Replace(')','{)}')
  Send-Key $safe
}

function Send-RawKey([string]$key, [bool]$scanMode) {
  $map = @{ 'F1'=@(0x70,0x3B);'F2'=@(0x71,0x3C);'F3'=@(0x72,0x3D);'F4'=@(0x73,0x3E);'F5'=@(0x74,0x3F);'R'=@(0x52,0x13);'T'=@(0x54,0x14);'W'=@(0x57,0x11);'A'=@(0x41,0x1E);'S'=@(0x53,0x1F);'D'=@(0x44,0x20);'ENTER'=@(0x0D,0x1C);'ESC'=@(0x1B,0x01);'BACK'=@(0x08,0x0E) }
  if (-not $map.ContainsKey($key)) { Write-Output 'UNSUPPORTED'; return }
  $flags = if ($scanMode) { 0x8 } else { 0x0 }
  $sent = [T0Send]::SendKey([int]$map[$key][0], [int]$map[$key][1], $flags)
  Write-Output ('RAWKEY ' + $key + ' sent=' + $sent + ' mode=' + $(if ($scanMode) { 'scan' } else { 'vk' }))
}

switch ($Action) {
  'present' {
    $h = Present-Mc
    if ($h -ne $null) { Write-Output ('OK ' + $h + ' fg=' + [T0Win]::GetForegroundWindow()) } else { Write-Output 'WINDOW_NOT_FOUND' }
  }
  'press' {
    $h = Present-Mc
    if ($h -eq $null) { break }
    Start-Sleep -Milliseconds 800
    $fg = [T0Win]::GetForegroundWindow()
    if ($fg -ne $h) { Write-Output 'FOCUS_FAIL'; break }
    $vkCodeFromMap = $null
    $vkMap = @{ 'A'=0x41;'W'=0x57;'S'=0x53;'D'=0x44;'Q'=0x51;'Z'=0x5A;'X'=0x58;'C'=0x43;'E'=0x45;'G'=0x47;'L'=0x4C;'H'=0x48;'B'=0x42;'N'=0x4E;'M'=0x4D;'I'=0x49;'J'=0x4A;'K'=0x4B;'F'=0x46;'O'=0x4F;'P'=0x50;'U'=0x55;'V'=0x56;'Y'=0x59;'T'=0x54;'1'=0x31;'2'=0x32;'3'=0x33;'5'=0x35 }
    $vkMap = @{ 'A'=0x41;'W'=0x57;'S'=0x53;'D'=0x44;'Q'=0x51;'Z'=0x5A;'X'=0x58;'C'=0x43;'E'=0x45;'G'=0x47;'L'=0x4C;'H'=0x48;'B'=0x42;'N'=0x4E;'M'=0x4D;'I'=0x49;'J'=0x4A;'K'=0x4B;'F'=0x46;'O'=0x4F;'P'=0x50;'U'=0x55;'V'=0x56;'Y'=0x59;'T'=0x54 }
    if ($vkMap.ContainsKey($Text)) {
      [T0Win]::keybd_event([byte]$vkMap[$Text],0,0,[UIntPtr]::Zero)
      Start-Sleep -Milliseconds 60
      [T0Win]::keybd_event([byte]$vkMap[$Text],0,2,[UIntPtr]::Zero)
      Start-Sleep -Milliseconds 120
      Write-Output ('KEYED ' + $Text + ' fg=' + [T0Win]::GetForegroundWindow() + ' target=' + $h)
      break
    }
    switch ($Text) {
      'F3'   { [T0Win]::keybd_event(0x72,0,0,[UIntPtr]::Zero); Start-Sleep -Milliseconds 30; [T0Win]::keybd_event(0x72,0,2,[UIntPtr]::Zero) }
      'F2'   { [T0Win]::keybd_event(0x71,0,0,[UIntPtr]::Zero); Start-Sleep -Milliseconds 30; [T0Win]::keybd_event(0x71,0,2,[UIntPtr]::Zero) }
      'F4'   { [T0Win]::keybd_event(0x73,0,0,[UIntPtr]::Zero); Start-Sleep -Milliseconds 30; [T0Win]::keybd_event(0x73,0,2,[UIntPtr]::Zero) }
      'T'    { [T0Win]::keybd_event(0x54,0,0,[UIntPtr]::Zero); Start-Sleep -Milliseconds 30; [T0Win]::keybd_event(0x54,0,2,[UIntPtr]::Zero) }
      'ESC'  { [T0Win]::keybd_event(0x1B,0,0,[UIntPtr]::Zero); Start-Sleep -Milliseconds 30; [T0Win]::keybd_event(0x1B,0,2,[UIntPtr]::Zero) }
      'R'    { [T0Win]::keybd_event(0x52,0,0,[UIntPtr]::Zero); Start-Sleep -Milliseconds 30; [T0Win]::keybd_event(0x52,0,2,[UIntPtr]::Zero) }
      'ENTER' { [T0Win]::keybd_event(0x0D,0,0,[UIntPtr]::Zero); Start-Sleep -Milliseconds 30; [T0Win]::keybd_event(0x0D,0,2,[UIntPtr]::Zero) }
      'EXT'  {
        if ($vkCodeFromMap -ne $null) {
          [T0Win]::keybd_event([byte]$vkCodeFromMap,0,0,[UIntPtr]::Zero)
          Start-Sleep -Milliseconds 40
          [T0Win]::keybd_event([byte]$vkCodeFromMap,0,2,[UIntPtr]::Zero)
        } else { Write-Output 'BAD_KEY' }
      }
      default { Write-Output 'BAD_KEY'; break }
    }
    Start-Sleep -Milliseconds 120
    $fg2 = [T0Win]::GetForegroundWindow()
    Write-Output ('PRESSED ' + $Text + ' fg=' + $fg2 + ' target=' + $h + ' fgOk=' + ($fg2 -eq $h))
  }
  'char' {
    # 逐字符 keybd_event 注入（与游戏原生键一致；仅支持 a-z 数字 / 空格）
    $map = @{ '/' = 0xBF;' ' = 0x20;'0' = 0x30;'1' = 0x31;'2' = 0x32;'3' = 0x33;'4' = 0x34;'5' = 0x35;'6' = 0x36;'7' = 0x37;'8' = 0x38;'9' = 0x39;'a' = 0x41;'b' = 0x42;'c' = 0x43;'d' = 0x44;'e' = 0x45;'f' = 0x46;'g' = 0x47;'h' = 0x48;'i' = 0x49;'j' = 0x4A;'k' = 0x4B;'l' = 0x4C;'m' = 0x4D;'n' = 0x4E;'o' = 0x4F;'p' = 0x50;'q' = 0x51;'r' = 0x52;'s' = 0x53;'t' = 0x54;'u' = 0x55;'v' = 0x56;'w' = 0x57;'x' = 0x58;'y' = 0x59;'z' = 0x5A }
    foreach ($ch in $Text.ToCharArray()) {
      $vk = $map[[string]$ch]
      if ($null -ne $vk) {
        [T0Win]::keybd_event([byte]$vk,0,0,[UIntPtr]::Zero)
        Start-Sleep -Milliseconds 20
        [T0Win]::keybd_event([byte]$vk,0,2,[UIntPtr]::Zero)
        Start-Sleep -Milliseconds 20
      }
    }
    Write-Output ('CHARS_SENT len=' + $Text.Length)
  }
  'chat' {
    $h = Present-Mc
    if ($h -eq $null) { break }
    Send-Key 'T'
    Start-Sleep -Milliseconds 400
    Send-Chars $Text
    Start-Sleep -Milliseconds 200
    Send-Key '{ENTER}'
    Write-Output 'CHAT_SENT'
  }
  'f3r' {
    $h = Present-Mc
    if ($h -eq $null) { break }
    [T0Win]::keybd_event(0x72,0,0,[UIntPtr]::Zero)   # F3 down
    Start-Sleep -Milliseconds 40
    [T0Win]::keybd_event(0x52,0,0,[UIntPtr]::Zero)   # R down
    Start-Sleep -Milliseconds 40
    [T0Win]::keybd_event(0x52,0,2,[UIntPtr]::Zero)   # R up
    Start-Sleep -Milliseconds 40
    [T0Win]::keybd_event(0x72,0,2,[UIntPtr]::Zero)   # F3 up
    Write-Output 'F3R_SENT'
  }
  'postkey' {
    # 纯后台键盘通道(2026-09-02 用户明令:禁止抢鼠标/抢焦点)——
    # 旧实现真实点击标题栏抢焦点 + 合成 WM_ACTIVATE,会动用户鼠标并顶飞前台窗口(坑72)。
    # 现与 postkey-walk.ps1 同构:仅 PostMessage WM_KEYDOWN/UP,lParam 带 scancode
    # (坑46:后台可靠通道),KEYUP 带 bit30|bit31(坑23:否则 GLFW 视为 repeat 键粘滞)。
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    $map10 = @{ 'F3'=@(0x72,0x3D); 'F2'=@(0x71,0x3C); 'F4'=@(0x73,0x3E); 'F5'=@(0x74,0x3F);
                'ENTER'=@(0x0D,0x1C); 'ESC'=@(0x1B,0x01); 'BACK'=@(0x08,0x0E);
                'R'=@(0x52,0x13); 'T'=@(0x54,0x14); 'W'=@(0x57,0x11); 'A'=@(0x41,0x1E);
                'S'=@(0x53,0x1F); 'D'=@(0x44,0x20); 'J'=@(0x4A,0x24); 'N'=@(0x4E,0x31);
                'B'=@(0x42,0x30); 'K'=@(0x4B,0x25); 'L'=@(0x4C,0x26); '/'=@(0xBF,0x35);
                '1'=@(0x31,0x02); '2'=@(0x32,0x03); '3'=@(0x33,0x04); '4'=@(0x34,0x05);
                '5'=@(0x35,0x06); '6'=@(0x36,0x07); '7'=@(0x37,0x08); '8'=@(0x38,0x09); '9'=@(0x39,0x0A) }
    if (-not $map10.ContainsKey($Text)) { Write-Output 'UNSUPPORTED'; break }
    $vk = [int]$map10[$Text][0]; $scan = [int]$map10[$Text][1]
    $downL = [long]1 -bor ([long]$scan -shl 16)
    $upL = [long]3221225472 -bor ([long]$scan -shl 16) -bor [long]1
    [T0Win]::PostMessageW($h, 0x0100, [IntPtr]$vk, [IntPtr]$downL) | Out-Null
    Start-Sleep -Milliseconds 50
    [T0Win]::PostMessageW($h, 0x0101, [IntPtr]$vk, [IntPtr]$upL) | Out-Null
    Write-Output ('POSTED ' + $Text)
  }
  'holdkey' {
    # 持键通道(2026-08-30 移动光源调试):WM_KEYDOWN 持住 $Dur 毫秒再 WM_KEYUP。
    # 2026-09-02 改纯后台:去掉合成 WM_ACTIVATE/WM_SETFOCUS(假焦点诱发 GLFW 光标态翻转,
    # 坑72),lParam 带 scancode(坑46);与 postkey-walk.ps1 同构。仅支持单键,勿组合。
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    $map10 = @{ 'W'=@(0x57,0x11); 'A'=@(0x41,0x1E); 'S'=@(0x53,0x1F); 'D'=@(0x44,0x20) }
    if (-not $map10.ContainsKey($Text)) { Write-Output 'UNSUPPORTED'; break }
    $vk = [int]$map10[$Text][0]; $scan = [int]$map10[$Text][1]
    $downL = [long]1 -bor ([long]$scan -shl 16)
    $upL = [long]3221225472 -bor ([long]$scan -shl 16) -bor [long]1
    [T0Win]::PostMessageW($h, 0x0100, [IntPtr]$vk, [IntPtr]$downL) | Out-Null   # down
    Start-Sleep -Milliseconds $Dur
    [T0Win]::PostMessageW($h, 0x0101, [IntPtr]$vk, [IntPtr]$upL) | Out-Null     # up(坑23)
    Write-Output ('HELD ' + $Text + ' ' + $Dur + 'ms')
  }
  'postf3r' {
    # PostMessage lane: F3 down -> R down/up -> F3 up。2026-09-02 改纯后台:去掉真实点击
    # 标题栏抢焦点(坑72),lParam 带 scancode(坑46)。
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    $f3d = [long]1 -bor (0x3D -shl 16); $f3u = [long]3221225472 -bor (0x3D -shl 16) -bor 1
    $rd  = [long]1 -bor (0x13 -shl 16); $ru  = [long]3221225472 -bor (0x13 -shl 16) -bor 1
    [T0Win]::PostMessageW($h, 0x0100, [IntPtr]0x72, [IntPtr]$f3d) | Out-Null   # F3 down
    Start-Sleep -Milliseconds 250
    [T0Win]::PostMessageW($h, 0x0100, [IntPtr]0x52, [IntPtr]$rd) | Out-Null    # R down
    Start-Sleep -Milliseconds 250
    [T0Win]::PostMessageW($h, 0x0101, [IntPtr]0x52, [IntPtr]$ru) | Out-Null    # R up
    Start-Sleep -Milliseconds 250
    [T0Win]::PostMessageW($h, 0x0101, [IntPtr]0x72, [IntPtr]$f3u) | Out-Null   # F3 up
    Write-Output 'POSTF3R_SENT'
  }
  'postchars' {
    # 2026-09-02:去掉合成 WM_ACTIVATE/WM_SETFOCUS(假焦点,坑72);WM_CHAR 本身与焦点无关。
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    foreach ($ch in $Text.ToCharArray()) {
      [T0Win]::PostMessageW($h, 0x0102, [IntPtr]([int][char]$ch), [IntPtr]::Zero) | Out-Null
      Start-Sleep -Milliseconds 20
    }
    Write-Output ('POSTEDCHARS ' + $Text.Length)
  }
  'postchat' {
    # 纯 PostMessage 聊天命令(本机实测:物理输入队列在本调试场景不可靠,此通道可靠)
    # 流程:'/' 开聊天框(自动带斜杠)→ WM_CHAR 逐字 → ENTER 发送。Text 应以 '/' 开头。
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    if (-not $Text.StartsWith('/')) { Write-Output 'MUST_START_WITH_SLASH'; break }
    [T0Win]::PostMessageW($h, 0x0100, [IntPtr]0xBF, [IntPtr]::Zero) | Out-Null   # '/' down -> 打开聊天框
    Start-Sleep -Milliseconds 50
    [T0Win]::PostMessageW($h, 0x0101, [IntPtr]0xBF, [IntPtr]::Zero) | Out-Null   # '/' up
    Start-Sleep -Milliseconds 250
    foreach ($ch in $Text.Substring(1).ToCharArray()) {
      [T0Win]::PostMessageW($h, 0x0102, [IntPtr]([int][char]$ch), [IntPtr]::Zero) | Out-Null
      Start-Sleep -Milliseconds 20
    }
    Start-Sleep -Milliseconds 200
    [T0Win]::PostMessageW($h, 0x0100, [IntPtr]0x0D, [IntPtr]::Zero) | Out-Null   # ENTER down
    Start-Sleep -Milliseconds 50
    [T0Win]::PostMessageW($h, 0x0101, [IntPtr]0x0D, [IntPtr]::Zero) | Out-Null   # ENTER up
    Write-Output ('POSTCHAT_SENT ' + $Text)
  }
  'paste' {
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.Clipboard]::SetText($Text) | Out-Null
    Start-Sleep -Milliseconds 150
    [T0Win]::PostMessageW($h, 0x0100, [IntPtr]0x11, [IntPtr]::Zero) | Out-Null   # CTRL down
    [T0Win]::PostMessageW($h, 0x0100, [IntPtr]0x41, [IntPtr]::Zero) | Out-Null   # A down
    Start-Sleep -Milliseconds 50
    [T0Win]::PostMessageW($h, 0x0101, [IntPtr]0x41, [IntPtr]::Zero) | Out-Null   # A up
    [T0Win]::PostMessageW($h, 0x0100, [IntPtr]0x56, [IntPtr]::Zero) | Out-Null   # V down
    Start-Sleep -Milliseconds 50
    [T0Win]::PostMessageW($h, 0x0101, [IntPtr]0x56, [IntPtr]::Zero) | Out-Null   # V up
    [T0Win]::PostMessageW($h, 0x0101, [IntPtr]0x11, [IntPtr]::Zero) | Out-Null   # CTRL up
    Write-Output 'PASTED'
  }
  'close' {
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    [T0Win]::PostMessageW($h, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
    Write-Output 'CLOSE_SENT'
  }
  'click' {
    $h = Present-Mc
    if ($h -eq $null) { break }
    $parts = $Text -split ','
    if ($parts.Count -ne 2) { Write-Output 'BAD_COORDS'; break }
    $rx = New-Object T0Win+RECT
    [T0Win]::GetWindowRect($h, [ref]$rx) | Out-Null
    $ax = $rx.L + [int]$parts[0]
    $ay = $rx.T + [int]$parts[1]
    [T0Win]::SetCursorPos($ax, $ay) | Out-Null
    Start-Sleep -Milliseconds 150
    [T0Win]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)   # left down
    Start-Sleep -Milliseconds 60
    [T0Win]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)   # left up
    Write-Output ('CLICKED ' + $Text + ' abs=' + $ax + ',' + $ay)
  }
  'quit' {
    $h = Present-Mc
    if ($h -eq $null) { break }
    Send-Key '%{F4}'
    Write-Output 'QUIT_SENT'
  }
  'shot' {
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    $r = New-Object T0Win+RECT
    [T0Win]::GetWindowRect($h, [ref]$r) | Out-Null
    $w = $r.R - $r.L; $ht = $r.B - $r.T
    if ($w -le 0 -or $ht -le 0) { Write-Output 'BAD_RECT'; break }
    $bmp = New-Object System.Drawing.Bitmap($w, $ht)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.L, $r.T, 0, 0, (New-Object System.Drawing.Size($w, $ht)))
    $g.Dispose()
    $bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output ("SHOT " + $w + "x" + $ht + " -> " + $OutFile)
  }
  'pin' {
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class T0Top {
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
"@
    $top = [T0Top]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x0003)
    Write-Output ('PIN ' + $top)
  }
  'find' {
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND' } else { Write-Output ('OK ' + $h.ToString()) }
  }
  'rawkey' {
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    $rx = New-Object T0Win+RECT
    [T0Win]::GetWindowRect($h, [ref]$rx) | Out-Null
    [T0Win]::SetCursorPos(($rx.L + 650), ($rx.T + 18)) | Out-Null
    Start-Sleep -Milliseconds 120
    [T0Win]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [T0Win]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 400
    Send-RawKey $Text $false
  }
  'rawkey2' {
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    $rx = New-Object T0Win+RECT
    [T0Win]::GetWindowRect($h, [ref]$rx) | Out-Null
    [T0Win]::SetCursorPos(($rx.L + 650), ($rx.T + 18)) | Out-Null
    Start-Sleep -Milliseconds 120
    [T0Win]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [T0Win]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 400
    Send-RawKey $Text $true
  }
  'rawseq' {
    # Text = one of F3D/F3U/RD/RU/GD/GU/DD/DU (SCANCODE lane, single event)
    $seqMap = @{ 'F3D'=@(0x72,0x3D,0x0); 'F3U'=@(0x72,0x3D,0x2); 'RD'=@(0x52,0x13,0x0); 'RU'=@(0x52,0x13,0x2);
      'GD'=@(0x47,0x22,0x0); 'GU'=@(0x47,0x22,0x2); 'WD'=@(0x57,0x11,0x0); 'WU'=@(0x57,0x11,0x2);
      'TD'=@(0x54,0x14,0x0); 'TU'=@(0x54,0x14,0x2); 'ND'=@(0x4E,0x31,0x0); 'NU'=@(0x4E,0x31,0x2) }
    if (-not $seqMap.ContainsKey($Text)) { Write-Output 'UNSUPPORTED'; break }
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    $k = $seqMap[$Text]
    $sent = [T0Send]::SendSeq([int[][]]@([int[]]@($k[0],$k[1],$k[2])))
    Write-Output ('RAWSEQ ' + $Text + ' sent=' + $sent)
  }
  'rawf3r' {
    $h = Find-McWindow
    if ($h -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; break }
    $rx = New-Object T0Win+RECT
    [T0Win]::GetWindowRect($h, [ref]$rx) | Out-Null
    [T0Win]::SetCursorPos(($rx.L + 650), ($rx.T + 18)) | Out-Null
    Start-Sleep -Milliseconds 120
    [T0Win]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [T0Win]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 400
    # F3 down -> R down/up -> F3 up (Single-input raw lane, per-event with spacing)
    $seq = @(@(0x72,0x3D,0x0), @(0x52,0x13,0x0), @(0x52,0x13,0x2), @(0x72,0x3D,0x2))
    $sentTot = 0
    foreach ($k in $seq) {
      $one = New-Object 'System.Object[]' 0
      $sentTot += [T0Send]::SendSeq([int[][]]@([int[]]@($k[0],$k[1],$k[2])))
      Start-Sleep -Milliseconds 150
    }
    Write-Output ('RAWF3R_SENT sent=' + $sentTot)
  }
  default { Write-Output 'UNKNOWN_ACTION' }
}