# 2026-09-15 v0.2 新物品全流程 E2E + floor 门控补存 (task-25/29)

单票一遍过 `t25-item-final` (trial t1, ok=true, 旧 rig 真机):
toss38 → toss1 → give diamond_chestplate → open → drag 1→7 →
select 7 → use air (tier=air) → shot → state armor=8 → assert 2/2。
回执 `run/taclight-agent/outbox/25-item-final.result.json`，
CSV `run/taclight-agent/rec/run-20260915-044704-p18004/s0001/frames.csv`
(114 行 rows=113)，trial 归档不断链 (task-9)。

floor 门控 (task-15 口径):双 shot 对 a/b (间隔 ~1s, 空手无枪摆)
`naked changed=0.0924%, Ymean=0.0002, p99=0.00, max=45`
远低于同场本底带 (2-4%) → PASS。见 `imgdiff-floor.json`。

诚实失败对照: flashlight use-air 按设计失败
(`air use did nothing`, 附 fail 图);已穿同甲复穿亦 honest fail。
drag 双 x1 系护甲 maxStack=1 走原版 swap 分支 (InvActions clickSlot)。

文件:
- `taclight-t25-chestplate-a/b.png` floor 对子
- `taclight-t25-final-a.png` 一遍过证据帧
- `taclight-fail-t25-neg-flashlight-1.png` 诚实失败帧
- `imgdiff-floor.json` 量法数
- `manifest.sha256` 校验
