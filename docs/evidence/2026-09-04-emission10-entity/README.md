# 发射等级10活体感 + 实体不透明 + 漏光复核（2026-09-04，Complementary r5.9 前向注入）

> 接 `docs/evidence/2026-09-04-dual-leak/`（漏光 verdict CLOSED）与
> `docs/SESSION-HANDOFF-2026-09-04-dual-leak-v2.md` §7（新角色 rollout + 活 A 体感 + 半透明影子）。
> 分支 `interop/core-extract`（未提交）。A=InteropA3（新角色，离线 UUID `e4aea662…` 已逐字节验算），
> B=ObserverB；双端 `ComplementaryReimagined + enableShaders=true`，注入 `+7576 chars ×2`。

## 判定（结论先行：三项全绿）

| 项 | 帧 | 数字 / 所见 | 读法 |
|---|---|---|---|
| 发射10锥池 | A_on_wall_front（11:34:47，灯开）vs A_off_wall_front（11:36:55，灯关） | 全图 imgdiff threshold=8：meanDiff **15.07**，changed **170268/409920=41.5%**，maxDiff 157 | 灯控整帧，锥工作 |
| 热点不糊 | 同上，hotspot bbox 280,190,560,340（lumastats） | 开：mean **85.1**/p90 180.5/ge128 13887/**ge200 仅 2/ge250 0**；关：mean 28.7/p90 52.7 | 亮但无死白（frac250=0）；与 09-04 03:3x 轮开 89.8/p90 182.8 基本一致→**锥池亮度由 SSBO 锥主导，发射 5→10 几乎不动锥池，只补暖氛围** |
| 漏光复核（活 A 照墙） | B_back_dark（11:36:14，B 创造，墙背） | 墙背整面黑，仅宿主原生夜光/环境 | 活 A 照墙、B 看背仍黑，verdict 维持 CLOSED |
| 玩家实体不透明 | B_sees_A_lit（11:38:15，A 传送到墙背与 B 重叠机位） | A 后脑/发片/躯干受自灯暖照、**逐层实心、无透层**；nametag 正常 | `* color.a` 修复在真人实体上成立，半透明 bug CLOSED |
| 附带：猪/手 | A_on 右下猪、手部暖照实心 | 同上 | entities/hand 模板行为一致 |
| 影子+开销 | BENCH 3s（A，wall 机位）：灯开 frames=180 avgFPS **59.8** onePctLow 48.2；灯关 avgFPS **59.8** onePctLow 54.0 | 双双锁 60（vsync 上限），灯光注入零可感开销；三帧均无阴影伪影/黑块（阴影由宿主原生管线承担） | 定量开销：无感；影子：无异常（严格实体投影对照未做，不硬 claim） |
| 新角色验收 | playerdata `e4aea662-20f5-379e-81f5-ad996b9401cd.dat` 落盘 = `MD5("OfflinePlayer:InteropA3")` 逐字节验算一致 | 真新玩家；登录点世界出生点（-8.5,64,7.5），**无“恒为墙前机位”现象**（该现象仅同名重置出现，v2 §3 纠错的独立佐证） | rollout 通过 |

## 过程备注（坑位相关）

1. **B 后加入者是生存**：scene wall 的全员创造只覆盖执行时在线玩家；B 在 scene 之后加入→生存（空手+心条+教程 toast）。重跑一次 `scene wall` 后 B 转创造（心条消失，见 B_back_dark）。规则已在坑95（“每轮登录后立刻重跑 scene”）——本轮是该规则的实证。
2. **坑94规避成功**：活人 + `cam goto`（teleportTo 带 yaw/pitch）→ A 锥正打墙面（A_on 锥池居中），A 侧帧有效。反证 pit94 成因=死亡态 cam（死人传不动、只有相机动）。
3. B_back_dark 中央地面有一枚**红色小物件**（静态，三帧位置一致，非灯控）——身份未辨（疑似历史掉落物），与漏光无关，不立项，记一笔备查。
4. B 帧右上教程 toast（新号提示）遮挡右上，与墙面判定区不重叠，不影响结论。
5. A 视角右侧大块黑墙 = 跨预设残留（bloom 小屋黑墙，见主线记忆“场景跨预设残留”），已知现象，非本轮引入。
6. gun=false 全程（kit 预装 gun_light 但 HK416D 未持手→探针无枪=关，符合“有附件才亮”语义；T1 手动开关 ticket 另案）。

## 复算

```
node tools/imgdiff.js docs/evidence/2026-09-04-emission10-entity/A_on_wall_front.png docs/evidence/2026-09-04-emission10-entity/A_off_wall_front.png --threshold 8 --json
# → {meanDiff:15.07, changed:170268}
node tools/lumastats.js docs/evidence/2026-09-04-emission10-entity/A_on_wall_front.png docs/evidence/2026-09-04-emission10-entity/A_off_wall_front.png --bbox 280,190,560,340
# → on {mean:85.1, p90:180.5, ge200:2, ge250:0} / off {mean:28.7, p90:52.7}
```

## 收尾状态

- 灯态：A handheld=true（开，留给用户体感），B 无灯；双端光影包保持启用（待用户体感，故未按 AGENTS §7 关灯/禁包——实例非空闲）。
- LAN 25560 开着；双窗口 watch-only（坑96）。
