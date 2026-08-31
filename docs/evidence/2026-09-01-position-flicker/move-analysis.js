#!/usr/bin/env node
// 移动闪烁消融分析(09-01 深夜③)
// 从 B 端 latest.log 提取 LOOKTRACE 会话,把墙光斑晃动分解为:
//   位置链贡献(光源眼位 o->C 插值的抖动,1:1 投到墙)
//   方向链贡献(方向角抖动 × Dev->墙距离放大)
// 指标:去趋势峰峰(格)、跳变 max(cm/帧)、速度调制 p2p(格/s)、方向抖动(度)
'use strict';
const fs = require('fs');
const LOG = process.argv[2] || '/e/dshHome/mc-mod-spotlight-attachment/taclight/run-observer/logs/latest.log'
  .replace(/\//g, '\\');
const WALL_Z = 0.0;      // wall 预设:东西向墙,墙面 z=0
const PX_PER_M = 240;    // B 机位到光斑 ~6.5m,70°FOV 1920px ≈ 240px/格

const ROW = /LOOKTRACE t=([\d.]+) pt=([\d.]+) hO=(-?[\d.]+) hC=(-?[\d.]+) .*?base=\((-?[\d.]+),(-?[\d.]+)\) om=(-?[\d.]+) ext=(-?[\d.]+) posO=\((-?[\d.]+),(-?[\d.]+),(-?[\d.]+)\) posC=\((-?[\d.]+),(-?[\d.]+),(-?[\d.]+)\)/;

function parseSessions(text) {
  const sessions = [];
  let cur = null;
  for (const line of text.split(/\r?\n/)) {
    let m = line.match(/MCAP-OPEN session=(s\d+)/);
    if (m) { cur = { id: m[1], rows: [] }; continue; }
    m = line.match(ROW);
    if (m && cur) {
      cur.rows.push({
        t: +m[1], pt: +m[2], hO: +m[3], hC: +m[4],
        baseYaw: +m[5], basePitch: +m[6], om: +m[7], ext: +m[8],
        o: [+m[9], +m[10], +m[11]], c: [+m[12], +m[13], +m[14]],
      });
      continue;
    }
    if (/MCAP-CLOSE session=(s\d+)/.test(line) && cur) { sessions.push(cur); cur = null; }
  }
  if (cur) sessions.push(cur);
  return sessions.filter(s => s.rows.length > 30);
}

function movingAvgResidual(xs, win) { // 去趋势:滑动均值窗口(秒)换算帧
  const n = xs.length;
  if (n < 5) return xs.map(() => 0);
  const half = Math.max(2, Math.round(win / 2));
  return xs.map((_, i) => {
    let s = 0, c = 0;
    for (let j = Math.max(0, i - half); j <= Math.min(n - 1, i + half); j++) { s += xs[j]; c++; }
    return xs[i] - s / c;
  });
}
function p2p(xs) { let mn = Infinity, mx = -Infinity; for (const v of xs) { if (v < mn) mn = v; if (v > mx) mx = v; } return mx - mn; }
function jumpMax(xs) { let mx = 0; for (let i = 1; i < xs.length; i++) mx = Math.max(mx, Math.abs(xs[i] - xs[i - 1])); return mx; }
function lerp(a, b, t) { return a + (b - a) * t; }
function wrapDeg(d) { d %= 360; if (d > 180) d -= 360; if (d < -180) d += 360; return d; }

function analyze(s) {
  const rows = s.rows;
  const n = rows.length;
  // 位置链显示值(与灯锚点同式:o->C lerp)
  const ex = rows.map(r => lerp(r.o[0], r.c[0], r.pt));
  const ey = rows.map(r => lerp(r.o[1], r.c[1], r.pt));
  const ez = rows.map(r => lerp(r.o[2], r.c[2], r.pt));
  // 方向链显示值(bsnap 开时 base/ext 列=管线实际值)
  const yawD = rows.map(r => r.baseYaw + r.ext);
  const pitD = rows.map(r => r.basePitch);
  // 墙光斑坐标:spot = E + t*·dir,t* = (WALL_Z - Ez)/dz
  const spotX = [], spotY = [], tstar = [];
  for (let i = 0; i < n; i++) {
    const yaw = yawD[i] * Math.PI / 180, pit = pitD[i] * Math.PI / 180;
    const dx = -Math.sin(yaw) * Math.cos(pit), dy = -Math.sin(pit), dz = Math.cos(yaw) * Math.cos(pit);
    const ts = Math.abs(dz) < 1e-4 ? 9 : (WALL_Z - ez[i]) / dz;
    tstar.push(ts);
    spotX.push(ex[i] + ts * dx);
    spotY.push(ey[i] + ts * dy);
  }
  // 分类:平移(x 主导变化) / 纵深(z 变化)
  const xr = p2p(rows.map(r => r.c[0])), zr = p2p(rows.map(r => r.c[2]));
  const kind = zr > xr ? '纵深(缩放)' : '平移';
  // 去趋势残差(窗口 0.5s:保留 20Hz 抖动,滤掉扫掠趋势)
  const resX = movingAvgResidual(spotX, 0.5);
  const resY = movingAvgResidual(spotY, 0.5);
  // 分解:方向固定(把方向角换成会话中位数)重算光斑 → 残差差 = 方向贡献
  const medYaw = [...yawD].sort((a, b) => a - b)[Math.floor(n / 2)];
  const medPit = [...pitD].sort((a, b) => a - b)[Math.floor(n / 2)];
  const spotXfix = [], spotYfix = [];
  for (let i = 0; i < n; i++) {
    const yaw = medYaw * Math.PI / 180, pit = medPit * Math.PI / 180;
    const dx = -Math.sin(yaw) * Math.cos(pit), dy = -Math.sin(pit), dz = Math.cos(yaw) * Math.cos(pit);
    const ts = Math.abs(dz) < 1e-4 ? 9 : (WALL_Z - ez[i]) / dz;
    spotXfix.push(ex[i] + ts * dx); spotYfix.push(ey[i] + ts * dy);
  }
  const resXfix = movingAvgResidual(spotXfix, 0.5);
  const resYfix = movingAvgResidual(spotYfix, 0.5);
  // 速度调制(位置链):d(E)/dt 去趋势峰峰
  const vel = [];
  for (let i = 1; i < n; i++) {
    const dt = Math.max(1e-3, rows[i].t - rows[i - 1].t);
    const k = kind.startsWith('纵深') ? 2 : 0; // 纵深看 z,平移看 x
    vel.push(( (k ? ez[i] : ex[i]) - (k ? ez[i-1] : ex[i-1]) ) / dt);
  }
  const velMod = p2p(movingAvgResidual(vel, 0.5));
  // 方向抖动(度)
  const yawRes = movingAvgResidual(yawD, 0.5);
  return {
    id: s.id, kind, frames: n, dur: +(rows[n-1].t - rows[0].t).toFixed(1),
    spotP2P: Math.max(p2p(resX), p2p(resY)),                    // 光斑总晃动(格)
    posP2P: Math.max(p2p(resXfix), p2p(resYfix)),               // 位置链贡献
    dirP2P: Math.max(p2p(resX) - p2p(resXfix), p2p(resY) - p2p(resYfix)), // 方向链贡献(近似)
    jumpCm: jumpMax([...resX, ...resY]) * 100,                  // 帧间跳变(cm)
    velMod, yawJitDeg: p2p(yawRes),
    pxSpot: Math.max(p2p(resX), p2p(resY)) * PX_PER_M,
  };
}

const text = fs.readFileSync(LOG, 'utf8');
const sessions = parseSessions(text);
console.log('会话        类型        帧   时长  光斑晃动    位置链    方向链   帧间跳变  速度调制  方向抖动  光斑晃动px');
console.log('                                              (格)     (格)     (格)      (cm/帧)   (格/s)    (度)      (px)');
for (const s of sessions) {
  const a = analyze(s);
  console.log(
    a.id.padEnd(10), a.kind.padEnd(8), String(a.frames).padStart(4),
    String(a.dur).padStart(5) + 's',
    a.spotP2P.toFixed(4).padStart(9), a.posP2P.toFixed(4).padStart(9),
    a.dirP2P.toFixed(4).padStart(9), a.jumpCm.toFixed(2).padStart(8),
    a.velMod.toFixed(3).padStart(9), a.yawJitDeg.toFixed(3).padStart(9),
    a.pxSpot.toFixed(1).padStart(8));
}
