# libs/ — 本地依赖 jar(本项目不重分发)

本目录**不包含任何第三方 jar**。构建/运行需要时,请从各项目官方发布页下载后按**下列文件名**放入本目录
(文件名必须一致:`build.gradle` 以 `flatDir` + `blank:<name>:<version>` 解析)。

| 放入文件名 | 项目 | 版本 | 许可 | 何时需要 |
|---|---|---|---|---|
| `tacz-1.1.8-hotfix.jar` | Timeless and Classics Zero (TaCZ) | 1.1.8-hotfix | `GPL3 / CC BY-NC-ND 4.0`(以官方页为准) | **编译必需**(枪挂灯联动 API);运行可选 |
| `oculus-1.8.0.jar` | Oculus(Iris Forge 移植) | 1.8.0 | LGPL-3.0-only | **编译必需**(`iris.api.v0`);运行可选 |
| `embeddium-0.3.31.jar` | Embeddium | 0.3.31 | LGPL-3.0-only | 仅运行(可选) |
| `player-animation-lib-forge-1.0.2-rc1+1.20.jar` | Player Animator | 1.0.2-rc1+1.20 | MIT | 仅运行(可选,TaCZ 第三人称动画依赖) |
| `freecam-forge-1.2.1+1.20.jar` | Freecam | 1.2.1+1.20 | MIT | 仅运行(可选) |

- 编译只需前两个;缺失时 `gradlew jar` 会在 `checkLocalDeps` 任务给出明确报错。
- 这些 jar 已被 `.gitignore` 忽略(`libs/*.jar`),**请勿提交**——本项目不重分发第三方 jar,原因与逐条许可见
  `THIRD_PARTY.md` 的「依赖许可与再分发」。
