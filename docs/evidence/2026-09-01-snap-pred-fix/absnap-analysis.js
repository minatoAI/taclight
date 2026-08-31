#!/usr/bin/env node
// absnap-analysis2.js —— snap+pred 实机 A/B 对比终版(同输入 /tp 步进扫视)
'use strict';
const fs = require('fs');
const wrapDeg = (x) => ((x % 360) + 540) % 360 - 180;

const txt = fs.readFileSync(process.argv[2], 'utf8');
const tsOf = (h, m, s, ms) => (+h) * 3600 + (+m) * 60 + (+s) + (+ms) / 1000;

const sessions = [];
let cur = null;
for (const m of txt.matchAll(/\[\d+[^\]]* (\d+):(\d+):(\d+)\.(\d+)\] .*(LOOKTRACE-(?:START|END)[^\r\n]*|LOOKTRACE t=[^\r\n]*)/g)) {
  const sec = tsOf(m[1], m[2], m[3], m[4]);
  const body = m[5];
  if (body.startsWith('LOOKTRACE-START')) { cur = { t0: sec, rows: [] }; sessions.push(cur); }
  else if (body.startsWith('LOOKTRACE-END')) { if (cur) { cur.t1 = sec; cur = null; } }
  else if (cur) {
    const r = body.match(/t=([\d.]+) pt=([\d.]+) hO=(-?[\d.]+) hC=(-?[\d.]+) bO=(-?[\d.]+) bC=(-?[\d.]+) pO=(-?[\d.]+) pC=(-?[\d.]+) base=\((-?[\d.]+),(-?[\d.]+)\) om=(-?[\d.]+) ext=(-?[\d.]+)/);
    if (r) cur.rows.push({ hC: +r[4], base: +r[9], ext: +r[12] });
  }
}
const marks = [];
for (const line of txt.split('\n')) {
  if (!line.includes('RELAY bsnap ->')) continue;
  const tm = line.match(/\[\d+[^\]]* (\d+):(\d+):(\d+)\.(\d+)\]/);
  const mm = line.match(/基角 (on|off)/);
  if (tm && mm) marks.push({ sec: tsOf(tm[1], tm[2], tm[3], tm[4]), mode: mm[1] });
}
console.log('bsnap 切换:', marks.map(k => `${k.sec.toFixed(1)}s=${k.mode}`).join('  ') || '(未找到)');
const onSec = marks.find(k => k.mode === 'on')?.sec ?? 1e9;

function analyze(s) {
  if (s.rows.length < 10) return null;
  let jumpMax = 0, eMin = 1e9, eMax = -1e9, tailMax = 0;
  const n = s.rows.length;
  for (let i = 1; i < n; i++) {
    const d0 = s.rows[i - 1].base + s.rows[i - 1].ext;
    const d1 = s.rows[i].base + s.rows[i].ext;
    jumpMax = Math.max(jumpMax, Math.abs(wrapDeg(d1 - d0)));
  }
  for (const r of s.rows) {
    const e = wrapDeg(r.base + r.ext - r.hC);
    eMin = Math.min(eMin, e); eMax = Math.max(eMax, e);
  }
  for (let i = Math.floor(n * 0.5); i < n; i++) {
    tailMax = Math.max(tailMax, Math.abs(wrapDeg(s.rows[i].base + s.rows[i].ext - s.rows[i].hC)));
  }
  return { n, jumpMax, errP2P: eMax - eMin, tailMax, t0: s.t0 };
}
const agg = { old: [], new: [] };
const per = [];
for (const s of sessions) {
  const a = analyze(s);
  if (!a) continue;
  a.mode = s.t0 < onSec ? 'old' : 'new';
  agg[a.mode].push(a);
  per.push(a);
}
for (const mode of ['old', 'new']) {
  const list = agg[mode];
  if (!list.length) { console.log(`\n== ${mode}: 无会话 ==`); continue; }
  const avg = (f) => list.reduce((s, x) => s + f(x), 0) / list.length;
  const mx = (f) => Math.max(...list.map(f));
  console.log(`\n== ${mode === 'old' ? '旧管线(!bsnap off = rotLerp+方案A)' : '新管线(!bsnap on = snap+pred)'} == ${list.length} 会话`);
  console.log(`  帧间最大跳变 jumpMax: 平均 ${avg(x => x.jumpMax).toFixed(2)}°  最差 ${mx(x => x.jumpMax).toFixed(2)}°`);
  console.log(`  光斑-真值误差峰峰 errP2P: 平均 ${avg(x => x.errP2P).toFixed(2)}°  最差 ${mx(x => x.errP2P).toFixed(2)}°`);
  console.log(`  静止尾段最大误差 tailMax: 平均 ${avg(x => x.tailMax).toFixed(2)}°  最差 ${mx(x => x.tailMax).toFixed(2)}°`);
}
console.log('\n逐会话:');
for (const a of per) {
  console.log(`  ${a.mode.padEnd(4)} t=${a.t0.toFixed(0)}s rows=${String(a.n).padStart(3)} jump=${a.jumpMax.toFixed(2)}° errP2P=${a.errP2P.toFixed(2)}° tail=${a.tailMax.toFixed(2)}°`);
}
