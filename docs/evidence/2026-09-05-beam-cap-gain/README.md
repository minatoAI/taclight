# 2026-09-05 !beamcap 重叠软上限 + BEAM_GAIN 翻倍(侧视提亮)

## 需求(用户)
1. 两灯同照一处时体积光亮度无限叠加、白爆刺眼 → 要"上限"限制;
2. 体积光轮廓(尤其侧视)仍偏暗 → 亮度提高或给可调项。

## 改动
1. **`!beamcap <0.25..8>`**(第七旋钮):SSBO vlParams.z 透传倍率 m,GLSL 软上限
   `cap = TACLIGHT_BEAM_CAP(2.0)×m`,分量级指数肩部——**分量 ≤半帽点恒等(单灯观感
   零变化),>半帽点平滑渐近 cap**(非硬截断,无色阶)。0.25=压得最狠,8≈基本不限,
   off=回 m=1。表面照明(M1)不受影响(不消费 vlParams.z)。
2. **TACLIGHT_BEAM_GAIN 0.5→1.0**:体积光总增益翻倍(全角度,侧视轮廓×2);
   重叠眩光由软上限兜底。
3. 坑105 入册:首版 `vl<=sh`(vec3 与 float 三元比较)→ C1020 → Iris 静默禁包
   (全场原版黑夜),修复为 min+exp 分量级写法;`!reload` 后必查日志编译错。

## 契约
LightTune 68→**88** 项:beamcap 解析/0=哨兵拒绝/越界不污染/SSBO 回环/buildSpotBeam
集成(vlParams.z=2.0 覆盖默认 1.0)/收尾零残留 + GLSL 源断言(BEAM_CAP 2.0、
vlParams.z 消费点、指数肩部、GAIN 1.0)。红(编译失败)→绿,
`AllContracts: ALL PASS`(2026-09-05 16:4x 实跑)。

## 实机判定(双灯同照重叠场景:B+Dev 相距 ~4m、双束汇聚同一片墙面,diag count=2 钉死;
B 端拍摄,beamonly on + beam 1.0 + bright 30 + atten 0.5 + scat 0)
| 图 | beamcap | 结果 |
|---|---|---|
| 01-overlap-cap-default2-blown | off(cap=2.0) | 重叠区白爆成片,墙纹全被冲掉 |
| 02-overlap-cap025-clear | 0.25(cap=0.5) | **墙砖纹理清晰可辨、光斑柔和收敛,眩光消失** |
| 03-overlap-cap8-unlimited | 8(cap=16) | 与 01 观感相同(仍白爆)= 上限放开即回刺眼 |
| 04/05-single-identity | off vs 8(单灯常规配方) | **meanDiff 0.033/changed 0.11% ≈ 噪声底(0.48)——上限不咬合正常单灯,零误伤** |

像素差(cap off=2.0 为基准):
- vs cap 0.25:meanDiff **17.57** / changed **21.1%**(86278/409920)——压制幅度显著;
- vs cap 8:meanDiff 0.035 / 0.07%——极能级下 2.0 与 16 post-tonemap 同饱和(都白爆),
  实用压制值在 ≤1 一档。

## 使用建议(给用户)
- 双灯同照刺眼:`beamcap 0.5`(常用)或 `0.25`(最狠);单灯场景不用动(默认不咬合)。
- 侧视轮廓更亮:本包 GAIN 已×2;再要更亮 → `beam 0.8~1.0` + `scat 0~0.1`;
  侧视背景要有近景(墙/地面),对虚空看本来就该暗。
- 现场已复原:A 端=推荐配方(beamonly on + beam 0.6 + bright 12 + atten 2 + scat 0.15 +
  beamcap off),Dev 在 (2004.3,121,6.7) yaw63、灯 off;B 灯 on、旋钮全默认、
  (2000.5,121,8.6) yaw180.3 pitch5.7。
