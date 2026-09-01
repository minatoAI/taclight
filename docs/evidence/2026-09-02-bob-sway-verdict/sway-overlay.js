// 把 sway-track 输出画回帧上验证:红线=检测位置。用法: node sway-overlay.js <csvFile> <f1,f2,...>
const path = require('path');
const fs = require('fs');
const { decodePng, encodePng } = require(path.join(__dirname, '..', 'imgdiff.js'));
const dir = 'E:/dshHome/mc-mod-spotlight-attachment/taclight/run-observer/mcap/run-20260902-025855-096-p4228/s0005/screenshots';
const csv = fs.readFileSync(process.argv[2], 'utf8').trim().split('\n');
const hdr = csv[0].split(',');
const frames = process.argv[3].split(',').map(Number);
const byFrame = {};
for (const line of csv.slice(1)) { const p = line.split(','); byFrame[parseInt(p[0], 10)] = p; }
for (const f of frames) {
  const img = decodePng(fs.readFileSync(path.join(dir, 'shot-' + String(f).padStart(6, '0') + '.png')));
  const p = byFrame[f];
  const marks = [];
  for (let i = 2; i < hdr.length; i++) if (p[i] !== '') marks.push({ name: hdr[i], x: parseFloat(p[i]) });
  for (const m of marks) {
    const xi = Math.round(m.x);
    const y0 = parseInt(m.name.split('_y')[1], 10);
    for (let y = y0 - 14; y <= y0 + 14; y++) {
      if (y < 0 || y >= img.height) continue;
      for (const dx of [-2, -1, 0, 1, 2]) {
        const x = xi + dx;
        if (x < 0 || x >= img.width) continue;
        const i4 = (y * img.width + x) * 4;
        img.data[i4] = 255; img.data[i4 + 1] = 0; img.data[i4 + 2] = 0;
      }
    }
  }
  // 提亮 2x,RGBA→RGB
  const w = img.width, h = img.height;
  const rgb = Buffer.alloc(w * h * 3);
  for (let i = 0; i < w * h; i++)
    for (let c = 0; c < 3; c++)
      rgb[i * 3 + c] = Math.min(255, img.data[i * 4 + c] * 2);
  const out = path.join(__dirname, 'sway-mark-' + f + '.png');
  fs.writeFileSync(out, encodePng(rgb, w, h));
  console.log('wrote', out, 'marks:', marks.map(m => m.name + '@' + m.x.toFixed(0)).join(' '));
}
