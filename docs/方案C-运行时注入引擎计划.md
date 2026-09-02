# 方案 C · 运行时注入引擎计划(里程碑 2)

> 状态:**已批准开工**(2026-09-02 用户批准,"多参考 Iris 官方 DH 兼容的已验证实现")。
> 2026-09-02 23:x 因电脑维护暂停,系统就绪信号后按 §9 顺序实施。
> 本文档 = 实施蓝图(架构/规范/验收),进度状态写 CHANGELOG,坑写坑位册,本文不改审批语义。

---

## 1. 目标与非目标

**目标**:玩家选择任意光影包时,模组在**运行时、内存中**把 TacLight 锥形照明核心注入该包
的 deferred/composite 程序,使照明跨包可用;玩家磁盘上的包文件**零改动**;不支持的包
**零注入 + 一次性提示**(绝不产生坏渲染)。

**非目标**(本期不做):
- 不追求"支持一切包"——只做模板库覆盖的包系(family);
- 不注入 gbuffers/patchVanilla/patchSodium(不碰顶点几何,只做 composite 族表面照明);
- 不做配置 GUI(不支持包的提示走聊天栏/日志);
- 不动 SSBO 数据链(binding=7 上传与包无关,零改动)。

## 2. 参考实现(已验证先例)

| 先例 | 取什么 | 证据 |
|---|---|---|
| **Iris 官方 DH 兼容**(1.7.x) | per-family GLSL 补丁 + 内置补丁文本 + 守门(未声明的包零改动);补丁在 shader 翻译前的 transform 层应用(`patchDHTerrain`/`patchDHGeneric` 与 `patchComposite` 同层) | devdocs developer/dh;PR #2493/#2728;本地 jar `iris/compat/dh/` |
| DH 兼容的历史坑 | 旧 OptiFine 式 option 指令曾使补丁判定反转 → **指纹判定宁可保守,判不了=不注入** | PR #2493 修复记录 |
| Euphoria Patches | per-family 补丁层可长期维护,但**每包升级必须跟版、版本配对** | github SpacEagle17/Euphoria-Patches |
| 本项目路线 P 引擎 | 补丁格式(JSON+锚点唯一+marker 幂等+replace/insertBefore/insertAfter)当年实机在 Oculus 下加载成功(git 0a98400) | `iterationT-3.2.0-v3.patch.json` |
| 本项目自家包 | `#version 430 core` + `layout(std430, binding=7)` 源内直声明,oculus-1.8.0 实机长期在跑 | pack/shaders/composite.fsh |

**本地 jar 取证(oculus-1.8.0_mapped_official_1.20.1,= Iris 1.7.x 移植;注意:不是传言的 1.7.0/Iris 1.6.4)**:
- `TransformPatcher.patchComposite(String×4, TextureStage, Object2ObjectMap)` **public static**;
  调用方字节码实锤 = `pipeline/CompositeRenderer`(composite/deferred 族)+ `pipeline/FinalPassRenderer`(final)。
- DH 补丁类(`compat/dh/DHCompat*`)与 glsl-transformer(io.github.douira)同在 classpath。
- mixinf 配置现状:`taclight.mixins.json` 带 plugin 门控(`TacLightMixinPlugin`),客户端 mixin
  走 `client` 列表——注入 mixin 照此模式加 Oculus 在场门控。

## 3. 架构

```
包加载/编译时序(注入窗口):
Iris/Oculus 加载包 → ProgramSet(含 include 解析+option 处理)
→ 首帧前管线编译:CompositeRenderer / FinalPassRenderer
   → TransformPatcher.patchComposite(4×源文本, ...)   ←【注入点:mixin 此处】
   → Iris 自身 transform(纹理指令/兼容) → 编译

我们的注入发生在 patchComposite 的【输入侧】:注入文本随后走 Iris 完整
transform,与"离线派生包再被正常加载"语义一致(路线 P 实证过的等价形态)。
```

四件套(全在 `dev.taclight.interop` 包):

1. **PackFingerprint(指纹)**:键 = (包显示名 from `config/oculus.properties` 的
   `shaderPack=`,关键文件 SHA-256:`shaders/composite.fsh` + `shaders/shaders.properties`)。
   包根定位 = shaderpacks 目录按名 resolve(zip 或目录;绝对路径包直接用)。
   指纹在首次 patchComposite 调用时惰性计算并缓存;判定失败/文件读不到 = 无指纹 = 零注入。
   (不 mixin ShaderPack,少一个耦合面。)
2. **TemplateLibrary(模板库)**:resources `shader_patches/templates/*.json` + 每 adapter
   prelude 文本。运行时只读。
3. **PatchExecutor(补丁执行器)**:对一段源文本按模板执行;语义见 §4。纯函数、可 JVM 契约。
4. **RuntimePackInjector(mixin 挂点)**:`@Mixin(TransformPatcher.class)` 对
   `patchComposite` 用 `@ModifyArgs`(或逐参 `@ModifyVariable`)改写 4 个 String 入参;
   文本含模板 selector 才动,否则原样返回。Oculus 不在场时 mixin 不应用(plugin 门控),
   patchComposite 根本不存在,零影响。

**core 内联(免 #include)**:注入文本 =
`[prelude] + [taclight_math.glsl 去头注释] + [taclight_core.glsl 去掉其 #include math 行]`
一次性 insert 进目标程序(见模板)。原因:patchComposite 收到的文本已完成 include 解析,
新塞的 #include 不会再被展开(此点列入 §8 实证项,冒烟首验)。
**prelude**(外包无 adapter):
- `#define TACLIGHT_LIGHT_GAIN 2.2`(core 无兜底,必须给;初值沿用本包,实机再标定);
- `TACLIGHT_OCCLUSION_AT` **不定义**——core `#ifndef` 默认 1.0(保守全挡)。
  遮挡主路径是体素 DDA(`taclight_vox_transmit`,SSBO 栅格,包无关);colortex3 SSO
  仅在体素无效时兜底,1.0 = 体素无效时不照明 = 安全降级,符合设计。
- **资源单一真源**:gradle task 把 `pack/shaders/lib/taclight_{math,core}.glsl` 拷入
  `src/main/resources/shader_patches/`,契约钉两处 SHA-256 一致(防漂移)。

## 4. 模板格式规范(v2,路线 P 格式复活+运行时语义)

```json
{
  "familyId": "iterationT",
  "packName": "iterationT 3.2.0",
  "packHash": { "shaders/composite.fsh": "<sha256 前 16 位>" },
  "note": "冒烟模板: revived from route-P v3-p1, call site rewritten for 2.0 core",
  "files": [
    {
      "file": "shaders/composite.fsh",
      "selector": "finalComposite += HeldLighting(viewPos, viewDir, gbuffer.normalL",
      "selectorCount": 1,
      "ops": [
        { "op": "replaceFirst", "anchor": "#version 330", "content": "#version 430 core" },
        { "op": "insertAfterLine", "anchor": "#version 430 core", "content": "<INLINE_CORE>" },
        { "op": "insertAfterLine", "anchor": "finalComposite += HeldLighting(viewPos, viewDir, gbuffer.normalL",
          "content": "finalComposite += taclight_surface_lighting(viewPos, gbuffer.albedo, gbuffer.normalL, gbuffer.material.roughness, 0.0, vec3(0.04));" }
      ]
    },
    {
      "file": "shaders/shaders.properties",
      "selector": null,
      "ops": [ { "op": "insertAtEnd", "content": "iris.features.optional = SSBO\n" } ]
    }
  ]
}
```

执行语义(契约钉死):
- `selector`:主锚点。文本不含 selector → 该文件**跳过**(天然防误伤同名 anchor 的其他程序;
  composite.fsh 是 fsh 才有 HeldLighting 调用,vsh 不会命中)。
- `selectorCount`:锚点出现次数必须恰好等于此值,否则**整模板中止**(不注入,记日志)。
- `ops` 依序应用,后序 op 看到前序结果;`insertAfterLine` = 在含 anchor 的**行尾**后插入
  (content 自带换行则多行);`replaceFirst` 只动首个出现;`insertAtEnd` 追加到文本末尾。
- **幂等**:文本已含 `TACLIGHT_PATCH_BEGIN`(marker 随模板注入)→ 整体跳过。
- **失败语义**:任何 op 的 anchor 缺失/selectorCount 不符 → 中止且**不留任何部分修改**
  (先在字符串副本上跑,全部成功才采用)= fail-safe。
- `<INLINE_CORE>` 占位符由引擎在加载模板时替换为 §3 的内联文本(资源拼接,非 JSON 内嵌)。
- 调用文本签名 = `taclight_surface_lighting(fragView, albedo, n, roughness, metal, f0)`
  (core L395 实读);上式 metal/f0 为保守初值,最终形态以实机冒烟像素判定为准。

## 5. 族适配规范

- **每族一个模板 JSON + 一段 adapter 知识**(该包 gbuffer 解码 → core 入参映射);
  族内包升级 = 换 hash/锚点重导,模板版本随 CHANGELOG 走。
- **冒烟族 = iterationT 3.2.0**(锚点已知、路线 P 实证过加载):验引擎不验视觉效果。
- **首个正式族 = Complementary**(用户量最大;Reimagined/Unbound 分 profile 锚点可能漂移,
  先只支持默认 profile,行不通再拆 profile 模板)。
- 红线自查:模板只含"我们的代码 + 锚点字符串 + 标定常数",不照搬包代码(与路线 P 同界)。

## 6. 契约清单(TDD 先红后绿,`gradlew-interop.cmd taclightContracts`)

| 契约 | 钉死点 |
|---|---|
| `PatchExecutorContract` | 四算子语义/selector 门控/selectorCount 校验/anchor 缺失=整体不留痕/幂等 marker/顺序应用 |
| `TemplateLibraryContract` | JSON 解析/缺字段即拒/`<INLINE_CORE>` 替换后无占位残留/hash 键匹配 |
| `PackFingerprintContract` | 属性文件解析/包根 resolve(目录+zip)/hash 稳定性/未知输入=空 |
| `InlineCoreContract` | resources 内联文本 = pack/shaders/lib 两文件拼接去 include 行(SHA-256 对账);含 SSBO layout 声明;含 `#ifndef TACLIGHT_OCCLUSION_AT` 兜底;prelude 提供 GAIN;花括号平衡;`#version` 不由内联文本携带(由模板 op 管) |
| `ShaderCoreContract`(既有 31 项) | 不回归——内联不改 pack 源文件本身 |

## 7. 实机验收标准(run-interop,程序化通道,零键鼠)

前置:`iterationT 3.2.0/` 从工作区根**拷贝**到 `run-interop/shaderpacks/`(运行时产物,
gitignored,不提交不分发);oculus.properties `shaderPack=iterationT 3.2.0`,
`enableShaders=true`+`!reload`。

| # | 判定 | 手段 |
|---|---|---|
| 1 | 打补丁包加载成功,无静默禁包 | 日志 grep `[TacLight]` + `Using shaderpack`/无 "disabled" |
| 2 | 注入确实发生 | 日志:`[TacLight] interop injected family=iterationT …`(注入器打点) |
| 3 | 照明可见且随灯开关 | 中继 `/taclight light on|off` + `!shot` 截图对,imgdiff changed 占比与亮区 bbox 判定 |
| 4 | 观感合理(不炸不黑) | 灯亮截图目检 + 亮度直方图(acceptance) |
| 5 | 本家包零回归 | `shaderPack=taclight-shaders-dev` 重载,草地 on/off 与 21:2x 证据基线同判 |
| 6 | 未知包安全 | 选一个未覆盖包(如 BSL 系任一),确认零注入+一次性提示+包正常渲染(无照明) |
| 7 | 幂等 | 同包二次 reload,日志无重复注入 |

证据包:`docs/evidence/2026-09-03-interop-runtime-inject/`(README 判定数字+manifest)。

## 8. 风险与缓解

| # | 风险 | 缓解 |
|---|---|---|
| 1 | patchComposite 输入侧语义假设错(文本是否已含 include 解析/option 展开) | 冒烟第一项:注入器把收到的文本特征(长度/含 "#include" 行数)打日志,与原包文件对照;假设不成立则改挂更早点位(FileIncludeGraph 输入),架构不变 |
| 2 | `#version` 330→430 个别包不兼容 | 该族模板带"版本试验"标记,冒烟即验;失败=该族标不支持,不硬上 |
| 3 | 注入后语法错 → Iris **静默禁包**(铁律级坑) | 执行器成功后才采用(§4 fail-safe)+ 注入文本括号平衡契约 + 日志打点使禁包可归因 |
| 4 | tonemap 位置差 → 灯被二次调色/过暗 | iterationT 在 composite 注入(tonemap 在 final,已实证路径);新族模板先看包的 tonemap 所在程序再定注入程序 |
| 5 | 指纹漂移(包升级) | hash 不匹配=自动零注入+提示;跟版流程见 §10 |
| 6 | Oculus 升级破坏 mixin | 混入面仅 patchComposite 一个方法;`defaultRequire` 策略+冒烟重验 |
| 7 | 误伤同名 anchor 的其他程序 | selector 门控+selectorCount 唯一性(§4) |
| 8 | 性能(每程序每次 reload 重算指纹/读包) | 首次计算后按 (包名,mtime) 缓存;patchComposite 仅 reload 时被调,非每帧 |

## 9. 实施顺序(就绪信号后执行)

1. 契约先行(§6 全家红,实证旧结构上 FAIL);
2. 引擎实现(PackFingerprint/TemplateLibrary/PatchExecutor/资源拷贝 task)→ 契约绿;
3. mixin(RuntimePackInjector + plugin 门控 + 日志打点);
4. iterationT 冒烟模板 + run-interop 实机 §7-1~4(判定/截图/imgdiff);
5. 本家包回归 §7-5 + 未知包安全 §7-6 + 幂等 §7-7;
6. 证据包落盘 → CHANGELOG/坑位册/记忆回写 → git commit(不 push)→ 汇报;
7. (批准后续)Complementary 正式族模板。

## 10. 包升级跟版流程(族维护 SOP)

包出新版 → 拷入 run-interop → 指纹不匹配(日志可见)→ 对照新版文件重导锚点
(锚点缺失处找语义等价点;结构性大改则该版暂标不支持)→ 更新模板 hash/锚点 →
§7-1~4 回归 → 模板版本+1 记 CHANGELOG。
