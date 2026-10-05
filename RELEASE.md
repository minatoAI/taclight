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

`InteropPackagingContract` 会直接读 jar 做构件闸门：dev 面泄漏、已删调试类复活、版本号不同源都会判红。
发布前顺手核一遍 `unzip -l` 里的 class 数量与关键条目。

## 3. 运行需求

| 模组 | 版本 | 必需 |
|---|---|---|
| Forge | `[47.1,48)` | 必需 |
| Oculus | 建议 1.8.0 | 客户端可选，没有就没有锥形光 |
| Embeddium | 建议 0.3.31 | 可选，提升帧率 |
| TaCZ | 建议 1.1.8-hotfix | 可选，没有就没有枪灯 |

## 4. 光影包

聚光灯真正发光需要 Oculus/Iris 加一个光影包。运行时注入的已适配模板：

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

## 6. 发布前检查

- [ ] `clean build` 通过
- [ ] `taclightContracts` 全绿（注意它不在 `build` 里，要显式运行）
- [ ] 实机确认：手电（J）与枪灯（M）开关正常，光影包下能看到锥形光
- [ ] `mods.toml` 版本 == `gradle.properties` 的 `mod_version` == 清单 `Implementation-Version`
- [ ] 发布件里没有调试类与孤儿语言键（`InteropPackagingContract` 会拦）
- [ ] 更新 `CHANGELOG.md`
- [ ] `git tag vX.Y.Z`
- [ ] 推送后确认远端只有一条主分支
