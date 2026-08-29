# rcon.ps1 — Source RCON 最小客户端(PS 5.1 原生 TCP,零依赖)
# 用途:M5 多人测试里向 dev 专用服(run-server,RCON 口 25575)发命令,
#       替代"往服务器控制台敲字"——自动化场景/灯开关不占键鼠。
# 用法:powershell -NoProfile -File tools\rcon.ps1 -Command "/taclight light on PlayerA"
# 禁止管道(项目纪律);失败非零退出码,错误进 stderr。
param(
  [Parameter(Mandatory=$true)][string]$Command,
  [string]$ServerHost = '127.0.0.1',
  [int]$Port = 25575,
  [string]$Password = 'taclight',
  [int]$TimeoutMs = 8000
)
$ErrorActionPreference = 'Stop'

function New-Packet([int]$id, [int]$type, [string]$payload) {
  $body = [Text.Encoding]::ASCII.GetBytes($payload)
  $len = 4 + 4 + $body.Length + 2
  $ms = New-Object System.IO.MemoryStream
  $bw = New-Object System.IO.BinaryWriter($ms)
  $bw.Write([int]$len); $bw.Write([int]$id); $bw.Write([int]$type)
  $bw.Write($body); $bw.Write([byte]0); $bw.Write([byte]0)
  $bw.Flush()
  return ,$ms.ToArray()
}

function Read-Exact([System.IO.NetworkStream]$s, [int]$n) {
  $buf = New-Object byte[] $n
  $off = 0
  while ($off -lt $n) {
    $r = $s.Read($buf, $off, $n - $off)
    if ($r -le 0) { throw 'RCON connection closed early' }
    $off += $r
  }
  return ,$buf
}

function Read-Packet([System.IO.NetworkStream]$s) {
  $lenBuf = Read-Exact $s 4
  $len = [BitConverter]::ToInt32($lenBuf, 0)
  if ($len -lt 10 -or $len -gt 8192) { throw ('RCON bad length ' + $len) }
  $rest = Read-Exact $s $len
  $id = [BitConverter]::ToInt32($rest, 0)
  $text = [Text.Encoding]::ASCII.GetString($rest, 8, $len - 10)
  return @{ id = $id; text = $text }
}

$client = New-Object System.Net.Sockets.TcpClient
$task = $client.ConnectAsync($ServerHost, $Port)
if (-not $task.Wait($TimeoutMs)) { throw ('RCON connect timeout ' + $ServerHost + ':' + $Port) }
$stream = $client.GetStream()
$stream.ReadTimeout = $TimeoutMs
try {
  $auth = New-Packet 1 3 $Password
  $stream.Write($auth, 0, $auth.Length)
  $resp = Read-Packet $stream
  if ($resp.id -eq -1) { throw 'RCON auth failed (check rcon.password)' }
  $cmd = New-Packet 2 2 $Command
  $stream.Write($cmd, 0, $cmd.Length)
  $resp2 = Read-Packet $stream
  Write-Output $resp2.text.TrimEnd()
  if ($resp2.id -eq -1) { exit 1 }
  exit 0
} finally {
  $client.Close()
}
