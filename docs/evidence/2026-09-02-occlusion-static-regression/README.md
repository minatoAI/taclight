# 2026-09-02 · 遮挡静态数字回归:FUZZ 0.35+坑57+坑58 三重变更下复测

## 结论

**四项判定数字全部与 09-01 验收基线吻合,零回归。** 用户 09-02 复盘时指出
"遮挡检测在影子晃动检查中确认过没问题",本轮按 09-01 同协议实机复测把
"静态数字回归"悬置项关闭:软化带 TACLIGHT_VOX_FUZZ 0.35(3738fca)、
坑57 全矩阵坐标换算(9530b09 系列)、坑58 体素 DDA 无条件先执行(8fb47c5)
三重变更叠加后,墙后漏光消除与直射池恒等行为与 09-01 完全一致。

## 判定数字(协议与工具同 09-01:scene wall + B 端墙顶俯视,F2 真机截图,
`node tools/lumastats.js <png> --bbox 150,150,700,360`,`node tools/imgdiff.js a b --json`)

布防:Dev (2000.5,121,6.5) yaw180 pitch+10 手持灯 ON(服务端真源);
ObserverB (2000.5,124,0.5) 站墙顶。B 端 `!voxel off/on` A/B。

| 判定 | !voxel off (SSO) | !voxel on (DDA) | 09-01 历史 | 判读 |
|---|---|---|---|---|
| 北视漏光 ROI mean | **82.2** | **44.3** | 82.3 / 44.3 | DDA 后=无灯基线 43.7,漏光消除复现 |
| 北视 p90 / p99 | 110 / 128 | 46.8 / 48 | 110.6/126.1, 46.8/48.0 | 光晕整体消失 |
| imgdiff N (off vs on) | meanDiff 14.996, changed 40.0% | bbox=[0,0,743,403] | 14.94 / 40% | 差异=漏光区域本身 |
| 南视直射池 off vs on | mean 64.5 = 64.5(p50 44.2/p90 207.7/p99 215.5/max 216.8/亮区 14163px **全等**) | | 64.5 / 64.5 | **零回归** |
| imgdiff S | meanDiff 2.623, changed 3.19% | | 2.58 / 3.2% | 差异仅 HUD/toast 噪声 |

截图(screenshots/):`06.37.41`=北视 off(漏光晕可见)、`06.37.48`=北视 on
(全黑=基线)、`06.38.13`=南视 off、`06.38.19`=南视 on(逐分位恒等)。

## 复现步骤

1. `tools/mp-session.ps1` 双端;`oculus.properties enableShaders=true` + 双端 `!reload`
   (收尾规则把包禁用了,实测前先拉起;测毕再禁用)。
2. A 中继:`/taclight scene wall` → 三件套 → `/tp Dev 2000.5 121 6.5 185 10` →
   `/tp Dev 2000.5 121 6.5 180 10`(坑48 头角重发)→ `/tp ObserverB 2000.5 124 0.5 180 55`
   → `/taclight light on Dev`。
3. B 中继 `!voxel off` → drive.ps1 postkey F2 → `!voxel on` → F2(北视对);
   `/tp ObserverB 2000.5 124 0.5 0 45` 后同法(南视对)。
4. lumastats + imgdiff 判定(命令见上)。

## 边界

- 树叶/植被透射观感(0.4/0.75 每格)仍属用户体感项,本轮未单独标定(与 09-01 相同)。
- 体积束(composite1)灯侧遮挡仍为开放项(束段穿墙未立项,等实机可见)。
