# 2026-09-04 前向体素取数精度修复（坑102）— 实机证据包

用户在体感中报告：墙面/地面出现“不存在的方块的阴影”，杂乱方形阴影出现在不该有的位置。
与坑100横贯硬带不同：坑100是调用点复用 post-DoLighting color.rgb 作 albedo（宿主阴影二次放大）；
本轮是前向 `taclight_vox_fetch` 用 float 整字除法读 2bit 分类，字值超过 float 24 位精度即舍入错位，
空↔实心翻转=凭空多出/少掉整块方形阴影。

## 根因（坑102）

- 旧取数：`float w = float(voxData[word]); div*=4.0 循环; code = mod(floor(w/div), 4.0)`。
  float 尾数仅 24 位，word 超过 2^24（本场景常见，如 50331651→50331652、4294967295→4294967296、
  50331649→50331648、16777217→16777216，Node 实算）即舍入：code 3→0 之类错位，
  空格子被判实心（多出方形假阴影）或实心被判空（该有的遮挡漏掉）。
- 修法：uint 域内逐槽剥除 `uint w = voxData[word]; for(b<slot) w = w/4u; code = float(w%4u)`。
  小值 0..3 转 float 精确，全程整数域，无舍入。slot=idx-word*16（与 Java VoxelField pack
  `(idx&15)*2` 同语义）。
- 注入字符数：A/B 双端均 `+7859`（本轮 resid=rawAlbedo+uint-peel 均已在位；启动期管线逐次构建
  首批 +7869 为同模板不同半体长度，稳定后 +7859，与 19:xx 轮一致）。

## 判定数字（B 端 wall_back，gunless，开关对照）

| 图 | 状态 | 全图 mean/p50/p90 | ge128/ge200/ge250 |
|---|---|---|---|
| b_off.png（21:21:18） | 灯关（handheld=false） | 21.5/16.6/47.7 | 1662/368/38 |
| b_on.png（21:21:46） | 灯开（handheld=true） | 48.3/25.2/166.4 | 58258/300/20 |

- 全图 off→on：meanDiff **29.58**/maxDiff 175/changed 107735/409920（26.3%，中央锥池整块点亮）。
- 热点 bbox(280,190,560,340)：off mean **20.8**/p90 50.7/ge128=68 → on mean **136.6**/p50 160.7/p90
  182.4/ge128=**23255**/ge200=**0**/ge250=**0**——锥池居中提亮、有衰减、无死白、无杂乱方形假阴影。
- 目检：on 帧锥池柔和居中（左黑墙面为 scene 黑墙体本身，非阴影；右白砖墙面干净，
  无用户报的那种“不存在方块的方形杂影”）。

## 文件

- `b_off.png` / `b_on.png` — B 端 wall_back 开关对照（!shot 直读帧缓冲）
- `log-excerpt.txt` — 注入行（+7859/+7869）/LIGHT-SYNC/RELAY exec/shot/CAM goto/SCENE wall
- `manifest.sha256` — 哈希

## 复算

```powershell
node tools/imgdiff.js run-observer/screenshots/2026-09-04_21.21.18.png run-observer/screenshots/2026-09-04_21.21.46.png
node tools/lumastats.js run-observer/screenshots/2026-09-04_21.21.46.png --bbox 280,190,560,340
```

## 改动（随本轮提交）

- `src/main/java/dev/taclight/interop/TemplateLibrary.java`：FORWARD_VOX_STUB 取数改 uint 剥除
  （slot 语义修正 + /4u + %4u），tie eps 1e-6、FUZZ 0.35 保持。
- `src/test/java/dev/taclight/interop/InlineCoreContract.java`：+2 断言（37→39），禁 float 整字除法路径。
- `AllContracts: ALL PASS`（TemplateLibrary 37 / InlineCore 39 / ScenePlan 207）。
