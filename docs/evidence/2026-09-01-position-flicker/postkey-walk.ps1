param([string]$Seq, [int]$ProcId = 0)
# PostMessage walk driver: WM_KEYDOWN/UP directly to the game window (no focus needed).
# Seq tokens: KEY<ms> = hold key for ms, P<ms> = pause.
Add-Type -MemberDefinition '[DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);' -Name P -Namespace T0Pk

Add-Type -AssemblyName System.Windows.Forms | Out-Null
$procs = Get-Process | Where-Object { $_.MainWindowTitle -like 'Minecraft*' }
if ($ProcId -gt 0) { $procs = $procs | Where-Object { $_.Id -eq $ProcId } }
if (-not $procs) { Write-Output 'WINDOW_NOT_FOUND'; exit 1 }
$hwnd = $procs[0].MainWindowHandle

$vk = @{ 'W'=0x57; 'A'=0x41; 'S'=0x53; 'D'=0x44 }
$sc = @{ 'W'=0x11; 'A'=0x1E; 'S'=0x1F; 'D'=0x20 }
$WM_KEYDOWN = 0x0100; $WM_KEYUP = 0x0101
foreach ($tok in ($Seq -split '\s+' | Where-Object { $_ })) {
  $k = $tok.Substring(0,1); $ms = [int]$tok.Substring(1)
  if ($k -eq 'P') { Start-Sleep -Milliseconds $ms; continue }
  if (-not $vk.ContainsKey($k)) { Write-Output ('SKIP ' + $tok); continue }
  $downL = [long]1 -bor ([long]$sc[$k] -shl 16)
  $upL = [long]3221225472 -bor ([long]$sc[$k] -shl 16) -bor [long]1
  [T0Pk.P]::PostMessage($hwnd, $WM_KEYDOWN, [IntPtr]$vk[$k], [IntPtr]$downL) | Out-Null
  Start-Sleep -Milliseconds $ms
  [T0Pk.P]::PostMessage($hwnd, $WM_KEYUP, [IntPtr]$vk[$k], [IntPtr]$upL) | Out-Null
  Start-Sleep -Milliseconds 60
  Write-Output ('POSTED ' + $k + ' ' + $ms + 'ms')
}
Write-Output 'WALK DONE'
