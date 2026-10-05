# T19 direction self-test that can FALSIFY the implementation (ASCII only; PS 5.1 + pwsh).
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\agent-dir-selftest.ps1
#   ... -MutationDemo        # deliberately break each canonical line -> assertions MUST fail (proof of falsifiability)
#
# Why source assertions: an earlier version INLINED a copy of the projection formula, so flipping the sign in
# AgentInput.java still passed ("looks verified, verified nothing" -- pre-review). The projection now lives once, in
# AgentInput.comboFor(), and this script asserts the SOURCE TEXT of the canonical lines. The numeric table is a
# hand-written ORACLE of the expected semantics. -MutationDemo proves the assertions actually fire.
param(
    [string]$Source = (Join-Path (Split-Path $PSScriptRoot -Parent) 'src\main\java\dev\taclight\debug\AgentInput.java'),
    [switch]$MutationDemo
)
$ErrorActionPreference = 'Stop'
$script:fail = 0
function Get-Src([string]$p) { if (-not (Test-Path -LiteralPath $p)) { throw "source not found: $p" }; return [System.IO.File]::ReadAllText($p) }
function Assert-Source([string]$src, [string]$needle, [string]$label) {
    $ok = $src.Contains($needle); if (-not $ok) { $script:fail++ }
    Write-Host ("  {0} {1}" -f $(if ($ok) { 'OK  ' } else { 'FAIL' }), $label)
}
function Check-Source([string]$src) {
    $script:fail = 0
    Write-Host '== source assertions on the canonical projection =='
    Assert-Source $src 'static int[] comboFor(float yawDeg, double dx, double dz)' 'comboFor() exists (single shared implementation)'
    Assert-Source $src 'double fx = -Math.sin(yaw), fz = Math.cos(yaw);' 'forward basis = (-sin, cos) [yaw0 -> +Z]'
    Assert-Source $src 'double lx = Math.cos(yaw), lz = Math.sin(yaw);' 'LEFT basis = (cos, sin) [yaw0 -> +X]'
    Assert-Source $src 'return quantize(dx * fx + dz * fz, dx * lx + dz * lz);' 'projection has NO negation on the left term (T18 sign bug)'
    Assert-Source $src 'q = comboFor(mc.player.getYRot(), dx, dz);' 'onClientTick USES comboFor (no duplicate formula)'
    Assert-Source $src 'if (holdLeft) { q = new int[] { 0, 1 }; }' 'hold left -> (0,+1)'
    Assert-Source $src 'else if (holdRight) { q = new int[] { 0, -1 }; }' 'hold right -> (0,-1)'
    Assert-Source $src 'if (!holdForward && !holdLeft && !holdRight) {' 'progress loop excludes ALL hold modes (T19-fix1)'
    Assert-Source $src 'holdForward = false; holdJump = false; holdLeft = false; holdRight = false;' 'goto clears holdJump too (T19-fix2)'
    Assert-Source $src 'holdLeft = false;' 'stop() restores holdLeft'
    Assert-Source $src 'faceMove = false;' 'stop() restores faceMove'
    Assert-Source $src 'AGENT yaw restored on stop' 'stop() restores the yaw face=true changed (T19-fix4)'
    Assert-Source $src 'if (!probeOn) return;' 'probe gate defaults to OFF'
    Assert-Source $src 'effective(tick-end)={}' 'sprint reporting separates attempted vs effective (tick-end value kept)'
    Assert-Source $src 'AGENT sprint confirm: t={} moving={} moved={} (min {}) attempted={}' 'sprint confirm carries the shared tick counter t='
    Assert-Source $src 'AGENT probe t={} {}:' 'probe lines carry the shared tick counter t='
    Assert-Source $src 'private static final int PROBE_EVERY = 20;' 'probe throttling constant (T19-probe)'
    Assert-Source $src 'if (!emitThisTick) return;' 'probe lines are throttled by emitThisTick'
    Assert-Source $src 'emitThisTick = probeFull || changed || (tickCounter % PROBE_EVERY == 0);' 'throttle = full OR flag-change OR every 20 ticks'
    Assert-Source $src 'public static boolean stopSprint' 'TaCZ latch documented as a static FIELD (not a method)'
    return $script:fail
}
function Check-Oracle {
    $bad = 0
    Write-Host '== numeric oracle (hand-computed expectations) =='
    $rows = @(
        @{ n = 'yaw0   +x'; y = 0;   dx = 1; dz = 0;  f = 0;  l = 1 },
        @{ n = 'yaw0   -x'; y = 0;   dx = -1; dz = 0; f = 0;  l = -1 },
        @{ n = 'yaw0   +z'; y = 0;   dx = 0; dz = 1;  f = 1;  l = 0 },
        @{ n = 'yaw0   -z'; y = 0;   dx = 0; dz = -1; f = -1; l = 0 },
        @{ n = 'yaw180 +x'; y = 180; dx = 1; dz = 0;  f = 0;  l = -1 },
        @{ n = 'yaw180 -x'; y = 180; dx = -1; dz = 0; f = 0;  l = 1 },
        @{ n = 'yaw90  +x'; y = 90;  dx = 1; dz = 0;  f = -1; l = 0 },
        @{ n = 'yaw90  +z'; y = 90;  dx = 0; dz = 1;  f = 0;  l = 1 },
        @{ n = 'yaw0 diag '; y = 0;  dx = 0.7071; dz = 0.7071; f = 1; l = 1 }
    )
    foreach ($r in $rows) {
        $yaw = $r.y * [math]::PI / 180.0
        $f = $r.dx * (-[math]::Sin($yaw)) + $r.dz * [math]::Cos($yaw)
        $l = $r.dx * [math]::Cos($yaw) + $r.dz * [math]::Sin($yaw)
        $qf = [int][math]::Round([math]::Max(-1.0, [math]::Min(1.0, $f)))
        $ql = [int][math]::Round([math]::Max(-1.0, [math]::Min(1.0, $l)))
        if ($qf -eq 0 -and $ql -eq 0) { if ([math]::Abs($f) -ge [math]::Abs($l)) { $qf = if ($f -ge 0) { 1 } else { -1 } } else { $ql = if ($l -ge 0) { 1 } else { -1 } } }
        $ok = ($qf -eq $r.f -and $ql -eq $r.l); if (-not $ok) { $bad++ }
        Write-Host ("  {0} {1,-11} -> ({2,2},{3,2}) want ({4,2},{5,2})" -f $(if ($ok) { 'OK  ' } else { 'FAIL' }), $r.n, $qf, $ql, $r.f, $r.l)
    }
    Write-Host '== face=true formula: wantYaw = atan2(-dx,dz) points the forward basis at (dx,dz) =='
    foreach ($d in @(@(1, 0), @(-1, 0), @(0, 1), @(0, -1), @(0.7071, 0.7071))) {
        $want = [math]::Atan2(-$d[0], $d[1])
        $dot = $d[0] * (-[math]::Sin($want)) + $d[1] * [math]::Cos($want)
        $ok = $dot -gt 0.9999; if (-not $ok) { $bad++ }
        Write-Host ("  {0} dir=({1,7},{2,7}) dotForward={3:N4}" -f $(if ($ok) { 'OK  ' } else { 'FAIL' }), $d[0], $d[1], $dot)
    }
    return $bad
}
if ($MutationDemo) {
    $src = Get-Src $Source
    Write-Host '== MUTATION DEMO: every deliberate break MUST be caught =='
    $muts = @(
        @{ n = 'sign flipped (left term negated)'; from = 'return quantize(dx * fx + dz * fz, dx * lx + dz * lz);'; to = 'return quantize(dx * fx + dz * fz, -(dx * lx + dz * lz));' },
        @{ n = 'axis swapped (left basis replaced by forward basis)'; from = 'double lx = Math.cos(yaw), lz = Math.sin(yaw);'; to = 'double lx = -Math.sin(yaw), lz = Math.cos(yaw);' },
        @{ n = 'progress guard loses holdForward'; from = 'if (!holdForward && !holdLeft && !holdRight) {'; to = 'if (!holdLeft && !holdRight) {' },
        @{ n = 'goto forgets holdJump'; from = 'holdForward = false; holdJump = false; holdLeft = false; holdRight = false;'; to = 'holdForward = false; holdLeft = false; holdRight = false;' },
        @{ n = 'probe gate removed (probes always on)'; from = 'if (!probeOn) return;'; to = 'if (false) return;' },
        @{ n = 'onClientTick re-inlines the formula'; from = 'q = comboFor(mc.player.getYRot(), dx, dz);'; to = 'q = quantize(fwd, left);' },
        @{ n = 'sprint reporting loses the tick-end value'; from = 'effective(tick-end)={}'; to = 'effective()={}' }
    )
    $caught = 0
    foreach ($m in $muts) {
        $bad = $src.Replace($m.from, $m.to)
        if ($bad -eq $src) { Write-Host ("  FAIL {0} (mutation did not apply)" -f $m.n); continue }
        [void](Check-Source $bad)
        if ($script:fail -gt 0) { $caught++; Write-Host ("  OK   {0} -> CAUGHT ({1} assertion(s) fired)" -f $m.n, $script:fail) }
        else { Write-Host ("  FAIL {0} -> MISSED (no assertion fired!)" -f $m.n) }
    }
    Write-Host ("RESULT: {0}/{1} mutations caught" -f $caught, $muts.Count)
    if ($caught -ne $muts.Count) { exit 1 }
    exit 0
}
$src = Get-Src $Source
$f1 = Check-Source $src
$f2 = Check-Oracle
if (($f1 + $f2) -gt 0) { Write-Host ("RESULT: {0} assertion(s) FAILED" -f ($f1 + $f2)); exit 1 }
Write-Host 'RESULT: all direction assertions passed (source + oracle)'
exit 0
