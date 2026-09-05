# 2026-09-05 体积光穿墙漏光修复 + 锥角收窄 8°/4° + !cone 旋钮 + knee 默认开 G=2.0

## 需求(用户四点)
1. **体积光穿遮挡**:ObserverB 在墙后,体积光却糊到墙前 Dev 的窗口上 → 必须修,不能穿过遮挡物;
2. **性能开销**:若体积光开销过大就不做 → 要实测数字;
3. **近场眩光**:Dev 站进 ObserverB 的光束内,视野内反光强烈(第二张截图)→ 压眩光;
4. **锥角太宽**:远距离显得照明范围巨大 → 改接近平行光,并出旋钮。

## 改动(四件,全部零重启旋钮化)
1. **漏光修复(composite1.fsh)**:体积光采样原只有"视线深度门"(被实体表面挡住的采样跳过),
   缺"灯侧门"——灯→采样点路径穿墙的空气采样照样过锥角测试 → 墙后雾漏到墙前。
   修复 = 逐采样 `taclight_vox_transmit(L.posRadius.xyz, spWorld)`(与表面照明同一张体素
   网格同一套 DDA),vis≤0.003 剔除;网格无效(−1)回退 visible=1.0(fail-safe)。
   前置便宜门 `spot·atten·ph < 1e-4` 先剔除绝大多数采样,保证 DDA 只跑在有效样本上。
2. **锥角收窄(config)**:CONE_OUTER_DEG 32→**8.0**、CONE_INNER_DEG 18→**4.0**
   (近准直;30m 处光斑半径 18.7m→4.2m)。
3. **`!cone <2..45>`**(第八旋钮):外角(度),内角=外角×0.5,Java 侧重写 cosOuter/cosInner,
   SSBO/GLSL 零改动;off=回 config 8/4。
4. **knee 默认开**:DEFAULT_KNEE_G 0(恒等)→**2.0**(近场软膝出厂即开;`!knee off` 即回 2.0,
   显式关闭语义已并入默认;压近场白爆→bloom 眩光)。

## 契约
LightTune 88→**113** 项 AllContracts ALL PASS + 全套 BUILD SUCCESSFUL
(gradlew-java17 taclightContracts,2026-09-05 复跑):!cone 解析/范围拒绝/状态回显/
off 回 8/4/集成 cosOut=cos6° cosIn=cos3°、knee 默认 2.0 + off 回 2.0、
composite1 灯侧 DDA 调用与 visVox 门 GLSL 源断言、config 8.0/4.0 断言、
收尾零残留。双端 `!reload` 后日志 grep 编译错 = 零(坑105 纪律)。

## 实机判定(场景:41×26 平滑石地板 y=120 + 石英墙 z=11;B 灯水平照墙,Dev 侧后机位)

### ① 漏光消除(同机位同姿态,composite1 新↔旧热交换 A/B,cone 均 32)
| 图 | composite1 | 漏光带 ROI(100,140)-(740,260) 均值 |
|---|---|---|
| leak_OLDbuild_wide | 旧(HEAD,无灯侧门) | **83.4**(墙后雾糊到墙前) |
| leak_NEW_occ_cone32 | 新(灯侧 DDA) | **56.2** = 环境基线,漏光消除 |
| leak_NEW_noocc_cone32 | 旧(对照) | 83.4 |
| 下墙对照区 | 新 vs 旧 | 51.6 vs 55.9(≈噪声,非漏光区零误伤) |
侧视体积光束仍在(side_cone8/32)= **遮挡不杀丁达尔**。

### ② 锥角收窄(新构建,同机位)
| 图 | !cone | 观感 |
|---|---|---|
| side_cone32 | 32(旧默认) | 宽水洗,远距离一片 |
| side_cone8 | off(=8/4 新默认) | 细长楔形束,准直感 |
diag 实测 cosOuter=0.990(=cos8.1°)/cosInner=0.998(=cos3.6°),与 8/4 吻合。

### ③ knee 眩光(7.5m 光池 rig:B(2005,121,8.5) 照墙,Dev(2007.5,121,3.5) 侧后看;
A 端 bright12/atten2/beam0.3,唯一变量 knee)
| 图 | knee | 光池核 ROI(530,164)-(560,186,避开名牌文字行) |
|---|---|---|
| glare_OLDbuild_kneeOFF.jpg | 旧构建无 knee | 白爆成片(用户截图 2 同款) |
| glare_knee2.png | off=G2.0 | mean **189.1** / max **238**(仍饱和近白) |
| glare_knee8.png | 8.0 | mean **106.0** / max **132**(恢复灰,压 1.78×) |
| 对照墙面(600,160)-(700,220) | G2 vs G8 | 48.0 vs 47.9,ratio 1.00(远场零变化,近场选择性) |
注:该 rig 光池半径 ≈0.35m(8° 锥)仅 ~30px,与 B 名牌文字(y155-161)屏上重叠;
判定 ROI 已避开文字行,块级亮度图确认高差异结构=光池盘本身。

### ④ 性能 !bench 四臂(双分辨率复测;每臂 3 遍取中位;RTX 5070 Ti,vsync off)
**2026-09-05 晚,A 端 854×480 窗口(0.41MP),BENCH frames≈3800/3s:**
| 臂 | avgFPS 三遍 | 中位帧时 |
|---|---|---|
| 灯关(两段 lamp 循环整跳) | 1690.4 / 1683.3 / 1697.6 | 0.592 ms |
| 灯开+voxel off(无 DDA) | 1275.5 / 1274.8 / 1275.0 | 0.784 ms |
| 灯开+voxel on+cone8(默认) | 1278.8 / 1272.4 / 1278.9 | 0.782 ms |
| 灯开+voxel on+cone32(旧宽锥) | 1273.2 / 1275.0 / 1274.6 | 0.785 ms |

**2026-09-06 凌晨复测,A 端全屏 3840×2160(8.29MP,20.2× 像素;显示器原生 4K,
系统 2560×1440 系 150% DPI 缩放虚拟值),B 端 854×480 常驻,BENCH frames 213-385/3s:**
| 臂 | avgFPS 三遍 | 中位帧时 |
|---|---|---|
| 灯关(基线) | 128.2 / 128.2 / 128.1 | 7.80 ms(1%low≈110) |
| 灯开+voxel off | 89.6 / 89.4 / 89.6 | 11.16 ms |
| 灯开+voxel on+cone8(默认) | 88.6 / 88.9 / 88.4 | 11.29 ms(1%low≈73) |
| 灯开+voxel on+cone32(旧宽锥) | 70.8 / 70.9 / 70.9 | 14.10 ms |

**结论:TacLight 全部逐像素成本 480p≈0.19ms → 4K≈3.49ms,对 20.2× 像素增长 18.4×
≈随分辨率线性(1080p/1440p 估算 ≈1.0/1.8ms,未单独实测)。用户 4K 真实帧率
(默认 cone 8/4)≈88fps,灯关基线 128fps——60fps 余量充足,144 不达且瓶颈含场景
本身。灯侧 DDA 默认配方 +0.13ms(≈4%);旧宽锥 32° 在 4K 暴露 +2.81ms(88→71fps,
宽锥→更多采样过门→更多 DDA;480p 上此效应为噪声级)。体积光保留,不成瓶颈。**
注意:`!beam 0` 不是"关体积光"(密度只是乘数,循环恒跑)——量总成本必须用灯关基线;
分辨率变更走全屏启动(options.txt fullscreen:true,MC 帧缓冲=显示器原生),验证手段
= `!shot` 截图 PNG 尺寸(3840×2160 实证)。

## 使用建议(给用户)
- 默认即新配方:锥角 8/4、knee G=2.0 出厂即开,无需手动;
- 锥角再调:`!cone <2..45>`(内角自动=半角);近场还眩 → `!knee 4~8`;
- 双端现场已复原成交接配方:两端 bright 12 / atten 2 / beam 0.5 / scat 0.15 /
  knee off(=2.0)/ cone off(=8/4)/ beamcap off / beamonly off / voxel on,
  双灯 on(`/taclight light on Dev|ObserverB`),Dev(2007.5,121,4.4)、B(2005.5,121,8.5)。

## 分析工具备注
ROI/块级图判定用 imgdiff.js decodePng(注意:入参是 Buffer 非 path,需 fs.readFileSync;
返回 {width,height,data} 固定 RGBA)+ 内联 node -e;热交换法 = git show 旧版 composite1
→ syncShaderPack → !reload → 同姿态拍 → 复原。
