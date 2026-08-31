# 2026-09-01 snap+pred 实施与同输入 A/B 实机对比(用户批准后执行)

## 1. 实施(用户批准"尝试一下这个方法")

- 新增 `RemoteBaseSnap`(channel 包,纯 JVM):基角 = **延迟一段快照插值** —— C_k 到达后
  在 [t_k, t_k+50ms] 播放 C_{k-1}→C_k,只依赖自用 C 历史、不碰 O/C 对 → 构造上位置连续,
  对 O/C 边界时序分歧(源1)免疫;预测臂 = ω̂ EMA(τv=0.15s)→ ext EMA(τout=0.08s)
  两级,保留方案A 超前(消源2 纹波、保滞后补偿)。传送级双钳制(±20°/tick、±12°)与
  2s 陈旧重置沿用方案A 语义。离线分析器同式 = tools/lookreplay.js snap/snap+pred 臂。
- 旋钮:`!bsnap <on|off>`(默认 on;off 退回 rotLerp+方案A 旧管线);`!extrap` 仍控超前
  tick 数(共享旋钮)。接线:ClientSpotlightUploader.collectRemoteLights 分支 +
  lookTraceTick base/ext 列读实际使用值(peek 只读)。
- TDD:RemoteBaseSnapContract **25/25**(首见直取/静止像素恒等/延迟段播放/±180 最短弧/
  pitch 直差/双实体隔离/陈旧重置/稳态 ext→ω×ticks/急停单调回落/传送钳制/off-on 清状态/
  peek 只读幂等);AllContracts **395 项 ALL PASS**。

## 2. 实机同输入 A/B(2026-09-01 04:27,LAN 双端,墙 6.5m)

方法:双端重启(新代码)→ 场景三件套(创造/清怪/关生成)+ 归位 → Dev 灯开、B 布防
!mcap → 完全相同的 /tp 步进扫视序列(13 步 ×15°、0.9s 间隔)各跑一轮:
round1 = `!bsnap off`(旧管线),round2 = `!bsnap on`(新管线),B 端逐帧 LOOKTRACE
记录实际使用的 base+ext。

| 指标(光斑显示角) | 旧管线(11 会话) | 新管线(剔除伪影后 10 会话) | 改善 |
|---|---|---|---|
| 帧间最大跳变 jumpMax 平均 | 5.58° | **1.82°** | **-67%** |
| 帧间最大跳变 jumpMax 最差 | 5.80° | **1.88°** | **-68%** |
| 静止尾段最大误差 tailMax 平均 | 0.68° | **0.24°** | -65% |
| 静止尾段最大误差 tailMax 最差 | 4.44° | **0.75°** | **-83%** |
| 光斑-真值误差峰峰 errP2P 平均 | 6.15° | 5.40° | -12%(步进输入的滞后量,预期内) |

- 逐会话数据见 absnap-analysis-output.txt;原始逐帧行 1822 行见 absnap-live-rows.txt。
- **伪影说明**:round2 首会话(s0012,t=16060)jump=20.67°/err=64° 系测试序列自身跨
  ±180 回绕(round1 止于 yaw 383≈23°,round2 自 203 起步 → 表观 -60°/tick 突跳)——
  管线按传送级设计钳制并在 0.5s 内收敛(tail=0.65°),属正确行为,统计中剔除。
- 活体行为核对(原始行可复算):步进到达瞬间 display=旧值(延迟段)✅;段内 50ms 线性
  ease ✅;静止段 base≡hC 精确恒等 ✅(如 s0018 尾段 base=-37.969=hC 恒定 0.3s+)。
- 像素佐证(相位难对齐,作旁证不作主证):静止窗相邻帧 imgdiff,旧管线光晕区 bbox
  (old-s0008 对:[256,98,488,461],maxDiff=45)vs 新管线仅底部准星小邻域
  (new-s0018 对:[256,447,269,464],maxDiff=14)。

## 3. 结论与验收状态

1. 同输入下新管线把单帧跳变砍 2/3、静止残差砍 4/5,且滞后(errP2P)未回退 —— 实现与
   离线消融预测(纹波 -52~69%)一致。
2. 静止像素恒等、±180 环绕、传送钳制在实机全部按契约工作。
3. **待用户真鼠标体感验收**(A/B 均在运行、场景就位、snap+pred 已默认生效):
   若仍嫌晃,`!bsnap off` 一键退回;若嫌滞后,`!extrap` 上调(1.5/1.75)。

## 文件

- `absnap-analysis.js` / `absnap-analysis-output.txt` — 对比分析脚本与输出(可复算)
- `absnap-live-rows.txt` — 实机逐帧 LOOKTRACE 原始行(1822 行)
- `old-s0008-f37/f6.png`、`new-s0018-f12/f23.png` — B 端真机截图样本(imgdiff 对)
- 临时采集(~1200 张 PNG)已按用户指示清理
