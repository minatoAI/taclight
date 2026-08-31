# TacLight Changelog

## 未提交 · 09-01 深夜②(snap+pred 实施 + 同输入 A/B 实机对比,用户批准)

- **RemoteBaseSnap(snap+pred,用户批准"尝试一下")**:基角 = 延迟一段快照插值
  (C_k 到达后 [t_k, t_k+50ms] 播 C_{k-1}→C_k,只依赖自用 C 历史、不碰 O/C 对 →
  构造上位置连续,消源1;预测臂两级 EMA τv=0.15s→τout=0.08s 保留方案A 超前,消源2)
  + 传送级双钳制 + 2s 陈旧重置。旋钮 `!bsnap <on|off>`(默认 on,off 退回旧管线)、
  `!extrap` 仍控超前。RemoteBaseSnapContract 25 项;**AllContracts 395 项 ALL PASS**。
- **同输入 A/B 实机对比**(双端重启,同序列 /tp 步进 ×15° 各一轮):jumpMax 平均
  **5.58°→1.82°(-67%)**、最差 5.80°→1.88°;静止尾段误差最差 **4.44°→0.75°(-83%)**;
  errP2P 6.15°→5.40°(滞后未回退)。活体行为:到达不跳/50ms ease/静止 base≡hC 恒等
  全按契约。round2 首会话 64° 峰 = 测试序列自身跨 ±180 回绕伪影(传送级钳制正确处置,
  0.5s 收敛,统计剔除)。证据 evidence/2026-09-01-snap-pred-fix/(README+1822 行原始
  信号+manifest)。**待用户真鼠标体感验收;`!bsnap off` 一键回退。**

## 未提交 · 09-01 深夜(非对称确认:用户双向实测 + !mcap 真鼠标采集分析)

- **用户双向实机对照**:B 看 Dev(开灯转动+移动)= 光晕移动时边缘明显闪烁;
  Dev 看 B = 无类似闪烁。同 shader 同渲染链仅交换被看者结果相反 → 闪烁定位在
  观察端"远程玩家姿态重建"同步链(B 走真实 socket;A=LAN 主机同进程),渲染/光影无罪。
- **!mcap 无人值守采集实战**:用户测试全程自动抓取 s0005-s0012 共 7 个有效信号会话、
  6291 帧 LOOKTRACE、4683 张截图,静止自停 100%,全程方案A 默认 1.25。
- **真鼠标消融数字**(tools/lookreplay.js,墙距 6.5m):现行管线纹波 **3.2-11.2°**
  (墙面晃动 36-127cm)、单帧尖峰至 15.8°;候选对比:chaser/chaser0 纹波最低但滞后
  -6~-16° 淘汰;snap 无超前回退滞后问题;**snap+pred(基角跟 C 快照 + 方案A 超前保留)
  稳定砍纹波 52-69%、尖峰约减半** → 提案实施对象,参数以本批真实向量为 TDD 契约向量。
  O-滞后全 0%;#11 慢扫纹波 0 但仍单帧 6° 跳变(尖峰与纹波为两种可感症状)。
  证据 evidence/2026-09-01-asymmetry-confirm/。**修复实施待用户批准。**

## 未提交 · 09-01 晚(运动门控采集开关 !mcap + 灯态跨 relog 持久化)

- **!mcap(用户指令:开关布防→动则采集、静则自停)**:MotionCapture 纯 JVM 状态机
  (35 项契约)——被观察角色朝向/位置超阈(yaw/pitch/pos=0.3°/0.3°/0.02m,参考采样
  100ms)开会话,静止 800ms 自动收窗,单会话 1200 张上限翻转,目标切换重参考,
  ±180° 环绕安全;`!mcap [on|off|yaw=|pitch=|pos=|ref=|still=|fps=|max=]`。
  LookTrace 增 on 门控模式 + posO/posC 字段(尾置,lookreplay 向后兼容)。
  截图 = RenderTick END 进程内 Screenshot.grab(帧末主缓冲,免前台窗口,坑34 旁路),
  60fps 节流 + ioPool 积压跳帧;落盘 <gameDir>/mcap/s%04d/screenshots/。
  实机:8 次 /tp 步进 → 4 会话/473 行信号/346+103 张 PNG/静止自停 100%,
  imgdiff 光斑摆动 bbox 可见;证据 evidence/2026-09-01-motioncap/。
  用途:用户真实鼠标轮次(方案A on/off 三档对照)自动采集,不再盯秒表。
- **灯态持久化(用户 bug:退出灯开、重进灯灭)**:根因 = 灯开关真源在 SynchedEntityData
  (实体实例字段),重进服务端重建玩家实体回落默认关,无持久化层。修复 = serverApply
  (唯一服务端写口)落玩家 Forge persisted NBT 子树(PERSISTED_NBT_TAG,跨 relog 且跨
  死亡克隆)+ PlayerLoggedInEvent 重放(实体数据 + SyncLightS2C 回包一并恢复,
  本地状态零新增包跟随)。LightStatePersistenceContract 11 项;实机:B 重登
  零命令 → login-restore handheld=true + 本地 ACK(03:27:10/11)。

## 未提交 · 09-01(闪烁消融工具链 + 受控实验:探针/分析器/机制判定/方案分析)

- **工具链(用户指令:消融实验定位根源)**:①`!looktrace` 逐帧全量角度链路探针
  (LookTrace + LookTraceContract,AllContracts 全绿);②`tools/lookreplay.js` 消融分析器
  (raw/lerp/lerp+pred/snap/snap+pred/chaser 七配置回放对比;纹波=去趋势残差峰峰、
  边界跳变、20Hz Goertzel、O-滞后分布;自测 8 项 PASS)。证据
  evidence/2026-09-01-looktrace-ablation/。
- **受控实验**(僵尸追村民连续转动 4.2s/-109°):O-滞后 61/61 边界全零 → 连续转动下
  O/C 对良性,源 1(每边界锯齿)未在 zombie 复现,触发条件收敛到玩家实体特有因素
  (双通道/±180 邻近,用户锯齿恰在 yaw≈±180)——待对远程玩家采集钉死。AI look-snap
  单 tick 达 16.51°;**snap 快照插值 jumpMax 16.51→0.43°(33×)**;临界阻尼追随器因
  恒速滞后 2v/ωn 被自测淘汰。
- **方案分析**:推荐 **snap+pred**(自己的 C 历史分段线性播放 + 速度 EMA×超前)——
  同时免疫源 1(O/C 异常与样本台阶)与源 2(纹波),保留超前;B(只慢化 τ)不解决
  源 1。**实现待用户批准**;下一步=mp-session 对远程玩家真鼠标采集钉死源 1 + 实测矩阵。
- **坑43 入册**:中继按批消费(整个文件同 tick 执行,# 零延迟)→ 序列必须逐行写入
  ≥500ms 间隔;多会话拼接会伪造假边界(分析器已分会话);maxFps=260=原版"无上限",
  实测裸跑 1788fps。

## 未提交 · 08-31 深夜(边缘闪烁再诊断 — 修正"拓扑不对称"结论;实测两抖动源)

- **用户反驳成立**:拓扑不对称只能解释**滞后差**(B 端慢/dev 端快),解释不了**光晕边缘
  闪**;且坑36 修复后闪烁曾确认"基本消失",方案A 之后又出现。复挖用户体感会话日志
  (run*/latest.log,`!extrap log on` 已留痕 21:06-21:39,全部真鼠标数据),实测**两个
  独立抖动源**;分析脚本+原始样本存 evidence/2026-08-31-flicker-rediag/。
- **源1 基础角 20Hz tick 边界锯齿(先于方案A 存在)**:坑36 同源插值后的 base 角在 tick
  边界前跳 +2~3° 又回退 ~2°,周期精确 50ms(rotLerp(pt,O,C) 理论连续,实测违背 → 疑原版
  O/C 更新时序或 RotateHead+Move 双通道叠加,机制待探针钉死)。**与原版头部渲染同公式 →
  头部模型同步微振 = 用户看到"头/晕同步"的原因;头部纹理掩蔽、光斑软边缘放大。**
- **源2 方案A ext 纹波(08-31 新增)**:ext=EMA(20Hz 量化 ω̂×ticks),τ=80ms 每 tick 收敛
  46% → 20Hz 台阶近直通。实测 dev 端默认 1.25 稳态扫视 ext 峰峰 **3.52°**(21:38:47 段,
  翻转 1.9Hz=真摆动);B 端因 21:07:37 误设 `!extrap 2.0` 放大到 **8.71°**。既往验证漏检
  原因:静止(ext=0)/直走(ω=0)/tp 阶跃(风暴淹没)/慢转(锯齿∝转速)全踩不到连续快扫。
- **换算**:6.5m 墙面 1°≈11cm → dev 端 3.52° ≈ 40cm 边缘晃动/半秒,可见闪烁实锤。
- **修正**:08-31 晚"拓扑不对称非缺陷"仅对滞后成立,对闪烁不成立。
- **待办**:①2 分钟决定性对照(dev 中继 `!extrap off` → 真鼠标快扫,预期闪烁减但不消);
  ②修复提案(光斑方向解耦平滑,吸收锯齿+纹波、保留超前补偿;契约用本次实测 ω 序列做
  测试向量)——**等用户批准后动工**。

## 已提交 8c3105c · 2026-08-31 晚(体感首轮反馈诊断补录 — 无代码变更,知识回写项目文档)

- **用户体感反馈**:B(observer)看 dev 灯转动/移动仍闪,dev 看 B 正常;提问"是否只对
  dev 做了优化"。**此诊断此前只进了 AI 会话私有记忆、未进项目文档,新会话查不到只能
  从零重推,交接低效 —— 教训入 AGENTS.md(收尾知识回写纪律),结论必须落 CHANGELOG。**
- **诊断结论(非新缺陷、非单端优化)**:双端同源码/同光影包/同配置,不存在单端优化;
  差异全部来自 **LAN 拓扑不对称** —— dev=LAN 主机,集成服在同进程,B 的实体数据零
  网络延迟直达,dev 看 B 天然"正常";B 走完整同步链(20Hz 打包→tick 转发→插值),
  **B 端才是方案A 的真正考验场**。快甩急停过冲 ~4.5°/250ms = 方案A 已知代价
  (EMA 起停平滑),非回归。
- **干扰因素**:排障发现 B 端曾被中继执行 `!extrap 2.0`(放大过冲);旋钮不持久化,
  重启回默认 1.25。
- **待用户判定**:三档对照(B 端中继 `!extrap 1.0` / `!extrap 1.25` / `!extrap 1.75`)
  + 定性"位置晃 vs 亮度闪";都不满意 → 立项"急停过冲收敛"改进(先提案)。

## 已提交 4451861 · 2026-08-31(用户复测:闪烁已消;新发现转动滞后 → 方案A 预测外推)

- **远程灯转动滞后诊断**:B 眼里光斑转动滞后 A 本地视角 ~100-250ms = 原版实体同步链固有
  (A 20Hz 打包 + 集成服 20Hz tick 转发 + B 端 lerpSteps=3 渐近收敛 + 渲染 1-tick 插值窗);
  修闪烁前同延迟被 20Hz 台阶抖动掩盖,平滑后暴露为滞后 —— 同一数据两种症状,非修复引入。
- **方案A(用户批准)→ RemoteLookPredictor(新,纯 JVM 可测)**:同源角速度外推
  extrapTicks×ω̂(ω̂=O→current/tick,与渲染同源零新包);双层钳制(ω̂≤20°/tick、|ext|≤12°)
  + 外推量 EMA τ=80ms(稳态无损、起停平滑防回弹/防 20Hz 台阶);状态按实体 id,2s 未见即清。
  默认 1.25 tick;**运行时调参 `!extrap <0-3|off>` + 校准日志 `!extrap log on|off`**
  (bang 命令;/taclight 是服务端路由,管不到 B 的客户端状态 —— 坑40)。
  接线:collectRemoteLights(远程手持+枪灯共用预测方向);静止 ext=0 行为不变。
- **实机验证**(证据 docs/evidence/2026-08-31-extrap/):契约 15 项全绿;静止
  extrap off/on 像素恒等(changed 41/409920=0.01%);/tp 阶跃扫掠 ext 峰值 10.17°<12°、
  急停 ~250ms 平滑单调回落、符号正确;直走 2.5s 光斑稳定无噪声(走直线⇒ext=0,与
  用户已验收行为逐位一致)。待用户体感验收:A 转视角 B 看光斑跟随。
- **排障三坑入册(39/40/41)**:坑39 场景预置自带蜘蛛推玩家下平台 + **死亡玩家实体不被
  跟踪 → 远程灯假消失**(预防:创造/清怪/关生成);坑40 relay `!light` 只切本地不上报
  服务端(已修补 sendSetLight;服务端真源一律 /taclight light);坑41 后台 PostMessage
  合成鼠标点击对 GLFW 屏界面(死亡界面)无效,键盘消息可以 → 自动化救援不可行靠预防。
- `!diag` 增 DIAG-REMOTE 探针(syncReady/accessor id/各玩家 flash 标志)。

## 未提交 · 2026-08-30 深夜②(用户实机复核两反馈:双灯仍过亮 + 远程移动闪烁)

- **P1 双灯过亮 → 多源感知肩部(composite.fsh + taclight_common.glsl)**:渲染方程保持
  线性叠加(超叠加原理),感知压缩放显示域 —— `taclight_shoulder3`:x≤T 逐像素恒等
  (单灯不变),x>T tanh 收敛,上界 (1+q)·T。T=0.55(锚定单灯名义核心,DBG8 实测标定),
  q=0.15。实测:双开/单开显示峰值 248.1/247.4(旧 255 且白斑 4.3×),均值比 1.23×,
  饱和像素双双归零(8329→387 / 35605→481),砖缝纹理全程可读。
- **P2 远程移动闪烁 → 坑36 + 坑37**:
  坑36 ClientSpotlightUploader:远程灯方向 getLookAngle() 是 20Hz tick 瞬时值,与模型
  渲染插值脱 sync → 采集端改 rotLerp/lerp 与渲染同源;
  坑37 composite3:移动光斑扫过 bloom 软阈值带被 smoothstep 放大(F6 同族,成员=表面
  光斑)→ 大半径辉光对上一帧 EMA(α=0.6),历史存 colortex6(唯一未占用槽)。
- 回归:带修复直走连拍残差 0.03(静止基线同级)、零翻转;静态双朝向 dir 校验正确。
  附注:一次重启后移动窗口出现"方向瞬时指反"未复现,留观。
- 工具:capture-burst.ps1 加 -ProcId 选窗 + 自动最小化其他 MC 窗(坑34 连拍版);
  坑38 入册(连拍前 /weather clear + 目检首帧,雨丝残差虚高 5×/空视野=空采样伪结果)。
- 证据:docs/evidence/2026-08-30-m5-interfere/(n 三态 + burst 连拍代表帧)。

## 未提交 · 2026-08-30 深夜(M5 附加:双光源同点叠加 interfere 判定)

> 用户问题:两个玩家的光同照一点,会不会亮度过高?结论:**不会失控过曝** ——
> 叠加近似物理相加(线性域中位 0.957),核心饱和面积 5.5%→23.5%(局部白斑扩大,
> 中低亮区纹理可辨),knee+ACES+曝光锁约束下有界,无全局泛白。证据
> docs/evidence/2026-08-30-m5-interfere/(终帧+DBG8×1 三态、lumastats 量化、README)。

- **摆位**:wall 场景双玩家显式 yaw/pitch 同瞄 (2000,122.4,1);A 中继一点控制双灯
  (`/taclight light on|off [player]`);状态序列经服务器 LIGHT-SYNC 日志逐条核对。
- **取证**:双客户端截图改走 F2 postkey(坑34:双窗重叠+DPI 使 CopyFromScreen 抓错窗);
  DBG8 增益临时 ×6→×1 暴露重叠核心(测后还原,W3 标定不动)。
- **坑33/34/35** 入册:/tp facing 脚底锚点偏航;F2 自截帧通道;多态对比常量像素陷阱
  (必须"两灯贡献均>阈值"真重叠过滤 + 空间分布,禁全图聚合)。
- **新工具** tools/lumastats.js(亮度/饱和统计,--bbox/--exclude 扣常量 UI)。
- 开放点(不阻塞):线性域局部簇超和 +10~30%、核心 1.86×强侧单开(>knee 模型 1.40×),
  模型缺项待单客户端 duo 场景隔离;立项条件 = 实际画面出现不可接受的过亮。

## 未提交 · 2026-08-30 晚(M5 双客户端 LAN 联测通过)

> 首次真实双端验证。结论:**通过** —— 观察者实时渲染主机手电(表面光斑+光束),
> 开关灯实时跟随。证据 docs/evidence/2026-08-30-m5-lan/(视觉对照 + SSBO 计数 + imgdiff)。

- **坑32 根因(字节码级确认)**:原版 1.20.1 `IntegratedServer.initServer()` 硬编码
  `setUsesAuthentication(true)` —— 局域网加入者须过 Mojang 会话验证,dev 第二客户端
  无会话必被 "Invalid session" 踢(约 2 秒,两侧日志无声)。修复 = `DevLanAuthHook`
  (仅 `-Dtaclight.dev.disableLanAuth=true` 时关闭集成服验证;dev run 配置携带,
  生产不带属性、行为与原版一致)。
- **坑31 附带修复**:`--quickPlayMultiplayer` 直连无 status ping,客户端把 Forge 服
  误判 vanilla → 通道谓词改 `NetworkRegistry.acceptMissingOr`(同时支持单侧装 mod 降级)。
- **工具链六连修(坑29/30)**:mp-session 中继无 BOM 写入+端口正则对齐;
  drive.ps1 `-ProcId` 按进程选窗 + present ALT 解锁前台锁;FG6 clientObserver
  `parents` 继承 client(未知 run 名不配主类);observer oculus.properties 须在
  `run-observer/config/`。
- 量化:双端 /list=2;B 端 ssbo count 随 A 开关灯 1→0→1 实时跟随;
  b_on/b_off 帧差 changed=10823px(光束区),maxDiff=220。

## 未提交 · 2026-08-30 傍晚(W2/W3 标尺换基准:几何判定改 DBG4/DBG8,终帧质心降级)

> 用户批准坑26 提案:"把 W2/W3 的几何判定基准改成 DBG4 光束"。当日落地并实机取证。

- **新增 DBG strip 8**(表面光直读):`final.fsh` 直读 colortex0(M1 照明,无光束/bloom),
  ×6+γ0.45 显示增益与 DBG4 同款(只影响诊断显示);与 strip 4 构成 W3 的风格无关双腿。
- **acceptance.ps1**:align 新增 `-LumaThA/-LumaThB` 每腿阈值(W3 配方:A=DBG8 饱和核心
  253 / B=DBG4 光束 110;253→254 实测 Δcx=0.0006 阈值不敏感);frac 空判定护栏改用
  各腿阈值(堵住"A 腿空核心仍绿灯"的洞)。TOOLS-SELFTEST 全绿。
- **§7 规格变更**:W2=`side` on DBG4(✅ cx=0.5268,带 [0.49,0.58]);W3=align DBG8↔DBG4
  (✅ dx=0.0095/dy=−0.0180,容差 0.05/0.08 未放宽);终帧亮区质心降级为观测量
  (实测再证摆动:同姿态跨场次 0.4541↔0.4852)。W4 加坑26 备注(本次未动)。
- **取证 SOP 补坑**:ESC 菜单污染截图(两张图质心相同才暴露)——截图先目检再判定;
  gradle 离线时 syncShaderPack 手工等价(include 检查 + cp)。坑26 关闭。
- 证据:docs/evidence/2026-08-30-w2w3-recalib/(manifest.sha256)。

## 未提交 · 2026-08-30 午后(色调管线 v2:参考对比 + 消融实验 + AgX/线性域)

> 用户实机验收阶段二(铁块高光 ✅)后反馈"画面色调有点别扭",要求对比借鉴
> iterationT,以拆解+消融找其观感本质。全程证据 docs/evidence/2026-08-30-tone-ablation/。

### 许可查证(借鉴的边界)
- iterationT 3.2.0:**包内无许可文件**,镜像站标注互相矛盾(GPLv3 vs 保留所有权利),
  MineBBS 标"转载",作者经 Bilibili/MGC 分发 → 默认保留所有权利;只读学习思想/公式,
  不搬代码不搬资源。SEUS 血统一说(SEUS EULA:仅限个人修改、禁止再分发)更加固此结论。
- E-LITE 5.1.1:Modrinth 标 LGPL-3.0-or-later(已核实)→ 可合法读码;用户已对比,
  观感不如 iterationT,仅辅助参考。
- 本项目纪律不变:只使用公开数学,0 行第三方 shader 代码。

### 根因(消融实验量化,stylemetrics 四指标)
1. **加法域错误**:M1 在 gamma 域把光照加进原版画面,final 再整体 pow(2.2)
   —— 光斑贡献非线性、色相偏移。
2. **ACES(Hill) 中间调反差硬**:夜景局部对比 0.298 vs iterationT 系 0.092;
   关掉 ACES 直通掉到 0.179 → 色调算子是反差主源。
3. **bloom 阈值在 gamma 域**(0.55):白天整片天空进 bloom → 泛白雾、玻璃死白。
4. **split-tone 全幅 1.0**:量化显示抬饱和 +0.06、蓝移 +0.08。

### 修复(全部实机标定)
- composite 入口统一线性化(colortex0 契约改线性,taclight_gbuffer.glsl 头注同步);
  final 不再 pow(2.2);albedo 线性域照明。
- **AgX 色调映射**(公开 minimals 数学自实现:前向矩阵列和=1、逆矩阵数值求逆、
  S 型对比多项式;EV 窗口 ±6 适配显示参照输入);TACLIGHT_TONEMAP=1 可切回 ACES。
- bloom 阈值 → 线性域 1.0 软阈 ±0.4;增益 0.35/0.55→0.18/0.30。
- split-tone 1.0→0.35;后置饱和 +0.05;暗角 0.78→0.85;颗粒 0.035→0.025;
  LIGHT_GAIN 1.0→2.2、BEAM_GAIN 1.4→0.5(线性域换算后同观感重校,fix1→fix2)。
- DBG4 诊断视图加 ×6+γ0.45 显示增益(线性域小值直读近乎全黑,量测会被 TACZ
  HUD 抢走最亮区)。

### 验证(实机)
- 昼夜反差对齐参考带:昼 CONTRAST 0.269→0.189(参考 0.176);夜 0.298→0.254
  (含光斑本体对比,参考夜景无光斑 0.092);夜亮度保持(SHADOW 0.785→0.788)。
- 光束几何(外观无关基准):DBG4 质心 cx=0.5264 ∈ [0.49,0.58] **PASS**,cy=0.502。
- 静帧对 meanDiff **2.46**(无闪烁);开关灯 A/B meanDiff 23.08 / changed 23%
  (与阶段二 24.4 同量级)。
- **W2(final 质心 0.4541)/W3 dx(-0.072) 数值 FAIL,已根因定位**:AgX 下玻璃按
  真实透射率变暗 + 软肩展宽,终帧质心测量随外观旋钮摆动 ±0.05+(消融 B1/B4=0.52
  实证)。标尺换基准(W2/W3 改以 DBG4 光束为几何基准)属验收口径变更,**留用户审批**,
  未擅自改。

### 附带发现
- **零重启换包**:sed oculus.properties 的 shaderPack= 行 + `!reload` 即可
  (Iris.reload() 重读 oculus.properties,日志实证)——本次实验全程零重启。
- 8/26 旧作 iterationT(taclight) fork 复活验证:SSBO 通路活着(霓虹绿锥正常),
  真实光路不可见(疑其 gbuffer.normalL 约定 → ndl 恒 0),支线待查;仅本地不入库。

## 未提交 · 2026-08-30 晨(阶段二:M1 GGX specular + LabPBR 解析;git 历史重建)

> 用户实机验收 F1-F6 通过后批准:①按里程碑重建 git 历史(8 笔,快照重建——共享
> 文件按主体里程碑归档);②开工阶段二(TACZ PBR 前置)。

### 阶段二实施(全部实机验证,证据 docs/evidence/2026-08-30-stage2-specular/)

- **查证先行**:LabPBR 1.3 标准(shaderlabs wiki)= R perceptual smoothness
  (roughness=(1-s)²)/ G:0-229 线性 F0(≤0.898)、230-255 金属、255=albedo 作 F0,
  标准明文允许"230-255 全按 255 简化";"只读 R+G 即 LabPBR-ready"。
  Oculus 1.8.0 jar 内确认 CustomTextureSamplerInterceptor(`specular` 采样器)。
  **TACZ 默认枪包自带 LabPBR _s/_n 贴图**(gun/uv/*.png 319 张)。
- **G-Buffer 契约升级(lib/taclight_gbuffer.glsl)**:colortex2.a = 0.3 占位 →
  LabPBR smoothness;新增 colortex5(RGBA8,r=F0 介电值 g=金属标志 b=smoothness
  副本供 final DBG7——final 读时 colortex2 已被 bloom 复用)。无 _s 数据回落旧默认
  (roughness 0.7 经 1-sqrt(0.7) 逆变换 / F0 0.04),原版材质观感与阶段一一致。
- **gbuffers_terrain/entities/hand**:DRAWBUFFERS 0123→01235 + `specular` 采样器
  解码(taclight_decode_specular);textured/water 等不写材质(粒子半透不参与,
  water 不写 5 → 水下地形材质保留,属正确行为)。
- **composite(M1)**:roughness = clamp((1-s)², 0.20, 1.0)——0.20 下限是能量护栏
  ((1-s)² 下 D 峰 ∝ 1/a⁴ 发散,0.20×SPEC_DAMP 0.35 把同轴镜心压在 knee 平台内);
  taclight_ggx f0 参数 float→vec3(金属彩色菲涅尔);金属 diffuse 清零(albedo 转 F0);
  调用点 vec3 化(首测 C7623 隐式收窄炸整包,坑14 同款)。
- **DBG7 材质审计视图**:首版写在 composite 被 final 的 beam/bloom 二次叠加污染
  (中心径向亮斑),移到 final 早退(colortex5 在 gbuffers 后无人写,干净)。
- **测试台 pack-dev/labpbr-rig/**:只含 _s 贴图不改原版 albedo(gen.ps1 确定性生成);
  stone_bricks 砖面 R200/G30 + 砖缝 R40/G12、smooth_stone R170/G20、iron_block
  R205/**G230(金属)**;A 通道写 255(ignored,防预乘)。

### 实机验证(雨天+停雨两轮)

- DBG7:砖面/砖缝逐 texel 对比清晰;iron 补丁白色(金属位);TACZ HK416D 整枪白色
  = 金属+高 smoothness(自带 _s 经 hand 路径正确解码),手臂正确回退
- final:iron 补丁 diffuse 抑制 + 镜面光泽,与砖墙形成物理正确材质对比;acceptance
  side PASS cx=0.5044;闪烁指数 0.22%(雨中静止);雨本底帧差 4.9 vs 开灯 6.6(雨主导)
- !bench avgFPS=375.1(见坑24,数值体系已变)

### 坑24(重大):options.txt 从未生效过

- 症状:labpbr-rig 资源包进不了 Reload 列表;日志 "Failed to load options"
  (NumberFormatException on key 值,OptionsKeyLwjgl3Fix)
- **根因链**:session.ps1 用 PS5.1 `Set-Content -Encoding UTF8` 重写 options.txt =
  写入 BOM → 首行 `version:3465` 版本标记被吃 → MC 把 options 当史前格式跑全量
  datafix → OptionsKeyLwjgl3Fix 对现代键名抛异常 → **整个 options 丢弃全默认**
- 影响面:**08-29 起所有 session 会话的 maxFps/vsync/pauseOnLostFocus/resourcePacks
  从未生效**;旧 bench 118.9 ≈ 默认 maxFps 120 上限(非真实性能上限);坑17 的
  pauseOnLostFocus 修复属无效药方(症状消失另有原因)
- 修复:session.ps1 改 `[IO.File]::WriteAllText(..., UTF8Encoding($false))` 无 BOM
  + version 标记守护;drive.ps1 postkey 键位表补数字键 1-9(热栏切换通道)

### git 历史重建(用户批准)

- 8 笔里程碑提交:v0.10.0(M1-M4)→ 调试环境 P0-P2 → 边界规格 §7 两大 bug 关断 →
  M5+缺陷分析 → 第一性原理 → F1-F5 → F6 → docs 收尾。工作树快照重建,共享文件按
  主体里程碑归档(各提交信息内有归属说明);gitignore 增补 logs/*.gz、build-log.txt、
  tools/.session/

## 未提交 · 2026-08-30 深夜(F6 移动闪烁:定位+修复,用户报告驱动)

> 用户指出移动闪烁是"光源移动引起的光晕忽亮忽暗",与雨丝无关。建移动光源调试
> 工具链 → 对照实验定位 → 修复 → 复测归零。证据 move-burst/(b1 修复前/b2 关 beam
> 对照/dbg4b 体积单独/b4 修复后,各 24 帧 @3.4fps)。

### 新工具

- `tools/drive.ps1` 新动作 `holdkey`(WM_KEYDOWN 持住 $Dur ms 再 KEYUP)+ `-Dur` 参数
  —— 玩家自动移动通道;`tools/capture-burst.ps1`(进程内循环 CopyFromScreen,
  ~3.4fps,BMP 落盘);`tools/move-flicker.js`(帧序列量化:光斑亮度时序/相邻帧
  ΔL/符号翻转/去趋势残差/逐像素时域σ,支持 BMP+PNG)

### 定位过程(三连对照,唯一嫌疑收敛到 bloom 提取)

- 静止段 ΔL=0.00 恒定 → IGN 抖动图案不随时间变,静止无噪声(排除"抖动本身")
- B1(完整管线)移动段 ΔL=+0.8/+2.4/**−4.5**/+3.1/**−2.0**:锯齿,3 次符号翻转
  —— **问题确认,±1~2% 光斑亮度忽亮忽暗**
- B2(关 beam)移动段单调 → DBG4b(体积单独成像,握手流程)移动段**单调平滑**
  → beam 积分与 scene 都平滑,**锯齿诞生在合成环节**
- **根因 F6**:composite2 的 bloom 提取 `sceneAt = scene + beam`,同轴视角中央
  luma≈0.63 恰落在软阈值(TACLIGHT_BLOOM_TH±0.15 = 0.40~0.70)中段,beam 的
  平滑变化被 smoothstep 非线性放大成提取权重跳变 → 忽亮忽暗
- 修复:`sceneAt` 只取 colortex0(beam 不进 bloom;beam 本身即辉光观感,
  final 直加,无需二次 bloom)

### 复测(B4)

- 移动段 ΔL=+2.1/+2.9/+1.1/+7.4/+2.9 **单调,零翻转**(靠近墙变亮=物理正确);
  静止段恒定
- 副产品:中央 sat220 9.9%→0.3%(bloom 不再给光斑叠白),**墙面砖缝/衰减层次
  完全清晰,fp_final_bloomfix.png 为当前最佳观感**;acceptance side PASS(0.5064)
- 抖动幅度实验(jitter 0.9→0.35,B3)无改善 → 佐证非采样噪声,已回退保持 0.9

### 实机坑位 23

- **WM_KEYUP 的 lParam=0 被 GLFW 当"重复按下"→ 移动键永久粘滞**(玩家顶墙走,
  传送回起点 1s 内又走回;连拍全程静止画面,曾误判"goto 失效")。KEYUP 必须
  lParam=0xC0000000(bit30|31)。F5 等切换键不受 repeat 影响,故 postkey 一直
  "看似正常"。另:goto 与 diag 同帧消费时 diag 抓到传送前一瞬位置,机位判定
  需轮询握手(连续两次一致才可信)

## 未提交 · 2026-08-30(F1–F5 修复实施 + 实机验收,用户批准后执行)

### 修复内容(全部实机验证,证据 docs/evidence/2026-08-30-f1-f5-fix/ + manifest)

- **F1 雨不再破坏照明**:`gbuffers_weather` → `DRAWBUFFERS:0` + alpha<0.1 discard;
  **M1 表面查找与 M3 march 终点统一切 depthtex1**(实心表面语义,半透不再当被照面/
  march 终点)。实机:雨天墙面光斑稳定,雨丝无亮纹;雨/晴静止帧差 2.0 vs 0.01-1.6
  (残差=雨丝自身运动)
- **F2 自体胶囊豁免(SSO 假遮挡根治)**:cookie 槽语义扩展(SSBO 布局 96B 不变):
  Java 侧 `selfCapped()` 每灯写 (灯→胶囊中心偏移.xyz, 半径 0.45),胶囊=玩家眼位
  -0.55y、竖直半高 1.05(GLSL 常量 `TACLIGHT_SELF_CAP_HALF`);GLSL 侧两级豁免:
  ①采样点在胶囊内不计(身体切锥),②**遮挡者本体在胶囊内不计**(TP 下身体与
  远墙屏幕重叠的假消光——DBG3 全屏成像定位,occView 与 sceneDist 同源零额外成本)。
  本地手持/枪灯/远程灯全部携带。实机:TP 身后视角墙面光斑连续,身体不切光
- **F3 能量重标定**:radius 默认 56→18(室内档;atten(8m) 0.94→0.58,√亮度耦合
  不变;**run/config/taclight-client.toml 已同步——Forge 默认值改动不覆盖已有 toml**);
  spec 项 ×`TACLIGHT_SPEC_DAMP` 0.35(压 GGX 峰值×intensity 的能量尖峰)。
  实机:sat220 面积 24.2%→(见 F4 延伸)
- **F4 M3 近场正则 + 同轴爆炸治理**:`taclight_attenuation` 输入 `max(d, 0.75)`;
  TP 枪灯 fallback 改锚玩家眼+玩家视线(原锚相机)。**实机新发现(R6):FP 同轴视角
  下 HG 相位前向峰值 0.609(g=0.55)×32 步全程贴轴 → inscattering 积分 ≈1.06,
  体积束单独就把中央洗白(DBG4 colortex4 直读证实),这才是过曝主因;
  `TACLIGHT_BEAM_GAIN` 8.0→1.4**。实机:sat220 24.2%(旧 GLSL)→ 17.2%(gain2)
  → **12.8%(gain1.4),砖缝纹理/衰减层次/锥形边界全可见**
- **F5 噪声软化**:SSO 16→24 步、消光 2.4→1.2;体积 24→32 步。实机:静止相邻帧差
  ~2.0/255(≈雨丝自然运动),无噪闪

### 实机排障沉淀(工具链坑位 19-22)

- **坑 19:GLSL 改动必须 `gradlew syncShaderPack` 后再 `!reload`**——游戏读
  `run/shaderpacks/taclight-shaders-dev` 副本,只改 pack/ 等于白改(本轮 F3 首测
  24.2% 不变即此因)
- **坑 20:brigadier `string()` 无引号模式字符集同 `word()`,`@` 截断参数**——
  `cam goto "wall@front"` 机位名必须带引号(注释声称 string() 支持 @ 是错的)
- **坑 21:mixin `@Shadow` 不能 shadow 继承成员**——`PlayerSynchedDataMixin` 的
  `getEntityData()` 声明在 Entity,Player 字节码无此方法 → "was not located"启动崩;
  改 `((Entity)(Object)this).getEntityData()` cast 调用(M5 实机验收欠债补上)
- **坑 22:本机 F5 循环顺序实测反常**(FP→正面→身后),TP 机位判定以 diag
  `cam=(…,z)` 为准:身后视角 z≈玩家+4

### 契约与文档

- UploaderSemanticContract 24→29 项(withSelfCapsule/selfCapped/cookie 语义)
- AllContracts 287 项 ALL PASS;证据包 24 图 + SHA-256 manifest

## 未提交 · 2026-08-30(第一性原理分析轮)

- **新报告 docs/聚光灯手电第一性原理与技术路线-0830.md**:渲染方程把手电拆成四子问题
  (锥形 diffuse / specular / 可移动阴影 / 体积散射),"1/2/4 是算术,3 是架构";
  AAA 正解 = per-frame spot shadow map,Iris shadow pass 被锁死太阳方向 = shaderpack 天花板;
  SSO 是降级方案,应按降级方案做干净而非当正解交付
- **Radiance / Caustica 查证(读 README)**:两者均为"重写渲染器 + 硬件 RT"路线
  (Caustica=Vulkan 路径追踪接管世界渲染,MC 26.2 Fabric;Radiance=C++/Vulkan 替换 OpenGL,alpha),
  README 均无动态点光源描述 → 对 1.20.1 Forge + TACZ 不可移植,佐证天花板判断;
  结论:**不换路线**,留在 Iris/Oculus composite,按四子问题补齐(下一步:specular + LabPBR 解析服务 TACZ)

## 未提交 · 2026-08-30(缺陷分析轮 + M5 多人同步)

### M5 多人同步(提案 → 实施,契约全绿)

- **状态真源上服务端**:Player `SynchedEntityData` 两个 boolean(手持/枪灯),原版自动同步;
  mixin `PlayerSynchedDataMixin` 入公共 mixins 列表且旁路 TaCZ 门控(`TacLightMixinPlugin`)
- **网络通道** `taclight:main`:`SetLightC2S`(L 键/枪灯探针状态变化上报)+ `SyncLightS2C`
  (命令改灯后本人客户端跟随真源)
- **命令**:`/taclight light <on|off|toggle> [player]` + `light status`(RCON/控制台可驱动)
- **收集端**:`ClientSpotlightUploader.collectRemoteLights` 遍历 `level.players()`,距离剔除
  (config `remoteLightMaxDist`=48)+ 就近上限(config `remoteLightMaxCount`=8,SSBO 硬顶 8);
  远程手持灯=玩家眼位+`handheldOffset`(与本地第三人称同一条数学,契约钉死);远程枪灯眼位近似
- **顺带修复 Freecam 锚定陷阱**:`fp = isFirstPerson && cam.getEntity() == mc.player`(旁观方案 §2)
- **多人测试环境**:LAN 双实例拓扑(`tools/mp-session.ps1`:A 复用 session.ps1 → /publish →
  B `runClientObserver` 自动入服);`taclightMpSetup`(观察者低配 options/oculus 预置)+
  `syncShaderPackObserver` 双目录同步;`tools/rcon.ps1`(Source RCON 最小客户端,备用)。
  **实测:dev 专用服不可行(oculus/embeddium 纯客户端 mod 在 runtimeOnly → 服务端 dist 崩;
  FG classpath 剔除方案引入全量 jar 重复也失败),勿再尝试**
- 新契约 ×2:`MultiLightCollectorContract`(11)、`PlayerLightSyncContract`(10,含
  `Bootstrap.bootStrap()` 离线引导——`EntityDataSerializers` 静态初始化依赖注册表,离线 JVM 必须手动引导)

### 0830 显示缺陷根因分析(详见 docs/聚光灯显示缺陷分析与实现方案-0830.md)

- **左右不对称根因 = 第三人称 SSO 自体阴影**(灯锚头侧偏右 0.22m,身体把自己锥的左下切掉,
  硬阴影边贴身体轮廓;第一人称有 vis=1 豁免,第三人称没有——设计缺口)
- **过曝平台根因 = 半径 56 的反平方在室内尺度无衰减**(atten(8m)≈0.94)×intensity 6×spec+
  体积+bloom 推平顶(截图 10% 面积饱和 224,纹理全毁)
- **移动闪烁主因 = 雨丝逐帧覆写 G-Buffer**(`gbuffers_weather` DRAWBUFFERS:0123,辅助附件
  无混合整块覆写;原版 1.20.1 雨写深度,与该文件头注释前提相反)
- **M3 体积束缺陷**:表面衰减公式直接进体积积分(近场 1/d² 未正则)、TP 枪灯 fallback 锚相机
- 修复路线图 F1-F5 待批准实施(F1 雨裁剪 → F2 自体豁免 → F3 能量重标定 → F4 近场正则 → F5 抖动)

### 参考实现拆解(docs/聚光灯显示缺陷分析与实现方案-0830.md §3)

- DynamicLightsReforged(已切 `1.20` 分支)= LambDynamicLights 家族:动态光改写 lightmap 坐标
  (线性衰减/全向/默认无遮挡/逐 tick 区块重建)
- HandheldMoon = 其上的体素级角度遮罩 + `level.clip` 逐块遮挡 + **2D 屏幕中心提亮 post pass
  (即"光锥"本体)**;多人靠物品 NBT 同步天生可用。两项目均未做真 3D 聚光

## v0.10.0(路线 S 完整版 —— M1 闭环 + M3 体积光 + M2 风格层 + M4 发布物料)

> 本版本把里程碑推进到"可发布成品":体积光束 + 风格层一次性上线(用户裁决:
> 直接完成到最终成果阶段验收,不再逐里程碑中途验收)。

### M1 闭环 · 实机热修 10–13(2026-08-29 用户实测驱动)

- **热修 10·ndl 符号反转(五轮"无白光"唯一根因)**:主循环把"灯→片元"向量(锥判定轴,正确)直接当光照方向 `l` 用,而光照约定 `l` 必须"表面→灯"——同轴灯下 `dot(n,l)≡-1` → 背面门拒绝全部像素 → 辐射恒零;修复 `vec3 l = -lf`(K 绿锥一直正常,因锥判定不需要 ndl)
- **热修 11·门探针判读污染事故(方法论教训)**:曾据门探针得出"Embeddium 地形属性法线不可信"并转导数法线——该结论出自 ndl 符号 bug 时代(惩罚正确法线、奖励背面朝向),判读被污染;作废旧结论,法线回归属性路径(`taclight_decode_normal`)+ 构造性朝向校正;实机验收:法线审计盒逐面变色 ✓
- **热修 12·导数法线 = 黑边根因 + 遮挡按灯-相机几何分流**:①dFdx/dFdy 按 2×2 像素四边形差分,混合四边形(近草+远地)输出两表面切向混合的垃圾 → ndl=0 黑边;且设备深度差随距离二次缩小,固定阈值必漏检 → 轮廓检测方案整体退役。②第一人称灯锚眼睛=同轴光,"可见即无遮挡"是几何事实 → vis=1;第三人称灯与相机分离存在真遮挡 → 走 SSO。教训:第一人称的几何结论不可外推到第三人称
- **热修 12b·430 core 严格隐式转换**:vec4 表达式赋 vec3 直接编译失败(C7011)→ Iris 静默禁用整包("Failed to create shader rendering pipeline"),排查入口 `run/logs/latest.log` 搜 `GlShader`
- **热修 13·SSO 深度域缺陷(实机:透光仍在)**:遮挡判定此前在非线性设备深度域做,bias(1.5e-3)在 5m 外比半格厚真遮挡的设备深度信号(~0.001)还大,必漏检——与热修 11 删除轮廓检测同款陷阱("设备深度差随距离二次缩小");修复 = 比较搬回视图空间线性米数(世界尺度 bias 6–10cm);配套消光曲线改陡(挡 1/4 步数剩 ~16%)、步进 12→16
- **M1 收尾·植被半透挡光**:草/花/作物/藤蔓(软植被 0.25)与树叶(0.6)经 `block.properties` 分类 + `mc_Entity` 写入 colortex3.a,SSO 按遮挡者材质系数消光——修复满草场景"草亮地黑"(镂空植被被当实心全挡,地面像素射线全部误判遮挡);G-Buffer 契约同步:colortex3.a = 遮挡系数(1.0 实心)
- **M1 收尾·贴灯豁免**(`TACLIGHT_SSO_SELF_FREE=0.6`):距灯 <0.6 格的遮挡者(自身体/枪身)不参与 SSO——其阴影半影物理上全弥散;消第三人称脚下暗环
- **亮度总增益减半**(用户实测过曝):`TACLIGHT_LIGHT_GAIN` 2.0→1.0
- 调试条带 `TACLIGHT_DBG_STRIP` 关闭(法线审计盒验收通过)

### M3 · 体积光束(提前于 M2 实施,与 M2 同版本交付)

- `composite1`(全屏 24 步 + IGN 抖动):沿像素视线 raymarch,锥内采样点按 HG 相位 × D6 衰减 × 密度(`vlParams.y` = 配置 beamDensity)累加散射;深度遮挡走 depthtex1 视图空间线性比较(热修 13 同款);贴灯豁免与 SSO 共用半径——自身体不切光束
- **HG 各向异性接线**:`SpotlightData.BEAM_ANISOTROPY=0.55`(vlParams.x,此前硬编码 0=各向同性雾球无方向感)——前向散射强,光束沿照射方向最亮(手电束感,参照 Handheld Moon 观感)
- 灯数据预取(view 空间)后步进内层只做数学;性能旋钮 `TACLIGHT_VL_STEPS`/`TACLIGHT_BEAM_GAIN`

### M2 · 风格层(doc06 §2.9,bloom 经 §8.3 审批)

- **两级 bloom**(doc06 §8.3 最简实现):composite2 从 scene+beam 提取亮部(软阈值 0.55)按 2×2 块降采样 → colortex5(内容半分辨率);composite3 按 1/4 密度 5tap 十字模糊 → colortex6;final 中两级 LINEAR 上采样叠加
- **自适应曝光**:composite2 稀疏 8×3 网格全屏平均亮度 → 目标亮度(0.12,夜景基调)反比 → 帧率无关眼适应(~0.3s)→ colortex7 跨帧 history(`clear=false`);暗场景自动提亮、光斑聚焦不过曝
- **ACES 色调映射**(Hill 拟合,ACES 官方色度常量,公开数学):gamma→线性→曝光→ACES→显示 gamma;高光滚降防平白
- **split-tone**(阴影冷/高光暖)+ **暗角**(四角 0.78)+ **胶片颗粒**(IGN,暗部偏重);全部旋钮集中在 final.fsh/composite2.fsh #define 区
- HDR 链就绪:colortex0/4/5/6 升 RGBA16(Iris 注释常量声明,colortex0 从 8bit 升级以承载 >1 动态范围)

### M4 · 发布物料

- **打包任务** `gradlew packShaderZip`:shaders/ + pack.png 图标 → `build/distributions/taclight-shaders-0.10.0.zip`(版本号单一来源 gradle.properties,与模组 jar 一致)
- **pack.png** 图标(256×256,夜色手电光锥)
- **ShaderPackDiag 修复**:自检此前查路线 P 时代的注入文件 `shaders/Lib/taclight_lights.glsl`,对自研包必然误报"无 TacLight 注入";改查自研包 `shaders/shaders.properties` 的 `TACLIGHT_PATCH_BEGIN` 标记;文案"派生包"→"配套包"(M1 遗留项结清)
- pack/README.md 重写(安装/配置联动/风格旋钮表/已知边界);版本 0.9.0→0.10.0
- 验收与证据:M2/M3 量化验收(风格目标板、性能表)与 3×5 场景证据矩阵转入用户成品验收轮收集

## v0.9.0(路线 S 开工 —— 自研光影包启动)

- **审批落地**:docs/06 草案 v0.1 → 已批准 v1.0;D1–D8 全部按建议批准;三项未决问题同日裁决(6.1 坐标语义→执行 doc06 版 world 上传;6.3 bloom→纳入 M2;包位置→内包 `pack/`,M4 抽独立仓库),完整记录见 docs/06 §8
- **6.1 坐标语义整改(行为变更,三处对齐)**:`ClientSpotlightUploader.toSpot` 不再减眼位 —— posRadius.xyz 恢复传 **world 坐标**;scene-relative 转换移交配套包 GLSL 侧(`pack/shaders/lib/taclight_common.glsl`,`taclight_world_to_scene → taclight_scene_to_view` 两级唯一入口);`SpotlightBufferLayout` javadoc 同步标注。⚠️ 旧派生包 `iterationT 3.2.0 (taclight)` 冻结于 ≤0.8.4 scene-relative 契约,与 0.9.0+ 模组组合会"灯随镜头漂移",属预期废弃路径
- **新增契约**:`UploaderSemanticContract`(9 断言:world 直传逐位校验/无眼位减法/纯函数可复现),注册进 AllContracts;上传器参数提取为可注入 `LightParams`(测试零 MC 依赖)
- **M0 骨架包**(`taclight/pack/shaders/`,全自写 0 行照搬):gbuffers ×11 程序对(basic/textured/textured_lit/terrain/entities/hand/skybasic/skytextured/water/weather/beaconbeam,#version 120 最小直通+光图);composite(#version 430,binding=7 SSBO 只读消费 + K 键调试绿锥);final 直通(M2 风格层接入点);shaders.properties(铁律2:不声明 bufferObject)
- **新增任务**:`gradlew syncShaderPack`(pack/shaders → run/shaderpacks/taclight-shaders-dev/shaders)
- **提前清理**:LightBuffer.upload() 移除 SLOT PROBE(binding 0/1/8 冗余绑定);FLAG_TIMING_PROBE 头位保留至 M1 门控探针落地再收编(docs/06 §8.2)
- 版本统一:**TacLightMod.VERSION 与 mod_version 均 = 0.9.0**(D8)
- **M0 热修(首次实机加载,2026-08-27)**:composite.fsh 在 `#version 430 core` 下使用 varying/gl_FragData 触发 NVIDIA C5514/C7616 编译失败 → Iris 禁用整包;改为 fsh=`430 core`+`in`/`layout(location=0) out`、vsh=`330 compatibility` 的混搭 —— 与路线 P 派生包在本机验证过的组合一致。另在 shaders.properties 放入 `TACLIGHT_PATCH_BEGIN` 标记使 ShaderPackDiag 能识别自研包(文案"派生包"措辞系 v0.8.3 遗留,M1 再改"配套包")。证据:run/logs/latest.log 原报错行消除(待复验绿锥)
- **M0 热修 2·渲染帧同步(实机:转视角灯光拖拽)**:根因 = SSBO 上传挂在 ClientTick(20Hz),灯位滞后相机最多 50ms;迁移到 RenderLevelStageEvent.AFTER_LEVEL(渲染帧级,相机本帧终值,先于 Iris composite);退出世界时主动清空 SSBO。社区同型案例交叉验证(手持光滞后 = 数据未按帧更新):shaderLABS wiki / r/OptiFine / Chocapic13 论坛帖
- **M0 热修 3·D6 衰减提前落地(实机:照明距离不足)**:绿锥预览接入平滑反平方 `taclight_attenuation = 1/(1+k·d²)`(k=2/r²,atten(r)=0 长尾,分母无奇点),替换旧 `(1-d/r)` 线性淡出(半半径仅剩 50% 亮度 = 视觉半径提前死亡);radius 默认 24→40、上限 64→96(TacLightConfig + run/config 同步);M1 表面照明复用同一函数
- **M0 热修 4·亮度-距离 √ 耦合(实机:亮度高但照不远)**:反平方律推论 d ∝ √I —— `有效半径 = radius × √(intensity/6.0)` 钳制 ≤96;radius 语义改为"基准半径@亮度6",默认 40→56;未来挡位设计 = 只改亮度,照距自动 √ 缩放(doc06 §8.7);契约升级 12 断言(参考/×2/钳制/×0.05 四个标定点)
- **M0 热修 5·第三人称世界空间锚定**:手持灯非第一人称下锚玩家眼睛+玩家视线(原锚相机会"灯浮在相机上");第一人称行为不变。参考实现 Handheld Moon(ARR,只参考行为)分析入 doc06 §8.7:地面亮斑=M1、可见光锥=M3(已批, vlParams 已预留)、他人手持灯= v2 可纯客户端实现
- **M0 热修 6·近场软肩压缩(实机:近场过曝糊死)**:诊断 = 反平方归一化近场平台(0.25r 处仍 83%)+ 无高光压缩 → 削顶纯绿,属亮度曲线缺陷(非调试色问题);修复 = 软肩 `x/(1+G·x)`(G=2.0,远场≈线性不动、近场压向 1/G,近远比 5.3:1→2.6:1)+ 合成增益 1.5→1.8;可调旋钮 `TACLIGHT_KNEE_GAIN`;v0.8.2 同型软膝行为语义、公式重写,M1 复用
- **M1 开发热修 7·include 行尾注释炸整包(实机:整包禁用、K 键无光)**:`#include "/lib/…" // 注释` 被 Iris 按整行解析为路径 → `InvalidPathException: Illegal char <">` → 包加载失败回退原版(区别于编译错误:这次连 pipeline 都建不起来);规则 = **include 指令必须独占一行**;已加 `checkShaderIncludes` gradle 契约守护(syncShaderPack 强制前置)
- **M1 开发热修 8·"无照明"结案 + 双面法线**:门探针四象限判读证明照明系统自热修 7 后已正确工作(门控/几何全对,骷髅白斑=全门通过)——"没有照明"是场景误判:平射时地面入射角极浅(ndl≈0.1,物理正确)+ savanna 草丛交叉面片背面法线背光;真正缺陷 = 植被单面法线,修复 = `gl_FrontFacing` 背面翻转(7 个 gbuffers),草叶正反两面正确受光;门探针/四象限作为 M1 常备诊断工具保留(开关在 composite.fsh)
- **M1 开发热修 9·地形法线数据源不可信 → 导数法线**:双面法线后草丛面片亮了、地形顶面仍暗(俯射近距复现),门探针+实体对照组实锤 = **Embeddium 区块路径的 gl_Normal 方向错误**(实体路径同代码正确);修复 = composite 改用深度重建位置的 `cross(dFdx,dFdy)` 求面法线——方向构造性朝向相机,方块平直面更精确,实体低多边形棱面感 M1 接受;colortex1 顶点法线继续写出备用
- 证据:`gradlew-java17.cmd compileJava taclightContracts --offline` → BUILD SUCCESSFUL,契约 39/39 通过(Layout 16 + UploaderSemantic 12 + MuzzlePoseMath 11)

## v0.8.4(坐标约定修复——光终于画出来了!)
- **根因**:Iris/Oculus 的 composite 后处理 pass 里 `gbufferModelView` 是**纯旋转矩阵(无平移)**——坐标体系是"场景相对坐标"(world − cameraPosition);我们按"完整视图矩阵"换算,导致灯被算到 ~275 格外 → `dist>radius` 全部拒绝 → **光从未渲染过**(用户看到的"白团"= iterationT 内置传统手持光,HELDLIGHT_MODE=0)
- **修复**:`lightView = mat3(gbufferModelView) * posRadius`(纯旋转,场景相对→视图);surface/specular/beam 三处同步(与旧项目 tarkovline 的全程 scene-relative 约定一致)
- **端到端验证**:探针 `reserved=0x22fb` 全位通过;实机截图确认绿色锥形光 + 距离衰减可见
- 新增分阶段探针位与 GPU 回读(纠正 cookie 读回偏移 80→96 的 bug)

## v0.8.3(选包自检 + K 键反馈)
- **症状**:用户按 K 无反应、光仍为"无衰减白团" → 调查:代码无误,最可能是**选中的是原包迭代T而非派生包**,SSBO 通道根本没运行
- **新增开机自检**(ShaderPackDiag):读 config/oculus.properties + 检查活动包里是否有 TACLIGHT_PATCH_BEGIN 标记 → 每 5 秒检测,状态变化时聊天栏+日志提示(未激活/原包/派生包/无法判定)
- **K 键聊天反馈**:切换时聊天栏提示 ON/OFF;若手电筒关闭自动开启(便于观察绿锥)
- 主类版本日志 v0.8.3(确认运行构建)

## v0.8.2(真实感 + 通道可辨识)
- **修复削顶**:注入光加 filmic soft-knee(`taclight_knee = e/(1+e)`,增益 2.5)——此前强度 6 直接叠加导致锥形区域内近处远处全部钳到最亮,肉眼看不到距离衰减(用户反馈"无论多远亮度一样"的根因,也是"假"的主要来源)
- **K 键霓虹调试模式**:GLSL 输出纯绿锥形光(无 albedo/AO),与光影包内置手电一眼区分;契约新增 FLAG_DEBUG=2 断言(15 项)
- 光束/高光同样套用 soft-knee,消除白团
- 教学手册新增"怎么分辨你看到的是哪条通道"

## v0.8.1(热修)
- **修复**:taclight_specular 的 f0 参数 vec4→float(与 iterationT Material.f0/SpecularGGX 一致);此前导致 composite5 编译失败→整个光影管线关闭(也是 SSBO 探针全线归零的根因)
- **SSBO 通道端到端验证通过**:E2E 探针回读 reserved=0x1(表面 pass 触发+闭环))
- docs/04 结论修正:外部 SSBO 绑定可用的(推翻先前"Oculus 转换层阻断"的假设)

## v0.8.0
- **B 计划**:GunItemLightProviderMixin 给 ModernKineticGunItem 注入 IrisItemLightProvider 接口(官方 API 判定战术枪灯 → 光强 15),枪灯经 G 通道点亮 iterationT 内置 FLASHLIGHT
- 补丁工具修复:patchIterationT inputs 声明(消除 up-to-date 误判);PackPatcher 幂等改按内容判断
- docs/04-SSBO绑定调查记录.md(完整证据链与重启路径)
- 契约 29/29

## v0.7.0
- /taclight kit 命令(一键发放验收套件:手电筒+HK416D+战术枪灯)
- 正式发布构建(clean build 终验)

## v0.7.0-dev
- 新增 Forge 客户端配置 taclight-client.toml(半径/强度/内外锥角/光束密度/枪灯倍率)
- 发布文档:本文件、RELEASE.md、docs/03-实机验收清单.md

## v0.6.0-dev — V4 枪口精确姿态
- BeamRendererMixin(双路径)捕获 TaCZ 激光渲染矩阵 → 视图空间姿态
- MixinConfigPlugin 软依赖门控;MuzzlePoseMath(纯数学,契约 11 项)
- gunpack 模型骨名改为 laser_beam(光束渲染+捕获两用)
- 契约总数 29/29

## v0.5.0-dev — V3-p2 体积光束 + 高光
- taclight_beam(16 步 raymarch + 光束遮挡,composite.fsh)
- taclight_specular(GGX,composite5.fsh);SpotlightData.spotBeam

## v0.4.0-dev — V3-p1 SSBO 通道
- SSBO binding 7(std430:16B 头 + 96B/灯,兼容 irlite ABI)
- 表面锥光(smoothstep 软边/距离衰减/screen-space 遮挡/Burley)
- PackPatcherTool:锚点唯一性 + SHA-512 + marker 幂等;iterationT 3.2.0 派生包

## v0.3.0-dev — V2 TaCZ 枪挂灯
- gunpack taclight:gun_light(laser 类;官方 ResourceManager.EXTRA_ENTRIES 注册)
- 探针识别(显式优先/内置兜底)+ GunLaserReader 契约 6 项

## v0.2.0-dev — V1 手电筒物品
- FlashlightItem + FlashlightItemIris(IrisItemLightProvider)
- L 键开关(本地状态);中英语言;16x16 贴图

## v0.1.0 — V0 工程骨架
- Forge 47.1.3 + MC 1.20.1 + Java 17;离线构建(复用 1.67GB Gradle 缓存)
