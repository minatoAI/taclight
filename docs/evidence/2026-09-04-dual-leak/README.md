# 双端墙体漏光验证 2026-09-04（Complementary r5.9 前向注入，标量DDA版）

> 机位：墙 wall（x1994-2005,y121-123,z=0）；A=wall_front(2000,121.5,8,yaw180) B=wall_back(2000,121.5,-8,yaw0)。
> 灯：发射等级10，手持暖色(1.0,0.96,0.88)，outer32/inner18。分支 interop/core-extract（未提交）。

## 判定（结论先行：锥光不穿墙）

| 对照 | 帧 | 数字（imgdiff threshold=8） | 读法 |
|---|---|---|---|
| B侧活人灯 on→off | B_on_living(10:40:50,ObserverB=true) vs B_off_clean(10:49:37,ObserverB=false) | meanDiff **18.73**，changed **100476/409920=24.5%**，maxDiff 226 | 锥在自侧正常照亮（地面积光池+右半墙），灯工作 |
| A侧看对面（B灯 on→off） | A_leakwatch_on(10:41:01) vs A_leakwatch_off(10:42:43) | meanDiff **0.29**，changed 3263=**0.8%** | B的灯对墙这头零贡献=噪声。**不漏** |
| A侧自灯 on→off（参考） | 10:09:07 vs 10:10:03（run2） | meanDiff 1.37，changed 5.1%，热点在脚下bbox[539,278,752,479] | A锥没打到正对墙面（见§3），该对照作参考 |
| B侧死人灯 on→off（参考） | B_on_deadA_ref(10:09:15,A灯on) vs B_off_deadA(10:10:12,A灯off) | meanDiff 0.64，changed 4872=1.2% | 死人灯方向不可信；池区逐位一致=死人锥没照到墙（见§3），作废 |

**heat_B_onoff.png**（B活人灯on→off）：地面光池+右半墙通红=完全灯控；墙背左半纯黑=灯零贡献；青色点=树叶/猪/提示条抖动（环境噪声）。教科书级“照亮自侧、不穿墙”。

## 关键发现

1. **穿墙亮区真凶（本轮前序帧）**：run2里B侧"灯关还亮"，是因为死客户端不吃S2C、A的物品发射等级卡在10（宿主heldLighting无遮挡直照）。不是SSBO锥漏。
2. **cam机位≠灯方向（新坑，待立项）**：灯跟玩家头（look）不跟相机。A相机yaw180、头留在出生朝向→锥照身后，A端墙面帧全部只能当参考。B头与相机一致（均为0）→B侧结论有效。
3. A端5连死：06:41蜘蛛（旧）+09:53/10:09/10:16/10:21（fresh重生2-9分钟内死）。和平已落盘（level.dat Difficulty=0，doMobSpawning=false）后仍死→非怪；死因未根除（登录点恒为墙前机位(2000,121.5,8)可疑）。死亡调查已按用户要求冻结。

## 灯态链（log-excerpt.txt）

- LIGHT-SYNC ObserverB true(10:40:24)→B_on；false(10:41:37)→A_leakwatch_off；B重进login-restore false(10:46:25)→B_off_clean。
- A灯off(10:39:49)在反向验证全程保持off，单变量干净（gun=false全程）。

## 复算

```
node tools/imgdiff.js docs/evidence/2026-09-04-dual-leak/B_on_living.png docs/evidence/2026-09-04-dual-leak/B_off_clean.png --threshold 8 --json
# → {meanDiff:18.73, changed:100476}
```
