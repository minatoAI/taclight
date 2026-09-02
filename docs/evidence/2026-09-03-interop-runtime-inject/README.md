# 2026-09-03 方案C 里程碑2:interop 运行时注入引擎 — 实机终验证据包

## 判定串与验收对照(计划文档 §7 七项)

| # | 验收项 | 判定 | 证据 |
|---|---|---|---|
| 1 | 补丁包加载不被静默禁用 | PASS | 全程零 `ShaderCompile` / 零包禁用日志;管线正常创建(下 timeline) |
| 2 | `[TacLight] interop injected family=iterationT` 日志 | PASS | `log-excerpt.txt` L3:`interop injected family=iterationT pack=iterationT 3.2.0 (+20852 chars)` |
| 3 | 灯 on/off 像素差 | PASS | `it32clean_off/on.png`:imgdiff `meanDiff=36.43 maxDiff=242 changed=184207/409920 (45.0%)`,冻结午夜场景 |
| 4 | 视觉正确性 | PASS | off=全黑夜景 / on=锥形光池(边界清晰、花丛被照亮),见截图 |
| 5 | 本家包回归 | PASS | `ownpack_light_on.png`:taclight-shaders-dev 原生五段链锥光正常,interop 零注入 |
| 6 | 未知包安全 | PASS | taclight-shaders-dev 对注入引擎=未知包:零注入 + `暂无注入模板` 提示**恰好 1 次**(重复 reload 不刷屏)+ 包正常渲染 |
| 7 | 幂等 | PASS | 切回 iterationT 连续两次 `!reload`:每次管线重建注入**恰好一次**、`+20852 chars` 恒定、零禁用零累积(excerpt L7-L13) |

契约:`AllContracts: ALL PASS`(taclightContracts 全套,含本里程碑新增 65 项:
TemplateLibraryContract / InlineCoreContract 16 / PatchExecutorContract 20 / RuntimePackInjector /
PackFingerprint 等套件;清理探针后复跑仍 ALL PASS)。

## 实机 timeline(干净构建一次连续会话,run-interop/logs/latest.log)

1. `mixin gate ... -> true (self-guarded)` — 坑79 修复后的门控行为
2. `Creating pipeline`(登录期)→ `interop injected ... (+20852 chars)` — 验收 2
3. smoke:`/taclight scene grass` → `cam goto grass_low` → `light off` → !shot → `light on` → !shot → 验收 3/4
4. 切 `taclight-shaders-dev` + reload×2:零 injected、announce ×1 — 验收 5/6
5. 本家包 `light on` 截图:原生锥光正常 — 验收 5
6. 切回 `iterationT 3.2.0` + reload×2:injected ×2(每次管线重建一次,恒定字节数)— 验收 7

## 文件

- `it32clean_off.png` / `it32clean_on.png` — 干净构建最终验收对(冻结午夜,grass_low 机位)
- `wj_off.png` / `wj_on.png` — 首轮打通验收对(meanDiff=37.30 maxDiff=245 changed=40.8%,与终验同量级互证)
- `ownpack_light_on.png` — 本家包原生链回归 + 未知包零注入并存的证明
- `taclight-composite-dump-1.fsh` — patchComposite 实际收到的运行时文本(66243 字符;
  坑80 锚点取证依据:`#version  330` 双空格、宿主 uniform 在前、HeldLighting 定义行)
- `taclight-patched-sim.fsh` — 同一文本经模板三操作后的本地重建(版本行/核 2054/调用 3358)
- `log-excerpt.txt` — 上述 timeline 的日志原文

## 本轮新坑(已入册 `docs/调试环境搭建计划.md` §6)

- 坑79 shouldApplyMixin 里 Class.forName 目标类 = prepare 期抢先加载 → 零 mixin 变换永久缓存,静默失效
- 坑80 patchComposite 输入是 jcpp 解析后文本:模板锚必须按运行时实测文本编写;注入文本须指令全解析+零 uniform;注入点须在宿主 uniform 声明之后(insertBeforeLine)
- 坑81 一次性不可复现亮帧异常(疑似首建时灯登录恢复 on;worldTime 可视化证明 shader 时间正确,非时间缺陷)
- 坑82 ConcurrentHashMap 禁 null value → 负缓存用 Set;patchSource 运行在 Iris 管线构建内,必须 try/catch fail-safe(异常=原文返回零注入)

## 复现

```powershell
# 从 wt-interop 根:
powershell -File tools/interop-session.ps1 -Shaders on   # 启动(shaderPack=iterationT 3.2.0)
powershell -File tools/interop-smoke.ps1 -Tag repro -User InteropA
node tools/imgdiff.js run-interop/screenshots/repro_off.png run-interop/screenshots/repro_on.png
cmd /c gradlew-interop.cmd taclightContracts --offline   # 期望 AllContracts: ALL PASS
```
