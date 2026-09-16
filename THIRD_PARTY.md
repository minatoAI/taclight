# TacLight 第三方致谢与许可

本仓(`taclight/`)自身许可是 **GPL-3.0-or-later**(根 `LICENSE`,与 `META-INF/mods.toml` 的 `license` 字段一致)。

## 零、总述(事实与口径)

- **本项目不重分发任何第三方 jar**:本仓 `libs/` 无 jar;发布 jar 内不含任何第三方类(实测条目级检查:无 `com/tacz`、无 `net/minecraftforge` 等);第三方依赖由用户按其自身许可从官方发布页获取。
- 下面各节列出**评估/借鉴过的上游项目**及其许可。本仓声称的现状是「**零逐字复制**」:对上游文件做逐字比对,本仓新文件命中 0;旧仓的少量命中均为既有接口签名基线。因此各上游许可(MIT/LGPL/GPL)**在当前状态下没有产生署名或声明义务**;这些小节是**自愿的学术致谢 + 事实留档**,不是许可条款的履行。
- 若将来发生任何受版权保护表达的复制/改编,本文件必须同步改写:届时义务会改变(需要随附许可副本、保持整包许可一致、并提供 Corresponding Source),见 GPL-3.0 §4/§5/§6 与 LGPL-3.0 §2/§4。
- 本节及以下所有内容**不是法律意见**;是否适用需专业人士判断。

## 一、模式借鉴五家(只借思想与包序,未搬运代码文件)

以下五家的具体实现**没有复制进本仓**(比对范围与结果见 §零),故本表是**自愿致谢**:
若将来确认存在复制,单独一张致谢表**不足以**满足 GPL-3.0/LGPL-3.0 —— 还需要随附许可副本、保持整包许可一致、并按 GPL-3.0 §6 提供 Corresponding Source。

1. **PrismarineJS/mineflayer** — MIT License, Copyright (c) 2015 Andrew Kelley
   (https://github.com/PrismarineJS/mineflayer)。
   借:`clickWindow` 事务确认 + 超时、`moveSlotItem` 取→放→余量放回、`carriedIndex` echo 抑制、
   `placeBlock` 的 blockUpdate ack、`activateItem` 包序(用于物品栏/交互包序设计)。
2. **gnembon/fabric-carpet** — MIT License, Copyright (c) 2020 gnembon
   (https://github.com/gnembon/fabric-carpet)。
   借:动作序列(USE/ATTACK/JUMP/DROP/SWAP)全序与 tick 冷却、背包槽同步、假玩家孵化思路。
3. **Creators-of-Create/Create(代码部分)** — MIT License, Copyright The Create Team
   (assets 目录为 All Rights Reserved,本仓未碰)(https://github.com/Creators-of-Create/Create,mc1.20.1/dev)。
   借:右键/左键动作管线全序、假玩家空连接与掉落回收。
4. **TartaricAcid/TouhouLittleMaid(代码部分)** — MIT License, Copyright (c) 2019-2025 tartaric_acid
   (LICENSE-MIT;assets 为 CC,本仓未碰)(https://github.com/TartaricAcid/TouhouLittleMaid,1.20 分支)。
   借:任务开关/切换钩子形状、统一背包视图、盾牌 start/stop 配对纪律、假玩家拒绝契约。
5. **microsoft/malmo** — MIT License(仓库已归档)(https://github.com/microsoft/malmo)。
   借:任务规格(世界 + 机位 + 观测 + 退出条件)的组织思想;1.11 时代代码无搬运价值,未搬。

## 二、评估过、未借用(思想参考,不进仓)

- **Voyager / MineDojo** — MIT。试次版本管理思想。
- **OpenAI VPT** — MIT。开机自检思想。
- **Shalev-Lifshitz/STEVE-1** — **无许可声明**,代码与权重一律未碰,仅参考 text→goal→policy 三段架构思想。
- **minerllabs/minerl** — **CC BY-NC-SA 4.0**,代码一律未碰,仅参考评审范式思想。

## 三、MinecraftForge 本体

- **MinecraftForge** — LGPL-2.1-only。我方以正常 mod 依赖方式**链接调用**(如 `FakePlayerFactory` 等 API),**未复制其源码进仓**。
- **本项目策略**:不 vendor Forge 源码(保持「仅链接」)。若将来需要 vendor,须先复核许可兼容性 ——
  注意 LGPL-2.1 §3 允许该副本适用 "GNU GPL version 2(或若已出现更新版本,你指定的那个版本)",
  且 FSF 的许可列表记载 **LGPLv2.1 与 GPLv3 兼容**(<https://www.gnu.org/licenses/license-list.html#LGPLv2.1>)。
  因此「与 GPL-3.0 不兼容」不是可靠理由;这里真正的理由是**策略选择**(避免 vendor 上游代码带来的维护/升级义务)。

## 四、依赖许可与再分发

**声明:本项目不重分发任何第三方 jar**(源码仓与发布 jar 内均不含)。下表的 jar 仅作为**用户自行安装的依赖**存在;
许可证信息以各项目官方发布页/仓库为准,下表为本项目核对结果的记录(版本为 2026-09 的实测版本)。

| 依赖 | 本版对接版本 | 许可(实测自 jar 内 `mods.toml`/官方页) | 与本项目关系 |
|---|---|---|---|
| **Timeless and Classics Zero (TaCZ)** | 1.1.8-hotfix | **`GPL3 / CC BY-NC-ND 4.0`**(其 jar 内未附许可全文) | 可选联动(枪挂灯);编译期 `compileOnly` 取 API,**不打包** |
| **Oculus** | 1.8.0 | **LGPL-3.0-only** | 可选(客户端);`compileOnly` 取 `iris.api.v0`,**不打包** |
| **Embeddium** | 0.3.31 | **LGPL-3.0-only** | 可选(客户端运行期),**不打包** |
| **Freecam**(xolt Freecam 的 Forge 移植) | 1.2.1+1.20 | **MIT** | 可选(运行期),本模组零代码引用 |
| **Player Animator** | 1.0.2-rc1+1.20 | **MIT** | 可选(运行期),TaCZ 第三人称动画的第三方库,本模组零代码引用 |

要点(事实与风险,非法律结论):
- **TaCZ 标注 `CC BY-NC-ND 4.0`(NC 非商业 + ND 禁止演绎)**,且其 jar 内未见许可全文。这与本项目 `GPL-3.0-or-later` 的再分发口径**存在冲突风险**,因此本项目**不分发 TaCZ 的 jar,也不把它的类打包进发布 jar**,只按官方文档使用其公开 API 做能力探测式联动。
- 因为不 convey 这些第三方作品,其许可中的再分发义务(附带许可、源码提供等)**不触发**。
- 若将来要把任一依赖打进发布件或与其静态合并,必须先逐个复核许可兼容性(尤其 TaCZ 的 NC 条款与 GPL 的商用/再分发条款),并同步改写本节与发行说明。
- 构建时这 5 个 jar 由用户自备放入 `libs/`(见 `libs/README.md` 与 README「从源码构建」);本仓 `.gitignore` 明确忽略 `libs/*.jar`,防止误提交。

## Baritone (idea-only reference, LGPL-3.0)

- **What**:input-injection timing(在 player-tick 起始阶段写 `input.forwardImpulse/leftImpulse/jumping`,使输入当 tick 生效)、
  每 tick「先清空再重灌」的声明式输入集、`MovementOption` 式 8 方向匹配(世界方向 → 玩家本地冲量,行走与朝向解耦)。**仅作概念参考**。
- **现状(逐条)**:未复制任何 Baritone 源码行;**未 vendor**(源码未进本仓);**未链接**(本仓不依赖 Baritone,发布 jar 内无 Baritone 类)。
  依 LGPL-3.0 §2/§4 的条件结构(义务绑定在 copy/modify/convey 上),上述动作均未触发 LGPL-3.0 §4 的组合作品义务。
- **若将来要链接**:LGPL-3.0 §4 允许按自选条款分发组合作品,但须满足 §4a-e(显著声明、附 GPL+LGPL 副本、版权显示、
  Minimal Corresponding Source 或 shared library 机制、必要的 Installation Information)。
  **若将来要 vendor**:LGPL-3.0 §2b 允许把修改版整体转为 **GNU GPL** 分发(该副本不再享有 LGPL 额外许可)——这是**策略选择**,不是「许可禁止」。
- **Verbatim check(2026-09-16)**:未复制行;新增源码中不含 Baritone 标识符
  (`movementHelper`/`PathExecutor`/`CalculationContext`/`ActionCosts`/`BetterPos`)与 Baritone 包名。

## Meteor Client (idea-only reference, GPL-3.0)

- **What**:两阶段 tick 纪律(Pre 发/Post 收)与 N-tick「按住键」处理器形状。**仅作概念参考**。
- **现状**:未复制任何源码行(比对口径见 §零);未链接。⇒ 当前不触发 GPL-3.0 §4/§5/§6 的分发义务。
- **若将来复制**:GPL-3.0 §5c 要求**整包以 GPL-3.0 授权**(本仓已是 GPL-3.0-or-later,方向兼容),并须按 §5 显著声明改动、
  按 §6 交付 Corresponding Source。即:许可**允许**复制,但会**改变整套分发义务**;本段「零复制」的表述届时必须改写。
- **Verbatim check(2026-09-16)**:未复制行;新增源码中不含 Meteor 标识符
  (`Rotations`/`PlayerUtils`/`CustomPlayerInput`/`InvUtils`/`Module`)与 `meteordevelopment` 字符串。
