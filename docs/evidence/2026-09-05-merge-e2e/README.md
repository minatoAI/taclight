# 2026-09-05 合并后(master 6f8592b)端到端验收证据

## 结论

合并后的 **master(6f8592b)** 构建产物在真实 Minecraft Forge 客户端端到端可用:
生产 jar 结构完整 + 客户端零模组级错误加载 + Complementary 注入引擎工作(逐帧
injected)+ 灯开关全链路(服务端真源→客户端 ACK→像素效果)+ 三旋钮命令实机生效。

## 验证内容与数字

### 1. 生产 jar(build/libs/taclight-0.10.0.jar,269,178 字节)

- `taclight.refmap.json`(坑86 关键物)+ `taclight.mixins.json` **在 jar 根**;
  jar task dependsOn generateRefmap(合并后双留,已确认仍在生效)。
- `shader_patches/templates/complementary-r5.9.json` + `inline/taclight_core.glsl`
  + `mask` 注入模板全在;LightTuneOverride/TemplateLibrary/RuntimePackInjector/
  TransformPatcherMixin/oculus mugshots 类全在。
- 构建命令:gradlew-java17.cmd -Dnet.minecraftforge.gradle.check.certs=false
  build -x test packShaderZip(内网需关证书校验,非合并回归)。

### 2. 真实客户端加载(run/ 主机实例,quickPlay=test,包=ComplementaryReimagined)

- [TacLight] v0.10.0 loading / NET channel(2 packets)/ CMDS(68 子命令)/
  SSBO created(id=17,bytes=525104)全绿。
- TacLightMixinGate: TransformPatcherMixin -> true(self-guarded)。
- **interop injected family=complementary pack=ComplementaryReimagined
  (+8010 chars)** ×5(启动期同模板不同半体长度,正常;分支轮 +7859/+7869,
  差值系合并树构建资源重打,注入量级一致)。
- 零模组级错误;仅有已知噪音:mekanism/fabric ClassNotFound(环境无此 mod)、
  Embeddium-MixinTaintDetector 提示(TacLight 对 Embeddium 可选的 mip 类 mixin,
  历史如此)。
- "无 TacLight 注入请选 iterationT" 提示 = **已知过期误报**(注入日志已证明命中)。

### 3. 灯开关链路(scene grass,午夜+清怪+关生成,已按坑39 布景)

- `/taclight light off Dev` → LIGHT-SYNC cmd:off@target → ACK;
  `/taclight light on Dev` → LIGHT-SYNC cmd:on@target → ACK。
- off/on 像素判定(854×480,!shot 直读,imgdiff threshold=8):
  meanDiff **11.66** / maxDiff 239 / changed **72560(17.7%)**。
- 目检:off=暗淡夜景;on=墙面+草地锥形光池清晰(见 figure)。
  - light_off.png / light_on_default.png

### 4. 三旋钮实机(用户验收核心:!bright/!dist/!atten)

- `!bright 10` → RELAY bright -> bright=10.0;`!dist 24` → RELAY dist -> dist=24.0;
  `!atten 2` → RELAY atten -> attenK=2.0;status 回显正确;off 全部回默认
  (默认 intensity=6.0/radius=36.0/K=5.0,GLSL 回退)。
- 像素效果:knob_b10_d24_k2.png(bright=10/dist=24/atten=2)明显更亮、光池更大
  (K=2 尾更长),与默认 light_on_default.png 差异显著。
- 三旋钮为纯内存覆盖,重启清零;体感扫参后把期望值固化进 TacLightConfig 默认。

## 文件清单与复算

| 文件 | 说明 |
|---|---|
| light_off.png | light off 后 !shot(854×480) |
| light_on_default.png | light on 默认参数(6/36/5)!shot |
| knob_b10_d24_k2.png | !bright 10 + !dist 24 + !atten 2 生效中 !shot |
| log-excerpt.txt | latest.log 关键行(grep TacLight loading/injected/RELAY/LIGHT-SYNC/SCENE/SSBO) |

复算:
- imgdiff:node tools/imgdiff.js light_off light_on_default --threshold 8 --json
  → meanDiff 11.6646/maxDiff 239/bbox 全图/changed 72560/409920(17.7%)
- manifest 见 manifest.sha256

## 环境备注

- 实例仍运行中(run/ 主机,ComplementaryReimagined,enableShaders=true,灯 on、
  三旋钮已回默认),供用户直接体感/扫参;这是合并后第一次真实客户端运行。
- 本证据包的 light on/off 均为服务端真源命令(非客户端旋钮),链路=合并树
  端到端完整验证。

## 5. 双端(LAN,离线号)远程灯同步 —— 追加验证(用户指出:正版与否非运行因素)

- 双端=合并树 A(run/ 主机,ComplementaryReimagined,注入 +8010)+ B(run-observer,
  ObserverB 离线号,LAN 25560,注入 +8010/+8020)。A 开灯(handheld+gun),
  B 端 `!diag`:
  - DIAG-REMOTE player=Dev self=false flash=false **gun=true**(同步正确)
  - DIAG-TP player=Dev state=fresh w=1.000 age=1ms(捕获链 fresh)
  - ssbo count=1,L0 pos/dir 与 A 端一致
- 视觉:B 视角截图 remote_b_sees_dev_pool.png = Dev 实体+其脚边清晰锥形光池
  (远程灯同步可视证据)。
- 复算:DIAG/DIAG-REMOTE/DIAG-TP 行在 log-excerpt-b-observer.txt。
