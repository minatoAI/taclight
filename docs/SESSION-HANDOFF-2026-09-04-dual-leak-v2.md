# 会话交接文档：双端漏光已判完 + 死亡冻结 + 存档机制纠错

接替 06:15 版 handoff（状态部分以本文件为准，任务目标不变）。
工作区 E:/dshHome/mc-mod-spotlight-attachment/wt-interop，分支 interop/core-extract，未提交、未 push。
主线 taclight/ 干净未动，对照 commit 4d6f09e。

## 1. 一句话状态

- 漏光 verdict CLOSED：锥光不穿墙（数字见第 2 节）。
- 灯关墙后还亮真凶：死客户端不吃 S2C，A 发射等级卡 10，宿主 heldLighting 直照，不是 SSBO 锥漏。
- 死亡调查按用户要求冻结。计数更正：5 次不是 4 次（06:41 蜘蛛 + 09:53/10:09/10:16/10:21 四张 You died 截图）。
- 存档机制纠错：删档重建等于新玩家、entity id 变等于新命，全错（详见第 3 节）。坑 95 需重写。
- 待办：发射 10 体感（缺活 A 视角）、半透明影子行为未验、新角色 rollout、commit（等批准）。

## 2. 漏光判定数字（CLOSED，不必重测）

- 场景：墙 12x3x1 stone_bricks x1994-2005 y121-123 z=0；A 墙前，B 墙后（B 头机一致，B 侧有效）。
- B 侧自灯 on-off：meanDiff 18.73，changed 24.5%，热图通红（地面积光池 + 右半墙）。
- A 侧看对面（B 灯 on-off）：meanDiff 0.29，changed 0.8% 等于噪声，零贡献，不漏。
- A 侧自灯 on-off：meanDiff 1.37 / 5.1%，热点在脚下（灯跟头不跟 cam，坑 94；A 相机 yaw180 头留出生朝向）。
- 死灯 pair run-2：0.64 / 1.2%，B 开菜单 voided，仅参考。
- 证据包 docs/evidence/2026-09-04-dual-leak（README + 7 帧 + heat_B_onoff + log-excerpt + manifest，自排除）。
- 坑 94：lamp 方向跟玩家头，不跟 taclight cam 相机；以后拍 A 侧先确认头机是否一致。

## 3. 存档机制纠错（必读，影响新角色方案）

- InteropA2 存档：run-interop/saves/interop/playerdata/776828ae-0aa6-3396-8378-55734ba8e23a.dat
- ObserverB 存档：同目录 7c813d22-f791-3723-9fa0-107d130bf6ca.dat；另有 stats/advancements 同名 json。
- 离线 UUID 现算验证通过：InteropA 对 a36161f8-43dc-3da9-87b7-95e2919077e5，
  InteropA2 对 776828ae-0aa6-3396-8378-55734ba8e23a，ObserverB 对 7c813d22 开头，与文件名逐字节一致。
- 错误承认：entity id 是运行时自增分配、每次进服都变，与玩家身份无关；
  此前以此论证每次都是新命、和名字无关，推理全废，特此收回。
- 删档真实语义：同名删 dat 重进等于同一 UUID 同一文件重建（清背包位置状态），只能叫重置。
  命令级证据链缺失（仅有 playerdata.bak-0636 备份加 dat_old 加新 dat mtime 11:00:38），取证缺陷认。
- 唯一真新玩家：InteropA（a361）到 InteropA2（7768），文件名都不一样，铁证。
  InteropA2 名下 09:53/10:09/10:16/10:21 四次同名操作全是重置。
- 连带推翻：换新 ID 也治不了死亡不成立，因为后几次根本没换 ID，一直是 776828ae。
- 真新角色标准：起新用户名（如 InteropA3），看到新 UUID 文件落盘才算；坑 95 必须按此重写。

## 4. 死亡事件（冻结，只记录）

- 5 次：06:41（zcode 遗留，蜘蛛咬死有案底）+ 09:53/10:09/10:16/10:21（四张 You died 直出截图）。
- level.dat 已 dump：Difficulty=0 和平、doMobSpawning=false 落盘，和平后照死，怪解释不了后两次。
- 未解：登录点恒为墙前机位之谜 + 开局生存空窗 + SIGKILL 回档链；按用户指令冻结。
- SIGKILL 教训：回档吃场景环境，已改 CloseMainWindow 优雅停（exit 0，和平持久化）。

## 5. 双端现状（新会话动手前先看）

- A 窗口（InteropA2，已死，停死亡界面）：它是集成服宿主，不能关；
  关等于世界关、B 掉线、LAN 断、中继无人消费。服务端活着，截图可看对面墙，保持开着不管它。
- B 窗口（ObserverB，活着，wall_back）：当前可用观察位。
- 灯态：A off（10:39:49 起）、B off（10:46:25 登录恢复 false）、gun=false 全程。
- LAN 25560；中继纪律：单文件单写不小于 1100ms，UTF-8 无 BOM，经 tools/interop-relay.ps1；
  多条一次发会静默失败，一次一条。
- 窗口纪律（坑 96）：双窗口 watch-only；B 开菜单 void 掉 2 个 off 帧、A 一暂停致 10:25 重启加 LAN 掉线。
- 找窗口：Get-Process java 加 MainWindowTitle（A 单人游戏，B Multiplayer）；任务列表工具被锁别用。
- 场景：wall 加猪（2000,121,正负2）加和平加创造加 NoAI；半透明修复 entities/hand 乘 color.a 在模板里，行为未闭环。

## 6. 文档提交状态

- CHANGELOG 顶部已加 09-04 双端漏光 verdict 条目，未提交。
- 坑位册第 6 节已追 94（cam 不等于灯向）、95（死亡加 S2C 加回档，需按第 3 节重写）、96（watch-only）。
- taclightContracts：Java 核心绿，尾部红是沙箱 EPERM（Node spawnSync 被拦），与代码无关。
- wt-interop 15M 加 untracked 未提交；用户批准前不 commit、不 push。

## 7. 新会话下一步

1. 新角色 rollout（需拍板，约 8 分钟）：杀 A（含服务器）到起 InteropA3 到 B 重连到 kit 机位 LAN 全套；
   验收标准是新 UUID 文件落盘；覆盖活 A 视角加发射 10 体感加半透明影子。
2. 发射 10 体感：必须等活 A，当前 A 死视角无效。
3. 半透明影子：模板乘 color.a 已在，行为（透层是否消、影子观感开销）未验，留给活 A 加 B 双看。
4. 坑 95 重写加 CHANGELOG 加 commit：新角色结论出来后一起，用户批准才提交。
