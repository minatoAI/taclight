'use strict';
const fs = require('fs');
const path = require('path');
const { decodePng } = require('../imgdiff.js');

const root = process.argv[2];
const sessions = (process.argv[3] || 's0006,s0007,s0008,s0009').split(',');
if (!root || sessions.length !== 4) {
  console.error('usage: node matched-abba-analysis.js RUN_DIR [onOn,onOff,offOn,offOff]');
  process.exit(2);
}
const names = ['onOn', 'onOff', 'offOn', 'offOff'];
const roi = [0, 40, 650, 420];
const positionTolerance = 0.10;
const bobDriverTolerance = 0.018;
const moveAxis = [Math.sin(42 * Math.PI / 180), -Math.cos(42 * Math.PI / 180)];

function metric(file) {
  const image = decodePng(fs.readFileSync(file));
  const channels = image.data.length / (image.width * image.height);
  const [x0, y0, x1, y1] = roi;
  const luma = [];
  let ge180 = 0, ge200 = 0, gradient = 0, edge32 = 0;
  const lum = (x, y) => {
    const i = (y * image.width + x) * channels;
    return 0.2126 * image.data[i] + 0.7152 * image.data[i + 1] + 0.0722 * image.data[i + 2];
  };
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
    const value = lum(x, y);
    luma.push(value);
    if (value >= 180) ge180++;
    if (value >= 200) ge200++;
    if (x > x0 && y > y0) {
      const g = Math.max(Math.abs(value - lum(x - 1, y)), Math.abs(value - lum(x, y - 1)));
      gradient += g;
      if (g >= 32) edge32++;
    }
  }
  luma.sort((a, b) => a - b);
  const n = luma.length;
  return {
    p90: luma[Math.floor(0.90 * (n - 1))],
    p99: luma[Math.floor(0.99 * (n - 1))],
    ge180: ge180 / n,
    ge200: ge200 / n,
    gradient: gradient / n,
    edge32: edge32 / n,
  };
}

function load(session) {
  const dir = path.join(root, session);
  const lines = fs.readFileSync(path.join(dir, 'frames.csv'), 'utf8').split(/\r?\n/);
  const cameras = new Map();
  for (const line of lines) if (line.startsWith('C,')) {
    const row = line.split(',');
    const walk = (+row[14] + +row[15]) * 0.5;
    const bob = (+row[16] + +row[17]) * 0.5;
    cameras.set(+row[2], {
      x: +row[3], z: +row[5], walk, bob,
      bobEnabled: /^true$/i.test(row[18]),
      bobX: Math.sin(Math.PI * walk) * bob,
      bobY: Math.abs(Math.cos(Math.PI * walk) * bob),
    });
  }
  const samples = [];
  for (const line of lines) if (line.startsWith('S,')) {
    const row = line.split(',');
    const camera = cameras.get(+row[2]);
    if (!camera || camera.bob < 0.045) continue;
    const seq = +row[3];
    samples.push({
      ...camera, frame: +row[2], seq,
      u: (camera.x - 2004.0) * moveAxis[0] + (camera.z - 3.5) * moveAxis[1],
      file: path.join(dir, 'screenshots', `shot-${String(seq).padStart(6, '0')}.png`),
    });
  }
  for (let i = 0; i < samples.length; i++) {
    const before = samples[Math.max(0, i - 2)].u;
    const after = samples[Math.min(samples.length - 1, i + 2)].u;
    samples[i].direction = Math.sign(after - before) || (i < samples.length / 2 ? 1 : -1);
  }
  return samples;
}

function distance(a, b) {
  return Math.hypot(a.x - b.x, a.z - b.z);
}
function driverDistance(a, b) {
  return Math.hypot(a.bobX - b.bobX, a.bobY - b.bobY);
}
function nearest(anchor, candidates, used, constrainBob) {
  let best = null;
  for (let i = 0; i < candidates.length; i++) {
    if (used.has(i) || candidates[i].direction !== anchor.direction) continue;
    const pos = distance(anchor, candidates[i]);
    if (pos > positionTolerance) continue;
    const bob = driverDistance(anchor, candidates[i]);
    if (constrainBob && bob > bobDriverTolerance) continue;
    const score = pos + (constrainBob ? bob * 2 : 0);
    if (!best || score < best.score) best = { index: i, score, pos, bob };
  }
  return best;
}

const arms = sessions.map(load);
const used = [null, new Set(), new Set(), new Set()];
const quartets = [];
for (const anchor of arms[0]) {
  const matches = [];
  let ok = true;
  for (let arm = 1; arm < 4; arm++) {
    const match = nearest(anchor, arms[arm], used[arm], arm === 1);
    if (!match) { ok = false; break; }
    matches.push(match);
  }
  if (!ok) continue;
  for (let arm = 1; arm < 4; arm++) used[arm].add(matches[arm - 1].index);
  quartets.push([
    anchor,
    arms[1][matches[0].index],
    arms[2][matches[1].index],
    arms[3][matches[2].index],
  ]);
}

for (const quartet of quartets) for (const sample of quartet) {
  if (!sample.metrics) sample.metrics = metric(sample.file);
}

function summarize(values) {
  const abs = values.map(Math.abs).sort((a, b) => a - b);
  const mean = values.reduce((a, b) => a + b, 0) / values.length;
  return {
    mean,
    meanAbs: abs.reduce((a, b) => a + b, 0) / abs.length,
    rms: Math.sqrt(values.reduce((a, b) => a + b * b, 0) / values.length),
    p95Abs: abs[Math.floor(0.95 * (abs.length - 1))],
  };
}

const positionErrors = quartets.flatMap(q => q.slice(1).map(s => distance(q[0], s)));
const bobErrors = quartets.map(q => driverDistance(q[0], q[1]));
const result = {};
for (const key of ['p90', 'p99', 'ge180', 'ge200', 'gradient', 'edge32']) {
  const voxelOnBobOn = quartets.map(q => q[0].metrics[key] - q[1].metrics[key]);
  const voxelOnBobOff = quartets.map(q => q[2].metrics[key] - q[3].metrics[key]);
  const interaction = voxelOnBobOn.map((value, i) => value - voxelOnBobOff[i]);
  result[key] = {
    voxelEffectBobOn: summarize(voxelOnBobOn),
    voxelEffectBobOff: summarize(voxelOnBobOff),
    factorialInteraction: summarize(interaction),
  };
}

console.log(JSON.stringify({
  root,
  mapping: Object.fromEntries(names.map((name, i) => [name, sessions[i]])),
  roi,
  matching: {
    positionTolerance,
    bobDriverTolerance,
    matchedQuartets: quartets.length,
    directions: {
      forward: quartets.filter(q => q[0].direction > 0).length,
      backward: quartets.filter(q => q[0].direction < 0).length,
    },
    positionError: summarize(positionErrors),
    bobDriverErrorOnArms: summarize(bobErrors),
  },
  result,
}, null, 2));
