# 2026-08-30 · M5 附加:双光源同点叠加(interfere)亮度判定

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
