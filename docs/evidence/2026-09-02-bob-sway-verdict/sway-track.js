// 判决测量 v2:影子边界 vs 石柱棱边(世界几何特征)横向摆动对比。
// 判读:两者摆动幅度一致 → 相机重投影(透视呼吸,物理正确);
//       影界显著多摆 → 光源链有额外晃动(bug)。
// 用法: node sway-track.js <sessionDir> <frameStart> <frameEnd>
const path = require('path');
const fs = require('fs');
const { decodePng, encodePng } = require(path.join(__dirname, '..', 'imgdiff.js'));

const sessDir = process.argv[2];
const F0 = parseInt(process.argv[3] || '60', 10);
const F1 = parseInt(process.argv[4] || '140', 10);
const shotDir = path.join(sessDir, 'screenshots');
const shot = n => path.join(shotDir, 'shot-' + String(n).padStart(6, '0') + '.png');

// kind: 'edgeDarkL'=左暗右亮 50% 穿越;'seam'=局部最暗
const FEATURES = [
  { name: 'shadow_y320', y: 320, x0: 180, x1: 340, kind: 'edgeDarkL' },
  { name: 'shadow_y400', y: 400, x0: 150, x1: 330, kind: 'edgeDarkL' },
  { name: 'pillar_y400', y: 400, x0: 585, x1: 660, kind: 'band' },
  { name: 'pillar_y430', y: 430, x0: 585, x1: 660, kind: 'band' },
  { name: 'seam_y320', y: 320, x0: 380, x1: 580, kind: 'seam' },
];
const STEP = 18, WIDE = 60, MINCONTRAST = 20;
const K = [1, 2, 3, 2, 1];

function lum(img) {
  const { width: w, height: h, data } = img;
  const L = new Float32Array(w * h);
  for (let i = 0; i < w * h; i++)
    L[i] = 0.2126 * data[i * 4] + 0.7152 * data[i * 4 + 1] + 0.0722 * data[i * 4 + 2];
  return L;
}
function rowSmooth(L, w, y, x0, x1) {
  const out = [];
  for (let x = x0; x < x1; x++) {
    let s = 0, wn = 0;
    for (let k = -2; k <= 2; k++) {
      const xx = Math.min(w - 1, Math.max(0, x + k));
      s += K[k + 2] * L[y * w + xx]; wn += K[k + 2];
    }
    out.push(s / wn);
  }
  return out;
}
function locate(profile, x0, kind) {
  const n = profile.length;
  if (n < 20) return null;
  if (kind === 'band') {
    let mi = -1, mv = Infinity;
    for (let i = 1; i < n - 1; i++) if (profile[i] < mv) { mv = profile[i]; mi = i; }
    if (mi < 1 || mi > n - 2) return null;
    const a = profile[mi - 1], b = profile[mi], c = profile[mi + 1];
    const den = a - 2 * b + c;
    return x0 + mi + (den !== 0 ? Math.max(-1, Math.min(1, 0.5 * (a - c) / den)) : 0);
  }
  if (kind === 'seam') {
    let mi = -1, mv = Infinity;
    for (let i = 2; i < n - 2; i++) if (profile[i] < mv) { mv = profile[i]; mi = i; }
    if (mi < 2 || mi > n - 3) return null;
    const a = profile[mi - 1], b = profile[mi], c = profile[mi + 1];
    const den = a - 2 * b + c;
    return x0 + mi + (den !== 0 ? Math.max(-1, Math.min(1, 0.5 * (a - c) / den)) : 0);
  }
  const med = arr => { const s = [...arr].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
  const cut = Math.floor(n * 0.3);
  const d1 = med(profile.slice(0, cut)), b1 = med(profile.slice(n - cut));
  if (b1 - d1 < MINCONTRAST) return null;
  const thr = d1 + 0.5 * (b1 - d1);
  let px = null;
  for (let i = 1; i < n; i++) if (profile[i - 1] < thr && profile[i] >= thr) { px = i; break; }
  if (px === null || px < 1 || px > n - 2) return null;
  const a = profile[px - 1], b = profile[px];
  return x0 + px - 1 + (a !== b ? (thr - a) / (b - a) : 0.5);
}

// 解析 frames.csv:i-th C 行 ↔ i-th S 行(shot seq)
function parseBob() {
  const csv = fs.readFileSync(path.join(sessDir, 'frames.csv'), 'utf8').split('\n');
  let cCount = 0;
  const bobBySeq = {};
  for (const line of csv) {
    if (line.startsWith('C,')) cCount++;
    else if (line.startsWith('S,')) {
      const p = line.split(',');
      const seq = parseInt(p[3], 10);
      if (cCount >= 1) {
        const cLine = csv.map; // 占位,下面重新扫
      }
    }
  }
  // 重新按顺序配对:C 队列,遇 S 出队
  const q = [];
  for (const line of csv) {
    if (line.startsWith('C,')) {
      const p = line.split(',');
      q.push({ bob: parseFloat(p[16]), oBob: parseFloat(p[17]), walkDist: parseFloat(p[14]), walkDistO: parseFloat(p[15]) });
    } else if (line.startsWith('S,') && q.length) {
      const p = line.split(',');
      bobBySeq[parseInt(p[3], 10)] = q.shift();
    }
  }
  return bobBySeq;
}

(async () => {
  const bobBySeq = parseBob();
  const st = {};   // name → {prev, lost, x0,x1}
  for (const ft of FEATURES) st[ft.name] = { prev: null, lost: 0, base: ft };
  const rows = [];
  for (let f = F0; f <= F1; f++) {
    const file = shot(f);
    if (!fs.existsSync(file)) continue;
    const L = lum(decodePng(fs.readFileSync(file)));
    const cb = bobBySeq[f] || {};
    const fPhase = -(cb.walkDist !== undefined ? cb.walkDist + 0.5 * (cb.walkDist - (cb.walkDistO !== undefined ? cb.walkDistO : cb.walkDist)) : 0);
    const rec = { frame: f, bob: cb.bob !== undefined ? cb.bob : NaN, sway: (cb.bob !== undefined ? Math.sin(fPhase * Math.PI) * cb.bob * 0.5 : NaN) };
    for (const ft of FEATURES) {
      const s = st[ft.name];
      let x0 = ft.x0, x1 = ft.x1;
      if (s.prev !== null) {
        const w = s.lost > 0 ? WIDE : STEP;
        x0 = Math.max(0, Math.round(s.prev - w));
        x1 = Math.min(854, Math.round(s.prev + w));
      }
      const x = locate(rowSmooth(L, 854, ft.y, x0, x1), x0, ft.kind);
      if (x !== null && x >= ft.x0 - 40 && x <= ft.x1 + 40) {
        rec[ft.name] = x; s.prev = x; s.lost = 0;
      } else { rec[ft.name] = null; s.lost++; if (s.lost > 6) { s.prev = null; s.lost = 0; } }
    }
    rows.push(rec);
  }
  // 去趋势 + 统计
  const names = FEATURES.map(f => f.name);
  const stats = {};
  for (const nm of names) {
    const xs = rows.map(r => r[nm]);
    const osc = xs.map((_, i) => {
      const a = Math.max(0, i - 6), b = Math.min(xs.length - 1, i + 6);
      let s = 0, n = 0;
      for (let k = a; k <= b; k++) if (xs[k] !== null) { s += xs[k]; n++; }
      return (xs[i] !== null && n >= 5) ? xs[i] - s / n : null;
    });
    const vals = osc.filter(v => v !== null);
    stats[nm] = {
      rms: Math.sqrt(vals.reduce((s, v) => s + v * v, 0) / Math.max(1, vals.length)),
      n: vals.length, osc,
    };
  }
  // bob 相关
  function corr(series, bobArr) {
    const ps = [], pb = [];
    for (let i = 0; i < series.length; i++)
      if (series[i] !== null && !isNaN(bobArr[i])) { ps.push(series[i]); pb.push(bobArr[i]); }
    if (ps.length < 10) return NaN;
    const mA = ps.reduce((s, v) => s + v, 0) / ps.length, mB = pb.reduce((s, v) => s + v, 0) / pb.length;
    let num = 0, da = 0, db = 0;
    for (let i = 0; i < ps.length; i++) { const u = ps[i] - mA, v = pb[i] - mB; num += u * v; da += u * u; db += v * v; }
    return num / Math.sqrt(da * db);
  }
  console.log('frame,bob,swayPred,' + names.join(','));
  for (const r of rows)
    console.log(r.frame + ',' + (isNaN(r.bob) ? '' : r.bob.toFixed(4)) + ',' + (isNaN(r.sway) ? '' : r.sway.toFixed(4)) + ',' +
      names.map(nm => r[nm] === null ? '' : r[nm].toFixed(2)).join(','));
  console.error('---- osc RMS(px) / n / corrWithBob ----');
  for (const nm of names)
    console.error(nm.padEnd(12), 'rms=' + stats[nm].rms.toFixed(3), 'n=' + stats[nm].n,
      'corrSway=' + corr(stats[nm].osc, rows.map(r => r.sway)).toFixed(3));
  const shAvg = (stats.shadow_y320.rms + stats.shadow_y400.rms) / 2;
  const woAvg = (stats.pillar_y400.rms + stats.pillar_y430.rms) / 2;
  console.error('corrSway(shadow320)=' + corr(stats.shadow_y320.osc, rows.map(r=>r.sway)).toFixed(3) + ' corrSway(pillar400)=' + corr(stats.pillar_y400.osc, rows.map(r=>r.sway)).toFixed(3));
  console.error('shadowAvg=' + shAvg.toFixed(3), 'worldAvg(pillar)=' + woAvg.toFixed(3),
    'ratio=' + (shAvg / Math.max(1e-6, woAvg)).toFixed(3));
})();
