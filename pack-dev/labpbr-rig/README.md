# labpbr-rig · TacLight LabPBR 测试资源包(开发专用)

阶段二(M1 GGX specular + LabPBR 解析)的实机验证台。**只提供 `_s` 贴图,
不改原版任何 albedo** —— 三种材质语义一次覆盖:

| 方块 | _s 设计 | 预期观感(手电照射) |
|---|---|---|
| `stone_bricks`(wall 场景墙面) | 砖面 R≈200/G=30 半抛光介电,砖缝 R≈40/G=12 粗糙 | 砖面出现高光切变、砖缝哑光,逐 texel 对比 |
| `smooth_stone`(wall 场景地板) | 均匀 R=170/G=20 | 地板轻微反光,粗糙度被 0.20 护栏钳制 |
| `iron_block`(测试时 /setblock 上墙) | R≈205/G=230(**金属**,LabPBR 预定义 Iron) | diffuse 归零,高光呈铁灰色(=albedo 调色) |

## 使用

```powershell
# 1. 生成贴图(确定性抖动,输出到本目录 assets/)
powershell -NoProfile -ExecutionPolicy Bypass -File gen.ps1
# 2. 同步到实例资源包目录(游戏关闭时)
robocopy pack-dev\labpbr-rig run\resourcepacks\taclight-labpbr-rig /E /NFL /NDL
# 3. run/options.txt 的 resourcePacks 追加 "file/taclight-labpbr-rig"
# 4. 启动后:gradlew syncShaderPack + !reload → DBG_STRIP=7 看材质审计视图
```

注意:A 通道写 255(LabPBR 语义 = ignored),防加载链预乘 alpha 清空 RGB 数据。
