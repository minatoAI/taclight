# TacLight

**Forge 1.20.1 战术聚光/手电模组**:锥形聚光灯 + 手电筒物品 + 与 *Timeless and Classics Zero (TaCZ)* 的枪挂灯联动,并为 Iris/Oculus 光影包提供一个**运行时灯光注入引擎**(把模组里的灯以真实光源的方式喂给光影的体积累加/遮挡管线)。

本仓是**公开发布仓**(由开发仓整理导出):
- 只包含**产品代码**(`src/main/java`、`src/main/resources`)、**光影包源码**(`pack/shaders`,41 个文件)、构建脚本与许可/致谢文件。
- **不包含**:第三方 jar(见下「依赖许可与再分发」)、内部调试/自动化面、内部开发记录与历史。
- 发布日期:2026-09-17 ｜ 版本:**v0.10.0** ｜ 许可:**GPL-3.0-or-later**

> TacLight — a Forge 1.20.1 mod adding cone spotlights / flashlights and a gun-mounted light that integrates with Timeless and Classics Zero (TaCZ). It also ships a runtime **light-injection engine** for Iris/Oculus shader packs (feeds mod lights into the volumetric light/occlusion pipeline) plus the full source of the companion shader pack. This is a curated public release repo: product code + shader sources only, no third-party jars, no internal tooling.

---

## 1. 安装

| # | 组件 | 版本 | 必需? | 说明 |
|---|---|---|---|---|
| 1 | Minecraft Forge | 1.20.1-47.1.3(范围 `[47.1,48)`) | **必需** | 服务端/客户端 |
| 2 | TacLight | v0.10.0 | **必需** | 把 `taclight-0.10.0.jar` 放进 `mods/` |
| 3 | Timeless and Classics Zero (TaCZ) | 1.1.8-hotfix | 可选 | 提供枪挂灯联动;缺失时降级(见 `META-INF/mods.toml`,全部 optional + 能力探测) |
| 4 | Oculus | 1.8.0 | 可选(客户端) | Iris Forge 移植;装光影必需 |
| 5 | Embeddium | 0.3.31 | 可选(客户端) | Sodium Forge 移植,提升帧率 |
| 6 | Player Animator | 1.0.2-rc1+1.20 | 可选 | TaCZ 第三人称动画依赖的第三方库;本模组零代码引用 |
| 7 | Freecam | 1.2.1+1.20 | 可选 | 观战/观察用;本模组零代码引用 |

**光影包**:把 `taclight-shaders-0.10.0.zip`(发布件)整个放进 `.minecraft/shaderpacks/`,在 *视频设置 → 光影* 里选择 `taclight-shaders-0.10.0`。它是构建自本仓 `pack/shaders/` 的派生包(补丁模板见 `src/main/resources/shader_patches/`)。

> ⚠️ 光影只在**客户端**生效;无 Oculus/Iris 时模组本体仍可用,但没有体积光效果。

## 2. 性能说明(实测数字,如实标注口径)

以下为**开发机实测**(8 灯场景、旧测试台,2026-09),**不是**发布验收数字;数值随硬件/光影包/场景变化:

| 优化 | 状态 | 实测口径 |
|---|---|---|
| ① **灯全灭早退**(灯全灭时跳过整支体积光 raymarch) | **已随本版发布** | 上界 **≈35 FPS(≈9.8%)**:`a2`(密度 0,8 灯)=365.7 ≈ `a`(全灭)=366.3 ≫ `b`(8 灯 64 步)=330.5。**是上界**,不是承诺值 |
| ②a **体积光遮挡查表**(方案二,`!occl`) | **已随本版光影包发布** | 2026-09-06 开发机 4K 双灯实测:最坏视角 38.4→65.4 FPS(+70%)、其他视角 +5.9~+9.3 帧、同机位 +58%;画面差异 0.16% 像素(meanDiff 0.894/255)。**是特定机位实测,非普适承诺** |
| ②b **体积光时间复用**(`!tm`,默认开) | **已随本版光影包发布** | 2026-09-17 开发机实测:**+21.9 FPS(方向正确;量级随场景变化,不作承诺)** |
| ③ **8 灯遮挡查表**(`row_b1`) | **未随本版发布** | 收益未证实:ms 口径 N=8 从 2.880 → 2.949 ms/帧(**+0.069 ms,即慢 2.4%**);N=6 仅 +0.006 ms;N=4/5 在噪声内。判定为小负收益,故不进本版 |

## 3. 从源码构建

```bash
# 依赖(JDK 17):
#   1) Minecraft Forge 1.20.1-47.1.3(由 ForgeGradle 自动获取)
#   2) 自行下载 TaCZ 与 Oculus 的 jar 放入 libs/(本项目不重分发,原因见 THIRD_PARTY.md)
#      libs/tacz-1.1.8-hotfix.jar
#      libs/oculus-1.8.0.jar
#      (运行游戏时另需: embeddium-0.3.31.jar / player-animation-lib-forge-1.0.2-rc1+1.20.jar / freecam-forge-1.2.1+1.20.jar)

./gradlew jar              # 产物: build/libs/taclight-0.10.0.jar
./gradlew packShaderZip    # 产物: build/distributions/taclight-shaders-0.10.0.zip
./gradlew build            # 全量构建
```
- `libs/` 下缺 jar 时,构建会在 `checkLocalDeps` 阶段给出**明确的缺件报错**,而不是编译期的 "package does not exist"。
- 本仓 `libs/` 只有一份说明(`libs/README.md`),**没有任何第三方 jar**。

## 4. 已知限制(如实)

1. **像素级"灯亮"未做机器验证**:本模组的可见性/亮度结论来自实机肉眼与结构化日志,没有像素级自动化断言。
2. **未与官方启动器逐字节对照**:发布 jar 在开发环境(dev 与生产映射)实机运行通过,但没有与官方启动器的分发链路做逐字节对照。
3. **③(8 灯遮挡查表)未发布**:见上表 —— 8 灯下反而慢约 2.4%,收益未证实。
4. **体积光参数为手动调参**(亮度/距离/衰减等),没有自动适配方差。
5. **内部验证台未随源码发布**:`src/test/**` 与内部契约测试属于开发验证台(其中还覆盖了未发布的③),**不在本仓**;因此本仓没有可直接运行的自动化测试。
6. 光影包仅在 **Oculus/Iris** 下有效;不同光影包的补丁模板需要匹配版本(本仓提供 iterationT 3.2.0 与 Complementary r5.9 模板)。

## 5. 许可与致谢

- 本项目:**GPL-3.0-or-later**(`LICENSE`;全文见 `LICENSE-GPL-3.0.txt`)。
- 光影包(`pack/shaders/**`)与模组本体同一许可;发布 zip 内不附许可正文,许可与署名以本 README 与发布页(Modrinth)说明为准。
- **本项目不重分发任何第三方 jar**;5 个依赖的许可与再分发口径见 `THIRD_PARTY.md` 的「依赖许可与再分发」一节。
- 上游思想借鉴(均**零逐字复制**)、Minecraft/Forge 的链接使用:见 `THIRD_PARTY.md`。

## 6. 目录结构

```
src/main/java/dev/taclight/     产品代码(64 个 .java)
src/main/resources/             资源:mixin 配置、枪灯枪包、光影补丁模板与内联 core(77 个文件)
pack/shaders/                   光影包源码(41 个文件,含 lib/taclight_core.glsl 等单一真源)
pack/pack.png                   光影包图标(packShaderZip 用)
gradle/wrapper/ + gradlew*      标准 Gradle Wrapper(8.1.1)
build.gradle                    构建脚本(已按公开仓改编:移除内部调试面相关机制)
```
