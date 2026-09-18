# TacLight Shaders v0.10.0

路线 S 自研光影包(M1–M4 完整版)。GPL-3.0,100% 自写(0 行 iterationT /
Photon / 旧项目代码;仅使用公开数学公式与官方色度常量)。
独立发布仓库名 `taclight-shaders`;当前按 doc06 §8 审批记录·① 在本目录内包开发,
发布时抽出为独立仓库(`gradlew packShaderZip` 产出发布 zip)。

## 效果总览(与里程碑对应)

| 里程碑 | 内容 | 管线位置 |
|---|---|---|
| M1 锥光照明 | Lambert 漫反射 + GGX 高光 × 内外锥软边 × D6 平滑反平方衰减 × 屏幕空间遮挡(植被半透) | composite |
| M3 体积光束 | 沿视线 raymarch,HG 相位(g=0.55 前向散射)+ 深度遮挡 + IGN 抖动 | composite1 |
| M2 风格层 | 两级 bloom + 自适应曝光 + ACES(Hill 拟合)+ split-tone + 暗角 + 胶片颗粒 | composite2/3 + final |

## 目录

```
pack/shaders/
├── shaders.properties          # ASCII-only;禁止声明 bufferObject.7(铁律2)
├── block.properties            # 材质分类(遮挡系数:薄片档0.0/中低档0.25/树叶0.6/实心1.0)
├── lib/taclight_common.glsl    # SSBO ABI 镜像 + 坐标入口 + 照明数学 + SSO(430 include)
├── lib/taclight_gbuffer.glsl   # 八面体法线编解码 + G-Buffer 布局契约(版本无关)
├── lib/taclight_style.glsl     # 风格层数学:IGN/HG/ACES/split-tone/暗角(版本无关)
├── gbuffers_*(vsh/fsh,11 对)  # G-Buffer 写出(0=基线色 1=法线+lm 2=albedo+smooth 3=视位置+遮挡系数)
├── composite.(vsh|fsh)         # M1 表面照明(430 core;含 K 键绿锥/门探针/法线审计诊断开关)
├── composite1.(vsh|fsh)        # M3 体积光束 raymarch → colortex4
├── composite2.(vsh|fsh)        # M2 bloom 一级(半密度降采样)+ 自适应曝光统计
├── composite3.(vsh|fsh)        # M2 bloom 二级(1/4 密度十字模糊)
└── final.(vsh|fsh)             # M2 合成:bloom 叠加 → 曝光 → ACES → split-tone → 暗角 → 颗粒
```

## 材质分类(block.properties)与状态谓词

遮挡系数分档(2026-09-18 起):`block.2003` 薄片档 **0.0** / `block.2001` 中低档 **0.25**
(镂空植被 + 半砖 bottom/楼梯 bottom/雪 3–7 层)/ `block.2002` 树叶 **0.6** / 其余实心 **1.0**。

**改这个文件前必读(Oculus 1.8.0 实测)**:状态过滤只能写 `minecraft:name:key=value`(冒号);
写成 `minecraft:name[key=value]` 会被解析成**不存在的** `minecraft:minecraft` 而**静默失效**;
未知但格式合法的 ID 会被静默跳过;格式非法的 ID 会抛异常中止整包加载。该结论由**发布的**
`BlockEntry.parse` 对全部 226 个 token 回读校验(0 mismatch)得出,不是推测。

Java 体素路径按**运行时碰撞形 + 遮挡形**分档(C 口径:守卫读碰撞形、占比读遮挡形),
覆盖面大于本清单(含模组方块);本清单只能列原版 ID,故 `!voxel off` 的 SSO 回退路径在
清单之外仍会漂移 —— **以 Java 体素路径为主路径**。

## 安装

1. 安装 Forge 1.20.1 + Oculus 1.8.0 + Embeddium ≥0.3.31(不支持 OptiFine);
2. 发布 zip(`gradlew packShaderZip` → `build/distributions/taclight-shaders-<版本>.zip`)
   放入 `.minecraft/shaderpacks/`;
3. 视频设置 → 光影包 选择 `taclight-shaders-<版本>.zip`;
4. 装本模组(TacLight),L 键开关手电筒,K 键霓虹调试(绿锥 = SSBO 通道取证)。

聊天栏提示"✔ 配套包已激活" = 包与模组握手成功(自检读 shaders.properties 的
TACLIGHT_PATCH_BEGIN 标记)。

## 配置联动(模组侧 taclight-client.toml)

- `radius` = 基准半径@亮度6(有效半径 = radius × √(intensity/6),钳 ≤96);
- `intensity` = 亮度(挡位只改亮度,照距自动 √ 缩放);
- `coneOuterDeg / coneInnerDeg` = 外/内锥半角(默认 32°/18°);
- `beamDensity` = 体积光束密度(0 = 关闭光束,默认 0.05);
- `gunMultiplier` = 枪灯亮度倍率。

## 风格旋钮(单一入口)

- `composite1.fsh`:`TACLIGHT_VL_STEPS`(步数)/ `TACLIGHT_BEAM_GAIN`(光束增益);
- `composite2.fsh`:`TACLIGHT_BLOOM_TH`(bloom 阈值)/ `TACLIGHT_EXPOSURE_TARGET`(目标亮度,
  夜景基调)/ `TACLIGHT_EXPOSURE_MIN/MAX`(曝光钳制)/ `TACLIGHT_ADAPT_RATE`(眼适应速率);
- `final.fsh`:`TACLIGHT_BLOOM1/2_GAIN`(辉光强度)/ `TACLIGHT_SPLITTONE` /
  `TACLIGHT_VIGNETTE`(四角亮度)/ `TACLIGHT_GRAIN`(颗粒幅度);
- `lib/taclight_common.glsl`:`TACLIGHT_LIGHT_GAIN`(表面照明总增益)/
  `TACLIGHT_KNEE_GAIN`(近场软肩)/ `TACLIGHT_ATTEN_K`(衰减尾长)/
  `TACLIGHT_SSO_SELF_FREE`(贴灯豁免半径)。

## 已知边界(写死,doc06 §3.3)

- 遮挡上限 = 屏幕空间(SSO):视锥外的遮挡者不参与(墙后物体不挡光);
  真投影阴影 = S4 spike 探索项,失败即关;
- 第三人称/他人视角的光源锚定为玩家眼睛+视线(第一人称锚相机);
- 只承诺 Oculus 1.8.0 + Embeddium ≥0.3.31 单栈、MC 1.20.1 单版本。

## 与 Java 侧的契约

- ABI 真源:`src/main/java/dev/taclight/channel/SpotlightBufferLayout.java`
  (96B×N 灯 + 16B 头,binding=7;包内禁止声明 bufferObject.7);
- 坐标语义:**world 上传**(v0.9.0 起),消费侧经 `taclight_world_to_scene` →
  `taclight_scene_to_view` 两级封装;禁止其它位置内联换算(v0.8.4 事故教训);
- 双端守护:`gradlew taclightContracts`(Layout + UploaderSemantic + MuzzlePoseMath)。
