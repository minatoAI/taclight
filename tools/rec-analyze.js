'use strict';
/**
 * Strict offline analyzer for TacLight frame-recorder sessions.
 *
 * Usage: node rec-analyze.js <sessionDir> [--bbox x0,y0,x1,y1]
 * Success writes <sessionDir>/summary.json. Any protocol/integrity failure exits
 * non-zero and does not write a summary.
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { TextDecoder } = require('util');

const ROW_TYPES = ['C', 'L', 'R', 'P', 'S', 'F', 'D'];
const LUMA_BATCH_SIZE = 50;

function fail(message) {
  throw new Error(message);
}

function finite(value, label) {
  const n = Number(value);
  if (!Number.isFinite(n)) fail(`invalid ${label}: ${JSON.stringify(value)}`);
  return n;
}

function integer(value, label) {
  const n = finite(value, label);
  if (!Number.isInteger(n) || n < 0) fail(`invalid ${label}: ${JSON.stringify(value)}`);
  return n;
}

function parseBool(value, label) {
  if (value === '1' || /^true$/i.test(value)) return true;
  if (value === '0' || /^false$/i.test(value)) return false;
  fail(`invalid ${label}: ${JSON.stringify(value)}`);
}

function mean(values) {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function rangeStat(values) {
  if (!values.length) return null;
  const min = Math.min(...values);
  const max = Math.max(...values);
  return { min, max, range: max - min };
}

function seriesStat(values) {
  if (!values.length) return null;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const avg = mean(values);
  const relPulse = Math.abs(avg) > 1e-12 ? (max - min) / Math.abs(avg) : null;
  return {
    min,
    max,
    mean: avg,
    relPulse,
    relPulsePct: relPulse === null ? null : relPulse * 100,
  };
}

function vectorRanges(rows, indices, names) {
  const result = {};
  names.forEach((name, i) => {
    result[name] = rangeStat(rows.map(row => finite(row[indices[i]], `${row[0]}.${name}`)));
  });
  return result;
}

function parseKeyNumber(line, key) {
  const match = line.match(new RegExp(`(?:^|\\s)${key}=([0-9]+)(?=\\s|$)`));
  return match ? integer(match[1], `footer ${key}`) : null;
}

function parseCloseReason(line) {
  const match = line.match(/(?:^|\s)(?:closeReason|close_reason|reason|why)=(?:"([^"]*)"|'([^']*)'|(.+?))(?=\s+[A-Za-z_][A-Za-z0-9_]*=|$)/);
  if (!match) return null;
  return (match[1] ?? match[2] ?? match[3]).trim();
}

function parseTargetFps(commentLines) {
  for (const line of commentLines) {
    const explicit = line.match(/(?:targetFps|target_fps|target-fps)\s*=\s*([0-9]+(?:\.[0-9]+)?)/i);
    if (explicit) return finite(explicit[1], 'target fps');
  }
  for (const line of commentLines) {
    const fallback = line.match(/(?:^|\s)fps\s*=\s*([0-9]+(?:\.[0-9]+)?)(?=\s|$)/i);
    if (fallback) return finite(fallback[1], 'target fps');
  }
  return null;
}

function parseCsv(csvPath) {
  if (!fs.existsSync(csvPath) || !fs.statSync(csvPath).isFile()) fail(`missing frames.csv: ${csvPath}`);
  const bytes = fs.readFileSync(csvPath);
  if (!bytes.length) fail('frames.csv is empty');
  if (bytes.includes(0)) fail('frames.csv contains NUL byte');
  let text;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch (error) {
    fail(`frames.csv is not valid UTF-8: ${error.message}`);
  }
  if (!text.endsWith('\n')) fail('frames.csv has a truncated final line (missing newline)');

  const rawLines = text.split('\n');
  rawLines.pop();
  const nonEmpty = rawLines.map((line, index) => ({ line: line.replace(/\r$/, ''), number: index + 1 }))
    .filter(entry => entry.line.length > 0);
  if (!nonEmpty.length) fail('frames.csv has no records');
  if (!nonEmpty[0].line.startsWith('#') || !/TacLight.*(?:mcap|rec)/i.test(nonEmpty[0].line)) {
    fail(`missing/invalid header at line ${nonEmpty[0].number}`);
  }
  const footerEntries = nonEmpty.filter(entry => /^#\s*END(?:\s|$)/.test(entry.line));
  if (footerEntries.length !== 1) fail(`expected exactly one footer, found ${footerEntries.length}`);
  const footerEntry = footerEntries[0];
  if (footerEntry !== nonEmpty[nonEmpty.length - 1]) fail(`footer must be the last non-empty line (line ${footerEntry.number})`);

  const rows = Object.fromEntries(ROW_TYPES.map(type => [type, []]));
  const comments = [];
  for (const entry of nonEmpty) {
    const { line, number } = entry;
    if (line.startsWith('#')) {
      comments.push(line);
      continue;
    }
    const fields = line.split(',');
    if (!ROW_TYPES.includes(fields[0])) fail(`unknown/truncated row type at line ${number}: ${JSON.stringify(fields[0])}`);
    if (fields.some(field => field.length === 0)) fail(`empty/truncated field at line ${number}`);
    fields.lineNumber = number;
    rows[fields[0]].push(fields);
  }

  const footerLine = footerEntry.line;
  const footer = {
    rows: parseKeyNumber(footerLine, 'rows'),
    requested: parseKeyNumber(footerLine, 'requested'),
    succeeded: parseKeyNumber(footerLine, 'succeeded'),
    failed: parseKeyNumber(footerLine, 'failed'),
    dropped: parseKeyNumber(footerLine, 'dropped'),
    closeReason: parseCloseReason(footerLine),
  };
  for (const key of ['rows', 'requested', 'succeeded', 'failed', 'dropped']) {
    if (footer[key] === null) fail(`footer missing ${key}`);
  }
  if (!footer.closeReason) fail('footer missing close reason');

  const dataRows = ROW_TYPES.reduce((sum, type) => sum + rows[type].length, 0);
  if (footer.rows !== dataRows) fail(`footer rows=${footer.rows}, actual=${dataRows}`);
  if (footer.requested !== rows.P.length) fail(`footer requested=${footer.requested}, P=${rows.P.length}`);
  if (footer.succeeded !== rows.S.length) fail(`footer succeeded=${footer.succeeded}, S=${rows.S.length}`);
  if (footer.failed !== rows.F.length) fail(`footer failed=${footer.failed}, F=${rows.F.length}`);
  const droppedTotal = rows.D.reduce((sum, row) => {
    if (row.length < 5) fail(`D row ${row.lineNumber} has ${row.length} columns; expected D,t,frame,count,reason`);
    return sum + integer(row[3], `D.count line ${row.lineNumber}`);
  }, 0);
  if (footer.dropped !== droppedTotal) fail(`footer dropped=${footer.dropped}, sum(D.count)=${droppedTotal}`);
  if (rows.P.length !== rows.S.length + rows.F.length) {
    fail(`P/S/F mismatch: P=${rows.P.length}, S=${rows.S.length}, F=${rows.F.length}`);
  }

  return { rows, footer, comments, targetFps: parseTargetFps(comments) };
}

function validateRows(rows) {
  if (rows.C.length < 2) fail(`need at least two C rows, found ${rows.C.length}`);
  let previousT = null;
  let previousFrame = null;
  for (const row of rows.C) {
    if (row.length < 19) fail(`C row ${row.lineNumber} has ${row.length} columns; expected at least 19`);
    const t = finite(row[1], `C.t line ${row.lineNumber}`);
    const frame = integer(row[2], `C.frame line ${row.lineNumber}`);
    for (let i = 3; i <= 17; i++) finite(row[i], `C[${i}] line ${row.lineNumber}`);
    parseBool(row[18], `C.bobEnabled line ${row.lineNumber}`);
    if (previousT !== null) {
      if (t <= previousT) fail(`C timestamps are not strictly increasing at line ${row.lineNumber}: ${previousT} -> ${t}`);
      if (frame <= previousFrame) fail(`C render frames are not strictly increasing at line ${row.lineNumber}: ${previousFrame} -> ${frame}`);
      if (t - previousT <= 5) fail(`suspected duplicate sampling at line ${row.lineNumber}: C interval ${t - previousT}ms <= 5ms`);
    }
    previousT = t;
    previousFrame = frame;
  }

  for (const row of rows.L) {
    if (row.length < 14) fail(`L row ${row.lineNumber} has ${row.length} columns; expected at least 14`);
    finite(row[1], 'L.t'); integer(row[2], 'L.frame'); integer(row[3], 'L.idx');
    for (let i = 4; i <= 13; i++) finite(row[i], `L[${i}] line ${row.lineNumber}`);
  }
  for (const row of rows.R) {
    if (row.length < 27) fail(`R row ${row.lineNumber} has ${row.length} columns; expected at least 27`);
    finite(row[1], 'R.t'); integer(row[2], 'R.frame'); integer(row[3], 'R.id');
    for (let i = 4; i <= 26; i++) finite(row[i], `R[${i}] line ${row.lineNumber}`);
  }
  for (const row of rows.D) {
    if (row.length < 5) fail(`D row ${row.lineNumber} has ${row.length} columns; expected D,t,frame,count,reason`);
    finite(row[1], `D.t line ${row.lineNumber}`);
    integer(row[2], `D.frame line ${row.lineNumber}`);
    integer(row[3], `D.count line ${row.lineNumber}`);
  }
}

function normalizeRelativeFilename(filename, lineNumber) {
  if (!filename || filename.includes('\0')) fail(`invalid P filename at line ${lineNumber}`);
  const normalized = filename.replace(/\\/g, '/');
  if (path.posix.isAbsolute(normalized) || /^[A-Za-z]:\//.test(normalized)) fail(`absolute P filename at line ${lineNumber}`);
  const parts = normalized.split('/');
  if (parts.some(part => !part || part === '.' || part === '..')) fail(`unsafe P filename at line ${lineNumber}: ${filename}`);
  return normalized;
}

function canonical(file) {
  const absolute = path.resolve(file);
  return process.platform === 'win32' ? absolute.toLowerCase() : absolute;
}

function pairShots(dir, rows) {
  const pending = new Map();
  const filenames = new Map();
  const screenshotsDir = path.join(dir, 'screenshots');
  for (const row of rows.P) {
    if (row.length < 5) fail(`P row ${row.lineNumber} has ${row.length} columns; expected P,t,frame,seq,filename`);
    const t = finite(row[1], `P.t line ${row.lineNumber}`);
    const frame = integer(row[2], `P.frame line ${row.lineNumber}`);
    const seq = integer(row[3], `P.seq line ${row.lineNumber}`);
    const seqToken = String(seq);
    const filename = normalizeRelativeFilename(row[4], row.lineNumber);
    const expectedBase = `shot-${String(seq).padStart(6, '0')}.png`;
    if (path.posix.basename(filename) !== expectedBase) {
      fail(`P filename at line ${row.lineNumber} must be ${expectedBase}, got ${filename}`);
    }
    if (pending.has(seqToken)) fail(`duplicate P seq=${seq} at line ${row.lineNumber}`);
    const filenameKey = process.platform === 'win32' ? filename.toLowerCase() : filename;
    if (filenames.has(filenameKey)) fail(`duplicate P filename=${filename} at line ${row.lineNumber}`);
    const filePath = filename.includes('/') ? path.resolve(dir, ...filename.split('/')) : path.join(
      fs.existsSync(screenshotsDir) && fs.statSync(screenshotsDir).isDirectory() ? screenshotsDir : dir,
      filename,
    );
    const record = { t, frame, seq, filename, filePath, lineNumber: row.lineNumber };
    pending.set(seqToken, record);
    filenames.set(filenameKey, record);
  }

  const outcomes = new Map();
  const successes = [];
  function addOutcome(row, kind) {
    const minimumColumns = kind === 'F' ? 6 : 5;
    if (row.length < minimumColumns) {
      fail(`${kind} row ${row.lineNumber} has ${row.length} columns; expected ${kind},t,frame,seq,filename${kind === 'F' ? ',detail' : ''}`);
    }
    const t = finite(row[1], `${kind}.t line ${row.lineNumber}`);
    const frame = integer(row[2], `${kind}.frame line ${row.lineNumber}`);
    const seq = integer(row[3], `${kind}.seq line ${row.lineNumber}`);
    const key = String(seq);
    const request = pending.get(key);
    if (!request) fail(`${kind} seq=${seq} at line ${row.lineNumber} has no matching P`);
    if (outcomes.has(key)) fail(`duplicate/conflicting S/F outcome for seq=${seq} at line ${row.lineNumber}`);
    if (frame !== request.frame) fail(`${kind}/P frame mismatch for seq=${seq}: ${frame} != ${request.frame}`);
    const outcomeFilename = normalizeRelativeFilename(row[4], row.lineNumber);
    if (outcomeFilename !== request.filename) fail(`${kind}/P filename mismatch for seq=${seq}`);
    outcomes.set(key, kind);
    if (kind === 'S') successes.push({ ...request, successT: t });
  }
  rows.S.forEach(row => addOutcome(row, 'S'));
  rows.F.forEach(row => addOutcome(row, 'F'));
  for (const request of pending.values()) {
    if (!outcomes.has(String(request.seq))) fail(`P seq=${request.seq} has no S/F outcome`);
  }

  for (const shot of successes) {
    if (!fs.existsSync(shot.filePath) || !fs.statSync(shot.filePath).isFile()) {
      fail(`successful PNG missing for seq=${shot.seq}: ${shot.filePath}`);
    }
  }

  const actualPngs = [];
  function walk(current) {
    if (!fs.existsSync(current)) return;
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const child = path.join(current, entry.name);
      if (entry.isDirectory()) walk(child);
      else if (entry.isFile() && /\.png$/i.test(entry.name)) actualPngs.push(child);
    }
  }
  walk(dir);
  const expected = new Set(successes.map(shot => canonical(shot.filePath)));
  const extras = actualPngs.filter(file => !expected.has(canonical(file)));
  if (extras.length) fail(`extra PNG(s): ${extras.map(file => path.relative(dir, file)).join(', ')}`);
  if (actualPngs.length !== successes.length) {
    fail(`PNG count mismatch: expected successful=${successes.length}, actual=${actualPngs.length}`);
  }

  successes.sort((a, b) => a.successT - b.successT || a.seq - b.seq);
  return successes;
}

function parseBbox(argv) {
  const index = argv.indexOf('--bbox');
  if (index < 0) return null;
  if (index + 1 >= argv.length) fail('--bbox requires x0,y0,x1,y1');
  const value = argv[index + 1];
  const parts = value.split(',').map(Number);
  if (parts.length !== 4 || parts.some(number => !Number.isFinite(number))) fail(`invalid --bbox: ${value}`);
  return value;
}

function readLuma(shots, bbox) {
  if (!shots.length) return { series: [], batches: 0 };
  const lumastats = path.join(__dirname, 'lumastats.js');
  const output = [];
  let batches = 0;
  for (let start = 0; start < shots.length; start += LUMA_BATCH_SIZE) {
    const batch = shots.slice(start, start + LUMA_BATCH_SIZE);
    const args = [lumastats, ...batch.map(shot => shot.filePath)];
    if (bbox !== null) args.push('--bbox', bbox);
    const result = spawnSync(process.execPath, args, {
      encoding: 'utf8',
      windowsHide: true,
      maxBuffer: 64 * 1024 * 1024,
    });
    batches++;
    if (result.error) fail(`lumastats spawn error (batch ${batches}): ${result.error.message}`);
    if (result.status !== 0) {
      fail(`lumastats failed (batch ${batches}, status=${result.status}): ${(result.stderr || result.stdout || '').trim()}`);
    }
    if ((result.stderr || '').trim()) fail(`lumastats stderr (batch ${batches}): ${result.stderr.trim()}`);
    const lines = (result.stdout || '').split(/\r?\n/).filter(line => line.trim().length > 0);
    if (lines.length !== batch.length) fail(`lumastats batch ${batches} returned ${lines.length}/${batch.length} records`);
    lines.forEach((line, index) => {
      let stat;
      try { stat = JSON.parse(line); } catch (error) { fail(`invalid lumastats JSON in batch ${batches}: ${error.message}`); }
      const shot = batch[index];
      if (stat.file && stat.file !== path.basename(shot.filePath)) {
        fail(`lumastats output order mismatch: expected ${path.basename(shot.filePath)}, got ${stat.file}`);
      }
      const px = finite(stat.px, 'lumastats px');
      const ge200Fraction = Number.isFinite(Number(stat.ge200Fraction)) ? Number(stat.ge200Fraction)
        : Number.isFinite(Number(stat.frac200)) ? Number(stat.frac200)
          : finite(stat.ge200, 'lumastats ge200') / px;
      output.push({
        t: shot.successT,
        requestT: shot.t,
        frame: shot.frame,
        seq: shot.seq,
        filename: shot.filename,
        mean: finite(stat.mean, 'lumastats mean'),
        p90: finite(stat.p90, 'lumastats p90'),
        p99: finite(stat.p99, 'lumastats p99'),
        ge200Fraction,
      });
    });
  }
  output.sort((a, b) => a.t - b.t || a.seq - b.seq);
  return { series: output, batches };
}

function interpolateCamera(C, t) {
  const firstT = Number(C[0][1]);
  const lastT = Number(C[C.length - 1][1]);
  if (t < firstT || t > lastT) return null; // Deliberately no endpoint clamp.
  let low = 0;
  let high = C.length - 1;
  while (low <= high) {
    const middle = (low + high) >> 1;
    const middleT = Number(C[middle][1]);
    if (middleT === t) return cameraSignals(C[middle]);
    if (middleT < t) low = middle + 1;
    else high = middle - 1;
  }
  const left = C[high];
  const right = C[low];
  const alpha = (t - Number(left[1])) / (Number(right[1]) - Number(left[1]));
  const lerp = index => Number(left[index]) + (Number(right[index]) - Number(left[index])) * alpha;
  const enabled = alpha < 0.5 ? parseBool(left[18], 'C.bobEnabled') : parseBool(right[18], 'C.bobEnabled');
  const walkDist = lerp(14);
  const bobAmplitude = lerp(16);
  return {
    walkDist,
    walkDistO: lerp(15),
    bob: bobAmplitude,
    oBob: lerp(17),
    bobEnabled: enabled,
    vanillaBobDriver: enabled ? Math.sin(Math.PI * walkDist) * bobAmplitude : 0,
    cameraEyeDelta: { x: lerp(3) - lerp(11), y: lerp(4) - lerp(12), z: lerp(5) - lerp(13) },
  };
}

function cameraSignals(row) {
  const enabled = parseBool(row[18], 'C.bobEnabled');
  const walkDist = Number(row[14]);
  const bobAmplitude = Number(row[16]);
  return {
    walkDist,
    walkDistO: Number(row[15]),
    bob: bobAmplitude,
    oBob: Number(row[17]),
    bobEnabled: enabled,
    vanillaBobDriver: enabled ? Math.sin(Math.PI * walkDist) * bobAmplitude : 0,
    cameraEyeDelta: {
      x: Number(row[3]) - Number(row[11]),
      y: Number(row[4]) - Number(row[12]),
      z: Number(row[5]) - Number(row[13]),
    },
  };
}

function pearson(pairs) {
  if (pairs.length < 8) return null;
  const xs = pairs.map(pair => pair[0]);
  const ys = pairs.map(pair => pair[1]);
  const mx = mean(xs);
  const my = mean(ys);
  let xy = 0;
  let xx = 0;
  let yy = 0;
  for (let i = 0; i < pairs.length; i++) {
    const dx = xs[i] - mx;
    const dy = ys[i] - my;
    xy += dx * dy;
    xx += dx * dx;
    yy += dy * dy;
  }
  if (xx <= 0 || yy <= 0) return null;
  return xy / Math.sqrt(xx * yy);
}

function lagCorrelation(C, visual, metric) {
  let best = null;
  for (let lagMs = -120; lagMs <= 120; lagMs++) {
    const pairs = [];
    for (const sample of visual) {
      const camera = interpolateCamera(C, sample.t + lagMs);
      if (camera) pairs.push([sample[metric], camera.vanillaBobDriver]);
    }
    const r = pearson(pairs);
    if (r !== null && (best === null || Math.abs(r) > Math.abs(best.r))) {
      best = { lagMs, r, n: pairs.length };
    }
  }
  return best;
}

function invarianceSummary(rows) {
  const lightGroups = new Map();
  for (const row of rows.L) {
    const key = String(integer(row[3], 'L.idx'));
    if (!lightGroups.has(key)) lightGroups.set(key, []);
    lightGroups.get(key).push(row);
  }
  const lights = [...lightGroups.entries()].sort((a, b) => Number(a[0]) - Number(b[0])).map(([idx, group]) => ({
    idx: Number(idx),
    samples: group.length,
    anchor: vectorRanges(group, [4, 5, 6], ['x', 'y', 'z']),
    direction: vectorRanges(group, [7, 8, 9], ['x', 'y', 'z']),
    radius: rangeStat(group.map(row => Number(row[10]))),
    intensity: rangeStat(group.map(row => Number(row[11]))),
  }));

  const remoteGroups = new Map();
  for (const row of rows.R) {
    const key = String(integer(row[3], 'R.id'));
    if (!remoteGroups.has(key)) remoteGroups.set(key, []);
    remoteGroups.get(key).push(row);
  }
  const remote = [...remoteGroups.entries()].sort((a, b) => Number(a[0]) - Number(b[0])).map(([id, group]) => ({
    id: Number(id),
    samples: group.length,
    base: vectorRanges(group, [11, 12], ['yaw', 'pitch']),
    ext: rangeStat(group.map(row => Number(row[14]))),
    disp: vectorRanges(group, [24, 25, 26], ['x', 'y', 'z']),
  }));
  return { lights, remote };
}

function analyze(dir, bbox) {
  const summaryPath = path.join(dir, 'summary.json');
  fs.rmSync(summaryPath, { force: true }); // Never leave a stale success artifact after a hard failure.
  const csvPath = path.join(dir, 'frames.csv');
  const parsed = parseCsv(csvPath);
  validateRows(parsed.rows);
  const shots = pairShots(dir, parsed.rows);
  const luma = readLuma(shots, bbox);
  const C = parsed.rows.C;
  const firstT = Number(C[0][1]);
  const lastT = Number(C[C.length - 1][1]);
  const durationMs = lastT - firstT;
  if (durationMs <= 0) fail(`invalid C duration: ${durationMs}ms`);
  const actualFps = (C.length - 1) / (durationMs / 1000);

  const metrics = ['mean', 'p90', 'p99', 'ge200Fraction'];
  const visualSummary = Object.fromEntries(metrics.map(metric => [metric, seriesStat(luma.series.map(sample => sample[metric]))]));
  const correlations = Object.fromEntries(metrics.map(metric => [metric, lagCorrelation(C, luma.series, metric)]));
  const cameraAtShots = luma.series.map(sample => interpolateCamera(C, sample.t)).filter(Boolean);
  const cameraEyeDelta = {
    note: 'API camera-eye difference only; not a measurement of the vanilla bob driver',
    samples: cameraAtShots.length,
    x: rangeStat(cameraAtShots.map(sample => sample.cameraEyeDelta.x)),
    y: rangeStat(cameraAtShots.map(sample => sample.cameraEyeDelta.y)),
    z: rangeStat(cameraAtShots.map(sample => sample.cameraEyeDelta.z)),
  };

  const counts = Object.fromEntries(ROW_TYPES.map(type => [type, parsed.rows[type].length]));
  const summary = {
    session: path.basename(path.resolve(dir)),
    integrity: {
      ok: true,
      hardErrors: [],
      warnings: [],
      checks: {
        footerCounts: true,
        shotTokens: true,
        pngSetExact: true,
        cTimestampsStrict: true,
        renderFramesStrict: true,
        noDuplicateSamplingAtOrBelow5ms: true,
      },
    },
    input: {
      counts,
      dataRows: parsed.footer.rows,
      pngs: luma.series.length,
      footer: parsed.footer,
    },
    targetFps: parsed.targetFps,
    actualFps,
    fps: { target: parsed.targetFps, actual: actualFps },
    dropped: parsed.footer.dropped,
    durationMs,
    durationS: durationMs / 1000,
    invariance: invarianceSummary(parsed.rows),
    cameraEyeDelta,
    vanillaBobDriver: {
      definition: 'bobEnabled ? sin(pi * linearlyInterpolatedWalkDist) * linearlyInterpolatedBob : 0',
      interpolationTimeline: 'S.t milliseconds; out-of-range samples omitted (no endpoint clamp)',
      samples: cameraAtShots.length,
      range: rangeStat(cameraAtShots.map(sample => sample.vanillaBobDriver)),
    },
    visual: {
      bbox,
      lumastatsBatchSize: LUMA_BATCH_SIZE,
      lumastatsBatches: luma.batches,
      summary: visualSummary,
      series: luma.series,
    },
    timeLagCorrelations: {
      lagRangeMs: [-120, 120],
      lagStepMs: 1,
      convention: 'corr(visual(t), vanillaBobDriver(t + lagMs)); samples outside C time range are omitted, never clamped',
      metrics: correlations,
    },
  };

  fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2) + '\n', 'utf8');
  return { summary, summaryPath };
}

function main() {
  const argv = process.argv.slice(2);
  const dirArg = argv[0];
  if (!dirArg || dirArg.startsWith('--')) {
    console.error('usage: node rec-analyze.js <sessionDir> [--bbox x0,y0,x1,y1]');
    process.exitCode = 1;
    return;
  }
  try {
    const dir = path.resolve(dirArg);
    if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) fail(`session directory not found: ${dir}`);
    const bbox = parseBbox(argv.slice(1));
    const { summary, summaryPath } = analyze(dir, bbox);
    console.log(`REC-ANALYZE PASS session=${summary.session} C=${summary.input.counts.C} shots=${summary.input.counts.S} duration=${summary.durationS.toFixed(3)}s actualFps=${summary.actualFps.toFixed(3)}`);
    console.log(`summary -> ${summaryPath}`);
  } catch (error) {
    console.error(`REC-ANALYZE FAIL: ${error.message}`);
    process.exitCode = 1;
  }
}

if (require.main === module) main();

module.exports = { analyze, LUMA_BATCH_SIZE };
