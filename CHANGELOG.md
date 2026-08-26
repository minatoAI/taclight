# TacLight Changelog

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
