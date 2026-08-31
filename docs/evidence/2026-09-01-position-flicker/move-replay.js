#!/usr/bin/env node
// 位置链修复离线回放(09-01 深夜③):在录得的步行会话上对比三种显示臂
//   lerp   = lerp(pt, posO, posC)  现状(基线)
//   snapT  = 对重建快照目标 T(=X_prev+3ΔX)做延迟段插值(50ms)
//   snapP  = snapT + 速度预测超前(v̂ EMA×ticks×0.05)
// 指标:去趋势(0.5s)峰峰残差(格)、帧间最大跳变(格)
'use strict';
const fs = require('fs');
const LOG = process.argv[2];
const PX_PER_M = 240;
const ROW = /LOOKTRACE t=([\d.]+) pt=([\d.]+) .*?base=\((-?[\d.]+),(-?[\d.]+)\) om=(-?[\d.]+) ext=(-?[\d.]+) posO=\((-?[\d.]+),(-?[\d.]+),(-?[\d.]+)\) posC=\((-?[\d.]+),(-?[\d.]+),(-?[\d.]+)\)/;

function parseSessions(text) {
  const sessions = []; let cur = null;
  for (const line of text.split(/\r?\n/)) {
    let m = line.match(/MCAP-OPEN session=(s\d+)/);
    if (m) { cur = { id: m[1], rows: [] }; continue; }
    m = line.match(ROW);
    if (m && cur) cur.rows.push({ pt: +m[2], o: [+m[7], +m[8], +m[9]], c: [+m[10], +m[11], +m[12]] });
    if (/MCAP-CLOSE/.test(line) && cur) { sessions.push(cur); cur = null; }
  }
  if (cur) sessions.push(cur);
  return sessions.filter(s => s.rows.length > 100);
}
const lerp = (a, b, t) => a + (b - a) * t;
function p2p(xs) { let mn = Infinity, mx = -Infinity; for (const v of xs) { if (v < mn) mn = v; if (v > mx) mx = v; } return mx - mn; }
function jumpMax(xs) { let mx = 0; for (let i = 1; i < xs.length; i++) mx = Math.max(mx, Math.abs(xs[i] - xs[i - 1])); return mx; }
function resid(xs, half = 16) { // 0.5s @62fps ≈ 16 帧
  return xs.map((_, i) => {
    let s = 0, c = 0;
    for (let j = Math.max(0, i - half); j <= Math.min(xs.length - 1, i + half); j++) { s += xs[j]; c++; }
    return xs[i] - s / c;
  });
}
const clamp = (v, m) => Math.max(-m, Math.min(m, v));

// 回放一轴(单位:格);frames 帧序列,取该轴 o/c
function replayAxis(rows, ax, cfg) {
  const FRAME = 1 / 62; // fps≈62(mcap 日志实测)
  let disp = 0, from = 0, to = 0, segT = -9, cum = 0, prevC = null;
  let vHat = 0, ext = 0;
  const out = [];
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i], c = r.c[ax], o = r.o[ax];
    if (cfg === 'lerp') { disp = lerp(o, c, r.pt); }
    else {
      // 快照目标回放:posC 变化帧=一次 ease(除数按 3,环路稳态成立)→ T += 3Δ
      if (prevC === null) { cum = c; from = to = c; segT = i; disp = c; }
      else {
        const d = c - prevC;
        if (Math.abs(d) > 1e-9) {
          const teleport = Math.abs(d) > 1.0; // 单帧跳 >1 格=传送,直接落位
          cum = teleport ? c : cum + 3 * d;
          from = to; to = cum; segT = i;
          if (teleport) { from = to; }
        }
        const frac = Math.min(1, Math.max(0, (i - segT) / 3)); // 50ms ≈ 3 帧
        disp = from + (to - from) * frac;
        if (cfg === 'snapP') { // 两段式预测:速度 EMA(τ≈9帧)→ 超前 EMA(τ≈5帧)
          const inst = prevC === null ? 0 : (c - prevC) / (3 * FRAME); // 格/s
          vHat += (clamp(inst, 20) - vHat) * (1 - Math.exp(-FRAME / 0.15));
          ext += (clamp(vHat * 1.25 * 0.05, 0.75) - ext) * (1 - Math.exp(-FRAME / 0.08));
          disp += ext;
        }
      }
    }
    out.push(disp);
    prevC = c;
  }
  return out;
}

const text = fs.readFileSync(LOG, 'utf8');
const sessions = parseSessions(text);
console.log('会话     类型      ' + ['lerp 残差', 'snapT 残差', 'snapP 残差', 'lerp跳变', 'snapT跳变', 'snapP跳变'].map(s => s.padStart(9)).join(' ') + '   (格;×240=px)');
for (const s of sessions) {
  const xr = p2p(s.rows.map(r => r.c[0])), zr = p2p(s.rows.map(r => r.c[2]));
  const ax = zr > xr ? 2 : 0;
  const kind = ax === 2 ? '纵深' : '平移';
  const arms = ['lerp', 'snapT', 'snapP'].map(cfg => replayAxis(s.rows, ax, cfg));
  const res = arms.map(a => p2p(resid(a)));
  const jmp = arms.map(a => jumpMax(resid(a)));
  console.log(s.id.padEnd(8), kind.padEnd(4),
    res.map(v => v.toFixed(4).padStart(9)).join(' '),
    '  ', jmp.map(v => v.toFixed(4).padStart(9)).join(' '),
    '  px:', (res[2] * PX_PER_M).toFixed(0));
}
