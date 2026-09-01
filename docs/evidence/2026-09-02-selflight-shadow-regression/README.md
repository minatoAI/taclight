# 2026-09-02 · 自灯影子回归(坑58):同轴快速通道视图域阈值被 bob 平移调制

## 结论

坑57 全矩阵修复后用户实测:**远灯(别人静止灯)影子跳位已消 ✔;自灯影子反而
"不显示+移动闪烁" ✘ = 新回归**。本轮定位并修复;真机截图确认自灯影子恢复
(`selflight-fix-verify.png`:Dev 手持聚光灯,中央方块在灯池中投出清晰阴影)。

## 机制(数字)

composite.fsh vis 分流的**同轴快速通道**:`dot(lightView, lightView) < 0.25`
(灯距相机 <0.5 格 → vis=1,跳过一切遮挡计算;M1 热修 12,救 SSO 同轴假消光)。

- 坑57 修复前:`lightView = mat3(gbufferModelView)·scene` 丢 bob 平移 →
  灯距相机恒定 = 锚点世界偏移模长。
- 坑57 修复后:`lightView` 是**真实视图距离,含 bob 平移 ±0.1 格**,随步频振荡。
- 自灯锚点恰在阈值两侧:
  - 手持灯 `handheldOffset` = 前0.35 + 右0.22 + 下0.14 → 模长 **0.4365**
    (ClientSpotlightUploader.java:382),视图域 0.415~0.478,**恒 <0.5**;
  - 枪灯 = 相机系枪口偏移(MuzzlePoseCapture),典型 ~0.6,bob 下 **0.5 两侧穿越**。
- 翻转效果:命中快速通道 → `vis=1` 无影子;命中 DDA → 有影子。步频翻转 =
  用户所见"影子不显示+移动闪烁"。远灯距离 »0.5 永远走 DDA,不受影响 →
  用户观察"修了一半"。

## 修复(TDD,契约红→绿)

composite.fsh:
1. **DDA 无条件先执行**——体素 DDA 是世界空间射线,灯≈相机依然有效(起点格
   先步进后判定、终点格回退 1e-3,双端豁免);热修 12 的退化只属于屏幕空间
   SSO 回退,快速通道不得跳过主算法。
2. **同轴判定改场景域** `dot(lightScene, lightScene) < 0.25`(world−camera,
   无 bob,恒定),且只作为 DDA 无效(-1)时的回退分支。

契约:VoxelDdaContract 25→**29 项**(4 条新契约:禁视图域判定/场景域判定/
DDA 先于同轴判定/同轴豁免仅回退分支),`AllContracts: ALL PASS`
(contracts-output.txt)。

附带行为变化:**手持灯(0.4365<0.5,旧版从不投影)现在也投真实影子**——与
用户预期"自己的灯应有影子"一致;若需回退旧行为改回"同轴跳过 DDA"一行即可。

## 实机

- `syncShaderPack` + 双端 `!reload`(用户已自行禁用光影,本轮重新拉起):
  `Using shaderpack: taclight-shaders-dev`、0 编译错、无 "disabling shaders"。
- 真机截图 `selflight-fix-verify.png`(A=Dev,876×536,postkey ESC 收暂停菜单
  后 drive.ps1 -ProcId 18508):自灯灯池中央方块投影清晰、边缘为 FUZZ 0.35
  软半影。**截图后双端灯已关**(/taclight light off,日志 LIGHT-SYNC-ACK
  handheld=false gun=false)。
- 坑57 修复的用户体感验收(远灯不跳位)用户已确认 ✔;本轮自灯回归修复
  **用户体感终验通过 ✔(09-02 05:5x:"实测没啥摇晃的视觉瑕疵现象了")**
  ——bob 跳位链(坑57+坑58)闭环。复盘文档:
  docs/复盘学习-bob影子跳位战役-2026-09-02.md。

## 边界与未竟

- 静态数字回归(墙后漏光 ROI≈44.3/直射池 64.5)在 FUZZ 0.35+坑57+坑58 修复下
  **仍未补跑**,下轮布防优先。
- manifest.sha256 见同目录。
