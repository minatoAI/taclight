> 本文所述 commit id 为 2026-09-17 历史重写前的旧 id；映射见 `docs/COMMIT-ID-REMAP-2026-09-17.md`

# 进展与瓶颈报告（2026-09-04 12:20 快照，供分析）

> 分支 interop/core-extract；提交 a57dae3（未 push）+ 未提交一批（见 1 节）。
> 双端已由用户手动关闭（B 12:19:21 / A 12:19:24，优雅退出，世界全存盘，无崩溃无丢失）。
> 重建约 8 分钟（配方见 SESSION-HANDOFF-2026-09-04-dual-leak(-v2).md）。

## 1. 当前状态

- 代码：a57dae3（发射 10 三项绿）之后新增未提交：back 关界面命令
 （DebugCommandRelay）、DDA 掠边软化带 FUZZ（TemplateLibrary + InlineCoreContract
 新断言 37 项）、证据包 docs/evidence/2026-09-04-emission10-entity/（已随 a57dae3 提交）
 与新建一半的 docs/evidence/2026-09-04-shadow-fuzz/（四个前后对照帧已落盘；
 README/manifest/CHANGELOG/坑位册未写）。
- 契约：Java 核心全绿（TemplateLibrary 35 / InlineCore 37 / ScenePlan 207）；
 唯一红 = FrameRecorder 跨语言项，本 DSH 沙箱 EPERM 拦 Node 套 Node，环境性、
 与代码无关、绕不过（平台 documented 边界，不重试）。
- 实机：Complementary 注入 +7869x3（FUZZ 版）；A=InteropA3（wall_front，
 灯开 handheld+gun）；B=ObserverB（末位为用户走位后未知点，不可信）。
- 结论状态：漏光 CLOSED、半透明 CLOSED、发射 10 冻结；阴影破碎已修待用户目验。

## 2. 本轮闭环（根因 + 证据）

### 2.1 阴影破碎 = 前向 DDA 掠边假阴影（定案，已修）

- 现象：A 视角墙面左半块黑色锯齿咬痕 + 阶梯齿（用户报阴影破碎、异常位置形状）。
- 排除链：猪影（单猪只够挡约 1 格，咬痕半面墙）/ 月影（双端对不上）/ 灯位偏移
 （手电居中灯 11:34 帧放大仍有咬痕）——11:34 手电帧 zoom 为铁证。
- 机制：float 累积排序翻转（掠射末步进错邻格，墙体素即判 0）+ 零 FUZZ 硬归零。
 前向模板 tie 多轴逻辑虽在，但 eps 1e-5 过松且无软化带吸收残差。
- 修法（照搬主线已验证配方）：tie eps 1e-5 改 1e-6 + 穿透软化带 0.35
 （穿透小于 0.35 按比例放行，大于等于 0.35 仍回 0；字面量内联，前向零预处理指令红线）。
- 验证（12:14 帧 post_on）：黑齿消失、只剩柔和左渐变；枪灯严格前后对照
 pre_off_gunonly(11:56:43) 对 post_off_gunonly(12:16:09)：meanDiff 1.49、
 changed 3.1%、maxDiff 227 集中原咬痕区；热点 mean 84.4 无回归、ge250=0 无死白。
- TDD：先加红断言（penLen + 除 0.35 + T 乘 1.0-f + eps 1e-6）跑红，再实现跑绿。

### 2.2 back 关界面命令（已实战验证）

- 起因：用户指正测试应该是完全程序化的，此前菜单挡帧只能手点（违坑 76/坑 96）。
- 实现：DebugCommandRelay 新增 back 命令，等于 setScreen(null)（与回到游戏同入口）。
- 验证：B 真实菜单事故中 RELAY back 日志确认，菜单消失、游戏画面回来。

### 2.3 B 被顶飞之谜（已定案，教训入库）

- 11:38 为拍灯照玩家实体，把 A 传送到 B 脸上（wall_back 同坐标），玩家碰撞
 把 B 顶到 16 格外（1992.9,121,7.05）；之后 B 的 cam goto 触发服务端
 moved too quickly 被弹回（位置回旧点、朝向留新值），即 11:59 对天看月废帧的来历。
- 重发一次归位。规则：同机位重叠操作禁用，人与人之间保持距离。

## 3. 问题瓶颈（按卡脖子程度）

1. 人机共治冲突（最卡）：自动化假设机位固定、窗口只看不碰；用户体感时会开菜单、
 走动 B（本轮 3 次菜单 + B 被开走）。后果：废帧、机位假设崩、甄别成本高。
 对策二选一：分时（取证时段不碰、体感时段不抓），或 B 机位锁（丑）。
 back 命令只治菜单不治走位。现状：B 机位在用户手里，下次取证前必须重摆 + 目检。
2. B 是 LAN 客人，传送不可靠：宿主 A 的 cam goto 次次成；B 大跳可被
 moved too quickly 弹回（静止重发一般能成）。B 每次传送后必须 shot 目检机位，
 不可信日志 cam 箭头行。另有 taclight cam here 可程序化查双方精确位姿（尚未启用）。
3. 本轮 agent 两次违规（已认，待入坑位册）：
  (1) 按窗口标题杀 B 误杀 A（A 开 LAN 后标题变多人游戏局域网），SIGKILL 吃
 4 分钟回档（被 autosave 兜住，只丢灯开关几次中继）；铁律：杀进程只看命令行
 （B 用 runClientObserver 匹配，宿主只许 CloseMainWindow 优雅停）；
  (2) 5 条中继写进一个循环连发互相覆盖只剩最后一条，还连带 B 在 LAN 未开时启动连拒；
 铁律：中继一次一调用、逐条验 log。
  (3) 关联：B 启动失败窗体用 pid 定点清除 OK；Get-CimInstance 在本沙箱拒绝访问，
 别依赖它。
4. 小谜团（不阻塞）：A 视角墙顶东沿两枚 3 像素级亮斑（粉白块 + 绿红小旗）+
 B 脚边猪排掉落物。最可能 = 刷怪点埋进方块闷死猪的掉落 + 残留黑墙被照亮的顶角。
 场景卫生问题，非渲染器 bug；修法 = 挪猪刷怪点出光束（1 行 + 合约），待拍板。
5. 环境性天花板：DSH 沙箱 EPERM（契约尾红、Node 套 Node 全灭）永久，
 AllContracts ALL PASS 全字串在此环境拿不到，对外汇报须常备注。

## 4. 待拍板（4 项）

1. 阴影修复目验：12:14 帧（post_on）黑齿已消、只剩柔和左渐变是否为预期效果？
 （实例已关，要看需重开摆回，约 8 分钟。）
2. 提交：shadow-FUZZ 批（含 back 命令）是否 commit（不 push）？证据包收尾
 （README/manifest/CHANGELOG/坑位册 97/98）落完一起交还是一件件来？
3. 小谜团：亮斑猪排顺手修（挪刷怪点 1 行 + 合约）还是记坑不管？
4. B 机位：下次取证前重摆 wall_back（体感时不动它）。

## 5. 关键帧与数字索引

- docs/evidence/2026-09-04-emission10-entity/：发射 10（A on-off 15.07/41.5%，
 热点 85.1/p90 180.5/ge250=0）+ 实体不透明 + 漏光复核 + bench 双 59.8。
- docs/evidence/2026-09-04-shadow-fuzz/（收尾中）：pre_on 11:55:53 /
 pre_off_gunonly 11:56:43 / post_on 12:14:40 / post_off_gunonly 12:16:09；
 枪灯严格对照 meanDiff 1.49 / changed 3.1% / maxDiff 227。
- 新角色：InteropA3 离线 UUID e4aea662 开头文件逐字节验算一致（v2 第 3 节纠错的独立佐证）。
