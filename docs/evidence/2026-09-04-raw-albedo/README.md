# 调用点反照率污染修复（2026-09-04，Complementary r5.9 前向注入 raw-albedo 版）

> 接 `docs/evidence/2026-09-04-shadow-fuzz/`（前向 DDA 掠边 FUZZ）。
> 分支 `interop/core-extract`（未提交，未 push）。B=ObserverB（gunless，handheld 干净开关），
> 机位 `wall_back`（2000,121.5,-8 yaw0 pitch5），`ComplementaryReimagined + enableShaders=true`，
> 注入 `+7903 chars`（raw-albedo 版；FUZZ 版 `+7859`，差值 +44 = 快照行，见 log-excerpt）。
> 同包附带：失焦弹菜单修复（坑101）实机验证帧。

## 判定（结论先行：横贯硬带已消，锥池柔和完整，无死白）

| 项 | 帧 | 数字 / 所见 | 读法 |
|---|---|---|---|
| 灯开关对照（同机位 wall_back） | b_off（19:15:15，ACK false/false）vs b_on（19:21:44，ACK true/false） | 全图 imgdiff：meanDiff **21.73**，changed **92223/409920=22.5%**，maxDiff 161，bbox [22,0,853,435] | 锥池点亮（墙面+地面+石板缝），差异=灯锥本身 |
| 肉眼 | b_on 墙面锥池柔和居中、无横向硬切分界；b_off 同区纯黑 | 用户报的"草地石板中央横贯亮暗带"类硬边不可见 | raw-albedo 修复成立 |
| 热点（bbox 280,190,560,340） | off mean **20.9**/p90 50.8；on mean **90.1**/p90 179.6/**ge200 34/ge250 0** | 与发射 10 轮（85.1/p90 180.5）同一档，无死白（frac250=0） | 只修 albedo 源，不动锥池峰值 |
| 墙 ROI（bbox 150,80,650,300） | off mean 13.2；on mean **46.7**/p90 114.9/ge250 **0** | 墙面被照亮但无饱和、无硬齿 | 与 FUZZ 轮"柔和左渐变"一致 |
| 失焦 8 秒（坑101） | b_focus8s（19:13:10）：准星+血条+锥池都在，无 GameMenu | B 在 A 被聚焦操作期间保持游戏内 | pauseOnLostFocus=false 生效 |

## 根因（定案，坑100）

- 现象：用户实机报灯锥区一条与灯无关的横向亮暗硬带（Complementary 前向注入改动之后出现）。
- 机制：宿主 `DoLighting` 内做 `color.rgb *= finalDiffuse + lightHighlight`（太阳阴影/月光/火把全乘进反照率）；
  调用点用 DoLighting **之后**的 `color.rgb` 作锥光 albedo = 宿主阴影被二次放大，形成与灯锥无关的硬切分界。
- 修法：DoLighting 调用首行前加一行快照 `vec3 taclightRawAlbedo = color.rgb;`，调用点 albedo 改用快照
  （entities/hand 保留 `* color.a` alpha 门）。模板 3 文件各 +1 算子（4 算子/文件），+44 chars。
- TDD：先加红断言（调用点含 `taclightRawAlbedo`、禁 `taclight_surface_lighting(viewPos, color.rgb`、禁 `/ 2048.0`）再实现。

## 复算

```
node tools/imgdiff.js docs/evidence/2026-09-04-raw-albedo/b_off.png docs/evidence/2026-09-04-raw-albedo/b_on.png
# → {meanDiff:21.7311, changed:92223, maxDiff:161}
node tools/lumastats.js docs/evidence/2026-09-04-raw-albedo/b_off.png docs/evidence/2026-09-04-raw-albedo/b_on.png --bbox 280,190,560,340
# → off {mean:20.9, p90:50.8} / on {mean:90.1, p90:179.6, ge200:34, ge250:0}
node tools/lumastats.js docs/evidence/2026-09-04-raw-albedo/b_off.png docs/evidence/2026-09-04-raw-albedo/b_on.png --bbox 150,80,650,300
# → off {mean:13.2} / on {mean:46.7, p90:114.9, ge250:0}
./gradlew-interop.cmd taclightContracts
# → TemplateLibrary 37 / InlineCore 37 / ScenePlan 207, AllContracts: ALL PASS
```

## 收尾状态

- 代码未提交未 push（等批准）：模板 raw-albedo（complementary-r5.9.json）+ TemplateLibraryContract 新断言
  + TemplateLibrary FUZZ + DebugCommandRelay `!back` + build.gradle B 失焦加固。
- 双端运行中（LAN 25560，B 灯开，包启用；收尾/交用户前按 §7 关灯+禁包）。
- 待用户目验：用户截图场景（草地+石板+土墙）的横贯硬带是否已消（本包为 wall_back 墙面验证；机位不同，结论同源）。
