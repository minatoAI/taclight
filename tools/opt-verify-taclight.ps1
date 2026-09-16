#requires -Version 5.1
<#
  opt-verify-taclight.ps1 -- six-arm ablation driver for the OLD TacLight rig (t-opt-verify).

  WHAT IT DOES (never starts the game; QA starts it):
    for each arm A,A2,B,C,D,E:
      1. write run/taclight-cmds.txt with the arm's relay pre-state (!gun/!beam/!occl)
         and wait until the game logs "RELAY exec: <line>" for every line (no guessing);
      2. drop tickets/<ticket>.json into run/taclight-agent/inbox atomically (.tmp + move);
      3. wait for run/taclight-agent/outbox/<ticket>.result.json (canonical, newest);
      4. copy the result JSON into the evidence dir, verify result.ok and the in-ticket
         lamp readback (arm A must read handheld=false AND gun=false -> a true off baseline);
      5. run the rigorous bench: relay !bench x BenchWarmup (discarded) + x BenchReps (measured),
         3s frame windows as logged by ClientEvents, keep the raw log slice + median;
      6. relay !diag for an independent (pack/lamp/pose) evidence line;
      7. freeze a slice of run/logs/latest.log for the arm into the evidence dir.
    finally: tail reset (!gun auto / !beam 0.25 / !occl on / !tm on / !beamonly off), copy the whole session log
    (latest.log + any file rotated during the run) with sha256, and write summary.json/md.

  WHY relay for occl/beam/gun: TicketBridge.KNOWN_OPS has no occl/beam/gun op
  (TicketBridge.java:80-83, OpCatalog.java:51-108) and /taclight only has kit|cam|scene|light
  (TacLightCommand.java:35-69). Those knobs are consumed by DebugCommandRelay from
  run/taclight-cmds.txt (DebugCommandRelay.java:163-283, polling at 2Hz, world-only).

  USAGE (QA, copy-paste):
    $env:JAVA_HOME="E:\dshHome\.jdks\temurin-17"
    cd E:\dshHome\mc-mod-spotlight-attachment\taclight
    .\gradlew.bat compileJava syncShaderPack
    .\gradlew.bat runClient -PtaclightQuickPlay=test          # keep it running, in-world
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\opt-verify-taclight.ps1
#>
[CmdletBinding()]
param(
    [string]$Repo = 'E:\dshHome\mc-mod-spotlight-attachment\taclight',
    [string]$Evidence = 'E:\dshHome\ray-traced-spotlight-mod-dev\docs\evidence\2026-09-15-oldtrack-verify\dev\run-out',
    [int]$BenchReps = 3,
    [int]$BenchWarmup = 1,
    [int]$ArmTimeoutSec = 300,
    [int]$RelayTimeoutSec = 60,
    [switch]$SkipPreconditions,
    [string]$Spec = '',
    [string]$Session = '',
    [string]$ExpectPng = '',
    [int]$FreecamWaitSec = 60,
    [string]$RelayExtra = ''
)

$script:FreecamWaitSec = $FreecamWaitSec

$ErrorActionPreference = 'Stop'

trap {
    Write-Host ("[opt-verify] FATAL {0}" -f $_.Exception.Message) -ForegroundColor Red
    Write-Host $_.InvocationInfo.PositionMessage -ForegroundColor Red
    exit 2
}

# ---------------------------------------------------------------- paths / spec
$Run      = Join-Path $Repo 'run'
$PackSrc  = Join-Path $Repo 'pack\shaders'
$PackLive = Join-Path $Run 'shaderpacks\taclight-shaders-dev\shaders'
$OculusCfg= Join-Path $Run 'config\oculus.properties'
$LogDir   = Join-Path $Run 'logs'
$LogPath  = Join-Path $LogDir 'latest.log'
$CmdsFile = Join-Path $Run 'taclight-cmds.txt'
$Inbox    = Join-Path $Run 'taclight-agent\inbox'
$Outbox   = Join-Path $Run 'taclight-agent\outbox'
$Shots    = Join-Path $Run 'screenshots'
$Tickets  = Join-Path $Repo 'tickets'
$LivePack = 'taclight-shaders-dev'

# T8/multi-client: each Minecraft instance owns its own run/taclight-cmds.txt, so a >=5-light session
# (main + observer instances) must fan every relay line out to ALL instances. -RelayExtra takes a
# comma/semicolon-separated list of extra relay file paths; empty = single-instance behaviour.
$RelayFiles = @($CmdsFile) + @($RelayExtra -split '[,;]' | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() })

$Arms = @(
    [pscustomobject]@{ Id='A';  Ticket='t-opt-arm-a';  Relay=@('!gun off','!beam 0.25','!occl off');   Desc='lights-off baseline (both lamps dead)';          NeedLampOff=$true;  Camera='first'; ShotPrefix='opt-verify-A'  },
    [pscustomobject]@{ Id='A2'; Ticket='t-opt-arm-a2'; Relay=@('!gun auto','!beam 0','!occl off');     Desc='lights on + beam density 0 (beamIdle early-out)'; NeedLampOff=$false; Camera='first'; ShotPrefix='opt-verify-A2' },
    [pscustomobject]@{ Id='B';  Ticket='t-opt-arm-b';  Relay=@('!gun auto','!beam 0.25','!occl off'); Desc='tm off / occl off = 64-step exact';             NeedLampOff=$false; Camera='first'; ShotPrefix='opt-verify-B'  },
    [pscustomobject]@{ Id='C';  Ticket='t-opt-arm-c';  Relay=@('!gun auto','!beam 0.25','!occl off'); Desc='tm on / occl off = 32-step';                   NeedLampOff=$false; Camera='first'; ShotPrefix='opt-verify-C'  },
    [pscustomobject]@{ Id='D';  Ticket='t-opt-arm-d';  Relay=@('!gun auto','!beam 0.25','!occl on');  Desc='tm on / occl on = 8-light occlusion table';     NeedLampOff=$false; Camera='first'; ShotPrefix='opt-verify-D'  },
    [pscustomobject]@{ Id='E';  Ticket='t-opt-arm-e';  Relay=@('!gun auto','!beam 0.25','!occl off'); Desc='occl off rollback (expect ~= C)';               NeedLampOff=$false; Camera='first'; ShotPrefix='opt-verify-E'  }
)
$TailRelay = @('!gun auto','!beam 0.25','!occl on','!tm on','!beamonly off','!synth 0')


# ---------------------------------------------------------------- tiny helpers
function Say([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg)
}
function Fail([string]$msg) { throw "[opt-verify] $msg" }
function Ensure-Dir([string]$p) { if (-not (Test-Path $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null } }
function Get-Sha256([string]$p) { (Get-FileHash -Algorithm SHA256 -Path $p).Hash }
function Get-Median([double[]]$v) {
    $s = @($v | Sort-Object)
    $n = $s.Count
    if ($n -eq 0) { return $null }
    if ($n % 2 -eq 1) { return [double]$s[[int](($n - 1) / 2)] }
    return [double](($s[$n / 2 - 1] + $s[$n / 2]) / 2.0)
}
function Write-Utf8NoBom([string]$path, [string]$text) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $text, $enc)
}
function Copy-FileShared([string]$src, [string]$dest, [int]$retries = 10) {
    # the game keeps latest.log open while writing: copy through a ReadWrite-share handle
    for ($i = 1; $i -le $retries; $i++) {
        try {
            $in = [System.IO.File]::Open($src, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete))
            try {
                $outStream = [System.IO.File]::Open($dest, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
                try { $in.CopyTo($outStream) } finally { $outStream.Close() }
            } finally { $in.Close() }
            return $dest
        } catch {
            Start-Sleep -Milliseconds 300
            if ($i -eq $retries) { throw }
        }
    }
}

function Read-FileShared([string]$path, [int]$retries = 10) {
    # B2: latest.log is held open by the game; a plain ReadAllText opens with FileShare::Read
    # only and dies with a sharing violation. Read through a ReadWrite-share handle instead.
    for ($i = 1; $i -le $retries; $i++) {
        try {
            $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete))
            try {
                $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
                try { return $sr.ReadToEnd() } finally { $sr.Close() }
            } finally { $fs.Close() }
        } catch {
            Start-Sleep -Milliseconds 300
            if ($i -eq $retries) { throw }
        }
    }
}
function Read-FileRangeShared([string]$path, [int64]$from, [int64]$to, [int]$retries = 10) {
    # B3: same sharing problem for the per-arm log slice.
    for ($i = 1; $i -le $retries; $i++) {
        try {
            $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete))
            try {
                $len = $fs.Length
                if ($to -gt $len) { $to = $len }
                if ($to -le $from) { return $null }
                [void]$fs.Seek($from, [System.IO.SeekOrigin]::Begin)
                $count = [int]($to - $from)
                $buf = New-Object byte[] $count
                $read = 0
                while ($read -lt $count) {
                    $n = $fs.Read($buf, $read, $count - $read)
                    if ($n -le 0) { break }
                    $read += $n
                }
                if ($read -le 0) { return $null }
                return [System.Text.Encoding]::UTF8.GetString($buf, 0, $read)
            } finally { $fs.Close() }
        } catch {
            Start-Sleep -Milliseconds 300
            if ($i -eq $retries) { throw }
        }
    }
}
function Get-PngSize([string]$path) {
    # PNG IHDR: width/height are big-endian u32 at byte offset 16 / 20.
    try {
        $b = [System.IO.File]::ReadAllBytes($path)
        if ($b.Length -lt 24) { return $null }
        $w = ([int]$b[16] -shl 24) -bor ([int]$b[17] -shl 16) -bor ([int]$b[18] -shl 8) -bor [int]$b[19]
        $h = ([int]$b[20] -shl 24) -bor ([int]$b[21] -shl 16) -bor ([int]$b[22] -shl 8) -bor [int]$b[23]
        return ("{0}x{1}" -f $w, $h)
    } catch { return $null }
}
function Get-TicketPlayerPos($res) {
    # last ticket row carrying a "pos" string ("x,y,z") -- dump rows and state-all snapshots both have it
    $last = $null
    foreach ($r in @($res.ops)) {
        if (($r.PSObject.Properties.Name -contains 'pos') -and $r.pos) { $last = $r.pos }
    }
    if (-not $last) { return $null }
    $p = ([string]$last) -split ','
    if ($p.Count -lt 3) { return $null }
    return @([double]$p[0], [double]$p[1], [double]$p[2])
}
function Get-CamPos([string]$diagText) {
    $m = [regex]::Match($diagText, 'cam=\(([-0-9.]+),([-0-9.]+),([-0-9.]+)\)')
    if (-not $m.Success) { return $null }
    return @([double]$m.Groups[1].Value, [double]$m.Groups[2].Value, [double]$m.Groups[3].Value)
}
function Test-FreecamEvidence($res, [string]$diagText) {
    # two independent keys (lead request): the pseudo-player line, OR a camera detached from the player eye.
    # BUG7: the key MUST name FreeCamera -- every other player also logs "self=false" (e.g. an observer
    # instance in a multi-client / 8-light session), so a bare self=false falsely satisfies this gate.
    if ([regex]::IsMatch($diagText, 'DIAG-REMOTE player=FreeCamera\S*\s+self=false')) { return 'DIAG-REMOTE player=FreeCamera self=false' }
    $cam = Get-CamPos $diagText
    $pp = Get-TicketPlayerPos $res
    if ($cam -and $pp) {
        $dx = [math]::Abs($cam[0] - $pp[0]); $dz = [math]::Abs($cam[2] - $pp[2]); $dy = $cam[1] - $pp[1]
        if ($dx -gt 1.5 -or $dz -gt 1.5 -or $dy -lt 0.5 -or $dy -gt 2.5) {
            return ("cam-detached dx={0} dy={1} dz={2}" -f [math]::Round($dx, 2), [math]::Round($dy, 2), [math]::Round($dz, 2))
        }
    }
    return $null
}
function Assert-CameraGate([string]$mode, $res, [string]$diagText) {
    # B4: a polluted arm (F4 freecam left on, or user walking the player away) must not produce numbers.
    $cam = Get-CamPos $diagText
    # BUG7: name-scoped -- a remote player line also says self=false; only the FreeCamera pseudo-player voids an arm.
    $free = [regex]::IsMatch($diagText, 'DIAG-REMOTE player=FreeCamera\S*\s+self=false')
    if ($mode -eq 'any') { return [pscustomobject]@{ ok = $true; note = 'gate=any (no camera check requested)' } }
    if ($mode -eq 'freecam') {
        $ev = Test-FreecamEvidence $res $diagText
        if ($ev) { return [pscustomobject]@{ ok = $true; note = ("gate=freecam ok ({0}, cam={1})" -f $ev, ($cam -join ',')) } }
        # BUG1 fix: DIAG/DIAG-REMOTE lines are only produced by a relayed !diag -- a passive wait can
        # never see FreeCamera. Probe !diag every 2s while waiting for the human to press F4.
        $deadline = (Get-Date).AddSeconds($script:FreecamWaitSec)
        Say ("  gate=freecam: waiting up to {0}s for F4 (probing !diag every 2s) ..." -f $script:FreecamWaitSec)
        $nextProbe = Get-Date
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
            if ((Get-Date) -ge $nextProbe) {
                $nextProbe = (Get-Date).AddSeconds(2)
                try { Write-Relay @('!diag') } catch { Say ("  WARN freecam probe !diag failed: {0}" -f $_.Exception.Message) }
            }
            Update-LogBuf
            $ev = Test-FreecamEvidence $res $script:LogBuf
            if ($ev) { return [pscustomobject]@{ ok = $true; note = ("gate=freecam ok after wait ({0})" -f $ev) } }
            if ([regex]::IsMatch($script:LogBuf, 'cam=\(([-0-9.]+),([-0-9.]+),([-0-9.]+)\)')) {
                Say ("  gate=freecam probe: {0}" -f ([regex]::Match($script:LogBuf, 'cam=\([-0-9.,]+\)').Value))
            }
        }
        return [pscustomobject]@{ ok = $false; note = ("gate=freecam FAIL: no freecam evidence within {0}s (no DIAG-REMOTE self=false AND camera still on the player eye -- F4 not pressed)" -f $script:FreecamWaitSec) }
    }
    if ($free) { return [pscustomobject]@{ ok = $false; note = 'gate FAIL: FreeCamera pseudo-player present (self=false) -- freecam still on, arm void' } }
    $pp = Get-TicketPlayerPos $res
    if (-not $pp) { return [pscustomobject]@{ ok = $false; note = 'gate FAIL: ticket result has no player pos to compare' } }
    if (-not $cam) { return [pscustomobject]@{ ok = $false; note = 'gate FAIL: no cam=(x,y,z) in DIAG' } }
    $dx = [math]::Abs($cam[0] - $pp[0]); $dz = [math]::Abs($cam[2] - $pp[2]); $dy = $cam[1] - $pp[1]
    $d = ("dx={0} dy={1} dz={2}" -f [math]::Round($dx, 2), [math]::Round($dy, 2), [math]::Round($dz, 2))
    if ($mode -eq 'third') {
        if ($dx -le 8 -and $dz -le 8 -and $dy -ge 0.5 -and $dy -le 6) { return [pscustomobject]@{ ok = $true; note = ("gate=third ok ({0})" -f $d) } }
        return [pscustomobject]@{ ok = $false; note = ("gate=third FAIL ({0}) -- camera not within third-person range of the ticket pose" -f $d) }
    }
    # default: first person -> camera must sit at the player eye
    if ($dx -le 0.8 -and $dz -le 0.8 -and $dy -ge 0.8 -and $dy -le 2.2) { return [pscustomobject]@{ ok = $true; note = ("gate=first ok ({0})" -f $d) } }
    return [pscustomobject]@{ ok = $false; note = ("gate=first FAIL ({0}) -- camera is not on the player eye (freecam/third-person leaked in)" -f $d) }
}

# ---------------------------------------------------------------- log tailing
$script:LogOff = 0
$script:LogBuf = ''
function Update-LogBuf {
    if (-not (Test-Path $LogPath)) { return }
    $len = (Get-Item $LogPath).Length
    if ($len -lt $script:LogOff) { $script:LogOff = 0 }   # rotated / truncated
    if ($len -eq $script:LogOff) { return }
    $fs = [System.IO.File]::Open($LogPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        [void]$fs.Seek($script:LogOff, [System.IO.SeekOrigin]::Begin)
        $count = $len - $script:LogOff
        $buf = New-Object byte[] $count
        $read = 0
        while ($read -lt $count) {
            $n = $fs.Read($buf, $read, $count - $read)
            if ($n -le 0) { break }
            $read += $n
        }
    } finally { $fs.Close() }
    $script:LogBuf += [System.Text.Encoding]::UTF8.GetString($buf, 0, $read)
    $script:LogOff = $len
}
function Wait-Log([string]$pattern, [int]$timeoutSec, [string]$what) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        Update-LogBuf
        $m = [regex]::Matches($script:LogBuf, $pattern)
        if ($m.Count -gt 0) { return $m[$m.Count - 1].Value }
        Start-Sleep -Milliseconds 200
    }
    Fail ("timeout {0}s waiting for {1} (pattern {2})" -f $timeoutSec, $what, $pattern)
}
function Clear-LogBuf { Update-LogBuf; $script:LogBuf = '' }
function Get-LogMatches([string]$pattern, [int]$timeoutSec) {
    # collect matches in the accumulated buffer, waiting for at least one
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        Update-LogBuf
        $m = [regex]::Matches($script:LogBuf, $pattern)
        if ($m.Count -gt 0) { return @($m | ForEach-Object { $_.Value }) }
        Start-Sleep -Milliseconds 200
    }
    return @()
}
function Save-LogSlice([string]$from, [string]$to, [string]$dest) {
    # byte-range copy of the CURRENT latest.log; empty slice -> not written
    try {
        $a = [int64]$from; $b = [int64]$to
        if ($b -le $a) { return $null }
        $text = Read-FileRangeShared $LogPath $a $b
        if (-not $text) { Say ("WARN empty log slice for {0}" -f $dest); return $null }
        [System.IO.File]::WriteAllText($dest, $text, (New-Object System.Text.UTF8Encoding($false)))
        return $dest
    } catch { Say ("WARN cannot save log slice: {0}" -f $_.Exception.Message); return $null }
}

# ---------------------------------------------------------------- relay + tickets
function Write-Utf8NoBomFile([string]$path, [string]$text) {
    # every instance owns its own relay file: create the parent dir if the observer run dir is fresh
    $d = Split-Path $path -Parent
    if ($d -and -not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
    Write-Utf8NoBom $path $text
}
function Write-Relay([string[]]$lines) {
    # B1: clear the accumulated log buffer BEFORE writing, so the confirmation below (and the
    # BENCH line that follows) can only match output produced after this write. Without this,
    # the 2nd/3rd bench matched the previous bench's line and reported three identical values.
    Clear-LogBuf
    $payload = ($lines -join "`r`n") + "`r`n"
    Write-Utf8NoBomFile $CmdsFile $payload            # main instance (confirmation is read from ITS log)
    foreach ($rf in $RelayFiles) {
        if ($rf -ne $CmdsFile) { try { Write-Utf8NoBomFile $rf $payload } catch { Say ("  WARN extra relay write failed ({0}): {1}" -f $rf, $_.Exception.Message) } }
    }
    foreach ($l in $lines) {
        Wait-Log ("RELAY exec: " + [regex]::Escape($l)) $RelayTimeoutSec ("relay line '$l'") | Out-Null
        Say ("  relay ok: {0}" -f $l)
    }
    # surface the knob effect lines for the record
    foreach ($l in $lines) { Start-Sleep -Milliseconds 150 }
    Update-LogBuf
}
function Drop-Ticket([string]$ticket, [string]$ticketFile, [datetime]$since) {
    $dest = Join-Path $Inbox ($ticket + '.json')
    $tmp  = Join-Path $Inbox ($ticket + '.json.tmp')
    Copy-Item -LiteralPath $ticketFile -Destination $tmp -Force
    if (Test-Path $dest) { Remove-Item -LiteralPath $dest -Force }
    [System.IO.File]::Move($tmp, $dest)
    Say ("  ticket dropped: {0}" -f $dest)
    $resPath = Join-Path $Outbox ($ticket + '.result.json')
    $deadline = (Get-Date).AddSeconds($ArmTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $resPath) {
            $it = Get-Item $resPath
            if ($it.LastWriteTime -gt $since) {
                try {
                    $json = Get-Content -LiteralPath $resPath -Raw -Encoding UTF8 | ConvertFrom-Json
                    if ($json.ticket) { return [pscustomobject]@{ Path = $resPath; Json = $json } }
                } catch { }
            }
        }
        Start-Sleep -Milliseconds 400
    }
    Fail ("timeout {0}s waiting for outbox result {1}" -f $ArmTimeoutSec, $resPath)
}

# ---------------------------------------------------------------- preconditions
function Assert-OculusConfig {
    if (-not (Test-Path $OculusCfg)) { Fail "missing $OculusCfg" }
    $txt = [System.IO.File]::ReadAllText($OculusCfg)
    $pack = ([regex]::Match($txt, '(?m)^shaderPack=(.+)$')).Groups[1].Value.Trim()
    $en   = ([regex]::Match($txt, '(?m)^enableShaders=(.+)$')).Groups[1].Value.Trim()
    if ($pack -ne $LivePack) { Fail ("config/oculus.properties shaderPack='{0}', expected '{1}'" -f $pack, $LivePack) }
    if ($en -ne 'true')      { Fail ("config/oculus.properties enableShaders='{0}', expected 'true'" -f $en) }
    Say ("  oculus: shaderPack={0} enableShaders={1}" -f $pack, $en)
}
function Assert-PackSynced {
    $src = Get-ChildItem -Path $PackSrc -Recurse -File
    $dst = Get-ChildItem -Path $PackLive -Recurse -File -ErrorAction SilentlyContinue
    $bad = @()
    foreach ($f in $src) {
        $rel = $f.FullName.Substring($PackSrc.Length).TrimStart('\')
        $l = Join-Path $PackLive $rel
        if (-not (Test-Path -LiteralPath $l)) { $bad += "missing: $rel"; continue }
        if ((Get-Sha256 $f.FullName) -ne (Get-Sha256 $l)) { $bad += "differs: $rel" }
    }
    if ($bad.Count -gt 0) {
        Fail ("pack/shaders != run/shaderpacks/{0}/shaders -> run 'gradlew syncShaderPack' first:`n  {1}" -f $LivePack, ($bad -join "`n  "))
    }
    Say ("  pack sync: {0}/{0} files identical (sha256)" -f $src.Count)
}
function Assert-InboxEmpty {
    $n = @(Get-ChildItem -Path $Inbox -File -ErrorAction SilentlyContinue).Count
    if ($n -gt 0) { Fail ("inbox not empty ({0} file(s)) -- drain it before running" -f $n) }
    Say "  inbox empty"
}
function Assert-ClientLive {
    if (-not (Test-Path $LogPath)) { Fail "no run/logs/latest.log -- is runClient running?" }
    $age = ((Get-Date) - (Get-Item $LogPath).LastWriteTime).TotalSeconds
    if ($age -gt 120) { Fail ("latest.log is {0:N0}s old -- client not running / not ticking" -f $age) }
    $head = Read-FileShared $LogPath
    if ($head -notmatch 'bridge armed in') { Fail "no '[TacLight][agent] bridge armed in' in latest.log -- enter a world and wait for the bridge to arm" }
    Say ("  client live (latest.log {0:N0}s old, bridge armed)" -f $age)
}

# ---------------------------------------------------------------- main
$SessionName = 'builtin-perf-6arm'

# ---- optional spec-driven run (t-tyndall-matrix etc.): -Spec <json> -Session <id>
if (-not [string]::IsNullOrWhiteSpace($Spec)) {
    if ([string]::IsNullOrWhiteSpace($Session)) { Fail "-Spec requires -Session <id>" }
    if (-not (Test-Path -LiteralPath $Spec)) { Fail ("spec not found: {0}" -f $Spec) }
    $specRoot = Get-Content -LiteralPath $Spec -Raw -Encoding UTF8 | ConvertFrom-Json
    $sessions = @($specRoot.sessions)
    $ses = @($sessions | Where-Object { $_.id -eq $Session })
    if ($ses.Count -eq 0) {
        Fail ("spec has no session '{0}' (available: {1})" -f $Session, ((@($sessions | ForEach-Object { $_.id })) -join ','))
    }
    $ses = $ses[0]
    $Arms = @($ses.arms | ForEach-Object {
        [pscustomobject]@{
            Id          = [string]$_.id
            Ticket      = [string]$_.ticket
            Relay       = @($_.relay)
            Desc        = [string]$_.desc
            NeedLampOff = [bool]$_.needLampOff
            Camera      = $(if ($_.requireCamera) { [string]$_.requireCamera } else { 'any' })
            ShotPrefix  = $(if ($_.shotPrefix) { [string]$_.shotPrefix } else { 'opt-verify-' + [string]$_.id })
            PostRelay   = $(if ($_.postRelay) { @($_.postRelay) } else { @() })
            PostRestore = $(if ($_.postRestore) { @($_.postRestore) } else { @('!beamonly off') })
            PostTag     = $(if ($_.postTag) { [string]$_.postTag } else { 'beamonly' })
            MinLights   = $(if ($_.minLights) { [int]$_.minLights } else { 0 })
            MaxLights   = $(if ($null -ne $_.maxLights) { [int]$_.maxLights } else { -1 })
            RequireFlags = $(if ($_.requireFlags) { [int]$_.requireFlags } else { 0 })
        }
    })
    if ($ses.expectPng) { $ExpectPng = [string]$ses.expectPng }
    if ($ses.tailRelay) { $TailRelay = @($ses.tailRelay) }
    $SessionName = 'spec:' + [string]$ses.id
    Say ("spec loaded: {0} session={1} arms={2} expectPng={3}" -f $Spec, $ses.id, $Arms.Count, $(if ($ExpectPng) { $ExpectPng } else { '-' }))
}

$runStart = Get-Date

# ---- display mode capture (P0 requirement): IHDR is asserted per arm; options.txt records the mode.
$displayCfg = [ordered]@{}
$OptionsTxt = Join-Path $Run 'options.txt'
if (Test-Path -LiteralPath $OptionsTxt) {
    $otxt = Read-FileShared $OptionsTxt
    foreach ($k in 'fullscreen', 'overrideWidth', 'overrideHeight') {
        $mm = [regex]::Match($otxt, ("(?m)^{0}:(.+)$" -f $k))
        $displayCfg[$k] = $(if ($mm.Success) { $mm.Groups[1].Value.Trim() } else { '?' })
    }
    $displayCfg['options_txt_sha256'] = Get-Sha256 $OptionsTxt
    $displayCfg['hdr_proxy'] = $(if ($displayCfg['fullscreen'] -eq 'true') { 'off (PROXY, not measured: user reports system HDR is disabled while in fullscreen)' } else { 'on (PROXY, not measured: windowed per user report)' })
} else {
    $displayCfg['options_txt_sha256'] = '(missing options.txt)'
}
$displayCfg['expected_png'] = $(if ($ExpectPng) { $ExpectPng } else { '(not asserted)' })
Ensure-Dir $Evidence
Ensure-Dir (Join-Path $Evidence 'logs')
Ensure-Dir (Join-Path $Evidence 'results')

Say "== t-opt-verify / opt-verify-taclight =="
Say ("repo     : {0}" -f $Repo)
Say ("evidence : {0}" -f $Evidence)

if (-not $SkipPreconditions) {
    Say "-- preconditions"
    Assert-OculusConfig
    Assert-PackSynced
    Assert-InboxEmpty
    Assert-ClientLive
    # one-time snapshot of the log head so the frozen copy below is complete
    Copy-FileShared $LogPath (Join-Path $Evidence 'logs\session-latest.head.log') | Out-Null
    Say "  session log head frozen"
}

$summary = @()
foreach ($arm in $Arms) {
    Say ("== arm {0}: {1} ==" -f $arm.Id, $arm.Desc)
    if (-not $SkipPreconditions) { Assert-PackSynced }
    Clear-LogBuf
    $armLogStart = $script:LogOff
    # T18-D3: the display gate must only accept images produced by THIS arm run. A stale PNG with the same name
    # from an earlier round made the gate a false green (worse than a REJECT) -- so stamp the arm start here and
    # filter by LastWriteTime below.
    $armStartTime = Get-Date

    $ticketFile = Join-Path $Tickets ($arm.Ticket + '.json')
    if (-not (Test-Path -LiteralPath $ticketFile)) { Fail ("missing ticket {0}" -f $ticketFile) }

    Say ("  relay pre: {0}" -f ($arm.Relay -join ' | '))
    Write-Relay $arm.Relay

    $since = Get-Date
    $rr = Drop-Ticket $arm.Ticket $ticketFile $since
    $res = $rr.Json
    Say ("  result: ok={0} ops={1}" -f $res.ok, @($res.ops).Count)

    # ---- in-ticket lamp readback = arm acceptance (lead: A must be a true all-off baseline)
    $rows      = @($res.ops)
    $lampRow   = @($rows | Where-Object { $_.PSObject.Properties.Name -contains 'handheld' }) | Select-Object -Last 1
    $tailRow   = @($rows | Where-Object { $_.PSObject.Properties.Name -contains 'lampHandheld' }) | Select-Object -Last 1
    $lampOk    = $false
    $lampNote  = 'no lamp readback row'
    if ($lampRow) {
        $h = [bool]$lampRow.handheld; $g = [bool]$lampRow.gun
        if ($arm.NeedLampOff) {
            $lampOk = (-not $h) -and (-not $g)
            $lampNote = "handheld=$h gun=$g (want false/false)"
        } else {
            $lampOk = $h
            $lampNote = "handheld=$h gun=$g (want handheld=true; gun=false => single lamp, DECLARE it)"
        }
    }
    if ($tailRow) { $lampNote += " | tail handheld=$($tailRow.lampHandheld) gun=$($tailRow.lampGun)" }
    $resultOk = [bool]$res.ok
    Say ("  lamp readback: {0} -> {1}" -f $lampNote, $(if ($lampOk) { 'ACCEPT' } else { 'REJECT' }))

    # freeze result evidence
    $resCopy = Join-Path $Evidence ('results\{0}.result.json' -f $arm.Id)
    Copy-Item -LiteralPath $rr.Path -Destination $resCopy -Force

    # ---- display-mode gate: the PNG header must prove window vs fullscreen (B4-P0 requirement)
    $pngOk   = $true
    $pngNote = 'no PNG found'
    $shotPrefix = $arm.ShotPrefix
    $shotPaths = @(1..3 | ForEach-Object { Join-Path $Shots ('taclight-{0}-{1}.png' -f $shotPrefix, $_) })
    # T18-D3: fresh-only. Ignore any same-named PNG older than this arm's start (stale-round false green).
    $stale = @($shotPaths | Where-Object { (Test-Path -LiteralPath $_) -and ((Get-Item -LiteralPath $_).LastWriteTime -lt $armStartTime) })
    $fresh = @($shotPaths | Where-Object { (Test-Path -LiteralPath $_) -and ((Get-Item -LiteralPath $_).LastWriteTime -ge $armStartTime) })
    $staleNote = $(if ($stale.Count -gt 0) { (" [{0} stale PNG(s) ignored: {1}]" -f $stale.Count, (($stale | ForEach-Object { Split-Path $_ -Leaf }) -join ',')) } else { '' })
    $firstShot = $fresh | Select-Object -First 1
    if ($firstShot) {
        $sz = Get-PngSize $firstShot
        if ($ExpectPng) { $pngOk = ($sz -eq $ExpectPng); $pngNote = ("{0} (want {1}) [{2}/3 fresh]" -f $sz, $ExpectPng, $fresh.Count) + $staleNote }
        else { $pngNote = ("{0} (no expectation set) [{1}/3 fresh]" -f $sz, $fresh.Count) + $staleNote }
    } elseif ($ExpectPng) {
        $pngOk = $false
        $pngNote = ("no FRESH PNG at taclight-{0}-1.png (want {1}; {2})" -f $shotPrefix, $ExpectPng, $(if ($stale.Count -gt 0) { ("{0} stale PNG(s) present but ignored" -f $stale.Count) } else { 'none present either' }))
    }
    Say ("  display gate: {0} -> {1}" -f $pngNote, $(if ($pngOk) { 'ACCEPT' } else { 'REJECT' }))

    # ---- camera gate (B4): fresh DIAG BEFORE spending bench time; a polluted arm must not yield numbers
    Write-Relay @('!diag')
    Start-Sleep -Milliseconds 500
    Update-LogBuf
    $diagText = $script:LogBuf
    $diagLine = ''
    $dm = [regex]::Matches($diagText, '\[TacLight\] DIAG .*')
    if ($dm.Count -gt 0) { $diagLine = $dm[$dm.Count - 1].Value }
    $camGate = Assert-CameraGate $arm.Camera $res $diagText
    Say ("  camera gate: {0} -> {1}" -f $camGate.note, $(if ($camGate.ok) { 'ACCEPT' } else { 'REJECT' }))
    Say ("  diag: {0}" -f $diagLine)

    # ---- light-count gate (T8): prove the SSBO really carried N lights (>=5 = row_b1 branch reachable);
    # arm A (lights-off baseline) instead proves count==0.
    $lightsN = -1
    $lm = [regex]::Match($diagText, 'ssbo count=(\d+)')
    if ($lm.Success) { $lightsN = [int]$lm.Groups[1].Value }
    # T11 pre-review #3: the occl-table path only exists when the header flags carry bit4 (0x10);
    # without it the Java-side voxel grid is invalid and the numbers have nothing to do with row_b1.
    $flagsN = -1
    $fm = [regex]::Match($diagText, 'ssbo count=\d+ flags=0x([0-9a-fA-F]+)')
    if ($fm.Success) { try { $flagsN = [Convert]::ToInt32($fm.Groups[1].Value, 16) } catch { $flagsN = -1 } }
    $minL = [int]$arm.MinLights
    $maxL = [int]$arm.MaxLights
    $reqF = [int]$arm.RequireFlags
    $lightOk = $true
    if ($minL -gt 0 -and $lightsN -lt $minL) { $lightOk = $false }
    if ($maxL -ge 0 -and $lightsN -gt $maxL) { $lightOk = $false }
    if ($reqF -ne 0 -and (($flagsN -band $reqF) -ne $reqF)) { $lightOk = $false }
    $flagNote = $(if ($reqF -ne 0) { (" flags=0x{0:x} (want bit 0x{1:x})" -f $flagsN, $reqF) } else { (" flags=0x{0:x}" -f $flagsN) })
    $lightNote = ("ssbo count={0} (want {1}{2}){3}" -f $lightsN, $(if ($minL -gt 0) { ">=$minL" } else { '*' }), $(if ($maxL -ge 0) { " <=$maxL" } else { '' }), $flagNote)
    if ($minL -gt 0 -or $maxL -ge 0 -or $reqF -ne 0) { Say ("  light gate: {0} -> {1}" -f $lightNote, $(if ($lightOk) { 'ACCEPT' } else { 'REJECT' })) }

    # ---- BUG2 fix: a rejected gate ABORTS the arm now -- no post-shot, no bench, no shots kept as valid
    if (-not ($resultOk -and $lampOk -and $camGate.ok -and $pngOk -and $lightOk)) {
        Update-LogBuf
        $abortSlice = Save-LogSlice $armLogStart $script:LogOff (Join-Path $Evidence ('logs\{0}.log.txt' -f $arm.Id))
        Clear-LogBuf
        $summary += [pscustomobject]@{
            arm            = $arm.Id
            desc           = $arm.Desc
            relay_pre      = ($arm.Relay -join ' | ')
            ticket         = $arm.Ticket
            result_file    = $resCopy
            result_ok      = $resultOk
            lamp_note      = $lampNote
            lamp_accept    = $lampOk
            camera_mode    = $arm.Camera
            camera_note    = $camGate.note
            camera_accept  = $camGate.ok
            light_note     = $lightNote
            light_accept   = $lightOk
            lights         = $lightsN
            png_note       = $pngNote
            png_accept     = $pngOk
            arm_accept     = $false
            aborted        = $true
            shots_valid    = $false
            ticket_bench_instant = @($rows | Where-Object { $_.op -eq 'bench' } | ForEach-Object { $_.line })
            relay_bench    = @()
            relay_bench_avg= @()
            relay_median   = $null
            diag           = $diagLine
            post_shot      = $null
            shots          = @()
            log_slice      = $abortSlice
        }
        Say ("  !! arm {0} ABORTED at the gate (result={1} lamp={2} png={3} camera={4} lights={5}) -- no post-shot / no bench / ticket shots marked INVALID and excluded from the judgment table" -f $arm.Id, $resultOk, $lampOk, $pngOk, $camGate.ok, $lightOk)
        continue
    }

    # ---- optional post-shot under a different relay state (e.g. !beamonly on) -> isolates the volume beam
    $postShot = $null
    if ($arm.PostRelay -and @($arm.PostRelay).Count -gt 0) {
        $postSince = (Get-Date).AddSeconds(-2)
        Write-Relay @($arm.PostRelay)
        Write-Relay @('!shot')
        Start-Sleep -Milliseconds 900
        $cand = @(Get-ChildItem -Path $Shots -Filter '*.png' -File -ErrorAction SilentlyContinue |
                Where-Object { $_.LastWriteTime -gt $postSince } | Sort-Object LastWriteTime)
        if ($cand.Count -gt 0) {
            $pf = $cand[$cand.Count - 1].FullName
            $postDest = Join-Path $Evidence ('postshots\{0}-{1}.png' -f $arm.Id, $arm.PostTag)
            Ensure-Dir (Split-Path $postDest -Parent)
            Copy-FileShared $pf $postDest | Out-Null
            $postShot = [pscustomobject]@{ tag = $arm.PostTag; relay = (@($arm.PostRelay) -join ' | '); file = $pf; copy = $postDest; size = (Get-PngSize $pf); sha256 = (Get-Sha256 $pf) }
            Say ("  post-shot [{0}] {1} ({2})" -f $arm.PostTag, (Split-Path $pf -Leaf), $postShot.size)
        } else {
            Say ("  WARN post-shot PNG not found for arm {0} (relay !shot)" -f $arm.Id)
        }
        if ($arm.PostRestore -and @($arm.PostRestore).Count -gt 0) { Write-Relay @($arm.PostRestore) }
    }

    # ---- rigorous bench: warmup (discarded) + BenchReps measured, relay-driven 3s windows
    for ($w = 0; $w -lt $BenchWarmup; $w++) {
        Write-Relay @('!bench')
        Wait-Log '\[TacLight\] BENCH frames=\d+ avgFPS=' 30 'bench warmup result' | Out-Null
        Say "  bench warmup (discarded) done"
    }
    $benchVals = @()
    $benchLines = @()
    for ($i = 1; $i -le $BenchReps; $i++) {
        Write-Relay @('!bench')
        $line = Wait-Log '\[TacLight\] BENCH frames=\d+ avgFPS=[0-9.]+ onePctLow=[0-9.]+ minFPS=[0-9.]+' 30 'bench result'
        $m = [regex]::Match($line, 'avgFPS=([0-9.]+) onePctLow=([0-9.]+) minFPS=([0-9.]+) frames=(\d+)')
        if (-not $m.Success) { $m = [regex]::Match($line, 'frames=(\d+) avgFPS=([0-9.]+) onePctLow=([0-9.]+) minFPS=([0-9.]+)') }
        $benchLines += $line
        $v = [double]([regex]::Match($line, 'avgFPS=([0-9.]+)').Groups[1].Value)
        $benchVals += $v
        Say ("  bench {0}/{1}: avgFPS={2}" -f $i, $BenchReps, $v)
    }
    $benchMedian = Get-Median $benchVals

    # ---- freeze this arm's log slice
    Update-LogBuf
    $slice = Save-LogSlice $armLogStart $script:LogOff (Join-Path $Evidence ('logs\{0}.log.txt' -f $arm.Id))
    Clear-LogBuf

    $armShots = @()
    foreach ($shotPath in $shotPaths) {
        if (Test-Path -LiteralPath $shotPath) { $armShots += [pscustomobject]@{ file = $shotPath; sha256 = (Get-Sha256 $shotPath) } }
    }
    $armOk = ($resultOk -and $lampOk -and $camGate.ok -and $pngOk -and $lightOk)

    $summary += [pscustomobject]@{
        arm            = $arm.Id
        desc           = $arm.Desc
        relay_pre      = ($arm.Relay -join ' | ')
        ticket         = $arm.Ticket
        result_file    = $resCopy
        result_ok      = $resultOk
        lamp_note      = $lampNote
        lamp_accept    = $lampOk
        camera_mode    = $arm.Camera
        camera_note    = $camGate.note
        camera_accept  = $camGate.ok
        light_note     = $lightNote
        light_accept   = $lightOk
        lights         = $lightsN
        png_note       = $pngNote
        png_accept     = $pngOk
        arm_accept     = $armOk
        aborted        = $false
        shots_valid    = $true
        ticket_bench_instant = @($rows | Where-Object { $_.op -eq 'bench' } | ForEach-Object { $_.line })
        relay_bench    = $benchLines
        relay_bench_avg= $benchVals
        relay_median   = $benchMedian
        diag           = $diagLine
        post_shot      = $postShot
        shots          = $armShots
        log_slice      = $slice
    }
    if (-not $armOk) {
        Say ("  !! arm {0} FAILED acceptance (result_ok={1} lamp={2} camera={3} png={4}) -- numbers NOT usable" -f $arm.Id, $resultOk, $lampOk, $camGate.ok, $pngOk)
    }
}

# ---------------------------------------------------------------- tail + freeze
Say "== tail reset =="
try { Write-Relay $TailRelay } catch { Say ("WARN tail relay failed: {0}" -f $_.Exception.Message) }

Say "== freeze session logs =="
$frozen = @()
$cands = @(Get-ChildItem -Path $LogDir -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $runStart })
if (Test-Path $LogPath) { $cands = @($cands + (Get-Item $LogPath)) | Sort-Object FullName -Unique }
foreach ($f in $cands) {
    $dest = Join-Path $Evidence ('logs\frozen\{0}' -f $f.Name)
    Ensure-Dir (Split-Path $dest -Parent)
    Copy-FileShared $f.FullName $dest | Out-Null
    $frozen += [pscustomobject]@{ file = $dest; bytes = (Get-Item $dest).Length; sha256 = (Get-Sha256 $dest) }
    Say ("  frozen {0} ({1} B) sha256={2}" -f $f.Name, (Get-Item $dest).Length, (Get-Sha256 $dest))
}

$aborted = @($summary | Where-Object { $_.aborted })
$table = @($summary | Where-Object { -not $_.aborted } |
    ForEach-Object { [pscustomobject]@{ arm = $_.arm; relay_median_avgFPS = $(if ($_.relay_median -ne $null) { [math]::Round($_.relay_median, 1) } else { $null }); ms = $(if ($_.relay_median -ne $null) { [math]::Round(1000.0 / $_.relay_median, 2) } else { $null }); result_ok = $_.result_ok; lamp_accept = $_.lamp_accept; camera_accept = $_.camera_accept; png_accept = $_.png_accept; arm_accept = $_.arm_accept } })

$out = [pscustomobject]@{
    ticket      = $SessionName
    generated   = (Get-Date).ToString('s')
    session     = $SessionName
    expect_png  = $ExpectPng
    scoped      = 'old TacLight rig / save=test / scene=wall / pose=wall_front(2000.0,121.5,8.0,yaw180,pitch5) / midnight+clear+doDaylightCycle false+doMobSpawning false / wait 20 / single client. Window vs fullscreen is part of the run: the per-arm PNG header is asserted against expect_png. Camera mode is asserted per arm (first|third|freecam|any).'
    bench_protocol = ("PRIMARY = relay !bench x{0} discarded + x{1} measured (3s nominal windows, ClientEvents.java:176-211), median. SECONDARY ONLY = in-ticket bench op (TicketBridge.java:967-974 mc.getFps() instantaneous) -- reported as ticket_bench_instant and never used as the headline number." -f $BenchWarmup, $BenchReps)
    live_pack   = $LivePack
    display_config = $displayCfg
    arms        = $summary
    table       = $table
    aborted_arms = @($aborted | ForEach-Object { [pscustomobject]@{ arm = $_.arm; reason = ("result={0} lamp={1} png={2} camera={3} :: {4}" -f $_.result_ok, $_.lamp_accept, $_.png_accept, $_.camera_accept, $_.camera_note) } })
    frozen_logs = $frozen
}
$jsonPath = Join-Path $Evidence ('{0}-summary.json' -f $(if ($Session -ne '') { $Session } else { 'opt-verify' }))
Write-Utf8NoBom $jsonPath ($out | ConvertTo-Json -Depth 8)
Say ("summary json -> {0}" -f $jsonPath)

$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine(('# run summary: {0}' -f $SessionName))
[void]$md.AppendLine('')
[void]$md.AppendLine(("- generated: {0}" -f (Get-Date).ToString('s')))
[void]$md.AppendLine(("- live pack: {0} | repo: {1}" -f $LivePack, $Repo))
[void]$md.AppendLine(("- expect PNG: {0}" -f $(if ($ExpectPng) { $ExpectPng } else { '(not asserted)' })))
[void]$md.AppendLine(("- display: fullscreen={0} override={1}x{2} HDR={3} options.txt sha256={4}" -f $displayCfg['fullscreen'], $displayCfg['overrideWidth'], $displayCfg['overrideHeight'], $displayCfg['hdr_proxy'], $displayCfg['options_txt_sha256']))
[void]$md.AppendLine(("- bench protocol: {0}" -f $out.bench_protocol))
[void]$md.AppendLine('')
[void]$md.AppendLine('| arm | relay median avgFPS (PRIMARY) | ms | result | lamp | camera | png | arm |')
[void]$md.AppendLine('|---|---|---|---|---|---|---|---|')
foreach ($r in $table) { [void]$md.AppendLine(("| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} |" -f $r.arm, $r.relay_median_avgFPS, $r.ms, $r.result_ok, $r.lamp_accept, $r.camera_accept, $r.png_accept, $r.arm_accept)) }
[void]$md.AppendLine('')
if ($aborted.Count -gt 0) {
    [void]$md.AppendLine(('## ABORTED arms -- INVALID, excluded from the judgment table ({0})' -f $aborted.Count))
    [void]$md.AppendLine('')
    [void]$md.AppendLine('| arm | reason | ticket shots on disk are INVALID |')
    [void]$md.AppendLine('|---|---|---|')
    foreach ($a in $aborted) { [void]$md.AppendLine(('| {0} | result={1} lamp={2} png={3} camera={4} :: {5} | yes |' -f $a.arm, $a.result_ok, $a.lamp_accept, $a.png_accept, $a.camera_accept, $a.camera_note)) }
    [void]$md.AppendLine('')
}
[void]$md.AppendLine('Camera gate = cam(x,y,z) from `[TacLight] DIAG` compared with the ticket pose (first: <=0.8 horiz + eye height; third: within 8 blocks; freecam: `DIAG-REMOTE ... self=false` OR a camera detached from the player eye; while waiting for F4 the driver re-probes `!diag` every 2s). Display gate = the actual PNG header size. A rejected arm is ABORTED on the spot: no post-shot, no bench, its ticket shots are not listed as valid.')
[void]$md.AppendLine('Raw per-arm bench lines, log slices and frozen log copies are next to this file (logs/, results/).')
$mdPath = Join-Path $Evidence ('{0}-summary.md' -f $(if ($Session -ne '') { $Session } else { 'opt-verify' }))
Write-Utf8NoBom $mdPath $md.ToString()
Say ("summary md -> {0}" -f $mdPath)

$bad = @($summary | Where-Object { -not $_.arm_accept })
if ($bad.Count -gt 0) {
    Say ("DONE with {0} rejected arm(s): {1}" -f $bad.Count, (($bad | ForEach-Object { $_.arm }) -join ','))
    exit 1
}
Say ("DONE: {0}/{0} arms accepted" -f $summary.Count)
exit 0
