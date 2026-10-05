# TacLight 第三方致谢与许可（v0.2 发版件）

本仓（`taclight/` 源码）许可：**GPL-3.0-or-later**（见各源码头）。

## 一、模式借鉴五家（只借思想与包序，未搬运代码文件）

以下五家的具体实现**没有复制进本仓**，v0.2 agent（TicketBridge＋Inv/Use/Act/
State/OpCatalog/TicketEnv）是读后自写。列此表即为署名义务履行。

1. **PrismarineJS/mineflayer** — MIT License, Copyright (c) 2015 Andrew Kelley
   （https://github.com/PrismarineJS/mineflayer）。
   借：`clickWindow` 事务确认＋超时、`moveSlotItem` 取→放→余量放回、
   `carriedIndex` echo 抑制、`placeBlock` 的 blockUpdate ack、
   `activateItem` 包序 → 对应 F1 背包确认环、`use` 包序。

2. **gnembon/fabric-carpet** — MIT License, Copyright (c) 2020 gnembon
   （https://github.com/gnembon/fabric-carpet）。
   借：`/player`＋`EntityPlayerActionPack` 的 USE/ATTACK/JUMP/DROP/SWAP 全序、
   3 tick 冷却、`setSlot` 背包同步、`EntityPlayerMPFake` 孵化；
   scarpet Inventories/Screen 回调遥测 → 对应 F1/F2/F3 与容器测试思路。

3. **Creators-of-Create/Create（代码部分）** — MIT License,
   Copyright The Create Team（assets 目录为 All Rights Reserved，本仓未碰）
   （https://github.com/Creators-of-Create/Create，mc1.20.1/dev 分支）。
   借：`DeployerHandler` 右键三档＋左键破坏管线全序、`DeployerFakePlayer`
   空连接与掉落回收 → 对应 F2/F3。

4. **TartaricAcid/TouhouLittleMaid（代码部分）** — MIT License,
   Copyright (c) 2019-2025 tartaric_acid（LICENSE-MIT；assets 为 CC，本仓未碰）
   （https://github.com/TartaricAcid/TouhouLittleMaid，1.20 分支）。
   借：`IMaidTask` 开关/切换钩子/LLM 摘要形状、`getAvailableInv` 背包统一视图、
   盾牌 start/stop 配对纪律、FakePlayer 拒绝契约 → 对应 F7 catalog、F1 背包视图、
   F2 配对、F12 对照表。

5. **microsoft/malmo** — MIT License（仓库已归档）
   （https://github.com/microsoft/malmo）。
   借：Mission XML 任务规格思想（world 生成＋机位＋观测＋奖励＋退出条件），
   我方 ticket JSON 即其精神后继；1.11 时代代码无搬运价值，未搬。

## 二、评估过、未借用（思想参考，不进仓）

- **Voyager / MineDojo** — MIT。试次版本管理思想（→我方 trial）。
- **OpenAI VPT** — MIT。`validate_env` 开机自检思想（→我方收票自检）。
- **Shalev-Lifshitz/STEVE-1** — **无许可声明**，代码权重一律未碰，
  仅参考 text→goal→policy 三段架构思想。
- **minerllabs/minerl** — **CC BY-NC-SA 4.0**（全文已核），NC＋SA 双杀，
  代码一律未碰，仅参考 BASALT 评审范式思想。

## 三、Forge 本体

- **MinecraftForge** — LGPL-2.1-only。我方以正常 mod 依赖方式链接调用
  （`FakePlayerFactory` 等 API），**未复制其源码进仓**
  （LGPL-2.1 §3 只允许转 GPLv2，与本仓 GPL-3.0 不兼容，故禁 vendor）。

## 四、libs/ 本地依赖 jar（不随源码分发，dev 用，见 libs/README.md）

- tacz-1.1.8-hotfix.jar — Timeless & Classics Zero（GPL-3.0 / CC BY-NC-ND 4.0）
- oculus-1.8.0.jar — Oculus（LGPL-3.0），仅 dev 运行时/compileOnly
- embeddium-0.3.31.jar — Embeddium（LGPL-3.0），仅 dev 运行时
- freecam-forge-1.2.1+1.20.jar — Freecam（MIT，以 Modrinth 页面为准）
- player-animation-lib-forge-1.0.2-rc1+1.20.jar — playerAnimator（MIT，以 Modrinth 页面为准）

## Baritone (idea-only reference, LGPL-3.0)

- **What**: input-injection timing (write `input.forwardImpulse/leftImpulse/jumping` in the player-tick START
  phase so it lands the same tick), declarative per-tick "clear then refill" of the input set, and
  `MovementOption`-style 8-way direction matching (world direction -> player-local impulses, walking decoupled
  from facing). Used as **concepts only; no Baritone code was copied, vendored or linked** (LGPL: linking is
  allowed, vendoring is not).
- **Snapshot read**: workspace `.research\baritone`, branch `mc1201`, HEAD
  `efed17c8804252f14bc5ba5b647d91863c5c7e7d` (notes: `docs\research\baritone-movement-notes.md`).
- **Verbatim check (2026-09-16)**: no copied lines; new sources contain no Baritone identifiers
  (`movementHelper`/`PathExecutor`/`CalculationContext`/`ActionCosts`/`BetterPos`) and no Baritone package names.

## Meteor Client (idea-only reference, GPL-3.0)

- **What**: two-phase tick discipline ("issue in Pre / collect in Post") and the N-tick "hold key" handler shape.
  Used as **concepts only; not one line was copied** (GPL-3.0 forbids copying code into a differently licensed project).
- **Snapshots read (anchors derived from git 2026-09-16)**: `.research\meteor-client` = `8819b2e1a5630839d41bf60ac8ce63efbe411deb` (branch `master`);
  `.research\meteor-1.20.1` = `6d76982a87ed175642d4f2ba2703179ab816cde3` (tag `v0.5.4`). (Lead's message quoted the master id as
  `...ac8cece63...`; the git-measured value above is authoritative -- 40 hex chars.)
  Companion research snapshot `.research\baritone` = `efed17c8804252f14bc5ba5b647d91863c5c7e7d` (branch `mc1201`).
- **Mapping caveat**: the 1.20.1 snapshot uses **Yarn** names (`ClientPlayerEntity`,
  `ClientPlayerInteractionManager`) while master uses official names; semantics were read from master, API shapes
  cross-checked against v0.5.4.
- **Verbatim check (2026-09-16)**: no copied lines; new sources contain no Meteor identifiers
  (`Rotations`/`PlayerUtils`/`CustomPlayerInput`/`InvUtils`/`Module`) and no `meteordevelopment` strings.