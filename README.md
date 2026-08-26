# TacLight — 聚光灯照明设备(Forge 1.20.1)

为 Minecraft 提供"手电筒/聚光灯"式**锥形照明设备**(非游戏内点光源),并与
[Timeless and Classics Zero](https://www.curseforge.com/minecraft/mc-mods/timeless-and-classics-zero)
联动(枪挂照明附件 + 独立手持手电筒)。

## 技术栈
- MC 1.20.1 / Forge 47.1.3 / Java 17
- 光影:Oculus 1.8.0(Iris Forge 移植)+ Embeddium;参考/首发适配 **iterationT 3.2.0**
- 数据通道:官方 Iris API(IrisItemLightProvider)→ 兼容所有支持手持光的包;
  V3 起 SSBO+补丁通道实现"光从枪口出"(详见调查报告)

## 配置
所有光效参数在 `run/config/taclight-client.toml`(半径/强度/内外锥角/光束密度/枪灯倍率)或发布后 `config/taclight-client.toml`。

## 常用命令(纯离线,复用本地 Gradle 缓存)
```powershell
.\gradlew-java17.cmd build        # 编译 + 打包(离线 --offline 可加)
.\gradlew-java17.cmd runClient    # 客户端 dev 运行
```
> 需要  `E:\dshHome\mc-shader-spotlight-dev-qa\.tools\jdk-17`(JDK 17,复用旧项目工具链);
> 首次联网环境执行一次 `gradlew build` 后即可完全离线。

## 目录
- `src/main/java/dev/taclight/` 产品代码
- `libs/` 本地离线依赖(不提交 git)
- `.gradle-user-home/` 复用自旧项目的完整 Gradle/ForgeGradle 缓存(不提交 git)
- `../docs/01-调查报告与项目边界.md` 三问调查与路线图

## 开发纪律(摘录,详见调查报告 §5)
1. 单一状态真源:只维护一份里程碑清单(本 README 状态行)。
2. 测试/脚手架独立 sourceSet,主源码只放产品类。
3. 官方 API 优先;反射/mixin 收敛进 adapter 层 + 版本探测 + fail-closed。
4. 依赖全部 optional + 能力探测。
5. GLSL 单源生成,禁止手工双拷贝。
6. 许可证:TaCZ=GPL-3.0 项目取 GPL-3.0-or-later;Photon 禁改版分发;HandheldMoon=ARR 仅参考行为。
7. 证据自动化(OFF-A/OFF-B 像素基线),每阶段 git tag。

## 路线图
- [x] V0 环境与工程骨架(离线构建验证通过)
- [x] V1 手电筒物品 + IrisItemLightProvider + 开关(代码完成,**待实机验收**:runClient 手持手电筒按 L 验证锥形光)
- [x] V2 TaCZ 枪挂灯附件(taclight:gun_light gunpack + EXTRA_ENTRIES 官方注册 + 探针识别;契约测试 6/6;冒烟:2 个 gunpack 注册成功,**待实机验收**:枪匠台合成→改装界面装上 HK416D→日志出现 gun light ON)
- [x] V3-p1 SSBO 通道(binding 7,std430 96B/灯)+ iterationT 补丁管线(锚点/SHA512/幂等)+ 表面锥光 GLSL(软边+屏幕空间遮挡);契约 18/18;补丁包在 Oculus 加载成功
- [x] V3-p2 体积光束(composite.fsh 16 步 raymarch + 光束遮挡)+ 高光(composite5 GGX);契约 18/18;补丁包加载成功,**待实机验收**:雾中可见光柱、潮湿表面有高光
- [x] V4 枪口精确姿态(BeamRendererMixin 捕获激光骨矩阵→视图空间→场景换算;软依赖插件门控;MuzzlePoseMath 契约测试);契约 29/29,**待实机验收**:装上枪灯后光源随枪口而非视线
- [ ] V3 SSBO 真通道(枪口朝向/体积束/屏幕空间遮挡)
- [ ] V4 打磨(多灯/UI/性能/多人可选)
