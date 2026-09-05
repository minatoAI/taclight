# 贴墙穿墙漏光修复证据包（2026-09-05）

## 用户原报

玩家持枪面对墙壁贴墙时，枪上的灯光会穿过墙照到对面，破坏沉浸感。

## 根因（实测钉死）

贴墙站位诊断行：`L0 pos=(2000.32,122.52,0.20)` —— 灯头 z=0.20 落在墙体素格
（墙 z=0..1）内部。体素 DDA 按惯例豁免起点格，于是这堵 1 格墙对该灯透明。
`A-wall-front-before.png`（02:32 旧代码）：枪灯开着，面前墙面一片漆黑 ——
光没打在墙正面，直接穿过去了。

## 修复

Java 上传 SSBO 前灯头出实心钳制（`VoxelField.clampOutOfSolid` + 上传器接入）：
灯落实心格 → 沿 −dir 退到首个非实心格（步进 0.05m，上限 2m，无出路原样
fail-safe）。三条消费路径（表面/体积/前向注入）共用 SSBO 灯位，一处修全修好，
GLSL 零改动。

## 实机判定数字

- 灯位：同贴墙站位，修复前 `L0 z=0.20`（墙内）→ 修复后 `L0 z=-0.05`（墙前空气）。
- 墙正面（`A-wall-front-fixed.png`，bbox 250,80,600,480）：mean 72.7 / p90 148.2 /
  ge128 28018 —— 锥形光池正常照亮墙面。
- 墙后地面（`B-behind-wall-fixed.png`，bbox 300,280,540,470）：mean 60.8 / ge200 208。
- 对照（Dev 传走 30 米，同机位 `B-behind-wall-control.png`）：mean 60.7 / ge200 208，
  逐位一致 —— 208 亮像素是环境本底（月光），非枪灯漏光。**穿墙漏光消除。**

## 契约

`AllContracts: ALL PASS`（VoxelField 21 项含钳制 7 项 / UploaderSemantic 38 项含钳制 6 项）。

## 文件

- `A-wall-front-before.png`：修复前 A 端，枪灯开但墙面全黑（症状）。
- `A-wall-front-fixed.png`：修复后 A 端退后视角，墙面锥形光池。
- `B-behind-wall-fixed.png`：修复后观察端墙后俯视，地面无漏光。
- `B-behind-wall-control.png`：对照（灯移走），数字逐位一致。
- `manifest.sha256`：完整性。
