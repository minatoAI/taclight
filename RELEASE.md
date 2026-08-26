# TacLight 发布清单(How to Release)

## 1. 构建
```powershell
.\gradlew-java17.cmd clean build taclightContracts --offline
```
产物:build/libs/taclight-0.1.0.jar(版本号在 gradle.properties 的 mod_version)
前置:JDK 17(E:\dshHome\mc-shader-spotlight-dev-qa\.tools\jdk-17 或 TACLIGHT_JDK17)

## 2. 依赖声明(全部 optional)
| 模组 | 版本 | 必需? |
|---|---|---|
| Forge | [47.1,48) | 必需 |
| Oculus | * (建议 1.8.0) | optional(无则物品仍可用,不发光) |
| Embeddium | * (建议 0.3.31) | optional |
| TaCZ | * (建议 1.1.8-hotfix) | optional(无则无枪灯) |

## 3. 光影包(聚光灯真正发光的条件)
模组自带"G 通道"(IrisItemLightProvider),任何实现手持光的包都能亮(球形或锥形)。
**iterationT 3.2.0 完整锥形方案**需要派生包:

```powershell
.\gradlew-java17.cmd patchIterationT --offline
```
- 输入:仓库外 ../iterationT 3.2.0(原包,零改动)
- 输出:run/shaderpacks/iterationT 3.2.0 (taclight)(含 SSBO 表面光+光束+高光)
- 用户须在光影包目录选择该派生包;并在 iterationT 菜单开 HELDLIGHT_MODE=1(体验包内自带手持光)

> 许可证约定:不重新分发改版光影包;发布物只含"补丁描述符+注入 GLSL",由用户本地打补丁。

## 4. 许可
- 本模组:GPL-3.0-or-later(https://www.gnu.org/licenses/gpl-3.0.txt 完整文本)
- 依赖注意:TaCZ=GPL-3.0 / CC BY-NC-ND 4.0;Oculus=LGPL-3.0-only;Embeddium=LGPL-3.0-only
- 参考:光子(Photon)=禁止改版分发;HandheldMoon=ARR(仅行为参考);irl-core=MIT

## 5. 发布前检查
- [ ] gradlew clean build 通过(全离线)
- [ ] taclightContracts 29/29 通过
- [ ] 实机验收清单 docs/03 全部通过
- [ ] mods.toml 版本号与 gradle.properties 一致
- [ ] CHANGELOG 更新
- [ ] git tag vX.Y.Z
