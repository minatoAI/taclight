# 证据包 · 色调管线 v2(2026-08-30):参考对比 + 消融实验 + AgX/线性域修复

起因:用户实机验收阶段二(铁块高光 ✅)后反馈"画面色调有点别扭",要求对比借鉴
iterationT(必要时其他允许借鉴的开源光影),用"拆解代码 + 消融实验"找其观感好的本质。

## 0. 许可结论(先于一切借鉴)

| 对象 | 事实(查证) | 结论 |
|---|---|---|
| iterationT 3.2.0 | 包内**无任何许可文件**(全量 find 无 license/readme/协议);MineBBS 标"转载";镜像站互相矛盾(XyeBBS 称 GPLv3 / BBSMC称保留所有权利);作者经 Bilibili/MGC 中文图形站分发 | 默认**保留所有权利**。只读学习思想/公式(版权不保护思想),**不搬代码不搬资源**。用户回忆其 SEUS 血统一说:SEUS EULA(sonicether.com)明文"仅限个人修改、禁止任何再分发",衍生包权利状态更差,远离 |
| E-LITE 5.1.1 | Modrinth 页面标 **LGPL-3.0-or-later**(已核实) | 可合法读码借鉴(注意 LGPL 义务);用户已自行对比,观感不如 iterationT → 仅辅助参考 |
|本项目纪律| lib/taclight_style.glsl 头注:"只使用公开数学公式,0 行第三方 shader 代码" | 继续执行。AgX 为公开数学自实现,矩阵逆由数值求逆得出 |

## 1. 对比帧(同机位 wall@front,noon/midnight + clear)

| 文件 | 内容 |
|---|---|
| itt_day_wall.png / itt_day_open.png / itt_night_base.png | iterationT 原版(自绘大气/云/玻璃反射) |
| fork_day_wall.png / fork_night_light.png / fork_neon_probe.png | iterationT(taclight) fork(8/26 旧作,见 §4) |
| ours_day_wall.png / ours_night_light_B0.png | 我方修复前基线(ACES 旧管线) |
| abl_B1..B5_*.png | 消融:逐个关 split-tone/暗角/颗粒/bloom/ACES |
| fix1_*.png / fix2_*.png | 修复 v1(欠亮)/ v2(标定后,当前提交态) |
| fix2_dbg4.png | DBG4 光束视图(×6+γ0.45 显示增益,量测用) |
| fix2_night_on_a/b.png, fix2_night_off.png | 静帧对 + 开关灯 A/B |

## 2. stylemetrics 量化(SAT 饱和 / SHADOW 暗占比 / CONTRAST 中央80%RMS / TEMP 冷暖)

| 配置 | SAT | SHADOW | CONTRAST | 判读 |
|---|---|---|---|---|
| 我方夜 B0(旧) | 0.290 | 0.785 | **0.298** | 反差硬,元凶之一 |
| 消融 −split-tone | 0.230 | 0.793 | 0.286 | split-tone 抬 SAT+0.06、蓝移 TEMP+0.08 |
| 消融 −bloom | 0.284 | 0.791 | 0.226 | bloom 次要贡献 |
| 消融 −ACES(直通) | 0.323 | 0.859 | **0.179** | **色调算子是反差主源**(−0.11) |
| fork 夜(参考) | 0.337 | 0.935 | **0.092** | 参考:暗而柔 |
| 我方夜 fix2(新) | 0.246 | 0.788 | 0.254 | 亮度保持(SHADOW 持平),柔化+去蓝移 |
| 我方昼 B0(旧) | 0.105 | 0.091 | 0.269 | 发灰发紫、玻璃死白 |
| itt 昼(参考) | 0.118 | 0.068 | 0.176 | — |
| 我方昼 fix2(新) | 0.307 | 0.161 | **0.189** | 反差对齐参考带;天空恢复原版饱和蓝 |

## 3. 根因与修复(管线 v2)

消融+读码(iterationT 结构对照)定出四个根因,全部修复:

1. **加法域错误**:M1 光照在 gamma 域加进原版画面,final 再整体 pow(2.2)——
   叠加贡献随底亮度非线性、彩色光斑色相偏移。修:composite 入口统一线性化
   (colortex0 契约改线性,见 taclight_gbuffer.glsl 头注),final 不再 pow(2.2)。
2. **色调算子**:ACES(Hill) 中间调反差硬 + 高光偏暖。修:**AgX**(公开 minimals
   数学自实现:前向矩阵列和=1 中性保持、逆矩阵数值求逆、S 型对比多项式);
   EV 窗口 ±6(发布默认 ±12.47 面向场景参照 HDR,我们是显示参照输入)。
   TACLIGHT_TONEMAP=1 可切回 ACES(A/B 逃生门)。
3. **bloom 域**:阈值 0.55 是 gamma 域亮度 → 白天整片天空进 bloom → 泛白雾、
   玻璃死白块。修:线性域 1.0 软阈 ±0.4,只提真高光;增益 0.35/0.55→0.18/0.30。
4. **split-tone 全幅**:1.0→0.35(量化见 §2);后置饱和旋钮 +0.05 补偿映射压饱和。
   另:暗角 0.78→0.85、颗粒 0.035→0.025;LIGHT_GAIN 1.0→2.2 与 BEAM_GAIN
   1.4→0.5 为线性域换算后的同观感重校(fix1→fix2 实机标定)。

## 4. 附带发现:iterationT(taclight) fork(8/26 旧作)现状

- `run/shaderpacks/iterationT 3.2.0 (taclight)` 是完整的 SSBO 注入
  (Lib/taclight_lights.glsl:Burley+其屏幕空间阴影+GGX+16 步束;mod 侧会自动检测并提示)。
- **SSBO 通路活着**(fork_neon_probe.png:霓虹绿锥正常),但真实光路不可见
  (昼/夜均无光斑)——疑似其 gbuffer.normalL 约定与我方 dirToFrag 不匹配,ndl 恒 0 被
  门拒。支线,记录待查;fork 仅本地(gitignored),不入手库。
- **零重启换包(新工具发现)**:`sed oculus.properties 的 shaderPack= 行 + !reload`
  即可切包——Iris.reload() 会重读 oculus.properties(日志 15:43:23 "Using shaderpack"
  实证)。本次实验全程零重启。

## 5. 验收门判定(如实)

| 门 | 结果 | 说明 |
|---|---|---|
| W1 手性契约 | ✅(未触碰) | right() 路径无改动 |
| 光束几何(DBG4 side) | ✅ **cx=0.5264 ∈ [0.49,0.58]**,cy=0.502 | 外观无关的光路基准:光斑落带正确 |
| W2 final 质心 | ❌ cx=0.4541 | 根因:AgX 下玻璃按真实透射率变暗(旧 ACES 玻璃死白把质心拉右)+软肩展宽;且消融显示该测量随外观旋钮摆动 ±0.05+(B1/B4=0.52)。**标尺需换基准:W2 改以 DBG4 光束为准**——审批事项,未擅自改 |
| W3 align dx | ❌ -0.0723(容差 0.05) | 同 W2 根因(终帧质心 vs 光束质心之差即玻璃权重);dy=-0.013 PASS |
| N6 穿墙 | ✅(未触碰) | SSO 路径无改动;fix2_night 墙后全黑可见 |
| 静帧稳定 | ✅ meanDiff **2.46**(3s 对,晴) | 无闪烁(雨天旧本底 6.6;新 bloom 阈值线性域后更稳) |
| 开/关灯 A/B | ✅ meanDiff 23.08,changed 23% | 光照效果量级与阶段二(24.4)持平 |
| W7 bench | 未跑 | 风格链成本与旧链同量级(一次 pow 换一次 pow);需要时补 |

## 6. 剩余差距(诚实清单,不属本次)

- iterationT 的天空/体积云/大气透视/玻璃反射来自**完整渲染器**(deferred×6+GTAO+
  自绘方块光/太阳阴影);聚光灯附件包不追这个体量。白天天空的饱和蓝 = 原版天空色
  过干净色调映射的真实颜色,不再是"洗白发紫"。
- 曝光自适应(TACLIGHT_EXPOSURE_LOCK 当前=1 锁定)的夜间自动标定:后续单独小步。
- fork 真实光路修复(见 §4)——若修好可获得"同一灯光在两条管线下的直接对照"。
