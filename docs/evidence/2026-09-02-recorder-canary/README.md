# 运动门控逐帧录制器实机 canary

## 结论

ObserverB 在真实 Forge 1.20.1 + Oculus 1.8.0 双客户端中移动，`!rec` 自动开窗并在静止后收窗。协议、截图集合和逐渲染帧采样全部通过严格分析器。

## 判定数字

- 目标帧率：60 fps（生产 header `targetFps=60`）。
- 实际采集：59.773 fps，138 个 C/P/S 样本，时长 2.292 s。
- 完整性：P=138，S=138，F=0，D=0，PNG=138；footer `reason=still`。
- 严格门禁：footer/token/PNG 集合精确匹配；C 时间与 render frame 严格递增；无 ≤5 ms 双采样。
- 远程静态前提：L 灯锚/方向和 R 远程显示位置全程 range=0。
- 离线/跨语言契约：`REC-ANALYZE-TEST PASS (56 checks)`、`FrameRecorderContract: ALL PASS (22 checks)`、`MotionCaptureContract: ALL PASS (20 checks)`、`TOOLS-SELFTEST PASS`、`AllContracts: ALL PASS`。

## 文件

- `frames.csv`：真实游戏逐帧协议。不复制 138 张 PNG；原始图仍在 gitignored `run-observer/mcap/run-20260902-012910-105-p18948/s0002/screenshots/`。
- `summary.json`：严格分析器可复算摘要。
- `contracts.txt`：本里程碑完整契约输出。
- `manifest.sha256`：本目录文件哈希。
