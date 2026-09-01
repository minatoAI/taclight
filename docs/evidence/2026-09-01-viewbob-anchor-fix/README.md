# 2026-09-01 深夜⑤ · 第一人称灯锚 view-bob 根治:"地面条纹随观察者视角晃动同频放大"

## 结论

用户实测报告:观察另一个玩家的灯光光晕时,观察者一开始移动,地面照射出的条纹
就随观察者的视角晃动频率**有节奏地放大**。定位后确认**新算法(体素 DDA)不是
元凶**——病灶是 **第一人称手持灯锚定了「相机眼球」`cam.getPosition()`,而
1.20.1 行走 view-bob(横移摆动 + 姿态旋转)就注入在相机位置/旋转里**,灯源随
bob 摆动 → 灯源-地面几何以 bob 频率周期微变(近距池缘调制 ~3-6%)→ bloom
软阈值带(smoothstep 0.6→1.4)非线性放大 ~3 倍 → 看到"有节奏放大",与视角
晃动严格同频。修复:灯锚一律取**玩家眼位**(`getEyePosition`,bob-free,与自体
胶囊/SSO 豁免同源);freecam/第三人称语义不变。

## 排除清单(先排后修)

| 怀疑项 | 判定 |
|---|---|
| 远程灯数据摆动(对方灯姿态在跳) | 排除:LOOKTRACE 逐帧 `hO=hC=4.523 / pO=pC=16.875 / posO=posC` 全帧恒等 —— 远程灯重建数据是常数 |
| 体素 DDA(新算法) | 排除:Amanatides-Woo 是纯世界空间(`taclight_vox_transmit`),相机无关;`!voxel on/off` 同视角 A/B 池 rim 脉动幅度无差异 |
| 自适应曝光呼吸 | 排除:`TACLIGHT_EXPOSURE_LOCK=1`(锁定 1.0) |
| bloom 时域 EMA 拖影 | 排除:α=0.6 记忆 ~0.67 帧,亚像素级;池静止 rim ±0.5%(n45 远角对照 ±0.12%) |

## 病灶与修法

`ClientSpotlightUploader.onFrame()`(改前):
```java
Vec3 eye = cam.getPosition();          // 相机眼球——含行走 view-bob(横摆+旋转)
Vec3 playerEye = mc.player.getEyePosition(mc.getPartialTick());  // bob-free
Vec3 anchor = fp ? eye : playerEye;    // ← FP 锚相机眼球 = 病灶
Vec3 lookDir = fp ? look : mc.player.getLookAngle();
```

修后(锚与 bob 解耦;视线仍取相机 = "所见即所照" 不变):
```java
Vec3 anchor = spotAnchor(eye, playerEye);  // 一律玩家眼位(bob-free)
```
`spotAnchor` 为纯函数(唯一合法锚定入口,契约钉死);枪灯**枪口姿态**路径
(`eye.add(off…)`,随枪模型摆动)不动——枪口跟随视模型是意图行为。

**bob 实测事实(1.20.1)**:walk 中 `!diag` 相机 y 恒 122.62(=眼位,不动),bob 注入
的是**水平横摆 + 姿态旋转**分量 → 旧代码灯源世界位随横摆 ±~0.2m 摆动、灯锥轴
随旋转 wobble → 近距(2-4m)池缘几何调制 3-6%,再过 bloom 软阈带 → 可见节拍。

## 契约(先红后绿)

- 新增 `UploaderSemanticContract` §7:FP 灯锚 = 玩家眼位(不取带 bob 的相机眼),
  坐标原样、无 bob 分量;测试用"相机眼 122.71 vs 玩家眼 122.62(>5cm 差)"。
- 红:`spotAnchor` 未定义 → `compileTestJava` 编译失败;
- 绿:`AllContracts: ALL PASS`(437 → **440** checks)。

## 实机判定(双端,B=ObserverB 已重启加载新构建;Dev 灯开 + B 灯开)

| 采样 | 数值 | 判读 |
|---|---|---|
| 静止 `!diag` | cam=(2004.00,**122.62**,3.50) L0 pos=(2004.39,**122.42**,3.39) | 锚=眼位-0.20 偏移 |
| 走中 `!diag`(W700 期间) | cam=(2006.02,**122.62**,1.30) L0 pos=(2006.41,**122.42**,1.19) | 锚 y 与静止**恒等**;x/z 与相机同步平移且无摆动(走后 diag 对照一致) |
| 跑动全段(含坠落过渡帧) | cam y/锚系 y 全部 122.62/122.42 系(见日志 DIAG L0) | 任意采样相机 y 不动,bob=横摆/旋转 |
| 远程灯同步 | DIAG-REMOTE Dev flash=true,pos=(2003.3,121,5.4);B 端 L0=(2003.32,122.40,5.81) dir=(0.562,−0.219,0.798) | 远程灯数据/方向与 Dev 本地(0.557,−0.242,0.795)一致(预测外插残余 <0.03) |

截图:screenshots/dev-pool-ok.png(B 端观看 Dev 远程灯,墙+平台池光;修复后构建);
wsh2_0.png / wsh2_1.png(走前/走中活帧,池缘无 bob 节拍)。

## 边界与遗留

- 相机**姿态**的 bob 旋转仍存在(vanilla 视角晃动,灯锥轴跟随视线 = 手持灯真实
  感,按设计保留);修复只移除了灯**源位置**的 bob 注入——这是"条纹有节奏放大"
  的主导项。
- 若用户体感验收发现"观看远处对方池、自身灯关"时仍有极轻微联动,属视角晃动
  的屏幕视差(vanilla),可再议是否引入灯侧平滑(不影响本次修复判定)。
- 坑49(F2 toast 污染)/坑50(/tp 方向约定)/坑51(连拍前台+时序)已入坑位册。
