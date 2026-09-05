# 2026-09-05 !scat 第六旋钮 + raymarch 64 步:侧视丁达尔改进

## 需求
用户实拍确认 beamonly 下体积光存在但"侧面看太弱",批准改进散射方向(各向异性 g)与采样。

## 改动
1. **`!scat <0..0.9>`**(第六旋钮):SSBO vlParams.x 逐灯直接换值,GLSL 零改动。
   0=完全各向同性(侧视最亮),off=回编译期默认 BEAM_ANISOTROPY=0.55;与 !beam 同族
   (0 是合法消费值,不走 0 哨兵)。表面照明不消费 vlParams.x → 只影响光束形态。
2. **TACLIGHT_VL_STEPS 32→64**(composite1.fsh):远背景视线步长 3m→1.5m,近场细锥
   不再被步进跨过(诊断 03 图四机制之三)。成本 ~0.25→~0.5ms/帧量级(0.25ms@32 步
   基准外推,待正式验收 !bench 实测)。
3. knob.ps1 旋钮表/横幅同步;LightTuneOverride/ClientSpotlightUploader/DebugCommandRelay
   三处 Java 透传。

## 契约
LightTuneContract 51→**68** 项:scat 解析/显式零/越界拒绝不污染/SSBO 读写回环/
buildSpotBeam 集成/收尾零残留 + GLSL 源断言(TACLIGHT_VL_STEPS 64、vlParams.x 消费点)。
红(编译失败)→绿,`AllContracts: ALL PASS`(2026-09-05 16:0x 实跑)。

## 实机判定(稳定 B 机位:Dev 灯横穿 B 视野、墙做背景,L0 dir=(1,0,0) diag 钉死)
| 图 | 参数(均在 B 端,beamonly on) | 结果 |
|---|---|---|
| 01-max-recipe-scat0-shaft | beam 1.0 + bright 30 + atten 0.5 + scat 0 | **清晰离散光锥**:锥体侧视成形、打墙亮斑、锥缘可辨 |
| 02-half-recipe-visible | beam 0.6 + bright 12 + atten 2 + scat 0.15 | **仍可见**(淡一些)——推荐日常配方 |
| 03-max-recipe-scat055-baseline | 同 01 但 scat off(g=0.55) | 光锥明显偏暗(前向散射侧视惩罚) |
- scat 单变量 A/B(01 vs 03,其余全同):meanDiff **5.46** / changed **12.6%**(51842/409920),
  噪声底参照 0.48(同态双拍,2026-09-05 旋钮校准)。
- 此前 A 端 yaw63 机位三档全黑 = 病态工况,非实现缺陷:光束背景=无限远虚空
  (maxDist 96)+ 视线夹角 63° 导致仅近端 ~2.5m 细锥入画(能量×路径双输,见
  evidence/2026-09-05-beam-visibility-diagnosis/ 四机制)。

## 观察要领(给用户)
- 从侧面 3~5m 看光束,**让墙/地面当光束背景**;对着虚空看本来就该暗(物理+采样双限制)。
- 推荐:`beamonly on` + `beam 0.6` + `bright 12` + `atten 2` + `scat 0.15` 起步,
  侧视不够亮 → scat 降到 0~0.1、beam 拉到 1.0;想要更细的"一根柱"→ 待窄锥角立项。
- A 端当前已留在:beamonly on + beam 0.6 + bright 12 + atten 2 + scat 0.15;
  B 端旋钮全默认、灯 on(水平照墙);Dev 在 (2004.3,121,6.7) yaw63。

## 现场恢复记录
诊断 rig(Dev 灯横穿 B 视野)已全部复原:Dev 灯 off、回原机位;B 灯 on、旋钮清零;
A 端旋钮=推荐配方(见上)。
