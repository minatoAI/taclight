# T11/T15/T17/T19 synth+paired spec self-consistency checker (ASCII only; PS 5.1 + pwsh).
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-synth-spec.ps1 [-Spec tickets\t-opt-synth-matrix.json]
#
# Invariants (fixed regression surface of this spec pipeline):
#   1 relay-derived "!synth N" (token parser; handles [dist] and [mode=arc|slots])
#   2 ticket internals agree with the spec (synthN/totalCount/minLights/maxLights/requireFlags/dist/mode)
#   3 lights-off arm => synth=0 && total=0 ; lights-on => total == 2+synth, minLights == total, maxLights == -1
#   4 selflight/gun switches agree; requireFlags == 16 iff "!occl on"
#   5 shotPrefix == ticket.shot_prefix (T12 bug#2: driver looks up the spec prefix, the game names from the ticket)
#   6 ticket.internal == ticket filename stem == spec.arm.ticket (T19: bridge writes outbox\<internal>.result.json
#     while the driver waits on the stem -> mismatch = 300s timeout / exit 2)
#   7 shot names start with shotPrefix AND are unique within the session (T19: two arms overwrote each other)
#   8 paired designs (_paired=true, or arms carrying a `pair` field) need a DECLARED back-to-back identical-config
#     noise pair (pair=anoise / roles x|y, adjacent, identical relay) -> the only valid noise scale (D_adj)
#   A spec may declare _synth=false (e.g. the action-layer demo): then only the ticket/shot invariants apply.
#  10 (D2/T20, added 2026-09-17) world-mutation ops must DECLARE their probe cell and the ticket must carry
#     in-band block probes around the op: a pre-probe "execute if block X Y Z minecraft:air" (the declared
#     replaceability basis) BEFORE it and a post-probe "execute if block X Y Z minecraft:<non-air>" AFTER it
#     (the independent world-change proof). Covered: act verb=place (cell = its own x/y/z) and
#     use target=block (cell = the declared _probe_cell, i.e. where the placement lands).
#     Rationale: a receipt that self-reports ok=True is not proof that the world changed.
#  11 (D2/T20) each inv action=equip must declare an OBSERVABLE armor-value transition
#     (_expect_armor_before / _expect_armor_after, integers, before != after), must declare the two
#     slot contents it swaps (_expect_slot_source / _expect_slot_armor, and they must DIFFER -- a
#     same-item swap makes the confirm predicate tautological), and must be bracketed by state
#     readbacks: "state what=armor" AND a slot-content snapshot ("state what=all|inv").
#     Rationale: the D2 pause saw backpack s1 and armor slot 38 both hold diamond_chestplatex1, so the
#     swap reported "armor 8 -> 8" -- and InvActions' confirm predicate was satisfied by the no-op.
#  12 (D2/T20) SPEC-level: exactly ONE displacement threshold may be declared (">=4 blocks" vs
#     ">=3.0 blocks" in one _judgment was a self-contradiction the checker could not see).
#     ARM-level: a face=true "!agent goto" arm MUST NOT carry a pose op at all -- pose always pins
#     yaw+pitch and its settle loop fights the action layer's turn (B4: "settle timeout want yaw=180.00
#     but yaw=-90.00"). Place A with {"op":"command","line":"tp @s x y z ~ <pitch>"} (yaw untouched)
#     or send the goto with face=false (face=false arms keep their pose on purpose, frozen yaw).
#  12c (D2/T20, lead-approved) every movement arm ("!agent goto") MUST carry an explicit position anchor
#     for A: a pose op with x/y/z, or a "command" tp with explicit coordinates. A face=true arm may only
#     anchor via tp and its yaw segment MUST be '~' (an explicit yaw re-pins the value the action layer
#     overwrites). Why: T18/D4/D5 "accepted but zero displacement" (moved=0.000, pos unchanged) came from
#     an unpinned/unsatisfiable start A; inv12b removes the pose, inv12c keeps A pinned by other means.
#
# D7 (2026-09-17, pre-reviewer phase5 P2-6: their 12 self-chosen mutations caught only 3). Closes the nine
# "toothless" spots. Every rule below is proven by ONE mutation that turns the new spec red (see the D7
# report for the 9/9 mutation table). Behavior on the frozen T17 baseline is bit-for-bit unchanged
# (checker-red-on-frozen-old-spec.txt stays 1,487 B / 42F6BBA9...).
#  12a-lb a movement spec (any "!agent goto" arm) MUST declare >=1 displacement threshold; the old rule only
#     failed when a SECOND one appeared, so deleting every ">=N blocks" text passed silently.
#  10a    _probe_cell must be bound to the op it claims to prove: place => (x,y,z); use-block => the clicked
#     face cell (x, y+1, z). The place-side cross-check used to be DEAD CODE (its guard variable was only
#     set in the use-block branch), so a bogus _probe_cell passed.
#  10b    a post-probe only proves a world change if the EXPECTED block is declared: '_expect_block_after'
#     (ticket) or '_probe_block_after' (op), else a literal "execute if block <cell> minecraft:<block>"
#     claim in the ticket text. Probes present with no expectation => red; mismatch => red. (Before: any
#     non-air block passed, so switching the probe to quartz_block was invisible.)
#  11b    the declared slot contents must ARITHMETICALLY match the declared armor transition: the slot named
#     by _expect_slot_armor holds the piece BEFORE, _expect_slot_source is the piece moving in =>
#     after == before - armorPoints(slotArmor item) + armorPoints(slotSource item). Swapping the two
#     _expect_slot_* values (still "different", still "observable") is now red.
#  12c-A  the anchor must BE A, not merely exist: anchor coords must equal the arm's 'anchor' declaration or
#     the scoped "start A = <name>(x,y,z" value, and must NOT equal the goto target (an anchor on B).
#  13     (discipline-gated) a ticket that mutates the world must carry a runtime `assert` op: _expect_*
#     prose is a declaration, not a judgment. Gate = spec._discipline.assert_after_world_mutation.
#  14     (discipline-gated) the arm that mutates the world must be the session's LAST arm; the spec prose
#     already PROMISES "runs LAST ... cannot touch the movement arms" and now the promise is machine-checked.
#     Gate = spec._discipline.world_mutation_arm_last; the prose claim without the flag is itself a red.
#  15     every arm needs an EXPLICIT lamp readback ("state what=lamp"): the driver lamp gate needs the
#     lamp-specific reading (phase5 b3) and T18's first material defect was a lamp gate without readback.
#     Unconditional: every ticket referenced by any spec in this repo already carries one.
# Known limits (honest): rules 13/14 are opt-in, so deleting _discipline disables them (the "runs LAST"
#   prose cross-check still fires); if no "start A"/anchor value exists at all the anchor-vs-A equality is
#   skipped; unknown armor item names are not bound (only the vanilla chest-piece table).
#
# DISCIPLINE (three PowerShell traps burned in this session -- never repeat):
#   * NEVER patch code with [regex]::Replace($t,$pat,$ins+'$0'): in a .NET replacement string `$_` expands to the
#     WHOLE input (it inlined this file into itself and broke the parse). Use a MatchEvaluator:
#     [regex]::Replace($t,$pat,{param($m) $ins + $m.Value})
#   * variable names are case-insensitive: $T/$t and $Spec/$spec are the SAME variable (this silently ate a table)
#   * files: JSON without BOM, .ps1 pure ASCII (or UTF-8 with BOM); verify via [System.IO.File]::ReadAllBytes
param(
    [string]$Spec = (Join-Path (Split-Path $PSScriptRoot -Parent) 'tickets\t-opt-synth-matrix.json')
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Spec)) { Write-Host "spec not found: $Spec"; exit 2 }
$ticketDir = Join-Path (Split-Path $Spec -Parent) ''
$specObj = Get-Content -LiteralPath $Spec -Raw -Encoding UTF8 | ConvertFrom-Json

function Parse-SynthLine([string]$line) {
    if ($line -notmatch '^!synth(?:\s+(.*))?$') { return $null }
    $rest = $Matches[1]
    if ($null -eq $rest -or $rest.Trim() -eq '') { return [pscustomobject]@{ n = -1; dist = 0.0; mode = 'arc' } }
    $n = -1; $dist = 0.0; $mode = 'arc'
    foreach ($tok in ($rest.Trim() -split '\s+')) {
        if ($tok -match '^dist=(\d+(?:\.\d+)?)$') { $dist = [double]$Matches[1] }
        elseif ($tok -match '^mode=(arc|slots)$') { $mode = $Matches[1] }
        elseif ($tok -match '^\d+$' -and $n -lt 0) { $n = [int]$tok }
        elseif ($tok -match '^\d+(?:\.\d+)?$') { $dist = [double]$tok }
        else { return [pscustomobject]@{ n = -2; dist = 0.0; mode = 'bad:' + $tok } }
    }
    return [pscustomobject]@{ n = $n; dist = $dist; mode = $mode }
}

$fail = 0
$script:shotSeen = @{}
$nonSynth = (@($specObj.PSObject.Properties.Name) -contains '_synth') -and ($specObj._synth -eq $false)

# ---- inv12a (spec level): exactly ONE declared displacement threshold (T20 self-contradiction fix) ----
$judgeStrings = @()
if ($null -ne $specObj._what) { $judgeStrings += [string]$specObj._what }
if ($null -ne $specObj._prereq) { $judgeStrings += [string]$specObj._prereq }
if ($null -ne $specObj._judgment) {
    foreach ($judgeProp in $specObj._judgment.PSObject.Properties) {
        if ($judgeProp.Value -is [string]) { $judgeStrings += [string]$judgeProp.Value }
    }
}
$dispThresholds = @()
foreach ($judgeString in $judgeStrings) {
    foreach ($thrMatch in [regex]::Matches($judgeString, '>=\s*([0-9]+(?:\.[0-9]+)?)\s*blocks?\b')) {
        $dispThresholds += [double]$thrMatch.Groups[1].Value
    }
}
$dispUnique = @($dispThresholds | Sort-Object -Unique)
if ($dispUnique.Count -gt 1) {
    $fail++
    Write-Host ("   FAIL (spec) conflicting displacement thresholds declared: {0} -- declare exactly one (T20: '>=4 blocks' in _judgment.moved vs '>=3.0 blocks' in _judgment.thresholds was a self-contradiction)" -f (($dispUnique | ForEach-Object { $_.ToString() }) -join ' vs '))
}
# ---- inv12a LOWER BOUND (D7 / pre-reviewer P2-6-7, their mutation M12): the old rule only failed
# when Count > 1, so deleting EVERY '>=N blocks' text passed silently. A movement spec with no
# declared threshold has no acceptance number at all. Trigger = the spec really has '!agent goto'
# arms, so non-movement specs (synth-matrix / paired / t16 / tyndall / 8l) are untouched.
$hasGotoArm = $false
foreach ($sesProbe in @($specObj.sessions)) {
    foreach ($armProbe in @($sesProbe.arms)) {
        if (@($armProbe.relay) -match '^!agent\s+goto\b') { $hasGotoArm = $true }
    }
}
if ($hasGotoArm -and $dispUnique.Count -eq 0) {
    $fail++
    Write-Host "   FAIL (spec) movement spec (!agent goto arm present) declares NO displacement threshold -- add exactly one '>=N blocks' to _judgment (D7 inv12a lower bound; otherwise deleting every threshold passes silently)"
}

# ---- D7 declarations used by the new invariants ----
# A point: arm-level 'anchor' (preferred) or the scoped text 'start A = <name>(x,y,z...)'.
$declaredA = $null
if ($null -ne $specObj.scoped) {
    $scopedText = [string]$specObj.scoped
    $aMatch = [regex]::Match($scopedText, 'start\s+A\s*=\s*[^()]*\(\s*(-?[0-9]+(?:\.[0-9]+)?)\s*,\s*(-?[0-9]+(?:\.[0-9]+)?)\s*,\s*(-?[0-9]+(?:\.[0-9]+)?)')
    if ($aMatch.Success) {
        $declaredA = @([double]$aMatch.Groups[1].Value, [double]$aMatch.Groups[2].Value, [double]$aMatch.Groups[3].Value)
    } elseif ($scopedText -match 'start\s+A') {
        $fail++
        Write-Host "   FAIL (spec) scoped text claims a start A but its coordinates are not parseable as 'start A = <name>(x,y,z' -- the anchor cannot be judged against A (D7 inv12c-A)"
    }
}
# _discipline: optional spec-level opt-in for the order / assert-coverage rules.
$disciplineObj = $null
if ($null -ne $specObj._discipline) { $disciplineObj = $specObj._discipline }
$discWorldLast = ($null -ne $disciplineObj) -and (@($disciplineObj.PSObject.Properties.Name) -contains 'world_mutation_arm_last') -and ([bool]$disciplineObj.world_mutation_arm_last)
$discAssert = ($null -ne $disciplineObj) -and (@($disciplineObj.PSObject.Properties.Name) -contains 'assert_after_world_mutation') -and ([bool]$disciplineObj.assert_after_world_mutation)
# Claim cross-check: prose that PROMISES the discipline must be backed by the machine-readable flag,
# otherwise deleting the flag would silently disable the rule (the old T17 baseline makes no such claim).
$specClaimText = ($judgeStrings -join "`n")
foreach ($sesClaim in @($specObj.sessions)) {
    foreach ($armClaim in @($sesClaim.arms)) {
        if ($null -ne $armClaim.desc) { $specClaimText += "`n" + [string]$armClaim.desc }
    }
}
if ([regex]::IsMatch($specClaimText, '(?i)runs?\s+last|mutations?\s+cannot\s+touch') -and -not $discWorldLast) {
    $fail++
    Write-Host "   FAIL (spec) spec text claims 'runs LAST / world mutations cannot touch the movement arms' but _discipline.world_mutation_arm_last is not declared -- a prose promise must be machine-checked (D7 inv14)"
}

foreach ($ses in @($specObj.sessions)) {
    # inv7 must be SESSION-scoped: several sessions intentionally reuse the same tickets
    # (T12 matrix V800/V480) and their PNGs live in separate evidence runs.
    $script:shotSeen = @{}   # per-session scope
    Write-Host ("-- session {0} ({1} arms, expectPng {2}){3}" -f $ses.id, @($ses.arms).Count, $ses.expectPng, $(if ($nonSynth) { ' [non-synth spec]' } else { '' }))
    foreach ($arm in @($ses.arms)) {
        $problems = @()
        $tk = $null
        $tf = Join-Path $ticketDir ($arm.ticket + '.json')
        if (-not (Test-Path -LiteralPath $tf)) { $problems += "missing ticket $($arm.ticket)" }
        else { $tk = Get-Content -LiteralPath $tf -Raw -Encoding UTF8 | ConvertFrom-Json }

        if (-not $nonSynth) {
            $relaySynth = $null; $relayDist = 0.0; $relayMode = 'arc'
            foreach ($line in @($arm.relay)) {
                $ps = Parse-SynthLine $line
                if ($null -ne $ps -and $ps.n -ge 0) { $relaySynth = [int]$ps.n; $relayDist = [double]$ps.dist; $relayMode = [string]$ps.mode }
            }
            if ($null -eq $relaySynth) { $problems += 'relay has no !synth N line' }
            if ($null -ne $relaySynth -and [int]$arm.synthN -ne $relaySynth) { $problems += ("spec synthN={0} but relay says {1}" -f $arm.synthN, $relaySynth) }
            $specDist = $(if ($null -ne $arm.dist) { [double]$arm.dist } else { 0.0 })
            if ([math]::Abs($specDist - $relayDist) -gt 0.001) { $problems += ("spec dist={0} but relay distance={1}" -f $specDist, $relayDist) }
            if ($specDist -gt 48.0) { $problems += ("dist={0} exceeds the fixture cap 48" -f $specDist) }
            $allOff = ([int]$arm.minLights -eq 0 -and [int]$arm.maxLights -eq 0)
            if ($allOff) {
                if ($relaySynth -ne 0) { $problems += ("lights-off arm must use !synth 0, got {0}" -f $relaySynth) }
                if ([int]$arm.totalCount -ne 0) { $problems += ("lights-off arm totalCount must be 0, got {0}" -f $arm.totalCount) }
            } else {
                if ([int]$arm.totalCount -ne (2 + $relaySynth)) { $problems += ("totalCount={0} != 2 + synth({1})" -f $arm.totalCount, $relaySynth) }
                if ([int]$arm.minLights -ne [int]$arm.totalCount) { $problems += ("minLights={0} != totalCount={1}" -f $arm.minLights, $arm.totalCount) }
                if ([int]$arm.maxLights -ne -1) { $problems += ("maxLights={0} must be -1 for a lights-on arm" -f $arm.maxLights) }
                if ([int]$arm.totalCount -lt 2 -or [int]$arm.totalCount -gt 8) { $problems += ("totalCount={0} outside [2,8]" -f $arm.totalCount) }
            }
            $occlOn = [bool](@($arm.relay) -match '^!occl on$')
            $want = $(if ($occlOn) { 16 } else { 0 })
            if ([int]$arm.requireFlags -ne $want) { $problems += ("requireFlags={0} but occl on={1} (want {2})" -f $arm.requireFlags, $occlOn, $want) }
        }

        if ($null -ne $tk) {
            if ([string]$arm.shotPrefix -ne [string]$tk.shot_prefix) { $problems += ("shotPrefix='{0}' != ticket.shot_prefix='{1}'" -f $arm.shotPrefix, $tk.shot_prefix) }
            if ([string]$tk.ticket -ne [string]$arm.ticket) { $problems += ("ticket.internal='{0}' != spec arm.ticket='{1}'" -f $tk.ticket, $arm.ticket) }
            $stem = [System.IO.Path]::GetFileNameWithoutExtension($tf)
            if ([string]$tk.ticket -ne $stem) { $problems += ("ticket.internal='{0}' != filename stem='{1}' (bridge writes outbox\<internal>.result.json)" -f $tk.ticket, $stem) }
            # inv9: the driver locates arm images by "<shotPrefix>-N.png"; a ticket producing other names
            # (T12 bug#2 class: spec prefix agent-walkrun vs ticket shots agent-walkrun-run1) makes the display
            # gate match a STALE png from an earlier round, or REJECT a fresh arm.
            $drvRe = '^' + [regex]::Escape([string]$arm.shotPrefix) + '-\d+$'
            foreach ($sn2 in @($tk.ops | Where-Object { $_.op -eq 'shot' } | ForEach-Object { [string]$_.name })) {
                if ($sn2 -notmatch $drvRe) { $problems += ("shot name '{0}' is not matchable by the driver rule '{1}' (needs <shotPrefix>-N)" -f $sn2, $drvRe) }
            }            foreach ($sn in @($tk.ops | Where-Object { $_.op -eq 'shot' } | ForEach-Object { [string]$_.name })) {
                if ($sn -ne '' -and -not $sn.StartsWith([string]$arm.shotPrefix)) { $problems += ("shot name '{0}' does not start with shotPrefix '{1}'" -f $sn, $arm.shotPrefix) }
                if ($script:shotSeen.ContainsKey($sn)) { $problems += ("shot name '{0}' DUPLICATE in this session (first seen in arm {1})" -f $sn, $script:shotSeen[$sn]) } else { $script:shotSeen[$sn] = [string]$arm.id }
            }
            if (-not $nonSynth) {
                if ([int]$tk._synth_n -ne [int]$arm.synthN) { $problems += ("ticket _synth_n={0} != spec synthN={1}" -f $tk._synth_n, $arm.synthN) }
                if ([int]$tk._total_count -ne [int]$arm.totalCount) { $problems += ("ticket _total_count={0} != spec totalCount={1}" -f $tk._total_count, $arm.totalCount) }
                if ([int]$tk._min_lights -ne [int]$arm.minLights) { $problems += ("ticket _min_lights={0} != spec minLights={1}" -f $tk._min_lights, $arm.minLights) }
                if ([int]$tk._max_lights -ne [int]$arm.maxLights) { $problems += ("ticket _max_lights={0} != spec maxLights={1}" -f $tk._max_lights, $arm.maxLights) }
                if ([int]$tk._require_flags -ne [int]$arm.requireFlags) { $problems += ("ticket _require_flags={0} != spec requireFlags={1}" -f $tk._require_flags, $arm.requireFlags) }
                $relayTicket = $null; $ticketDist = 0.0; $ticketMode = 'arc'
                foreach ($line in @($tk._requires_relay)) {
                    $pt = Parse-SynthLine $line
                    if ($null -ne $pt -and $pt.n -ge 0) { $relayTicket = [int]$pt.n; $ticketDist = [double]$pt.dist; $ticketMode = [string]$pt.mode }
                }
                if ($null -eq $relayTicket -or $relayTicket -ne $relaySynth) { $problems += ("ticket relay synth={0} != spec relay synth={1}" -f $relayTicket, $relaySynth) }
                if ([math]::Abs($ticketDist - $relayDist) -gt 0.001) { $problems += ("ticket relay distance={0} != spec relay distance={1}" -f $ticketDist, $relayDist) }
                if ($ticketMode -ne $relayMode) { $problems += ("ticket relay mode={0} != spec relay mode={1}" -f $ticketMode, $relayMode) }
                if (($null -ne $tk._dist) -and ([math]::Abs([double]$tk._dist - $specDist) -gt 0.001)) { $problems += ("ticket _dist={0} != spec dist={1}" -f $tk._dist, $specDist) }
                $selfOff = [bool](@($tk._requires_relay) -match '^!selflight off$')
                $gunOff = [bool](@($tk._requires_relay) -match '^!gun off$')
                if ($selfOff -ne $gunOff) { $problems += 'selflight/gun switches disagree' }
            }

            # ---- inv10 (D2/T20): world-mutation ops must declare their probe cell and be bracketed by
            # in-band block probes (pre = declared replaceability basis, post = independent world change).
            $ticketOps = @($tk.ops)
            $ticketOpCount = $ticketOps.Count
            for ($mutIndex = 0; $mutIndex -lt $ticketOpCount; $mutIndex++) {
                $mutOp = $ticketOps[$mutIndex]
                if ($null -eq $mutOp) { continue }
                $mutKind = ''; $mutCell = $null; $mutDeclared = $false
                if ($mutOp.op -eq 'act' -and ([string]$mutOp.verb).Trim().ToLowerInvariant() -eq 'place') {
                    $mutKind = 'place'
                    if ($null -ne $mutOp.x -and $null -ne $mutOp.y -and $null -ne $mutOp.z) {
                        $mutCell = @([int]$mutOp.x, [int]$mutOp.y, [int]$mutOp.z)
                    }
                } elseif ($mutOp.op -eq 'use' -and ([string]$mutOp.target).Trim().ToLowerInvariant() -eq 'block') {
                    $mutKind = 'use-block'
                    if ($mutOp.PSObject.Properties.Name -contains '_probe_cell') {
                        $mutCell = @([int]$mutOp._probe_cell[0], [int]$mutOp._probe_cell[1], [int]$mutOp._probe_cell[2])
                        $mutDeclared = $true
                    }
                } else { continue }
                if ($null -eq $mutCell) {
                    $problems += ("inv10: {0} op #{1} does not declare _probe_cell (the cell whose block state proves the change)" -f $mutKind, $mutIndex)
                    continue
                }
                $cellX = $mutCell[0]; $cellY = $mutCell[1]; $cellZ = $mutCell[2]
                # D7 fix (pre-reviewer P2-6-3, their mutation M6): the old cross-check lived behind
                # '$mutKind -eq place -and $mutDeclared', but $mutDeclared was only set in the use-block
                # branch => that block was DEAD CODE. Evaluate it directly now.
                if ($mutKind -eq 'place' -and ($mutOp.PSObject.Properties.Name -contains '_probe_cell')) {
                    if ([int]$mutOp._probe_cell[0] -ne [int]$mutOp.x -or [int]$mutOp._probe_cell[1] -ne [int]$mutOp.y -or [int]$mutOp._probe_cell[2] -ne [int]$mutOp.z) {
                        $problems += ("inv10: place op #{0} declares _probe_cell != its own x/y/z (a probe on another cell cannot prove THIS placement) (D7 inv10a)" -f $mutIndex)
                    }
                }
                # D7 (P2-6-4, their mutation M11): a use-block probe must sit on the CLICKED cell
                # (x, y+1, z). Moving the whole probe group to a cell the action never touches used to pass.
                if ($mutKind -eq 'use-block') {
                    if ([int]$mutOp._probe_cell[0] -ne [int]$mutOp.x -or [int]$mutOp._probe_cell[1] -ne ([int]$mutOp.y + 1) -or [int]$mutOp._probe_cell[2] -ne [int]$mutOp.z) {
                        $problems += ("inv10: use-block op #{0} declares _probe_cell {1},{2},{3} but the clicked cell is {4},{5},{6} -- the probe must sit on the clicked face cell (x, y+1, z), otherwise it proves a cell the action never touches (D7 inv10a)" -f $mutIndex, $mutCell[0], $mutCell[1], $mutCell[2], [int]$mutOp.x, ([int]$mutOp.y + 1), [int]$mutOp.z)
                    }
                }
                $probeCellTxt = ('execute if block ' + $cellX + ' ' + $cellY + ' ' + $cellZ)
                $preProbeRe = '^' + [regex]::Escape($probeCellTxt) + ' minecraft:air$'
                $postProbeRe = '^' + [regex]::Escape($probeCellTxt) + ' minecraft:(\S+)$'
                $preProbeAt = -1; $postProbeAt = -1
                for ($probeScan = 0; $probeScan -lt $ticketOpCount; $probeScan++) {
                    $probeOp = $ticketOps[$probeScan]
                    if ($null -eq $probeOp -or $probeOp.op -ne 'command') { continue }
                    $probeLine = ([string]$probeOp.line).Trim()
                    if ($probeScan -lt $mutIndex) {
                        if ($probeLine -match $preProbeRe) { $preProbeAt = $probeScan }
                    } elseif ($probeScan -gt $mutIndex -and $postProbeAt -lt 0) {
                        if ($probeLine -match $postProbeRe -and $Matches[1] -ne 'air') { $postProbeAt = $probeScan }
                    }
                }
                if ($preProbeAt -lt 0) {
                    $problems += ("inv10: {0} op #{1} has no prior in-band probe '{2} minecraft:air' (declared replaceability basis missing)" -f $mutKind, $mutIndex, $probeCellTxt)
                }
                if ($postProbeAt -lt 0) {
                    $problems += ("inv10: {0} op #{1} has no later in-band probe '{2} minecraft:<non-air>' (independent world-change proof missing)" -f $mutKind, $mutIndex, $probeCellTxt)
                } else {
                    # D7 (P2-6-2, their mutation M5): the post regex 'minecraft:(\S+)' accepted ANY
                    # non-air block, so changing the probe to quartz_block still passed. The expected
                    # block must come from the ticket: '_expect_block_after' (or the op's
                    # '_probe_block_after'), else a literal 'execute if block <cell> minecraft:<block>'
                    # claim in the ticket's own text. Probes present + no expectation => RED (unjudgeable).
                    $postProbeLine = ([string]$ticketOps[$postProbeAt].line).Trim()
                    $postProbeBlock = ''
                    if ($postProbeLine -match '^execute if block \S+ \S+ \S+ minecraft:(\S+)$') { $postProbeBlock = 'minecraft:' + $Matches[1] }
                    $expectBlock = ''
                    if ($tk.PSObject.Properties.Name -contains '_expect_block_after') { $expectBlock = [string]$tk._expect_block_after }
                    elseif ($mutOp.PSObject.Properties.Name -contains '_probe_block_after') { $expectBlock = [string]$mutOp._probe_block_after }
                    if ($expectBlock -eq '') {
                        $claimRe = [regex]::Escape($probeCellTxt) + ' minecraft:(\S+)'
                        foreach ($claimField in @('_expect', '_probe_mechanism', '_what')) {
                            if ($tk.PSObject.Properties.Name -contains $claimField) {
                                $claimHit = [regex]::Match([string]$tk.$claimField, $claimRe)
                                if ($claimHit.Success -and $claimHit.Groups[1].Value -ne 'air') { $expectBlock = 'minecraft:' + $claimHit.Groups[1].Value; break }
                            }
                        }
                    }
                    if ($expectBlock -eq '') {
                        $problems += ("inv10: {0} op #{1} has a post-probe '{2}' but the ticket declares no expected block ('_expect_block_after' or a literal 'execute if block ... minecraft:<block>' claim) -- any non-air block currently passes (D7 inv10b)" -f $mutKind, $mutIndex, $postProbeLine)
                    } elseif ($postProbeBlock -ne $expectBlock) {
                        $problems += ("inv10: {0} op #{1} post-probe checks '{2}' but the declared/claimed expected block is {3} (D7 inv10b: the probe must check the DECLARED block)" -f $mutKind, $mutIndex, $postProbeLine, $expectBlock)
                    }
                }
            }

            # ---- inv11 (D2/T20): equip must be judged by an observable slot-level transition, never by a
            # same-item swap (backpack s1 and armor slot 38 both held diamond_chestplatex1 => "armor 8 -> 8").
            for ($equipIndex = 0; $equipIndex -lt $ticketOpCount; $equipIndex++) {
                $equipOp = $ticketOps[$equipIndex]
                if ($null -eq $equipOp) { continue }
                if ($equipOp.op -ne 'inv' -or ([string]$equipOp.action).Trim().ToLowerInvariant() -ne 'equip') { continue }
                $declaredBefore = $equipOp.PSObject.Properties.Name -contains '_expect_armor_before'
                $declaredAfter = $equipOp.PSObject.Properties.Name -contains '_expect_armor_after'
                if (-not ($declaredBefore -and $declaredAfter)) {
                    $problems += ("inv11: equip op #{0} does not declare _expect_armor_before/_expect_armor_after (criterion must be an observable armor-value transition)" -f $equipIndex)
                } else {
                    $armorWantBefore = [int]$equipOp._expect_armor_before
                    $armorWantAfter = [int]$equipOp._expect_armor_after
                    if ($armorWantBefore -eq $armorWantAfter) {
                        $problems += ("inv11: equip op #{0} declares armor {1} -> {1} (a same-value swap is NOT an observable criterion; unequip or install a different piece first)" -f $equipIndex, $armorWantBefore)
                    }
                }
                $declaredSource = $equipOp.PSObject.Properties.Name -contains '_expect_slot_source'
                $declaredArmor = $equipOp.PSObject.Properties.Name -contains '_expect_slot_armor'
                if (-not ($declaredSource -and $declaredArmor)) {
                    $problems += ("inv11: equip op #{0} does not declare _expect_slot_source/_expect_slot_armor (the two slot contents being exchanged must be declared)" -f $equipIndex)
                } elseif ([string]$equipOp._expect_slot_source -eq [string]$equipOp._expect_slot_armor) {
                    $problems += ("inv11: equip op #{0} declares IDENTICAL slot contents ('{1}') -- a same-item swap makes the confirm predicate TAUTOLOGICAL" -f $equipIndex, [string]$equipOp._expect_slot_source)
                }
                # D7 (P2-6-6, their mutation M3): the two _expect_slot_* values were never bound to the
                # declared armor transition, so SWAPPING them still passed (leather <-> diamond). Vanilla
                # chest-piece armor points give the binding: the slot named by _expect_slot_armor holds the
                # piece BEFORE, _expect_slot_source is the piece moving IN, so
                #   after = before - value(slot_armor item) + value(slot_source item)
                if ($declaredBefore -and $declaredAfter -and $declaredSource -and $declaredArmor) {
                    $armorPoints = @{
                        'leather_chestplate'   = 3
                        'chainmail_chestplate' = 5
                        'iron_chestplate'      = 6
                        'golden_chestplate'    = 5
                        'diamond_chestplate'   = 8
                        'netherite_chestplate' = 8
                        'empty'                = 0
                        'air'                  = 0
                    }
                    $slotSourceItem = (([string]$equipOp._expect_slot_source) -replace 'x\d+$', '').Trim().ToLowerInvariant()
                    $slotArmorItem = (([string]$equipOp._expect_slot_armor) -replace 'x\d+$', '').Trim().ToLowerInvariant()
                    if ($armorPoints.ContainsKey($slotSourceItem) -and $armorPoints.ContainsKey($slotArmorItem)) {
                        $boundAfter = [int]$equipOp._expect_armor_before - [int]$armorPoints[$slotArmorItem] + [int]$armorPoints[$slotSourceItem]
                        if ($boundAfter -ne [int]$equipOp._expect_armor_after) {
                            $problems += ("inv11: equip op #{0} declares armor {1} -> {2} but its declared slots imply {3} (before holds {4}={5}, {6}={7} moves in; the two _expect_slot_* values must match the armor transition -- swapping them is not allowed) (D7 inv11b)" -f $equipIndex, $equipOp._expect_armor_before, $equipOp._expect_armor_after, $boundAfter, $slotArmorItem, $armorPoints[$slotArmorItem], $slotSourceItem, $armorPoints[$slotSourceItem])
                        }
                    }
                }
                $armorReadBefore = $false; $armorReadAfter = $false
                $slotReadBefore = $false; $slotReadAfter = $false
                for ($readScan = 0; $readScan -lt $ticketOpCount; $readScan++) {
                    $readOp = $ticketOps[$readScan]
                    if ($null -eq $readOp -or $readOp.op -ne 'state') { continue }
                    $readWhat = ([string]$readOp.what).Trim().ToLowerInvariant()
                    if ($readScan -lt $equipIndex) {
                        if ($readWhat -eq 'armor') { $armorReadBefore = $true }
                        if ($readWhat -eq 'inv' -or $readWhat -eq 'all') { $slotReadBefore = $true }
                    } elseif ($readScan -gt $equipIndex) {
                        if ($readWhat -eq 'armor') { $armorReadAfter = $true }
                        if ($readWhat -eq 'inv' -or $readWhat -eq 'all') { $slotReadAfter = $true }
                    }
                }
                if (-not ($armorReadBefore -and $armorReadAfter)) {
                    $problems += ("inv11: equip op #{0} is not bracketed by 'state what=armor' readbacks (numeric readback missing)" -f $equipIndex)
                }
                if (-not ($slotReadBefore -and $slotReadAfter)) {
                    $problems += ("inv11: equip op #{0} is not bracketed by a slot-content snapshot ('state what=all|inv') (slot-level criterion missing)" -f $equipIndex)
                }
            }

            # ---- inv12b (D2/T20): pose discipline in a face=true "!agent goto" arm. The action layer
            # turns the player to the travel direction; a pose op ALWAYS pins yaw+pitch (TicketBridge
            # doPose requires x/y/z/yaw/pitch) and its settle loop then waits for the pinned yaw -- pin
            # anything the action layer will overwrite and the arm dies with "settle timeout" (B4).
            # Face=true arms must therefore place A with {'op':'command','line':'tp @s x y z ~ <pitch>'}
            # (yaw untouched) or send the goto with face=false (the decoupling arm keeps its frozen yaw).
            $gotoTarget = $null; $gotoFaceTrue = $false
            foreach ($relayItem in @($arm.relay)) {
                $relayText = [string]$relayItem
                if ($relayText -match '^!agent\s+goto\s+(-?[0-9]+(?:\.[0-9]+)?)\s+(-?[0-9]+(?:\.[0-9]+)?)\s+(-?[0-9]+(?:\.[0-9]+)?)') {
                    $gotoTarget = @([double]$Matches[1], [double]$Matches[2], [double]$Matches[3])
                    $gotoFaceTrue = ($relayText -notmatch 'face=false')
                }
            }
            if ($null -ne $gotoTarget -and $gotoFaceTrue) {
                foreach ($poseOp in @($ticketOps | Where-Object { $_.op -eq 'pose' })) {
                    if ($null -eq $poseOp) { continue }
                    $problems += ("inv12: face=true goto arm (target {0},{1},{2}) contains a pose op (x={3},y={4},z={5},yaw={6},pitch={7}) -- the pose ALWAYS pins yaw and its settle loop fights the action layer's turn (B4 'settle timeout want yaw=180.00 but yaw=-90.00'); use {{'op':'command','line':'tp @s x y z ~ <pitch>'}} instead" -f $gotoTarget[0], $gotoTarget[1], $gotoTarget[2], $poseOp.x, $poseOp.y, $poseOp.z, $poseOp.yaw, $poseOp.pitch)
                }
            }

            # ---- inv12c (D2/T20, lead-approved 2026-09-17): every movement arm ("!agent goto") must carry
            # an EXPLICIT position anchor for A -- a pose op with x/y/z, or a "command" tp with explicit
            # coordinates. Why: D4/D5 failed with moved=0.000 / pos unchanged (T18 "accepted but zero
            # displacement") precisely because A was never pinned; cancelling the pose op (inv12b) must not
            # trade "settle conflict" for "unknown start point". A face=true arm may only anchor via tp, and
            # there the yaw segment MUST be '~' (an explicit yaw re-pins what the action layer overwrites).
            if ($null -ne $gotoTarget) {
                $anchorPoseCount = 0; $anchorTpCount = 0; $anchorBadYaw = ''
                $anchorPoints = @()
                foreach ($anchorOp in @($ticketOps)) {
                    if ($null -eq $anchorOp) { continue }
                    if ($anchorOp.op -eq 'pose' -and ($anchorOp.PSObject.Properties.Name -contains 'x') -and ($anchorOp.PSObject.Properties.Name -contains 'y') -and ($anchorOp.PSObject.Properties.Name -contains 'z')) {
                        $anchorPoseCount++
                        $anchorPoints += , @([double]$anchorOp.x, [double]$anchorOp.y, [double]$anchorOp.z)
                    } elseif ($anchorOp.op -eq 'command') {
                        $anchorLine = ([string]$anchorOp.line).Trim()
                        if ($anchorLine -match '^tp\s+@s\s+(-?[0-9]+(?:\.[0-9]+)?)\s+(-?[0-9]+(?:\.[0-9]+)?)\s+(-?[0-9]+(?:\.[0-9]+)?)(?:\s+(.*))?$') {
                            $anchorTpCount++
                            $anchorPoints += , @([double]$Matches[1], [double]$Matches[2], [double]$Matches[3])
                            $anchorRest = ([string]$Matches[4]).Trim()
                            if ($anchorRest -ne '') {
                                $anchorYawTok = ($anchorRest -split '\s+')[0]
                                if ($gotoFaceTrue -and $anchorYawTok -ne '~') {
                                    $anchorBadYaw = $anchorLine
                                }
                            }
                        }
                    }
                }
                if (($anchorPoseCount + $anchorTpCount) -eq 0) {
                    $problems += ("inv12c: movement arm (goto target {0},{1},{2}) has NO position anchor -- add {{'op':'command','line':'tp @s <x> <y> <z> ~ <pitch>'}} (yaw untouched) or a pose op; without a pinned A the displacement judgment is meaningless (D4/D5: moved=0.000 with an unpinned/unsatisfiable start)" -f $gotoTarget[0], $gotoTarget[1], $gotoTarget[2])
                }
                if ($anchorBadYaw -ne '') {
                    $problems += ("inv12c: face=true movement arm anchors A with '{0}' -- an explicit yaw pins the very value the action layer overwrites; use '~' for the yaw segment (or the 3-arg 'tp @s x y z')" -f $anchorBadYaw)
                }
                # D7 (P2-6-1, their mutation M1): "an anchor exists" is not enough -- the anchor must BE A.
                # M1 set the anchor to the goto TARGET (2005.2,121,8) and still passed. A comes from the
                # arm's 'anchor' declaration when present, else from scoped 'start A = <name>(x,y,z'.
                $armA = $declaredA
                if ($arm.PSObject.Properties.Name -contains 'anchor') {
                    $armA = @([double]$arm.anchor.x, [double]$arm.anchor.y, [double]$arm.anchor.z)
                }
                if ($null -ne $armA) {
                    foreach ($anchorPoint in $anchorPoints) {
                        if ([math]::Abs([double]$anchorPoint[0] - [double]$armA[0]) -gt 0.001 -or [math]::Abs([double]$anchorPoint[1] - [double]$armA[1]) -gt 0.001 -or [math]::Abs([double]$anchorPoint[2] - [double]$armA[2]) -gt 0.001) {
                            $problems += ("inv12c: movement arm anchor ({0},{1},{2}) != declared start A ({3},{4},{5}) -- pin A exactly; an anchor elsewhere leaves the displacement/residual judgment without a reference point (D7 inv12c-A)" -f $anchorPoint[0], $anchorPoint[1], $anchorPoint[2], $armA[0], $armA[1], $armA[2])
                        }
                        if ([math]::Abs([double]$anchorPoint[0] - [double]$gotoTarget[0]) -le 0.001 -and [math]::Abs([double]$anchorPoint[1] - [double]$gotoTarget[1]) -le 0.001 -and [math]::Abs([double]$anchorPoint[2] - [double]$gotoTarget[2]) -le 0.001) {
                            $problems += ("inv12c: movement arm anchor ({0},{1},{2}) IS the goto TARGET -- an anchor on B cannot prove the start point A (D7 inv12c-A)" -f $anchorPoint[0], $anchorPoint[1], $anchorPoint[2])
                        }
                    }
                }
            }
            # ---- inv13 (D7 / P2-6-5, their mutation M10): a ticket that mutates the world must ASSERT
            # the result at runtime; deleting every assert op used to pass. Discipline-gated
            # (spec._discipline.assert_after_world_mutation) so the frozen T17 baseline -- which never
            # declared asserts and does not opt in -- keeps its original verdict.
            if ($discAssert) {
                $hasWorldMutation = $false
                foreach ($op13 in @($ticketOps)) {
                    if ($null -eq $op13) { continue }
                    if ($op13.op -eq 'act' -and ([string]$op13.verb).Trim().ToLowerInvariant() -eq 'place') { $hasWorldMutation = $true }
                    elseif ($op13.op -eq 'use' -and ([string]$op13.target).Trim().ToLowerInvariant() -eq 'block') { $hasWorldMutation = $true }
                    elseif ($op13.op -eq 'inv' -and ([string]$op13.action).Trim().ToLowerInvariant() -eq 'equip') { $hasWorldMutation = $true }
                }
                if ($hasWorldMutation) {
                    $assertOpCount = @($ticketOps | Where-Object { $null -ne $_ -and $_.op -eq 'assert' }).Count
                    if ($assertOpCount -eq 0) {
                        $problems += 'inv13: ticket mutates the world (act place / use target=block / inv equip) but carries NO assert op -- the runtime criterion is missing, _expect_* prose alone is not a judgment (D7 inv13)'
                    }
                }
            }

            # ---- inv15 (D7 / P2-6-9, their mutation M9): every arm needs an EXPLICIT lamp readback
            # ('state what=lamp'). The driver lamp gate needs the lamp-specific reading (phase5 (b3)) and
            # T18's first material defect was exactly "the lamp gate had no readback". Unconditional is
            # safe here: every ticket referenced by any spec in this repo already carries one, and the
            # frozen T17 baseline does too -- so this cannot fire on old inputs.
            $lampReadbackCount = @($ticketOps | Where-Object { $null -ne $_ -and $_.op -eq 'state' -and ([string]$_.what).Trim().ToLowerInvariant() -eq 'lamp' }).Count
            if ($lampReadbackCount -eq 0) {
                $problems += 'inv15: arm has no explicit lamp readback (state what=lamp) -- the lamp gate cannot be judged from state all alone (D7 inv15)'
            }
        }
        if ($problems.Count -eq 0) { Write-Host ("   OK   {0,-15}" -f $arm.id) }
        else { $fail++; Write-Host ("   FAIL {0,-15} :: {1}" -f $arm.id, ($problems -join ' ; ')) }
    }
    # ---- inv14 (D7 / P2-6-8, their mutation M8): an arm that mutates the world must be the LAST arm
    # of its session -- the spec's own prose says "runs LAST ... cannot touch the movement arms" but
    # nothing enforced the order. Discipline-gated (spec._discipline.world_mutation_arm_last); the
    # prose claim itself is cross-checked at spec level, so deleting the flag while keeping the promise
    # is also red. The frozen T17 baseline claims no order and does not opt in => verdict unchanged.
    if ($discWorldLast) {
        $armIdsInOrder = @($ses.arms | ForEach-Object { [string]$_.id })
        $lastArmId = $armIdsInOrder[$armIdsInOrder.Count - 1]
        foreach ($arm14 in @($ses.arms)) {
            $tf14 = Join-Path $ticketDir ($arm14.ticket + '.json')
            if (-not (Test-Path -LiteralPath $tf14)) { continue }
            $tk14 = Get-Content -LiteralPath $tf14 -Raw -Encoding UTF8 | ConvertFrom-Json
            $mutates14 = $false
            foreach ($op14 in @($tk14.ops)) {
                if ($null -eq $op14) { continue }
                if ($op14.op -eq 'act' -and ([string]$op14.verb).Trim().ToLowerInvariant() -eq 'place') { $mutates14 = $true }
                elseif ($op14.op -eq 'use' -and ([string]$op14.target).Trim().ToLowerInvariant() -eq 'block') { $mutates14 = $true }
                elseif ($op14.op -eq 'inv' -and ([string]$op14.action).Trim().ToLowerInvariant() -eq 'equip') { $mutates14 = $true }
            }
            if ($mutates14 -and [string]$arm14.id -ne $lastArmId) {
                $fail++
                Write-Host ("   FAIL (session) arm '{0}' mutates the world but is NOT the last arm ('{1}') -- world mutations must not touch the movement arms (D7 inv14; _discipline.world_mutation_arm_last)" -f $arm14.id, $lastArmId)
            }
        }
    }
    $isPaired = ($specObj._paired -eq $true) -or (@($ses.arms | Where-Object { $_.PSObject.Properties.Name -contains 'pair' }).Count -gt 0)
    if ($isPaired) {
        $ids = @($ses.arms | ForEach-Object { $_.id }); $adj = $null; $why = ''
        foreach ($grp in ($ses.arms | Where-Object { $_.PSObject.Properties.Name -contains 'pair' } | Group-Object pair)) {
            $g = @($grp.Group)
            $declared = ($grp.Name -eq 'anoise') -or (@($g | Where-Object { $_.role -eq 'x' -or $_.role -eq 'y' }).Count -eq 2)
            if (-not $declared) { continue }
            if ($g.Count -ne 2) { $why = ("declared noise pair '{0}' has {1} arms" -f $grp.Name, $g.Count); continue }
            $i0 = [array]::IndexOf($ids, [string]$g[0].id); $i1 = [array]::IndexOf($ids, [string]$g[1].id)
            if ($i0 -lt 0 -or $i1 -lt 0) { $why = ("noise pair '{0}' references unknown ids" -f $grp.Name); continue }
            if ([math]::Abs($i0 - $i1) -ne 1) { $why = ("noise pair '{0}' is NOT back-to-back" -f $grp.Name); continue }
            if ((@($g[0].relay) -join '|') -ne (@($g[1].relay) -join '|')) { $why = ("noise pair '{0}' arms differ in relay config" -f $grp.Name); continue }
            $adj = ("{0} + {1} (pair={2})" -f $g[0].id, $g[1].id, $grp.Name); break
        }
        if ($null -eq $adj) {
            $msg = ("session {0} has NO valid DECLARED back-to-back identical-config noise pair -> no noise scale (paired analysis reports NOT READABLE). {1}" -f $ses.id, $why)
            if ($specObj._paired -eq $true) { $fail++; Write-Host ("   FAIL (session) {0}" -f $msg) } else { Write-Host ("   WARN (session) {0}" -f $msg) }
        } else { Write-Host ("   OK   (session {0}) noise pair: {1}" -f $ses.id, $adj) }
    }
}
if ($fail -gt 0) { Write-Host ("RESULT: {0} arm(s) INCONSISTENT" -f $fail); exit 1 }
Write-Host 'RESULT: all arms self-consistent'
exit 0
