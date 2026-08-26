# TacLight Changelog

## v0.9.0(路线 S 开工 —— 自研光影包启动)

- **审批落地**:docs/06 草案 v0.1 → 已批准 v1.0;D1–D8 全部按建议批准;三项未决问题同日裁决(6.1 坐标语义→执行 doc06 版 world 上传;6.3 bloom→纳入 M2;包位置→内包 `pack/`,M4 抽独立仓库),完整记录见 docs/06 §8
- **6.1 坐标语义整改(行为变更,三处对齐)**:`ClientSpotlightUploader.toSpot` 不再减眼位 —— posRadius.xyz 恢复传 **world 坐标**;scene-relative 转换移交配套包 GLSL 侧(`pack/shaders/lib/taclight_common.glsl`,`taclight_world_to_scene → taclight_scene_to_view` 两级唯一入口);`SpotlightBufferLayout` javadoc 同步标注。⚠️ 旧派生包 `iterationT 3.2.0 (taclight)` 冻结于 ≤0.8.4 scene-relative 契约,与 0.9.0+ 模组组合会"灯随镜头漂移",属预期废弃路径
- **新增契约**:`UploaderSemanticContract`(9 断言:world 直传逐位校验/无眼位减法/纯函数可复现),注册进 AllContracts;上传器参数提取为可注入 `LightParams`(测试零 MC 依赖)
- **M0 骨架包**(`taclight/pack/shaders/`,全自写 0 行照搬):gbuffers ×11 程序对(basic/textured/textured_lit/terrain/entities/hand/skybasic/skytextured/water/weather/beaconbeam,#version 120 最小直通+光图);composite(#version 430,binding=7 SSBO 只读消费 + K 键调试绿锥);final 直通(M2 风格层接入点);shaders.properties(铁律2:不声明 bufferObject)
- **新增任务**:`gradlew syncShaderPack`(pack/shaders → run/shaderpacks/taclight-shaders-dev/shaders)
- **提前清理**:LightBuffer.upload() 移除 SLOT PROBE(binding 0/1/8 冗余绑定);FLAG_TIMING_PROBE 头位保留至 M1 门控探针落地再收编(docs/06 §8.2)
- 版本统一:**TacLightMod.VERSION 与 mod_version 均 = 0.9.0**(D8)
- **M0 热修(首次实机加载,2026-08-27)**:composite.fsh 在 `#version 430 core` 下使用 varying/gl_FragData 触发 NVIDIA C5514/C7616 编译失败 → Iris 禁用整包;改为 fsh=`430 core`+`in`/`layout(location=0) out`、vsh=`330 compatibility` 的混搭 —— 与路线 P 派生包在本机验证过的组合一致。另在 shaders.properties 放入 `TACLIGHT_PATCH_BEGIN` 标记使 ShaderPackDiag 能识别自研包(文案"派生包"措辞系 v0.8.3 遗留,M1 再改"配套包")。证据:run/logs/latest.log 原报错行消除(待复验绿锥)
- **M0 热修 2·渲染帧同步(实机:转视角灯光拖拽)**:根因 = SSBO 上传挂在 ClientTick(20Hz),灯位滞后相机最多 50ms;迁移到 RenderLevelStageEvent.AFTER_LEVEL(渲染帧级,相机本帧终值,先于 Iris composite);退出世界时主动清空 SSBO。社区同型案例交叉验证(手持光滞后 = 数据未按帧更新):shaderLABS wiki / r/OptiFine / Chocapic13 论坛帖
- **M0 热修 3·D6 衰减提前落地(实机:照明距离不足)**:绿锥预览接入平滑反平方 `taclight_attenuation = 1/(1+k·d²)`(k=2/r²,atten(r)=0 长尾,分母无奇点),替换旧 `(1-d/r)` 线性淡出(半半径仅剩 50% 亮度 = 视觉半径提前死亡);radius 默认 24→40、上限 64→96(TacLightConfig + run/config 同步);M1 表面照明复用同一函数
- **M0 热修 4·亮度-距离 √ 耦合(实机:亮度高但照不远)**:反平方律推论 d ∝ √I —— `有效半径 = radius × √(intensity/6.0)` 钳制 ≤96;radius 语义改为"基准半径@亮度6",默认 40→56;未来挡位设计 = 只改亮度,照距自动 √ 缩放(doc06 §8.7);契约升级 12 断言(参考/×2/钳制/×0.05 四个标定点)
- **M0 热修 5·第三人称世界空间锚定**:手持灯非第一人称下锚玩家眼睛+玩家视线(原锚相机会"灯浮在相机上");第一人称行为不变。参考实现 Handheld Moon(ARR,只参考行为)分析入 doc06 §8.7:地面亮斑=M1、可见光锥=M3(已批, vlParams 已预留)、他人手持灯= v2 可纯客户端实现
- **M0 热修 6·近场软肩压缩(实机:近场过曝糊死)**:诊断 = 反平方归一化近场平台(0.25r 处仍 83%)+ 无高光压缩 → 削顶纯绿,属亮度曲线缺陷(非调试色问题);修复 = 软肩 `x/(1+G·x)`(G=2.0,远场≈线性不动、近场压向 1/G,近远比 5.3:1→2.6:1)+ 合成增益 1.5→1.8;可调旋钮 `TACLIGHT_KNEE_GAIN`;v0.8.2 同型软膝行为语义、公式重写,M1 复用
- 证据:`gradlew-java17.cmd compileJava taclightContracts --offline` → BUILD SUCCESSFUL,契约 39/39 通过(Layout 16 + UploaderSemantic 12 + MuzzlePoseMath 11)

## v0.8.4(坐标约定修复——光终于画出来了!)
- **根因**:Iris/Oculus 的 composite 后处理 pass 里 `gbufferModelView` 是**纯旋转矩阵(无平移)**——坐标体系是"场景相对坐标"(world − cameraPosition);我们按"完整视图矩阵"换算,导致灯被算到 ~275 格外 → `dist>radius` 全部拒绝 → **光从未渲染过**(用户看到的"白团"= iterationT 内置传统手持光,HELDLIGHT_MODE=0)
- **修复**:`lightView = mat3(gbufferModelView) * posRadius`(纯旋转,场景相对→视图);surface/specular/beam 三处同步(与旧项目 tarkovline 的全程 scene-relative 约定一致)
- **端到端验证**:探针 `reserved=0x22fb` 全位通过;实机截图确认绿色锥形光 + 距离衰减可见
- 新增分阶段探针位与 GPU 回读(纠正 cookie 读回偏移 80→96 的 bug)

## v0.8.3(选包自检 + K 键反馈)
- **症状**:用户按 K 无反应、光仍为"无衰减白团" → 调查:代码无误,最可能是**选中的是原包迭代T而非派生包**,SSBO 通道根本没运行
- **新增开机自检**(ShaderPackDiag):读 config/oculus.properties + 检查活动包里是否有 TACLIGHT_PATCH_BEGIN 标记 → 每 5 秒检测,状态变化时聊天栏+日志提示(未激活/原包/派生包/无法判定)
- **K 键聊天反馈**:切换时聊天栏提示 ON/OFF;若手电筒关闭自动开启(便于观察绿锥)
- 主类版本日志 v0.8.3(确认运行构建)

## v0.8.2(真实感 + 通道可辨识)
- **修复削顶**:注入光加 filmic soft-knee(`taclight_knee = e/(1+e)`,增益 2.5)——此前强度 6 直接叠加导致锥形区域内近处远处全部钳到最亮,肉眼看不到距离衰减(用户反馈"无论多远亮度一样"的根因,也是"假"的主要来源)
- **K 键霓虹调试模式**:GLSL 输出纯绿锥形光(无 albedo/AO),与光影包内置手电一眼区分;契约新增 FLAG_DEBUG=2 断言(15 项)
- 光束/高光同样套用 soft-knee,消除白团
- 教学手册新增"怎么分辨你看到的是哪条通道"

## v0.8.1(热修)
- **修复**:taclight_specular 的 f0 参数 vec4→float(与 iterationT Material.f0/SpecularGGX 一致);此前导致 composite5 编译失败→整个光影管线关闭(也是 SSBO 探针全线归零的根因)
- **SSBO 通道端到端验证通过**:E2E 探针回读 reserved=0x1(表面 pass 触发+闭环))
- docs/04 结论修正:外部 SSBO 绑定可用的(推翻先前"Oculus 转换层阻断"的假设)

## v0.8.0
- **B 计划**:GunItemLightProviderMixin 给 ModernKineticGunItem 注入 IrisItemLightProvider 接口(官方 API 判定战术枪灯 → 光强 15),枪灯经 G 通道点亮 iterationT 内置 FLASHLIGHT
- 补丁工具修复:patchIterationT inputs 声明(消除 up-to-date 误判);PackPatcher 幂等改按内容判断
- docs/04-SSBO绑定调查记录.md(完整证据链与重启路径)
- 契约 29/29

## v0.7.0
- /taclight kit 命令(一键发放验收套件:手电筒+HK416D+战术枪灯)
- 正式发布构建(clean build 终验)

## v0.7.0-dev
- 新增 Forge 客户端配置 taclight-client.toml(半径/强度/内外锥角/光束密度/枪灯倍率)
- 发布文档:本文件、RELEASE.md、docs/03-实机验收清单.md

## v0.6.0-dev — V4 枪口精确姿态
- BeamRendererMixin(双路径)捕获 TaCZ 激光渲染矩阵 → 视图空间姿态
- MixinConfigPlugin 软依赖门控;MuzzlePoseMath(纯数学,契约 11 项)
- gunpack 模型骨名改为 laser_beam(光束渲染+捕获两用)
- 契约总数 29/29

## v0.5.0-dev — V3-p2 体积光束 + 高光
- taclight_beam(16 步 raymarch + 光束遮挡,composite.fsh)
- taclight_specular(GGX,composite5.fsh);SpotlightData.spotBeam

## v0.4.0-dev — V3-p1 SSBO 通道
- SSBO binding 7(std430:16B 头 + 96B/灯,兼容 irlite ABI)
- 表面锥光(smoothstep 软边/距离衰减/screen-space 遮挡/Burley)
- PackPatcherTool:锚点唯一性 + SHA-512 + marker 幂等;iterationT 3.2.0 派生包

## v0.3.0-dev — V2 TaCZ 枪挂灯
- gunpack taclight:gun_light(laser 类;官方 ResourceManager.EXTRA_ENTRIES 注册)
- 探针识别(显式优先/内置兜底)+ GunLaserReader 契约 6 项

## v0.2.0-dev — V1 手电筒物品
- FlashlightItem + FlashlightItemIris(IrisItemLightProvider)
- L 键开关(本地状态);中英语言;16x16 贴图

## v0.1.0 — V0 工程骨架
- Forge 47.1.3 + MC 1.20.1 + Java 17;离线构建(复用 1.67GB Gradle 缓存)
