# 2026-08-30 · 对准修复 + 坑位16终解 实机证据

TDD 边界判定(§7)实机结果,全部来自真机截图 + 日志(会话三/四,quickPlay=test):

| 文件 | 判定 | 结果 |
|---|---|---|
| wall_on.png | W2 右手侧(side):光斑质心 cx=0.5614 ∈ [0.49,0.58] | **PASS** |
| wall_dbg4.png | W3 同轴(align,final↔colortex4):dx=0.025 dy=0.042 ≤ 0.05/0.08 | **PASS** |
| wall_dbg5.png | W4 bloom 居中(align,final↔bloom链):dx=0.013 dy=0.032 | **PASS** |
| bloom_after.png | 原 bug 复现位(bloom_inside)修复后:辉光对称环抱门洞 | 肉眼确认 |
| 性能 | !bench avgFPS=118.9 onePctLow=100.2(≥80/≥45) | **PASS** |
| 幂等 | 重复 scene bloom:blocks=15 → **blocks=0**(终态折叠修复) | **PASS** |
| 持久 | 三次重启 verify 恒等于最后写入态(15/0/15/0 全可解释) | **PASS** |

修复项:
1. right() 手性反(灯锚在左手 → 锥轴投影整体偏左)—— ClientSpotlightUploader.rightVector,
   契约 UploaderSemanticContract §4-5(12 项)。
2. 坑位 16 "30 格回滚" —— 平台↔小屋地板 fill 重叠,每次重建互相真实翻转 30 次;
   构建改 last-writer-wins 终态折叠(ScenePresets.expectedList + SceneExecutor 差量写),
   契约 ScenePlanContract verifySemantics(204 项)。
3. session.ps1 preflight 新增 pauseOnLostFocus=false(失焦自动暂停挡镜头,坑位 17)。

log 关键行:latest.log(会话三/四)SCENE/SCENEVERIFY/DIAG/BENCH;跨会话时序见报告。
