# 2026-09-01 深夜④ · 体素 DDA 遮挡:墙后漏光根治 + 实机 A/B

## 结论

用户实测报告"光照穿透墙壁,墙后地面有淡淡光晕"——满足 AGENTS §4 条件项
("DDA 体素遮挡:实机真见漏光才立项"),用户明确要求尝试新算法并关注性能。
本轮实现 **1 格=1 体素占用栅格(2bit 分类)+ GLSL Amanatides-Woo DDA 光线步进**,
替代屏幕空间 SSO 的已知局限("视锥外的遮挡者不投影,墙后物体不挡光")。
实机同视角 A/B:**墙后漏光亮度 mean 82.3 → 44.3(−46%,回到无灯环境基线 43.7),
漏光消除;直射光池 64.5 → 64.5 逐分位恒等(零回归);60fps 锁帧下 on/off
59.9 vs 59.8 fps(1% low 56.2 vs 55.0,噪声内);CPU 侧栅格构建 0.08ms/tick**。

## 病灶与修法

- 旧 SSO(composite 阶段):沿 片元→灯 视图空间射线 24 步投影回屏幕与 depthtex1
  比较。遮挡者必须在屏、且射线屏幕足迹恰好落在其像素上;B 站墙顶俯视北面时
  墙体不在画面内/在画面边缘 → 采样全部跳过或落空 → 光"穿墙"照亮墙后地面。
- 新 DDA:模组每 tick 采灯光作用盒内方块 → 2bit/体素(0 空/1 软植被/2 树叶/
  3 实心,与 block.properties 的 colortex3.a 分类同源)→ SSBO 尾段上传
  (lights[8] 定长 + voxOrigin/voxMeta/voxData,总长 525,104B;实际数据区按
  usedUints 增量上传,本场景 35³ ≈ 10.7KB/tick)。GLSL 侧对 灯→片元 射线做
  Amanatides-Woo 步进:实心一票否决(T=0,硬阴影),树叶 0.4/格、软植被
  0.75/格 透射衰减;栅格无效或任一端点出栅格 → 回退 SSO(保守一致,不假遮挡);
  起点格(灯)/终点格(被照表面所属方块)双向豁免,不自遮。
- 旋钮 `!voxel <on|off|status>`(客户端本地,默认 on;off=上传无效位回退 SSO)。

## 实机判定(双端 07:37 起,新构建;wall 场景 + 三件套)

布防:Dev (2000.5,121,6.5) yaw180 pitch+10 灯 ON(服务端真源);
B=ObserverB (2000.5,124,0.5) 站墙顶 yaw180 pitch+55 俯视北面地台(漏光判定区),
yaw0 pitch+45 南视为直射回归区。F2 postkey 真机截图(坑34 通道)。

| 判定 | SSO(!voxel off) | DDA(!voxel on) | 判读 |
|---|---|---|---|
| 北面地台 ROI(150,150→700,360) mean | **82.3** | **44.3** | 漏光 −46%,DDA 后=无灯基线 43.7 → 消除 |
| 同 ROI p90 / p99 | 110.6 / 126.1 | 46.8 / 48.0 | 光晕整体消失 |
| imgdiff N(on vs off) | meanDiff 14.94,changed 40% | bbox=[0,53,743,403] | 差异=光晕区域本身 |
| 南直射池同 ROI mean | 64.5 | 64.5(p50/p90/p99/max 全等) | **零回归** |
| imgdiff S | meanDiff 2.58,changed 3.2% | 差异集中于截图 toast/HUD 区 | 直射面无变化 |
| `!bench` 3s(on / off) | 59.9 fps,1% low 56.2 | 59.8 fps,1% low 55.0 | 60 锁帧下无差异 |
| 栅格构建(!voxel status) | — | lastBuildMs=**0.08**,box=(1983,105,-11)+35³ | CPU 可忽略 |

截图(screenshots/):`07.55.00`=北视 SSO 漏光(可见大片淡光晕)、
`07.55.06`=北视 DDA(全黑=基线)、`07.54.06/12`=南视 off/on 直射池(恒等)。

## 复现步骤

1. `tools/mp-session.ps1` 拉起双端(A=Dev 主机,B=ObserverB)。
2. A 中继(run/taclight-cmds.txt,无 BOM):`/taclight scene wall` → 三件套
   (`/gamemode creative Dev`、`/kill @e[type=!minecraft:player]`、
   `/gamerule doMobSpawning false`)→ `/tp Dev 2000.5 121 6.5 180 10` →
   `/tp ObserverB 2000.5 124 0.5 180 55` → `/taclight light on Dev`。
3. B 中继:`!voxel off` → F2 真机截图 → `!voxel on` → F2(北视对);
   `/tp ObserverB 2000.5 124 0.5 0 45` 后同法(南视对)。
4. `node tools/lumastats.js <png> --bbox 150,150,700,360` + `node tools/imgdiff.js a b --json`。

## 过程中发现并登记的坑(详见 调试环境搭建计划.md §6 坑47/48)

- 坑47:String.format `%d` 接 float 字段(Snapshot 记录分量)在事件监听器里抛
  IllegalFormatConversionException → 整个客户端 FATAL 崩溃(不是打条错误了事)。
- 坑48:原版远程玩家 HEAD 角同步缺口——ServerEntity 仅在量化字节变化时才发
  ClientboundRotateHeadPacket;被观察者 relog 后若头角字节未再变化,观察端
  yHeadRot 恒为旧值(实测 head=+4.63° vs 服务端真值 −180°,body=−178.59° 正确)
  → 远程灯方向错 180°。规避:验收前让被观察者动一下头(/tp 改 yaw 再改回)。
  长期项:消费侧检测 head/body 反常兜底(待立项)。

## 边界与未测项

- 体积束(composite1)无灯侧遮挡未动:束段"穿墙发光"理论上仍存在,本轮用户
  报告的病灶是表面照明,束段待实机可见再立项。
- 树叶/植被的新透射模型(0.4 / 0.75 每格)未单独实机标定(与旧 SSO 半透折中
  同方向,量级接近);草地/树预设下如有观感问题,调 taclight_common.glsl 两常量。
- 双灯跨度 >128 格时栅格钳制居中,出界灯逐光线回退 SSO(不会假遮挡,只是退旧行为)。
- radius 配置上限 96 > 栅格半宽 64:极端照距的远端回退 SSO,同上保守。
