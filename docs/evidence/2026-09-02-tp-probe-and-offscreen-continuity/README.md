# 2026-09-02 · TP 打桩判定工具链 + 屏外枪灯连续性(hold+blend)

两个交付,同一实机闭环:**①数据化打桩判定**(替代截图目检,用户 09-02 三问之②的落地);
**②屏外枪灯跳变修复**(用户三问之③:光源离开屏幕空间时的跳变)。
全部判定来自逐帧 CSV 中间值,截图仅作附录;权威判定串 = `TP-INVARIANCE` / `TP-CONTINUITY`。

## 一、打桩链路(什么被钉住了)

`!rec` 录制会话新增 **G 行**(34 列,与 C 行同帧同 t):TP 枪灯每帧全链中间值——

```
state/weight/mode → raw 视空间束轴原样读数(mixin 打桩点,变异模式下即错误读数)
→ 捕获时刻相机(yaw/pitch/四元数/眼位,映射基准)→ 映射后世界锚/向(混合前)
→ 活体 referent(位置/头偏航/俯仰/瞄准/手持,稳态判定输入)
```

`rec-analyze.js` 新增两个硬判定(阈值见 summary.json `tp.thresholds`):

- **TP-INVARIANCE**:冻结目标 + 相机扫掠 ≥30°、有效帧 ≥100 时,束向世界方向
  最大漂移 ≤3° 且与相机转动 Pearson 相关 ≤0.5 → PASS。
  (坑68 的特征正是"方向随相机转",双机位手工差 56°;本判定使其可脚本复现)
- **TP-CONTINUITY**:状态过渡帧的锚点步进 blend 模式 ≤0.15 格 → PASS;
  hard 模式 REPORT-ONLY(旧基线留档)。

新旋钮:`!sweep yaw|pitch <度> <秒>`(程序化扫掠,取代不可靠键鼠注入,坑41/46)、
`!tpfb blend|hard`(回退模式 A/B)、`!tproe col|row`(坑68 读数复现,变异测试)。
契约:TpLightResolverContract 34 项 / CameraSweepContract 13 项 /
TpFallbackControlContract 5 项 / MuzzlePoseStoreContract 12 项(语义变更)/
MuzzlePoseMathContract 38 项(+变异模式) / FrameRecorderContract 25 项(+G 行)。
AllContracts ALL PASS;rec-analyze.test.js 79 项(含红/绿合成用例);TOOLS-SELFTEST PASS。

## 二、屏外连续性方案(主流方案选型)

跳变根因:`出视锥 → 300ms 捕获过期 → 整体切到眼位+0.45·视线近似` 的二元硬切。
选型(实时图形/游戏开发常用工具箱):
- **dead reckoning 外推**(DIS/网络同步标准):转向时外推发散,且我们的信号非噪声而是"间歇可用"——不选;
- **critically damped 平滑 / one-Euro 滤波**(CHI 2012,VR/AR 输入标准):滤波加滞后,
  对世界锚定的灯=灯追枪慢半拍,且只遮掩不消除阶跃——不选为主方案;
- **离屏捕获 pass**(游戏引擎离屏动画更新思路):保留精确数据但要离屏重跑枪模评估,成本高——备选未立项;
- **✅ 选定:有效性保持 + 置信度连续混合**(游戏 AI 目标记忆/sensor fusion 的
  grace-period 惯例):`fresh(在渲染,精确)→ hold(屏外但持灯者 referent 未动:
  位置 5cm/偏航俯仰 2°/瞄准/手持,沿用捕获世界位,零跳变零滞后)→ blend(referent 变,
  权重出 400ms/入 200ms slew 限速滑向近似)`。实现 = TpLightResolver(纯 JVM,34 项契约);
  捕获链同时改为**绑定捕获时刻相机**映射(fresh 窗口内跨帧用当前相机会漏进相机旋转——坑68 家族教训)。

## 三、实机判定(run-20260902-153433-006-p1644,B 端,60fps,150°/6s yaw 扫掠)

| 会话 | 模式 | 刺激 | 状态直方图 | TP-INVARIANCE | TP-CONTINUITY | 过渡步进 |
|---|---|---|---|---|---|---|
| s0001 | hard(旧) | 冻结+扫离 | fresh160/fallback251 | PASS(0.56°) | REPORT-ONLY | **0.7297 格=旧跳变基线** |
| s0002 | blend | 设计失误(开局背对) | fallback213/fresh197 | PASS(0.60°) | PASS | 0.0634(再捕获爬升) |
| s0003 | blend | 冻结+扫离 | fresh155/**hold250** | PASS(0.64°) | PASS | **0(hold 精确钉死)** |
| s0005 | blend | postkey-walk(被墙挡,Dev 未动) | fresh160/hold250 | PASS(0.65°) | PASS | 0(负对照:referent 没动 hold 不松手) |
| s0007 | blend | /tp 挪 Dev(referent 变) | fresh160/hold89/**blend23**/fallback136 | PASS(0.79°) | PASS | 0.0924(滑落,=400ms 衰减 23 帧) |
| s0009 | blend+**!tproe row** | 冻结+扫离 | — | **FAIL maxDirDev=52.34°** | — | (变异命中,同坑68 手工 56.1° signature) |
| s0011 | blend(恢复 col) | 冻结+扫离 | fresh187/hold223 | PASS(0.83°) | PASS | 0 |

**结论数字**:屏外跳变 0.73 格(hard)→ 0(blend+冻结 hold)/ 0.09 格滑落(referent 变);
变异注入(row 读数)被 TP-INVARIANCE 当场抓获(52.3°>3° 阈),恢复后 PASS——打桩工具抓错能力实机证实。
方向不变性全程 0.56~0.83°(阈值 3°),相关 |corr|≤0.22(阈值 0.5)。

## 四、诚实备注

- s0002 为会话设计失误(上一会话把 B 扫得背对 Dev,blend 未测到出口过渡),
  保留作"再捕获平滑爬升"数据;修正 = 每会话前 /tp 复位面向。
- s0004/s0006/s0008/s0010 为杂散短窗(~50 帧):`!rec` 布防态跨会话存续,/tp 复位的
  位移自动开窗所致(已入坑位册;会话选择按 MCAP-OPEN 时间戳,不按序号)。
- s0005 的 postkey-walk 刺激被地形吞掉(Dev 面墙,W 无效)——但 G 行 ref 列证明
  referent 确实未动,故 hold 维持是**正确行为**;该教训入坑位册(刺激需数据验证)。
- 旧 DIAG-TP 的 `consumeFreshThirdPerson` 会**销毁过期条目**(诊断破坏被诊断状态),
  已随存储语义变更消除(get 只做新鲜门,条目保留供 hold;prune 界=holdMax 10s)。

## 五、文件

- `sessions/<名>/frames.csv + summary.json`(summary 含 tp 块与阈值;s0009 FAIL 无 summary,见 outputs)
- `analyzer-outputs.txt` 七会话判定串原文(s0009=FAIL 转录)
- `visuals/`:s0003 三帧(fresh 面向/扫掠中/屏外 hold);`hold-60deg-dev-culled.png`=
  Dev 出视锥后墙上光池+激光点串钉在原位(F2 postkey 真机截图)
- 复算:`node tools/rec-analyze.js <会话目录>`;原始完整数据(含截图)在
  `run-observer/mcap/run-20260902-153433-006-p1644/s000{1,2,3,5,7,9,11}/`
