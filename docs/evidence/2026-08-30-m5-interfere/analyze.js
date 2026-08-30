'use strict';
/*
 * analyze.js — interfere 双光源叠加的像元级检验(一次性分析,配套本证据包)。
 * 前提:d1/d2/d3 为 DBG8 表面光条视图(×6 增益 + γ0.45 编码,final 前 early-return,
 *        线性域亮度),同机位三态:B 单开 / 双开 / A 单开。
 * 检验:反解码 lin(v) = (v/255)^(1/0.45) / 6(v=255 为削顶,排除);
 *        在环带 |band| lin1+lin3 ∈ [0.008,0.14] 内检验 lin2 ≤ lin1+lin3+tol
 *        且统计 ratio = lin2/(lin1+lin3) 分布 —— ratio≈1 即单次计数(物理线性叠加)。
 * 用法:node analyze.js
 */
const fs = require('fs');
const path = require('path');
const { decodePng } = require('../../../tools/imgdiff.js');

const DIR = __dirname;
const GAIN = 6.0, GAMMA = 0.45, TOL = 0.006;
const BBOX = [220, 90, 770, 370]; // 避开教程框(y>=378)/HUD(y>=420)/手部(x>=760)

const lin = (v) => (v >= 255 ? NaN : Math.pow(v / 255, 1 / GAMMA) / GAIN);

function load(name) {
  const img = decodePng(fs.readFileSync(path.join(DIR, name)));
  return { w: img.width, h: img.height, d: img.data };
}
const d1 = load('d1_dbg8_b_only.png'), d2 = load('d2_dbg8_both.png'), d3 = load('d3_dbg8_a_only.png');
if (d1.w !== d2.w || d1.w !== d3.w) throw new Error('size mismatch');

let n = 0, clip2 = 0, viol = 0, violMax = 0;
const ratios = [], ratiosMax = [], rDiff = [];
for (let y = BBOX[1]; y <= BBOX[3]; y++) {
  for (let x = BBOX[0]; x <= BBOX[2]; x++) {
    const i = (y * d1.w + x) * 4;
    const l1 = lin(d1.d[i]), l2 = lin(d2.d[i]), l3 = lin(d3.d[i]);
    const s = l1 + l3, mx = Math.max(l1, l3);
    if (isNaN(l2)) { clip2++; continue; }          // 双开削顶像素
    if (isNaN(l1) || isNaN(l3)) continue;           // 单开已削顶:无法取和,排除
    n++;
    if (s >= 0.008 && s <= 0.14) {
      ratios.push(l2 / s);
      ratiosMax.push(l2 / mx);
      rDiff.push([l2 - s, l2 - mx]);
      if (l2 > s + TOL) { viol++; violMax = Math.max(violMax, l2 - s); }
    }
  }
}
const stats = (a) => {
  const t = [...a].sort((x, y) => x - y);
  const q = (p) => t[Math.floor(p * (t.length - 1))];
  return { mean: Math.round((t.reduce((u, v) => u + v, 0) / t.length) * 1000) / 1000,
    p05: Math.round(q(0.05) * 1000) / 1000, p50: Math.round(q(0.5) * 1000) / 1000,
    p95: Math.round(q(0.95) * 1000) / 1000 };
};
// 判别:l2 更接近 (l1+l3) 还是 max(l1,l3)?比较两种模型的残差
const resSum = rDiff.map((r) => Math.abs(r[0]));
const resMax = rDiff.map((r) => Math.abs(r[1]));
console.log(JSON.stringify({
  bandPx: n, clip2px: clip2,
  ratioSum: stats(ratios), ratioMax: stats(ratiosMax),
  residAbsSum: stats(resSum), residAbsMax: stats(resMax),
  violateFrac: Math.round((viol / n) * 10000) / 10000,
  violateMaxExcess: Math.round(violMax * 10000) / 10000,
}, null, 1));
