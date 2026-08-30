# 证据包 · W2/W3 标尺换基准(2026-08-30 傍晚):几何判定改 DBG4/DBG8,终帧质心降级

起因:用户批准"把 W2/W3 的几何判定基准改成 DBG4 光束"(坑26:终帧亮区质心随外观
旋钮摆动 ±0.05+,是观测量不是几何量)。

## 1. 变更清单

| 项 | 内容 |
|---|---|
| strip 8(新) | `final.fsh` 直读 colortex0(表面光,M1 照明,无光束/bloom),×6+γ0.45 显示增益(与 DBG4 同款,只影响诊断显示不改几何);`lib/taclight_debug.glsl` 值语义表同步 |
| acceptance.ps1 | align 模式新增 `-LumaThA/-LumaThB`(默认回落 `-LumaTh`);frac 空判定护栏改用各自阈值(修掉"A 腿空核心却绿灯"的洞);头注用法同步 |
| 文档 | 调试环境搭建计划 §3H(表加 7/8 行)、§7(W2/W3 换基准 + W4 备注)、坑26 关闭 |

## 2. 量测配方(定稿)

| 腿 | 视图 | 阈值 | 依据 |
|---|---|---|---|
| 光束 | strip 4(×6+γ0.45) | LumaTh 110 | 光束缓冲值小,×6 后整体可见;110 压掉 HUD 残余 |
| 表面光 | strip 8(×6+γ0.45) | **LumaTh 253(饱和核心)** | 受光核心整片饱和成 plateau,取 plateau 质心 = 阈值不敏感(实测 253→254:Δcx=0.0006);低阈值扫描漂移 0.576→0.532(140→220),故必须用核心 |

## 3. 门判定(2026-08-30 17:16,wall@front / midnight / clear / 灯开)

| 门 | 判定命令 | 结果 |
|---|---|---|
| W2(几何) | `acceptance side -A dbg4_beam.png -MinX -0.01 -MaxX 0.08` | **PASS cx=0.5268** ∈ [0.49,0.58](昨日 0.5264,复现性 0.0004) |
| W3(几何) | `acceptance align -A dbg8_surface.png -B dbg4_beam.png -LumaThA 253 -LumaThB 110` | **PASS dx=0.0095 dy=−0.0180**(容差 0.05/0.08 未放宽) |
| 观测(非门) | `acceptance centroid -A final_on.png` | cx=0.4852(昨日同姿态 0.4541——再次实证摆动,故降级) |
| 观测(非门) | 旧配方 `align final vs dbg4`(单阈值 110) | dx=−0.0415(昨日 −0.0723 FAIL;摆动跨 ±0.03+) |

## 4. 过程坑(新增入册)

- **ESC 菜单污染取证**:用户离开时游戏停在 ESC 菜单,前两张截图(菜单 + 背景)质心
  完全相同才暴露。以后取证 SOP:截图后先目检图像,再跑判定;UI 遮挡帧作废。
- **gradle 离线**:wrapper 发行版 8.1.1 从未下载完整(仅 .part);syncShaderPack 手工
  等价 = include 行尾检查(grep)+ `cp -r pack/shaders/. run/shaderpacks/.../shaders/`。

## 5. 文件

| 文件 | 内容 |
|---|---|
| final_on.png | 正常画面(修复后重拍,菜单污染帧已替换) |
| dbg8_surface.png | strip 8 表面光(×6+γ0.45) |
| dbg4_beam.png | strip 4 体积光束(×6+γ0.45) |
| manifest.sha256 | 全文件 SHA-256 |
