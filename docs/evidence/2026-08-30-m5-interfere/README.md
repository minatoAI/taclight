# 2026-08-30 · M5 附加:双光源同点叠加(interfere)亮度判定 + 用户复核后的两项修复

> 用户实机复核(08-30 深夜)反馈两项:①双灯同点仍过亮 → **肩部压缩**(composite.fsh);
> ②远程移动光再次闪烁 → **坑36 方向插值 + 坑37 bloom 时域平滑**。均在本目录留证。

## 问题
两个玩家的手电光锥同时照射墙上同一点(SSBO lightCount=2),叠加亮度是否"过高"(失控过曝)?

## 环境
- M5 LAN 拓扑复用:A=Dev(主机,run/,PID 31596)+ B=ObserverB(LAN 客户端,run-observer/,PID 65604)。
- `/taclight scene wall` 石砖墙(z=0,x1994..2005,y121..123),`/kill` 清蜘蛛;`/weather clear` 消雨丝。
- 摆位(显式 yaw/pitch,注意坑33:/tp facing 以脚底为锚,眼睛高 1.62m 会偏):
  - A (Dev) 脚 (1997.5,121,6.5) yaw=-155.57 pitch=2.08
  - B (ObserverB) 脚 (2002.5,121,6.5) yaw=155.57 pitch=2.08
  - 共同瞄准墙点 (2000, 122.4, 1):diag 实测 dir=(-0.413,-0.036,-0.910) 命中该点。
- 控制:A 中继 `run/taclight-cmds.txt` 一点控制双灯(`/taclight light on|off [player]`);
  截图走 B 的 F2 postkey 通道(坑34:双窗重叠 + DPI 缩放使屏幕抓取不可靠)。
- 曝光锁 TACLIGHT_EXPOSURE_LOCK=1.0(双端部署包实测确认)→ 全程绝对亮度,无眼适应干扰。
- DBG8 增益临时 ×6→×1(暴露重叠核心区,测后已还原,W3 标定不受影响)。

## 三态(机位固定 B 第一人称;状态序列经服务器 LIGHT-SYNC 日志逐条核对)
| 态 | 文件(终帧) | 文件(DBG8 g1 线性域) |
|---|---|---|
| B 单开 | s1_b_only.png | e1_dbg8g1_b_only.png |
| 双开 | s2_both.png | e2_dbg8g1_both.png |
| A 单开 | s3_a_only.png | e3_dbg8g1_a_only.png |
(×6 增益版 d1/d2/d3_dbg8_*.png 为首轮,核心全削顶,仅留档)

## 终帧量化(区域 x[220,770] y[90,400] 扣教程框;F2 帧 854x480)
| 指标 | B 单开 | 双开 | A 单开 |
|---|---|---|---|
| mean luma | 112.7 | 146.1 | 117.3 |
| p90 | 234 | 255 | 219.8 |
| 饱和像素 ≥250 | 8329 (5.5%) | **35605 (23.5%)** | 4355 (2.9%) |
imgdiff(S1↔S2):changed=107504/409920 maxDiff=201。

## 线性域(DBG8 ×1,γ0.45 反解;真重叠像素=两灯各自贡献>0.004,共 87,782 px)
- l2/(l1+l3):mean 0.867,**p50 0.957**,p05 0.493,p95 1.220 —— 叠加总体近似物理相加。
- 局部簇超和 +10~30%(空间聚簇,中心带 y230-300);核心处 dual ≈ 1.86×强侧单开,
  高于源码 knee 模型预测(f(x)=x/(1+2x),2×辐射 → 1.40×)。

## 判定
**不会失控性过曝,行为符合"两支真手电同照一点"的直觉:**
1. 双开比单开亮;瞄准点核心饱和面积 5.5%→23.5%(局部白斑扩大),但中低亮度区砖缝纹理仍可辨;
2. 无全局泛白:曝光锁 1.0 + knee 压缩(渐近上限)+ ACES,终帧各处有界;
3. 线性域中位 0.957 ≈ 加和,压缩器使高辐射处次线性 —— 防过曝设计在起作用。

## 开放点(不阻塞,后续可选)
- 线性域存在超和簇(+10~30%)与核心 1.86×(>knee 模型 1.40×):composite.fsh 主循环
  数学上 knee(凹)不可超和,现模型必有缺项(bloom 不在 colortex0、光束在 colortex4、
  SSBO count=2 已由 diag 确认、远程灯 ×0.9 已排除)。可选单客户端 duo 场景
  (手电+枪灯)复现以隔离网络变量 —— 立项条件:实际画面出现不可接受的过亮。

## 坑位新增(详见 调试环境搭建计划.md §7)
- 坑33 `/tp <p> <pos> facing <pos>` 以**脚底**为锚计算朝向,玩家眼高 1.62m → 光束上仰
  掠过目标;多玩家精确瞄准用显式 `/tp <p> x y z yaw pitch`。
- 坑34 双 MC 窗重叠 + Windows DPI(150%)下 drive.ps1 屏幕抓取(CopyFromScreen)会抓错
  窗/错尺寸 → 双客户端取证一律走 F2 postkey(MC 自截帧,焦点无关,零几何误差)。
- 坑35 多态对比分析:常量像素(提示框/暗天空/UI)三态相同,l1≈l3≈ε 天然同时满足
  "ratioMax=1"与"ratioSum=0.5",必须设"两灯贡献均 >阈值"的真重叠过滤,且逐区域
  看空间分布,禁止全图聚合判定。

## 复现
```
printf '/taclight scene wall\n/gamemode survival ObserverB\n/tp ObserverB 2002.5 121 6.5 155.57 2.08\n/tp Dev 1997.5 121 6.5 -155.57 2.08\n/taclight light off Dev\n/taclight light off ObserverB\n' > run/taclight-cmds.txt
# 三态切换:/taclight light on ObserverB → on Dev → off ObserverB;每态 B 端 postkey F2
node tools/lumastats.js <png...> --bbox 220,90,770,400 --exclude 525,185,855,265
```

---

# 2026-08-30 深夜追加:用户复核后的两项修复(P1 肩部 / P2 闪烁)

## P1 双灯同点仍过亮 → 多源感知肩部(shoulder)
**理论定位**:渲染方程 L_o=Σf_r·L_i·(n·ω_i) 对多光源线性叠加(超叠加原理,radiance 求和不动);
"两灯不该明显更亮"是感知/显示域需求(Stevens 幂律 ~0.33),工程落点 = filmic 肩部压缩。
**实现**(composite.fsh + taclight_common.glsl):
`taclight_shoulder3(x,T,H)`:x≤T 逐像素恒等(单灯不变);x>T 的超出量 tanh 收敛,
上界 (1+q)·T。T=0.55(锚定单灯名义核心,DBG8 实测 6m 石砖墙),q=0.15。
替换原 `soft_knee3` 调用点(旧 knee 全域压缩,远场也压;肩部版只压 T 以上)。
**实测**(n1/n2/n3,同机位三态):
| 指标(区域 220,90-770,400) | 旧单开 s1 | 新单开 n1 | 旧双开 s2 | 新双开 n2 |
|---|---|---|---|---|
| 显示饱和 ≥250 px | 8329 | **387** | 35605 | **481** |
| p99 | 255 | 247.4 | 255 | 248.1 |
- 双开/单开峰值 248.1 vs 247.4(旧 255 vs 255 但双开白斑 4.3×);区域均值比 1.23×;
  砖缝纹理全程可读(目检 n1/n2)。核心白斑消失 = "没有明显增强"达成。

## P2 远程移动光闪烁 → 坑36 + 坑37
**复现的三个教训(先踩了)**:①新实例没 `/weather clear` → 雨丝污染统计(残差虚高 5×);
②移动路径朝向错误 → 光斑根本不在 B 视野内,全部指标为空采样伪结果(坑27 重犯:
**连拍必须先目检帧再相信数字**);③3.8fps 连拍混叠 20-60Hz 现象,只能定位"哪一层
干净/脏",不能量化用户实感。
**根因定位(排除法+代码审查)**:SSO 掩码视图(无正确几何,结论弱)与表面光视图
均未见异常;**代码审查锁定两处真实缺陷**:
1. **坑36(ClientSpotlightUploader)**:远程灯方向用 `getLookAngle()` = tick 瞬时值
   (20Hz 台阶),而实体模型渲染走 O→current 的 partialTick 角度插值 → A 一转视角,
   B 眼里光斑 20Hz 跳动、且与模型旋转脱 sync。修复 = 与渲染同源插值:
   `rotLerp(pt, yHeadRotO, yHeadRot)` + `lerp(pt, xRotO, xRot)` → directionFromRotation。
2. **坑37(composite3)**:移动光斑扫过 bloom 软阈值带,提取权重被 smoothstep 非线性
   放大(F6 同族,成员=表面光斑;F6 只修了 beam 不进 bloom)。修复 = 大半径辉光
   (bloom2,视觉主导项)对上一帧 EMA(α=0.6),历史存 colortex6(全包唯一未用槽,
   4-7 号缓冲 Iris 默认不清帧,与 colortex7 曝光历史同机制)。辉光低频,拖迹 ~2 帧。
**回归**(带两修复,直走 1.4s 连拍 burst-final-move2):残差 RMS 0.03(静止基线 0.02 同级),
显著翻转 0,光斑内 σ 0.31;f2_006 目检:光斑贴 A 瞄准点扫过、无跳动。
**附注**:首次重启后的一轮移动连拍曾出现"方向瞬时指反"(B 侧 dir=+z 而 A 朝北,
帧全黑),两轮复现均未再出现;若用户实测遇"光斑方向冻结",即此瞬态,需加逐 tick
日志定位。bm 危险操作无。

## 本轮工具改动
- capture-burst.ps1:`-ProcId` 按进程选窗 + 自动最小化其他 MC 窗(坑34 的连拍版)。
- burst 目录 = move-flicker.js 输入(PNG 落盘但扩展名 .bmp,GDI+ MemoryBmp 之故)。
