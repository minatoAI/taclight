// 全枪械 gun_light 许可覆盖文件生成器(配合 gen-gunlight-allow.sh;纯 node,零依赖)。
// 用法: node gen-gunlight-allow.js <tacz解包根> <输出目录>
// 输入根 = tacz 默认包 assets/tacz/custom/tacz_default_gun 的解包目录。
'use strict';
const fs = require('fs');
const path = require('path');
const [root, out] = process.argv.slice(2);
if (!root || !out) {
  console.error('usage: node gen-gunlight-allow.js <tacz-unpacked-root> <out-dir>');
  process.exit(2);
}
const OUR = '#taclight:taclight_laser';
const idxDir = path.join(root, 'data/tacz/index/guns');
const allowDir = path.join(root, 'data/tacz/tacz_tags/attachments/allow_attachments');
const ids = fs.readdirSync(idxDir).filter(f => f.endsWith('.json')).map(f => f.slice(0, -5)).sort();
let updated = 0, unchanged = 0, created = 0;
fs.mkdirSync(out, { recursive: true });
for (const id of ids) {
  const src = path.join(allowDir, id + '.json');
  let arr;
  if (fs.existsSync(src)) {
    arr = JSON.parse(fs.readFileSync(src, 'utf8'));
    if (!Array.isArray(arr)) throw new Error(id + ': allow file is not an array');
    if (!arr.includes(OUR)) { arr.push(OUR); updated++; } else { unchanged++; }
  } else {
    arr = [OUR]; created++;
  }
  fs.writeFileSync(path.join(out, id + '.json'), JSON.stringify(arr, null, 2) + '\n');
}
console.log(`guns=${ids.length} updated=${updated} unchanged=${unchanged} created=${created}`);
