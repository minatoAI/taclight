# 证据包 · M5 双客户端 LAN 联测通过(2026-08-30 晚)

M5 多人灯同步的首次真实双端验证。**结论:通过** —— 观察者客户端实时渲染主机端
手电的表面光斑与光束,开关灯在观察者端实时跟随。

## 0. 登录阻断根因(此前 5 次联测尝试全灭的原因)

- **坑32(主因,字节码级确认)**:原版 1.20.1 `IntegratedServer.initServer()`
  硬编码 `setUsesAuthentication(true)` —— 局域网加入者必须通过 Mojang 会话验证;
  dev 第二客户端无有效会话,连接约 2 秒内被 "Failed to log in: Invalid session"
  踢掉(两侧日志均无异常,仅客户端屏幕可见)。单人不受影响(内存连接跳过验证),
  这就是"单人一切正常、联机必挂"完全无日志的原因。
  修复 = `DevLanAuthHook`:仅当 `-Dtaclight.dev.disableLanAuth=true`(仅 dev run
  配置携带)时关闭集成服会话验证;生产环境不带属性,行为与原版一致。
- **坑31(附带修复)**:`--quickPlayMultiplayer` 直连不做 status ping,客户端拿不到
  FML 标志会把 Forge 服误判为 vanilla(HandshakeHandler "vanilla impl")。通道谓词
  改 `NetworkRegistry.acceptMissingOr(PROTOCOL)`,同时天然支持"仅一侧装 mod"的降级。
- 工具链:mp-session 中继 BOM(坑29)/端口正则、drive.ps1 `-ProcId` 选窗 +
  前台锁 ALT 解锁(坑30)、FG6 clientObserver 需 `parents` 继承主类。

## 1. 拓扑与流程(均可复现)

A = `session.ps1`(dev 客户端,玩家名 Dev,QuickPlay 进 test 存档)→ 中继
`/publish`(端口见日志 "Started serving on N")→ B = `runClientObserver
-PtaclightJoin=127.0.0.1:N -PtaclightUser=ObserverB`(独立 run-observer 目录)。

## 2. 量化验证(2026-08-30 19:28-19:34)

| 步骤 | 证据 | 结果 |
|---|---|---|
| 双端在线 | A 中继 `/list` | "共有2名玩家在线"(Dev + ObserverB) |
| B 视角布位 | B `!diag` | cam=(2000.5,125.6,16.5) yRot=-180 xRot=20(旁观,面向 A 与墙) |
| A 开灯 → B | B `!diag` | A 灯开:B `ssbo count=2`(自身+远程);B 自灯关:count=**1(纯远程灯)** |
| A 关灯 → B | B `!diag` | count=**0**(远程灯实时消失);A 重新开灯:count=**1**(实时恢复) |
| 服务端同步 | A 日志 | `LIGHT-SYNC Dev handheld=true gun=false (cmd)` + 客户端 `LIGHT-SYNC-ACK` |
| 帧差(imgdiff) | b_on vs b_off | changed=10823px,bbox=[6,31,722,273](光束/墙面区域),maxDiff=220 |

## 3. 视觉证据

| 文件 | 内容 |
|---|---|
| b_on.png | B(观察者)视角:A 的光束打在墙上 + 地面光池清晰可见,主角模型背光 |
| b_off.png | 同机位,`/taclight light off` 后:墙全黑,构图/月夜完全一致 |

## 4. 遗留(如实)

- 生产级验收(构建 jar + 正式启动器双实例)未做;dev 结论覆盖同步逻辑本身,
  生产部署面(安装、profile)另行小步。
- interfere 场景(多灯交叠)、TP(第三人称)多灯细则未在本轮展开——本次已含
  TP 观察视角(B 旁观即第三人称锚定路径)。
- observer 实例 oculus.properties 需位于 `run-observer/config/`(首次启动会自建
  空文件覆盖预置位置的语义);taclightMpSetup 预置路径待下次修正。
