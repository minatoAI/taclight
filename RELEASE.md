# 发布清单（How to Release）

## 1. 构建

```powershell
.\gradlew-java17.cmd clean build taclightContracts --offline
```

产物：`build/libs/taclight-<mod_version>.jar`（版本号单一真源 = `gradle.properties` 的 `mod_version`）。
前置：JDK 17。可用 `TACLIGHT_JDK17` 指定路径。

注意：`taclightContracts` 目前**没有接进 `build`**，必须像上面这样显式带上。只跑 `build` 不会执行契约。

## 2. 发布件边界

发布 jar 由 `build.gradle` 的剔除规则决定，必须不含：

- `dev/taclight/debug/**`、`dev/taclight/mixin/debug/**`、`dev/taclight/devonly/**`
- `dev/taclight/client/DebugCommandRelay*`
- `taclight.debug.mixins.json`、`taclight.dev.mixins.json`

同时必须**含**玩家可用的功能面 —— 其中 `/taclight tune` 命令族
（`dev/taclight/command/TacLightCommand`、`dev/taclight/tune/TuneService`）在 2026-09-29
曾被按目录误剔除（用户实测发布件里无此命令），2026-10-06 修回并加了正控闸门。

`InteropPackagingContract` 会直接读 jar 做构件闸门：dev 面泄漏、已删调试类复活、版本号不同源、
以及**发布面命令族缺失**都会判红。发布前顺手核一遍 `unzip -l` 里的 class 数量与关键条目。

## 3. 环境

发布件本体只硬依赖 Forge（`[47.1,48)`）。Oculus 与一个已适配的光影包是锥形光生效的前提，模组不自带光影包。

实测通过的版本组合见 README 的「测试环境」一节，那里是唯一维护处。

## 4. 光影包

聚光灯真正发光需要 Oculus/Iris 加一个光影包。**模组不自带光影包**（2026-10-05 用户定案放弃自研包路径：观感不达标，一律注入第三方包）。

运行时注入的已适配模板：

- Complementary Reimagined r5.9（主线）
- iterationT 3.2.0

注入在运行时完成，模组只带自研 GLSL 与补丁描述符，不重新分发任何第三方光影包。

旧路线（离线派生包，仍可用但已被运行时注入覆盖）：

```powershell
.\gradlew-java17.cmd patchIterationT --offline
```

- 输入：仓库外的 iterationT 3.2.0 原包
- 输出：`run/shaderpacks/iterationT 3.2.0 (taclight)`
- 用户须在光影包目录选择该派生包

> 许可约定：不重新分发改版光影包；发布物只含补丁描述符与注入 GLSL，由用户本地打补丁。

## 5. 许可

- 本模组：GPL-3.0-or-later（`LICENSE`，全文 `LICENSE-GPL-3.0.txt`）
- 依赖注意：TaCZ = GPL-3.0 / CC BY-NC-ND 4.0；Oculus = LGPL-3.0-only；Embeddium = LGPL-3.0-only
- 参考：Photon 禁止改版分发；HandheldMoon 为 ARR，仅参考行为

## 6. Release 正文口径

GitHub Release 的正文只写**版本之间的变更点**（不兼容变更 / 修复 / 清理），从 `CHANGELOG.md` 顶部条目提炼，控制在十几行内。

不要在这里重复 README 已经写好的内容：安装步骤、运行需求表、许可、目录说明、从源码构建。那些只维护一份，放在 README；Release 正文末尾一句"安装与运行需求见 README"即可。

理由：这些信息在 Release 里再抄一遍，就会在改版本时出现两处不一致，而且页面过长反而看不到真正的变更。

## 7. 发布前检查

- [ ] `clean build` 通过
- [ ] `taclightContracts` 全绿（注意它不在 `build` 里，要显式运行）
- [ ] 实机确认：手电（J）与枪灯（M）开关正常，光影包下能看到锥形光
- [ ] `mods.toml` 版本 == `gradle.properties` 的 `mod_version` == 清单 `Implementation-Version`
- [ ] 发布件里没有调试类与孤儿语言键（`InteropPackagingContract` 会拦）
- [ ] 更新 `CHANGELOG.md`
- [ ] Release 正文只写变更点，不重复 README 内容（见第 6 节）
- [ ] `git tag vX.Y.Z`
- [ ] 推送后确认远端只有一条主分支
