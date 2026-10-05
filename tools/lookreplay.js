#!/usr/bin/env node
/**
 * lookreplay.js —— 远程光"边缘闪烁"消融分析器(09-01)。
 *
 * 输入:游戏日志中的 LOOKTRACE 行(逐帧角度链路,!looktrace 采集;兼容旧 EXTRAP 行的 ext 回放)。
 * 原理:同一份逐帧信号(hO/hC/pt/om/ext),离线回放各管线配置,输出对比矩阵 ——
 *   raw        = 原始 20Hz 阶梯(坑36 前)          —— 锚点
 *   lerp       = 坑36 同源插值 rotLerp(pt,O,C)      —— 源1(边界锯齿)可见度
 *   lerp+pred  = 现行默认(lerp + 方案A ext)        —— 源1+源2
 *   chaser     = 修复候选:解耦临界阻尼追随器,目标 = hC + v̂×超前(s''=ωn²·err−2ωn·s')
 *   chaser0    = chaser 无超前(隔离追随器自身贡献)
 * 指标:稳态扫视峰峰 p2p°、tick 边界跳变 jumpMax°、20Hz 频带幅度(Goertzel)、
 *       6.5m 墙面换算 cm、O-滞后异常分布(机制判定:hO_new ≠ hC_prev 的幅度)。
 *
 * 用法:
 *   node tools/lookreplay.js --selftest                 已知答案自测
 *   node tools/lookreplay.js --log run/logs/latest.log  分析 LOOKTRACE 数据
 *   node tools/lookreplay.js --log <旧日志> --extrap    只回放旧 EXTRAP 行(无 O/C)
 */
'use strict';
const fs = require('fs');

const DEG = Math.PI / 180;
const wrapDeg = (x) => ((x % 360) + 540) % 360 - 180;
const rotLerp = (pt, o, c) => o + pt * wrapDeg(c - o);

// ---------- 解析 ----------
function parseLooktrace(txt) {
  const re = /LOOKTRACE t=([\d.]+) pt=([\d.]+) hO=(-?[\d.]+) hC=(-?[\d.]+) bO=(-?[\d.]+) bC=(-?[\d.]+) pO=(-?[\d.]+) pC=(-?[\d.]+) base=\((-?[\d.]+),(-?[\d.]+)\) om=(-?[\d.]+) ext=(-?[\d.]+)/g;
  const rows = []; let m;
  while ((m = re.exec(txt))) {
    rows.push({ tMs: +m[1], pt: +m[2], hO: +m[3], hC: +m[4], bO: +m[5], bC: +m[6],
      pO: +m[7], pC: +m[8], baseYaw: +m[9], om: +m[12], ext: +m[13] });
  }
  return rows;
}
// 同一日志可能含多次采集(每次 START 一个会话);跨会话拼接会产生假边界(实测踩过)。
// 按 START/END 标记切会话,返回数组。
function splitSessions(txt) {
  const re = /(\[([^\]]+)\] )?(?:\[TacLight\] )?(LOOKTRACE-(?:START|END)[^\r\n]*|LOOKTRACE t=[^\r\n]*)/g;
  const sessions = []; let cur = null; let m;
  while ((m = re.exec(txt))) {
    const stamp = m[2] || '', body = m[3];
    if (body.startsWith('LOOKTRACE-START')) {
      cur = { start: stamp, id: (body.match(/id=(\d+)/) || [])[1], type: (body.match(/type=(\S+)/) || [])[1], rows: [] };
      sessions.push(cur);
    } else if (body.startsWith('LOOKTRACE-END')) {
      if (cur) { cur.end = stamp; cur = null; }
    } else if (cur) {
      cur.rows.push(body);
    }
  }
  return sessions;
}
function rowsFromSession(sess) {
  const re = /t=([\d.]+) pt=([\d.]+) hO=(-?[\d.]+) hC=(-?[\d.]+) bO=(-?[\d.]+) bC=(-?[\d.]+) pO=(-?[\d.]+) pC=(-?[\d.]+) base=\((-?[\d.]+),(-?[\d.]+)\) om=(-?[\d.]+) ext=(-?[\d.]+)/;
  return sess.rows.map(l => {
    const m = l.match(re);
    return { tMs: +m[1], pt: +m[2], hO: +m[3], hC: +m[4], bO: +m[5], bC: +m[6],
      pO: +m[7], pC: +m[8], baseYaw: +m[9], om: +m[12], ext: +m[13] };
  });
}
function parseExtrap(txt) { // 旧格式:无 O/C/pt,只有 truth/omega/ext
  const re = /(\d+):(\d+):(\d+)\.(\d+)\].*?EXTRAP id=\d+ truth=\((-?[\d.]+),(-?[\d.]+)\) pred=\((-?[\d.]+),(-?[\d.]+)\) omega=\((-?[\d.]+),(-?[\d.]+)\) ext=\((-?[\d.]+),(-?[\d.]+)\)/g;
  const rows = []; let m;
  while ((m = re.exec(txt))) {
    rows.push({ tMs: 0, truthYaw: +m[5], om: +m[9], ext: +m[11] });
  }
  return rows;
}

// ---------- 管线回放(与 Java 侧同式) ----------
function replay(rows, cfg) {
  // cfg: {mode:'raw'|'lerp', snap:bool, pred:{ticks,tauV,tauOut}|null, chaser:{omegaN,tauV,leadSec}|null}
  const out = [];
  let ext = 0, vHat = 0, s = rows.length ? rows[0].hC : 0, u = 0, prevC = null, prevT = null;
  // 快照插值状态:用自己的 C 历史(不碰 O/C 异常对),段 = [C_{k-1} → C_k] 在 C_k 到达后播放
  let snapFrom = null, snapTo = null, snapT0 = 0, snapCum = rows.length ? rows[0].hC : 0;
  for (const r of rows) {
    const dt = prevT == null ? 0.008 : Math.min(0.1, Math.max(1e-4, (r.tMs - prevT) / 1e3));
    let display;
    if (cfg.chaser) {
      const { omegaN, tauV, leadSec } = cfg.chaser;
      if (prevC != null) {
        const dCdt = wrapDeg(r.hC - prevC) / Math.max(dt, 1e-3);
        vHat += (dCdt - vHat) * (1 - Math.exp(-dt / tauV));
      }
      const tgt = r.hC + vHat * leadSec;
      const err = wrapDeg(tgt - s);
      const acc = omegaN * omegaN * err - 2 * omegaN * u;
      u += acc * dt; s += u * dt;
      display = s;
    } else if (cfg.snap) {
      // 快照插值(延迟一段):样本 C_k 到达后,在 [t_k, t_k+50ms] 播放 C_{k-1}→C_k。
      // 只依赖自己的 C 历史,不碰 O/C 对 → 构造上位置连续,锯齿/异常免疫。
      if (prevC == null) { snapFrom = snapCum; snapTo = snapCum; snapT0 = r.tMs; }
      else if (wrapDeg(r.hC - prevC) !== 0) {
        snapCum += wrapDeg(r.hC - prevC);
        snapFrom = snapTo; snapTo = snapCum; snapT0 = r.tMs;
      }
      const frac = Math.min(1, Math.max(0, (r.tMs - snapT0) / 50)); // 假设 50ms tick 节拍
      display = snapFrom + (snapTo - snapFrom) * frac;
    } else {
      display = cfg.mode === 'raw' ? r.hC : rotLerp(r.pt, r.hO, r.hC);
    }
    if (cfg.pred) {
      const tgt = Math.max(-20, Math.min(20, r.om)) * cfg.pred.ticks;
      const tau = cfg.pred.tauOut || 0.08;
      if (cfg.snap) { // snap 臂:速度 EMA(τv)再乘超前 —— 源2 的解法
        vHat += (r.om / 0.05 - vHat) * (1 - Math.exp(-dt / cfg.pred.tauV));
        ext += (vHat * cfg.pred.ticks * 0.05 - ext) * (1 - Math.exp(-dt / tau));
      } else {
        ext += (tgt - ext) * (1 - Math.exp(-dt / tau));
      }
      display += ext;
    }
    out.push({ tMs: r.tMs, display, r });
    prevC = r.hC; prevT = r.tMs;
  }
  return out;
}

// ---------- 指标 ----------
function boundaries(rows) { // O-滞后异常:tick 边界处 hO_new 相对上一帧 hC 的差
  const ev = [];
  for (let i = 1; i < rows.length; i++) {
    if (Math.abs(rows[i].hO - rows[i - 1].hO) > 1e-4 || wrapDeg(rows[i].hC - rows[i - 1].hC) !== 0) {
      // 边界 = O 或 C 步进;滞后 = 上一帧渲染末值(≈hC_prev)与本帧起点(hO)之差
      ev.push({ i, lag: wrapDeg(rows[i - 1].hC - rows[i].hO), stepC: wrapDeg(rows[i].hC - rows[i - 1].hC) });
    }
  }
  return ev;
}
function jumpMax(disp) {
  let mx = 0;
  for (let i = 1; i < disp.length; i++) mx = Math.max(mx, Math.abs(wrapDeg(disp[i].display - disp[i - 1].display)));
  return mx;
}
function steadyWin(rows) { // 最大稳态扫视窗:|om|>1 且符号一致的连续段(取最长)
  let best = null, cur = [];
  for (const r of rows) {
    if (Math.abs(r.om) > 1) cur.push(r);
    else { if (!best || cur.length > best.length) best = cur; cur = []; }
  }
  if (!best || cur.length > best.length) best = cur;
  return best && best.length >= 8 ? best : null;
}
function goertz20(disp, rows) { // 20Hz 频带幅度(5ms 均匀重采样 + Goertzel)
  if (disp.length < 40) return 0;
  const fs = 200, step = 5;
  const xs = [];
  for (let t = disp[0].tMs; t <= disp[disp.length - 1].tMs; t += step) {
    let j = disp.findIndex(d => d.tMs >= t);
    if (j <= 0) { xs.push(disp[0].display); continue; }
    const a = disp[j - 1], b = disp[j], f = (t - a.tMs) / Math.max(1e-6, b.tMs - a.tMs);
    xs.push(a.display + f * wrapDeg(b.display - a.display));
  }
  const w = 2 * Math.PI * 20 / fs, coeff = 2 * Math.cos(w);
  let s1 = 0, s2 = 0;
  for (const x of xs) { const s0 = x + coeff * s1 - s2; s2 = s1; s1 = s0; }
  const power = Math.max(0, s1 * s1 + s2 * s2 - coeff * s1 * s2);
  return 2 * Math.sqrt(power) / xs.length; // 幅度(度)
}
function resample5(disp) { // 5ms 均匀网格
  const xs = [];
  for (let t = disp[0].tMs; t <= disp[disp.length - 1].tMs; t += 5) {
    let j = disp.findIndex(d => d.tMs >= t);
    if (j <= 0) { xs.push({ t, v: disp[0].display }); continue; }
    const a = disp[j - 1], b = disp[j], f = (t - a.tMs) / Math.max(1e-6, b.tMs - a.tMs);
    xs.push({ t, v: a.display + f * wrapDeg(b.display - a.display) });
  }
  return xs;
}
function rippleP2P(disp, winMs = 150) { // 高频纹波:去 150ms 滑动均值趋势后的残差峰峰(闪烁量主指标)
  const xs = resample5(disp);
  if (xs.length < 10) return 0;
  const half = Math.max(1, Math.round(winMs / 5 / 2));
  const res = xs.map((p, i) => {
    let s = 0, n = 0;
    for (let j = Math.max(0, i - half); j <= Math.min(xs.length - 1, i + half); j++) { s += xs[j].v; n++; }
    return p.v - s / n;
  });
  // 裁掉两端半窗:单侧 MA 在边界处对斜坡有 ±斜率×半窗 的截断伪影(自测曾踩)
  const trim = xs.length > 2 * half + 2 ? half : 0;
  const core = res.slice(trim, res.length - trim);
  return Math.max(...core) - Math.min(...core);
}
function analyze(rows, label, distM) {
  if (!rows.length) { console.log(`  ${label}: 无数据`); return; }
  const dO = wrapDeg(rows[rows.length - 1].hC - rows[0].hC);
  console.log(`  ${label}: ${rows.length} 帧, 总转角 ${dO.toFixed(1)}°, 时长 ${(rows[rows.length - 1].tMs / 1e3).toFixed(1)}s`);
  const be = boundaries(rows);
  if (be.length) {
    const lags = be.map(e => Math.abs(e.lag)).sort((a, b) => a - b);
    const nz = lags.filter(l => l > 0.05);
    console.log(`    [机制] 边界事件 ${be.length} 个; O-滞后: 中位 ${(lags[Math.floor(lags.length / 2)]).toFixed(3)}°, 最大 ${lags[lags.length - 1].toFixed(3)}°, 非零比例 ${(nz.length / be.length * 100).toFixed(0)}%`);
  }
  const win = steadyWin(rows);
  const report = (name, cfg) => {
    const disp = replay(rows, cfg);
    const all = jumpMax(disp);
    let p2p = 0, rip = 0, g20 = 0, lead = 0;
    if (win) {
      const first = win[0], last = win[win.length - 1];
      const sub = disp.filter(d => d.tMs >= first.tMs && d.tMs <= last.tMs);
      const vs = sub.map(d => d.display);
      p2p = Math.max(...vs) - Math.min(...vs);
      rip = rippleP2P(sub);
      g20 = goertz20(sub, sub);
      const baseLerp = replay(rows, { mode: 'lerp' });
      const baseWin = baseLerp.filter(d => d.tMs >= first.tMs && d.tMs <= last.tMs);
      lead = sub.reduce((a, d, i) => a + wrapDeg(d.display - baseWin[i].display), 0) / sub.length;
    }
    const cm = (rip * DEG * distM * 100);
    console.log(`    ${name.padEnd(12)} 纹波=${rip.toFixed(2)}° 墙面≈${cm.toFixed(1)}cm 20Hz幅度=${g20.toFixed(2)}° jumpMax=${all.toFixed(2)}° 稳态超前=${lead.toFixed(2)}°`);
  };
  report('raw', { mode: 'raw' });
  report('lerp', { mode: 'lerp' });
  report('lerp+pred', { mode: 'lerp', pred: { ticks: 1.25, tauOut: 0.08 } });
  report('snap', { snap: true });
  report('snap+pred', { snap: true, pred: { ticks: 1.25, tauV: 0.15, tauOut: 0.08 } });
  report('chaser', { chaser: { omegaN: 10, tauV: 0.10, leadSec: 1.25 * 0.05 } });
  report('chaser0', { chaser: { omegaN: 10, tauV: 0.10, leadSec: 0 } });
}
function analyzeExtrap(rows) { // 旧日志:只回放 ext(ω 序列在),验证纹波量级
  console.log(`  EXTRAP 行 ${rows.length} 条(无 O/C,仅 ext 回放)`);
  const nz = rows.filter(r => Math.abs(r.om) > 1);
  if (!nz.length) { console.log('    无稳态样本'); return; }
  const exts = nz.map(r => r.ext);
  console.log(`    |ω|>1 样本 ext 峰峰=${(Math.max(...exts) - Math.min(...exts)).toFixed(2)}° (含符号混合,粗口径)`);
}

// ---------- 自测(已知答案) ----------
function selftest() {
  let ok = 0; const check = (c, m) => { if (!c) throw new Error('FAIL: ' + m); ok++; console.log('PASS: ' + m); };
  // 合成:200fps,20Hz tick,静止→40°/s 扫视(2°/tick 阶梯)→静止;O/C 行为良好(O_{N+1}=C_N)
  const rows = []; let hC = 100, t = 0;
  for (let f = 0; f < 1200; f++) {
    const pt = (f % 10) / 10;
    if (f % 10 === 0 && f >= 200 && f < 700) hC += 2; // 每 tick +2°
    const hO = hC - ((f % 10 === 0 && f >= 200 && f < 700) ? 2 : (f < 200 ? 0 : (f >= 700 ? 0 : 2)));
    rows.push({ tMs: t, pt, hO, hC, baseYaw: rotLerp(pt, hO, hC), om: (f % 10 === 0 && f >= 200 && f < 700) ? 2 : 0, ext: 0 });
    t += 5;
  }
  // 1) O-滞后检测:良好 O/C 下应全零
  const lags = boundaries(rows).map(e => Math.abs(e.lag));
  check(lags.every(l => l < 1e-6), `自测1 良好O/C → O-滞后全零(实测 max ${Math.max(...lags).toExponential(1)})`);
  // 2) lerp 连续无跳变(锯齿只在 O/C 异常时出现)
  const jl = jumpMax(replay(rows, { mode: 'lerp' }));
  check(jl < 0.5, `自测2 lerp 边界跳变≈0(实测 ${jl.toFixed(3)}°)`);
  // 3) raw 阶梯跳变 = 2°
  const jr = jumpMax(replay(rows, { mode: 'raw' }));
  check(Math.abs(jr - 2) < 0.01, `自测3 raw 跳变=2°(实测 ${jr.toFixed(3)}°)`);
  // 4) snap 位置连续 + 纹波抑制:稳态高频纹波 < raw 的 20%,且帧间无跳变(<0.5°)
  const winRows = rows.filter(r => r.om > 1);
  const inWin = d => d.tMs >= winRows[0].tMs + 100 && d.tMs <= winRows[winRows.length - 1].tMs;
  const dispRaw = replay(rows, { mode: 'raw' }).filter(inWin);
  const dispSnap = replay(rows, { snap: true }).filter(inWin);
  const rRaw = rippleP2P(dispRaw);
  const rSnap = rippleP2P(dispSnap);
  check(rSnap < rRaw * 0.20, `自测4 snap 纹波 < raw×20%(snap ${rSnap.toFixed(3)}° vs raw ${rRaw.toFixed(2)}°)`);
  const jSnap = jumpMax(dispSnap);
  check(jSnap < 0.5, `自测4b snap 帧间跳变 <0.5°(实测 ${jSnap.toFixed(3)}°)`);
  // 4c) snap+pred:稳态超前 ≈ (ticks×50ms − 50ms 播放延迟)×v = 0.25tick×40°/s = 0.5°
  const dispSP = replay(rows, { snap: true, pred: { ticks: 1.25, tauV: 0.15, tauOut: 0.08 } }).filter(inWin);
  const baseLerpWin = replay(rows, { mode: 'lerp' }).filter(inWin);
  const lead = dispSP.reduce((a, d, i) => a + wrapDeg(d.display - baseLerpWin[i].display), 0) / dispSP.length;
  check(Math.abs(lead - 0.5) < 0.5, `自测4c snap+pred 稳态超前 ≈0.5°(实测 ${lead.toFixed(2)}°)`);
  // 5) Goertzel 已知答案:注入 1°@20Hz 正弦 → 幅度≈1
  const t0 = 1000, xs = [];
  for (let i = 0; i < 400; i++) xs.push({ tMs: t0 + i * 5, display: Math.sin(2 * Math.PI * 20 * i / 200) });
  const g = goertz20(xs, xs);
  check(Math.abs(g - 1) < 0.05, `自测5 Goertzel 1°@20Hz → ${g.toFixed(3)}°`);
  // 6) ext 回放 vs 直接 EMA 恒等
  const cfg = { mode: 'lerp', pred: { ticks: 1.25, tauOut: 0.08 } };
  const disp = replay(rows, cfg);
  let ext = 0, prevT = null, mx = 0;
  for (const r of rows) {
    const dt = prevT == null ? 0.008 : Math.min(0.1, Math.max(1e-4, (r.tMs - prevT) / 1e3));
    ext += (Math.min(20, r.om * 1.25) - ext) * (1 - Math.exp(-dt / 0.08));
    const got = disp.find(d => d.tMs === r.tMs).display - rotLerp(r.pt, r.hO, r.hC);
    mx = Math.max(mx, Math.abs(got - ext));
    prevT = r.tMs;
  }
  check(mx < 0.05, `自测6 ext 回放与直接 EMA 恒等(最大差 ${mx.toExponential(1)}°)`);
  console.log(`TOOLS-LOOKREPLAY SELFTEST PASS (${ok} checks)`);
}

// ---------- 主入口 ----------
const argv = process.argv.slice(2);
if (argv.includes('--selftest')) { selftest(); process.exit(0); }
const li = argv.indexOf('--log');
if (li < 0) { console.log('用法: --selftest | --log <file> [--extrap] [--dist 6.5] [--session <k>]'); process.exit(1); }
const txt = fs.readFileSync(argv[li + 1], 'utf8');
const dist = argv.includes('--dist') ? +argv[argv.indexOf('--dist') + 1] : 6.5;
console.log(`== lookreplay 消融分析: ${argv[li + 1]} (墙面距离 ${dist}m) ==`);
if (argv.includes('--extrap')) analyzeExtrap(parseExtrap(txt));
else {
  const sessions = splitSessions(txt);
  if (!sessions.length) { console.log('无 LOOKTRACE 会话(用 --extrap 分析旧日志?)'); process.exit(1); }
  console.log(`共 ${sessions.length} 个采集会话: ` + sessions.map((s, i) => `#${i + 1} id=${s.id}(${s.type}) ${s.rows.length}行 ${s.start}`).join(' | '));
  const k = argv.includes('--session') ? +argv[argv.indexOf('--session') + 1] - 1 : sessions.length - 1;
  const sess = sessions[Math.max(0, Math.min(sessions.length - 1, k))];
  console.log(`== 分析会话 #${k + 1} (id=${sess.id} ${sess.type}) ==`);
  analyze(rowsFromSession(sess), 'LOOKTRACE', dist);
}
