'use strict';
const fs = require('fs');
const path = require('path');
const { decodePng } = require('../imgdiff.js');

const root = process.argv[2];
const sessionArgs = (process.argv[3] || 's0006,s0007,s0008,s0009').split(',');
const arms = [
  ['A1', sessionArgs[0], true, true],
  ['B1', sessionArgs[1], true, false],
  ['B2', sessionArgs[2], false, true],
  ['A2', sessionArgs[3], false, false],
];
const roi = [0, 40, 650, 420];

function corr(xs, ys) {
  const n = xs.length;
  const mx = xs.reduce((a,b)=>a+b,0)/n, my = ys.reduce((a,b)=>a+b,0)/n;
  let xy=0, xx=0, yy=0;
  for (let i=0;i<n;i++) { const x=xs[i]-mx,y=ys[i]-my; xy+=x*y;xx+=x*x;yy+=y*y; }
  return xx>0&&yy>0 ? xy/Math.sqrt(xx*yy) : null;
}
function quantile(a, q) { const b=[...a].sort((x,y)=>x-y); return b[Math.min(b.length-1,Math.floor(q*(b.length-1)))]; }
function solve(A,b) {
  const n=b.length, m=A.map((r,i)=>[...r,b[i]]);
  for(let c=0;c<n;c++) { let p=c; for(let r=c+1;r<n;r++) if(Math.abs(m[r][c])>Math.abs(m[p][c]))p=r; [m[c],m[p]]=[m[p],m[c]]; const d=m[c][c]; if(Math.abs(d)<1e-12)return null; for(let j=c;j<=n;j++)m[c][j]/=d; for(let r=0;r<n;r++)if(r!==c){const f=m[r][c];for(let j=c;j<=n;j++)m[r][j]-=f*m[c][j];} }
  return m.map(r=>r[n]);
}
function residual(values, samples) {
  const features=samples.map(s=>[1,s.u,s.u*s.u,s.u*s.u*s.u,s.dir]);
  const k=features[0].length, ata=Array.from({length:k},()=>Array(k).fill(0)), atb=Array(k).fill(0);
  for(let i=0;i<features.length;i++)for(let a=0;a<k;a++){atb[a]+=features[i][a]*values[i];for(let b=0;b<k;b++)ata[a][b]+=features[i][a]*features[i][b];}
  const beta=solve(ata,atb); return values.map((v,i)=>v-features[i].reduce((s,x,j)=>s+x*beta[j],0));
}
function metrics(file) {
  const img=decodePng(fs.readFileSync(file)), ch=img.data.length/(img.width*img.height);
  const [x0,y0,x1,y1]=roi, lum=[]; let ge150=0,ge180=0,ge200=0,grad=0,edges=0;
  const L=(x,y)=>{const i=(y*img.width+x)*ch;return .2126*img.data[i]+.7152*img.data[i+1]+.0722*img.data[i+2];};
  for(let y=y0;y<y1;y++)for(let x=x0;x<x1;x++){const l=L(x,y);lum.push(l);if(l>=150)ge150++;if(l>=180)ge180++;if(l>=200)ge200++;if(x>x0&&y>y0){const g=Math.max(Math.abs(l-L(x-1,y)),Math.abs(l-L(x,y-1)));grad+=g;if(g>=32)edges++;}}
  lum.sort((a,b)=>a-b); const n=lum.length;
  return {mean:lum.reduce((a,b)=>a+b,0)/n,p90:lum[Math.floor(.9*(n-1))],p99:lum[Math.floor(.99*(n-1))],ge150:ge150/n,ge180:ge180/n,ge200:ge200/n,grad:grad/n,edge32:edges/n};
}
function analyze(label, session, bob, voxel) {
  const dir=path.join(root,session), lines=fs.readFileSync(path.join(dir,'frames.csv'),'utf8').trim().split(/\r?\n/);
  const C=new Map(); for(const line of lines)if(line.startsWith('C,')){const r=line.split(',');C.set(+r[2],{x:+r[3],z:+r[5],walk:+r[14],amp:+r[16],enabled:/true/i.test(r[18])});}
  const shots=[]; for(const line of lines)if(line.startsWith('S,')){const r=line.split(','), c=C.get(+r[2]);if(c)shots.push({frame:+r[2],seq:+r[3],...c});}
  const x0=shots[0].x,z0=shots[0].z; let prevU=0;
  for(const s of shots){s.u=Math.hypot(s.x-x0,s.z-z0);s.dir=s.u>=prevU?1:-1;prevU=s.u;s.driver=s.enabled?Math.sin(Math.PI*s.walk)*s.amp:0;s.absDriver=s.enabled?Math.abs(Math.sin(Math.PI*s.walk)*s.amp):0;}
  const moving=shots.filter(s=>s.amp>=0.045);
  const data=moving.map(s=>({...s,m:metrics(path.join(dir,'screenshots',`shot-${String(s.seq).padStart(6,'0')}.png`))}));
  const out={label,session,bob,voxel,n:data.length};
  for(const key of ['mean','p90','p99','ge150','ge180','ge200','grad','edge32']){
    const vals=data.map(s=>s.m[key]), res=residual(vals,data), abs=res.map(Math.abs);
    out[key]={rawRange:Math.max(...vals)-Math.min(...vals),rms:Math.sqrt(res.reduce((a,b)=>a+b*b,0)/res.length),p95Abs:quantile(abs,.95),corrBob:corr(res,data.map(s=>s.driver)),corrAbsBob:corr(res,data.map(s=>s.absDriver))};
  }
  return out;
}
const result=arms.map(a=>analyze(...a));
console.log(JSON.stringify({roi,result},null,2));
