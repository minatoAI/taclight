# TacLight Shaders(开发内包)

路线 S 自研光影包,M0 骨架版。独立发布仓库名 `taclight-shaders`(GPL-3.0),
按 doc06 §8 审批记录·①:先在本目录内包开发,M4 发布时抽出为独立仓库。

## 目录

```
pack/shaders/
├── shaders.properties          # 最小配置;禁止声明 bufferObject.7(铁律2)
├── lib/taclight_common.glsl    # SSBO ABI 镜像 + 仅有的两个坐标入口函数(K 键绿锥)
├── gbuffers_basic / textured / textured_lit / terrain / entities /
│   hand / skybasic / skytextured / water / weather / beaconbeam
├── composite.(vsh|fsh)         # 光照主场:读 binding=7 SSBO + 绿锥诊断(#version 430)
└── final.(vsh|fsh)             # 直通输出(M2 风格层接入点)
```

## 同步到运行实例

```
gradlew syncShaderPack     # 复制 pack/shaders → run/shaderpacks/taclight-shaders-dev/shaders
```

游戏内 视频设置 → 光影包 选择 `taclight-shaders-dev`。

## M0 验收(doc06 §4)

1. **不加包**画面与原版一致(本包开箱基线 = 原版像素级观感);
2. 选中本包后加载日志无 error(gbuffers 全部编译通过);
3. 手持手电筒(Tab 键开灯)按 **K** 进入绿锥调试模式:枪指方向出现**绿色锥形**,
   锥内全亮、边缘 softstep 渐灭、超出半径熄灭 —— 这验证数据通道 + 锥几何 + 半径三件事。

任何调整前先截图留证(evidence 纪律:同机位 A/B 对照)。

## 与 Java 侧的契约

- ABI 真源:`src/main/java/dev/taclight/channel/SpotlightBufferLayout.java`
- 坐标语义:**world 上传**(v0.9.0 起),消费侧经 `taclight_world_to_scene` →
  `taclight_scene_to_view`;禁止在 GLSL 其它位置内联换算(v0.8.4 事故教训)
- 双端守护:`gradlew taclightContracts`(SpotlightBufferLayoutContract +
  UploaderSemanticContract)
