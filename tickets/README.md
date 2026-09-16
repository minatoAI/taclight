# Curated 票据模板（v0.2 E2E SOP）

来源：`run/taclight-agent/done/` 残留的 t25/t13 实战票据，拣绿固化。
用法：拷贝到 `run/taclight-agent/inbox/`（原子写：先写 `.tmp` 再改名），
在 outbox 等同名 `.result.json`。顶层未知键（`comment`）会被 bridge 忽略，可放心写中文备注。

## 清场 SOP（必读）

1. **t-recon**（trial t0，只读）：先跑，拿到背包实占槽位、手持、位姿、灯态。
2. **floor 法清场**：toss = 把东西扔到地板上（掉落物进世界，不删档）。
   按 recon 结果把实占槽号填进清场票的 `toss`（t-item-flow 模板默认 38/1）。
   教训（t25-item-clean False）：**toss 空槽直接 fail**——不清不楚就扔等于自爆，
   必须先 recon 再填槽号。清场本身也是证据（toss 结果进 outbox）。
3. **正票**：give→open→drag→select→use→shot→state→assert 一条龙（t-item-flow）。
4. **场景基线**：一切消融/E2E 先跑 t-scene-setup（wall＋锁夜＋开灯＋wall_front 机位）。

## 试次（trial）

顶层 `"trial": "t1"` 命名尝试：t0=侦察，t1=绿链，t2=反例/变体。
同名重跑旧回执自动进 `outbox/archive/<ticket>.result.<trial>.<stamp>.json`，
canonical 名恒为最新——放心重跑，不断链。

## use-air 反例教训（t25-item-full False）

`use air` 只对可右键物有效；对方块甲等不可 air-use 物期待动作会 fail。
这是正确的行为（包序诚实），不是 bug：模板里 use 步只配可用物，
查甲类断言走 state/assert。

## v0.3 跑走持枪（t-run-walk-gun）

A 臂疾跑 4 格（sprint 5.6m/s）＋第三人称 shot 跑姿，B 臂走回＋shot 走姿，
pose2＋assert 收姿态数（sprinting/moveYaw）。注意：
- 跑前 food>6，否则 sprint 门 fail 是正确的（先吃）。
- 枪口灯朝向差异（跑向左／走向前）由第二视角取证：run-observer 联机号进
  wall 区架好机位，主号跑本票，观察者手动截图；本票只收本机第三人称＋数。
- 票尾 camera 自动回 first（复位约定），跨票无残留；sprint 闩锁同理。
