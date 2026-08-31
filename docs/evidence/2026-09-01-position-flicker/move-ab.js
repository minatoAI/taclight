#!/usr/bin/env node
// psnap A/B 对比(09-01 深夜③):同一真实步行输入下,旧管线(lerp o->C)与新管线(disp 列)
// 的墙光斑残差对比。disp = 灯光 SSBO 实际用的锚点(LOOKTRACE 新列,tgt/disp 09-01 深夜③ 增)。
'use strict';
const fs = require('fs');
const LOG = process.argv[2];
const PX_PER_M = 240;
const ROW = /LOOKTRACE t=([\d.]+) pt=([\d.]+) .*?base=\((-?[\d.]+),(-?[\d.]+)\) om=(-?[\d.]+) ext=(-?[\d.]+) posO=\((-?[\d.]+),(-?[\d.]+),(-?[\d.]+)\) posC=\((-?[\d.]+),(-?[\d.]+),(-?[\d.]+)\) tgt=\((-?[\d.]+),(-?[\d.]+),(-?[\d.]+)\) disp=\((-?[\d.]+),(-?[\d.]+),(-?[\d.]+)\)/;
const TS = /\[(\d{2})月(\d{2})2026 (\d{2}):(\d{2}):(\d{2})\.(\d{3})\]/; // 01月9月2026? 实际格式 019月2026 = 日+月+年

function tsOf(line) {
  const m = line.match(/\[(\d{2}):(\d{2}):(\d{2})\.(\d{3})\]/);
  if (!m) return null;
  return (+m[1]) * 3600 + (+m[2]) * 60 + (+m[3]) + (+m[4]) / 1000; // 当日秒
}
const lerp = (a, b, t) => a + (b - a) * t;
function p2p(xs) { let mn = Infinity, mx = -Infinity; for (const v of xs) { if (v < mn) mn = v; if (v > mx) mx = v; } return mx - mn; }
function resid(xs, half = 16) {
  return xs.map((_, i) => {
    let s = 0, c = 0;
    for (let j = Math.max(0, i - half); j <= Math.min(xs.length - 1, i + half); j++) { s += xs[j]; c++; }
    return xs[i] - s / c;
  });
}

// 会话切分 + psnap 标记(墙光斑用 spot = E + t*·d;方向锁定时 E 的 x/z 残差即光斑残差)
const text = fs.readFileSync(LOG, 'utf8');
const lines = text.split(/\r?\n/);
const sessions = [];
let cur = null, lastPsnap = 'on';
for (const line of lines) {
  const mOpen = line.match(/MCAP-OPEN session=(s\d+)/);
  if (mOpen) { cur = { id: mOpen[1], t0: tsOf(line), rows: [], psnap: lastPsnap }; continue; }
  const mPs = line.match(/RELAY psnap -> snap\+pred .*?(on|off)\(/);
  if (mPs) { lastPsnap = mPs[1]; continue; }
  const m = line.match(ROW);
  if (m && cur) cur.rows.push({
    pt: +m[2],
    o: [+m[7], +m[8], +m[9]], c: [+m[10], +m[11], +m[12]],
    tgt: [+m[13], +m[14], +m[15]], disp: [+m[16], +m[17], +m[18]],
  });
  if (/MCAP-CLOSE/.test(line) && cur) { sessions.push(cur); cur = null; }
}

console.log('会话     psnap  类型      帧   lerp残差   disp残差   改善    disp残差px');
for (const s of sessions) {
  if (s.rows.length < 100) continue;
  const rows = s.rows, n = rows.length;
  const xr = p2p(rows.map(r => r.c[0])), zr = p2p(rows.map(r => r.c[2]));
  const kind = zr > xr ? '纵深' : '平移';
  // 旧臂:lerp(pt,posO,posC) 的 x(平移)或 z(纵深,取 x 亦稳)残差——统一取光斑横向=x
  const armLerp = rows.map(r => lerp(r.o[0], r.c[0], r.pt) + 1.26);
  const armDisp = rows.map(r => r.disp[0] + 1.26);
  const rL = p2p(resid(armLerp)), rD = p2p(resid(armDisp));
  const gain = rL > 1e-9 ? (100 * (1 - rD / rL)).toFixed(0) : 'n/a';
  console.log(s.id.padEnd(8), s.psnap.padEnd(6), kind.padEnd(5), String(n).padStart(4),
    rL.toFixed(4).padStart(10), rD.toFixed(4).padStart(10), (gain + '%').padStart(6),
    (rD * PX_PER_M).toFixed(0).padStart(10));
}
