# Minecraft 模组调试手段调研报告 —— 面向 AI 自动化操作

> 日期:2026-08-29
> 调研对象:在 Minecraft Java 版中,通过命令/代码/外部程序"可由 AI 自动化执行"的调试手段(含安装特定模组提供接口的方案)。
> 项目背景:TacLight(Forge 1.20.1 / Forge 47.1.3 / Oculus 1.8.0 / Embeddium 0.3.31),客户端渲染型模组,宿主机 Windows。
> 本报告与《调试环境搭建计划 v2》(taclight/docs/调试环境搭建计划.md)配套:该计划已定主控制通道(PowerShell+Win32 程序化驱动),本报告负责补齐全景图、证实/推翻其关键假设、并给出增量建议。非编号文档,遵守"不新增编号文档"红线。

---

## 0. TL;DR

1. **生态现状**:"AI 可编程调试 Minecraft"在**服务端/玩法层**非常成熟(RCON、协议机器人 mineflayer、GameTest 框架、数据包函数、MCP 桥接项目),但在**客户端渲染层没有现成方案**——机器人/桥接器不承载渲染管线,看不了画面。渲染类模组的 AI 调试闭环只能靠"真实客户端 + 程序化注入 + framebuffer 截图"自建,即当前计划的主路线,方向正确。
2. **对计划的关键证实(本次实测源码级)**:Iris 1.20.1 分支的公开 API `IrisApi` (api/v0) **不暴露任何重载方法**,且整个 Iris 1.20.1 源码树**没有注册任何聊天命令**(无 `/iris` 命令)。"单键注入触发 `key_iris.keybind.reload`"是唯一程序化热重载路径,计划 P0 假设成立。
3. **最有价值的三条增量建议**:
   - 自实现客户端命令 `/taclight shot <路径>`(调用原版 `Screenshot.grab` 截 framebuffer)——不受窗口遮挡影响,比 GDI 截窗更硬;GDI 截窗保留为通用兜底。
   - 一切能命令化的调试动作(diag dump / bench / 场景 / 机位)**全部命令化**,用已验证的 `chat` 注入通道触发;按键注入只留给无法命令化的着色器 reload。P/O 调试键保留为人工快捷键,同功能各注册一条命令。
   - 服务端逻辑(SceneBuilder 幂等性、命令注册、NBT 正确性)接入 Forge 内建 **GameTest**(`/test` + `gradlew runGameTestServer`,dev 环境默认可用),与视觉闭环解耦的自动回归。

---

## 1. 评估维度

对每种方案按以下维度评估,星级为对"本项目(客户端渲染模组 + Windows + AI 闭环)"的适配度:

| 维度 | 含义 |
|---|---|
| 可编程触发 | AI 能否通过 shell/网络/文件触发动作(无需人手、无需 GUI 交互) |
| 可机读输出 | 结果能否以日志/文件/命令回显形式被 AI 直接读取判定 |
| 断言能力 | 能否程序化判定"测试通过/失败",而非肉眼看图 |
| 视觉产物 | 能否产出真实渲染画面证据(对渲染模组是硬需求,计划红线:只允许真机截图) |
| 环境成本 | 需要装什么(模组/工具链)、是否仅限开发环境、是否占键鼠/桌面 |
| 1.20.1 Forge 适配 | 该方案在 1.20.1 + Forge 上是否可用(Fabric-only 方案标灰) |

---

## 2. 方案全景对比

| # | 方案 | 层次 | 可编程触发 | 可机读输出 | 断言 | 视觉产物 | 环境成本 | 1.20.1 Forge | 对本项目 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Gradle run task + JDWP 远程调试 | 进程 | ★★★ | ★★★(断点/栈) | ★ | ✗ | 仅 dev 环境 | ✓ | Java 断点深挖用 |
| 2 | RCON 远程控制 | 命令 | ★★★ | ★★★ | ★★ | ✗ | 需专用服务器 | ✓(仅 runServer) | 逻辑回归通道(可选) |
| 3 | 原版命令矩阵(/data /execute /debug…) | 命令 | ★★★ | ★★★ | ★★★ | ✗ | 零成本 | ✓ | 状态取证/断言主力 |
| 4 | 数据包 mcfunction 链 | 脚本 | ★★★ | ★★ | ★★ | ✗ | 零成本 | ✓(无宏,1.20.2+ 才有) | 场景预设原型 |
| 5 | Forge GameTest(/test + runGameTestServer) | 测试框架 | ★★★ | ★★★ | ★★★ | ✗ | 仅 dev 默认开启 | ✓ | 服务端逻辑自动回归 |
| 6 | mineflayer / MCC / MCProtocolLib 协议机器人 | 机器人 | ★★★ | ★★★ | ★★ | ✗ | Node/独立进程 | ✓ | 参考为主(不能渲染) |
| 7 | Carpet /player 假人 | 机器人 | ★★★ | ★★ | ★★ | ✗ | 需装 mod | ✗ Fabric/Quilt only | 不可用(生态参考) |
| 8 | CC:Tweaked 游戏内外联 | 脚本 mod | ★★ | ★★ | ★ | ✗ | 需装 mod | ✓ | 游戏内探针(非主线) |
| 9 | KubeJS / CraftTweaker | 脚本 mod | ★★★ | ★★ | ★ | ✗ | 需装 mod | ✓ | 免编译命令扩展(可选) |
| 10 | Minecraft MCP server 桥(yuniko 等) | AI 桥 | ★★★ | ★★★ | ★★ | ✗ | Node + mineflayer | ✓ | 工具面设计参考 |
| 11 | minecraft-dev-mcp(模组开发向) | AI 桥 | ★★★ | ★★★ | — | ✗ | MCP 接入 | ✓(实测 1.20.1) | 源码考古辅助(可选) |
| 12 | 外部截窗(GDI,计划主通道) | 视觉 | ★★★ | 读图 | 肉眼/AI | ★★★ | 零成本 | ✓ | **主通道(已定)** |
| 13 | 原生 F2 → screenshots 目录 | 视觉 | ★★ | 读图 | 肉眼/AI | ★★★ | 零成本 | ✓ | 计划次通道(已定) |
| 14 | 自实现客户端截图命令 | 视觉 | ★★★ | 读图 | 肉眼/AI | ★★★ | ~30 行代码 | ✓ | **建议新增(backlog)** |
| 15 | Oculus 单键注入热重载 | 视觉 | ★★ | 日志计数 | ✗ | ★★★ | 键位绑定 | ✓ | **主路(已定,P0 验证)** |
| 16 | spark /sparkc 客户端性能分析 | 性能 | ★★★ | 在线报告 URL | ★ | ✗ | 需装 mod | ✓(legacy 构建) | 帧耗时方法级定位 |
| 17 | AI 桌面自动化(computer-use) | 兜底 | ★★ | 截图 | 肉眼/AI | ★★ | 占真实键鼠 | ✓ | 兜底(计划已定,次选) |

> 灰名单(Carpet、screenshot-bot、Fabric GameTest 参数等 Fabric-only 方案)保留在正文供生态参考,不进入主线。

---

## 3. 分层详解

### 3.1 构建/进程层(Gradle / JDWP / 日志 / CI)

**run task 全集**(ForgeGradle 5,`gradlew`):
- `runClient`(客户端)、`runServer`(专用服务器)、`runData`(数据生成)、`runGameTestServer`(1.18.1+/Forge 39.0.88+,1.20.1 MDK 自带)。均为 **userdev 开发环境专用**,正式发布产物不走 Gradle。
- 传参:build.gradle 的 `minecraft.runs.<name>` 块支持 `jvmArg` / `property` / `arg` / `environment` / `workingDirectory` / `forceExit`。注意 `org.gradle.jvmargs` 只作用于构建 JVM,不影响游戏进程。

**JDWP 远程调试**(Java 断点级调试,AI 可经 DAP 协议半自动化附加):
```groovy
minecraft.runs.client {
    jvmArg '-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005'
}
```
启动后进程挂起等待附加;IDEA "Remote JVM Debug" 或任意 DAP 客户端连 `localhost:5005` 可下断点/读栈/取变量。AI 用 DAP 自动化断点在工程上可行但成本高,列为深水区,不作主线。

**日志取证**(计划已采用):`run/logs/latest.log` 是 AI 的主输出通道,grep 套路:`GlShader`(编译错)、`TacLight`(诊断)、`Iris`/`Reloading`(包状态/重载计数)。结构化调试输出(diag dump)是本项目的正确增强方向。

**CI(远期)**:GitHub Actions 可跑 `runGameTestServer`(exit code 作门禁,社区有 [成熟实例](https://github.com/alexiokay/MinecraftTransportSimulator-NeoForge/blob/1.21.1/TESTING.md));客户端冒烟测试有现成 Action [MC-Runtime-Test](https://github.com/marketplace/actions/mc-runtime-test)(验证"客户端能启动到主菜单")。本项目以本地离线构建为约束,CI 列为远期。

### 3.2 服务器命令层(RCON / stdin / 原版命令矩阵)

**RCON**(TCP 文本协议远程控制台):
- 开启:`server.properties` 里 `enable-rcon=true`、`rcon.port=25575`、`rcon.password=<密码>`。
- 客户端:`mcrcon`(C 语言 CLI,事实标准):`mcrcon -H 127.0.0.1 -P 25575 -p <密码> "/taclight scene bloom"`;Python 生态亦有多个库(mcstatus、aio-mc-rcon 等)可编程调用。
- 限制(重要):**只有 dedicated server 支持 RCON**——单人游戏/集成服务器(integrated server)不监听 RCON;RCON 只返回命令回显,收不到玩家聊天;协议明文,只限本机/内网。
- 对本项目:可支撑"runServer + mcrcon"的**纯逻辑自动回归通道**(不占键鼠、不置顶窗口),与 runClient 视觉通道解耦并行;注意 dev 环境下 runServer 与 runClient 的 run 目录是分开的。gradle 直启时 stdin 转发不可靠,统一走 RCON。

**原版命令矩阵**(全部可经 chat 注入或 RCON 触发,正式环境可用):

| 类别 | 命令 | AI 用途 |
|---|---|---|
| 场景搭建 | `/fill` `/setblock` `/summon` `/kill` `/tp` `/gamerule doDaylightCycle false` `/time set` `/weather clear` | 程序化布景+锁环境(计划 SceneBuilder 的原子操作) |
| 状态查询 | `/data get entity <target> [path]`、`/data get block <pos> [path]` | 机读读取实体/方块 NBT(如灯实体状态、标记实体) |
| 断言 | `/execute if block/entity/data/score …`(链尾条件即成败);`execute store result` 或函数 `return run` 捕获 | 程序化判定而非肉眼;配合日志 grep 做自动验收 |
| 定时 | `/schedule function <f> <time>` | 延迟触发的自动化时序(等曝光收敛后截图前调用诊断) |
| 热重载 | `/reload` | 数据包(function/recipe/tag)免重启重载 |
| 性能(服务端) | `/debug start` / `/debug stop` → 生成 `profile-results-*.txt`(树状耗时);`/forge tps`、`/forge entity`、`/forge track`、`/forge modlist` | 服务端 tick 耗时取证;`/debug` 只覆盖服务端,**对客户端渲染无用** |

### 3.3 数据包函数层(游戏内脚本,零成本)

- 存档内 `datapacks/<包>/data/<ns>/functions/*.mcfunction`,一行一条命令;`/function ns:name` 执行,`/reload` 热重载(单人模式可用, cheats 开启)。
- AI 工作流:写 mcfunction(布景+锁环境+清场+传送到机位)→ `/reload` → `/function taclight:scene/bloom` → 一条 chat 注入完成整套场景。**把多条命令合并成一条,减少注入次数**(每次键入注入都是时序风险点)。
- 版本注意:**1.20.1 不支持 mcfunction 宏(参数化),宏是 1.20.2+**。参数化场景需按预设各写一个函数——对本项目的固定 preset 集合恰好够用。
- 与计划 SceneBuilder(Java)的关系:mcfunction 免编译、改完即 `/reload`,适合**快速试错/原型**;定型后转 Java SceneBuilder(更灵活、可进模组分发)。两者可并存,不冲突。

### 3.4 GameTest 测试框架(Forge 1.20.1 内建,dev 环境默认可用)

Forge 1.20.1(39.0.88+)自带 GameTest,无需额外依赖:

```java
@GameTestHolder(TacLight.MODID)          // 方式 A:value 必须是 mod id
public class TacLightGameTests {
    @GameTest(timeoutTicks = 100)
    public static void sceneWallIdempotent(GameTestHelper helper) {
        helper.startSequence().thenExecute(() -> {/* 断言逻辑 */})
              .thenSucceed();              // 必须显式 succeed,否则超时判负
    }
}
// 方式 B:RegisterGameTestsEvent 注册(每个方法需 @GameTest(templateNamespace = MODID))
// 动态测试:@GameTestGenerator;批量:@GameTest(batch=...) + @BeforeBatch/@AfterBatch
// 场景模板:data/<ns>/structures/*.nbt(结构方块导出)
```

- 游戏内命令是 **`/test`**(非 `/gametest`):`/test run <全名>`、`/test runall`、`/test runthis|runthese`、`/test runfailed`、`/test pos`。
- 自动化:**`gradlew runGameTestServer` 启动无头服务器,自动跑完全部测试后退出,exit code = required 测试失败数**(CI 加 `forceExit false` 防 daemon 坑)。
- 环境边界:`/test` 与 GameTest **默认只在 dev run config 启用**;正式运行环境需 JVM 属性 `forge.enableGameTest=true`。run config 里配 `property 'forge.enabledGameTestNamespaces', 'taclight'` 限定命名空间。
- 生态:NeoForge 1.20.4+ 同体系(`/test`,属性改 `neoforge.enabledGameTestNamespaces`);Fabric 走 fabric-gametest-api-v1(`-Dfabric-api.gametest` 无头运行,`-Dfabric-api.gametest.report-file=` 出 XML 报告)。
- **对本项目的边界**:GameTest 只覆盖服务端逻辑(方块/实体/NBT/命令结果断言),**测不了渲染管线**;适合为 SceneBuilder 幂等性、`/taclight` 命令注册与参数校验、灯实体 NBT 等做自动回归。

### 3.5 协议机器人层(mineflayer / MCC / Carpet)——玩法自动化生态,本项目参考为主

- **mineflayer**(PrismarineJS,Node.js):事实标准协议机器人库,API 覆盖收发聊天(`bot.chat`)、读位置/血量/物品、方块查询、寻路等插件生态;支持版本范围很宽(1.8 至较新版本)。最小示例:
  ```js
  const mineflayer = require('mineflayer')
  const bot = mineflayer.createBot({ host: 'localhost', username: 'Bot', version: '1.20.1' })
  bot.on('chat', (u, m) => { if (m === 'ping') bot.chat('pong') })
  ```
  **根本局限:机器人是协议客户端,不渲染画面**——对渲染模组调试无视觉价值。
- **Minecraft Console Client (MCC)**:轻量 TUI 客户端(独立进程,无需装游戏),连服务器、发命令、收聊天文本,支持 ChatBot 脚本自动化;与 mineflayer 的差异是轻量、命令行友好。MCProtocolLib(GeyserMC)是 Java 侧协议库。
- **Carpet mod 假人**(`/player` 指令族):`/player <名字> spawn` 生成受控假人,`/player <名字> attack|use|jump|stop …` 模拟玩家动作,另有 `/tick warp`(tick 加速)、`/log`(实时监控)、scarpet 脚本语言——是"程序化模拟真人玩家"的最强方案。**但 Carpet 是 Fabric/Quilt only,1.20.1 Forge 无官方移植,本项目不可用**。Forge 侧替代:mineflayer/MCC 机器人,或模组内 `summon` + 目标选择器(计划 SceneBuilder 已覆盖本项目需求:蜘蛛对照等)。
- 对 TacLight 定位:生态参考。唯一潜在用途:未来做"机器人当假人目标"的自动化对照,但现状 SceneBuilder summon 已满足。

### 3.6 游戏内外联/脚本 mod(CC:Tweaked / KubeJS)

- **CC:Tweaked**(ComputerCraft 重制版):游戏内电脑跑 Lua,`http.get/post/request` 与 **websocket** 可与外部程序双向通信(需在配置允许本地 IP)。可以搭"游戏内代理":外部 AI 发指令 → 电脑执行动作/采集数据 → 回传。1.20.1 有 Forge 构建。局限:驱动的是电脑/外设,与渲染管线无关;对复杂客户端行为无能为力。列为奇技淫巧储备,不作主线。
- **KubeJS**(Forge 1.20.1 对应 2001.6.x 系列):JS 脚本扩展模组,分 startup/client/server 三类脚本,`/reload` 热重载 server 脚本;可注册新命令(`ServerEvents.commandRegistry`)、监听事件、调用 Java 类(受 class 过滤器约束)。价值:**免编译增加调试命令/胶水逻辑**。对本项目:主模组已有 Java 命令体系,KubeJS 的增量价值主要是"不想重编译时快速加一条诊断命令",可选。
- **CraftTweaker**:同类脚本模组,偏配方/集成,略。
- 取舍建议:简单场景/状态操作 → mcfunction(3.3);需要调 Java API 的诊断胶水 → KubeJS(可选);正式功能 → 模组 Java 代码。

### 3.7 AI 桥接层(MCP 生态与先例)

- **yuniko-software/minecraft-mcp-server**(712★):MCP(Model Context Protocol)server + mineflayer 底座,把"移动/放方块/打怪/读背包/读聊天"等封装为 MCP 工具供 LLM 客户端直接调用(面向 1.21.x)。**对本项目的价值是架构参考**:证明"把游戏动作封装成 AI 工具面"是成熟模式;将来若把 `/taclight scene|cam|shot|diag` 封装为 MCP 工具面,即可被任意 MCP 客户端(AI)直接调用——与计划的 chat 注入通道互补而非替代。
- **MCDxAI/minecraft-dev-mcp**:面向**模组开发**的 MCP server(反编译/重映射/mojmap/源码检索/Mixin 校验,实测支持 1.20.1)。对"考古 TaCZ/Oculus/Embeddium 源码找钩子"这类静态调试有直接价值,可选接入 AI 工具链。
- **Voyager**(NVIDIA):LLM agent 在 Minecraft 中自主探索的知名先例,底座同样是 mineflayer。结论与 3.5 一致:AI 玩 MC 的生态全部不渲染,渲染调试必须自建视觉闭环(即当前计划)。
- 通用"AI 桥"类模组(客户端暴露 HTTP/WS 接口):**没有找到适配 Forge 1.20.1 + 渲染调试场景的现成方案**;社区桥接均为玩法向(服务端/协议层)。

### 3.8 客户端视觉层(渲染模组专属,本项目核心)

**截图三通道对比**:

| 通道 | 原理 | 优点 | 风险/成本 |
|---|---|---|---|
| GDI 截窗(计划主通道,`drive.ps1 shot`) | `CopyFromScreen` 按窗口矩形截屏 | 路径自定、零依赖 | **窗口被遮挡时截到覆盖物**;需 present+pin 保证前台 |
| 原生 F2(计划次通道) | 引擎内截图 → `run/screenshots/` | 一定来自 framebuffer | 需键注入;文件名带时间戳,需翻目录 |
| **客户端截图命令(建议新增)** | 模组内调原版 `Screenshot.grab(gameDirectory, file, framebuffer, null)` | 一定来自 framebuffer、路径自定、一条 chat 命令直达 | ~30 行客户端命令,零新依赖 |

- 社区先例佐证"命令触发截图 + 外部程序读图"模式:[screenshot-bot](https://github.com/Jeroen-45/screenshot-bot)(Fabric mod,专为外部程序自动化设计)、ScreenshotUtility(图库/元数据型,参考)。Fabric-only,Forge 侧自实现最省。
- **建议**:`/taclight shot <相对路径>` 列入 backlog(P1 顺手实现);与 `imgdiff/griddiff` 工具链天然衔接。符合红线:framebuffer 真机截图,非 mock。

**相机/机位**:
- 计划的 `/taclight cam`(传送玩家到注册机位)= 确定性机位的最佳实践,调研未发现更优方案。
- 参考:Freecam(CurseForge,1.20.1 Forge 可用,键位控制分离相机穿墙飞行)、Spectator Freecam(Modrinth,命令/键位切旁观)、Replay Mod(回放+稳定运镜)。对"同一 bug 的 A/B 对比"用传送更确定,freecam 类只作人工观察辅助。

**Iris/Oculus 热重载(计划 P0 核心假设,本次源码级证实)**:
- Iris 1.20.1 分支 `net.irisshaders.iris.api.v0.IrisApi` 全部方法仅:`getMinorApiRevision` / `isShaderPackInUse` / `isRenderingShadowPass` / `openMainIrisScreenObj` / `getMainScreenLanguageKey` / `getConfig` / `createTextVertexSink` / `getSunPathRotation` ——**没有 reload 类方法**。
- 全源码树无任何 `Command` 注册文件——**Iris 1.20.1 无 `/iris` 等聊天命令**。
- 推论:程序化热重载只剩两条路——① 键位注入触发 `key_iris.keybind.reload`(计划 P0 主路,正确);② 反射调 Iris 内部重载类(脆弱,版本升级即碎,不推荐)。`openMainIrisScreenObj` 虽是公开 API,但打开 GUI 后的确认仍需键注入,无增益。
- 附带事实:Iris 有[已知重载内存泄漏 issue #1569](https://github.com/IrisShaders/Iris/issues/1569)——长时间自动化会话中多次 reload 后应重启客户端,防内存干扰测量。

**性能分析**:
- **spark**(需装 mod,Forge 1.20.1 用其 legacy 构建,非活跃维护但可用):客户端命令为 **`/sparkc`**(经 chat 注入即可用,绕开 F3 组合键限制)。`/sparkc profiler start [--timeout 秒]` → `stop` 生成在线报告 URL(可由 AI 抓取/解析),定位到方法级帧耗时;`/sparkc tps`、`/sparkc health`、heap dump 等。Windows 上原生采样器不可用,自动回退 Java 采样器(精度略降)。
- 与计划 O 键 bench 的分工:bench(3 秒 avg/1% low/min)= 快速回归指标;sparkc = 慢问题(哪一段 pass 吃帧)方法级定位。两者互补。
- 原版 `/debug` profiler 只覆盖服务端 tick;客户端 F3+L 属组合键(注入不可行,见计划红线 2)→ 均不适合本项目客户端测量,sparkc 是正解。

---

## 4. 对《调试环境搭建计划 v2》的映射与增量建议

| 计划条目 | 调研结论 | 建议 |
|---|---|---|
| C. drive.ps1 主通道 | 与生态实践一致(RCON/机器人都不渲染,视觉闭环必须真实客户端+注入) | 维持 |
| E. 热重载(单键注入 R) | **源码级证实**:Iris 1.20.1 API 无 reload、无命令,键注入是唯一程序化路 | 维持;放弃反射兜底路线 |
| D. 截图(GDI 主 + F2 次) | GDI 受窗口遮挡风险;F2 文件名不可控 | **新增 `/taclight shot <路径>`**(framebuffer 级,chat 一条直达),GDI 降级为通用兜底 |
| A. SceneBuilder(Java) | mcfunction 可免编译原型化同样场景;1.20.1 无宏 | 先 mcfunction 快速试错,定型后转 Java;或直接 Java(若 P1 时间充裕) |
| F. 调试键 P/O | chat 注入通道已验证可靠,命令比按键更稳(无持键态/焦点坑) | diag/bench **各注册一条命令**(`/taclight diag` / `/taclight bench`),键位保留为人工快捷键;AI 一律走命令 |
| O 键 bench | sparkc 提供方法级火焰图(在线报告) | P1 可选加装 spark(legacy 构建),bench 保留为快指标 |
| 2. 现状(`/taclight kit` 服务端命令) | GameTest 是服务端逻辑的标准自动回归框架 | P2 后可选:为 SceneBuilder 幂等性、命令参数校验、灯 NBT 写 GameTest(`runGameTestServer` 出 exit code) |
| (无对应) | RCON 仅专用服务器支持 | 若做纯逻辑回归,可另起 runServer + mcrcon 通道(不占桌面);与视觉通道解耦,非必需 |
| (无对应) | minecraft-dev-mcp(反编译/mojmap/源码检索,实测 1.20.1) | 可选接入,辅助 TaCZ/Oculus 源码考古;非必需 |
| 运行模式/红线 | 不变 | — |

---

## 5. 风险与陷阱清单

1. **版本锁定 1.20.1**:mcfunction 宏(1.20.2+)、NeoForge `/test` 文档、Fabric GameTest 参数均**不适用**;Carpet、screenshot-bot 等 Fabric-only mod 不可用,勿照抄 Fabric 教程。
2. **RCON 明文且仅专用服务器**:只绑本机;单人/集成服务器无 RCON,勿在 runClient 上试。
3. **GameTest 默认仅 dev 环境**:正式环境要 `forge.enableGameTest=true`;CI 中 `forceExit` 配置不当会导致构建假失败。
4. **Iris 反射重载是版本升级即碎的路线**:除非卡死,不走;Oculus 升级后需回归验证 `key_iris.keybind.reload` 键位名。
5. **多次热重载内存泄漏**(Iris #1569):长自动化会话多次 reload 后建议重启客户端再测性能。
6. **spark legacy 构建**非活跃维护,Windows 无原生采样器(Java 采样器精度略低)——火焰图结论需复核再下判断。
7. **dev ≠ 发布环境**(计划已列第 7 条):GameTest/RCON/spark 结论均在 dev 语境成立,成品验收仍走发布产物。
8. 机器人/MCC 加入本地服务器需 `online-mode=false`(仅限本机离线环境),勿用于公网。

---

## 6. 参考链接

**测试框架/构建层**
- Forge 1.20.1 GameTest 官方文档: https://docs.minecraftforge.net/en/1.20.1/misc/gametest/
- Forge GameTest 实操指南(SizableShrimp): https://gist.github.com/SizableShrimp/60ad4109e3d0a23107a546b3bc0d9752
- NeoForge GameTest: https://docs.neoforged.net/docs/1.21.1/misc/gametest/
- Fabric 自动化测试: https://docs.fabricmc.net/develop/automatic-testing
- ForgeGradle 5 Run Configs: https://docs.minecraftforge.net/en/fg-5.x/configuration/runs/
- MC-Runtime-Test(CI 客户端冒烟): https://github.com/marketplace/actions/mc-runtime-test
- CI 跑 runGameTestServer 实例: https://github.com/alexiokay/MinecraftTransportSimulator-NeoForge/blob/1.21.1/TESTING.md

**命令/数据包**
- /debug: https://minecraft.wiki/w/Commands/debug · /reload: https://minecraft.wiki/w/Commands/reload · /data: https://minecraft.wiki/w/Commands/data · /execute: https://minecraft.wiki/w/Commands/execute · /schedule: https://minecraft.wiki/w/Commands/schedule
- ForgeCommand(源码): https://github.com/MinecraftForge/MinecraftForge/blob/1.20.x/src/main/java/net/minecraftforge/server/command/ForgeCommand.java

**RCON**
- RCON 协议与配置: https://minecraft.wiki/w/RCON · server.properties: https://minecraft.fandom.com/wiki/Server.properties
- mcrcon: https://github.com/tiiffi/mcrcon

**机器人/脚本 mod**
- mineflayer: https://github.com/PrismarineJS/mineflayer · node-minecraft-protocol: https://prismarinejs.github.io/node-minecraft-protocol/
- Minecraft Console Client: https://github.com/MCCTeam/Minecraft-Console-Client
- MCProtocolLib: https://github.com/GeyserMC/MCProtocolLib
- Fabric Carpet(/player 假人): https://github.com/gnembon/fabric-carpet
- CC:Tweaked http/websocket 文档: https://tweaked.cc/module/http.html
- KubeJS: https://github.com/KubeJS-Mods/KubeJS

**AI 桥**
- minecraft-mcp-server(mineflayer 底座 MCP): https://github.com/yuniko-software/minecraft-mcp-server
- minecraft-dev-mcp(模组开发向 MCP): https://github.com/MCDxAI/minecraft-dev-mcp
- Voyager(NVIDIA LLM agent): https://github.com/MineDojo/Voyager

**客户端视觉/性能**
- Iris 源码(IrisApi v0,1.20.1 分支): https://github.com/IrisShaders/Iris/tree/1.20.1
- Iris 重载内存泄漏 issue: https://github.com/IrisShaders/Iris/issues/1569
- spark 命令用法: https://spark.lucko.me/docs/Command-Usage · 下载: https://spark.lucko.me/download
- screenshot-bot(命令触发截图,Fabric 先例): https://github.com/Jeroen-45/screenshot-bot
- Freecam: https://www.curseforge.com/minecraft/mc-mods/free-cam · Spectator Freecam: https://modrinth.com/project/9tbAj44W
- 原生截图指南(F2→screenshots): https://www.minecraft.net/en-us/article/screenshotting-guide
