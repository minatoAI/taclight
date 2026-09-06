# 枪灯 M 键 + `!gun auto` + 镜面 F4 + knee8(2026-09-07 凌晨,自研包)

机位:cam=(1987.95,122.62,4.96) yRot=121.3(夜景深蓝瓷砖墙,手持手电,bright 6,
锥 8/4,atten 20)。包=taclight-shaders-dev,零编译错(一次 C1503 翻车见坑 115)。

## B 路:枪灯可控性(Java,需重启,已重启验证)

- TaCZ 1.1.8 原版**无激光/灯开关**(键表:inspect/reload/shoot/interact/fire_select/
  aim/crawl/refit/zoom/melee/open_config,无 laser 位)——"与激光共用一键"无法字面
  落地,替代:M 键(`key.taclight.gunlight_toggle`,控制设置可改),与 `!gun` 共
  GunControl 状态机。契约 GunControlContract 13 项 ALL PASS。
- `!gun <on|off|auto|status>`:裸参=翻转(兼容);`auto`=清手动旗回探针跟随
  (主手有灯枪即亮、空手即灭);未知参=回显。实机:`auto`→probe-follow,
  `status`→`gun=false manual=false`。
- 用户"空手也亮"根因=诊断遗留 `manual=true` + 枪灯无键可灭;探针本身只读主手
  (TaczClientLightProbe:20),清 manual 即恢复。空手门禁(持枪才亮)未做——用户
  已定性 bug,待立项(探针已有主手判定,上传端是否再加门禁需拍板)。

## C 路:反光(同 ROI 560-780,300-500 步 2)

| 帧 | 状态 | 均值/峰值 | 说明 |
|---|---|---|---|
| 02.47.40.png | HEAD(无 F4),knee 2 | 63.0/- | 白核(肉眼炸白,clip250=0,240s 级) |
| 02.48.18.png | F4(镜面渐近 4),knee 2 | 63.0/- | **逐位一致**:此景白斑与镜面无关 |
| 02.49.46.png | F4 + SPEC_DAMP=0 探针 | 白斑不动 | 铁证:白斑=漫反射吹爆,不是 GGX |
| 02.50.58.png | F4 + knee 8 | 58.7/峰 211(vs 83.3/220) | 白核消除,墙砖纹理透出 |

- F4(对标 Comp `ggx.glsl` 饱和式 `spec/(0.125·spec+1)`):本包取渐近 4,线性 DAMP
  0.35 保留。签名不变。此景零作用但护真镜面(枪身金属近摄),保留。
- 真凶=近场漫反射 + 底黑对比。`!knee 8` 即治。knee 默认值(现 2.0)是否提到 8
  **待用户体感拍板**(运行实例现挂 knee 8 覆盖,重启清零)。

判定:B 路中继全通,契约绿(除环境性 node EPERM,见 CHANGELOG);C 路 F4 落地 +
真凶定位 knee,等拍板。M 键与空手门禁体感待用户(需真人按键/切槽)。
