# 2026-09-04 Complementary r5.9 前向注入 — 实机证据包(注入命中+锥池可见)

用户要求:"iterationT 有自适应曝光对手电有影响,试试注入 Complementary 是什么样子"。

## 结论(正结果:注入生效,锥形光池可见)

Complementary 包**注入命中、管线存活、画面见锥池**:`interop injected family=complementary
pack=ComplementaryReimagined (+4543 chars)` ×2(两份片元半体:terrain 271068 + translucent
285528 变体,顶点半体无调用点则跳过),零崩溃,崩溃报告无新增(仍是 09-03 旧 5 份)。
灯开后草地+土墙面出现柔和锥形光池(近亮远暗,边缘自然衰减),灯关即消失。
`!shot` 直读帧缓冲——排除"截图没拍到"假阳性/假阴性。

## 根因链(四段,每段都有实机/落盘证据)

1. **钩子 miss(已修)**:patchComposite 只覆盖 composite/deferred/final;Complementary
   全部光照在 gbuffers `DoLighting` 完成。前 2 组截图 off/on 逐位一致即此因。
   修=TransformPatcherMixin 加挂 patchSodium 6×String(ordinal 0..5)。
2. **`missing ';' at '{'` 三连崩溃(已定位,绕过)**:函数体内声明 + 宿主 #ifdef 包裹与
   gbuffers AST 声明解析冲突(减重 21k→4.4k 行号仍随动)。绕过=core 放**文件域**
   (DoLighting 定义前,词法前序),零函数体语句;前向精简(去 SSO/GGX/绿锥/体素 DDA,
   恒可见桩,遮挡由宿主 DoLighting 主管)。
3. **单规则双锚点跨半体注定 miss(本轮根因,已修)**:运行时 patchSodium 6 入参按
   顶点/片元**分半**到达——顶点半体(175445/246085 长,有顶点锚、无 DoLighting)与
   片元半体(271068/285528 长,有 DoLighting 定义+调用点、无顶点锚)是两份独立输入。
   旧模板把顶点锚(selector)+片元调用点(ops[1] 锚)绑在同一条规则=任一Meth半体恒有一
   锚缺席=PatchExecutor all-or-nothing 全 miss(fail-safe,零崩溃零注入)。
   落盘取证:`run-interop/interop-dump-*.glsl` 四份(已清,gitignored)——片元半体
   DoLighting 定义在 main 前(2960 行)、调用点在 main 内(9601 行)。
   修=模板收敛为**片元单规则**(顶点半体selector miss 则跳过,片元半体命中即注入)。
4. **版本策略(本轮定案)**:SSBO 用 `#extension GL_ARB_shader_storage_buffer_object`
   (宿主同式,见 voxelization/SSBOs),**不抬升** `#version 130`(430 会杀掉宿主
   texture2D/varying 兼容路径)。旧"//Program// 注释被剥离"判断有误——落盘显示
   顶点/片元半体皆含 `//Program//`,真正 root cause 是第 3 条分半到达。

## 判定数字(grass_low 机位,hotspot bbox 280,190,560,340)

| 图 | 状态 | hotspot mean/p50/p90 | ge200 |
|---|---|---|---|
| comp_off.png(旧,零注入对照) | 灯关 | 21.9/25.7/32.7 | 68 |
| comp_on.png(旧,零注入对照) | 灯开(SSBO 有数,无锥池) | 32.7/35.5/57.1 | 68 |
| comp_v2_off.png(本轮) | 灯关 | 21.2/26.6/33.9 | 68 |
| comp_v2_on.png(本轮) | 灯开(SSBO handheld=true,**有锥池**) | 89.8/67.3/182.8 | 67 |

- 全图 meanDiff=30.63,maxDiff=170,changed=228224/409920——差量为中央锥池整块提亮。
- hotspot:mean 21.2→89.8(×4.2),p90 33.9→182.8,ge128 68→14185(锥池区大面积点亮)。
- 观感:锥池柔和近亮远暗,土墙面亦有照亮,无 iterationT 式过曝糊死;灯关即刻消失。
- AE 对照(源码级,待体感确认):iterationT composite 注入在 AE 采样环内
  (MotionBlur GetExposureTiles→colortex2.a→Final GetExposureValue 全局 exposure),
  AE 回压手电;Complementary=手动 Lottes tonemap(TM_EXPOSURE=1.00,无自适应),
  前向注入不吃反馈——本轮锥池亮度稳定,无回压迹象。

## 文件

- `comp_off.png` / `comp_on.png` — 旧零注入对照( GrassLow 机位,保留作 AB 对照)
- `comp_v2_off.png` / `comp_v2_on.png` — 本轮注入命中对(灯关/灯开,锥池可见)
- `log-excerpt.txt` — 旧零注入证据(管线创建/无 injected 行/灯开关同步)
- `log-excerpt-v2.txt` — 本轮命中证据(injected ×2/LIGHT-SYNC on/ACK handheld=true)

## 改动(随本轮提交)

- `src/main/resources/shader_patches/templates/complementary-r5.9.json`:
  family=complementary,packHash=program/gbuffers_terrain.glsl 16 位前缀,片元单规则,
  算子=版本行后 extension + DoLighting 定义前文件域内联 + 调用后加性锥光
  (无 /2048,无版本抬升,宿主 SSBO 同式 extension)
- `TemplateLibraryContract`:Complementary 区同步(单规则/三算子/extension 锚/定义行锚)
- `AllContracts: ALL PASS;BUILD SUCCESSFUL`
- 诊断代码已撤(恢复干净,落盘 dump 已清);RuntimePackInjector/TemplateLibrary/
  TransformPatcherMixin 无未提交改动(前向精简/6 钩子/指纹键均在 561caae 内)

## 复现(已验证通过,单实例)

```powershell
powershell -File tools/interop-session.ps1 -Pack ComplementaryReimagined -Shaders on -User InteropA -World interop
powershell -File tools/interop-smoke.ps1 -Scene grass -Cam grass_low -Tag compv2 -User InteropA
# 预期:latest.log 见 injected ×2(+4543 chars);compv2_on 有锥池;hotspot mean ~90
node tools/lumastats.js run-interop/screenshots/compv2_on.png --bbox 280,190,560,340
node tools/imgdiff.js run-interop/screenshots/compv2_off.png run-interop/screenshots/compv2_on.png
```

## 新坑(入坑位册 §6:坑90/91/92,本轮修正坑91 结论)

- **坑90**:gbuffers_terrain AST `missing ';' at '{'`——函数体内声明+宿主 #ifdef 包裹下
  解析失败,减重不改行号随动;前向只留 lambert+衰减+肩部,遮挡交宿主。
- **坑91(修正)**:旧结论"注释锚被 jcpp 剥离"有误——落盘显示顶点/片元半体皆含
  `//Program//`;真正 root cause = patchSodium 6 入参按顶点/片元分半到达,单规则
  双锚点跨半体注定 miss。教训:模板规则必须与运行时输入分半对齐,落盘取证为准。
- **坑92**:双实例同存档 quickplay=目录锁互斥,第二实例标题屏僵尸(SSBO 建完无管线);
  程序化验证前必须单实例(先杀旧),否则"无 injected+截图全黑"误判为注入失败。
- **坑93(新)**:GLSL 版本抬升(130→430)会杀掉宿主 texture2D/varying 兼容路径;
  SSBO 在 130 下用 `#extension GL_ARB_shader_storage_buffer_object`(宿主同式)。
