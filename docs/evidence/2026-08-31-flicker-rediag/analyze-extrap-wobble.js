// 08-31 深夜边缘闪烁再诊断:解析两端 latest.log 的 EXTRAP 行,量化稳态扫视段 ext 抖动。
// 用法:在 taclight/ 下  node analyze-extrap-wobble.js
// 输出:各端稳态扫视段数、ext 峰峰分档、Top 段明细(时间/样本/时长/extP2P/ω均值/ωP2P/翻转Hz)。
// 判读:ext 是方案A 叠加在光斑方向上的角度 → 稳态扫视中 extP2P 即预测纹波注入的边缘晃动幅度。
const fs = require('fs');

function parse(file) {
  const txt = fs.readFileSync(file, 'utf8');
  const re = /(\d+):(\d+):(\d+)\.(\d+)\].*?EXTRAP id=\d+ truth=\((-?[\d.]+),(-?[\d.]+)\) pred=\((-?[\d.]+),(-?[\d.]+)\) omega=\((-?[\d.]+),(-?[\d.]+)\) ext=\((-?[\d.]+),(-?[\d.]+)\)/g;
  const rows = []; let m;
  while ((m = re.exec(txt))) {
    const t = (+m[1]) * 3600e3 + (+m[2]) * 60e3 + (+m[3]) * 1e3 + (+m[4]);
    rows.push({ t, truthYaw: +m[5], omegaYaw: +m[9], extYaw: +m[11] });
  }
  return rows;
}

function analyze(tag, rows) {
  console.log(`=== ${tag} rows=${rows.length}`);
  if (!rows.length) return [];
  const bursts = []; let cur = [rows[0]];
  for (let i = 1; i < rows.length; i++) {
    if (rows[i].t - rows[i - 1].t > 1000) { bursts.push(cur); cur = []; }
    cur.push(rows[i]);
  }
  if (cur.length) bursts.push(cur);
  // 稳态扫视段:≥8 样本、≥300ms、≥60% 帧 |ω|>1 且方向一致 ≥85%
  const steady = bursts.filter(b => {
    if (b.length < 8 || b[b.length - 1].t - b[0].t < 300) return false;
    const nz = b.filter(r => Math.abs(r.omegaYaw) > 1);
    if (nz.length < b.length * 0.6) return false;
    const pos = nz.filter(r => r.omegaYaw > 0).length, neg = nz.length - pos;
    return Math.max(pos, neg) >= nz.length * 0.85;
  });
  const buckets = { 'p2p>0.5': 0, '>1': 0, '>2': 0, '>4': 0 };
  const stats = steady.map(b => {
    const exts = b.map(r => r.extYaw);
    const p2p = Math.max(...exts) - Math.min(...exts);
    const om = b.map(r => r.omegaYaw);
    const omMean = om.reduce((a, c) => a + c, 0) / om.length;
    const omP2p = Math.max(...om) - Math.min(...om);
    let flips = 0;
    for (let i = 2; i < b.length; i++) {
      const d1 = exts[i - 1] - exts[i - 2], d2 = exts[i] - exts[i - 1];
      if (d1 * d2 < 0) flips++;
    }
    const durS = (b[b.length - 1].t - b[0].t) / 1e3;
    return { t: b[0].t, n: b.length, durS, p2p, omMean, omP2p, flips, freqHz: durS > 0 ? flips / durS : 0 };
  }).sort((a, b) => b.p2p - a.p2p);
  for (const s of stats) {
    if (s.p2p > 0.5) buckets['p2p>0.5']++;
    if (s.p2p > 1) buckets['>1']++;
    if (s.p2p > 2) buckets['>2']++;
    if (s.p2p > 4) buckets['>4']++;
  }
  console.log('稳态扫视段 ext 抖动分档(段数):', JSON.stringify(buckets));
  console.log('ext p2p Top8(时间|帧|时长s|extP2P°|ω均值|ωP2P|翻转Hz):');
  for (const s of stats.slice(0, 8)) {
    const hh = String(Math.floor(s.t / 3600e3)).padStart(2, '0');
    const mm = String(Math.floor(s.t / 60e3) % 60).padStart(2, '0');
    const ss = String(Math.floor(s.t / 1e3) % 60).padStart(2, '0');
    console.log(`  ${hh}:${mm}:${ss} | n=${s.n} | ${s.durS.toFixed(1)} | ${s.p2p.toFixed(2)} | ${s.omMean.toFixed(2)} | ${s.omP2p.toFixed(2)} | ${s.freqHz.toFixed(1)}`);
  }
  return stats;
}

analyze('dev端(预测B,默认1.25)', parse('run/logs/latest.log'));
analyze('B端(预测dev,21:07后被误设2.0)', parse('run-observer/logs/latest.log'));
