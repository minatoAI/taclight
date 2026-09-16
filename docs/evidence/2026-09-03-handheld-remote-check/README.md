> 本文所述 commit id 为 2026-09-17 历史重写前的旧 id；映射见 `docs/COMMIT-ID-REMAP-2026-09-17.md`

# 身体灯(手持)远程链判真伪(2026-09-03 晚,构建 8019aca,LAN 63057)

## 结论
链路通,无 bug。09-03 04:1x 轮"疑似断"为单轮历史现象,本轮实测关闭,不修代码。

## 判定(实机 !diag 双端,midnight duo 场景,两玩家相距约 5 格)
- 开灯命令:A 端 `/taclight light on Dev` → 服务端 `LIGHT-SYNC Dev handheld=true gun=true (cmd:on@target)` —— 写侧正常。
- A 端自见:DIAG flash=true,SSBO count=1→2 —— 自身手持分支正常。
- B 端所见:DIAG-REMOTE player=Dev flash=false→true,SSBO count=1→2,
  L0 pos=(1997.94,122.45,5.88) r=17.1 dir=(0.973,-0.074,0.219) —— 远程手持灯已展开上传。
- 写侧(服务端真源)→读侧(对端同步读)→收集展开(SSBO)三段全通。

## 说明
- 本轮 handheld=true 系判定用临时灯态,判后已 `/taclight light off Dev`
  (LIGHT-SYNC handheld=false)恢复,双端回到枪灯单变量状态。
- 历史疑似(s0019-s0025 共 4449 帧 B 端无 handheld 灯)未复现;若再遇,按"写侧→读侧→
  选择器→SSBO"四段抓 LIGHT-SYNC/DIAG-REMOTE/SSBO count 行定位。
