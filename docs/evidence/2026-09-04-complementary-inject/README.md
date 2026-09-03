# 2026-09-04 Complementary r5.9 前向注入 — 实机证据包(注入未生效=负结果证据)

用户要求:"iterationT 有自适应曝光对手电有影响,试试注入 Complementary 是什么样子"。

## 结论(负结果,如实记录)

Complementary 包**零崩溃、零注入**:`interop injected` 日志从缺,off/on 截图几乎逐位
一致(meanDiff 全图 17.7 系手持模型位置差,hotspot bbox 内 mean 21.9→32.7/p90
32.7→57.1 皆为噪声级,ge200 恒 68),灯亮 SSBO 有数但画面无锥池。
`!shot` 对证明零注入有效——排除"截图没拍到"假阴性。

## 根因链(三段,每段都有实机/离线证据)

1. **钩子 miss(已修)**:patchComposite 只覆盖 composite/deferred/final;Complementary
   全部光照在 gbuffers `DoLighting` 完成。前 2 组截图 off/on 逐位一致即此因。
   修=TransformPatcherMixin 加挂 patchSodium 6×String(ordinal 0..5)。
2. **`missing ';' at '{'` 三连崩溃(已定位,未根治)**:
   `gbuffers_terrain: line 9615/9626 missing ';' at '{'`,内联 21303→6869→4428 chars
   行号仍随动=与注入重量无关,是**注入形式**(函数体内声明)与 AST 声明解析冲突。
   减负过程:完整 core → 前向精简(去 SSO/GGX/绿锥)→ 再去体素 DDA(ivec3/bvec3/位运算),
   前向恒可见桩(遮挡由宿主 DoLighting 主管)。
3. **文件级 //Program// 锚零命中(当前卡点)**:
   `//Program//` 在 patchSodium 输入侧文本中不存在(Iris jcpp/合并阶段已剥离注释),
   selectorCount 2≠实测=静默零注入(fail-safe,无崩溃)。
   已改:锚 → 顶点 main 体 `lmCoord  = GetLightMapCoordinates();`(selectorCount=1,
   持续编译真分支);契约已同步,AllContracts ALL PASS。**实机未验证**(实例在关灯收尾后
   已停,需重启验证)。

## 判定数字(grass_low 机位,hotspot bbox 280,190,560,340)

| 图 | 状态 | hotspot mean/p50/p90 | ge200 |
|---|---|---|---|
| comp_off.png | 灯关 | 21.9/25.7/32.7 | 68 |
| comp_on.png | 灯开(SSBO handheld=true) | 32.7/35.5/57.1 | 68 |

- 全图 meanDiff=17.68,maxDiff=148,changed=191958/409920——差量集中在右下手持模型
  (灯开手持位置变化),非锥形光池。
- iterationT 自适应曝光对照(静态结论,源码级):
  iterationT `MotionBlur.glsl GetExposureTiles()` 对 colortex1 分块取亮度→colortex2.a,
  `Final.glsl GetExposureValue()` 全局 `color*=exposure`——composite 注入光在 AE
  采样环内,AE 会反馈压制手电;Complementary `composite5 DoCompTonemap`=Lottes 手动
  `TM_EXPOSURE=1.00`(lib/common.glsl:279),无自适应,注入光不吃反馈。**前向注入成功后
  预期:手电亮度不受全局曝光回压**,需实机确认。

## 文件

- `comp_off.png` / `comp_on.png` — 灯关/灯开(零注入对照, GrassLow 机位)
- `log-excerpt.txt` — 管线创建/无 injected 行/灯开关同步/中继原文(零注入证据)

## 改动(未提交,随本轮提交)

- `src/main/resources/shader_patches/templates/complementary-r5.9.json`(NEW):
  family=complementary,packHash=program/gbuffers_terrain.glsl 16 位前缀,前向单文件,
  算子=顶点 main 体内联注入 + DoLighting 后加性锥光(无 /2048,无版本抬升)
- `TemplateLibrary.java`:inlineFor 路由 + inlineCoreTextForward/slimForwardCore/
  FORWARD_SURFACE(lambert 直连 DDA→恒可见桩)/FORWARD_VOX_STUB
- `RuntimePackInjector.java`:FINGERPRINT_FILES 增 program/gbuffers_terrain.glsl 键
- `TransformPatcherMixin.java`:patchSodium 6 钩子
- 契约:TemplateLibraryContract 29(Complementary 区)+InlineCoreContract 34(前向精简 18)
- `AllContracts: ALL PASS;BUILD SUCCESSFUL`(acceptance align/side FAIL 系既有工具自检噪声)

## 复现(待验证,需重启 interop 实例)

```powershell
powershell -File tools/interop-session.ps1 -Pack ComplementaryReimagined -Shaders on -User InteropA -World interop
powershell -File tools/interop-relay.ps1 -Commands '/taclight scene grass'
powershell -File tools/interop-relay.ps1 -Commands '/taclight cam goto grass_low'
powershell -File tools/interop-relay.ps1 -Commands '/taclight light off InteropA'
powershell -File tools/interop-relay.ps1 -Commands '!shot'   # comp_off
powershell -File tools/interop-relay.ps1 -Commands '/taclight light on InteropA'
powershell -File tools/interop-relay.ps1 -Commands '!shot'   # comp_on,预期:有锥池+injected 日志
node tools/lumastats.js run-interop/screenshots/comp_on.png --bbox 280,190,560,340
node tools/imgdiff.js run-interop/screenshots/comp_off.png run-interop/screenshots/comp_on.png
```

## 新坑(已入坑位册 §6:坑90/坑91/坑92)

- **坑90**:gbuffers_terrain AST `missing ';' at '{'`——函数体内声明+宿主 #ifdef 包裹下
  解析失败,减重不改行号随动;前向只留 lambert+衰减+肩部,遮挡交宿主。
- **坑91**:`//Program//` 等注释锚在 patchSodium 输入侧不存在(jcpp/合并剥离注释),
  selector 必须选持续编译的真分支代码行;selectorCount 不符=静默零注入。
- **坑92**:双实例同存档 quickplay=目录锁互斥,第二实例标题屏僵尸(SSBO 建完无管线);
  程序化验证前必须单实例(先杀旧),否则"无 injected+截图全黑"误判为注入失败。
