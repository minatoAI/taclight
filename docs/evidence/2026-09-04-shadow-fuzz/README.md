# 前向 DDA 掠边假阴影修复（2026-09-04，Complementary r5.9 前向注入 FUZZ 版）

> 接 `docs/evidence/2026-09-04-emission10-entity/`（发射 10 + 实体不透明 + 漏光复核三项绿）。
> 分支 `interop/core-extract`（未提交，未 push）。A=InteropA3（wall_front，灯开 handheld+gun），
> B=ObserverB；双端 `ComplementaryReimagined + enableShaders=true`。
> pre=修复前旧代码（注入 `+7576/+7586 chars`），post=FUZZ 版（注入 `+7869/+7859 chars`，见 log-excerpt）。

## 判定（结论先行：黑齿已消，只剩柔和左渐变；锥池无回归、无死白）

| 项 | 帧 | 数字 / 所见 | 读法 |
|---|---|---|---|
| 灯开前后（修前→修后） | pre_on（11:55:53）vs post_on（12:14:40） | 全图 imgdiff threshold=8：meanDiff **1.62**，changed **12204/409920=3.0%**，maxDiff 227，bbox [0,83,853,479] | 差异集中原咬痕区；锥池主体不动 |
| 枪灯严格前后对照 | pre_off_gunonly（11:56:43）vs post_off_gunonly（12:16:09） | 全图 imgdiff threshold=8：meanDiff **1.49**，changed **12745/409920=3.1%**，maxDiff 227，bbox [0,88,853,479] | 同机位同灯态（handheld=false gun=true），差值=修复本身 |
| 肉眼 | pre_on 墙面左半块黑色锯齿咬痕 + 阶梯齿；post_on 同区只剩柔和左渐变 | 黑齿消失，锥池居中完整 | 修前修后同机位（wall_front）可比 |
| 热点无回归 | hotspot bbox 280,190,560,340（lumastats）：post_on mean **84.4**/p90 169.2/ge200 459/**ge250 0**；pre_on mean 78.2；pre_off 70.9；post_off 73.5 | 亮度与发射 10 轮（85.1/p90 180.5）同一档，无死白（frac250=0） | FUZZ 只吃掠边影缘，不动锥池峰值 |
| 灯态链 | pre：light on（11:54:22 ACK true/true）→ pre_on；light off（11:56:27 ACK false/true）→ pre_off；post：scene wall + kit + cam wall_front + lan 25560 → post_on（ACK true/true）；light off（12:15:56 ACK false/true）→ post_off | 灯态 ACK 逐帧可查，见 log-excerpt | 前后灯态对齐 |
| back 命令 | 11:54:24 `RELAY back -> screen closed`（A）；12:17:49 同行（B，菜单事故实战） | setScreen(null) 与“回到游戏”同入口，菜单挡帧 2 秒自愈 | 修程序化缺口（坑96），非渲染改动 |

## 根因（定案）

- 现象：A 视角墙面左半块黑色锯齿咬痕 + 阶梯齿（用户报阴影破碎）。
- 排除链：猪影（单猪只够挡约 1 格，咬痕半面墙）/ 月影（双端对不上）/ 灯位偏移（pre 手电居中帧放大仍有咬痕）。
- 机制：前向标量 DDA 掠射末步进错邻格、墙体素即判 0（float 累积排序翻转）+ 零 FUZZ 硬归零；
  前向模板 tie 多轴逻辑虽在，但 eps `abs(tNext)*1e-5` 过松且无软化带吸收残差。
- 修法（照搬主线已验证配方，主线 `VoxelDda.FUZZ_BLOCKS=0.35` + `tieEps=max(1e-6,|entry|*1e-6)`）：
  tie eps `1e-5` 改 `1e-6` + 穿透软化带 0.35（穿透 `<0.35` 按比例放行 `T*=1.0-f`，
  `>=0.35` 仍回 0；字面量内联，前向零预处理指令红线）。墙后深穿遮挡基线不变（漏光 CLOSED 不受影响）。
- TDD：先加红断言（penLen + 除 0.35 + `T*=1.0-f` + eps 1e-6）跑红，再实现跑绿。

## 复算

```
node tools/imgdiff.js docs/evidence/2026-09-04-shadow-fuzz/pre_off_gunonly.png docs/evidence/2026-09-04-shadow-fuzz/post_off_gunonly.png --threshold 8 --json
# → {meanDiff:1.4857, changed:12745, maxDiff:227}
node tools/imgdiff.js docs/evidence/2026-09-04-shadow-fuzz/pre_on.png docs/evidence/2026-09-04-shadow-fuzz/post_on.png --threshold 8 --json
# → {meanDiff:1.6162, changed:12204, maxDiff:227}
node tools/lumastats.js docs/evidence/2026-09-04-shadow-fuzz/post_on.png docs/evidence/2026-09-04-shadow-fuzz/pre_off_gunonly.png --bbox 280,190,560,340
# → post_on {mean:84.4, p90:169.2, ge200:459, ge250:0} / pre_off {mean:70.9, p90:168.1}
./gradlew-interop.cmd taclightContracts
# → TemplateLibrary 35 / InlineCore 37 / ScenePlan 207, AllContracts: ALL PASS
```

## 收尾状态

- 代码未提交未 push（等批准）：`DebugCommandRelay`（!back）+ `TemplateLibrary`（FUZZ）+ `InlineCoreContract`（新断言）。
- 双端已由用户手动关闭（B 12:19:21 / A 12:19:24，优雅退出，世界全存盘）。
- 待用户目验：post_on 黑齿已消、只剩柔和左渐变是否为预期效果（实例已关，要看需重开摆回约 8 分钟）。
