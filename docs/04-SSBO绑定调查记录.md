# SSBO 外挂绑定调查记录(Oculus 1.8.0 × iterationT 3.2.0)

> 结论先行:**外部 SSBO(binding 7/9)写入在当前栈上不可观测**,已将枪灯切换为
> **B 通道**(GunItemLightProviderMixin 注入 IrisItemLightProvider + iterationT 内置 FLASHLIGHT)。
> 本文件保留全部证据链与假设排序,供后续(或 Oculus 升级后)重启调查。

## 证据链(2026-08-26,dev 环境)
1. 补丁管线正确:composite.fsh 里注入代码在 [补丁日志 sha 与行级检查] 均在;
2. **include 确已内联进编译产物**:向 taclight_lights.glsl 注入语法错误 → 报错
   "ShaderCompileException: composite ... no viable alternative at input 'SYNTAX_ERROR_MARKER_XYZ\n\n\n\nvec3'"
   (错误上下文 = include 末尾 + composite 的 vec3 SkyLighting,证明内联发生);
3. **无守卫 atomicOr 探针(可被优化的最后手段)**:世界内渲染数秒→回读 reserved 恒 0x0;
4. **绑定未在 tick 时刻丢失**:glGetIntegeri(SSBO_BINDING, 7/9) = 我们自己的 id(266/262);
5. **binding 9(超出 Iris 管理 0-8)相同结果**;
6. **main() 入口直写 reserved = 11u**(经正规补丁 op 注入,L148 验证存在):仍回读 0x0;
7. [过程发现的两个工具级 bug 已修复]
   - 补丁任务 inputs 未声明 patch json 内容 → Gradle up-to-date 误判(mtime 变了但
     args 不变)→ 加 inputs.files(shader_patches);
   - PackPatcher 幂等判断用"文件含 marker"→ 同文件后续新 op 被跳过 →
     改为"按 content 判断"。

## 假设排序(被证据排除/保留)
| 假设 | 处置 |
|---|---|
| 注入代码不在编译产物 | ❌ 排除(证据 2) |
| 绑定被 Iris 按 pass 覆盖 | ⚠️ 部分排除(证据 4,但"保存/恢复"语义仍可能) |
| 编译器 DCE 掉普通写 | ⚠️ atomicOr 不可 DCE(证据 3 仍 0)|
| composite pass 未执行 | ⚠️ 无法区分(main 直写 0;但 plain store 也可能被 transform 层消除) |
| **Iris glsl-transformer 转换层丢掉对 SSBO/我们的写入** | 🔴 最符合全部证据;下一步需要 dump 转换后源码验证 |
| 旧项目(Photon)成功 ≠ iterationT 成功 | 待复核(两者 pass 结构不同;Photon 用了 world0/ 目录) |

## 下一步(若重启)
1. 让 Iris 输出转换后源码(Oculus debug 开关或临时改 log4j 级别);
2. 对比 Photon(在 qa 项目还有 photon_v1.3b.zip)用同一探针;
3. 或改用 Iris 原生 bufferObject.N 声明 + 在 shader 内承接(放弃 Java 外写)。

## B 通道(现行)
GunItemLightProviderMixin:Target = ModernKineticGunItem,注入 IrisItemLightProvider
接口(官方 API 判定:枪上装有 taclight:gun_light→15)。效果上:
- 持枪(装了灯)→ heldBlockLightValue=15 → iterationT 内置 FLASHLIGHT(HELDLIGHT_MODE=1)
- 光型:相机朝向锥光+屏幕空间遮挡(iterationT 自带)——"枪口精确"仍属 SSBO 议题
