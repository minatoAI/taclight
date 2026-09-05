# !beam 第五旋钮(体积光束密度)实机验证 · 2026-09-05

## 判定数字

- 契约:LightTuneContract 31→**42 checks**,`AllContracts: ALL PASS`,BUILD SUCCESSFUL(14:44)。
- 实机三档 A/B(同机位:墙前 6m、yaw0/pitch8、!bright 30 / !atten 2 / !dist 96 放大框架):
  - beam 0(关) vs beam 0.35:meanDiff **17.0**,changed **29.6%**(121282/409920)
  - beam 0(关) vs beam 0.7:meanDiff **18.9**,changed **30.4%**(124386/409920)
  - 判读:光池外围出现明显光晕/光雾(0.7 比 0.35 更弥散)。顺视角(相机在灯后沿束轴)
    读作 halation,侧视角(ObserverB 看 Dev)才读作光柱——与 HG 前向散射 g=0.55 一致。
- 性能定案(同场景 scene wall,!bench 3s):composite1 开 **395.5fps** vs 临时关
  (shaders.properties `program.composite1.enabled=false`)**439.2fps** →
  **0.25ms/帧固定成本**;复原后 447.5fps。密度调大不增加成本(32 步 raymarch 恒定)。

## 旋钮语义

- `!beam <0..1>`:SSBO vlParams.y 逐灯直接换值(GLSL 零改动)。
- **0 = 完全关闭光束**(开关对比用);**off = 回 config 默认 0.05**;无参 = status。
- 与 atten/knee 的 0 哨兵语义不同:密度是消费值本身,显式 0 就是关,不回退。
- 内存覆盖,重启清零;B 端看远程灯用 B 自己的本地覆盖(两端各自设)。

## 为什么之前"看不见"

密度默认 0.05 + 贴墙光路仅 ~1.1m + 前向散射侧视弱 5×,三因叠加低于可见阈;
放大实验(亮 30/衰减 2/6m 光路,双端拍片)在 0.05 密度下仍不可见 → 密度是约束。

## 文件

- A-beam0-off.png / A-beam035.png / A-beam07.png:三档同机位对比(A 端第一人称)。
- 复算:node tools/imgdiff.js A-beam0-off.png A-beam035.png --json → meanDiff≈17.0。

## 追加:!beamonly 只看光束开关(同日第二轮)

- 用户需求:单独观察体积光形态。SSBO 头部 flags bit2(FLAG_BEAM_ONLY=4,契约守卫与
  HAS_DATA/DEBUG/TIMING 无冲突)→ composite.fsh M1 表面照明分支整支跳过
  (`else if ((flags & TACLIGHT_FLAG_BEAM_ONLY) == 0u)`),composite1 体积束照常。
- 契约 LightTune 42→**51** 项 ALL PASS(含 GLSL 源两处断言)。
- 实机(6m+亮30+atten2+dist96+beam0.5):on vs off meanDiff **11.7**/changed **12.2%**
  ——on 态墙砖失去照明(平黑反照率),白色光球 = 体积束顺视积分 + bloom;off 态光池内
  可见被照墙砖纹理。A-beamonly-off.png / A-beamonly-on.png。
- 推荐观感配方:`beamonly on` + `beam 0.5` + `bright 10~15`,退 6m;侧视角(ObserverB
  看 Dev)见柱形,顺视角为光雾球(g=0.55 前向散射特性)。
