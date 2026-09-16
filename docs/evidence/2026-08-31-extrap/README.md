> 本文所述 commit id 为 2026-09-17 历史重写前的旧 id；映射见 `docs/COMMIT-ID-REMAP-2026-09-17.md`

# 2026-08-31 方案A:远程灯方向预测外推 — 证据包

## 背景与目标

用户实机复测(08-31):坑36/坑37 修复后远程移动闪烁"基本消失"✅,但发现 B 眼里
远程光斑的转动滞后 A 的本地视角(~100-250ms)。诊断结论 = 原版玩家实体同步链固有延迟
(A 端 20Hz 打包 + 集成服 20Hz tick 转发 + B 端 lerpSteps=3 渐近收敛 + 渲染 1-tick
插值窗);修闪烁前同延迟被 20Hz 台阶抖动掩盖。用户批准方案A(客户端预测外推)。

## 实现(commit 见 CHANGELOG)

- `RemoteLookPredictor`(新,纯 JVM 可测):同源角速度 ω̂=(O→current)/tick,
  显示角外推 extrapTicks×ω̂;双层钳制(ω̂≤20°/tick、|ext|≤12°,传送级突变不作预测)
  + 外推量 EMA(τ=80ms,稳态无损、起停 ~160ms 平滑过渡);状态按实体 id,2s 未见即清。
  默认 extrapTicks=1.25,运行时 `!extrap <0-3|off>` / `!extrap log on|off` 调参
  (DebugCommandRelay,客户端本地;/taclight 走服务端管不到 B 的客户端状态)。
- 接线:ClientSpotlightUploader.collectRemoteLights(远程手持灯+枪灯共用预测方向)。
- 附带修复:relay `!light` 补 sendSetLight(原只切本地状态不上报服务端,双端 ssbo 假 count);
  `!diag` 增 DIAG-REMOTE 探针(syncReady/accessor id/各玩家 flash 标志)。

## 验证结果

### 1. 契约(离线 JVM)
RemoteLookPredictorContract 15 项全绿(首见直取/稳态收敛/急停单调无翻转/传送双层钳制/
负对称/off 直通清态/陈旧复活/极端调参保护/configure 解析);AllContracts 整体 PASS。

### 2. 静止恒等(实机,双端 LAN)
A 静止(B 旁观同机位):`!extrap off` vs `!extrap 1.25` 两帧
imgdiff **changed=41/409920 px(0.01%),meanDiff 0.15,maxDiff 45**(星空噪声级)
→ 静止时 ext=0,行为与 9465dcd 逐位一致,零回归。
文件:static-extrap-off.png / static-extrap-on.png / static-identity-imgdiff.json

### 3. 阶跃转向响应(实机,/tp 连续 7×15° yaw 扫掠,B 端校准日志)
step-response-extrap.log(43 行,truth=同源插值角 / pred=预测角 / omega / ext):
- **ext 有界**:整轮峰值 |ext|=10.17° < 12° 上限;
- **急停回落平滑单调无翻转**:每次停顿 ext 从 ~8° 指数衰减至 0.45°(≈250ms,符合 τ=80ms);
- **符号正确**:负向转动 → ext 负(超前量指向转动方向);
- truth 列的 ±16° 振荡 = /tp 传送风暴伪影(瞬间 15° 跳变 + 头/身插值通道冲突),
  真实鼠标转动为连续 20Hz 包不出现(坑36 修复的前提场景),非本次改动引入。

### 4. 直走回归(实机,轻量目检版)
A 朝墙直走 2.5s(z 8→1.3,W 键注入正常释放无粘滞):走前/走后 B 帧光斑稳定、
肩部压缩后砖纹全程可读、无噪声闪。走直线 ⇒ ω̂=0 ⇒ ext=0,与用户已验收的
9465dcd 行为逐位一致(由 2 的像素恒等背书)。walk-before.png / walk-after.png

## 排障记录(本次"同步坏了"的真相,非同步系统缺陷)

1. 测试中途 A 被场景预置自带的蜘蛛推下平台淹死;**死亡玩家的实体不被跟踪** →
   B 读 flash=false、ssbo 假 count=1。复活(用户手动点重生)后即恢复。
   预防:创造模式 + `/kill @e[type=!player]` + doMobSpawning false。
2. relay `!light` 原本只切本地状态、从不上报服务端(只有 L 键路径和
   `/taclight light` 服务端命令会走 C2S→实体数据→广播)→ 已修(补 sendSetLight)。
3. 后台 PostMessage 鼠标点击对 GLFW 屏界面(死亡界面)无效(键盘消息可以,
   合成鼠标点击不行)→ 死亡界面自动化救援不可行,靠预防或人工。

## 复现

双实例(直接 gradlew 后台任务,A: `runClient -PtaclightQuickPlay=test`,B:
`runClientObserver -PtaclightJoin=<port> -PtaclightUser=ObserverB`);`/taclight scene wall`;
清怪+创造+清雨;A `/tp Dev 1997.5 121 6.5 -155.57 2.08` + `/taclight light on Dev`;
B `/tp ObserverB 2002.5 121 6.5 155.57 2.08` + `!extrap log on` + `!diag`。

## 待用户体感验收

A 窗口鼠标转视角,B 窗口看光斑跟随:滞后感应明显小于修复前;急停允许极轻微
滑行回落(~0.2s 内)。若仍嫌滞后:B 端中继 `!extrap 1.75`(最大 3.0,过冲增大);
若出现明显过冲/打滑:`!extrap 1.0` 或 `!extrap off` 对照。
