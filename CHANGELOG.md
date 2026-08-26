# TacLight Changelog

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
