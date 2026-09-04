# 会话交接文档：Complementary 双端漏光/半透明验证（2026-09-04 06:15）

> 用法：把本文件全文粘贴进新会话，或让新会话先读本文件，再继续执行“下一步操作”节。
> 工作区：`E:\dshHome\mc-mod-spotlight-attachment\wt-interop`（分支 `interop/core-extract`，未提交、未 push）

## 1. 任务目标（用户原话归纳）

1. 发射等级 10 体感复核（手电 + 枪灯，之前 15 太强、5 太弱丢失暖氛围，10 取中）。
2. 双端旁观漏光验证：两玩家实体 + 两窗口，验证“光穿墙”是否真的存在（墙 z=0，A 在墙前照墙，B 在墙后看墙背）。
3. 实体半透明 bug：灯照玩家实体有透层感 → 已定位根因并修复 → 实机看影子效果 + 确认动态实体 shadow 开销。
4. 怪物干扰测试环境：此前 wall 预设刷蜘蛛，双端均被咬死 → 已改被动猪 + 和平 + 创造 + NoAI。
5. 收尾：证据包 + CHANGELOG/坑位册回写 + commit（用户批准前不提交）。

## 2. 当前状态（新会话先看这里）

- 分支：`interop/core-extract`，`git status` 有 15 个 M + 4 个 untracked（见 §4），契约 `AllContracts: ALL PASS`（ScenePlan 207 项）。
- A 主机：已用新代码重启（boot `run-interop/boot-20260904-060759.log`），注入正常：
  `injected family=complementary pack=ComplementaryReimagined (+7586/+7576)`，
  `LIGHT-SYNC-ACK handheld=true gun=false`。QuickPlay 世界 `interop`，用户 `InteropA`。
- B 观察者：已随旧代码停掉（`run-observer/logs/latest.log` 停在 05:49），**需重新拉起**。
- LAN：A 重启后尚未 `!lan`，`netstat 25560` 为空是正常的，新会话按 §5 开 LAN 即可。
- 光影：双端 `oculus.properties` 均为 `shaderPack=ComplementaryReimagined, enableShaders=true`。
- 注意：A 日志里 `✘ 当前包无 TacLight 注入，请选 iterationT` 是**过期误报**（注入行已证明命中），B 端同理，忽略。
- wall_back 机位：已加入代码 `CamStore.DEFAULTS`（B 站 z=-8 朝南 yaw=0 回看墙背），但磁盘 `taclight-cams.json` 尚无该键 —— 正常，`load()` 会合并默认值，`/taclight cam goto wall_back` 可直接用（A 已是新代码）。

## 3. 关键参数（体感对照用）

- `TacLightConfig.radius` 基准 36.0（原 18，两倍）；intensity 6.0 不变；实际半径 `r*sqrt(I/6)`。
- 前向精简 `TACLIGHT_ATTEN_K = 2.0`（完整版保持 5.0）；峰值不涨、中远段抬起。
- 发射等级：`FlashlightItemIris` 与 `GunItemLightProviderMixin` 均为 `? 10 : 0`（10/15 的 4 次方 ≈ 1/5 暖氛围，5 则 1/81 丢失）。
- 锥角 outer32/inner18；颜色暖 `(1.0, 0.96, 0.88)`；手持锚眼位 + fwd0.35/right0.22/down0.14，枪 1.1x。
- 实体/手部调用点已乘 `* color.a`（修半透明）；地形不乘（alpha=1）。
- 前向 DDA：标量 Amanatides-Woo（float/int + `voxData[word]` 除法循环，无 ivec3/bvec3/位运算；solid>=2.5 归零，叶 0.4、植被 0.75；无 FUZZ 软边，边缘偏硬是已知代价）。

## 4. 未提交改动清单（勿拆分提交，用户批准前不 commit）

- `config/TacLightConfig.java`：radius 36。
- `item/FlashlightItemIris.java`、`mixin/GunItemLightProviderMixin.java`：发射等级 10。
- `interop/TemplateLibrary.java`：前向精简实体/手部 + 标量 DDA。
- `interop/RuntimePackInjector.java`：指纹文件新增 `gbuffers_entities.glsl` + `gbuffers_hand.glsl`。
- `mixin/oculus/TransformPatcherMixin.java`：新增 patchVanilla 6xString（entities/hand）。
- `resources/shader_patches/templates/complementary-r5.9.json`：terrain/entities/hand 三规则 + `* color.a`。
- `client/DebugCommandRelay.java`：`!lan`（publishServer 固定 25560）。
- `command/ScenePresets.java`：wall 蜘蛛 → 猪（防干扰）。
- `command/SceneExecutor.java`：环境锁 + 和平 + 全员创造 + 怪 NoAI。
- `command/CamStore.java`：新增 `wall_back`。
- `test/.../InlineCoreContract.java`、`TemplateLibraryContract.java`、`scene/ScenePlanContract.java`：对应契约。
- `CHANGELOG.md`：7 行占位（待补完整条目）。
- untracked：`.ref-packs/`（只读参考）、`gradlew-interop.cmd`、`tools/java-procs*.ps1`。

## 5. 下一步操作（新会话按序执行）

1. 查活：`powershell -NoProfile -File tools/java-procs.ps1`，确认 A（wt-interop）在跑；确认 25560 未监听是正常的。
2. A 布景（新代码，和平+创造+NoAI 猪）：经 `tools/interop-relay.ps1` 一次一写（≥1100ms，无 BOM）向 `run-interop/taclight-cmds.txt` 发：
   `/taclight scene wall` → `/taclight kit` → `/taclight cam goto wall_front` → `!lan` → `/taclight light on InteropA`
   （注意：原生 `/difficulty`、`/gamemode`、`/kill`、`/op` 经中继会被客户端命令树拒“未知或不完整的命令”，**不要用**；环境锁走 scene 预设。）
3. 确认 LAN：`netstat -ano | findstr 25560` 有 LISTENING，且 A 日志有 `RELAY lan -> true (port=25560)`。
4. 拉 B：`gradlew-interop.cmd runClientObserver -PtaclightJoin=127.0.0.1:25560 -PtaclightUser=ObserverB`（后台跑，轮询 `run-observer/logs/latest.log` 的 `Connected to modded server` / `ObserverB logged in`）。
5. B 到墙后：向 `run-observer/taclight-cmds.txt` 发 `/taclight cam goto wall_back`，确认 `cam -> 'wall_back'`。
6. 双端截图：A 端 `!shot`（墙前锥池），B 端 `!shot`（墙背应黑 + 猪不透明 + 影子）；截图在各 `screenshots/` 下。
7. 判定：墙背亮 = 仍漏光（DDA 未生效）；墙背黑 = 穿墙已修；猪/玩家无透层感 = alpha 门生效；影子由宿主 DoLighting 免费给出，动态实体不增加体素重建（静态网格，DDA 按像素步进）。
8. 收尾：证据包 `docs/evidence/2026-09-04-<topic>/`（README 判定数字 + shots + log 片段 + manifest.sha256）→ CHANGELOG 顶部 + 坑位册 → 用户批准后 commit，不 push。

## 6. 坑位提醒（别重踩）

- 中继纪律：一次一写、≥1100ms、无 BOM；Git Bash 下 `powershell -File tools/interop-relay.ps1 -Commands @(...)` 的括号会被 bash 吃，改用循环单条发或写 ps1 再调，固定加 `MSYS2_ARG_CONV_EXCL='*'`。
- boot 日志是 UTF-16，grep 前 `tr -d '\0'`。
- 旧后台拉起任务的 failed（exit 1）是停进程带下来的，属正常。
- 别碰的进程：`respawn-beacon` 项目的 java 与 gradle 进程（`java-procs.ps1` 可区分）。
- 构建走 `gradlew-java17.cmd taclightContracts`；契约绿是重启前提。
- 枪灯手动开关 ticket：TaCZ 探针每 tick 覆盖问题待立项（键位用户未定），本次不动。
