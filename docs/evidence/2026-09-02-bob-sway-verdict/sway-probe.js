// 探针:解一张 ABBA s0005(bob on + voxel on)帧,放大平台区域供特征选点。
const path = require('path');
const fs = require('fs');
const { decodePng, encodePng } = require(path.join(__dirname, '..', 'imgdiff.js'));

const dir = 'E:/dshHome/mc-mod-spotlight-attachment/taclight/run-observer/mcap/run-20260902-025855-096-p4228/s0005/screenshots';
const img = decodePng(fs.readFileSync(path.join(dir, 'shot-000120.png')));
console.log('dims', img.width, 'x', img.height, '(RGBA)');

// 裁平台区 [x0,x1)x[y0,y1) 提亮 4x 输出
const x0 = 0, y0 = 240, x1 = 700, y1 = 480, gain = 4;
const w = x1 - x0, h = y1 - y0;
const out = { width: w, height: h, channels: 3, data: Buffer.alloc(w * h * 3) };
for (let y = 0; y < h; y++) {
  for (let x = 0; x < w; x++) {
    const si = ((y0 + y) * img.width + (x0 + x)) * 4;
    const di = (y * w + x) * 3;
    for (let c = 0; c < 3; c++) {
      out.data[di + c] = Math.min(255, img.data[si + c] * gain);
    }
  }
}
fs.writeFileSync(path.join(__dirname, 'sway-probe.png'), encodePng(out.data, w, h));
console.log('wrote tools/.session/sway-probe.png');
