# 阶段二证据包 · M1 GGX specular + LabPBR 解析(2026-08-30 晨)

wall 场景(wall@front 机位)+ labpbr-rig 资源包(stone_bricks 砖面/砖缝、smooth_stone
地板、/fill 嵌入墙面的 iron_block 3×2 补丁)+ TACZ HK416D(默认枪包自带 LabPBR _s)。

| 文件 | 内容 | 判定 |
|---|---|---|
| dbg7_material.png | DBG7 材质审计:砖面橙色高 smoothness、砖缝暗色低、iron 补丁**白色(含 B=金属标志)**、地板均匀半抛光、手部回退值 | LabPBR 解码端到端正确 |
| dbg7_gun.png | DBG7 下 TACZ HK416D:**整枪白色 = 金属+高 smoothness**,手臂回退值 | TACZ 自带 _s 经 gbuffers_hand 正确解码 |
| final_on.png / final_off.png | 开/关灯 A/B:iron 补丁呈金属响应(diffuse 抑制+镜面光泽),砖墙漫反射 | imgdiff meanDiff=24.4,changed 23%(光斑+雨区) |
| final_on_b.png / final_on_d.png | 开灯静止帧对(3s 间隔,雨中) | acceptance side PASS cx=0.5044∈[0.49,0.58];闪烁指数 0.22% |
| final_off_c.png | 关灯雨本底 | imgdiff 静止帧差:关灯 4.9 / 开灯 6.6(雨主导,开灯增量≈1.7) |
| final_gun.png | 持枪 + 手电开:枪身金属光泽,铁块/砖墙材质对比 | 无过曝回归,纹理全可见 |
| manifest.sha256 | SHA-256 清单 | — |

性能:!bench avgFPS=375.1 onePctLow=39.4(3s 窗口含重载毛刺,minFPS 8.5 为启动抖动;
W7 ≥80 达标。注:options 修复后 maxFps/vsync 首次真实生效,数值体系与旧基线不可直接比)。

同期排障(坑24,详见 CHANGELOG):options.txt 因 BOM 吃掉首行 version:3465 → MC 跑
全量 datafix 抛 NumberFormatException → **历届会话 options 整体丢弃全默认**
(maxFps/vsync/pauseOnLostFocus/resourcePacks 从未生效;旧 bench 118.9 ≈ 默认 120 上限)。
session.ps1 已改无 BOM 写入 + version 标记守护。
