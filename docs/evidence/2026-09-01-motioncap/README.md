# 2026-09-01 运动门控采集开关(mcap)+ 灯态持久化修复 · 证据包

## 1. 背景与需求

用户指令:"弄一个采集开关的指令,运行该指令后,检测到角色朝向或位置坐标改变就开始
连续截图,同时也记录相关的重要信息,角色朝向或位置不变就停止截图。"

用途:边缘闪烁根因定位需要**真实鼠标运动**下的逐帧信号 + 真机连拍。固定窗口
(`!looktrace 7200`)要人盯秒表、易扫出窗外;门控开关布防一次,之后动作自动采集、
静止自动收窗,用户实测零操作负担。

## 2. 实现(契约先行,红→绿)

- `channel/MotionCapture.java`(纯 JVM 状态机,35 项契约):参考采样间隔 refMs 内
  位姿差超阈(yaw/pitch/pos)→ 开会话;运动持续刷新截止线;静止 stillMs 自动收窗;
  maxShots 翻转新会话(会话号单调);目标切换收窗重参考;±180° 环绕安全。
- `channel/LookTrace.java` 增 `on` 门控模式(无窗口上限)+ `posO/posC` 位置字段
  (行尾追加,lookreplay 旧正则尾部不锚定,向后兼容;22 项契约)。
- `client/ClientEvents.onRenderTick`(RenderTick END):帧末主帧缓冲已含最终画面,
  进程内 `Screenshot.grab` 直读渲染目标 —— **免前台窗口**(坑34 F2 postkey 链路
  整个旁路);ioPool 编码积压 >3 跳帧防堆积;60fps 节流。
- `client/DebugCommandRelay`:`!mcap [on|off|yaw=|pitch=|pos=|ref=|still=|fps=|max=]`。
- 契约:`AllContracts: ALL PASS`(370 项,含 MotionCapture 35 + LookTrace 22)。

默认参数:yaw=0.30° pitch=0.30° pos=0.02m,ref=100ms,still=800ms,fps=62,max=1200/会话。
截图落盘 `<gameDir>/mcap/s%04d/screenshots/`。

## 3. 实机功能验证(02:59,B 端观察者看 Dev)

场景:B(ObserverB)@(2002.5,121,6.5) 看白墙;A(Dev)@(2000.5,121,9);
A 中继对 Dev 发 8 次 `/tp` 偏航步进(175↔185),B 端 `!mcap on` 布防。

判定数字(run-observer/logs/2026-09-01-1.log.gz,证据行见 mcap-sessions-b.log):

| 项 | 数字 |
|---|---|
| 会话 | 4 个(s0001-s0004;8 步中间隔 <still 的步进并窗 = 持续运动合并,符合设计) |
| 会话样例 | s0003:OPEN 02:59:34.082 → CLOSE 34.986,53 张,0.9s,why=静止 |
| 截图节流 | 53 张/0.9s ≈ 59fps(目标 62);135 张/2.3s ≈ 59fps |
| 信号行 | LOOKTRACE 473 行(逐帧,含 hO/hC/bO/bC/pO/pC/base/om/ext/posO/posC) |
| 截图落盘 | run-observer/mcap/s000{1-4}/screenshots/ 共 346 张 PNG(854×480) |
| 静止自停 | 4/4 会话 why=静止 自动收窗 |
| 像素证据 | imgdiff 会话首尾帧:bbox=[201,80,368,449],maxDiff=46(光束/光斑摆动区);相邻 32ms 帧(静止相)changed=0 —— 与门控语义一致 |
| 门控免打扰 | B 端全程无前台焦点(Dev 端在被操作),截图仍正常 |

代表帧:shots/s0001-first.png、s0004-first.png、s0004-last.png(真机截图,HUD 可见)。

## 4. 灯态持久化修复(用户 bug:退出灯开、重进灯灭)

- 根因:灯开关真源挂 SynchedEntityData(实体实例字段),重进游戏服务端重建玩家实体
  → 回落原版默认 false,无任何持久化层。
- 修复:`TacLightNetwork.serverApply`(唯一服务端写口,C2S 与 /taclight 命令都经它)
  同时落玩家 Forge 持久化 NBT 子树(Player.PERSISTED_NBT_TAG,跨 relog 且跨死亡克隆);
  `PlayerLoggedInEvent` 重放 serverApply → 实体数据 + 既有 SyncLightS2C 回包一并恢复
  (本人客户端本地状态自动跟随,零新增网络包)。
- 契约:LightStatePersistenceContract 11 项(存储位置/回环/不污染他模组键/裸键不入读)。

实机验证:见本文 §6(重启周期后 B 端重登测试)。

## 5. 已知边界

- RenderTick END 采帧与 F2 同源(主帧缓冲),含 HUD;Iris 最终画面已 blit 进主缓冲。
- 单会话 1200 张上限(60fps ≈ 20s)自动翻转新会话,长扫磁盘有界;历史会话号不复用。
- 会话号从 1 计,进程重启后从 s0001 重新计(目录按 gameDir 隔离,不会冲突覆盖)。

## 6. 重启周期后补录(03:27,终版 jar)

全量重启(A session.ps1 + B runClientObserver)后,持久化重登验证(persistence-login-restore.log):

- 03:15:21/23 `/taclight light on` 双灯打开(新 jar,serverApply 落 NBT)→ 03:15:35 杀 B
  → 03:27:10 B 重登,**期间零灯命令**:服务端自动 `LIGHT-SYNC ObserverB handheld=true gun=false (login-restore)`;
  03:27:11 B 本地 `LIGHT-SYNC-ACK handheld=true`(S2C 回包恢复本地渲染状态)。
  = 用户报障场景(退出灯开/重进灯灭)修复实机成立。
- mcap 重布防端到端复确认:2 次 /tp 步进 → s0001(51 张/0.9s)+ s0002(52 张/0.9s),
  103 张新 PNG 落盘 run-observer/mcap/,why=静止自动收窗。
- 终版契约:AllContracts: ALL PASS(370 项,contracts-final.txt)。
- 首轮功能验证的 346 张截图归档于 run-observer/mcap-run1/(README §3 数字即出自该批)。
